package io.freemytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import io.freemytv.proto.Proto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The phone app's door into the TV (Free My TV Remote): a small HTTP server on the home Wi-Fi, announced on the network
 * so the phone finds it. It lives in the :home process, next to the screens whose settings it changes. Anyone on the
 * network can ask who we are (/info); everything else needs the token a phone gets by pairing with the PIN shown on
 * the TV, and every request is signed with it (Proto.sign). Only addresses of the local network are served.
 */
public class Companion extends Service {
    private static final String CHANNEL = "companion";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_.]+");
    private static final int MAX_BODY = 64 * 1024;
    /** Keys the phone's remote may press: arrows, OK, back, home, menu, play/pause, volume, mute, channel. */
    private static final int[] KEYS = {19, 20, 21, 22, 23, 4, 3, 82, 85, 86, 87, 88, 89, 90, 24, 25, 164, 166, 167, 126, 127, 66, 67};

    private final SecureRandom random = new SecureRandom();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerSocket server;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;

    // Pairing: one PIN at a time.
    private String pin;
    private long pinUntil;
    private int pinTries;

    /** Starts the service (the TV booted, was updated, or the home screen opened). */
    static void start(Context c) {
        try {
            Intent i = new Intent(c, Companion.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (RuntimeException e) {
            AppLog.w("companion not started: " + e.getMessage());   // background start refused: the next home screen start tries again
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        startForeground(1, notification());
        pool.execute(() -> serve());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override public void onDestroy() {
        unregister();
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
        super.onDestroy();
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Phone remote", NotificationManager.IMPORTANCE_MIN));
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.icon).setContentTitle("Free My TV")
                .setContentText("Ready for the phone app").build();
    }

    // MARK: - Network

    private void serve() {
        try {
            try {
                server = new ServerSocket(Proto.PORT);
            } catch (IOException busy) {
                server = new ServerSocket(0);
            }
        } catch (IOException e) {
            AppLog.w("companion can't listen: " + e.getMessage());
            return;
        }
        register(server.getLocalPort());
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                break;
            }
        }
    }

    private void register(int port) {
        try {
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("FreeMyTV-" + Build.MODEL.replaceAll("[^A-Za-z0-9]", ""));
            info.setServiceType(Proto.SERVICE_TYPE);
            info.setPort(port);
            registration = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo i, int code) { AppLog.w("companion announce failed: " + code); }
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int code) {}
                @Override public void onServiceRegistered(NsdServiceInfo i) {}
                @Override public void onServiceUnregistered(NsdServiceInfo i) {}
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (RuntimeException e) {
            AppLog.w("companion announce failed: " + e.getMessage());
        }
    }

    private void unregister() {
        try {
            if (nsd != null && registration != null) nsd.unregisterService(registration);
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean local(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;   // fc00::/7, the IPv6 private range
    }

    // MARK: - Requests

    private static final class Request {
        String method, path, body = "";
        final java.util.Map<String, String> headers = new java.util.HashMap<>();
    }

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(8000);
            if (!local(socket.getInetAddress())) return;
            Request r = read(socket.getInputStream());
            if (r == null) return;
            Object[] reply;
            try {
                reply = route(r);
            } catch (JSONException | RuntimeException e) {
                reply = new Object[] {400, json("error", String.valueOf(e.getMessage()))};
            }
            write(socket.getOutputStream(), (Integer) reply[0], reply[1]);
        } catch (IOException ignored) {
        }
    }

    private static Request read(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int c, last = 0;
        while ((c = in.read()) >= 0) {
            head.write(c);
            last = (last << 8 | c) & 0xffffffff;
            if (last == 0x0d0a0d0a || head.size() > 8192) break;
        }
        String[] lines = new String(head.toByteArray(), StandardCharsets.UTF_8).split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;
        Request r = new Request();
        r.method = first[0];
        r.path = first[1];
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) r.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        int length = 0;
        try {
            length = Integer.parseInt(r.headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException ignored) {
        }
        if (length < 0 || length > MAX_BODY) return null;
        byte[] body = new byte[length];
        for (int got = 0; got < length; ) {
            int n = in.read(body, got, length - got);
            if (n < 0) return null;
            got += n;
        }
        r.body = new String(body, StandardCharsets.UTF_8);
        return r;
    }

    private static void write(OutputStream out, int status, Object body) throws IOException {
        byte[] data;
        String type;
        if (body instanceof byte[]) {
            data = (byte[]) body;
            type = "image/png";
        } else {
            data = String.valueOf(body).getBytes(StandardCharsets.UTF_8);
            type = "application/json; charset=utf-8";
        }
        out.write(("HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    private static String json(String key, Object value) {
        try {
            return new JSONObject().put(key, value).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** {status, body: String (JSON) or byte[] (PNG)} */
    private Object[] route(Request r) throws JSONException {
        String path = r.path.indexOf('?') >= 0 ? r.path.substring(0, r.path.indexOf('?')) : r.path;
        if (path.equals("/info")) return ok(info());
        if (path.equals("/pair/start") && r.method.equals("POST")) return pairStart();
        if (path.equals("/pair/confirm") && r.method.equals("POST")) return pairConfirm(new JSONObject(r.body));

        String id = r.headers.get(Proto.H_ID.toLowerCase(Locale.ROOT));
        if (!authorized(r, id)) return new Object[] {401, json("error", "not paired")};

        switch (path) {
            case "/apps": return ok(apps());
            case "/icon": return icon(r.path);
            case "/launch": return launch(new JSONObject(r.body).optString("pkg"));
            case "/share": return share(new JSONObject(r.body).optString("url"));
            case "/key": return key(new JSONObject(r.body).optInt("code", -1));
            case "/settings":
                if (r.method.equals("GET")) return ok(Prefs.exportAll().toString());
                Prefs.importAll(new JSONObject(r.body));
                return ok(Prefs.exportAll().toString());
            default: return new Object[] {404, json("error", "unknown")};
        }
    }

    private static Object[] ok(String body) { return new Object[] {200, body}; }

    private String info() throws JSONException {
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return new JSONObject().put("app", "freemytv").put("proto", Proto.VERSION).put("version", version)
                .put("name", Build.BRAND + " " + Build.MODEL).put("home", HomeMode.FREE.equals(HomeMode.state(this).optString("mode"))).toString();
    }

    // MARK: - Pairing and signatures

    private synchronized Object[] pairStart() {
        pin = String.format(Locale.ROOT, "%04d", random.nextInt(10000));
        pinUntil = System.currentTimeMillis() + 120_000;
        pinTries = 0;
        showPin(pin, pinUntil);
        return ok(json("ok", true));
    }

    /** The PIN as a notice on the TV every few seconds, until it is used or runs out. */
    private void showPin(String shown, long until) {
        main.post(new Runnable() {
            @Override public void run() {
                synchronized (Companion.this) {
                    if (!shown.equals(pin) || System.currentTimeMillis() > until) return;
                }
                Toast.makeText(Companion.this, "Free My TV phone code: " + shown, Toast.LENGTH_LONG).show();
                main.postDelayed(this, 3500);
            }
        });
    }

    private synchronized Object[] pairConfirm(JSONObject in) throws JSONException {
        String tried = in.optString("pin");
        if (pin == null || System.currentTimeMillis() > pinUntil || pinTries >= 5) {
            pin = null;
            return new Object[] {403, json("error", "ask the TV for a new code")};
        }
        pinTries++;
        if (!Proto.same(pin, tried)) return new Object[] {403, json("error", "wrong code")};
        pin = null;
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        StringBuilder token = new StringBuilder(), id = new StringBuilder();
        for (byte b : raw) token.append(String.format("%02x", b));
        for (int i = 0; i < 6; i++) id.append(String.format("%02x", random.nextInt(256)));
        JSONObject devices = devices();
        devices.put(id.toString(), new JSONObject().put("token", token.toString()).put("name", in.optString("name", "Phone")).put("last", 0L));
        saveDevices(devices);
        return ok(new JSONObject().put("id", id.toString()).put("token", token.toString()).toString());
    }

    private SharedPreferences store() { return getSharedPreferences("companion", MODE_PRIVATE); }

    private JSONObject devices() {
        try {
            return new JSONObject(store().getString("devices", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void saveDevices(JSONObject devices) { store().edit().putString("devices", devices.toString()).apply(); }

    private synchronized boolean authorized(Request r, String id) {
        try {
            JSONObject devices = devices();
            JSONObject d = id == null ? null : devices.optJSONObject(id);
            String sig = r.headers.get(Proto.H_SIG.toLowerCase(Locale.ROOT)), ts = r.headers.get(Proto.H_TS.toLowerCase(Locale.ROOT));
            if (d == null || sig == null || ts == null) return false;
            long stamp = Long.parseLong(ts);
            if (stamp <= d.optLong("last")) return false;   // a request sent before, or out of order
            if (!Proto.same(Proto.sign(d.getString("token"), r.method, r.path, stamp, r.body), sig)) return false;
            d.put("last", stamp);
            saveDevices(devices);
            return true;
        } catch (JSONException | NumberFormatException e) {
            return false;
        }
    }

    // MARK: - What the phone can do

    private String apps() throws JSONException {
        PackageManager pm = getPackageManager();
        List<String> shelf = HomeApps.shelfPackages(this);
        JSONArray list = new JSONArray();
        for (String[] a : HomeApps.launchable(this)) {
            if (a[0].equals(getPackageName())) continue;
            list.put(new JSONObject().put("pkg", a[0]).put("label", a[1]).put("shelf", shelf.contains(a[0])));
        }
        return new JSONObject().put("apps", list).put("shelf", new JSONArray(shelf)).toString();
    }

    private Object[] icon(String query) {
        int at = query.indexOf("pkg=");
        String pkg = at < 0 ? "" : query.substring(at + 4);
        if (!PACKAGE.matcher(pkg).matches()) return new Object[] {400, json("error", "bad package")};
        try {
            PackageManager pm = getPackageManager();
            Drawable d = pm.getApplicationBanner(pkg);
            if (d == null) d = pm.getApplicationIcon(pkg);
            int w = d.getIntrinsicWidth() > d.getIntrinsicHeight() * 1.4 ? 256 : 144, h = w == 256 ? 144 : 144;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, w, h);
            d.draw(new Canvas(b));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 90, out);
            b.recycle();
            return new Object[] {200, out.toByteArray()};
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return new Object[] {404, json("error", "no icon")};
        }
    }

    private Object[] launch(String pkg) {
        if (!PACKAGE.matcher(pkg).matches()) return new Object[] {400, json("error", "bad package")};
        Intent intent = HomeApps.launchIntent(getPackageManager(), pkg);
        ComponentName cn = intent == null ? null : intent.getComponent();
        if (cn == null) return new Object[] {404, json("error", "not on the TV")};
        return background(Build.VERSION.SDK_INT < 29 ? intent : null, "am start -n " + cn.flattenToShortString());
    }

    private Object[] share(String url) {
        // Only web links, and none that could end the shell's quote.
        if (!url.matches("https?://[^\\s'\"\\\\`$;&|<>]{1,1500}")) return new Object[] {400, json("error", "not a web link")};
        String host = android.net.Uri.parse(url).getHost();
        host = host == null ? "" : host.toLowerCase(Locale.ROOT);
        String yt = "com.google.android.youtube.tv";
        boolean youtube = host.equals("youtu.be") || host.endsWith("youtube.com");
        boolean has = false;
        try {
            has = getPackageManager().getApplicationInfo(yt, 0) != null;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        Intent view = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
        if (youtube && has) view.setPackage(yt);
        return background(Build.VERSION.SDK_INT < 29 ? view : null,
                "am start -a android.intent.action.VIEW" + (youtube && has ? " -p " + yt : "") + " -d '" + url + "'");
    }

    private Object[] key(int code) {
        for (int k : KEYS) if (k == code) return background(null, "input keyevent " + code);
        return new Object[] {400, json("error", "key not allowed")};
    }

    /**
     * Android 10+ keeps a background service from opening screens, so those go through the TV's own debugging
     * (the same door the switch uses, already allowed once). On Android 8 and 9 it is a plain startActivity.
     */
    private Object[] background(Intent direct, String shell) {
        if (direct != null) {
            try {
                startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return ok(json("ok", true));
            } catch (RuntimeException e) {
                AppLog.w("companion: " + e.getMessage());
            }
        }
        try (Adb adb = Adb.connect(this, 3000, null)) {
            adb.shell(shell);
            return ok(json("ok", true));
        } catch (IOException e) {
            return new Object[] {502, json("error", String.valueOf(e.getMessage()))};
        }
    }
}
