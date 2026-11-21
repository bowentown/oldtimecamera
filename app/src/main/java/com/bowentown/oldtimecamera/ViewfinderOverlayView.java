package com.bowentown.oldtimecamera;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
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

    private CompositionMath.Guide3DCue targetCue = CompositionMath.Guide3DCue.EMPTY;
    private CompositionMath.Guide3DCue drawingCue = CompositionMath.Guide3DCue.EMPTY;
    private String drawingStepLabel = "";
    private float moveCueAlpha = 0f;
    private ValueAnimator moveCueAnimator;

    private final Paint movePillFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint movePillStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moveGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moveDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moveTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghostPhonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint phoneSidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint phoneFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint directorBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint directorGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpMoveRect = new RectF();
    private final Path tmpPhonePath = new Path();
    private final float[] ghostFrontPts = new float[8];
    private final float[] curFrontPts = new float[8];
    private final float[] curBackPts = new float[8];

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

        movePillFillPaint.setColor(0xA611100E);
        movePillFillPaint.setStyle(Paint.Style.FILL);

        movePillStrokePaint.setColor(0x88F2A65A);
        movePillStrokePaint.setStyle(Paint.Style.STROKE);
        movePillStrokePaint.setStrokeWidth(0.95f * d);

        moveGlyphPaint.setColor(0xFFF2A65A);
        moveGlyphPaint.setStyle(Paint.Style.STROKE);
        moveGlyphPaint.setStrokeWidth(1.65f * d);
        moveGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
        moveGlyphPaint.setStrokeJoin(Paint.Join.ROUND);

        moveDotPaint.setColor(0xFFF2A65A);
        moveDotPaint.setStyle(Paint.Style.FILL);

        moveTextPaint.setColor(0xFFF0E8D8);
        moveTextPaint.setTypeface(FilmUiHelper.monoFont(context));
        moveTextPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 10f, getResources().getDisplayMetrics()));
        moveTextPaint.setTextAlign(Paint.Align.CENTER);

        ghostPhonePaint.setColor(0x4DF0E8D8);
        ghostPhonePaint.setStyle(Paint.Style.STROKE);
        ghostPhonePaint.setStrokeWidth(1.0f * d);

        phoneSidePaint.setColor(0xDDD25A34);
        phoneSidePaint.setStyle(Paint.Style.STROKE);
        phoneSidePaint.setStrokeWidth(1.25f * d);
        phoneSidePaint.setStrokeJoin(Paint.Join.ROUND);

        phoneFillPaint.setColor(0x2EF2A65A);
        phoneFillPaint.setStyle(Paint.Style.FILL);

        directorBoxPaint.setColor(0xB8F0E8D8);
        directorBoxPaint.setStyle(Paint.Style.STROKE);
        directorBoxPaint.setStrokeWidth(1.6f * d);
        directorBoxPaint.setStrokeCap(Paint.Cap.ROUND);

        directorGlowPaint.setColor(0x4DF2A65A);
        directorGlowPaint.setStyle(Paint.Style.STROKE);
        directorGlowPaint.setStrokeWidth(4.5f * d);
        directorGlowPaint.setStrokeCap(Paint.Cap.ROUND);
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

    /** Updates the live 3D mini-phone guidance pod and optional AI director target frame. */
    public void setGuide3DCue(CompositionMath.Guide3DCue cue, String stepLabel) {
        CompositionMath.Guide3DCue safe = cue != null ? cue : CompositionMath.Guide3DCue.EMPTY;
        String safeLabel = stepLabel != null ? stepLabel : "";
        boolean sameCue = safe.equals(targetCue)
                && safeLabel.equals(drawingStepLabel)
                && Math.abs(safe.intensity - targetCue.intensity) < 0.08f
                && floatEqual(safe.subjectX, targetCue.subjectX, 0.015f)
                && floatEqual(safe.subjectY, targetCue.subjectY, 0.015f)
                && Math.abs(safe.offsetX - targetCue.offsetX) < 0.015f
                && Math.abs(safe.offsetY - targetCue.offsetY) < 0.015f
                && Math.abs(safe.offsetZ - targetCue.offsetZ) < 0.015f
                && Math.abs(safe.offsetPitch - targetCue.offsetPitch) < 0.015f;
        if (sameCue) {
            return;
        }
        this.targetCue = safe;

        if (!safe.isEmpty()) {
            this.drawingCue = safe;
            this.drawingStepLabel = safeLabel;
            if (moveCueAlpha >= 0.99f) {
                invalidate();
                return;
            }
            animateMoveCueAlpha(1f, 150L);
        } else {
            if (moveCueAlpha <= 0.005f) {
                this.drawingCue = CompositionMath.Guide3DCue.EMPTY;
                this.drawingStepLabel = "";
                invalidate();
                return;
            }
            animateMoveCueAlpha(0f, 210L);
        }
    }

    private static boolean floatEqual(float a, float b, float eps) {
        if (Float.isNaN(a) && Float.isNaN(b)) return true;
        if (Float.isNaN(a) || Float.isNaN(b)) return false;
        return Math.abs(a - b) < eps;
    }

    private void animateMoveCueAlpha(float toAlpha, long durationMs) {
        if (moveCueAnimator != null) moveCueAnimator.cancel();
        moveCueAnimator = ValueAnimator.ofFloat(moveCueAlpha, toAlpha);
        moveCueAnimator.setDuration(durationMs);
        moveCueAnimator.setInterpolator(new DecelerateInterpolator());
        moveCueAnimator.addUpdateListener(anim -> {
            moveCueAlpha = (float) anim.getAnimatedValue();
            if (targetCue.isEmpty() && moveCueAlpha <= 0.005f) {
                drawingCue = CompositionMath.Guide3DCue.EMPTY;
                drawingStepLabel = "";
            }
            invalidate();
        });
        moveCueAnimator.start();
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
        if (moveCueAnimator != null) moveCueAnimator.cancel();
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
        drawMoveGuide(canvas, d);

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

    private void drawMoveGuide(Canvas canvas, float d) {
        if (moveCueAlpha <= 0.01f || drawingCue.isEmpty()
                || activeRect.width() < 120f * d || activeRect.height() < 90f * d) {
            return;
        }
        canvas.save();
        canvas.clipRect(activeRect);

        if (drawingCue.directorActive) {
            drawDirectorTargetBox(canvas, d);
        }
        if (drawingCue.step != CompositionMath.Step3D.NONE) {
            draw3DPhonePod(canvas, d);
            if (drawingCue.step != CompositionMath.Step3D.LOCKED) {
                postInvalidateOnAnimation();
            }
        }

        canvas.restore();
    }

    private void drawDirectorTargetBox(Canvas canvas, float d) {
        float bx1 = activeRect.left + drawingCue.targetBoxLeft * activeRect.width();
        float by1 = activeRect.top + drawingCue.targetBoxTop * activeRect.height();
        float bx2 = activeRect.left + drawingCue.targetBoxRight * activeRect.width();
        float by2 = activeRect.top + drawingCue.targetBoxBottom * activeRect.height();
        if (bx2 - bx1 < 18f * d || by2 - by1 < 18f * d) return;

        float bracket = Math.min(13f * d, Math.min(bx2 - bx1, by2 - by1) * 0.28f);
        directorBoxPaint.setColor(drawingCue.locked ? 0xFFF2A65A : 0xCCF0E8D8);
        directorBoxPaint.setAlpha(Math.round((drawingCue.locked ? 245f : 200f) * moveCueAlpha));

        if (drawingCue.locked) {
            directorGlowPaint.setAlpha(Math.round(92f * moveCueAlpha));
            drawCornerBrackets(canvas, bx1, by1, bx2, by2, bracket, directorGlowPaint);
        }
        drawCornerBrackets(canvas, bx1, by1, bx2, by2, bracket, directorBoxPaint);

        float tcx = (bx1 + bx2) * 0.5f;
        float tcy = (by1 + by2) * 0.5f;
        float cross = 4.5f * d;
        canvas.drawLine(tcx - cross, tcy, tcx + cross, tcy, directorBoxPaint);
        canvas.drawLine(tcx, tcy - cross, tcx, tcy + cross, directorBoxPaint);

        if (!Float.isNaN(drawingCue.subjectX) && !Float.isNaN(drawingCue.subjectY)) {
            float sx = activeRect.left + drawingCue.subjectX * activeRect.width();
            float sy = activeRect.top + drawingCue.subjectY * activeRect.height();
            if (!drawingCue.locked) {
                ghostPhonePaint.setColor(0x88F2A65A);
                ghostPhonePaint.setAlpha(Math.round(135f * moveCueAlpha));
                canvas.drawLine(sx, sy, tcx, tcy, ghostPhonePaint);
            }
            moveDotPaint.setAlpha(Math.round(230f * moveCueAlpha));
            canvas.drawCircle(sx, sy, 2.8f * d, moveDotPaint);
        }
    }

    private static void drawCornerBrackets(Canvas canvas, float l, float t, float r, float b,
                                           float len, Paint paint) {
        canvas.drawLine(l, t, l + len, t, paint);
        canvas.drawLine(l, t, l, t + len, paint);
        canvas.drawLine(r - len, t, r, t, paint);
        canvas.drawLine(r, t, r, t + len, paint);
        canvas.drawLine(l, b, l + len, b, paint);
        canvas.drawLine(l, b - len, l, b, paint);
        canvas.drawLine(r - len, b, r, b, paint);
        canvas.drawLine(r, b - len, r, b, paint);
    }

    private void draw3DPhonePod(Canvas canvas, float d) {
        float podW = Math.min(92f * d, Math.max(66f * d, activeRect.width() - 130f * d));
        float podH = activeRect.height() < 150f * d
                ? Math.min(52f * d, activeRect.height() * 0.44f)
                : 62f * d;
        float cx = activeRect.centerX();
        float bottom = activeRect.bottom - 10f * d;
        tmpMoveRect.set(cx - podW * 0.5f, bottom - podH, cx + podW * 0.5f, bottom);

        boolean isLocked = drawingCue.step == CompositionMath.Step3D.LOCKED;
        movePillFillPaint.setAlpha(Math.round(168f * moveCueAlpha));
        movePillStrokePaint.setColor(isLocked ? 0xFFF2A65A : 0x88F2A65A);
        movePillStrokePaint.setStrokeWidth((isLocked ? 1.35f : 0.95f) * d);
        movePillStrokePaint.setAlpha(Math.round((isLocked ? 245f : 155f) * moveCueAlpha));

        float cornerR = 12f * d;
        canvas.drawRoundRect(tmpMoveRect, cornerR, cornerR, movePillFillPaint);
        canvas.drawRoundRect(tmpMoveRect, cornerR, cornerR, movePillStrokePaint);

        boolean hasLabel = drawingStepLabel != null && !drawingStepLabel.isEmpty();
        float labelBand = hasLabel ? 14f * d : 2f * d;
        float stageCY = tmpMoveRect.top + 4f * d + (podH - 4f * d - labelBand) * 0.5f;
        float halfW = 6.0f * d;
        float halfH = 10.0f * d;
        float halfDepth = 1.6f * d;
        float focalLen = 42f * d;

        float breath = (SystemClock.uptimeMillis() % 1400L) / 1400f;
        CompositionMath.Pose3D cur = CompositionMath.poseForLiveCue(drawingCue, breath);

        // Stationary rangefinder target wireframe slot at center; amber 3D phone glides into coincidence.
        if (!isLocked) {
            CompositionMath.projectPhoneCorners(
                    CompositionMath.TARGET_POSE, halfW, halfH, -halfDepth, focalLen, ghostFrontPts);
            ghostPhonePaint.setColor(0x99F0E8D8);
            ghostPhonePaint.setAlpha(Math.round(135f * moveCueAlpha));
            drawQuad(canvas, ghostFrontPts, cx, stageCY, ghostPhonePaint);
            drawEarpieceTick(canvas, ghostFrontPts, cx, stageCY, ghostPhonePaint);
        }

        CompositionMath.projectPhoneCorners(cur, halfW, halfH, -halfDepth, focalLen, curFrontPts);
        CompositionMath.projectPhoneCorners(cur, halfW, halfH, halfDepth, focalLen, curBackPts);

        phoneSidePaint.setAlpha(Math.round(90f * moveCueAlpha));
        drawQuad(canvas, curBackPts, cx, stageCY, phoneSidePaint);

        phoneSidePaint.setAlpha(Math.round(205f * moveCueAlpha));
        for (int i = 0; i < 4; i++) {
            canvas.drawLine(
                    cx + curFrontPts[i * 2], stageCY + curFrontPts[i * 2 + 1],
                    cx + curBackPts[i * 2], stageCY + curBackPts[i * 2 + 1],
                    phoneSidePaint);
        }

        phoneFillPaint.setColor(isLocked ? 0x44F2A65A : 0x2AF2A65A);
        phoneFillPaint.setAlpha(Math.round((isLocked ? 72f : 44f) * moveCueAlpha));
        drawQuad(canvas, curFrontPts, cx, stageCY, phoneFillPaint);

        moveGlyphPaint.setColor(isLocked ? 0xFFF2A65A : 0xFFF0E8D8);
        moveGlyphPaint.setStrokeWidth(1.35f * d);
        moveGlyphPaint.setAlpha(Math.round(240f * moveCueAlpha));
        drawQuad(canvas, curFrontPts, cx, stageCY, moveGlyphPaint);

        moveGlyphPaint.setColor(0xFFF2A65A);
        moveGlyphPaint.setAlpha(Math.round(235f * moveCueAlpha));
        drawEarpieceTick(canvas, curFrontPts, cx, stageCY, moveGlyphPaint);

        drawStepGlyphAccessory(canvas, cx, stageCY, podW, d);

        if (hasLabel) {
            moveTextPaint.setColor(isLocked ? 0xFFF2A65A : 0xFFF0E8D8);
            moveTextPaint.setAlpha(Math.round(240f * moveCueAlpha));
            canvas.drawText(drawingStepLabel, cx, tmpMoveRect.bottom - 4.8f * d, moveTextPaint);
        }
    }

    private static void drawEarpieceTick(Canvas canvas, float[] frontPts, float ox, float oy, Paint paint) {
        float tlx = frontPts[0], tly = frontPts[1];
        float trx = frontPts[2], trY = frontPts[3];
        float brx = frontPts[4], bry = frontPts[5];
        float blx = frontPts[6], bly = frontPts[7];
        float ex1 = ox + tlx + (trx - tlx) * 0.34f + (blx - tlx) * 0.14f;
        float ey1 = oy + tly + (trY - tly) * 0.34f + (bly - tly) * 0.14f;
        float ex2 = ox + tlx + (trx - tlx) * 0.66f + (brx - trx) * 0.14f;
        float ey2 = oy + tly + (trY - tly) * 0.66f + (bry - trY) * 0.14f;
        canvas.drawLine(ex1, ey1, ex2, ey2, paint);
    }

    private void drawQuad(Canvas canvas, float[] pts, float ox, float oy, Paint paint) {
        tmpPhonePath.reset();
        tmpPhonePath.moveTo(ox + pts[0], oy + pts[1]);
        tmpPhonePath.lineTo(ox + pts[2], oy + pts[3]);
        tmpPhonePath.lineTo(ox + pts[4], oy + pts[5]);
        tmpPhonePath.lineTo(ox + pts[6], oy + pts[7]);
        tmpPhonePath.close();
        canvas.drawPath(tmpPhonePath, paint);
    }

    private void drawStepGlyphAccessory(Canvas canvas, float cx, float stageCY, float podW, float d) {
        moveGlyphPaint.setColor(0xFFF2A65A);
        moveGlyphPaint.setStrokeWidth(1.5f * d);
        moveGlyphPaint.setAlpha(Math.round(235f * moveCueAlpha));
        float sideX = cx + podW * 0.30f;
        switch (drawingCue.step) {
            case MOVE_LEFT:
                drawMiniArrow(canvas, cx - podW * 0.31f, stageCY, 180f, 3.6f * d, d);
                break;
            case MOVE_RIGHT:
                drawMiniArrow(canvas, cx + podW * 0.31f, stageCY, 0f, 3.6f * d, d);
                break;
            case MOVE_UP:
                drawMiniArrow(canvas, sideX, stageCY, -90f, 3.6f * d, d);
                break;
            case MOVE_DOWN:
                drawMiniArrow(canvas, sideX, stageCY, 90f, 3.6f * d, d);
                break;
            case STEP_CLOSER:
                drawMiniArrow(canvas, sideX, stageCY, -35f, 3.5f * d, d);
                break;
            case STEP_BACK:
                drawMiniArrow(canvas, sideX, stageCY, 145f, 3.5f * d, d);
                break;
            case TILT_TOP_AWAY:
            case TILT_TOP_TOWARD: {
                boolean away = drawingCue.step == CompositionMath.Step3D.TILT_TOP_AWAY;
                float r = 6.5f * d;
                tmpOval.set(sideX - r, stageCY - r, sideX + r, stageCY + r);
                canvas.drawArc(tmpOval, away ? -115f : -65f, away ? 115f : -115f,
                        false, moveGlyphPaint);
                float tipX = away ? sideX + r : sideX - r;
                float tipY = stageCY;
                float dir = away ? 1f : -1f;
                canvas.drawLine(tipX - 2.3f * d * dir, tipY - 2.2f * d, tipX, tipY, moveGlyphPaint);
                canvas.drawLine(tipX + 1.8f * d * dir, tipY - 2.4f * d, tipX, tipY, moveGlyphPaint);
                break;
            }
            case LOCKED:
                canvas.drawLine(sideX - 3.2f * d, stageCY + 0.3f * d,
                        sideX - 0.8f * d, stageCY + 2.8f * d, moveGlyphPaint);
                canvas.drawLine(sideX - 0.8f * d, stageCY + 2.8f * d,
                        sideX + 3.6f * d, stageCY - 2.6f * d, moveGlyphPaint);
                break;
            default:
                break;
        }
    }

    private void drawMiniArrow(Canvas canvas, float ax, float ay, float angleDeg,
                               float halfLen, float d) {
        canvas.save();
        canvas.rotate(angleDeg, ax, ay);
        float head = 2.6f * d;
        canvas.drawLine(-halfLen, 0f, halfLen, 0f, moveGlyphPaint);
        canvas.drawLine(halfLen - head, -head, halfLen, 0f, moveGlyphPaint);
        canvas.drawLine(halfLen - head, head, halfLen, 0f, moveGlyphPaint);
        canvas.restore();
    }
}
