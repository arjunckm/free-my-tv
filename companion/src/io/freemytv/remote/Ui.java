package io.freemytv.remote;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Colors, shapes and the few widgets every screen shares: one look for the whole app. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF0B0D12, SURFACE = 0xFF151922, SURFACE2 = 0xFF1E2430, ACCENT = 0xFF7C9CFF, ON_ACCENT = 0xFF0B1020,
            TEXT = 0xFFF2F4F8, SUB = 0xFF9AA3B5, LINE = 0x1AFFFFFF, DANGER = 0xFFFF7A7A, OK = 0xFF4ADE80;

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** A rounded surface that ripples when touched. */
    static RippleDrawable ripple(Context c, int color, float radiusDp) {
        GradientDrawable mask = round(c, Color.WHITE, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(c, color, radiusDp), mask);
    }

    static TextView text(Context c, String s, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (medium) t.setTypeface(MEDIUM);
        t.setIncludeFontPadding(false);
        return t;
    }

    static ImageView icon(Context c, int res, int tint, float sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(tint));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** A round or rounded icon button (a tappable surface with one icon in the middle). */
    static FrameLayoutLike iconButton(Context c, int res, int bg, int tint, float sizeDp, float iconDp) {
        FrameLayoutLike f = new FrameLayoutLike(c);
        f.setBackground(ripple(c, bg, sizeDp / 2));
        ImageView i = icon(c, res, tint, iconDp);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(dp(c, iconDp), dp(c, iconDp), Gravity.CENTER);
        f.addView(i, lp);
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        f.glyph = i;
        return f;
    }

    static final class FrameLayoutLike extends android.widget.FrameLayout {
        ImageView glyph;
        FrameLayoutLike(Context c) { super(c); }
    }

    static TextView pillButton(Context c, String s, boolean filled) {
        TextView b = text(c, s, 15, filled ? ON_ACCENT : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 20), dp(c, 13), dp(c, 20), dp(c, 13));
        b.setBackground(ripple(c, filled ? ACCENT : SURFACE2, 24));
        return b;
    }

    /** A small buzz, unless the person turned it off (Phone settings). */
    static void haptic(View v, int type) {
        if (AppPrefs.haptics()) v.performHapticFeedback(type);
    }

    interface Pick { void picked(int index); }

    // MARK: - Dialogs

    /** A rounded dialog in the app's look: title, your content, and Cancel / OK (OK only when `ok` is given). */
    static Dialog dialog(Activity a, String title, View body, String ok, Runnable onOk) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 22), dp(a, 22), dp(a, 22), dp(a, 14));
        box.setBackground(round(a, SURFACE2, 28));
        box.addView(text(a, title, 20, TEXT, true));
        if (body != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(a, 14);
            box.addView(body, lp);
        }
        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(a, 16);
        TextView cancel = text(a, ok == null ? "Close" : "Cancel", 15, ACCENT, true);
        cancel.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12));
        cancel.setOnClickListener(v -> d.dismiss());
        buttons.addView(cancel);
        if (ok != null) {
            TextView go = text(a, ok, 15, ON_ACCENT, true);
            go.setPadding(dp(a, 20), dp(a, 12), dp(a, 20), dp(a, 12));
            go.setBackground(ripple(a, ACCENT, 20));
            go.setOnClickListener(v -> {
                d.dismiss();
                onOk.run();
            });
            buttons.addView(go);
        }
        box.addView(buttons, bl);
        d.setContentView(box);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        w.setLayout((int) (a.getResources().getDisplayMetrics().widthPixels * 0.9), WindowManager.LayoutParams.WRAP_CONTENT);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        return d;
    }

    /** One choice out of a list; the current one is ticked. */
    static Dialog choose(Activity a, String title, String[] names, int current, Pick pick) {
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        Dialog d = dialog(a, title, scroll, null, null);
        for (int i = 0; i < names.length; i++) {
            final int at = i;
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(a, 8), dp(a, 14), dp(a, 8), dp(a, 14));
            row.setBackground(ripple(a, Color.TRANSPARENT, 12));
            TextView t = text(a, names[i], 16, i == current ? ACCENT : TEXT, i == current);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (i == current) row.addView(icon(a, R.drawable.ic_check, ACCENT, 20));
            row.setOnClickListener(v -> {
                d.dismiss();
                pick.picked(at);
            });
            list.addView(row);
        }
        d.show();
        // A long list scrolls instead of filling the screen.
        int max = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.6);
        scroll.measure(View.MeasureSpec.makeMeasureSpec(d.getWindow().getAttributes().width, View.MeasureSpec.AT_MOST), 0);
        if (scroll.getMeasuredHeight() > max) scroll.getLayoutParams().height = max;
        scroll.requestLayout();
        return d;
    }
}
