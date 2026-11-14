package com.bowentown.oldtimecamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws an animated warm-amber progress sweep around the shutter ring while
 * steady-shutter stabilization waits up to 1.5s for hand motion to settle.
 */
public final class ShutterProgressView extends View {

    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private final float strokePx;
    private float progress = 0f;
    private boolean active = false;

    public ShutterProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        strokePx = 2.5f * d;

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(strokePx);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(0xFFF2A65A);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(strokePx * 2.2f);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setColor(0x40F2A65A);
    }

    public void setWaitingProgress(boolean active, float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        if (this.active == active && Math.abs(this.progress - clamped) < 0.005f) {
            return;
        }
        this.active = active;
        this.progress = clamped;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float pad = strokePx * 0.6f;
        arcBounds.set(pad, pad, Math.max(pad + 1f, w - pad), Math.max(pad + 1f, h - pad));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active || arcBounds.width() <= 0f) return;
        float sweep = Math.max(12f, progress * 360f);
        canvas.drawArc(arcBounds, -90f, sweep, false, glowPaint);
        canvas.drawArc(arcBounds, -90f, sweep, false, arcPaint);
    }
}
