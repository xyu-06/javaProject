package com.embedded.blueserial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/** X and Y range [-100, 100]; +Y is forward/up. */
public final class JoystickView extends View {
    public interface Listener { void onMove(int x, int y, boolean released); }
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;
    private float x = 0f, y = 0f;
    private boolean pressed = false;
    private long lastSent = 0;
    private final Handler repeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!pressed) return;
            if (listener != null) listener.onMove(Math.round(x * 100), Math.round(y * 100), false);
            repeatHandler.postDelayed(this, 160);
        }
    };
    private final int navy = Color.rgb(15, 33, 50);
    private final int teal = Color.rgb(0, 180, 193);

    public JoystickView(Context context) { super(context); setMinimumHeight(dp(220)); }
    public void setListener(Listener listener) { this.listener = listener; }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.min(MeasureSpec.getSize(widthMeasureSpec), dp(270));
        if (size < dp(180)) size = dp(180);
        setMeasuredDimension(size, size);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - dp(18);
        p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(231, 243, 246));
        canvas.drawCircle(cx, cy, r, p);
        p.setColor(Color.rgb(203, 226, 233)); p.setStrokeWidth(dp(1)); p.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(cx, cy, r * 0.72f, p);
        canvas.drawLine(cx - r, cy, cx + r, cy, p);
        canvas.drawLine(cx, cy - r, cx, cy + r, p);
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(dp(12)); p.setColor(Color.rgb(81, 113, 128));
        canvas.drawText("前进", cx, cy - r + dp(20), p);
        canvas.drawText("后退", cx, cy + r - dp(10), p);
        p.setColor(teal); p.setAlpha(pressed ? 70 : 35);
        canvas.drawCircle(cx + x * r * .75f, cy - y * r * .75f, dp(42), p);
        p.setAlpha(255); p.setColor(navy);
        canvas.drawCircle(cx + x * r * .75f, cy - y * r * .75f, dp(32), p);
        p.setColor(Color.WHITE);
        canvas.drawCircle(cx + x * r * .75f, cy - y * r * .75f, dp(8), p);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                pressed = true;
                update(event.getX(), event.getY(), true);
                repeatHandler.removeCallbacks(heartbeat);
                repeatHandler.postDelayed(heartbeat, 160);
                return true;
            case MotionEvent.ACTION_MOVE:
                update(event.getX(), event.getY(), false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                pressed = false; repeatHandler.removeCallbacks(heartbeat); x = 0; y = 0;
                invalidate();
                if (listener != null) listener.onMove(0, 0, true);
                performClick();
                return true;
            default: return true;
        }
    }

    private void update(float px, float py, boolean force) {
        float radius = Math.min(getWidth(), getHeight()) / 2f - dp(18);
        float rawX = (px - getWidth() / 2f) / radius;
        float rawY = (getHeight() / 2f - py) / radius;
        float mag = (float) Math.sqrt(rawX * rawX + rawY * rawY);
        if (mag > 1) { rawX /= mag; rawY /= mag; }
        x = rawX; y = rawY; invalidate();
        long now = SystemClock.uptimeMillis();
        if (force || now - lastSent >= 60) {
            lastSent = now;
            if (listener != null) listener.onMove(Math.round(x * 100), Math.round(y * 100), false);
        }
    }
    public void releaseControl() {
        repeatHandler.removeCallbacks(heartbeat);
        if (pressed && listener != null) listener.onMove(0, 0, true);
        pressed = false;
        x = 0; y = 0; invalidate();
    }
    @Override protected void onDetachedFromWindow() {
        releaseControl();
        super.onDetachedFromWindow();
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
