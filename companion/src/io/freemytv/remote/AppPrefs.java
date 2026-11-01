package io.freemytv.remote;

import android.content.Context;
import android.content.SharedPreferences;

/** How this phone app behaves (the Phone tab). Nothing here goes to the TV. */
final class AppPrefs {
    private AppPrefs() {}

    private static SharedPreferences sp;

    static void init(Context c) {
        if (sp == null) sp = c.getApplicationContext().getSharedPreferences("app", Context.MODE_PRIVATE);
    }

    /** A small buzz on every remote key and app tap. */
    static boolean haptics() { return sp == null || sp.getBoolean("haptics", true); }
    static void setHaptics(boolean on) { sp.edit().putBoolean("haptics", on).apply(); }

    /** The phone's volume buttons change the TV's volume while the Remote tab is open. */
    static boolean volumeKeys() { return sp == null || sp.getBoolean("volumeKeys", true); }
    static void setVolumeKeys(boolean on) { sp.edit().putBoolean("volumeKeys", on).apply(); }

    /** The screen stays on while the Remote tab is open. */
    static boolean keepAwake() { return sp == null || sp.getBoolean("keepAwake", true); }
    static void setKeepAwake(boolean on) { sp.edit().putBoolean("keepAwake", on).apply(); }

    /** Ask the TV for its apps and settings whenever the app opens (off: only the refresh button does). */
    static boolean syncOnOpen() { return sp != null && sp.getBoolean("syncOnOpen", false); }
    static void setSyncOnOpen(boolean on) { sp.edit().putBoolean("syncOnOpen", on).apply(); }

    /** Columns in the "All apps" grid: 2 to 4. */
    static int columns() { return sp == null ? 3 : Math.max(2, Math.min(4, sp.getInt("columns", 3))); }
    static void setColumns(int n) { sp.edit().putInt("columns", Math.max(2, Math.min(4, n))).apply(); }

    /** The tab the app opens on: 0 Apps, 1 Remote, 2 TV, 3 Phone. */
    static int startTab() { return sp == null ? 0 : Math.max(0, Math.min(3, sp.getInt("startTab", 0))); }
    static void setStartTab(int tab) { sp.edit().putInt("startTab", tab).apply(); }

    /** The Remote tab shows a touchpad instead of the round pad. */
    static boolean touchpad() { return sp != null && sp.getBoolean("touchpad", false); }
    static void setTouchpad(boolean on) { sp.edit().putBoolean("touchpad", on).apply(); }

    /** Pinned apps of one TV (package names, in the order pinned). */
    static java.util.List<String> pinned(String tv) { return list("pin-" + tv); }

    static boolean isPinned(String tv, String pkg) { return pinned(tv).contains(pkg); }

    static void togglePin(String tv, String pkg) {
        java.util.List<String> l = pinned(tv);
        if (!l.remove(pkg)) l.add(pkg);
        store("pin-" + tv, l);
    }

    /** The last apps opened on one TV from here, newest first. */
    static java.util.List<String> recent(String tv) { return list("recent-" + tv); }

    static void opened(String tv, String pkg) {
        java.util.List<String> l = recent(tv);
        l.remove(pkg);
        l.add(0, pkg);
        while (l.size() > 6) l.remove(l.size() - 1);
        store("recent-" + tv, l);
    }

    /** Scenes of one TV, as the JSON Tools writes. */
    static String scenes(String tv) { return sp == null ? "[]" : sp.getString("scenes-" + tv, "[]"); }
    static void setScenes(String tv, String json) { if (sp != null) sp.edit().putString("scenes-" + tv, json).apply(); }

    private static java.util.List<String> list(String name) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String s = sp == null ? "" : sp.getString(name, "");
        for (String p : s.split("\n")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private static void store(String name, java.util.List<String> l) {
        if (sp != null) sp.edit().putString(name, android.text.TextUtils.join("\n", l)).apply();
    }
}
