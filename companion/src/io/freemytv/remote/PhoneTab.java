package io.freemytv.remote;

import static io.freemytv.remote.Form.*;

import android.text.format.DateUtils;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** This phone app's own settings (how it behaves). The TV's settings are in SettingsTab. */
final class PhoneTab {
    private static final String[] TABS = {"Apps", "Remote", "TV settings", "Phone settings"};

    private final MainActivity a;
    private final LinearLayout host;

    PhoneTab(MainActivity a, LinearLayout host) {
        this.a = a;
        this.host = host;
    }

    void show() {
        host.removeAllViews();
        Form f = new Form(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 24));
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        host.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        f.heading(list, "Phone settings");

        LinearLayout tv = f.group(list, "This TV");
        f.row(tv, R.drawable.ic_tv, BLUE, "Name", Tv.title(), v -> a.renameTv());
        f.row(tv, R.drawable.ic_place, TEAL, "Address", Tv.host + ":" + Tv.port, null);
        long at = Cache.syncedAt();
        f.row(tv, R.drawable.ic_refresh, GREEN, "Last synced",
                at == 0 ? "Never" : DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                v -> a.refresh());
        int paired = Tv.all().size();
        f.row(tv, R.drawable.ic_swap, ORANGE, "Your TVs", paired + (paired == 1 ? " TV" : " TVs"), v -> a.showTvMenu());
        f.row(tv, R.drawable.ic_close, PINK, "Forget this TV", "", v -> a.confirmForget());

        LinearLayout remote = f.group(list, "Remote");
        f.toggle(remote, R.drawable.ic_vibrate, PURPLE, "Vibrate on key press", AppPrefs.haptics(), AppPrefs::setHaptics);
        f.toggle(remote, R.drawable.ic_vol_up, TEAL, "Volume buttons control the TV", AppPrefs.volumeKeys(), AppPrefs::setVolumeKeys);
        f.toggle(remote, R.drawable.ic_theme, YELLOW, "Keep the screen on in Remote", AppPrefs.keepAwake(), AppPrefs::setKeepAwake);

        LinearLayout apps = f.group(list, "Apps");
        f.row(apps, R.drawable.ic_apps, BLUE, "Columns in All apps", String.valueOf(AppPrefs.columns()), v ->
                Ui.choose(a, "Columns in All apps", new String[] {"2", "3", "4"}, AppPrefs.columns() - 2, which -> {
                    AppPrefs.setColumns(which + 2);
                    show();
                }));
        f.row(apps, R.drawable.ic_home, ORANGE, "Open the app on", TABS[AppPrefs.startTab()], v ->
                Ui.choose(a, "Open the app on", TABS, AppPrefs.startTab(), which -> {
                    AppPrefs.setStartTab(which);
                    show();
                }));

        LinearLayout sync = f.group(list, "Sync");
        f.toggle(sync, R.drawable.ic_refresh, GREEN, "Refresh when the app opens", AppPrefs.syncOnOpen(), AppPrefs::setSyncOnOpen);
        f.row(sync, R.drawable.ic_close, PINK, "Clear the saved copy", "", v -> {
            int waiting = Cache.pendingCount();
            android.widget.TextView body = Ui.text(a, "The saved apps, settings and icons are removed from this phone. Your pairing stays."
                    + (waiting > 0 ? " " + waiting + " change" + (waiting == 1 ? "" : "s") + " not yet sent to the TV will be lost." : ""), 15, Ui.SUB, false);
            body.setLineSpacing(0, 1.2f);
            Ui.dialog(a, "Clear the saved copy?", body, "Clear", () -> {
                Cache.clear();
                a.say("Saved copy cleared. Tap refresh to load it again.");
                show();
            }).show();
        });

        LinearLayout about = f.group(list, "About");
        String version = "";
        try {
            version = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
        }
        f.row(about, R.drawable.ic_tv, GREY, "Free My TV Remote", version, null);
    }
}
