package io.freemytv.remote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The photos on the TV (My Photos: the screensaver and the home screen background): add some from the phone's gallery, or
 * select some and delete them from the TV. Tap a photo to select it. Adding sends each picture through PhotoSender; the TV saves
 * it the way it saves one sent from a computer.
 */
final class PhotosPage {
    private static final ExecutorService THUMBS = Executors.newFixedThreadPool(3);

    private final MainActivity a;
    private final LinearLayout host;
    private final Runnable back;
    private final Tv.Session session = Tv.current();   // the TV this page is about
    private final List<String> names = new ArrayList<>(), selected = new ArrayList<>();
    private final List<FrameLayout> cells = new ArrayList<>();
    private final List<View> badges = new ArrayList<>();
    private TextView hint;
    private GridLayout grid;
    private LinearLayout actions;
    private TextView deleteButton;
    private boolean busy;   // sending photos

    PhotosPage(MainActivity a, LinearLayout host, Runnable back) {
        this.a = a;
        this.host = host;
        this.back = back;
    }

    void show() {
        host.removeAllViews();
        LinearLayout page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);
        host.addView(page, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 8));
        Ui.FrameLayoutLike backButton = Ui.iconButton(a, R.drawable.ic_back, Ui.SURFACE, Ui.TEXT, 44, 22);
        backButton.setOnClickListener(v -> back.run());
        bar.addView(backButton);
        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(a, 12), 0, 0, 0);
        titles.addView(Ui.text(a, "My photos", 20, Ui.TEXT, true));
        hint = Ui.text(a, "Loading the photos on the TV…", 13, Ui.SUB, false);
        hint.setPadding(0, Ui.dp(a, 4), 0, 0);
        titles.addView(hint);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView add = Ui.pillButton(a, "Add", true);
        add.setOnClickListener(v -> {
            if (busy) a.say("Still sending the last photos");
            else a.pickPhotos(this);
        });
        bar.addView(add);
        page.addView(bar);

        grid = new GridLayout(a);
        grid.setColumnCount(3);
        ScrollView scroll = new ScrollView(a);
        scroll.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 16));
        scroll.setClipToPadding(false);
        scroll.addView(grid);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Shown while photos are selected
        actions = new LinearLayout(a);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 10));
        actions.setBackgroundColor(Ui.SURFACE);
        TextView cancel = Ui.pillButton(a, "Cancel", false);
        cancel.setOnClickListener(v -> {
            selected.clear();
            refreshSelection();
        });
        actions.addView(cancel);
        deleteButton = Ui.pillButton(a, "Delete", true);
        deleteButton.setBackground(Ui.ripple(a, Ui.DANGER, 24));
        deleteButton.setOnClickListener(v -> confirmDelete());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.leftMargin = Ui.dp(a, 12);
        actions.addView(deleteButton, dl);
        actions.setVisibility(View.GONE);
        page.addView(actions);
        load();
    }

    // MARK: - The photos on the TV

    private void load() {
        new Thread(() -> {
            try {
                Tv.Reply r = session.get("/photos");
                if (r.status != 200) throw new java.io.IOException(r.error());
                JSONArray files = r.json().getJSONArray("files");
                List<String> now = new ArrayList<>();
                for (int i = 0; i < files.length(); i++) now.add(files.getString(i));
                a.runOnUiThread(() -> showGrid(now));
            } catch (Exception e) {
                a.runOnUiThread(() -> hint.setText(Sync.explain(e)));
            }
        }).start();
    }

    private void showGrid(List<String> now) {
        names.clear();
        names.addAll(now);
        selected.retainAll(now);
        grid.removeAllViews();
        cells.clear();
        badges.clear();
        int width = (a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 24)) / 3;
        for (String name : names) {
            FrameLayout cell = new FrameLayout(a);
            int pad = Ui.dp(a, 4);
            cell.setPadding(pad, pad, pad, pad);
            ImageView image = new ImageView(a);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(Ui.SURFACE);
            cell.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout badge = new FrameLayout(a);
            badge.setBackground(Ui.round(a, Ui.ACCENT, 11));
            badge.addView(Ui.icon(a, R.drawable.ic_check, Ui.ON_ACCENT, 16), new FrameLayout.LayoutParams(Ui.dp(a, 16), Ui.dp(a, 16), Gravity.CENTER));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22), Gravity.TOP | Gravity.START);
            bl.setMargins(Ui.dp(a, 8), Ui.dp(a, 8), 0, 0);
            cell.addView(badge, bl);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = width;
            lp.height = Math.round(width * 9f / 16f) + 2 * pad;
            cell.setLayoutParams(lp);
            cells.add(cell);
            badges.add(badge);
            cell.setOnClickListener(v -> {
                if (selected.contains(name)) selected.remove(name);
                else selected.add(name);
                refreshSelection();
            });
            grid.addView(cell);
            thumb(a, name, image);
        }
        refreshSelection();
    }

    private void refreshSelection() {
        for (int i = 0; i < cells.size(); i++) {
            boolean on = selected.contains(names.get(i));
            cells.get(i).setBackground(on ? Ui.round(a, Ui.ACCENT, 10) : null);
            badges.get(i).setVisibility(on ? View.VISIBLE : View.GONE);
        }
        actions.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        deleteButton.setText("Delete " + selected.size());
        if (busy) return;
        hint.setText(!selected.isEmpty() ? selected.size() + " selected"
                : names.isEmpty() ? "No photos on the TV yet. Tap Add."
                : names.size() + (names.size() == 1 ? " photo" : " photos") + " on the TV · tap to select");
    }

    /** A small preview of a photo, from the phone's copy or from the TV (once). */
    static void thumb(MainActivity a, String name, ImageView image) {
        THUMBS.execute(() -> {
            Bitmap b = Cache.photo(name);
            if (b == null) {
                try {
                    Tv.Reply r = Tv.get("/photo?name=" + java.net.URLEncoder.encode(name, "UTF-8"));
                    if (r.status == 200) {
                        Cache.savePhoto(name, r.body);
                        b = BitmapFactory.decodeByteArray(r.body, 0, r.body.length);
                    }
                } catch (Exception ignored) {
                }
            }
            final Bitmap shown = b;
            if (shown != null) a.runOnUiThread(() -> image.setImageBitmap(shown));
        });
    }

    // MARK: - Removing

    private void confirmDelete() {
        final List<String> doomed = new ArrayList<>(selected);
        TextView body = Ui.text(a, "They are deleted from " + Tv.title() + ". The originals on your phone are not touched.", 15, Ui.SUB, false);
        body.setLineSpacing(0, 1.2f);
        Ui.dialog(a, "Remove " + doomed.size() + (doomed.size() == 1 ? " photo" : " photos") + "?", body, "Remove", () -> delete(doomed)).show();
    }

    private void delete(List<String> doomed) {
        hint.setText("Removing…");
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("names", new JSONArray(doomed));
                Tv.Reply r = session.post("/photos/delete", body);
                if (r.status != 200) throw new java.io.IOException(r.error());
                int removed = r.json().optInt("removed", doomed.size());
                a.runOnUiThread(() -> {
                    selected.clear();
                    a.say(removed + (removed == 1 ? " photo" : " photos") + " removed from the TV");
                    load();
                });
            } catch (Exception e) {
                a.say(Sync.explain(e));
                a.runOnUiThread(this::refreshSelection);
            }
        }).start();
    }

    // MARK: - Adding

    /** Sends the pictures the person picked, one after the other, then waits for the TV to have saved them and shows the new list. */
    void upload(List<Uri> uris) {
        if (busy || uris.isEmpty()) return;
        busy = true;
        final int before = names.size();
        new Thread(() -> {
            int sent = 0;
            String problem = null;
            for (int i = 0; i < uris.size(); i++) {
                final int n = i + 1;
                a.runOnUiThread(() -> hint.setText("Sending " + n + " of " + uris.size() + "…"));
                try {
                    byte[] jpeg = PhotoSender.prepare(a, uris.get(i));
                    JSONObject body = new JSONObject().put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP));
                    Tv.Reply r = session.post("/photos/add", body);
                    if (r.status == 200) {
                        sent++;
                    } else {
                        problem = r.error();
                        if (r.status == 409 || r.status == 401) break;   // full, or not paired: more would fail the same way
                    }
                } catch (java.io.IOException e) {
                    problem = Sync.explain(e);
                    if (Sync.CANT_REACH.equals(problem)) break;
                } catch (Exception e) {
                    problem = "Couldn't send a photo";
                }
            }
            if (sent > 0) {
                a.runOnUiThread(() -> hint.setText("The TV is saving them…"));
                for (int wait = 0; wait < 24; wait++) {   // the TV crops and saves them one by one
                    try {
                        Thread.sleep(1500);
                        Tv.Reply r = session.get("/photos");
                        if (r.status == 200 && r.json().getJSONArray("files").length() >= before + sent) break;
                    } catch (Exception ignored) {
                    }
                }
            }
            final int done = sent;
            final String why = problem;
            a.runOnUiThread(() -> {
                busy = false;
                a.say(done == 0 ? (why == null ? "No photo was sent" : why)
                        : done + (done == 1 ? " photo" : " photos") + " sent to the TV" + (why == null ? "" : ". " + why));
                load();
            });
        }).start();
    }
}
