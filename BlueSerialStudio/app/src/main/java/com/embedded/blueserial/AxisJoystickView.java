package com.embedded.blueserial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/** Independent one-axis joystick: throttle (Y) and steering (X), each in -100..100. */
public final class AxisJoystickView extends View {
    public enum Axis { VERTICAL, HORIZONTAL }
    public interface Listener { void onValue(int value, boolean released); }
    private final Axis axis;
    private Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int value = 0;
    private boolean tracking = false;
    private long lastEmitted = 0;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!tracking) return;
            if (listener != null) listener.onValue(value, false);
            handler.postDelayed(this, 160);
        }
    };
    public AxisJoystickView(Context context, Axis axis) { super(context); this.axis = axis; }
    public void setListener(Listener callback) { listener = callback; }
    private float dp(float size) { return size * getResources().getDisplayMetrics().density; }
    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(155));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth()/2f, cy=getHeight()/2f;
        float radius = Math.max(dp(22), Math.min(getWidth(), getHeight()) / 2f - dp(13));
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xffe6f2f6); canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(0xffb5ced9); paint.setStrokeWidth(dp(2));
        if (axis == Axis.VERTICAL) canvas.drawLine(cx, cy-radius*.8f, cx, cy+radius*.8f, paint);
        else canvas.drawLine(cx-radius*.8f, cy, cx+radius*.8f, cy, paint);
        float pos = radius*.66f*value/100f;
        float knobX = axis == Axis.HORIZONTAL ? cx+pos : cx;
        float knobY = axis == Axis.VERTICAL ? cy-pos : cy;
        paint.setColor(tracking ? 0xff039db0 : 0xff14344d);
        canvas.drawCircle(knobX, knobY, dp(25), paint);
        paint.setColor(Color.WHITE); canvas.drawCircle(knobX, knobY, dp(7), paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                applyTouch(event, true);
                handler.removeCallbacks(heartbeat); handler.postDelayed(heartbeat,160);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tracking) applyTouch(event, false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                releaseControl(); performClick(); return true;
            default: return true;
        }
    }
    private void applyTouch(MotionEvent event, boolean first) {
        float radius = Math.max(dp(22), Math.min(getWidth(), getHeight())/2f - dp(13));
        float amount = axis==Axis.VERTICAL ? (getHeight()/2f-event.getY()) / radius :
            (event.getX()-getWidth()/2f) / radius;
        value = Math.round(Math.max(-1f, Math.min(1f, amount))*100f);
        invalidate();
        long now = SystemClock.uptimeMillis();
        if (first || now-lastEmitted>=60) {
            lastEmitted=now;
            if (listener!=null) listener.onValue(value,false);
        }
    }
    public void releaseControl() {
        handler.removeCallbacks(heartbeat);
        boolean wasTracking=tracking;
        tracking=false; value=0; invalidate();
        if (wasTracking && listener!=null) listener.onValue(0,true);
    }
    @Override protected void onDetachedFromWindow() {
        releaseControl(); super.onDetachedFromWindow();
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
