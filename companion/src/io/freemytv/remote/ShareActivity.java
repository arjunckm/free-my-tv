package io.freemytv.remote;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "Share" from any app: the link opens on the TV (with several TVs paired, you pick which one). */
public class ShareActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Cache.init(this);
        Tv.load(this);
        String text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        // Shared text often has a title around the link: take the link.
        Matcher m = text == null ? null : Pattern.compile("https?://\\S+").matcher(text);
        if (m == null || !m.find()) {
            Toast.makeText(this, "No link to open", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        final List<Tv.Entry> tvs = Tv.all();
        if (tvs.isEmpty()) {
            Toast.makeText(this, "Pair your TV first (open Free My TV Remote)", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        final String url = m.group();
        if (tvs.size() == 1) {
            send(tvs.get(0), url);
            return;
        }
        // Several TVs: which one? The one in use is on top.
        final String[] names = new String[tvs.size()];
        int current = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = tvs.get(i).title();
            if (tvs.get(i).key.equals(Tv.activeKey())) current = i;
        }
        final boolean[] chose = {false};
        Dialog d = Ui.choose(this, "Open on which TV?", names, current, which -> {
            chose[0] = true;
            send(tvs.get(which), url);
        });
        d.setOnDismissListener(x -> {
            if (!chose[0]) finish();
        });
    }

    private void send(Tv.Entry tv, String url) {
        final Tv.Session session = Tv.session(tv);
        new Thread(() -> {
            String message;
            try {
                Tv.Reply r = session.post("/share", new JSONObject().put("url", url));
                message = r.status == 200 ? "Opening on " + tv.title() : r.error();
            } catch (Exception e) {
                message = Sync.explain(e);
            }
            final String shown = message;
            runOnUiThread(() -> {
                Toast.makeText(this, shown, Toast.LENGTH_SHORT).show();
                finish();
            });
        }).start();
    }
}
