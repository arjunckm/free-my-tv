package io.freemytv.remote;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * A touch surface for the TV: every step of a swipe presses an arrow, a tap is OK and a long press is Back.
 * It only decides which key; RemotePage sends it.
 */
final class Touchpad extends View {
    interface Keys { void press(int code); }

    private static final int UP = 19, DOWN = 20, LEFT = 21, RIGHT = 22, OK = 23, BACK = 4;

    private final Keys keys;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float step, slop, radius;
    private float downX, downY, lastX, lastY, touchX, touchY;
    private boolean moved, longPressed, touching;

    private final Runnable hold = new Runnable() {
        @Override public void run() {
            if (moved || !touching) return;
            longPressed = true;
            Ui.haptic(Touchpad.this, HapticFeedbackConstants.LONG_PRESS);
            Touchpad.this.keys.press(BACK);
        }
    };

    Touchpad(Context c, Keys keys) {
        super(c);
        this.keys = keys;
        step = Ui.dp(c, 44);
        slop = Ui.dp(c, 10);
        radius = Ui.dp(c, 28);
        fill.setColor(Ui.SURFACE2);
        label.setColor(Ui.SUB);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(Ui.dp(c, 14));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dot.setColor(0x557C9CFF);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, fill);
        float y = getHeight() / 2f;
        canvas.drawText("Swipe to move", getWidth() / 2f, y - Ui.dp(getContext(), 8), label);
        canvas.drawText("Tap = OK   ·   Hold = Back", getWidth() / 2f, y + Ui.dp(getContext(), 14), label);
        if (touching) canvas.drawCircle(touchX, touchY, Ui.dp(getContext(), 30), dot);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        touchX = e.getX();
        touchY = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);   // the page scrolls otherwise
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                moved = false;
                longPressed = false;
                touching = true;
                handler.postDelayed(hold, 550);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!moved && Math.hypot(e.getX() - downX, e.getY() - downY) > slop) {
                    moved = true;
                    handler.removeCallbacks(hold);
                }
                if (moved) swipe(e.getX(), e.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(hold);
                touching = false;
                if (!moved && !longPressed) {
                    Ui.haptic(this, HapticFeedbackConstants.KEYBOARD_TAP);
                    keys.press(OK);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(hold);
                touching = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    /** One arrow for every `step` pixels travelled, along the direction that moved most. */
    private void swipe(float x, float y) {
        float dx = x - lastX, dy = y - lastY;
        if (Math.abs(dx) >= Math.abs(dy)) {
            if (Math.abs(dx) < step) return;
            keys.press(dx > 0 ? RIGHT : LEFT);
            lastX += dx > 0 ? step : -step;
            lastY = y;
        } else {
            if (Math.abs(dy) < step) return;
            keys.press(dy > 0 ? DOWN : UP);
            lastY += dy > 0 ? step : -step;
            lastX = x;
        }
        Ui.haptic(this, HapticFeedbackConstants.CLOCK_TICK);
    }
}
