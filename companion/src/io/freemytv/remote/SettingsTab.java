package io.freemytv.remote;

import android.content.res.ColorStateList;
import android.graphics.Color;
import static io.freemytv.remote.Form.*;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The TV's settings on the phone, from the saved copy (Refresh brings the TV's current ones). A change shows at once, is
 * kept on the phone and goes to the TV in the background; if the TV is off it waits and goes with the next try.
 */
final class SettingsTab {
    private static final String[][] TEMPERATURE = {{"auto", "Automatic"}, {"C", "°C"}, {"F", "°F"}};
    private static final String[][] THEME = {{"auto", "Automatic"}, {"light", "Light"}, {"dark", "Dark"}};

    private final MainActivity a;
    private final LinearLayout host;
    private final Form form;
    private JSONObject values, keys, meta;
    private final List<String[]> apps = new ArrayList<>();   // {pkg, label}, by name
    private boolean bannerShown, editing;
    private ScrollView listScroll;

    SettingsTab(MainActivity a, LinearLayout host) {
        this.a = a;
        this.host = host;
        this.form = new Form(a);
    }

    void show() {
        host.removeAllViews();
        JSONObject saved = Cache.settings();
        if (saved == null) {
            host.addView(a.empty("No settings yet", "Tap refresh to load the settings from your TV.", true));
            return;
        }
        try {
            values = saved.getJSONObject("values");
            keys = saved.getJSONObject("keys");
            meta = saved.getJSONObject("meta");
            apps.clear();
            JSONObject cached = Cache.apps();
            JSONArray list = cached == null ? new JSONArray() : cached.getJSONArray("apps");
            for (int i = 0; i < list.length(); i++) apps.add(new String[] {list.getJSONObject(i).getString("pkg"), list.getJSONObject(i).getString("label")});
            Collections.sort(apps, (x, y) -> x[1].compareToIgnoreCase(y[1]));
        } catch (JSONException e) {
            host.addView(a.empty("No settings yet", "Tap refresh to load the settings from your TV.", true));
            return;
        }
        render();
    }

    // MARK: - The groups

    private void render() {
        // A switch that changes the list (coins, hero) draws it again: stay where you were.
        final int keep = listScroll == null ? 0 : listScroll.getScrollY();
        host.removeAllViews();
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 24));
        ScrollView scroll = new ScrollView(a);
        listScroll = scroll;
        scroll.addView(list);
        host.addView(scroll);
        scroll.post(() -> scroll.scrollTo(0, keep));
        form.heading(list, "TV settings");

        editing = false;
        int waiting = Cache.pendingCount();
        bannerShown = waiting > 0;
        if (waiting > 0) {
            LinearLayout banner = new LinearLayout(a);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12));
            banner.setBackground(Ui.round(a, 0x337C9CFF, 16));
            banner.addView(Ui.text(a, waiting + (waiting == 1 ? " change" : " changes") + " not on the TV yet", 14, Ui.TEXT, true),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView send = Ui.pillButton(a, "Send now", true);
            send.setOnClickListener(v -> a.sendChanges());
            banner.addView(send);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.bottomMargin = Ui.dp(a, 8);
            list.addView(banner, bl);
        }

        LinearLayout look = group(list, "Look");
        row(look, R.drawable.ic_globe, BLUE, "Language", languageName(values.optString("language", "auto")), v -> pickLanguage());
        row(look, R.drawable.ic_thermo, ORANGE, "Temperature", name(TEMPERATURE, values.optString("temperature", "auto")), v -> pick("Temperature", TEMPERATURE, "temperature"));
        row(look, R.drawable.ic_theme, PURPLE, "Theme", name(THEME, values.optString("theme", "auto")), v -> pick("Theme", THEME, "theme"));
        toggle(look, R.drawable.ic_vol_up, TEAL, "Navigation sounds", "sounds", true);

        LinearLayout widgets = group(list, "Widgets");
        toggle(widgets, R.drawable.ic_cloud, BLUE, "Weather", "weather", true);
        row(widgets, R.drawable.ic_place, PINK, "City", values.optString("city", "").isEmpty() ? "Not set" : values.optString("city"), v -> searchCity());
        toggle(widgets, R.drawable.ic_clock, ORANGE, "Date and time", "dateTime", true);
        toggle(widgets, R.drawable.ic_trend, YELLOW, "Coin prices", "quotes", false);
        if (values.optBoolean("quotes", false)) {
            String[] coins = meta.optBoolean("dollar") ? new String[] {"Bitcoin", "Ethereum", "Solana", "Dollar"} : new String[] {"Bitcoin", "Ethereum", "Solana"};
            for (int i = 0; i < coins.length; i++) toggle(widgets, R.drawable.ic_trend, 0xFF8A8F99, coins[i], "coin" + i, true);
        }
        int photos = meta.optInt("photos");
        toggle(widgets, R.drawable.ic_photo, GREEN, photos == 0 ? "My photos (none sent yet)" : "My photos (" + photos + ")", "photos", true);
        row(widgets, R.drawable.ic_add, GREEN, "Add or remove photos", photos + (photos == 1 ? " photo" : " photos"), v -> {
            editing = true;   // a page of its own: the notice of unsent changes does not redraw this list under it
            new PhotosPage(a, host, this::render).show();
        });
        toggle(widgets, R.drawable.ic_cards, PURPLE, "Hero section", "hero", true);
        if (values.optBoolean("hero", true)) {
            stepper(widgets, R.drawable.ic_cards, PURPLE, "Top cards", "heroCards", 3, 8, 8);
        } else {
            // Off: nothing is asked of TMDB, and three of your own photos take its place.
            int chosen = 0;
            for (String n : values.optString("heroPhotos", "").split(",")) if (!n.isEmpty()) chosen++;
            row(widgets, R.drawable.ic_photo, GREEN, "Wallpapers", photos == 0 ? "None sent yet" : chosen == 0 ? "First 3" : chosen + " chosen", v -> {
                if (photos == 0) a.say("Send photos to the TV first (see My Photos in the README)"); else pickHeroPhotos();
            });
        }

        LinearLayout home = group(list, "Home screen");
        row(home, R.drawable.ic_list, BLUE, "Shelf", shelf().size() + " apps", v -> editShelf());
        row(home, R.drawable.ic_dots, TEAL, "Top buttons", top().size() + " buttons", v -> editTop());

        JSONArray labels = meta.optJSONArray("keyLabels");
        JSONArray codes = meta.optJSONArray("keyCodes");
        if (labels != null && codes != null && labels.length() > 0) {
            LinearLayout remote = group(list, "Remote buttons");
            for (int i = 0; i < labels.length(); i++) {
                final int at = i;
                final String code = String.valueOf(codes.optInt(i));
                row(remote, R.drawable.ic_remote, ORANGE, labels.optString(i), appName(keys.optString(code, "")), v -> pickKeyApp(at, code));
            }
        }

        TextView forget = Ui.pillButton(a, "Forget this TV", false);
        forget.setTextColor(Ui.DANGER);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.topMargin = Ui.dp(a, 24);
        forget.setOnClickListener(v -> a.confirmForget());
        list.addView(forget, fl);
    }

    private String name(String[][] options, String code) {
        for (String[] o : options) if (o[0].equals(code)) return o[1];
        return code;
    }

    private String appName(String pkg) {
        if (pkg.isEmpty()) return meta.optString("keyNothing", "Nothing");
        for (String[] app : apps) if (app[0].equals(pkg)) return app[1];
        return pkg.equals("io.freemytv") ? "Free My TV" : "Not installed";
    }

    private String languageName(String code) {
        if (code.equals("auto")) return "Automatic";
        JSONArray l = meta.optJSONArray("languages");
        for (int i = 0; l != null && i < l.length(); i++) if (l.optJSONObject(i).optString("code").equals(code)) return l.optJSONObject(i).optString("name");
        return code;
    }

    private List<String> shelf() {
        List<String> out = new ArrayList<>();
        if (values.has("shelf")) {
            for (String p : values.optString("shelf").split(",")) if (!p.isEmpty()) out.add(p);
        } else {
            JSONArray s = meta.optJSONArray("shelf");   // the built-in list, until it is edited
            for (int i = 0; s != null && i < s.length(); i++) out.add(s.optString(i));
        }
        return out;
    }

    private List<String> top() {
        List<String> out = new ArrayList<>();
        if (values.has("topButtons")) {
            for (String p : values.optString("topButtons").split(",")) if (!p.isEmpty()) out.add(p);
        } else {
            JSONArray t = meta.optJSONArray("topButtons");
            for (int i = 0; t != null && i < t.length(); i++) out.add(t.optString(i));
        }
        return out;
    }

    /** The TV took (or did not take) our changes: show or hide the "not on the TV yet" notice. */
    void pendingChanged() {
        if (!editing && values != null && (Cache.pendingCount() > 0) != bannerShown) render();
    }

    // MARK: - Changes

    private void set(String key, Object value) {
        Cache.change("values", key, value);
        try {
            values.put(key, value);
        } catch (JSONException ignored) {
        }
        a.sendChanges();
    }

    private void pick(String title, String[][] options, String key) {
        String[] names = new String[options.length];
        int current = 0;
        for (int i = 0; i < options.length; i++) {
            names[i] = options[i][1];
            if (options[i][0].equals(values.optString(key, "auto"))) current = i;
        }
        Ui.choose(a, title, names, current, which -> {
            set(key, options[which][0]);
            render();
        });
    }

    private void pickLanguage() {
        JSONArray l = meta.optJSONArray("languages");
        List<String[]> options = new ArrayList<>();
        options.add(new String[] {"auto", "Automatic"});
        for (int i = 0; l != null && i < l.length(); i++) options.add(new String[] {l.optJSONObject(i).optString("code"), l.optJSONObject(i).optString("name")});
        pick("Language", options.toArray(new String[0][]), "language");
    }

    private void pickKeyApp(int at, String code) {
        List<String[]> options = new ArrayList<>();
        options.add(new String[] {"", meta.optString("keyNothing", "Nothing")});
        options.addAll(apps);
        String[] names = new String[options.size()];
        int current = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = options.get(i)[1];
            if (options.get(i)[0].equals(keys.optString(code, ""))) current = i;
        }
        Ui.choose(a, meta.optJSONArray("keyLabels").optString(at), names, current, which -> {
            Cache.change("keys", code, options.get(which)[0]);
            try {
                keys.put(code, options.get(which)[0]);
            } catch (JSONException ignored) {
            }
            a.sendChanges();
            render();
        });
    }

    private void searchCity() {
        EditText name = new EditText(a);
        name.setHint("Type the city's name");
        name.setHintTextColor(Ui.SUB);
        name.setTextColor(Ui.TEXT);
        name.setSingleLine();
        name.setBackground(Ui.round(a, Ui.SURFACE, 14));
        name.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 14));
        Ui.dialog(a, "City", name, "Search", () -> findCity(name.getText().toString().trim())).show();
    }

    private void findCity(String q) {
        if (q.isEmpty()) return;
        a.say("Searching…");
        new Thread(() -> {
            try {
                Tv.Reply r = Tv.get("/city?q=" + java.net.URLEncoder.encode(q, "UTF-8"));
                if (r.status != 200) throw new java.io.IOException(r.error());
                JSONArray found = r.json().getJSONArray("cities");
                if (found.length() == 0) {
                    a.say("No city with that name");
                    return;
                }
                String[] labels = new String[found.length()];
                for (int i = 0; i < labels.length; i++) labels[i] = found.getJSONObject(i).getString("label");
                a.runOnUiThread(() -> Ui.choose(a, "City", labels, -1, which -> {
                    try {
                        JSONObject c = found.getJSONObject(which);
                        Cache.change("values", "city", c.getString("name"));
                        Cache.change("values", "latitude", c.getString("lat"));
                        Cache.change("values", "longitude", c.getString("lon"));
                        values.put("city", c.getString("name"));
                        a.sendChanges();
                        render();
                    } catch (JSONException ignored) {
                    }
                }));
            } catch (Exception e) {
                a.say("Couldn't search now. Is the TV on?");
            }
        }).start();
    }

    // MARK: - Rows

    private LinearLayout group(LinearLayout list, String title) { return form.group(list, title); }

    private void divider(LinearLayout card) { form.divider(card); }

    private View chip(int icon, int color) { return form.chip(icon, color); }

    private void row(LinearLayout card, int icon, int color, String label, String value, View.OnClickListener click) {
        form.row(card, icon, color, label, value, click);
    }

    private void toggle(LinearLayout card, int icon, int color, String label, String key, boolean fallback) {
        form.toggle(card, icon, color, label, values.optBoolean(key, fallback), on -> {
            set(key, on);
            if (key.equals("quotes") || key.equals("hero")) render();   // the coins appear or go; the hero cards give way to photos or back
        });
    }

    private void stepper(LinearLayout card, int icon, int color, String label, String key, int min, int max, int fallback) {
        divider(card);
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(a, 60));
        r.setPadding(Ui.dp(a, 16), Ui.dp(a, 6), Ui.dp(a, 12), Ui.dp(a, 6));
        r.addView(chip(icon, color));
        r.addView(Ui.text(a, label, 16, Ui.TEXT, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView n = Ui.text(a, String.valueOf(values.optInt(key, fallback)), 16, Ui.TEXT, true);
        n.setGravity(Gravity.CENTER);
        Ui.FrameLayoutLike minus = Ui.iconButton(a, R.drawable.ic_down, Ui.SURFACE2, Ui.TEXT, 36, 20);
        Ui.FrameLayoutLike plus = Ui.iconButton(a, R.drawable.ic_up, Ui.SURFACE2, Ui.TEXT, 36, 20);
        minus.setOnClickListener(v -> {
            int x = Math.max(min, values.optInt(key, fallback) - 1);
            set(key, x);
            n.setText(String.valueOf(x));
        });
        plus.setOnClickListener(v -> {
            int x = Math.min(max, values.optInt(key, fallback) + 1);
            set(key, x);
            n.setText(String.valueOf(x));
        });
        r.addView(minus);
        r.addView(n, new LinearLayout.LayoutParams(Ui.dp(a, 36), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(plus);
        card.addView(r);
    }

    // MARK: - Hero photos: three of the TV photos take the hero place while it is off

    private void pickHeroPhotos() {
        editing = true;
        host.removeAllViews();
        LinearLayout page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);
        host.addView(page);
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 8));
        Ui.FrameLayoutLike back = Ui.iconButton(a, R.drawable.ic_back, Ui.SURFACE, Ui.TEXT, 44, 22);
        back.setOnClickListener(v -> render());
        bar.addView(back);
        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(a, 12), 0, 0, 0);
        titles.addView(Ui.text(a, "Wallpapers", 20, Ui.TEXT, true));
        TextView hint = Ui.text(a, "Loading the photos on the TV...", 13, Ui.SUB, false);
        hint.setPadding(0, Ui.dp(a, 4), 0, 0);
        titles.addView(hint);
        bar.addView(titles);
        page.addView(bar);
        android.widget.GridLayout grid = new android.widget.GridLayout(a);
        grid.setColumnCount(3);
        ScrollView scroll = new ScrollView(a);
        scroll.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 16));
        scroll.setClipToPadding(false);
        scroll.addView(grid);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        new Thread(() -> {
            try {
                Tv.Reply r = Tv.get("/photos");
                if (r.status != 200) throw new java.io.IOException(r.error());
                JSONObject all = r.json();
                JSONArray files = all.getJSONArray("files"), now = all.getJSONArray("chosen");
                List<String> order = new ArrayList<>();
                for (String n : values.optString("heroPhotos", "").split(",")) if (!n.isEmpty()) order.add(n);
                if (order.isEmpty()) for (int i = 0; i < now.length(); i++) order.add(now.getString(i));
                a.runOnUiThread(() -> showPhotoGrid(grid, hint, files, order));
            } catch (Exception e) {
                a.runOnUiThread(() -> hint.setText(Sync.explain(e)));
            }
        }).start();
    }

    /** One cell per photo: tap to choose it (up to 3, numbered in the order they rotate); the ring and the number are the choice. */
    private void showPhotoGrid(android.widget.GridLayout grid, TextView hint, JSONArray files, List<String> order) {
        int width = (a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 24)) / 3;
        List<android.widget.FrameLayout> cells = new ArrayList<>();
        List<TextView> badges = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            hint.setText(order.isEmpty() ? "Tap up to 3 photos. None chosen: the first 3." : order.size() + " of 3 chosen, in the order they rotate");
            for (int i = 0; i < cells.size(); i++) {
                int at = order.indexOf(names.get(i));
                cells.get(i).setBackground(at < 0 ? null : Ui.round(a, Ui.ACCENT, 10));
                badges.get(i).setVisibility(at < 0 ? View.GONE : View.VISIBLE);
                badges.get(i).setText(String.valueOf(at + 1));
            }
        };
        for (int i = 0; i < files.length(); i++) {
            final String name = files.optString(i);
            names.add(name);
            android.widget.FrameLayout cell = new android.widget.FrameLayout(a);
            int pad = Ui.dp(a, 4);
            cell.setPadding(pad, pad, pad, pad);
            android.widget.ImageView image = new android.widget.ImageView(a);
            image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(Ui.SURFACE);
            cell.addView(image, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            TextView badge = Ui.text(a, "1", 13, Ui.ON_ACCENT, true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(Ui.round(a, Ui.ACCENT, 11));
            android.widget.FrameLayout.LayoutParams bl = new android.widget.FrameLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22), Gravity.TOP | Gravity.START);
            bl.setMargins(Ui.dp(a, 8), Ui.dp(a, 8), 0, 0);
            cell.addView(badge, bl);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = width;
            lp.height = Math.round(width * 9f / 16f) + 2 * pad;
            cell.setLayoutParams(lp);
            cells.add(cell);
            badges.add(badge);
            cell.setOnClickListener(v -> {
                if (order.contains(name)) {
                    order.remove(name);
                } else if (order.size() >= 3) {
                    a.say("Up to 3: tap a chosen one to remove it first");
                    return;
                } else {
                    order.add(name);
                }
                refresh[0].run();
                set("heroPhotos", join(order));
            });
            grid.addView(cell);
            PhotosPage.thumb(a, name, image);
        }
        refresh[0].run();
    }

    // MARK: - Ordered lists: the shelf and the top buttons

    private void editShelf() {
        editList("Shelf", shelf(), new ArrayList<>(apps), meta.optInt("maxShelf", -1), order -> set("shelf", join(order)));
    }

    private void editTop() {
        // What can go there, named by the TV (so a new button on the TV shows up here without a new phone app).
        List<String[]> candidates = new ArrayList<>();
        JSONArray known = meta.optJSONArray("topCandidates");
        for (int i = 0; known != null && i < known.length(); i++) {
            candidates.add(new String[] {known.optJSONObject(i).optString("key"), known.optJSONObject(i).optString("label")});
        }
        editList("Top buttons", top(), candidates, -1, order -> set("topButtons", join(order)));
    }

    private interface Saved { void done(List<String> order); }

    private void editList(String title, List<String> order, List<String[]> candidates, int max, Saved saved) {
        editing = true;
        host.removeAllViews();
        LinearLayout page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);
        host.addView(page);

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 8));
        Ui.FrameLayoutLike back = Ui.iconButton(a, R.drawable.ic_back, Ui.SURFACE, Ui.TEXT, 44, 22);
        back.setOnClickListener(v -> render());
        bar.addView(back);
        TextView head = Ui.text(a, title + (max > 0 ? "  ·  up to " + max : ""), 20, Ui.TEXT, true);
        head.setPadding(Ui.dp(a, 12), 0, 0, 0);
        bar.addView(head);
        page.addView(bar);

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 24));
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            list.removeAllViews();
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.round(a, Ui.SURFACE, 20));
            for (int i = 0; i < order.size(); i++) {
                final int at = i;
                divider(card);
                LinearLayout r = new LinearLayout(a);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 4));
                r.addView(Ui.text(a, (i + 1) + "", 14, Ui.SUB, true), new LinearLayout.LayoutParams(Ui.dp(a, 28), ViewGroup.LayoutParams.WRAP_CONTENT));
                r.addView(Ui.text(a, nameOf(candidates, order.get(i)), 16, Ui.TEXT, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Ui.FrameLayoutLike up = Ui.iconButton(a, R.drawable.ic_up, Color.TRANSPARENT, Ui.SUB, 44, 22);
                Ui.FrameLayoutLike down = Ui.iconButton(a, R.drawable.ic_down, Color.TRANSPARENT, Ui.SUB, 44, 22);
                Ui.FrameLayoutLike del = Ui.iconButton(a, R.drawable.ic_close, Color.TRANSPARENT, Ui.DANGER, 44, 20);
                up.setOnClickListener(v -> {
                    if (at > 0) {
                        Collections.swap(order, at, at - 1);
                        saved.done(order);
                        draw[0].run();
                    }
                });
                down.setOnClickListener(v -> {
                    if (at < order.size() - 1) {
                        Collections.swap(order, at, at + 1);
                        saved.done(order);
                        draw[0].run();
                    }
                });
                del.setOnClickListener(v -> {
                    order.remove(at);
                    saved.done(order);
                    draw[0].run();
                });
                r.addView(up);
                r.addView(down);
                r.addView(del);
                card.addView(r);
            }
            if (!order.isEmpty()) list.addView(card);
            TextView add = Ui.pillButton(a, "Add", true);
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            al.topMargin = Ui.dp(a, 16);
            add.setOnClickListener(v -> {
                if (max > 0 && order.size() >= max) {
                    a.say("This TV has little memory: the shelf holds " + max + " apps.");
                    return;
                }
                List<String[]> rest = new ArrayList<>();
                for (String[] c : candidates) if (!order.contains(c[0])) rest.add(c);
                if (rest.isEmpty()) {
                    a.say("Nothing left to add");
                    return;
                }
                String[] names = new String[rest.size()];
                for (int i = 0; i < names.length; i++) names[i] = rest.get(i)[1];
                Ui.choose(a, "Add", names, -1, which -> {
                    order.add(rest.get(which)[0]);
                    saved.done(order);
                    draw[0].run();
                });
            });
            list.addView(add, al);
        };
        draw[0].run();
    }

    private static String nameOf(List<String[]> candidates, String key) {
        for (String[] c : candidates) if (c[0].equals(key)) return c[1];
        return key;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() == 0 ? "" : ",").append(s);
        return sb.toString();
    }
}
