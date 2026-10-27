package io.freemytv.remote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import io.freemytv.proto.Proto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The paired TVs, which one is in use, and signed requests to them (see Proto). There can be several TVs; the fields
 * below (host, name, id, token...) are always those of the one in use, which is what the screens show and talk to. A job
 * that may outlast a switch (Sync) takes a Session first, so it keeps talking to the TV it was asked about.
 * Call the requests from a background thread.
 */
final class Tv {
    private Tv() {}

    /** One paired TV. `key` is the id this phone has on it (random, so different on every TV). */
    static final class Entry {
        String key, label, name, host, svc, id, token;
        int port;

        /** What the person calls it (they can rename it), else what the TV calls itself. */
        String title() {
            if (label != null && !label.isEmpty()) return label;
            return name != null && !name.isEmpty() ? name : "TV";
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("key", key).put("label", label == null ? "" : label).put("name", name == null ? "" : name)
                    .put("host", host).put("port", port).put("svc", svc == null ? "" : svc).put("id", id).put("token", token);
        }

        static Entry from(JSONObject o) {
            Entry e = new Entry();
            e.id = o.optString("id");
            e.key = o.optString("key", e.id);
            e.label = o.optString("label");
            e.name = o.optString("name");
            e.host = o.optString("host", null);
            e.port = o.optInt("port", Proto.PORT);
            e.svc = o.optString("svc").isEmpty() ? null : o.optString("svc");
            e.token = o.optString("token", null);
            return e.id.isEmpty() || e.token == null || e.host == null ? null : e;
        }
    }

    // The TV in use.
    static String host, name, label, id, token, svc;
    static int port;

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static String activeKey;
    private static long last;

    /** No reply from a TV is bigger than this (a bad server can't fill the phone's memory). */
    private static final int MAX_REPLY = 4 * 1024 * 1024;

    // MARK: - The list

    static synchronized void load(Context c) {
        ENTRIES.clear();
        SharedPreferences sp = c.getSharedPreferences("tvs", Context.MODE_PRIVATE);
        try {
            JSONArray list = new JSONArray(sp.getString("list", "[]"));
            for (int i = 0; i < list.length(); i++) {
                Entry e = Entry.from(list.getJSONObject(i));
                if (e != null) ENTRIES.add(e);
            }
        } catch (JSONException ignored) {
        }
        if (ENTRIES.isEmpty()) {
            // The one TV of the first versions of the app.
            SharedPreferences old = c.getSharedPreferences("tv", Context.MODE_PRIVATE);
            if (old.getString("token", null) != null && old.getString("host", null) != null) {
                Entry e = new Entry();
                e.id = old.getString("id", "");
                e.key = e.id;
                e.host = old.getString("host", null);
                e.port = old.getInt("port", Proto.PORT);
                e.name = old.getString("name", "");
                e.svc = old.getString("svc", null);
                e.token = old.getString("token", null);
                if (!e.id.isEmpty()) {
                    ENTRIES.add(e);
                    persist(c, e.key);
                    old.edit().clear().apply();
                }
            }
        }
        select(sp.getString("active", null));
    }

    private static void persist(Context c, String active) {
        try {
            JSONArray list = new JSONArray();
            for (Entry e : ENTRIES) list.put(e.toJson());
            c.getSharedPreferences("tvs", Context.MODE_PRIVATE).edit().putString("list", list.toString()).putString("active", active).apply();
        } catch (JSONException ignored) {
        }
    }

    private static Entry find(String key) {
        for (Entry e : ENTRIES) if (e.key.equals(key)) return e;
        return null;
    }

    /** Makes it the TV in use (the first one when `key` is unknown; none when there are none). */
    private static void select(String key) {
        Entry e = find(key);
        if (e == null && !ENTRIES.isEmpty()) e = ENTRIES.get(0);
        activeKey = e == null ? null : e.key;
        host = e == null ? null : e.host;
        port = e == null ? Proto.PORT : e.port;
        name = e == null ? null : e.name;
        label = e == null ? null : e.label;
        id = e == null ? null : e.id;
        token = e == null ? null : e.token;
        svc = e == null ? null : e.svc;
        Cache.use(activeKey);
    }

    static synchronized List<Entry> all() { return new ArrayList<>(ENTRIES); }

    static synchronized boolean paired() { return activeKey != null && host != null && token != null; }

    static synchronized String activeKey() { return activeKey; }

    /** The name of the TV in use. */
    static synchronized String title() {
        Entry e = find(activeKey);
        return e == null ? "Your TV" : e.title();
    }

    /** Switches to another paired TV. */
    static synchronized void use(Context c, String key) {
        select(key);
        persist(c, activeKey);
    }

    /** Writes what changed in the fields of the TV in use (its address, its name) back to the list. */
    static synchronized void save(Context c) {
        Entry e = find(activeKey);
        if (e == null) return;
        e.host = host;
        e.port = port;
        e.name = name;
        e.svc = svc;
        persist(c, activeKey);
    }

    /** A TV announced itself: if it is one we know, its address may have changed. */
    static synchronized void moved(Context c, String service, String newHost, int newPort) {
        for (Entry e : ENTRIES) {
            if (!service.equals(e.svc) || (newHost.equals(e.host) && newPort == e.port)) continue;
            e.host = newHost;
            e.port = newPort;
            if (e.key.equals(activeKey)) {
                host = newHost;
                port = newPort;
            }
            persist(c, activeKey);
        }
    }

    static synchronized boolean knows(String service) {
        for (Entry e : ENTRIES) if (service.equals(e.svc)) return true;
        return false;
    }

    /** Gives the TV in use a name of the person's own ("" brings back the TV's). */
    static synchronized void rename(Context c, String newLabel) {
        Entry e = find(activeKey);
        if (e == null) return;
        e.label = newLabel.trim();
        label = e.label;
        persist(c, activeKey);
    }

    /** A newly paired TV: it joins the list (replacing the same TV paired before) and is the one in use. */
    static synchronized void add(Context c, Entry fresh) {
        for (int i = ENTRIES.size() - 1; i >= 0; i--) {
            Entry old = ENTRIES.get(i);
            boolean same = fresh.svc != null ? fresh.svc.equals(old.svc) : fresh.host.equals(old.host);
            if (same) {
                if (fresh.label == null || fresh.label.isEmpty()) fresh.label = old.label;   // keeps the name it was given
                ENTRIES.remove(i);
                Cache.drop(old.key);
            }
        }
        ENTRIES.add(fresh);
        select(fresh.key);
        persist(c, activeKey);
    }

    /** Forgets the TV in use. */
    static void forget(Context c) { forget(c, activeKey()); }

    /** Forgets a TV here, and tells it (best effort) to forget this phone too. Another one takes over if it was in use. */
    static synchronized void forget(Context c, String key) {
        final Entry e = find(key);
        if (e == null) return;
        new Thread(() -> {
            try {
                exchange(e.host, e.port, e.id, e.token, "POST", "/unpair", "{}", true);
            } catch (Exception ignored) {
            }
        }).start();
        ENTRIES.remove(e);
        Cache.drop(e.key);
        select(e.key.equals(activeKey) ? null : activeKey);
        persist(c, activeKey);
    }

    // MARK: - Talking to a TV

    private static synchronized long nextStamp() {
        last = Math.max(System.currentTimeMillis(), last + 1);
        return last;
    }

    static final class Reply {
        int status;
        byte[] body;
        String text() { return new String(body, StandardCharsets.UTF_8); }
        JSONObject json() throws JSONException { return new JSONObject(text()); }
        String error() {
            try {
                return json().optString("error", "error " + status);
            } catch (JSONException ex) {
                return "error " + status;
            }
        }
    }

    /** A TV, as it was when the job started: a switch to another one meanwhile does not redirect it. */
    static final class Session {
        final String host, id, token, key;
        final int port;

        Session(String host, int port, String id, String token) {
            this.host = host;
            this.port = port;
            this.id = id;
            this.token = token;
            this.key = id;
        }

        Reply get(String path) throws IOException { return exchange(host, port, id, token, "GET", path, null, true); }

        Reply post(String path, JSONObject body) throws IOException { return exchange(host, port, id, token, "POST", path, body.toString(), true); }
    }

    static synchronized Session current() { return new Session(host, port, id, token); }

    static synchronized Session session(Entry e) { return new Session(e.host, e.port, e.id, e.token); }

    static Reply get(String path) throws IOException { return current().get(path); }

    static Reply post(String path, JSONObject body) throws IOException { return current().post(path, body); }

    /** Who is at that address (open to anyone: no pairing needed). */
    static Reply info(String host, int port) throws IOException { return exchange(host, port, null, null, "GET", "/info", null, false); }

    private static Reply exchange(String host, int port, String id, String token, String method, String path, String body, boolean signed) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(false);   // a TV never sends us elsewhere
            c.setRequestMethod(method);
            String payload = body == null ? "" : body;
            long stamp = 0;
            if (signed) {
                stamp = nextStamp();
                c.setRequestProperty(Proto.H_ID, id);
                c.setRequestProperty(Proto.H_TS, String.valueOf(stamp));
                c.setRequestProperty(Proto.H_SIG, Proto.sign(token, method, path, stamp, payload));
            }
            if (!method.equals("GET")) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(payload.getBytes(StandardCharsets.UTF_8));
                }
            }
            Reply r = new Reply();
            r.status = c.getResponseCode();
            try (InputStream in = r.status < 400 ? c.getInputStream() : c.getErrorStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                for (int n; in != null && (n = in.read(chunk)) > 0; ) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > MAX_REPLY) throw new IOException("The reply was too big");
                }
                r.body = buf.toByteArray();
            }
            // A paired TV signs what it answers. A 401 can't be signed (it doesn't know us), so that one is taken as it is.
            if (signed && r.status != 401) {
                String sig = c.getHeaderField(Proto.H_SIG);
                if (sig == null || !Proto.same(Proto.signReply(token, stamp, r.status, r.body), sig)) {
                    throw new IOException("This reply isn't from your TV");
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    // MARK: - Pairing

    /** One pairing in progress: where, our key pair and the TV's public key. Nothing is stored until it works. */
    static final class Pairing {
        String host;
        int port;
        KeyPair keys;
        byte[] tvPublic;
    }

    /** Asks the TV at that address to show a code, and swaps public keys with it. */
    static Pairing pairStart(String host, int port) throws Exception {
        Reply r = exchange(host, port, null, null, "POST", "/pair/start", "{}", false);
        if (r.status != 200) throw new IOException(r.error());
        Pairing p = new Pairing();
        p.host = host;
        p.port = port;
        p.tvPublic = Base64.getDecoder().decode(r.json().getString("pub"));
        p.keys = Proto.newKeyPair();
        return p;
    }

    /**
     * Sends the proof that we know the code (never the code itself), checks the TV's own proof, and returns the TV with the
     * token both sides work out from their shared key (the caller names it and adds it with add).
     */
    static Entry pairFinish(Pairing p, String pin, String phoneName) throws Exception {
        byte[] secret = Proto.secret(Proto.agree(p.keys.getPrivate(), p.tvPublic), pin);
        JSONObject body = new JSONObject().put("pub", Base64.getEncoder().encodeToString(p.keys.getPublic().getEncoded()))
                .put("proof", Proto.proof(secret, "phone")).put("name", phoneName);
        Reply r = exchange(p.host, p.port, null, null, "POST", "/pair/confirm", body.toString(), false);
        if (r.status != 200) throw new IOException(r.error());
        JSONObject ok = r.json();
        if (!Proto.same(Proto.proof(secret, "tv"), ok.optString("proof"))) throw new IOException("That isn't your TV");
        Entry e = new Entry();
        e.id = ok.getString("id");
        e.key = e.id;
        e.token = Proto.token(secret);
        e.host = p.host;
        e.port = p.port;
        return e;
    }
}
