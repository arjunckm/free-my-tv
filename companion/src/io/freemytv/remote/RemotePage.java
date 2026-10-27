package io.freemytv.remote;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

/** The phone as the TV's remote: a round pad, Back / Home / Menu, volume and playback. Needs the TV (it is not cached). */
final class RemotePage {
    private final MainActivity a;
    private final Handler handler = new Handler(Looper.getMainLooper());

    RemotePage(MainActivity a) { this.a = a; }

    View build() {
        LinearLayout page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 24), Ui.dp(a, 16));

        int pad = Math.min(a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 80), Ui.dp(a, 300));
        page.addView(new DPad(a, this::press), new LinearLayout.LayoutParams(pad, pad));

        page.addView(row(
                key(R.drawable.ic_back, "Back", 4, false), key(R.drawable.ic_home, "Home", 3, false), key(R.drawable.ic_menu, "Menu", 82, false)));
        page.addView(row(
                key(R.drawable.ic_vol_down, "Vol −", 25, true), key(R.drawable.ic_mute, "Mute", 164, false), key(R.drawable.ic_vol_up, "Vol +", 24, true)));
        page.addView(row(
                key(R.drawable.ic_prev, "Previous", 88, false), key(R.drawable.ic_play_pause, "Play / pause", 85, false), key(R.drawable.ic_next, "Next", 87, false)));

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.addView(page);
        return scroll;
    }

    private void press(int code) {
        try {
            Sync.command("/key", new JSONObject().put("code", code), error -> {
                if (error != null) a.problem(error);
            });
        } catch (JSONException ignored) {
        }
    }

    private LinearLayout row(View... keys) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 20);
        r.setLayoutParams(lp);
        for (View k : keys) {
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            r.addView(k, kp);
        }
        return r;
    }

    /** A round key with its name under it. Holding a repeating key (volume) keeps sending it. */
    @SuppressLint("ClickableViewAccessibility")
    private View key(int icon, String name, int code, boolean repeats) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.FrameLayoutLike b = Ui.iconButton(a, icon, Ui.SURFACE2, Ui.TEXT, 64, 26);
        box.addView(b);
        TextView label = Ui.text(a, name, 12, Ui.SUB, false);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setPadding(0, Ui.dp(a, 8), 0, 0);
        box.addView(label);
        Runnable again = new Runnable() {
            @Override public void run() {
                press(code);
                Ui.haptic(b, HapticFeedbackConstants.CLOCK_TICK);
                handler.postDelayed(this, 140);
            }
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    Ui.haptic(v, HapticFeedbackConstants.KEYBOARD_TAP);
                    press(code);
                    if (repeats) handler.postDelayed(again, 420);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    handler.removeCallbacks(again);
                    return true;
                default:
                    return true;
            }
        });
        return box;
    }
}
