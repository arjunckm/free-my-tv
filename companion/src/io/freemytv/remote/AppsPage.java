package io.freemytv.remote;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The TV's apps, from the phone's saved copy: the shelf first, then the rest. Tap one and it opens on the TV. */
final class AppsPage {
    private static final ExecutorService DECODE = Executors.newFixedThreadPool(2);

    private final MainActivity a;
    private final Map<String, ImageView> images = new HashMap<>();
    private final List<JSONObject> apps = new ArrayList<>();
    private LinearLayout list;
    private String query = "";

    AppsPage(MainActivity a) { this.a = a; }

    View build() {
        JSONObject saved = Cache.apps();
        if (saved == null) return a.empty("No apps yet", "Tap refresh to load the apps from your TV.", true);
        try {
            JSONArray all = saved.getJSONArray("apps");
            for (int i = 0; i < all.length(); i++) apps.add(all.getJSONObject(i));
        } catch (JSONException e) {
            return a.empty("No apps yet", "Tap refresh to load the apps from your TV.", true);
        }

        LinearLayout page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);

        // Search
        LinearLayout search = new LinearLayout(a);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        search.setBackground(Ui.round(a, Ui.SURFACE, 16));
        search.addView(Ui.icon(a, R.drawable.ic_search, Ui.SUB, 20));
        EditText field = new EditText(a);
        field.setHint("Search apps");
        field.setHintTextColor(Ui.SUB);
        field.setTextColor(Ui.TEXT);
        field.setTextSize(16);
        field.setSingleLine();
        field.setBackground(null);
        field.setPadding(Ui.dp(a, 12), Ui.dp(a, 14), 0, Ui.dp(a, 14));
        search.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 8));
        page.addView(search, sp);

        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(a);
        scroll.setClipToPadding(false);
        scroll.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 16));
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                fill();
            }
        });
        Sync.onIcon = (key, pkg) -> {
            if (key.equals(Tv.activeKey())) a.runOnUiThread(() -> showIcon(pkg));   // an icon of the TV shown here
        };
        fill();
        return page;
    }

    void close() {
        if (Sync.onIcon != null) Sync.onIcon = null;
    }

    private void fill() {
        list.removeAllViews();
        images.clear();
        List<JSONObject> shelf = new ArrayList<>(), rest = new ArrayList<>();
        for (JSONObject app : apps) {
            if (!query.isEmpty() && !app.optString("label").toLowerCase(Locale.ROOT).contains(query)) continue;
            (app.optBoolean("shelf") ? shelf : rest).add(app);
        }
        if (shelf.isEmpty() && rest.isEmpty()) {
            TextView none = Ui.text(a, "No app with that name", 15, Ui.SUB, false);
            none.setPadding(Ui.dp(a, 8), Ui.dp(a, 24), 0, 0);
            list.addView(none);
            return;
        }
        if (!shelf.isEmpty()) {
            list.addView(title("On your shelf"));
            list.addView(grid(shelf, 2));
        }
        if (!rest.isEmpty()) {
            list.addView(title(shelf.isEmpty() ? "Apps" : "All apps"));
            list.addView(grid(rest, AppPrefs.columns()));
        }
    }

    private TextView title(String s) {
        TextView t = Ui.text(a, s, 13, Ui.SUB, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(a, 6), Ui.dp(a, 14), 0, Ui.dp(a, 8));
        return t;
    }

    private GridLayout grid(List<JSONObject> items, int columns) {
        GridLayout g = new GridLayout(a);
        g.setColumnCount(columns);
        int width = (a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 24)) / columns;
        for (JSONObject app : items) g.addView(card(app, width, columns == 2));
        return g;
    }

    private View card(JSONObject app, int width, boolean big) {
        String pkg = app.optString("pkg"), label = app.optString("label");
        FrameHolder cell = new FrameHolder(a);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = width;
        cell.setLayoutParams(lp);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8));
        box.setBackground(Ui.ripple(a, Ui.SURFACE, 18));
        ImageView image = new ImageView(a);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int h = Ui.dp(a, big ? 84 : 52);
        box.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        TextView name = Ui.text(a, label, big ? 14 : 12, Ui.TEXT, true);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 2));
        box.addView(name);
        FrameHolder.LayoutParams bp = new FrameHolder.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMargins(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
        cell.addView(box, bp);
        box.setOnClickListener(v -> {
            Ui.haptic(v, android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            try {
                Sync.command("/launch", new JSONObject().put("pkg", pkg), error -> a.done(error, "Opening " + label + " on the TV"));
            } catch (JSONException ignored) {
            }
        });
        images.put(pkg, image);
        DECODE.execute(() -> {
            Bitmap b = Cache.icon(pkg);
            if (b != null) a.runOnUiThread(() -> showIcon(pkg, b));
        });
        return cell;
    }

    private void showIcon(String pkg) {
        DECODE.execute(() -> {
            Bitmap b = Cache.icon(pkg);
            if (b != null) a.runOnUiThread(() -> showIcon(pkg, b));
        });
    }

    private void showIcon(String pkg, Bitmap b) {
        ImageView v = images.get(pkg);
        if (v != null) v.setImageBitmap(b);
    }

    private static final class FrameHolder extends android.widget.FrameLayout {
        FrameHolder(android.content.Context c) { super(c); }
    }
}
