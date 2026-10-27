package io.freemytv.remote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The only place that asks a TV for everything. It happens when the person taps Refresh (and once after pairing), never
 * when the app opens. Changes made on the phone go out first, so a refresh cannot undo them. Every job takes the TV it was
 * asked about (Tv.Session) when it starts, so switching to another TV meanwhile changes nothing for it.
 */
final class Sync {
    private Sync() {}

    static final String UNPAIRED = "unpaired";
    static final String CANT_REACH = "Can't reach the TV. Is it on, and on the same Wi-Fi? (2.4 and 5 GHz can be kept apart by the router.)";

    /** What to tell the person: a network problem in plain words, or the TV's own reason. */
    static String explain(Exception e) {
        if (e instanceof java.net.SocketException || e instanceof java.net.SocketTimeoutException || e instanceof java.net.UnknownHostException) return CANT_REACH;
        return e.getMessage() == null ? CANT_REACH : e.getMessage();
    }

    interface Done { void done(String error); }   // error == null: all good
    interface IconListener { void icon(String key, String pkg); }

    /** Heard when an icon arrives after the lists (the Apps screen puts it on its card, if it is showing that TV). */
    static volatile IconListener onIcon;

    private static final Object LOCK = new Object();
    private static final ExecutorService WRITES = Executors.newSingleThreadExecutor();   // changes reach the TV in the order they were made
    private static final ExecutorService ICONS = Executors.newFixedThreadPool(4);
    private static volatile String refreshingKey;   // the TV being refreshed, or null

    /** Is this TV being refreshed now? */
    static boolean refreshing(String key) { return key != null && key.equals(refreshingKey); }

    /** Sends the changes made here to the TV in use, in the background. A failed send stays queued for the next try. */
    static void push(Done done) {
        final Tv.Session s = Tv.current();
        WRITES.execute(() -> {
            String error;
            synchronized (LOCK) {
                error = pushNow(s);
            }
            if (done != null) done.done(error);
        });
    }

    private static String pushNow(Tv.Session s) {
        if (Cache.pendingCount(s.key) == 0) return null;
        JSONObject p = Cache.pending(s.key);
        try {
            Tv.Reply r = s.post("/settings", p);
            if (r.status == 401) return UNPAIRED;
            if (r.status != 200) return r.error();
            Cache.sent(s.key, p);
            return null;
        } catch (Exception e) {
            return explain(e);
        }
    }

    /** Pushes what we changed, then asks the TV in use for its apps and settings and keeps them. Calls `done` on a background thread. */
    static void refresh(Done done) {
        final Tv.Session s = Tv.current();
        if (s.key == null || refreshingKey != null) return;
        refreshingKey = s.key;
        new Thread(() -> {
            String error = null;
            List<String> missing = new ArrayList<>();
            synchronized (LOCK) {
                try {
                    String pushed = pushNow(s);
                    if (UNPAIRED.equals(pushed)) throw new IllegalStateException(UNPAIRED);
                    Tv.Reply settingsReply = s.get("/settings");
                    if (settingsReply.status == 401) throw new IllegalStateException(UNPAIRED);
                    if (settingsReply.status != 200) throw new java.io.IOException(settingsReply.error());
                    Tv.Reply appsReply = s.get("/apps");
                    if (appsReply.status == 401) throw new IllegalStateException(UNPAIRED);
                    if (appsReply.status != 200) throw new java.io.IOException(appsReply.error());
                    JSONObject settings = settingsReply.json(), apps = appsReply.json();
                    Cache.save(s.key, apps, settings);
                    // Changes that could not be sent stay on top of what the TV said.
                    JSONObject p = Cache.pending(s.key);
                    for (String section : new String[] {"values", "keys"}) {
                        JSONObject o = p.optJSONObject(section);
                        for (java.util.Iterator<String> it = o == null ? java.util.Collections.<String>emptyIterator() : o.keys(); it.hasNext(); ) {
                            String k = it.next();
                            Cache.change(s.key, section, k, o.get(k));
                        }
                    }
                    JSONArray list = apps.getJSONArray("apps");
                    for (int i = 0; i < list.length(); i++) {
                        String pkg = list.getJSONObject(i).getString("pkg");
                        if (!Cache.hasIcon(s.key, pkg)) missing.add(pkg);
                    }
                } catch (IllegalStateException e) {
                    error = e.getMessage();
                } catch (Exception e) {
                    error = explain(e);
                }
            }
            refreshingKey = null;
            if (done != null) done.done(error);
            if (error == null) fetchIcons(s, missing);
        }).start();
    }

    private static void fetchIcons(Tv.Session s, List<String> packages) {
        for (String pkg : packages) {
            ICONS.execute(() -> {
                try {
                    Tv.Reply r = s.get("/icon?pkg=" + pkg);
                    if (r.status == 200) {
                        Cache.saveIcon(s.key, pkg, r.body);
                        IconListener l = onIcon;
                        if (l != null) l.icon(s.key, pkg);
                    }
                } catch (Exception ignored) {
                }
            });
        }
    }

    // MARK: - Keys and launches (they go straight to the TV, one after the other)

    private static final ExecutorService COMMANDS = Executors.newSingleThreadExecutor();

    interface Result { void result(String error); }

    /** Sends a command to the TV in use (the one it was in use when asked, even if it is queued while the person switches). */
    static void command(String path, JSONObject body, Result result) {
        final Tv.Session s = Tv.current();
        COMMANDS.execute(() -> {
            String error = null;
            try {
                Tv.Reply r = s.post(path, body);
                if (r.status == 401) error = UNPAIRED;
                else if (r.status != 200) error = r.error();
            } catch (Exception e) {
                error = explain(e);
            }
            if (result != null) result.result(error);
        });
    }
}
