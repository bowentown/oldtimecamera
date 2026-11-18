package com.bowentown.oldtimecamera;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Draws the optical rangefinder frame curtains, subtle corner brackets,
 * multi-mode composition guides (thirds, rotatable golden spiral, crosshair) with
 * intersection snap highlights, and animated tap-to-focus reticle.
 */
public final class ViewfinderOverlayView extends View {

    public interface OnFrameBoundsListener {
        void onBoundsChanged(RectF activeRect, int viewWidth, int viewHeight);
    }

    private final Paint curtainPaint = new Paint();
    private final Paint frameLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spiralPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint snapGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint snapCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path spiralPath = new Path();
    private final RectF tmpOval = new RectF();

    private final RectF activeRect = new RectF();
    private float currentScaleX = 1f;
    private float currentScaleY = 1f;
    private float targetScaleX = 1f;
    private float targetScaleY = 1f;

    private CompositionMath.GridMode gridMode = CompositionMath.GridMode.OFF;
    private int goldenRotation = 0;
    private int highlightedPowerPoint = -1;
    private int drawingPowerPoint = -1;
    private float snapGlowAlpha = 0f;

    private float focusX = -1f;
    private float focusY = -1f;
    private float focusAlpha = 0f;
    private float focusScale = 1f;

    private ValueAnimator frameAnimator;
    private ValueAnimator focusAnimator;
    private ValueAnimator snapAnimator;
    private OnFrameBoundsListener boundsListener;

    private final Runnable hideFocusTask = this::fadeOutFocus;

    private boolean levelEnabled = false;
    private float tiltDegrees = 0f;
    private boolean wasLevel = false;
    private long lastLevelHapticMs = 0L;
    private float shutterCurtainProgress = 0f;
    private ValueAnimator shutterAnimator;
    private Bitmap ghostBitmap = null;
    private final Paint levelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghostPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shutterPaint = new Paint();
    private final Paint shutterFlashBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halfFrameBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpHalfRect = new RectF();

    private Bitmap halfFrameThumb = null;

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

        gridPaint.setColor(0x32EFE7D6);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(0.8f * d);

        spiralPaint.setColor(0x4CEFE7D6);
        spiralPaint.setStyle(Paint.Style.STROKE);
        spiralPaint.setStrokeWidth(1.05f * d);
        spiralPaint.setStrokeCap(Paint.Cap.ROUND);
        spiralPaint.setStrokeJoin(Paint.Join.ROUND);

        snapGlowPaint.setColor(0xFFF2A65A);
        snapGlowPaint.setStyle(Paint.Style.STROKE);
        snapGlowPaint.setStrokeWidth(1.6f * d);

        snapCorePaint.setColor(0xFFF2A65A);
        snapCorePaint.setStyle(Paint.Style.FILL);

        focusPaint.setColor(0xFFF2A65A);
        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeWidth(1.5f * d);
        focusPaint.setStrokeCap(Paint.Cap.ROUND);

        shutterPaint.setColor(0xF20D0C0B);
        shutterPaint.setStyle(Paint.Style.FILL);
        shutterFlashBorderPaint.setColor(0x99F2A65A);
        shutterFlashBorderPaint.setStyle(Paint.Style.STROKE);
        shutterFlashBorderPaint.setStrokeWidth(2f * d);

        halfFrameBorderPaint.setColor(0xDDF2A65A);
        halfFrameBorderPaint.setStyle(Paint.Style.STROKE);
        halfFrameBorderPaint.setStrokeWidth(1.2f * d);
    }

    public void setOnFrameBoundsListener(OnFrameBoundsListener listener) {
        this.boundsListener = listener;
        notifyBounds();
    }

    public void setGridEnabled(boolean enabled) {
        setGridMode(enabled ? CompositionMath.GridMode.THIRDS : CompositionMath.GridMode.OFF);
    }

    public void setGridMode(CompositionMath.GridMode mode) {
        CompositionMath.GridMode safe = mode != null ? mode : CompositionMath.GridMode.OFF;
        if (this.gridMode != safe) {
            this.gridMode = safe;
            if (safe == CompositionMath.GridMode.OFF) {
                setPowerPointHighlight(-1);
            } else {
                highlightedPowerPoint = -1;
                drawingPowerPoint = -1;
                snapGlowAlpha = 0f;
                if (snapAnimator != null) snapAnimator.cancel();
            }
            invalidate();
        }
    }

    public void setGoldenRotation(int rotSteps) {
        int norm = ((rotSteps % 4) + 4) % 4;
        if (this.goldenRotation != norm) {
            this.goldenRotation = norm;
            invalidate();
        }
    }

    /** Highlights an aligned composition power point with a subtle warm amber fade animation. */
    public void setPowerPointHighlight(int index) {
        if (highlightedPowerPoint == index) return;
        highlightedPowerPoint = index;
        if (index >= 0) {
            drawingPowerPoint = index;
        }
        if (snapAnimator != null) snapAnimator.cancel();
        if (index < 0 && drawingPowerPoint < 0) {
            snapGlowAlpha = 0f;
            invalidate();
            return;
        }
        float target = index >= 0 ? 1f : 0f;
        snapAnimator = ValueAnimator.ofFloat(snapGlowAlpha, target);
        snapAnimator.setDuration(index >= 0 ? 180 : 260);
        snapAnimator.setInterpolator(new DecelerateInterpolator());
        snapAnimator.addUpdateListener(anim -> {
            snapGlowAlpha = (float) anim.getAnimatedValue();
            if (highlightedPowerPoint < 0 && snapGlowAlpha <= 0.005f) {
                drawingPowerPoint = -1;
            }
            invalidate();
        });
        snapAnimator.start();
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

    public void setLevelEnabled(boolean enabled) {
        if (this.levelEnabled != enabled) {
            this.levelEnabled = enabled;
            if (!enabled) wasLevel = false;
            invalidate();
        }
    }

    public void setTiltDegrees(float degrees) {
        float norm = degrees % 360f;
        if (norm > 180f) norm -= 360f;
        if (norm <= -180f) norm += 360f;
        if (levelEnabled) {
            float snapAngle = Math.round(norm / 90f) * 90f;
            boolean isLevel = Math.abs(norm - snapAngle) <= 1.5f;
            if (isLevel && !wasLevel) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastLevelHapticMs >= 500L) {
                    lastLevelHapticMs = now;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
            }
            wasLevel = isLevel;
            if (Math.abs(norm - this.tiltDegrees) >= 0.25f) {
                this.tiltDegrees = norm;
                invalidate();
            }
        } else {
            this.tiltDegrees = norm;
        }
    }

    public void triggerShutterEffect() {
        if (shutterAnimator != null) shutterAnimator.cancel();
        shutterCurtainProgress = 1f;
        shutterAnimator = ValueAnimator.ofFloat(1f, 0f);
        shutterAnimator.setDuration(155);
        shutterAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        shutterAnimator.addUpdateListener(anim -> {
            shutterCurtainProgress = (float) anim.getAnimatedValue();
            invalidate();
        });
        shutterAnimator.start();
    }

    public boolean isPointInActiveFrame(float x, float y) {
        if (activeRect.width() <= 0f || activeRect.height() <= 0f) return true;
        return activeRect.contains(x, y);
    }

    public void setDoubleExposureGhost(Bitmap bitmap) {
        if (ghostBitmap != null && ghostBitmap != bitmap && !ghostBitmap.isRecycled()) {
            ghostBitmap.recycle();
        }
        ghostBitmap = bitmap;
        invalidate();
    }

    public void setHalfFrameThumb(Bitmap bitmap) {
        if (halfFrameThumb != null && halfFrameThumb != bitmap && !halfFrameThumb.isRecycled()) {
            halfFrameThumb.recycle();
        }
        halfFrameThumb = bitmap;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(hideFocusTask);
        if (frameAnimator != null) frameAnimator.cancel();
        if (focusAnimator != null) focusAnimator.cancel();
        if (snapAnimator != null) snapAnimator.cancel();
        if (shutterAnimator != null) shutterAnimator.cancel();
        if (ghostBitmap != null && !ghostBitmap.isRecycled()) {
            ghostBitmap.recycle();
            ghostBitmap = null;
        }
        if (halfFrameThumb != null && !halfFrameThumb.isRecycled()) {
            halfFrameThumb.recycle();
            halfFrameThumb = null;
        }
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

        // Translucent first-exposure guide during double exposure.
        if (ghostBitmap != null && !ghostBitmap.isRecycled()) {
            ghostPaint.setAlpha(92);
            canvas.save();
            canvas.clipRect(activeRect);
            canvas.drawBitmap(ghostBitmap, null, activeRect, ghostPaint);
            canvas.restore();
        }

        float d = getResources().getDisplayMetrics().density;
        drawCompositionOverlay(canvas, d);

        // Compact inset preview of frame 1/2 while composing half-frame 2/2.
        if (halfFrameThumb != null && !halfFrameThumb.isRecycled()) {
            float mw = Math.min(72f * d, activeRect.width() * 0.22f);
            float ratio = (float) halfFrameThumb.getHeight() / Math.max(1f, halfFrameThumb.getWidth());
            float mh = Math.min(84f * d, mw * ratio);
            float mx = activeRect.left + 10f * d;
            float my = activeRect.top + 10f * d;
            tmpHalfRect.set(mx, my, mx + mw, my + mh);
            canvas.save();
            canvas.drawBitmap(halfFrameThumb, null, tmpHalfRect, ghostPaint);
            canvas.drawRect(tmpHalfRect, halfFrameBorderPaint);
            canvas.restore();
        }

        // Subtle optical viewfinder hairline + corner ticks.
        canvas.drawRect(activeRect, frameLinePaint);
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

        // Minimalist horizon spirit-level hairline in frame center.
        if (levelEnabled) {
            float cx = activeRect.centerX();
            float cy = activeRect.centerY();
            float snapAngle = Math.round(tiltDegrees / 90f) * 90f;
            float delta = tiltDegrees - snapAngle;
            boolean isLevel = Math.abs(delta) <= 1.5f;
            levelPaint.setStyle(Paint.Style.STROKE);
            levelPaint.setStrokeCap(Paint.Cap.ROUND);
            levelPaint.setStrokeWidth((isLevel ? 1.7f : 1.15f) * d);
            levelPaint.setColor(isLevel ? 0xE6F2A65A : 0x80F0E8D8);

            canvas.save();
            canvas.rotate(-snapAngle, cx, cy);
            float tickStart = 28f * d;
            float tickEnd = 36f * d;
            canvas.drawLine(cx - tickEnd, cy, cx - tickStart, cy, levelPaint);
            canvas.drawLine(cx + tickStart, cy, cx + tickEnd, cy, levelPaint);
            canvas.restore();

            canvas.save();
            canvas.rotate(-tiltDegrees, cx, cy);
            float halfBar = 20f * d;
            canvas.drawLine(cx - halfBar, cy, cx + halfBar, cy, levelPaint);
            canvas.restore();
        }

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

        // Mechanical focal-plane shutter curtain release blink inside active frame.
        if (shutterCurtainProgress > 0.01f) {
            float bladeH = activeRect.height() * 0.5f * shutterCurtainProgress;
            shutterPaint.setAlpha(Math.round(235f * Math.min(1f, shutterCurtainProgress * 1.15f)));
            canvas.drawRect(activeRect.left, activeRect.top, activeRect.right,
                    activeRect.top + bladeH, shutterPaint);
            canvas.drawRect(activeRect.left, activeRect.bottom - bladeH, activeRect.right,
                    activeRect.bottom, shutterPaint);
            shutterFlashBorderPaint.setAlpha(Math.round(160f * shutterCurtainProgress));
            canvas.drawRect(activeRect, shutterFlashBorderPaint);
        }
    }

    private void drawCompositionOverlay(Canvas canvas, float d) {
        if (gridMode == CompositionMath.GridMode.OFF) return;

        float aw = activeRect.width();
        float ah = activeRect.height();
        float al = activeRect.left;
        float at = activeRect.top;

        canvas.save();
        canvas.clipRect(activeRect);

        if (gridMode == CompositionMath.GridMode.THIRDS) {
            float gw = aw / 3f;
            float gh = ah / 3f;
            canvas.drawLine(al + gw, at, al + gw, activeRect.bottom, gridPaint);
            canvas.drawLine(al + gw * 2f, at, al + gw * 2f, activeRect.bottom, gridPaint);
            canvas.drawLine(al, at + gh, activeRect.right, at + gh, gridPaint);
            canvas.drawLine(al, at + gh * 2f, activeRect.right, at + gh * 2f, gridPaint);
        } else if (gridMode == CompositionMath.GridMode.GOLDEN_SPIRAL) {
            CompositionMath.SpiralArc[] arcs = CompositionMath.buildGoldenSpiral(goldenRotation, 7);
            spiralPath.reset();
            for (int i = 0; i < arcs.length; i++) {
                CompositionMath.SpiralArc a = arcs[i];
                canvas.drawLine(
                        al + a.divX1 * aw, at + a.divY1 * ah,
                        al + a.divX2 * aw, at + a.divY2 * ah,
                        gridPaint);
                tmpOval.set(
                        al + a.ovalLeft * aw,
                        at + a.ovalTop * ah,
                        al + a.ovalRight * aw,
                        at + a.ovalBottom * ah);
                if (i == 0) {
                    spiralPath.moveTo(al + a.startX * aw, at + a.startY * ah);
                }
                spiralPath.arcTo(tmpOval, a.startAngleDeg, a.sweepAngleDeg, false);
            }
            canvas.drawPath(spiralPath, spiralPaint);
        } else if (gridMode == CompositionMath.GridMode.CROSSHAIR) {
            float cx = activeRect.centerX();
            float cy = activeRect.centerY();
            float outer = 20f * d;
            float inner = 6f * d;
            canvas.drawLine(cx - outer, cy, cx - inner, cy, spiralPaint);
            canvas.drawLine(cx + inner, cy, cx + outer, cy, spiralPaint);
            canvas.drawLine(cx, cy - outer, cx, cy - inner, spiralPaint);
            canvas.drawLine(cx, cy + inner, cx, cy + outer, spiralPaint);
        }

        if (drawingPowerPoint >= 0 && snapGlowAlpha > 0.01f) {
            float[][] pts = CompositionMath.powerPoints(gridMode, goldenRotation);
            if (drawingPowerPoint < pts.length) {
                float px = al + pts[drawingPowerPoint][0] * aw;
                float py = at + pts[drawingPowerPoint][1] * ah;
                snapGlowPaint.setAlpha(Math.round(220f * snapGlowAlpha));
                snapCorePaint.setAlpha(Math.round(160f * snapGlowAlpha));
                canvas.drawCircle(px, py, 9f * d, snapGlowPaint);
                canvas.drawCircle(px, py, 2.5f * d, snapCorePaint);
            }
        }

        canvas.restore();
    }
}
