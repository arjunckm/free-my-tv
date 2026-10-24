package io.freemytv.remote;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "Share" from any app: the link opens on the TV. */
public class ShareActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Tv.load(this);
        String text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        // Shared text often has a title around the link: take the link.
        Matcher m = text == null ? null : Pattern.compile("https?://\\S+").matcher(text);
        if (m == null || !m.find()) {
            Toast.makeText(this, "No link to open", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!Tv.paired()) {
            Toast.makeText(this, "Pair your TV first (open Free My TV Remote)", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        final String url = m.group();
        new Thread(() -> {
            String message;
            try {
                Tv.Reply r = Tv.post("/share", new JSONObject().put("url", url));
                message = r.status == 200 ? "Opening on " + Tv.name : r.error();
            } catch (Exception e) {
                message = "Can't reach the TV (same Wi-Fi?)";
            }
            final String shown = message;
            runOnUiThread(() -> {
                Toast.makeText(this, shown, Toast.LENGTH_SHORT).show();
                finish();
            });
        }).start();
    }
}
