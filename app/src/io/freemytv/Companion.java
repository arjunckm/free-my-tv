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

import java.io.BufferedReader;
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
    /** A photo from the phone (a 16:9 JPEG of at most about 3 MB, as base64): the only request allowed to be bigger. */
    private static final int MAX_UPLOAD = 4 * 1024 * 1024;
    /** The most a pairing request (which needs no id) may send. */
    private static final int MAX_PUBLIC = 4 * 1024;
    /** A whole request, headers and body, must arrive within this long (a slow trickle can't hold a worker). */
    private static final long REQUEST_MS = 15_000;
    private static final int MAX_CACHE_FILES = 300;

    private final SecureRandom random = new SecureRandom();
    private final ExecutorService pool = Executors.newCachedThreadPool();   // the accept loop and background jobs
    /** One thread per request, up to 16 at once and 32 waiting: a flood of connections can't use up the TV. */
    private final ExecutorService workers = new java.util.concurrent.ThreadPoolExecutor(2, 16, 30, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<Runnable>(32));
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
        closeKeyHelper();
        workers.shutdownNow();
        pool.shutdownNow();
        closeAdb();
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
                try {
                    workers.execute(() -> handle(s));
                } catch (java.util.concurrent.RejectedExecutionException busy) {
                    s.close();   // too many at once: drop this one
                }
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
        String token;   // the paired phone's token, once authorized: the reply is signed with it
        long stamp;
        String remote = "";   // the caller's address
        int length;           // the body's declared size
    }

    private final java.util.concurrent.atomic.AtomicBoolean bigBody = new java.util.concurrent.atomic.AtomicBoolean();

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(8000);
            if (!local(socket.getInetAddress())) return;
            long deadline = android.os.SystemClock.elapsedRealtime() + REQUEST_MS;
            InputStream in = new java.io.BufferedInputStream(socket.getInputStream());
            Request r = readHead(in, deadline);
            if (r == null) return;
            r.remote = socket.getInetAddress().getHostAddress();
            // The size is checked, and the caller's id looked up, before a single byte of body is kept.
            String path = r.path.indexOf('?') >= 0 ? r.path.substring(0, r.path.indexOf('?')) : r.path;
            int limit;
            if (path.equals("/info")) {
                limit = 0;
            } else if (path.startsWith("/pair/")) {
                limit = MAX_PUBLIC;
            } else if (!knownCaller(r)) {
                write(socket.getOutputStream(), 401, json("error", "not paired"), r);
                return;
            } else {
                limit = path.equals("/photos/add") ? MAX_UPLOAD : MAX_BODY;
            }
            if (r.length > limit) {
                write(socket.getOutputStream(), 413, json("error", "too big"), r);
                return;
            }
            // A phone's id travels in the clear, so a big body is read before its signature can be checked: one such upload at a time.
            boolean big = r.length > MAX_BODY;
            if (big && !bigBody.compareAndSet(false, true)) {
                write(socket.getOutputStream(), 429, json("error", "busy"), r);
                return;
            }
            try {
                if (!readBody(in, r, deadline)) return;
            } finally {
                if (big) bigBody.set(false);
            }
            Object[] reply;
            try {
                long t0 = android.os.SystemClock.elapsedRealtime();
                reply = route(r);
                AppLog.w("companion " + r.method + " " + r.path.split("\\?")[0] + " -> " + reply[0] + " in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
            } catch (JSONException e) {
                AppLog.w("companion: bad request: " + e);
                reply = new Object[] {400, json("error", "bad request")};
            } catch (RuntimeException e) {
                AppLog.w("companion: error on " + r.path.split("\\?")[0] + ": " + e);   // a fault on the TV, not the phone's doing
                reply = new Object[] {500, json("error", "the TV had a problem")};
            }
            write(socket.getOutputStream(), (Integer) reply[0], reply[1], r);
        } catch (IOException ignored) {
        }
    }

    /** Cheap look before any body is read: the request names a paired phone and carries a signature and a timestamp. */
    private boolean knownCaller(Request r) {
        String id = r.headers.get(Proto.H_ID.toLowerCase(Locale.ROOT));
        if (id == null || !r.headers.containsKey(Proto.H_SIG.toLowerCase(Locale.ROOT))) return false;
        try {
            Long.parseLong(r.headers.get(Proto.H_TS.toLowerCase(Locale.ROOT)));
        } catch (NumberFormatException e) {
            return false;
        }
        return devices().has(id);
    }

    private static boolean readBody(InputStream in, Request r, long deadline) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(Math.min(r.length, 8192));
        byte[] chunk = new byte[8192];
        while (body.size() < r.length) {
            if (android.os.SystemClock.elapsedRealtime() > deadline) return false;
            int n = in.read(chunk, 0, Math.min(chunk.length, r.length - body.size()));
            if (n < 0) return false;
            body.write(chunk, 0, n);
        }
        r.body = new String(body.toByteArray(), StandardCharsets.UTF_8);
        return true;
    }

    /** The request line and headers; the body is read later, once its size is known to be acceptable. */
    private static Request readHead(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int c, last = 0;
        boolean ended = false;
        while ((c = in.read()) >= 0) {
            if (android.os.SystemClock.elapsedRealtime() > deadline) return null;
            head.write(c);
            last = (last << 8) | c;
            if (last == 0x0d0a0d0a) {
                ended = true;
                break;
            }
            if (head.size() > 8192) return null;   // no end of headers in a sensible size
        }
        if (!ended) return null;
        String[] lines = new String(head.toByteArray(), StandardCharsets.UTF_8).split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2 || first[1].length() > 2048) return null;
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
        if (length < 0) return null;
        r.length = length;
        return r;
    }

    private static void write(OutputStream out, int status, Object body, Request r) throws IOException {
        byte[] data;
        String type;
        if (body instanceof byte[]) {
            data = (byte[]) body;
            type = "image/png";
        } else {
            data = String.valueOf(body).getBytes(StandardCharsets.UTF_8);
            type = "application/json; charset=utf-8";
        }
        // A reply to a paired phone carries our signature, so the phone knows it is really from this TV.
        String signature = r.token == null ? "" : Proto.H_SIG + ": " + Proto.signReply(r.token, r.stamp, status, data) + "\r\n";
        out.write(("HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\nContent-Type: " + type + "\r\n" + signature
                + "Content-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
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
        if (path.equals("/pair/start") && r.method.equals("POST")) return pairStart(r.remote);
        if (path.equals("/pair/confirm") && r.method.equals("POST")) return pairConfirm(r.remote, new JSONObject(r.body));

        String id = r.headers.get(Proto.H_ID.toLowerCase(Locale.ROOT));
        if (!authorized(r, id)) return new Object[] {401, json("error", "not paired")};

        switch (path) {
            case "/apps": return ok(apps());
            case "/icon": return icon(r.path);
            case "/launch": return launch(new JSONObject(r.body).optString("pkg"));
            case "/share": return share(new JSONObject(r.body).optString("url"));
            case "/key": return key(new JSONObject(r.body).optInt("code", -1));
            case "/settings":
                if (!r.method.equals("GET")) Prefs.importAll(new JSONObject(r.body));
                return ok(settings().toString());
            case "/city": return city(r.path);
            case "/photos": return ok(photos());
            case "/photo": return photo(r.path);
            case "/photos/add": return addPhoto(new JSONObject(r.body));
            case "/photos/delete": return deletePhotos(new JSONObject(r.body));
            case "/unpair": return unpair(id);
            default: return new Object[] {404, json("error", "unknown")};
        }
    }

    /** Every setting, plus what the phone needs to show them the way the TV does. */
    private JSONObject settings() throws JSONException {
        JSONObject out = Prefs.exportAll();
        JSONArray languages = new JSONArray();
        for (String code : Ui.languages()) languages.put(new JSONObject().put("code", code).put("name", Ui.languageName(code)));
        List<String> top = Prefs.topButtons() != null ? Prefs.topButtons() : HomeApps.TOP_DEFAULT;
        int max = HomeApps.maxShelf(this);
        JSONObject meta = new JSONObject().put("languages", languages).put("keyLabels", new JSONArray(java.util.Arrays.asList(Prefs.keyLabels())))
                .put("topButtons", new JSONArray(top)).put("topCandidates", topCandidates()).put("shelf", new JSONArray(HomeApps.shelfPackages(this)))
                .put("maxShelf", max == Integer.MAX_VALUE ? -1 : max).put("dollar", "BRL".equalsIgnoreCase(Config.CURRENCY.trim()))
                .put("photos", Photos.files(this).size()).put("keyCodes", new JSONArray(toList(Prefs.KEYS)))
                .put("mode", HomeMode.FREE.equals(HomeMode.state(this).optString("mode")) ? "free" : "original")
                .put("keyNothing", RemoteKeys.SONY ? "Sony default" : RemoteKeys.XIAOMI_MI ? "Xiaomi default" : "Nothing");
        return out.put("meta", meta);
    }

    /** What can go in the top buttons, as [{key, label}], named by the TV. */
    private static JSONArray topCandidates() throws JSONException {
        JSONArray out = new JSONArray();
        for (String[] c : HomeApps.topCandidates()) out.put(new JSONObject().put("key", c[0]).put("label", c[1]));
        return out;
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> l = new java.util.ArrayList<>();
        for (int v : a) l.add(v);
        return l;
    }

    /** Cities by name, as the TV's own search finds them: [{label, name, lat, lon}]. */
    private Object[] city(String query) throws JSONException {
        String raw = null;
        int mark = query.indexOf('?');
        for (String part : (mark < 0 ? "" : query.substring(mark + 1)).split("&")) {
            if (part.startsWith("q=")) {
                raw = part.substring(2);
                break;
            }
        }
        if (raw == null || raw.length() > 300) return new Object[] {400, json("error", "no name")};
        try {
            String name = java.net.URLDecoder.decode(raw, "UTF-8").trim();
            if (name.isEmpty() || name.length() > 100) return new Object[] {400, json("error", "no name")};
            JSONArray found = new JSONArray();
            for (String[] c : HomeData.findCity(name)) {
                found.put(new JSONObject().put("label", c[0]).put("name", c[1]).put("lat", c[2]).put("lon", c[3]));
            }
            return ok(new JSONObject().put("cities", found).toString());
        } catch (JSONException e) {
            throw e;
        } catch (Exception e) {
            return new Object[] {502, json("error", String.valueOf(e.getMessage()))};
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

    // Pairing limits: a new code at most every 5 s, 5 tries per code, and after 8 wrong codes from one address (24 in all),
    // a pause that doubles each time. A pairing in progress is not replaced by another address, nor spent by one.
    private java.security.KeyPair pairKeys;
    private String pairRemote = "";
    private long nextStartAt;
    private static final int MAX_PHONES = 8;

    /** Wrong codes so far and the pause that follows too many; one per caller's address, and one for everybody. */
    private static final class Strikes {
        int failures;
        long lockedUntil, lockMs = 5 * 60_000;

        boolean locked(long now) { return now < lockedUntil; }

        void wrong(long now, int limit) {
            if (++failures < limit) return;
            lockedUntil = now + lockMs;
            lockMs = Math.min(lockMs * 2, 3_600_000);
            failures = 0;
        }

        void right() {
            failures = 0;
            lockMs = 5 * 60_000;
        }
    }

    private final Strikes allStrikes = new Strikes();
    private final java.util.Map<String, Strikes> strikesBy = new java.util.HashMap<>();

    private Strikes strikes(String remote) {
        if (strikesBy.size() > 64) strikesBy.clear();   // a flood of addresses can't grow it
        Strikes s = strikesBy.get(remote);
        if (s == null) strikesBy.put(remote, s = new Strikes());
        return s;
    }

    private static final String LOCKED = "too many wrong codes: try again in a few minutes";

    private synchronized Object[] pairStart(String remote) throws JSONException {
        long now = System.currentTimeMillis();
        if (allStrikes.locked(now) || strikes(remote).locked(now)) return new Object[] {429, json("error", LOCKED)};
        if (pin != null && now < pinUntil && !remote.equals(pairRemote)) return new Object[] {429, json("error", "another phone is pairing: try again in a minute")};
        if (now < nextStartAt) return new Object[] {429, json("error", "wait a few seconds and try again")};
        nextStartAt = now + 5000;
        try {
            pairKeys = Proto.newKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            return new Object[] {500, json("error", "can't pair on this TV")};
        }
        pin = String.format(Locale.ROOT, "%0" + Proto.PIN_DIGITS + "d", random.nextInt((int) Math.pow(10, Proto.PIN_DIGITS)));
        pairRemote = remote;
        pinUntil = now + 120_000;
        pinTries = 0;
        showPin(pin, pinUntil);
        // Our public key, so both sides can agree a secret nobody listening can work out.
        return ok(new JSONObject().put("pub", java.util.Base64.getEncoder().encodeToString(pairKeys.getPublic().getEncoded())).toString());
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

    /**
     * The phone sends its public key and a proof that it knows the PIN (an HMAC under the key we now share). The PIN itself
     * never travels, and the token is worked out on both sides from the shared key, so it never travels either.
     */
    private synchronized Object[] pairConfirm(String remote, JSONObject in) throws JSONException {
        long now = System.currentTimeMillis();
        Strikes mine = strikes(remote);
        if (allStrikes.locked(now) || mine.locked(now)) return new Object[] {429, json("error", LOCKED)};
        if (pin == null || pairKeys == null || now > pinUntil || pinTries >= 5 || !remote.equals(pairRemote)) {
            if (remote.equals(pairRemote) || pin == null || now > pinUntil) {   // somebody else's attempt doesn't spend this pairing
                pin = null;
                pairKeys = null;
            }
            return new Object[] {403, json("error", "ask the TV for a new code")};
        }
        pinTries++;
        byte[] secret;
        try {
            byte[] key = Proto.agree(pairKeys.getPrivate(), java.util.Base64.getDecoder().decode(in.optString("pub")));
            secret = Proto.secret(key, pin);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            return new Object[] {400, json("error", "bad request")};
        }
        if (!Proto.same(Proto.proof(secret, "phone"), in.optString("proof"))) {
            mine.wrong(now, 8);
            allStrikes.wrong(now, 24);
            if (mine.locked(now) || allStrikes.locked(now)) {
                pin = null;
                pairKeys = null;
            }
            return new Object[] {403, json("error", "wrong code")};
        }
        pin = null;
        pairKeys = null;
        mine.right();
        allStrikes.right();
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 6; i++) id.append(String.format("%02x", random.nextInt(256)));
        JSONObject devices = devices();
        // At most MAX_PHONES: a new one replaces the one that was used longest ago.
        while (devices.length() >= MAX_PHONES) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (java.util.Iterator<String> it = devices.keys(); it.hasNext(); ) {
                String k = it.next();
                long at = store().getLong("last-" + k, 0);
                if (at < oldestAt) {
                    oldestAt = at;
                    oldest = k;
                }
            }
            devices.remove(oldest);
            forget(oldest);
        }
        String name = in.optString("name", "Phone");
        devices.put(id.toString(), new JSONObject().put("token", Proto.token(secret)).put("name", name.length() > 40 ? name.substring(0, 40) : name)
                .put("last", 0L));
        saveDevices(devices);
        // Our own proof, so the phone knows it paired with this TV and not with something pretending to be it.
        return ok(new JSONObject().put("id", id.toString()).put("proof", Proto.proof(secret, "tv")).toString());
    }

    /** The phone forgot this TV: its token stops working. */
    private synchronized Object[] unpair(String id) throws JSONException {
        JSONObject devices = devices();
        devices.remove(id);
        saveDevices(devices);
        forget(id);
        return ok(json("ok", true));
    }

    /** Drops what is kept to refuse repeated requests from a phone that is no longer paired. */
    private void forget(String id) {
        floors.remove(id);
        recent.remove(id);
        store().edit().remove("last-" + id).apply();
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

    private final java.util.Map<String, Long> floors = new java.util.HashMap<>();
    private final java.util.Map<String, java.util.TreeSet<Long>> recent = new java.util.HashMap<>();

    private synchronized boolean authorized(Request r, String id) {
        try {
            JSONObject devices = devices();
            JSONObject d = id == null ? null : devices.optJSONObject(id);
            String sig = r.headers.get(Proto.H_SIG.toLowerCase(Locale.ROOT)), ts = r.headers.get(Proto.H_TS.toLowerCase(Locale.ROOT));
            if (d == null || sig == null || ts == null) return false;
            long stamp = Long.parseLong(ts);
            // Requests from one phone can arrive out of order, so a recent timestamp is fine once; an old or repeated one is not.
            // The floor is the newest timestamp accepted before this process started (stored on every newer one): nothing
            // from before a restart can be replayed.
            Long floor = floors.get(id);
            if (floor == null) floors.put(id, floor = Math.max(d.optLong("last"), store().getLong("last-" + id, 0)));
            java.util.TreeSet<Long> seen = recent.get(id);
            if (seen == null) recent.put(id, seen = new java.util.TreeSet<>());
            long newest = seen.isEmpty() ? floor : Math.max(floor, seen.last());
            if (stamp <= floor || stamp < newest - 30_000 || seen.contains(stamp)) return false;
            if (!Proto.same(Proto.sign(d.getString("token"), r.method, r.path, stamp, r.body), sig)) return false;
            if (stamp > newest) store().edit().putLong("last-" + id, stamp).apply();   // a restart will not accept anything older
            seen.add(stamp);
            r.token = d.getString("token");
            r.stamp = stamp;
            while (seen.size() > 256) seen.pollFirst();
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

    /**
     * Keeps a reply in the cache: written under another name and then renamed, so a write that is cut short never leaves a
     * half file to be served; the oldest go when there are more than MAX_CACHE_FILES of that kind.
     */
    private void cacheWrite(java.io.File target, byte[] data, String prefix) {
        java.io.File tmp = new java.io.File(target.getParentFile(), ".tmp-" + target.getName());
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(tmp)) {
            f.write(data);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        java.io.File[] all = target.getParentFile().listFiles((dir, name) -> name.startsWith(prefix));
        if (all == null || all.length <= MAX_CACHE_FILES) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < all.length - MAX_CACHE_FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            all[i].delete();
        }
    }

    private Object[] icon(String query) {
        int at = query.indexOf("pkg=");
        String pkg = at < 0 ? "" : query.substring(at + 4);
        if (!PACKAGE.matcher(pkg).matches()) return new Object[] {400, json("error", "bad package")};
        java.io.File cached = new java.io.File(getCacheDir(), "phone-icon-" + pkg + ".png");
        try {
            long updated = getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime;
            if (cached.exists() && cached.lastModified() > updated) {
                java.io.FileInputStream in = new java.io.FileInputStream(cached);
                try {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
                    return new Object[] {200, buf.toByteArray()};
                } finally {
                    in.close();
                }
            }
        } catch (PackageManager.NameNotFoundException | IOException ignored) {
        }
        try {
            PackageManager pm = getPackageManager();
            Drawable d = pm.getApplicationBanner(pkg);
            if (d == null) d = pm.getApplicationIcon(pkg);
            int w = d.getIntrinsicWidth() > d.getIntrinsicHeight() * 1.4 ? 256 : 144, h = 144;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, w, h);
            d.draw(new Canvas(b));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 90, out);
            b.recycle();
            cacheWrite(cached, out.toByteArray(), "phone-icon-");
            return new Object[] {200, out.toByteArray()};
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return new Object[] {404, json("error", "no icon")};
        }
    }

    /** My Photos: the names of the imported photos, and the ones the hero shows while it is off (the phone lets you choose them). */
    private String photos() throws JSONException {
        JSONArray files = new JSONArray(), chosen = new JSONArray();
        for (java.io.File f : Photos.files(this)) files.put(f.getName());
        for (java.io.File f : Photos.wallpapers(this)) chosen.put(f.getName());
        return new JSONObject().put("files", files).put("chosen", chosen).toString();
    }

    /** A small preview (480 px wide, JPEG) of one photo, kept in the cache after the first time. Only names in the list are served. */
    private Object[] photo(String query) {
        int at = query.indexOf("name=");
        String name = at < 0 ? "" : query.substring(at + 5);
        java.io.File source = null;
        for (java.io.File f : Photos.files(this)) if (f.getName().equals(name)) source = f;
        if (source == null) return new Object[] {404, json("error", "no such photo")};
        java.io.File cached = new java.io.File(getCacheDir(), "phone-photo-" + name);
        try {
            if (!cached.exists() || cached.lastModified() < source.lastModified()) {   // a photo replaced under the same name is drawn again
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = 4;   // 2112 px wide -> 528
                Bitmap b = android.graphics.BitmapFactory.decodeFile(source.getPath(), o);
                if (b == null) return new Object[] {404, json("error", "unreadable")};
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                try {
                    b.compress(Bitmap.CompressFormat.JPEG, 80, jpeg);
                } finally {
                    b.recycle();
                }
                cacheWrite(cached, jpeg.toByteArray(), "phone-photo-");
            }
            java.io.FileInputStream in = new java.io.FileInputStream(cached);
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
                return new Object[] {200, buf.toByteArray()};
            } finally {
                in.close();
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return new Object[] {500, json("error", "can't read the photo")};
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean uploading = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * A photo from the phone: it goes into the inbox where Photos imports from (turned upright, cropped to 16:9, re-encoded), the
     * same as one sent from a computer. The phone has already done the cropping, so this is quick.
     */
    private Object[] addPhoto(JSONObject in) throws JSONException {
        if (Photos.files(this).size() >= Photos.MAX) return new Object[] {409, json("error", "The TV already has " + Photos.MAX + " photos: delete some first")};
        if (!uploading.compareAndSet(false, true)) return new Object[] {429, json("error", "The TV is still taking the last photo")};
        try {
            byte[] jpeg = java.util.Base64.getDecoder().decode(in.optString("data"));
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
            if (bounds.outWidth < 640 || bounds.outHeight < 360) return new Object[] {400, json("error", "That isn't a photo big enough for a TV")};
            java.io.File dir = Photos.privateInbox(this);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String base = System.currentTimeMillis() + "-" + random.nextInt(1000);
            java.io.File part = new java.io.File(dir, ".part-" + base);   // a hidden name until it is complete
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
                out.write(jpeg);
            }
            if (!part.renameTo(new java.io.File(dir, "phone-" + base + ".jpg"))) return new Object[] {500, json("error", "Couldn't save the photo")};
            Photos.importInbox(this, null);
            return ok(json("ok", true));
        } catch (IllegalArgumentException | IOException e) {
            return new Object[] {400, json("error", "Couldn't read the photo")};
        } finally {
            uploading.set(false);
        }
    }

    /** Removes photos by name (only names in the TV's list count). */
    private Object[] deletePhotos(JSONObject in) throws JSONException {
        JSONArray names = in.optJSONArray("names");
        List<String> list = new java.util.ArrayList<>();
        List<String> have = new java.util.ArrayList<>();
        for (java.io.File f : Photos.files(this)) have.add(f.getName());
        for (int i = 0; names != null && i < names.length() && i < 200; i++) {
            String name = names.getString(i);
            if (have.contains(name)) list.add(name);   // only names the TV has: nothing the phone made up reaches a file name
        }
        int removed = Photos.delete(this, list);
        for (String name : list) {
            //noinspection ResultOfMethodCallIgnored
            new java.io.File(getCacheDir(), "phone-photo-" + name).delete();   // its preview
        }
        return ok(new JSONObject().put("removed", removed).put("left", Photos.files(this).size()).toString());
    }

    private Object[] launch(String pkg) {
        if (!PACKAGE.matcher(pkg).matches()) return new Object[] {400, json("error", "bad package")};
        Intent intent = HomeApps.launchIntent(getPackageManager(), pkg);
        ComponentName cn = intent == null ? null : intent.getComponent();
        if (cn == null) return new Object[] {404, json("error", "not on the TV")};
        return background(Build.VERSION.SDK_INT < 29 ? intent : null, "am start -n " + cn.flattenToShortString());
    }

    private Object[] share(String url) {
        // Only web links, without spaces or control characters. The address goes to the shell inside single quotes, where only
        // the quote itself is special (it is written as %27), so & ? ; $ and the rest of a real link are fine.
        if (!url.matches("https?://[^\\s\\p{Cntrl}]{1,1500}")) return new Object[] {400, json("error", "not a web link")};
        url = url.replace("'", "%27");
        String host = android.net.Uri.parse(url).getHost();
        host = host == null ? "" : host.toLowerCase(Locale.ROOT);
        String yt = "com.google.android.youtube.tv";
        boolean youtube = host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com");
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
        for (int k : KeyServer.KEYS) {
            if (k != code) continue;
            // The key helper presses it at once; without it, the slow `input keyevent` does.
            if (pressKey(code)) return ok(json("ok", true));
            return background(null, "input keyevent " + code);
        }
        return new Object[] {400, json("error", "key not allowed")};
    }

    // MARK: - Key helper (KeyServer)

    private static final int KEY_PORT = 8766;
    private final Object keyLock = new Object();
    private Socket keySocket;
    private BufferedReader keyIn;
    private OutputStream keyOut;

    private String keyToken() {
        SharedPreferences sp = store();
        String t = sp.getString("keytoken", null);
        if (t == null) {
            byte[] raw = new byte[16];
            random.nextBytes(raw);
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            t = sb.toString();
            sp.edit().putString("keytoken", t).apply();
        }
        return t;
    }

    /**
     * True when the helper pressed the key. When it isn't running yet it is started in the background (it takes several
     * seconds on a small TV) and this key is pressed the slow way instead.
     */
    private boolean pressKey(int code) {
        synchronized (keyLock) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (keySocket == null && !openKeyHelper()) return false;
                    keyOut.write((keyToken() + " key " + code + "\n").getBytes(StandardCharsets.US_ASCII));
                    keyOut.flush();
                    if ("ok".equals(keyIn.readLine())) return true;
                } catch (IOException e) {
                    AppLog.w("key helper: " + e.getMessage());
                }
                closeKeyHelper();
            }
            return false;
        }
    }

    private void closeKeyHelper() {
        try {
            if (keySocket != null) keySocket.close();
        } catch (IOException ignored) {
        }
        keySocket = null;
    }

    private long keyStartedAt = -60_000;

    /** Connects to the helper. False when it isn't running (it is being started) or is an older version (it is asked to leave). */
    private boolean openKeyHelper() {
        Socket s = new Socket();
        try {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", KEY_PORT), 400);
            s.setSoTimeout(3000);
            BufferedReader in = new BufferedReader(new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = s.getOutputStream();
            out.write((keyToken() + " hello\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            if (("ok " + KeyServer.VERSION).equals(in.readLine())) {
                keySocket = s;
                keyIn = in;
                keyOut = out;
                return true;
            }
            out.write((keyToken() + " quit\n").getBytes(StandardCharsets.US_ASCII));   // another version: start ours
            out.flush();
            keyStartedAt = -60_000;
        } catch (IOException notThere) {
            // not running (or just starting)
        }
        try {
            s.close();
        } catch (IOException ignored) {
        }
        startKeyHelper();
        return false;
    }

    /** Starts the helper through the TV's debugging, once; a second start waits until the first had its time. */
    private void startKeyHelper() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - keyStartedAt < 40_000) return;
        keyStartedAt = now;
        AppLog.w("starting the key helper");
        final String command = "CLASSPATH='" + getPackageCodePath() + "' setsid nohup app_process /system/bin io.freemytv.KeyServer " + keyToken() + " " + KEY_PORT
                + " >/dev/null 2>&1 &";
        pool.execute(() -> {
            try {
                String out = shell(command, true);
                AppLog.w("key helper start command sent" + (out.trim().isEmpty() ? "" : ": " + out.trim()));
            } catch (IOException e) {
                AppLog.w("key helper not started: " + e.getMessage());
            }
        });
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
        try {
            shell(shell);
            return ok(json("ok", true));
        } catch (IOException e) {
            return new Object[] {502, json("error", String.valueOf(e.getMessage()))};
        }
    }

    private Adb adb;
    /** Its own lock: a slow or unreachable ADB must not hold up the checks every other request goes through. */
    private final Object adbLock = new Object();

    /** One debugging connection kept open: connecting takes longer than the key press itself. Tried again once if it went stale. */
    private String shell(String command) throws IOException {
        return shell(command, false);
    }

    private String shell(String command, boolean exec) throws IOException {
        synchronized (adbLock) {
            for (int attempt = 0; ; attempt++) {
                try {
                    if (adb == null) adb = Adb.connect(this, 3000, null);
                    return exec ? adb.exec(command) : adb.shell(command);
                } catch (IOException e) {
                    if (adb != null) adb.close();
                    adb = null;
                    if (attempt > 0) throw e;
                }
            }
        }
    }

    private void closeAdb() {
        synchronized (adbLock) {
            if (adb != null) adb.close();
            adb = null;
        }
    }
}
