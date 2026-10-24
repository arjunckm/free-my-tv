package io.freemytv.remote;

import android.content.Context;
import android.content.SharedPreferences;

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

/** The paired TV: where it is, who we are to it, and signed requests to it (see Proto). Call from a background thread. */
final class Tv {
    static String host, name, id, token, svc;   // svc: the name the TV announces, to find it again when its address changes
    static int port;
    private static long last;

    static void load(Context c) {
        SharedPreferences sp = c.getSharedPreferences("tv", Context.MODE_PRIVATE);
        host = sp.getString("host", null);
        port = sp.getInt("port", Proto.PORT);
        name = sp.getString("name", null);
        id = sp.getString("id", null);
        token = sp.getString("token", null);
        svc = sp.getString("svc", null);
    }

    static void save(Context c) {
        c.getSharedPreferences("tv", Context.MODE_PRIVATE).edit().putString("host", host).putInt("port", port)
                .putString("name", name).putString("id", id).putString("token", token).putString("svc", svc).apply();
    }

    static void forget(Context c) {
        c.getSharedPreferences("tv", Context.MODE_PRIVATE).edit().clear().apply();
        host = name = id = token = svc = null;
    }

    static boolean paired() { return host != null && token != null; }

    static final class Reply {
        int status;
        byte[] body;
        String text() { return new String(body, StandardCharsets.UTF_8); }
        JSONObject json() throws JSONException { return new JSONObject(text()); }
        String error() {
            try {
                return json().optString("error", "error " + status);
            } catch (JSONException e) {
                return "error " + status;
            }
        }
    }

    /** Signed when we are paired; the pairing calls go out without. */
    static synchronized Reply call(String method, String path, String body, boolean signed) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(12000);
            c.setRequestMethod(method);
            String payload = body == null ? "" : body;
            if (signed) {
                last = Math.max(System.currentTimeMillis(), last + 1);
                c.setRequestProperty(Proto.H_ID, id);
                c.setRequestProperty(Proto.H_TS, String.valueOf(last));
                c.setRequestProperty(Proto.H_SIG, Proto.sign(token, method, path, last, payload));
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
                for (int n; in != null && (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
                r.body = buf.toByteArray();
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    static Reply get(String path) throws IOException { return call("GET", path, null, true); }
    static Reply post(String path, JSONObject body) throws IOException { return call("POST", path, body.toString(), true); }
}
