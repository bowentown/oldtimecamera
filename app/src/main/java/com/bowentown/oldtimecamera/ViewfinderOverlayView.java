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
 * Draws the optical rangefinder frame curtains, subtle corner brackets,
 * optional rule-of-thirds composition grid, and animated tap-to-focus reticle.
 */
public final class ViewfinderOverlayView extends View {

    public interface OnFrameBoundsListener {
        void onBoundsChanged(RectF activeRect, int viewWidth, int viewHeight);
    }

    private final Paint curtainPaint = new Paint();
    private final Paint frameLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF activeRect = new RectF();
    private float currentScaleX = 1f;
    private float currentScaleY = 1f;
    private float targetScaleX = 1f;
    private float targetScaleY = 1f;

    private boolean gridEnabled = false;
    private float focusX = -1f;
    private float focusY = -1f;
    private float focusAlpha = 0f;
    private float focusScale = 1f;

    private ValueAnimator frameAnimator;
    private ValueAnimator focusAnimator;
    private OnFrameBoundsListener boundsListener;

    private final Runnable hideFocusTask = this::fadeOutFocus;

    public ViewfinderOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;

        curtainPaint.setColor(0xFF11100E);
        curtainPaint.setStyle(Paint.Style.FILL);

        frameLinePaint.setColor(0x38EFE7D6);
        frameLinePaint.setStyle(Paint.Style.STROKE);
        frameLinePaint.setStrokeWidth(0.8f * d);

        cornerPaint.setColor(0x88EFE7D6);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(1.4f * d);
        cornerPaint.setStrokeCap(Paint.Cap.SQUARE);

        gridPaint.setColor(0x30EFE7D6);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(0.75f * d);

        focusPaint.setColor(0xFFF2A65A);
        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeWidth(1.5f * d);
        focusPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setOnFrameBoundsListener(OnFrameBoundsListener listener) {
        this.boundsListener = listener;
        notifyBounds();
    }

    public void setGridEnabled(boolean enabled) {
        this.gridEnabled = enabled;
        invalidate();
    }

    public void setFrameScale(float scaleX, float scaleY, boolean animate) {
        targetScaleX = Math.max(0.1f, Math.min(1f, scaleX));
        targetScaleY = Math.max(0.1f, Math.min(1f, scaleY));
        if (frameAnimator != null) frameAnimator.cancel();

        if (!animate || (Math.abs(currentScaleX - targetScaleX) < 0.001f
                && Math.abs(currentScaleY - targetScaleY) < 0.001f)) {
            currentScaleX = targetScaleX;
            currentScaleY = targetScaleY;
            updateActiveRect();
            invalidate();
            return;
        }

        final float startX = currentScaleX;
        final float startY = currentScaleY;
        frameAnimator = ValueAnimator.ofFloat(0f, 1f);
        frameAnimator.setDuration(220);
        frameAnimator.setInterpolator(new DecelerateInterpolator());
        frameAnimator.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            currentScaleX = startX + (targetScaleX - startX) * t;
            currentScaleY = startY + (targetScaleY - startY) * t;
            updateActiveRect();
            invalidate();
        });
        frameAnimator.start();
    }

    public void showFocusAt(float x, float y) {
        removeCallbacks(hideFocusTask);
        if (focusAnimator != null) focusAnimator.cancel();

        focusX = x;
        focusY = y;
        focusAlpha = 1f;
        focusScale = 1.28f;

        focusAnimator = ValueAnimator.ofFloat(0f, 1f);
        focusAnimator.setDuration(180);
        focusAnimator.setInterpolator(new DecelerateInterpolator());
        focusAnimator.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            focusScale = 1.28f - 0.28f * t;
            invalidate();
        });
        focusAnimator.start();
        postDelayed(hideFocusTask, 1350);
    }

    private void fadeOutFocus() {
        if (focusAnimator != null) focusAnimator.cancel();
        focusAnimator = ValueAnimator.ofFloat(focusAlpha, 0f);
        focusAnimator.setDuration(260);
        focusAnimator.addUpdateListener(anim -> {
            focusAlpha = (float) anim.getAnimatedValue();
            invalidate();
        });
        focusAnimator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateActiveRect();
    }

    private void updateActiveRect() {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float fw = w * currentScaleX;
        float fh = h * currentScaleY;
        float left = (w - fw) * 0.5f;
        float top = (h - fh) * 0.5f;
        activeRect.set(left, top, left + fw, top + fh);
        notifyBounds();
    }

    private void notifyBounds() {
        if (boundsListener != null && getWidth() > 0 && getHeight() > 0) {
            boundsListener.onBoundsChanged(new RectF(activeRect), getWidth(), getHeight());
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (activeRect.width() <= 0 || activeRect.height() <= 0) {
            updateActiveRect();
        }

        // Matte darkroom curtains outside the chosen aspect ratio.
        if (activeRect.top > 0.5f) {
            canvas.drawRect(0f, 0f, w, activeRect.top, curtainPaint);
            canvas.drawRect(0f, activeRect.bottom, w, h, curtainPaint);
        }
        if (activeRect.left > 0.5f) {
            canvas.drawRect(0f, activeRect.top, activeRect.left, activeRect.bottom, curtainPaint);
            canvas.drawRect(activeRect.right, activeRect.top, w, activeRect.bottom, curtainPaint);
        }

        // Optional rule-of-thirds composition lines.
        if (gridEnabled) {
            float gw = activeRect.width() / 3f;
            float gh = activeRect.height() / 3f;
            canvas.drawLine(activeRect.left + gw, activeRect.top,
                    activeRect.left + gw, activeRect.bottom, gridPaint);
            canvas.drawLine(activeRect.left + gw * 2f, activeRect.top,
                    activeRect.left + gw * 2f, activeRect.bottom, gridPaint);
            canvas.drawLine(activeRect.left, activeRect.top + gh,
                    activeRect.right, activeRect.top + gh, gridPaint);
            canvas.drawLine(activeRect.left, activeRect.top + gh * 2f,
                    activeRect.right, activeRect.top + gh * 2f, gridPaint);
        }

        // Subtle optical viewfinder hairline + corner ticks.
        canvas.drawRect(activeRect, frameLinePaint);
        float d = getResources().getDisplayMetrics().density;
        float tick = 12f * d;
        float l = activeRect.left + 1f;
        float t = activeRect.top + 1f;
        float r = activeRect.right - 1f;
        float b = activeRect.bottom - 1f;

        canvas.drawLine(l, t, l + tick, t, cornerPaint);
        canvas.drawLine(l, t, l, t + tick, cornerPaint);
        canvas.drawLine(r - tick, t, r, t, cornerPaint);
        canvas.drawLine(r, t, r, t + tick, cornerPaint);
        canvas.drawLine(l, b, l + tick, b, cornerPaint);
        canvas.drawLine(l, b - tick, l, b, cornerPaint);
        canvas.drawLine(r - tick, b, r, b, cornerPaint);
        canvas.drawLine(r, b - tick, r, b, cornerPaint);

        // Animated tap-to-focus corner reticle.
        if (focusAlpha > 0.01f && focusX >= 0f && focusY >= 0f) {
            focusPaint.setAlpha(Math.round(focusAlpha * 255f));
            float half = 22f * d * focusScale;
            float arm = 9f * d * focusScale;
            float fx = Math.max(activeRect.left + half, Math.min(activeRect.right - half, focusX));
            float fy = Math.max(activeRect.top + half, Math.min(activeRect.bottom - half, focusY));

            float fl = fx - half;
            float ft = fy - half;
            float fr = fx + half;
            float fb = fy + half;

            canvas.drawLine(fl, ft, fl + arm, ft, focusPaint);
            canvas.drawLine(fl, ft, fl, ft + arm, focusPaint);
            canvas.drawLine(fr - arm, ft, fr, ft, focusPaint);
            canvas.drawLine(fr, ft, fr, ft + arm, focusPaint);
            canvas.drawLine(fl, fb, fl + arm, fb, focusPaint);
            canvas.drawLine(fl, fb - arm, fl, fb, focusPaint);
            canvas.drawLine(fr - arm, fb, fr, fb, focusPaint);
            canvas.drawLine(fr, fb - arm, fr, fb, focusPaint);

            canvas.drawLine(fx - 3f * d, fy, fx + 3f * d, fy, focusPaint);
            canvas.drawLine(fx, fy - 3f * d, fx, fy + 3f * d, focusPaint);
        }
    }
}
