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
    private final Paint dimArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private final float strokePx;
    private float progress = 0f;
    private boolean active = false;
    private int burstDone = 0;
    private int burstTotal = 0;

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

        dimArcPaint.setStyle(Paint.Style.STROKE);
        dimArcPaint.setStrokeWidth(strokePx);
        dimArcPaint.setStrokeCap(Paint.Cap.ROUND);
        dimArcPaint.setColor(0x3AEFE7D6);
    }

    public void setWaitingProgress(boolean active, float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        if (this.active == active && Math.abs(this.progress - clamped) < 0.005f
                && this.burstTotal == 0) {
            return;
        }
        this.active = active;
        this.progress = clamped;
        this.burstTotal = 0;
        this.burstDone = 0;
        invalidate();
    }

    /** Shows segmented 4-beat quad-burst progress arcs around the shutter ring. */
    public void setBurstProgress(int completedBeats, int totalBeats) {
        if (this.burstDone == completedBeats && this.burstTotal == totalBeats) {
            return;
        }
        this.active = false;
        this.burstDone = Math.max(0, completedBeats);
        this.burstTotal = Math.max(0, totalBeats);
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
        if (arcBounds.width() <= 0f) return;
        if (burstTotal > 1) {
            float segAngle = 360f / burstTotal;
            float gap = 14f;
            float sweep = Math.max(8f, segAngle - gap);
            for (int i = 0; i < burstTotal; i++) {
                float start = -90f + i * segAngle + gap * 0.5f;
                if (i < burstDone) {
                    canvas.drawArc(arcBounds, start, sweep, false, glowPaint);
                    canvas.drawArc(arcBounds, start, sweep, false, arcPaint);
                } else {
                    canvas.drawArc(arcBounds, start, sweep, false, dimArcPaint);
                }
            }
            return;
        }
        if (!active) return;
        float sweep = Math.max(12f, progress * 360f);
        canvas.drawArc(arcBounds, -90f, sweep, false, glowPaint);
        canvas.drawArc(arcBounds, -90f, sweep, false, arcPaint);
    }
}
