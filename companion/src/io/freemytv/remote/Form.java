package io.freemytv.remote;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** The building blocks of a settings page (grouped cards, rows with a colored icon, switches), shared by the TV and Phone pages. */
final class Form {
    static final int BLUE = 0xFF7C9CFF, PURPLE = 0xFFB07CFF, GREEN = 0xFF4ADE80, ORANGE = 0xFFFFA45C, PINK = 0xFFFF7AA8,
            TEAL = 0xFF3CD6C5, YELLOW = 0xFFFFD166, GREY = 0xFF8A8F99;

    interface Changed { void changed(boolean on); }

    private final MainActivity a;

    Form(MainActivity a) { this.a = a; }

    /** The page's big title. */
    void heading(LinearLayout list, String s) {
        TextView t = Ui.text(a, s, 26, Ui.TEXT, true);
        t.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), 0, Ui.dp(a, 4));
        list.addView(t);
    }

    LinearLayout group(LinearLayout list, String title) {
        TextView t = Ui.text(a, title, 13, Ui.SUB, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(a, 8), Ui.dp(a, 20), 0, Ui.dp(a, 8));
        list.addView(t);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(a, Ui.SURFACE, 20));
        list.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    void divider(LinearLayout card) {
        if (card.getChildCount() == 0) return;
        View d = new View(a);
        d.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = Ui.dp(a, 16);
        card.addView(d, lp);
    }

    /** The small colored square with an icon at the start of a row. */
    View chip(int icon, int color) {
        FrameLayout c = new FrameLayout(a);
        c.setBackground(Ui.round(a, (color & 0x00FFFFFF) | 0x33000000, 10));
        c.addView(Ui.icon(a, icon, color, 20), new FrameLayout.LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20), Gravity.CENTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36));
        lp.rightMargin = Ui.dp(a, 14);
        c.setLayoutParams(lp);
        return c;
    }

    /** A row with a label and a value; `click` null makes it plain information (no arrow, no ripple). */
    void row(LinearLayout card, int icon, int color, String label, String value, View.OnClickListener click) {
        divider(card);
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(a, 60));
        r.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
        if (click != null) r.setBackground(Ui.ripple(a, Color.TRANSPARENT, 20));
        r.addView(chip(icon, color));
        r.addView(Ui.text(a, label, 16, Ui.TEXT, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = Ui.text(a, value, 15, Ui.SUB, false);
        v.setMaxWidth(Ui.dp(a, 190));
        v.setMaxLines(1);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(v);
        if (click != null) {
            r.addView(Ui.icon(a, R.drawable.ic_chevron, Ui.SUB, 20));
            r.setOnClickListener(click);
        } else {
            v.setPadding(0, 0, Ui.dp(a, 8), 0);
        }
        card.addView(r);
    }

    void toggle(LinearLayout card, int icon, int color, String label, boolean on, Changed changed) {
        divider(card);
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(a, 60));
        r.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 4));
        r.setBackground(Ui.ripple(a, Color.TRANSPARENT, 20));
        r.addView(chip(icon, color));
        r.addView(Ui.text(a, label, 16, Ui.TEXT, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(a);
        sw.setThumbTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}}, new int[] {Ui.ACCENT, Ui.SUB}));
        sw.setTrackTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}}, new int[] {Ui.ACCENT, Ui.SURFACE2}));
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((v, now) -> {
            if (v.isPressed()) changed.changed(now);
        });
        r.addView(sw);
        r.setOnClickListener(v -> sw.performClick());
        card.addView(r);
    }
}
