package io.freemytv.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import io.freemytv.proto.Proto;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Free My TV Remote. Not paired: finds the TVs on the Wi-Fi that run Free My TV and pairs with the PIN the TV shows.
 * Paired: the TV's apps (tap to open on the TV), a remote, and the TV's settings. Sharing a link to this app is
 * ShareActivity. All the talking is in Tv; this class is screens.
 */
public class MainActivity extends Activity {
    private static final int ACCENT = Color.rgb(120, 170, 255), CARD = Color.rgb(28, 30, 36);

    private LinearLayout root;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;
    private LinearLayout found;
    private final Map<String, Bitmap> icons = new HashMap<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Tv.load(this);
        nsd = (NsdManager) getSystemService(NSD_SERVICE);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(8));
        setContentView(root);
        if (Tv.paired()) showTv(); else showFind();
    }

    @Override protected void onDestroy() {
        stopDiscovery();
        super.onDestroy();
    }

    // MARK: - Finding and pairing

    private void showFind() {
        root.removeAllViews();
        root.addView(title("Find your TV"));
        root.addView(text("Your phone and TV must be on the same Wi-Fi, with Free My TV on the TV."));
        found = new LinearLayout(this);
        found.setOrientation(LinearLayout.VERTICAL);
        root.addView(found);
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
        nsd.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
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
            @Override public void onResolveFailed(NsdServiceInfo i, int code) { done(); }
            @Override public void onServiceResolved(NsdServiceInfo i) {
                InetAddress a = i.getHost();
                if (a instanceof Inet4Address || a != null && Tv.paired() == false) {
                    String host = a.getHostAddress();
                    int cut = host.indexOf('%');
                    final String address = cut < 0 ? host : host.substring(0, cut);
                    final String service = i.getServiceName();
                    final int port = i.getPort();
                    runOnUiThread(() -> seen(service, address, port));
                }
                done();
            }
            private void done() {
                synchronized (toResolve) {
                    resolving = false;
                }
                resolveNext();
            }
        });
    }

    /** A TV answered: offer it, or, if it is the one we are paired with, follow its new address. */
    private void seen(String service, String address, int port) {
        if (Tv.paired() && service.equals(Tv.svc)) {
            if (!address.equals(Tv.host) || port != Tv.port) {
                Tv.host = address;
                Tv.port = port;
                Tv.save(this);
            }
            return;
        }
        if (Tv.paired() || found == null) return;
        for (int i = 0; i < found.getChildCount(); i++) if (service.equals(found.getChildAt(i).getTag())) return;
        Button tv = button(service.replace("FreeMyTV-", "TV: ") + "  (" + address + ")");
        tv.setTag(service);
        tv.setOnClickListener(v -> pair(service, address, port));
        found.addView(tv);
    }

    private void pair(String service, String address, int port) {
        Tv.host = address;
        Tv.port = port;
        new Thread(() -> {
            try {
                Tv.Reply r = Tv.call("POST", "/pair/start", "{}", false);
                if (r.status != 200) throw new java.io.IOException(r.error());
                runOnUiThread(() -> askPin(service));
            } catch (Exception e) {
                toast("Can't reach that TV: " + e.getMessage());
            }
        }).start();
    }

    private void askPin(String service) {
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER);
        pin.setHint("4-digit code");
        new AlertDialog.Builder(this).setTitle("Code on your TV").setMessage("A 4-digit code appears on the TV screen.")
                .setView(pin).setNegativeButton("Cancel", null)
                .setPositiveButton("Pair", (d, w) -> confirm(service, pin.getText().toString().trim())).show();
    }

    private void confirm(String service, String pin) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("pin", pin).put("name", android.os.Build.MODEL);
                Tv.Reply r = Tv.call("POST", "/pair/confirm", body.toString(), false);
                if (r.status != 200) throw new java.io.IOException(r.error());
                JSONObject ok = r.json();
                Tv.id = ok.getString("id");
                Tv.token = ok.getString("token");
                Tv.svc = service;
                Tv.name = service.replace("FreeMyTV-", "");
                try {
                    Tv.name = Tv.get("/info").json().optString("name", Tv.name);
                } catch (Exception ignored) {
                }
                Tv.save(this);
                runOnUiThread(() -> {
                    stopDiscovery();
                    showTv();
                });
            } catch (Exception e) {
                toast("Not paired: " + e.getMessage());
            }
        }).start();
    }

    // MARK: - Paired

    private LinearLayout content;

    private void showTv() {
        root.removeAllViews();
        root.addView(title(Tv.name));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Apps", "Remote", "Settings"};
        for (int i = 0; i < names.length; i++) {
            final int tab = i;
            Button t = button(names[i]);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            t.setOnClickListener(v -> showTab(tab));
            tabs.addView(t);
        }
        root.addView(tabs);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        startDiscovery();   // a new address for the same TV is picked up in seen()
        showTab(0);
    }

    private void showTab(int tab) {
        content.removeAllViews();
        if (tab == 0) showApps(); else if (tab == 1) showRemote(); else showSettings();
    }

    private void showApps() {
        TextView status = text("Loading…");
        content.addView(status);
        final GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(grid);
        content.addView(scroll);
        new Thread(() -> {
            try {
                Tv.Reply r = Tv.get("/apps");
                if (r.status == 401) {
                    runOnUiThread(() -> {
                        Tv.forget(this);
                        toast("Pairing was removed on the TV. Pair again.");
                        showFind();
                    });
                    return;
                }
                if (r.status != 200) throw new java.io.IOException(r.error());
                JSONArray apps = r.json().getJSONArray("apps");
                runOnUiThread(() -> content.removeView(status));
                // Shelf apps first, as on the TV.
                for (int pass = 0; pass < 2; pass++) {
                    for (int i = 0; i < apps.length(); i++) {
                        JSONObject a = apps.getJSONObject(i);
                        if (a.optBoolean("shelf") == (pass == 0)) runOnUiThread(() -> grid.addView(tile(a)));
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Can't reach the TV. Same Wi-Fi? Is the TV on?"));
            }
        }).start();
    }

    private View tile(JSONObject a) {
        final String pkg = a.optString("pkg");
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        ImageView icon = new ImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(96), dp(54)));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        t.addView(icon);
        TextView label = new TextView(this);
        label.setText(a.optString("label"));
        label.setTextColor(Color.WHITE);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        t.addView(label);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = (getResources().getDisplayMetrics().widthPixels - dp(32)) / 3;
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> send("/launch", new JSONObject(), "pkg", pkg, "Opening " + a.optString("label")));
        Bitmap cached = icons.get(pkg);
        if (cached != null) {
            icon.setImageBitmap(cached);
        } else {
            new Thread(() -> {
                try {
                    Tv.Reply r = Tv.get("/icon?pkg=" + pkg);
                    if (r.status != 200) return;
                    Bitmap bmp = BitmapFactory.decodeByteArray(r.body, 0, r.body.length);
                    icons.put(pkg, bmp);
                    runOnUiThread(() -> icon.setImageBitmap(bmp));
                } catch (Exception ignored) {
                }
            }).start();
        }
        return t;
    }

    private void showRemote() {
        int[][] rows = {{19}, {21, 23, 22}, {20}, {4, 3, 82}, {25, 164, 24}, {88, 85, 87}};
        String[][] names = {{"▲"}, {"◀", "OK", "▶"}, {"▼"}, {"Back", "Home", "Menu"}, {"Vol −", "Mute", "Vol +"}, {"⏮", "⏯", "⏭"}};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (int c = 0; c < rows[r].length; c++) {
                final int code = rows[r][c];
                Button k = button(names[r][c]);
                k.setLayoutParams(new LinearLayout.LayoutParams(dp(96), dp(64)));
                k.setOnClickListener(v -> send("/key", new JSONObject(), "code", code, null));
                row.addView(k);
            }
            content.addView(row);
        }
    }

    private void showSettings() {
        String[][] switches = {{"weather", "Weather"}, {"dateTime", "Date and time"}, {"quotes", "Coin prices"},
                {"photos", "My photos"}, {"sounds", "Click sounds"}};
        TextView status = text("Loading…");
        content.addView(status);
        new Thread(() -> {
            try {
                Tv.Reply r = Tv.get("/settings");
                if (r.status != 200) throw new java.io.IOException(r.error());
                JSONObject values = r.json().getJSONObject("values");
                runOnUiThread(() -> {
                    content.removeView(status);
                    for (String[] s : switches) {
                        Switch sw = new Switch(this);
                        sw.setText(s[1]);
                        sw.setTextColor(Color.WHITE);
                        sw.setPadding(0, dp(12), 0, dp(12));
                        sw.setChecked(values.optBoolean(s[0], true));
                        sw.setOnCheckedChangeListener((v, on) -> {
                            if (!v.isPressed()) return;
                            try {
                                send("/settings", new JSONObject().put("values", new JSONObject().put(s[0], on)), null, null, null);
                            } catch (Exception ignored) {
                            }
                        });
                        content.addView(sw);
                    }
                    Button forget = button("Forget this TV");
                    forget.setOnClickListener(v -> {
                        Tv.forget(this);
                        showFind();
                    });
                    content.addView(forget);
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Can't reach the TV. Same Wi-Fi? Is the TV on?"));
            }
        }).start();
    }

    /** POSTs `body` (plus one field, if given) to the TV and shows `done` or the problem. */
    private void send(String path, JSONObject body, String field, Object value, String done) {
        new Thread(() -> {
            try {
                if (field != null) body.put(field, value);
                Tv.Reply r = Tv.post(path, body);
                if (r.status != 200) toast(r.error());
                else if (done != null) toast(done);
            } catch (Exception e) {
                toast("Can't reach the TV. Same Wi-Fi?");
            }
        }).start();
    }

    // MARK: - Small helpers

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void toast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(24);
        t.setPadding(0, 0, 0, dp(12));
        return t;
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.LTGRAY);
        t.setPadding(0, dp(8), 0, dp(12));
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(CARD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        return b;
    }
}
