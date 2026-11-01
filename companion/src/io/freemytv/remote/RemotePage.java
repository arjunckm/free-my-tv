package io.freemytv.remote;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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

        // Pad or touchpad, and the keyboard
        LinearLayout modes = new LinearLayout(a);
        modes.setGravity(Gravity.CENTER);
        TextView padMode = Ui.pillButton(a, "Pad", !AppPrefs.touchpad());
        TextView touchMode = Ui.pillButton(a, "Touchpad", AppPrefs.touchpad());
        TextView typeMode = Ui.pillButton(a, "Keyboard", false);
        for (TextView t : new TextView[] {padMode, touchMode, typeMode}) {
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mp.leftMargin = mp.rightMargin = Ui.dp(a, 4);
            modes.addView(t, mp);
        }
        page.addView(modes);

        int pad = Math.min(a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 80), Ui.dp(a, 300));
        FrameLayout surface = new FrameLayout(a);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(pad, pad);
        sp.topMargin = Ui.dp(a, 14);
        page.addView(surface, sp);
        Runnable draw = () -> {
            surface.removeAllViews();
            surface.addView(AppPrefs.touchpad() ? new Touchpad(a, this::press) : new DPad(a, this::press),
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            padMode.setBackground(Ui.ripple(a, AppPrefs.touchpad() ? Ui.SURFACE2 : Ui.ACCENT, 20));
            padMode.setTextColor(AppPrefs.touchpad() ? Ui.TEXT : Ui.ON_ACCENT);
            touchMode.setBackground(Ui.ripple(a, AppPrefs.touchpad() ? Ui.ACCENT : Ui.SURFACE2, 20));
            touchMode.setTextColor(AppPrefs.touchpad() ? Ui.ON_ACCENT : Ui.TEXT);
        };
        padMode.setOnClickListener(v -> {
            AppPrefs.setTouchpad(false);
            draw.run();
        });
        touchMode.setOnClickListener(v -> {
            AppPrefs.setTouchpad(true);
            draw.run();
        });
        typeMode.setOnClickListener(v -> TypePad.show(a));
        draw.run();

        page.addView(row(
                key(R.drawable.ic_back, "Back", 4, false), key(R.drawable.ic_home, "Home", 3, false), key(R.drawable.ic_menu, "Menu", 82, false)));
        page.addView(row(
                key(R.drawable.ic_vol_down, "Vol −", 25, true), key(R.drawable.ic_mute, "Mute", 164, false), key(R.drawable.ic_vol_up, "Vol +", 24, true)));
        page.addView(row(
                key(R.drawable.ic_prev, "Previous", 88, false), key(R.drawable.ic_play_pause, "Play / pause", 85, false), key(R.drawable.ic_next, "Next", 87, false)));

        scenes(page);

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.addView(page);
        return scroll;
    }

    /** Saved scenes of this TV: tap one to run it, hold to remove it. */
    private void scenes(LinearLayout page) {
        String tv = Tv.activeKey();
        if (tv == null) return;
        TextView head = Ui.text(a, "Scenes", 13, Ui.SUB, true);
        head.setAllCaps(true);
        head.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = Ui.dp(a, 28);
        page.addView(head, hp);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        for (Tools.Scene s : Tools.scenes(tv)) {
            TextView chip = Ui.pillButton(a, s.name + (s.label.isEmpty() || s.label.equals("No app") ? "" : "  ·  " + s.label), false);
            chip.setOnClickListener(v -> Tools.run(a, s));
            chip.setOnLongClickListener(v -> {
                Tools.deleteScene(a, s, () -> a.showTab(1));
                return true;
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = Ui.dp(a, 8);
            box.addView(chip, cp);
        }
        TextView add = Ui.pillButton(a, "+  New scene", true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = Ui.dp(a, 8);
        add.setOnClickListener(v -> Tools.newScene(a, () -> a.showTab(1)));
        box.addView(add, ap);
        page.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
