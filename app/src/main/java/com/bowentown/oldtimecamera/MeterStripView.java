package com.bowentown.oldtimecamera;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Compact 22dp darkroom light-meter bar combining a 12-bin live luminance
 * waveform histogram and a smooth analog EV needle scale (-2 .. 0 .. +2 stops).
 */
public final class MeterStripView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barWarnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zeroDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillRect = new RectF();
    private final RectF barRect = new RectF();

    private final float[] bins = new float[PreviewStats.LUMA_BIN_COUNT];
    private float targetStops = 0f;
    private float drawnStops = 0f;
    private ValueAnimator needleAnim;

    public MeterStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;

        bgPaint.setColor(0xBA141210);
        bgPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(0x2AEFE7D6);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1f, 0.9f * d));

        barPaint.setColor(0x88EFE7D6);
        barPaint.setStyle(Paint.Style.FILL);

        barWarnPaint.setColor(0xEEF2A65A);
        barWarnPaint.setStyle(Paint.Style.FILL);

        tickPaint.setColor(0x66EFE7D6);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(Math.max(1f, 0.9f * d));
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        needlePaint.setColor(0xFFF2A65A);
        needlePaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStrokeWidth(2.0f * d);
        needlePaint.setStrokeCap(Paint.Cap.ROUND);

        zeroDotPaint.setColor(0xAAEFE7D6);
        zeroDotPaint.setStyle(Paint.Style.FILL);
    }

    public void updateTelemetry(PreviewStats stats) {
        if (stats == null || stats.width <= 0 || stats.height <= 0) {
            return;
        }
        System.arraycopy(stats.lumaBins, 0, bins, 0,
                Math.min(bins.length, stats.lumaBins.length));
        float nextStops = FilmRenderMath.meterNeedleStops(stats);
        if (Math.abs(nextStops - targetStops) >= 0.04f) {
            targetStops = nextStops;
            if (needleAnim != null) needleAnim.cancel();
            needleAnim = ValueAnimator.ofFloat(drawnStops, targetStops);
            needleAnim.setDuration(200);
            needleAnim.setInterpolator(new DecelerateInterpolator());
            needleAnim.addUpdateListener(a -> {
                drawnStops = (float) a.getAnimatedValue();
                invalidate();
            });
            needleAnim.start();
        } else {
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (needleAnim != null) {
            needleAnim.cancel();
            needleAnim = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 4f || h <= 4f) return;

        float d = getResources().getDisplayMetrics().density;
        float radius = h * 0.5f;
        pillRect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f);
        canvas.drawRoundRect(pillRect, radius, radius, bgPaint);
        canvas.drawRoundRect(pillRect, radius, radius, borderPaint);

        // Left block (~36% width): 12-bin live luminance histogram.
        float histLeft = 10f * d;
        float histW = (w - 20f * d) * 0.36f;
        float barBottom = h - 4.5f * d;
        float maxBarH = h - 9f * d;

        float peakBin = 0.24f;
        for (float b : bins) {
            if (b > peakBin) peakBin = b;
        }
        float step = histW / bins.length;
        float bw = Math.max(1.5f * d, step * 0.62f);
        for (int i = 0; i < bins.length; i++) {
            float frac = Math.min(1f, bins[i] / peakBin);
            float bh = Math.max(1.5f * d, frac * maxBarH);
            float bx = histLeft + i * step;
            barRect.set(bx, barBottom - bh, bx + bw, barBottom);
            boolean extremeClip = (i == bins.length - 1 && bins[i] >= 0.08f)
                    || (i == 0 && bins[i] >= 0.32f);
            canvas.drawRoundRect(barRect, 1f * d, 1f * d, extremeClip ? barWarnPaint : barPaint);
        }

        // Thin hairline divider.
        float divX = histLeft + histW + 8f * d;
        canvas.drawLine(divX, 5f * d, divX, h - 5f * d, tickPaint);

        // Right block: analog -2 .. 0 .. +2 stops meter scale & gliding needle.
        float meterLeft = divX + 9f * d;
        float meterRight = w - 11f * d;
        float meterMid = (meterLeft + meterRight) * 0.5f;
        float cy = h * 0.5f;

        for (int s = -2; s <= 2; s++) {
            float x = meterMid + (s / 2f) * ((meterRight - meterLeft) * 0.5f);
            float tickH = (s == 0) ? 4.5f * d : (Math.abs(s) == 2 ? 3.5f * d : 2.3f * d);
            canvas.drawLine(x, cy - tickH, x, cy + tickH, tickPaint);
        }
        canvas.drawCircle(meterMid, cy, 1.8f * d, zeroDotPaint);

        float norm = Math.max(-1f, Math.min(1f, drawnStops / 2f));
        float needleX = meterMid + norm * ((meterRight - meterLeft) * 0.5f);
        boolean offBalance = Math.abs(drawnStops) > 1.15f;
        needlePaint.setColor(offBalance ? 0xFFD25A34 : 0xFFF2A65A);
        canvas.drawLine(needleX, 4.5f * d, needleX, h - 4.5f * d, needlePaint);
    }
}
