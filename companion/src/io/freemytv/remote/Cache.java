package io.freemytv.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The phone's own copy of what each paired TV has: its apps, its settings and the app icons. The screens read from here and
 * only the Refresh button (Sync) asks the TV again. A change made here is kept as "pending" until the TV has it.
 *
 * Every TV has its own copy (named by its key, Tv.Entry.key). The methods without a key work on the TV in use; Sync, which
 * can finish after the person has switched to another TV, always names the TV it was asked about.
 */
final class Cache {
    private Cache() {}

    private static Context app;
    private static String active = "none";
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<>(160);

    static void init(Context c) { app = c.getApplicationContext(); }

    /** The TV the screens show (null: none). */
    static synchronized void use(String key) { active = key == null ? "none" : key; }

    static synchronized String key() { return active; }

    private static SharedPreferences sp(String key) { return app.getSharedPreferences("cache-" + key, Context.MODE_PRIVATE); }

    private static JSONObject read(String key, String name) {
        try {
            String v = sp(key).getString(name, null);
            return v == null ? null : new JSONObject(v);
        } catch (JSONException e) {
            return null;
        }
    }

    // MARK: - Apps and settings

    /** {"apps":[{pkg,label,shelf}], "shelf":[pkg]} as the TV sent it; null before the first Refresh. */
    static synchronized JSONObject apps() { return read(active, "apps"); }

    /** {"values":{}, "keys":{}, "meta":{}} as the TV sent it, with our own pending changes on top; null before the first Refresh. */
    static synchronized JSONObject settings() { return read(active, "settings"); }

    static synchronized long syncedAt() { return sp(active).getLong("syncedAt", 0); }

    static synchronized void save(String key, JSONObject apps, JSONObject settings) {
        sp(key).edit().putString("apps", apps.toString()).putString("settings", settings.toString()).putLong("syncedAt", System.currentTimeMillis()).apply();
    }

    /** Wipes the copy of the TV in use (the pairing stays). */
    static synchronized void clear() { clear(active); }

    static synchronized void clear(String key) {
        sp(key).edit().clear().apply();
        MEMORY.evictAll();
        File[] files = iconDir(key).listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** A TV was forgotten: its copy goes too. */
    static synchronized void drop(String key) {
        clear(key);
        //noinspection ResultOfMethodCallIgnored
        iconDir(key).delete();
    }

    // MARK: - Changes waiting for the TV

    /** {"values":{}, "keys":{}}: what we changed here and the TV has not confirmed. */
    static synchronized JSONObject pending(String key) {
        JSONObject p = read(key, "pending");
        return p == null ? new JSONObject() : p;
    }

    static synchronized int pendingCount() { return pendingCount(active); }

    static synchronized int pendingCount(String key) {
        JSONObject p = pending(key);
        int n = 0;
        for (String section : new String[] {"values", "keys"}) {
            JSONObject o = p.optJSONObject(section);
            if (o != null) n += o.length();
        }
        return n;
    }

    /** Changes a setting here (the screens show it at once) and queues it for the TV in use. */
    static synchronized void change(String section, String name, Object value) { change(active, section, name, value); }

    static synchronized void change(String key, String section, String name, Object value) {
        try {
            JSONObject s = read(key, "settings");
            if (s != null) s.getJSONObject(section).put(name, value);
            JSONObject p = pending(key);
            JSONObject o = p.optJSONObject(section);
            if (o == null) p.put(section, o = new JSONObject());
            o.put(name, value);
            SharedPreferences.Editor ed = sp(key).edit().putString("pending", p.toString());
            if (s != null) ed.putString("settings", s.toString());
            ed.apply();
        } catch (JSONException ignored) {
        }
    }

    /** The TV has what we sent: forget those changes, unless one was changed again since. */
    static synchronized void sent(String key, JSONObject sent) {
        try {
            JSONObject p = pending(key);
            for (String section : new String[] {"values", "keys"}) {
                JSONObject was = sent.optJSONObject(section), now = p.optJSONObject(section);
                if (was == null || now == null) continue;
                for (java.util.Iterator<String> it = was.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (now.has(k) && String.valueOf(now.get(k)).equals(String.valueOf(was.get(k)))) now.remove(k);
                }
                if (now.length() == 0) p.remove(section);
            }
            sp(key).edit().putString("pending", p.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    // MARK: - Icons and photo previews

    private static File iconDir(String key) {
        File d = new File(app.getCacheDir(), "icons-" + key);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File iconFile(String key, String pkg) { return new File(iconDir(key), pkg + ".png"); }

    static boolean hasIcon(String key, String pkg) { return MEMORY.get(key + "/" + pkg) != null || iconFile(key, pkg).exists(); }

    /** From memory or the file (the TV in use); call off the main thread. null when we have none. */
    static Bitmap icon(String pkg) {
        String key = key();
        Bitmap b = MEMORY.get(key + "/" + pkg);
        if (b == null && iconFile(key, pkg).exists()) {
            b = BitmapFactory.decodeFile(iconFile(key, pkg).getPath());
            if (b != null) MEMORY.put(key + "/" + pkg, b);
        }
        return b;
    }

    static void saveIcon(String key, String pkg, byte[] png) {
        try (FileOutputStream out = new FileOutputStream(iconFile(key, pkg))) {
            out.write(png);
        } catch (IOException ignored) {
        }
        MEMORY.remove(key + "/" + pkg);
    }

    /** Previews of the TV's photos (the wallpapers picker): kept after the first time, like the icons. */
    static Bitmap photo(String name) {
        File f = new File(iconDir(key()), "photo-" + name);
        return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    static void savePhoto(String name, byte[] jpeg) {
        try (FileOutputStream out = new FileOutputStream(new File(iconDir(key()), "photo-" + name))) {
            out.write(jpeg);
        } catch (IOException ignored) {
        }
    }
}
