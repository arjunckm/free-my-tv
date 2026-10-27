package io.freemytv.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** The remote's round pad: four arrows around OK. Holding an arrow repeats it. */
final class DPad extends View {
    interface Listener { void key(int code); }

    // Zones: 0 up, 1 right, 2 down, 3 left, 4 OK
    private static final int[] CODES = {19, 22, 20, 21, 23};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF outer = new RectF(), inner = new RectF();
    private final Path sector = new Path(), arrow = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private int pressed = -1;
    private float cx, cy, radius, okRadius;

    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (pressed < 0 || pressed == 4) return;
            listener.key(CODES[pressed]);
            Ui.haptic(DPad.this, HapticFeedbackConstants.CLOCK_TICK);
            handler.postDelayed(this, 130);
        }
    };

    DPad(Context c, Listener l) {
        super(c);
        listener = l;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(c, 1));
        stroke.setColor(Ui.LINE);
        glyph.setTextAlign(Paint.Align.CENTER);
        glyph.setTypeface(Ui.MEDIUM);
    }

    @Override protected void onMeasure(int w, int h) {
        int size = MeasureSpec.getSize(w);
        setMeasuredDimension(size, size);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.min(w, h) / 2f - Ui.dp(getContext(), 2);
        okRadius = radius * 0.36f;
        outer.set(cx - radius, cy - radius, cx + radius, cy + radius);
        inner.set(cx - okRadius, cy - okRadius, cx + okRadius, cy + okRadius);
    }

    @Override protected void onDraw(Canvas c) {
        fill.setColor(Ui.SURFACE2);
        c.drawCircle(cx, cy, radius, fill);
        c.drawCircle(cx, cy, radius, stroke);
        if (pressed >= 0 && pressed < 4) {   // the arrow under the finger lights up
            float start = -135 + pressed * 90;
            sector.rewind();
            sector.arcTo(outer, start, 90);
            sector.arcTo(inner, start + 90, -90);
            sector.close();
            fill.setColor(0x447C9CFF);
            c.drawPath(sector, fill);
        }
        fill.setColor(pressed == 4 ? Ui.ACCENT : Ui.SURFACE);
        c.drawCircle(cx, cy, okRadius, fill);
        c.drawCircle(cx, cy, okRadius, stroke);
        glyph.setColor(pressed == 4 ? Ui.ON_ACCENT : Ui.TEXT);
        glyph.setTextSize(okRadius * 0.62f);
        c.drawText("OK", cx, cy + glyph.getTextSize() * 0.35f, glyph);
        // Arrows
        float mid = (radius + okRadius) / 2f, s = radius * 0.1f;
        for (int z = 0; z < 4; z++) {
            float ax = cx + (z == 1 ? mid : z == 3 ? -mid : 0), ay = cy + (z == 2 ? mid : z == 0 ? -mid : 0);
            arrow.rewind();
            if (z == 0) { arrow.moveTo(ax - s, ay + s * 0.5f); arrow.lineTo(ax, ay - s * 0.6f); arrow.lineTo(ax + s, ay + s * 0.5f); }
            if (z == 2) { arrow.moveTo(ax - s, ay - s * 0.5f); arrow.lineTo(ax, ay + s * 0.6f); arrow.lineTo(ax + s, ay - s * 0.5f); }
            if (z == 1) { arrow.moveTo(ax - s * 0.5f, ay - s); arrow.lineTo(ax + s * 0.6f, ay); arrow.lineTo(ax - s * 0.5f, ay + s); }
            if (z == 3) { arrow.moveTo(ax + s * 0.5f, ay - s); arrow.lineTo(ax - s * 0.6f, ay); arrow.lineTo(ax + s * 0.5f, ay + s); }
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeWidth(Ui.dp(getContext(), 3));
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
            glyph.setColor(pressed == z ? Ui.ACCENT : Ui.SUB);
            c.drawPath(arrow, glyph);
            glyph.setStyle(Paint.Style.FILL);
        }
    }

    private int zoneAt(float x, float y) {
        float dx = x - cx, dy = y - cy, d = (float) Math.hypot(dx, dy);
        if (d > radius) return -1;
        if (d <= okRadius) return 4;
        double angle = Math.toDegrees(Math.atan2(dy, dx));   // 0 = right, 90 = down
        if (angle >= -135 && angle < -45) return 0;
        if (angle >= -45 && angle < 45) return 1;
        if (angle >= 45 && angle < 135) return 2;
        return 3;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = zoneAt(e.getX(), e.getY());
                if (pressed < 0) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                Ui.haptic(this, HapticFeedbackConstants.KEYBOARD_TAP);
                listener.key(CODES[pressed]);
                handler.postDelayed(repeat, 420);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                handler.removeCallbacks(repeat);
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
