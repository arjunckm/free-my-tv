package io.freemytv.remote;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import io.freemytv.proto.Proto;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;

/**
 * Free My TV Remote. No TV paired yet: finds the TVs on the Wi-Fi that run Free My TV and pairs with the PIN the TV shows.
 * Paired: four tabs (Apps, Remote, TV, Phone) drawn from the phone's own copy of the TV in use; only the Refresh button asks
 * the TV for it again (Sync). Any number of TVs can be paired: tap the TV's name at the top to switch, add or forget one.
 * Sharing a link to this app is ShareActivity.
 */
public class MainActivity extends Activity {
    private static final String[] TAB_NAMES = {"Apps", "Remote", "TV", "Phone"};
    private static final int[] TAB_ICONS = {R.drawable.ic_apps, R.drawable.ic_remote, R.drawable.ic_tv, R.drawable.ic_phone};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout frame;
    private FrameLayout pageHost;
    private LinearLayout nav;
    private TextView title, subtitle, snack;
    private Ui.FrameLayoutLike refreshButton;
    private ObjectAnimator spin;
    private int tab;
    private Boolean online;   // null until the TV in use has answered once in this session
    private AppsPage apps;
    private SettingsTab settings;

    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;
    private LinearLayout found;
    private TextView hint;
    private Tv.Pairing pairing;
    private boolean askedToPairAgain;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Cache.init(this);
        AppPrefs.init(this);
        Tv.load(this);
        tab = AppPrefs.startTab();
        nsd = (NsdManager) getSystemService(NSD_SERVICE);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.BG);
        frame.setFitsSystemWindows(true);
        setContentView(frame);
        if (Tv.paired()) {
            showTv();
            if (AppPrefs.syncOnOpen()) refresh();
        } else {
            showFind(false);
        }
    }

    /** On the Remote tab the phone's volume buttons change the TV's volume (Phone settings can turn this off). */
    @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (tab == 1 && Tv.paired() && AppPrefs.volumeKeys() && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            try {
                Sync.command("/key", new JSONObject().put("code", keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ? 24 : 25), error -> {
                    if (error != null) problem(error);
                });
            } catch (org.json.JSONException ignored) {
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** The key-up of a volume press we handled is ours too (otherwise the phone's own volume bar shows). */
    @Override public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (tab == 1 && Tv.paired() && AppPrefs.volumeKeys() && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override protected void onDestroy() {
        stopDiscovery();
        if (apps != null) apps.close();
        super.onDestroy();
    }

    // MARK: - Messages

    /** A short message at the bottom. Safe from any thread. */
    void say(String message) {
        runOnUiThread(() -> {
            if (snack == null) return;
            snack.setText(message);
            snack.animate().cancel();
            snack.setVisibility(View.VISIBLE);
            snack.setAlpha(0);
            snack.animate().alpha(1).setDuration(150).start();
            ui.removeCallbacks(hideSnack);
            ui.postDelayed(hideSnack, 2600);
        });
    }

    private final Runnable hideSnack = () -> {
        if (snack != null) snack.animate().alpha(0).setDuration(250).withEndAction(() -> snack.setVisibility(View.GONE)).start();
    };

    /** The TV did not take a command: say why. If it doesn't know this phone, ask (once) before throwing the pairing away. */
    void problem(String error) {
        if (Sync.UNPAIRED.equals(error)) {
            runOnUiThread(() -> {
                if (askedToPairAgain) return;
                askedToPairAgain = true;
                TextView body = Ui.text(this, Tv.title() + " doesn't recognize this phone any more. Pair again with the code on the TV.", 15, Ui.SUB, false);
                body.setLineSpacing(0, 1.2f);
                Dialog d = Ui.dialog(this, "Pair again?", body, "Pair again", this::pairAgain);
                d.setOnDismissListener(v -> askedToPairAgain = false);
                d.show();
            });
            return;
        }
        online = false;
        runOnUiThread(this::updateSubtitle);
        say(error);
    }

    /** A command finished: show `ok` or the problem. */
    void done(String error, String ok) {
        if (error != null) {
            problem(error);
            return;
        }
        online = true;
        runOnUiThread(this::updateSubtitle);
        say(ok);
    }

    /** Sends the changes made on the phone; they wait on the phone when the TV can't be reached. */
    void sendChanges() {
        final String key = Tv.activeKey();
        Sync.push(error -> runOnUiThread(() -> {
            if (key == null || !key.equals(Tv.activeKey())) return;   // the person has switched TV meanwhile
            if (error == null) {
                online = true;
            } else if (Sync.UNPAIRED.equals(error)) {
                problem(error);
                return;
            } else {
                online = false;
                say("Saved on the phone. It goes to the TV when it can be reached.");
            }
            updateSubtitle();
            if (settings != null) settings.pendingChanged();
        }));
    }

    View empty(String heading, String body, boolean withRefresh) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), Ui.dp(this, 48));
        box.addView(circleIcon(R.drawable.ic_refresh, 72));
        TextView t = Ui.text(this, heading, 20, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 8));
        box.addView(t);
        TextView s = Ui.text(this, body, 15, Ui.SUB, false);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(0, 1.2f);
        box.addView(s);
        if (withRefresh) {
            TextView go = Ui.pillButton(this, "Refresh", true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 24);
            go.setOnClickListener(v -> refresh());
            box.addView(go, lp);
            // Can't reach this TV? Switch to another, or add one (also the same TV at its new address), without a sync first.
            TextView other = Ui.pillButton(this, "Switch or add a TV", false);
            LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ol.topMargin = Ui.dp(this, 12);
            other.setOnClickListener(v -> showTvMenu());
            box.addView(other, ol);
        }
        return box;
    }

    private View circleIcon(int icon, float sizeDp) {
        FrameLayout c = new FrameLayout(this);
        c.setBackground(Ui.round(this, Ui.SURFACE2, sizeDp / 2));
        c.addView(Ui.icon(this, icon, Ui.ACCENT, sizeDp * 0.45f), new FrameLayout.LayoutParams(Ui.dp(this, sizeDp * 0.45f), Ui.dp(this, sizeDp * 0.45f), Gravity.CENTER));
        c.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp)));
        return c;
    }

    private void addSnack() {
        snack = Ui.text(this, "", 14, Ui.TEXT, true);
        snack.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        snack.setBackground(Ui.round(this, 0xF22A3142, 24));
        snack.setMaxWidth(Ui.dp(this, 320));
        snack.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Ui.dp(this, Tv.paired() && nav != null ? 92 : 32);
        frame.addView(snack, lp);
    }

    // MARK: - Finding and pairing

    /** `adding`: another TV while others are paired (a Back button returns to the one in use). */
    private void showFind(boolean adding) {
        stopDiscovery();
        nav = adding ? null : nav;
        frame.removeAllViews();
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(Ui.dp(this, 24), Ui.dp(this, adding ? 16 : 56), Ui.dp(this, 24), Ui.dp(this, 24));
        if (adding) {
            Ui.FrameLayoutLike back = Ui.iconButton(this, R.drawable.ic_back, Ui.SURFACE2, Ui.TEXT, 44, 22);
            back.setOnClickListener(v -> {
                stopDiscovery();
                found = null;
                showTv();
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
            bp.gravity = Gravity.START;
            bp.bottomMargin = Ui.dp(this, 24);
            column.addView(back, bp);
        }
        column.addView(circleIcon(R.drawable.ic_tv, adding ? 80 : 96));
        TextView h = Ui.text(this, adding ? "Add another TV" : "Connect your TV", 26, Ui.TEXT, true);
        h.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 8));
        h.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(h);
        TextView s = Ui.text(this, "Open Free My TV on the TV and keep your phone on the same Wi-Fi.", 15, Ui.SUB, false);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(0, 1.2f);
        column.addView(s);

        LinearLayout scanning = new LinearLayout(this);
        scanning.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar bar = new ProgressBar(this);
        bar.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        scanning.addView(bar, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        hint = Ui.text(this, "Looking for TVs…", 14, Ui.SUB, false);
        hint.setPadding(Ui.dp(this, 12), 0, 0, 0);
        scanning.addView(hint);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(this, 32);
        column.addView(scanning, sl);

        found = new LinearLayout(this);
        found.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.topMargin = Ui.dp(this, 16);
        column.addView(found, fl);

        TextView manual = Ui.pillButton(this, "Enter the TV's address", false);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = Ui.dp(this, 20);
        manual.setOnClickListener(v -> askAddress());
        column.addView(manual, ml);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        frame.addView(scroll);
        addSnack();
        final LinearLayout mine = found;
        ui.postDelayed(() -> {
            if (hint != null && found == mine && found != null && found.getChildCount() == 0) hint.setText("Nothing yet. Is Free My TV open on the TV?");
        }, 8000);
        startDiscovery();
    }

    private void startDiscovery() {
        stopDiscovery();
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onStartDiscoveryFailed(String t, int code) {}
            @Override public void onStopDiscoveryFailed(String t, int code) {}
            @Override public void onServiceLost(NsdServiceInfo i) {}
            @Override public void onServiceFound(NsdServiceInfo i) {
                synchronized (toResolve) {
                    toResolve.add(i);
                }
                resolveNext();
            }
        };
        try {
            nsd.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            discovery = null;
        }
    }

    private void stopDiscovery() {
        try {
            if (discovery != null) nsd.stopServiceDiscovery(discovery);
        } catch (RuntimeException ignored) {
        }
        discovery = null;
    }

    /** One at a time: Android refuses a second resolve while one is running. */
    private void resolveNext() {
        final NsdServiceInfo next;
        synchronized (toResolve) {
            if (resolving || toResolve.isEmpty()) return;
            resolving = true;
            next = toResolve.poll();
        }
        nsd.resolveService(next, new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo i, int code) { finished(); }
            @Override public void onServiceResolved(NsdServiceInfo i) {
                InetAddress a = i.getHost();
                if (a != null) {
                    String host = a.getHostAddress();
                    int cut = host.indexOf('%');
                    final String address = cut < 0 ? host : host.substring(0, cut);
                    final boolean v4 = a instanceof Inet4Address;
                    final String service = i.getServiceName();
                    final int port = i.getPort();
                    runOnUiThread(() -> seen(service, address, port, v4));
                }
                finished();
            }
            private void finished() {
                synchronized (toResolve) {
                    resolving = false;
                }
                resolveNext();
            }
        });
    }

    /** A TV answered: a paired one may have a new address; one we don't know is offered on the pairing screen. */
    private void seen(String service, String address, int port, boolean v4) {
        if (v4) Tv.moved(this, service, address, port);
        if (Tv.knows(service) || found == null) return;
        for (int i = 0; i < found.getChildCount(); i++) if (service.equals(found.getChildAt(i).getTag())) return;
        String nice = service.replace("FreeMyTV-", "");
        LinearLayout card = new LinearLayout(this);
        card.setTag(service);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14));
        card.setBackground(Ui.ripple(this, Ui.SURFACE, 20));
        card.addView(Ui.icon(this, R.drawable.ic_tv, Ui.ACCENT, 28));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(Ui.dp(this, 14), 0, 0, 0);
        names.addView(Ui.text(this, nice, 17, Ui.TEXT, true));
        TextView ip = Ui.text(this, address, 13, Ui.SUB, false);
        ip.setPadding(0, Ui.dp(this, 3), 0, 0);
        names.addView(ip);
        card.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(Ui.icon(this, R.drawable.ic_chevron, Ui.SUB, 22));
        card.setOnClickListener(v -> pair(service, address, port));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        found.addView(card, lp);
        if (hint != null) hint.setText(found.getChildCount() == 1 ? "Found a TV" : "Found " + found.getChildCount() + " TVs");
    }

    private void askAddress() {
        EditText ip = new EditText(this);
        ip.setHint("For example 192.168.1.7");
        ip.setHintTextColor(Ui.SUB);
        ip.setTextColor(Ui.TEXT);
        ip.setSingleLine();
        ip.setInputType(InputType.TYPE_CLASS_PHONE);
        ip.setBackground(Ui.round(this, Ui.SURFACE, 14));
        ip.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        Ui.dialog(this, "TV address", ip, "Connect", () -> {
            String address = ip.getText().toString().trim();
            if (!address.isEmpty()) pair(null, address, Proto.PORT);
        }).show();
    }

    private void pair(String service, String address, int port) {
        say("Connecting…");
        new Thread(() -> {
            try {
                pairing = Tv.pairStart(address, port);
                runOnUiThread(() -> askPin(service));
            } catch (java.net.SocketException | java.net.SocketTimeoutException e) {
                say("Can't reach that TV. Is Free My TV open on it, on the same Wi-Fi?");
            } catch (Exception e) {
                say(e.getMessage() == null ? "Couldn't start pairing" : e.getMessage());   // for example: wait a few seconds
            }
        }).start();
    }

    private void askPin(String service) {
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER);
        pin.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(Proto.PIN_DIGITS)});
        pin.setGravity(Gravity.CENTER);
        pin.setTextSize(30);
        pin.setLetterSpacing(0.3f);
        pin.setTypeface(Ui.MEDIUM);
        pin.setTextColor(Ui.TEXT);
        pin.setHint("········");
        pin.setHintTextColor(Ui.SUB);
        pin.setBackground(Ui.round(this, Ui.SURFACE, 16));
        pin.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView help = Ui.text(this, "Type the " + Proto.PIN_DIGITS + "-digit code shown on your TV.", 14, Ui.SUB, false);
        help.setPadding(0, 0, 0, Ui.dp(this, 14));
        body.addView(help);
        body.addView(pin);
        Ui.dialog(this, "Pair with your TV", body, "Pair", () -> confirm(service, pin.getText().toString().trim())).show();
    }

    private void confirm(String service, String pin) {
        say("Pairing…");
        new Thread(() -> {
            try {
                if (pairing == null) throw new java.io.IOException("Start again: tap the TV");
                Tv.Entry tv = Tv.pairFinish(pairing, pin, android.os.Build.MODEL);
                pairing = null;
                tv.svc = service;
                tv.name = service == null ? "Your TV" : service.replace("FreeMyTV-", "");
                try {
                    tv.name = Tv.info(tv.host, tv.port).json().optString("name", tv.name);
                } catch (Exception ignored) {
                }
                Tv.add(this, tv);   // joins the TVs already paired, and is the one in use
                runOnUiThread(() -> {
                    stopDiscovery();
                    found = null;
                    online = null;
                    showTv();
                    refresh();   // the first copy of this TV
                });
            } catch (Exception e) {
                say("Not paired: " + e.getMessage());
            }
        }).start();
    }

    // MARK: - Paired

    private void showTv() {
        found = null;
        hint = null;
        if (spin != null) spin.cancel();
        frame.removeAllViews();
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        frame.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top bar: the TV (tap to switch or add one) and how recent our copy is, and Refresh
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 8));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        title = Ui.text(this, Tv.title(), 22, Ui.TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameRow.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView more = Ui.icon(this, R.drawable.ic_down, Ui.SUB, 22);
        more.setPadding(Ui.dp(this, 2), 0, 0, 0);
        nameRow.addView(more);
        subtitle = Ui.text(this, "", 13, Ui.SUB, false);
        subtitle.setPadding(0, Ui.dp(this, 4), 0, 0);
        names.addView(nameRow);
        names.addView(subtitle);
        bar.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        names.setOnClickListener(v -> showTvMenu());
        refreshButton = Ui.iconButton(this, R.drawable.ic_refresh, Ui.SURFACE2, Ui.TEXT, 44, 22);
        refreshButton.setOnClickListener(v -> refresh());
        bar.addView(refreshButton);
        column.addView(bar);

        pageHost = new FrameLayout(this);
        column.addView(pageHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        nav = new LinearLayout(this);
        nav.setBackgroundColor(Ui.SURFACE);
        nav.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int at = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            FrameLayout pill = new FrameLayout(this);
            pill.addView(Ui.icon(this, TAB_ICONS[i], Ui.SUB, 24), new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER));
            item.addView(pill, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 32)));
            TextView label = Ui.text(this, TAB_NAMES[i], 12, Ui.SUB, true);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setPadding(0, Ui.dp(this, 4), 0, 0);
            item.addView(label);
            item.setOnClickListener(v -> showTab(at));
            nav.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        column.addView(nav);
        addSnack();

        updateSubtitle();
        showTab(tab);
        startDiscovery();   // a new address for a paired TV is picked up in seen()
        ping();
    }

    private void showTab(int index) {
        tab = index;
        if (apps != null) apps.close();
        apps = null;
        settings = null;
        pageHost.removeAllViews();
        if (index == 0) {
            apps = new AppsPage(this);
            pageHost.addView(apps.build());
        } else if (index == 1) {
            pageHost.addView(new RemotePage(this).build());
        } else {
            LinearLayout box = new LinearLayout(this);
            pageHost.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (index == 2) {
                settings = new SettingsTab(this, box);
                settings.show();
            } else {
                new PhoneTab(this, box).show();
            }
        }
        if (index == 1 && AppPrefs.keepAwake()) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pageHost.setAlpha(0);
        pageHost.animate().alpha(1).setDuration(160).start();
        for (int i = 0; i < nav.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) nav.getChildAt(i);
            FrameLayout pill = (FrameLayout) item.getChildAt(0);
            boolean on = i == index;
            pill.setBackground(on ? Ui.round(this, 0x337C9CFF, 16) : null);
            ((ImageView) pill.getChildAt(0)).setImageTintList(ColorStateList.valueOf(on ? Ui.ACCENT : Ui.SUB));
            ((TextView) item.getChildAt(1)).setTextColor(on ? Ui.TEXT : Ui.SUB);
        }
    }

    /** Is the TV there? One small question, not a sync. */
    private void ping() {
        final String key = Tv.activeKey(), host = Tv.host;
        final int port = Tv.port;
        if (key == null) return;
        new Thread(() -> {
            Boolean answered;
            String name = null;
            try {
                Tv.Reply r = Tv.info(host, port);
                answered = r.status == 200;
                name = r.json().optString("name", null);
            } catch (Exception e) {
                answered = false;
            }
            final Boolean up = answered;
            final String itsName = name;
            runOnUiThread(() -> {
                if (!key.equals(Tv.activeKey())) return;   // switched to another TV meanwhile
                online = up;
                if (itsName != null && !itsName.equals(Tv.name)) {
                    Tv.name = itsName;
                    Tv.save(this);
                    if (title != null) title.setText(Tv.title());
                }
                updateSubtitle();
            });
        }).start();
    }

    void updateSubtitle() {
        if (subtitle == null) return;
        long at = Cache.syncedAt();
        String synced = at == 0 ? "Not synced yet"
                : System.currentTimeMillis() - at < 45_000 ? "Synced just now"
                : "Synced " + DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().toLowerCase();
        int color = online == null ? Ui.SUB : online ? Ui.OK : Ui.DANGER;
        String text = "●  " + (online == null ? "" : online ? "Online  ·  " : "Offline  ·  ") + synced;
        SpannableString s = new SpannableString(text);
        s.setSpan(new ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        subtitle.setText(s);
    }

    /** Asks the TV in use for its apps and settings (the only time we do) and keeps them. */
    void refresh() {
        final String key = Tv.activeKey();
        if (key == null || Sync.refreshing(key) || refreshButton == null) return;
        spin = ObjectAnimator.ofFloat(refreshButton.glyph, "rotation", 0, 360);
        spin.setDuration(900);
        spin.setRepeatCount(ObjectAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.start();
        Sync.refresh(error -> runOnUiThread(() -> {
            if (!key.equals(Tv.activeKey())) return;   // the person switched TV: that one's screen was built fresh
            if (spin != null) spin.cancel();
            refreshButton.glyph.setRotation(0);
            if (Sync.UNPAIRED.equals(error)) {
                problem(error);
            } else if (error != null) {
                online = false;
                updateSubtitle();
                say(error);
            } else {
                online = true;
                updateSubtitle();
                say("Up to date");
                if (pageHost != null) showTab(tab);
            }
        }));
    }

    // MARK: - Photos from the gallery

    private static final int PICK_PHOTOS = 41;
    private PhotosPage choosing;   // the photo manager that asked for pictures

    /** Opens the phone's gallery picker (no permission needed: the person hands over exactly the pictures they pick). */
    void pickPhotos(PhotosPage page) {
        choosing = page;
        android.content.Intent pick = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        pick.setType("image/*");
        pick.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        pick.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(android.content.Intent.createChooser(pick, "Choose photos for the TV"), PICK_PHOTOS);
        } catch (android.content.ActivityNotFoundException e) {
            say("This phone has no gallery to pick from");
        }
    }

    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_PHOTOS || result != RESULT_OK || data == null || choosing == null) return;
        java.util.List<android.net.Uri> picked = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) picked.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        if (picked.size() > 30) {
            say("30 at a time: sending the first 30");
            picked = picked.subList(0, 30);
        }
        choosing.upload(picked);
    }

    // MARK: - Several TVs

    /** The TVs: tap one to switch to it; add another; rename, refresh or forget the one in use. */
    void showTvMenu() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        final Dialog d = Ui.dialog(this, "Your TVs", scroll, null, null);
        for (Tv.Entry e : Tv.all()) {
            final boolean current = e.key.equals(Tv.activeKey());
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
            row.setBackground(Ui.ripple(this, Color.TRANSPARENT, 14));
            row.addView(Ui.icon(this, R.drawable.ic_tv, current ? Ui.ACCENT : Ui.SUB, 26));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(Ui.dp(this, 14), 0, 0, 0);
            texts.addView(Ui.text(this, e.title(), 16, current ? Ui.ACCENT : Ui.TEXT, true));
            TextView where = Ui.text(this, e.host, 12, Ui.SUB, false);
            where.setPadding(0, Ui.dp(this, 3), 0, 0);
            texts.addView(where);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (current) row.addView(Ui.icon(this, R.drawable.ic_check, Ui.ACCENT, 20));
            row.setOnClickListener(v -> {
                d.dismiss();
                if (!current) switchTo(e.key);
            });
            list.addView(row);
        }
        menuRow(list, d, R.drawable.ic_add, Ui.TEXT, "Add another TV", () -> {
            stopDiscovery();
            showFind(true);
        });
        if (Tv.paired()) {
            menuRow(list, d, R.drawable.ic_edit, Ui.TEXT, "Rename this TV", this::renameTv);
            menuRow(list, d, R.drawable.ic_refresh, Ui.TEXT, "Refresh from this TV", this::refresh);
            menuRow(list, d, R.drawable.ic_close, Ui.DANGER, "Forget this TV", this::confirmForget);
        }
        d.show();
    }

    private void menuRow(LinearLayout list, Dialog d, int icon, int color, String label, Runnable action) {
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = Ui.dp(this, 6);
        lp.bottomMargin = Ui.dp(this, 6);
        if (list.getChildCount() > 0 && label.equals("Add another TV")) list.addView(line, lp);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 13), Ui.dp(this, 8), Ui.dp(this, 13));
        row.setBackground(Ui.ripple(this, Color.TRANSPARENT, 14));
        row.addView(Ui.icon(this, icon, color == Ui.DANGER ? Ui.DANGER : Ui.SUB, 24));
        TextView t = Ui.text(this, label, 16, color, false);
        t.setPadding(Ui.dp(this, 14), 0, 0, 0);
        row.addView(t);
        row.setOnClickListener(v -> {
            d.dismiss();
            action.run();
        });
        list.addView(row);
    }

    /** Makes another paired TV the one in use: its own apps, settings and remote. */
    void switchTo(String key) {
        Tv.use(this, key);
        online = null;
        askedToPairAgain = false;
        showTv();
        if (AppPrefs.syncOnOpen()) refresh();
    }

    void renameTv() {
        EditText name = new EditText(this);
        name.setText(Tv.title());
        name.setSelectAllOnFocus(true);
        name.setSingleLine();
        name.setTextColor(Ui.TEXT);
        name.setBackground(Ui.round(this, Ui.SURFACE, 14));
        name.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        Ui.dialog(this, "Name this TV", name, "Save", () -> {
            Tv.rename(this, name.getText().toString());
            if (title != null) title.setText(Tv.title());
            if (tab == 3) showTab(3);
        }).show();
    }

    void confirmForget() {
        TextView body = Ui.text(this, "This phone will stop controlling " + Tv.title() + ". You can pair it again any time.", 15, Ui.SUB, false);
        body.setLineSpacing(0, 1.2f);
        Ui.dialog(this, "Forget " + Tv.title() + "?", body, "Forget", this::forget).show();
    }

    /** Forgets the TV in use; another paired TV takes over, or the pairing screen comes if it was the last. */
    void forget() {
        Tv.forget(this);
        online = null;
        askedToPairAgain = false;
        if (Tv.paired()) {
            showTv();
        } else {
            tab = 0;
            showFind(false);
        }
    }

    /** The TV in use no longer knows this phone: forget it here and pair it again (the others stay). */
    private void pairAgain() {
        Tv.forget(this);
        online = null;
        showFind(Tv.paired());
    }
}
