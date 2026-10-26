package com.embedded.blueserial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lightweight four-channel strip chart, with automatic Y-scale. */
public final class OscilloscopeView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<float[]> samples = new ArrayList<>();
    private static final int LIMIT = 180;
    private static final int[] COLORS = {0xff00aabc, 0xffffa540, 0xff8069e8, 0xffe45182};

    public OscilloscopeView(Context context) { super(context); setMinimumHeight(dp(265)); }
    private int dp(float px) { return (int) (px * getResources().getDisplayMetrics().density + .5f); }
    public void addSample(float[] input) {
        float[] four = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
        for (int i = 0; i < Math.min(4, input.length); i++) four[i] = input[i];
        samples.add(four);
        if (samples.size() > LIMIT) samples.remove(0);
        invalidate();
    }
    public void clear() { samples.clear(); invalidate(); }
    public int size() { return samples.size(); }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(280), MeasureSpec.EXACTLY));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(), height = getHeight();
        float l = dp(53), r = width - dp(12), t = dp(30), b = height - dp(32);
        p.setStyle(Paint.Style.FILL); p.setColor(0xfff7fafc);
        canvas.drawRoundRect(new RectF(0, 0, width, height), dp(14), dp(14), p);
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (float[] item : samples) {
            for (float v : item) if (!Float.isNaN(v) && !Float.isInfinite(v)) {
                min = Math.min(min, v); max = Math.max(max, v);
            }
        }
        if (!Float.isFinite(min) || !Float.isFinite(max)) { min = 0; max = 100; }
        float span = max - min;
        if (span < 1f) { max += .5f; min -= .5f; }
        else { max += span * .1f; min -= span * .1f; }
        p.setTextSize(dp(10)); p.setTextAlign(Paint.Align.RIGHT); p.setStyle(Paint.Style.STROKE);
        for (int i = 0; i <= 4; i++) {
            float y = t + (b - t) * i / 4f;
            p.setColor(0xffdfe9ef); p.setStrokeWidth(dp(1));
            canvas.drawLine(l, y, r, y, p);
            p.setStyle(Paint.Style.FILL); p.setColor(0xff7691a3);
            canvas.drawText(String.format(Locale.US, "%.1f", max - (max - min) * i / 4f), l - dp(6), y + dp(3), p);
            p.setStyle(Paint.Style.STROKE);
        }
        for (int i = 0; i <= 4; i++) {
            float x = l + (r - l) * i / 4f;
            p.setColor(0xffeaf0f4);
            canvas.drawLine(x, t, x, b, p);
        }
        if (samples.isEmpty()) {
            p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(13)); p.setColor(0xff8195a5);
            canvas.drawText("等待数值数据…", (l + r) / 2f, (t + b) / 2f, p);
        } else {
            for (int ch = 0; ch < 4; ch++) {
                p.setColor(COLORS[ch]); p.setStrokeWidth(dp(2.2f)); p.setStyle(Paint.Style.STROKE);
                Path line = new Path(); boolean started = false;
                for (int idx = 0; idx < samples.size(); idx++) {
                    float value = samples.get(idx)[ch];
                    if (!Float.isFinite(value)) { started = false; continue; }
                    float x = l + (r - l) * idx / (LIMIT - 1f);
                    float y = b - (value - min) * (b - t) / (max - min);
                    if (!started) { line.moveTo(x, y); started = true; }
                    else line.lineTo(x, y);
                }
                canvas.drawPath(line, p);
            }
        }
        p.setStyle(Paint.Style.FILL); p.setTextSize(dp(11)); p.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < 4; i++) {
            float x = l + i * (r - l) / 4;
            p.setColor(COLORS[i]); canvas.drawCircle(x + dp(4), dp(14), dp(4), p);
            p.setColor(0xff476172); canvas.drawText("CH" + (i + 1), x + dp(12), dp(18), p);
        }
        p.setColor(0xff849bab); p.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("最近 " + samples.size() + " 帧", r, b + dp(21), p);
    }
}
