package io.freemytv.remote;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Small things the person does on the TV from the phone: a message, a backup of its settings, and scenes. */
final class Tools {
    private Tools() {}

    // MARK: - A message on the TV

    static void message(MainActivity a) {
        EditText text = field(a, "For example: dinner is ready");
        text.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(140)});
        Ui.dialog(a, "Show a message on the TV", text, "Send", () -> {
            String t = text.getText().toString().trim();
            if (t.isEmpty()) return;
            try {
                Sync.command("/notify", new JSONObject().put("text", t), error -> a.done(error, "Message sent"));
            } catch (JSONException ignored) {
            }
        }).show();
    }

    // MARK: - Backup of the TV's settings (to clone them onto another TV)

    /** Copies the TV's settings, as the phone has them, to the clipboard. */
    static void exportSettings(MainActivity a) {
        JSONObject s = Cache.settings();
        if (s == null) {
            a.say("Refresh first: the phone has no copy of the settings yet");
            return;
        }
        try {
            JSONObject out = new JSONObject().put("freemytv", 1).put("values", s.optJSONObject("values")).put("keys", s.optJSONObject("keys"));
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Free My TV settings", out.toString()));
            a.say("Settings of " + Tv.title() + " copied. Switch to another TV and tap Import.");
        } catch (JSONException e) {
            a.say("Couldn't copy the settings");
        }
    }

    /** Takes settings copied by exportSettings (pasted into the box) and sets them on the TV in use. The TV checks every value. */
    static void importSettings(MainActivity a) {
        EditText box = field(a, "Paste the copied settings");
        box.setSingleLine(false);
        box.setMinLines(4);
        box.setGravity(android.view.Gravity.TOP);
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence c = cm.getPrimaryClip().getItemAt(0).getText();
            if (c != null && c.toString().contains("\"freemytv\"")) box.setText(c);
        }
        Ui.dialog(a, "Import settings into " + Tv.title(), box, "Import", () -> {
            try {
                JSONObject in = new JSONObject(box.getText().toString());
                if (in.optInt("freemytv", 0) != 1) throw new JSONException("not ours");
                int n = 0;
                for (String section : new String[] {"values", "keys"}) {
                    JSONObject o = in.optJSONObject(section);
                    if (o == null) continue;
                    for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                        String k = it.next();
                        Cache.change(section, k, o.get(k));
                        n++;
                    }
                }
                a.sendChanges();
                a.say(n + " settings sent to " + Tv.title());
            } catch (JSONException e) {
                a.say("That isn't settings copied from Free My TV Remote");
            }
        }).show();
    }

    // MARK: - Scenes: an app and, a few seconds later, one key

    static final class Scene {
        String name, pkg, label;
        int key;   // 0: none
    }

    static List<Scene> scenes(String tv) {
        List<Scene> out = new ArrayList<>();
        try {
            JSONArray all = new JSONArray(AppPrefs.scenes(tv));
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.getJSONObject(i);
                Scene s = new Scene();
                s.name = o.getString("name");
                s.pkg = o.optString("pkg");
                s.label = o.optString("label");
                s.key = o.optInt("key", 0);
                out.add(s);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    static void saveScenes(String tv, List<Scene> list) {
        JSONArray all = new JSONArray();
        try {
            for (Scene s : list) all.put(new JSONObject().put("name", s.name).put("pkg", s.pkg).put("label", s.label).put("key", s.key));
        } catch (JSONException ignored) {
        }
        AppPrefs.setScenes(tv, all.toString());
    }

    private static final String[] KEY_NAMES = {"Nothing else", "Mute", "Play / pause", "Home", "OK"};
    private static final int[] KEY_CODES = {0, 164, 85, 3, 23};

    /** Opens the app on the TV and, 4 seconds later, presses the scene's key. */
    static void run(MainActivity a, Scene s) {
        try {
            if (!s.pkg.isEmpty()) {
                Sync.command("/launch", new JSONObject().put("pkg", s.pkg), error -> {
                    if (error != null) a.problem(error);
                });
            }
            if (s.key != 0) {
                final JSONObject key = new JSONObject().put("code", s.key);
                new Handler(Looper.getMainLooper()).postDelayed(() -> Sync.command("/key", key, error -> {
                    if (error != null) a.problem(error);
                }), s.pkg.isEmpty() ? 0 : 4000);
            }
            a.say("Scene: " + s.name);
        } catch (JSONException ignored) {
        }
    }

    /** Makes a scene: a name, an app of the TV, and what to press after it opens. */
    static void newScene(MainActivity a, Runnable done) {
        JSONObject saved = Cache.apps();
        final List<String> pkgs = new ArrayList<>(), labels = new ArrayList<>();
        pkgs.add("");
        labels.add("No app");
        try {
            JSONArray all = saved == null ? new JSONArray() : saved.getJSONArray("apps");
            for (int i = 0; i < all.length(); i++) {
                pkgs.add(all.getJSONObject(i).optString("pkg"));
                labels.add(all.getJSONObject(i).optString("label"));
            }
        } catch (JSONException ignored) {
        }
        final int[] pickedApp = {pkgs.size() > 1 ? 1 : 0}, pickedKey = {0};

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        EditText name = field(a, "Name, for example Movie night");
        body.addView(name);
        TextView app = Ui.pillButton(a, "App: " + labels.get(pickedApp[0]), false);
        TextView then = Ui.pillButton(a, "Then: " + KEY_NAMES[0], false);
        for (TextView t : new TextView[] {app, then}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(a, 10);
            t.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            body.addView(t, lp);
        }
        app.setOnClickListener(v -> Ui.choose(a, "Open on the TV", labels.toArray(new String[0]), pickedApp[0], which -> {
            pickedApp[0] = which;
            app.setText("App: " + labels.get(which));
        }));
        then.setOnClickListener(v -> Ui.choose(a, "After it opens, press", KEY_NAMES, pickedKey[0], which -> {
            pickedKey[0] = which;
            then.setText("Then: " + KEY_NAMES[which]);
        }));
        Ui.dialog(a, "New scene", body, "Save", () -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty() || Tv.activeKey() == null) {
                a.say("Give the scene a name");
                return;
            }
            List<Scene> list = scenes(Tv.activeKey());
            Scene s = new Scene();
            s.name = n;
            s.pkg = pkgs.get(pickedApp[0]);
            s.label = labels.get(pickedApp[0]);
            s.key = KEY_CODES[pickedKey[0]];
            list.add(s);
            saveScenes(Tv.activeKey(), list);
            done.run();
        }).show();
    }

    static void deleteScene(MainActivity a, Scene s, Runnable done) {
        TextView body = Ui.text(a, "Remove the scene " + s.name + "?", 15, Ui.SUB, false);
        Dialog d = Ui.dialog(a, "Remove scene", body, "Remove", () -> {
            List<Scene> list = scenes(Tv.activeKey());
            for (Iterator<Scene> it = list.iterator(); it.hasNext(); ) if (it.next().name.equals(s.name)) it.remove();
            saveScenes(Tv.activeKey(), list);
            done.run();
        });
        d.show();
    }

    private static EditText field(MainActivity a, String hint) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setHintTextColor(Ui.SUB);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(16);
        e.setBackground(Ui.round(a, Ui.SURFACE, 14));
        e.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 14));
        return e;
    }
}
