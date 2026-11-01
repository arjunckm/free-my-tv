package io.freemytv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The phone app's pairing code, big and readable from the sofa. Companion opens it when a phone asks to pair; it shows the
 * code and the time left, and closes by itself when the code is used or runs out (Back closes it too).
 */
public class PairCodeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView code, left;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            String c = Companion.pairingCode();
            if (c == null) {
                PairCodeActivity.this.finish();
                return;
            }
            code.setText(c.length() == 8 ? c.substring(0, 4) + "  " + c.substring(4) : c);   // 4 and 4, easier to read and to type
            long s = Math.max(0, Companion.pairingSecondsLeft());
            left.setText(Ui.trf("Valid for {m}:{s}", "m", String.valueOf(s / 60), "s", (s % 60 < 10 ? "0" : "") + (s % 60)));
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.init(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xF2080A10);
        TextView title = text(Ui.tr("Free My TV phone app"), 34, 0xFFFFFFFF, false);
        code = text("", 90, 0xFFFFFFFF, true);
        code.setSingleLine();
        code.setLetterSpacing(0.1f);
        TextView help = text(Ui.tr("Type this code in the phone app."), 30, 0xB3FFFFFF, false);
        left = text("", 26, 0x80FFFFFF, false);
        box.addView(title);
        box.addView(code);
        box.addView(help);
        box.addView(left);
        setContentView(box);
        getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** Companion's notice every few seconds is left out while this screen is up. */
    static volatile boolean showing;

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        t.setPadding(0, 16, 0, 16);
        return t;
    }

    @Override protected void onResume() {
        super.onResume();
        showing = true;
        handler.post(tick);
    }

    @Override protected void onPause() {
        showing = false;
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }
}
