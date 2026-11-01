package io.freemytv.remote;

import android.app.Dialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Type on the TV from the phone's keyboard: each letter typed is sent as a key press, a deletion as Backspace, and Search
 * (Enter) as Enter. The TV's key helper takes lowercase letters, digits, space and , . - / only.
 */
final class TypePad {
    private TypePad() {}

    /** The TV's key code for a character, or -1 when the TV can't be told that one. */
    static int code(char c) {
        c = Character.toLowerCase(c);
        if (c >= 'a' && c <= 'z') return 29 + (c - 'a');
        if (c >= '0' && c <= '9') return 7 + (c - '0');
        switch (c) {
            case ' ': return 62;
            case ',': return 55;
            case '.': return 56;
            case '-': return 69;
            case '/': return 76;
            default: return -1;
        }
    }

    static void show(MainActivity a) {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView help = Ui.text(a, "Open a search box on the TV, then type here. What you type goes to the TV as you type.", 14, Ui.SUB, false);
        help.setLineSpacing(0, 1.2f);
        help.setPadding(0, 0, 0, Ui.dp(a, 12));
        body.addView(help);

        EditText field = new EditText(a);
        field.setHint("Type here");
        field.setHintTextColor(Ui.SUB);
        field.setTextColor(Ui.TEXT);
        field.setTextSize(18);
        field.setSingleLine();
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        field.setBackground(Ui.round(a, Ui.SURFACE, 14));
        field.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 14));
        body.addView(field);

        TextView note = Ui.text(a, "", 13, Ui.DANGER, false);
        note.setPadding(0, Ui.dp(a, 8), 0, 0);
        body.addView(note);

        LinearLayout actions = new LinearLayout(a);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView enter = Ui.pillButton(a, "Enter / Search", true);
        TextView clear = Ui.pillButton(a, "Delete all", false);
        actions.addView(enter);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = Ui.dp(a, 10);
        actions.addView(clear, cl);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.topMargin = Ui.dp(a, 14);
        body.addView(actions, al);

        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                for (int i = 0; i < before; i++) press(a, 67);
                boolean skipped = false;
                for (int i = start; i < start + count && i < s.length(); i++) {
                    int k = code(s.charAt(i));
                    if (k < 0) skipped = true;
                    else press(a, k);
                }
                note.setText(skipped ? "Letters, digits, space and , . - / only: the TV can't take that character." : "");
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        enter.setOnClickListener(v -> press(a, 66));
        field.setOnEditorActionListener((v, action, e) -> {
            press(a, 66);
            return true;
        });
        clear.setOnClickListener(v -> {
            int n = field.getText().length();
            field.setText("");   // the watcher sends the deletions
            if (n == 0) note.setText("");
        });
        Dialog d = Ui.dialog(a, "Type on the TV", body, null, null);
        d.show();
        field.requestFocus();
    }

    private static void press(MainActivity a, int code) {
        try {
            Sync.command("/key", new JSONObject().put("code", code), error -> {
                if (error != null) a.problem(error);
            });
        } catch (JSONException ignored) {
        }
    }
}
