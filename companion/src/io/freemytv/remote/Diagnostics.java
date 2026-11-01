package io.freemytv.remote;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import io.freemytv.proto.Proto;

/** "Why can't I reach my TV?": asks the TV in use a few small questions and shows the answers, with a copyable report. */
final class Diagnostics {
    private Diagnostics() {}

    static void show(MainActivity a) {
        if (!Tv.paired()) return;
        TextView body = Ui.text(a, "Checking…", 14, Ui.TEXT, false);
        body.setLineSpacing(0, 1.3f);
        body.setTextIsSelectable(true);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(body);
        TextView copy = Ui.pillButton(a, "Copy report", false);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = Ui.dp(a, 14);
        box.addView(copy, cl);
        Dialog d = Ui.dialog(a, "Connection check", box, null, null);
        d.show();

        final Tv.Session s = Tv.current();
        final String name = Tv.title(), host = Tv.host;
        final int port = Tv.port;
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            line(out, "TV", name + " (" + host + ":" + port + ")");
            line(out, "Phone", android.os.Build.MODEL + ", Android " + android.os.Build.VERSION.RELEASE);
            boolean reached = false;
            try {
                long t = System.currentTimeMillis();
                Tv.Reply r = Tv.info(host, port);
                long ms = System.currentTimeMillis() - t;
                if (r.status == 200) {
                    reached = true;
                    JSONObject j = r.json();
                    line(out, "Reachable", "yes, in " + ms + " ms");
                    line(out, "TV app", "version " + j.optString("version", "?"));
                    int tvProto = j.optInt("proto", 0);
                    line(out, "Protocol", "TV " + tvProto + ", phone " + Proto.VERSION + (tvProto == Proto.VERSION ? "" : "  ← update the older app"));
                    line(out, "Free My TV mode", j.optBoolean("home") ? "on" : "off (Original home screen)");
                } else {
                    line(out, "Reachable", "answered with error " + r.status);
                }
            } catch (Exception e) {
                line(out, "Reachable", "no: " + Sync.explain(e));
            }
            if (reached) {
                try {
                    Tv.Reply r = s.get("/photos");
                    line(out, "Pairing", r.status == 200 ? "ok, this phone is known to the TV" : r.status == 401 ? "the TV doesn't know this phone: pair again" : "error " + r.status);
                } catch (Exception e) {
                    line(out, "Pairing", "couldn't check: " + Sync.explain(e));
                }
            } else {
                out.append("\nTry: open Free My TV on the TV once, check both are on the same Wi-Fi (the same band, 2.4 or 5 GHz), and that the router's AP isolation is off. If the TV has a new address, tap the TV's name at the top and add it again.");
            }
            final String text = out.toString().trim();
            a.runOnUiThread(() -> {
                body.setText(text);
                copy.setOnClickListener(v -> {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Free My TV report", text));
                    a.say("Report copied");
                });
            });
        }).start();
    }

    private static void line(StringBuilder out, String label, String value) {
        out.append(label).append(": ").append(value).append('\n');
    }
}
