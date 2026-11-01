package io.freemytv;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Presses the TV's keys for the phone app without starting a new `input` command every time (that takes 1 to 2 seconds
 * on a small TV). Companion starts it once, through the TV's own debugging, as `app_process` from this APK: it runs as the
 * shell user, which may inject keys. It listens only on 127.0.0.1 and every line starts with a secret token that only
 * Companion knows. Lines: "token hello" (answers "ok VERSION"), "token key CODE" (presses it) and "token quit".
 */
public final class KeyServer {
    private KeyServer() {}

    static final int VERSION = 1;

    /** Leaves after this long without a command, so it doesn't hold the TV's memory for nothing; Companion starts it again. */
    private static final long IDLE_MS = 10 * 60 * 1000;
    private static volatile long lastUse = SystemClock.elapsedRealtime();

    /** Keys the phone remote may press: arrows, OK, back, home, menu, play/pause, volume, mute, channel, and text. Companion checks them too. */
    static final int[] KEYS = {19, 20, 21, 22, 23, 4, 3, 82, 85, 86, 87, 88, 89, 90, 24, 25, 164, 166, 167, 126, 127, 66, 67,
        7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54,
        55, 56, 62, 69, 76};   // the last two lines: digits, letters, comma, period, space, minus, slash (typing from the phone)
    private static final int MAX_CLIENTS = 4;
    private static final java.util.concurrent.atomic.AtomicInteger clients = new java.util.concurrent.atomic.AtomicInteger();

    private static Object manager;
    private static Method inject;

    static boolean allowed(int code) {
        for (int k : KEYS) if (k == code) return true;
        return false;
    }

    public static void main(String[] args) throws Exception {
        String token = args[0];
        int port = Integer.parseInt(args[1]);
        manager = InputManager.class.getDeclaredMethod("getInstance").invoke(null);
        inject = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
        ServerSocket server = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
        Thread idle = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    return;
                }
                if (SystemClock.elapsedRealtime() - lastUse > IDLE_MS) System.exit(0);
            }
        });
        idle.setDaemon(true);
        idle.start();
        while (true) {
            final Socket s = server.accept();
            if (clients.incrementAndGet() > MAX_CLIENTS) {
                clients.decrementAndGet();
                try {
                    s.close();
                } catch (IOException ignored) {
                }
                continue;
            }
            new Thread(() -> {
                try {
                    serve(s, token);
                } finally {
                    clients.decrementAndGet();
                }
            }).start();
        }
    }

    private static void serve(Socket s, String token) {
        try (Socket socket = s) {
            socket.setSoTimeout(5000);   // until it shows the token; then it may sit idle between key presses
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = socket.getOutputStream();
            String line;
            while ((line = readLine(in)) != null) {
                String[] p = line.split(" ");
                String reply;
                if (p.length < 2 || !java.security.MessageDigest.isEqual(p[0].getBytes(StandardCharsets.US_ASCII), token.getBytes(StandardCharsets.US_ASCII))) {
                    out.write("err token\n".getBytes(StandardCharsets.US_ASCII));   // one wrong token and the connection is over
                    out.flush();
                    return;
                }
                lastUse = SystemClock.elapsedRealtime();
                socket.setSoTimeout((int) IDLE_MS);   // known now: it may sit idle between key presses
                if (p[1].equals("hello")) {
                    reply = "ok " + VERSION;
                } else if (p[1].equals("quit")) {
                    out.write("ok\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    System.exit(0);
                    return;
                } else if (p[1].equals("key") && p.length > 2) {
                    try {
                        int code = Integer.parseInt(p[2]);
                        if (!allowed(code)) {
                            reply = "err key";
                        } else {
                            press(code);
                            reply = "ok";
                        }
                    } catch (Exception e) {
                        reply = "err " + e;
                    }
                } else {
                    reply = "err command";
                }
                out.write((reply + "\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    /** A line of at most 128 characters; a longer one ends the connection (null). */
    private static String readLine(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 128) return null;
        }
        return null;
    }

    private static void press(int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            KeyEvent e = new KeyEvent(now, now, action, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
            inject.invoke(manager, e, 0);   // 0: don't wait for the app to handle it
        }
    }
}
