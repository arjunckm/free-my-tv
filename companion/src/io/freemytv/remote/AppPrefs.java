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
}
