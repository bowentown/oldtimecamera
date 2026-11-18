package com.bowentown.oldtimecamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A GLSurfaceView that owns the camera SurfaceTexture, film renderer, and
 * viewfinder touch gestures (tap-to-focus & pinch-to-zoom).
 */
public final class FilmCameraView extends GLSurfaceView {

    public interface OnSurfaceReadyListener {
        void onReady(SurfaceTexture surfaceTexture);
    }

    public interface OnGestureListener {
        void onTapFocus(float viewX, float viewY, int viewWidth, int viewHeight);
        void onPinchZoom(float scaleFactor);
        default void onDoubleTapFlip() {}
        default void onSwipeFilm(int direction) {}
    }

    public interface OnRendererErrorListener {
        void onRendererError(String message);
    }

    private final GLFilmRenderer renderer;
    private final GestureDetector tapDetector;
    private final ScaleGestureDetector scaleDetector;

    private OnSurfaceReadyListener surfaceListener;
    private OnGestureListener gestureListener;
    private OnRendererErrorListener rendererErrorListener;
    private volatile SurfaceTexture readyTexture;
    private int ratioW = 3, ratioH = 4;
    private boolean pinching = false;

    public FilmCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        renderer = new GLFilmRenderer(this, FilmPresets.all()[0]);
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (!pinching && gestureListener != null && getWidth() > 0 && getHeight() > 0) {
                    gestureListener.onTapFocus(e.getX(), e.getY(), getWidth(), getHeight());
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!pinching && gestureListener != null) {
                    gestureListener.onDoubleTapFlip();
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (pinching || gestureListener == null || e1 == null || e2 == null || getWidth() <= 0) {
                    return false;
                }
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                float minDist = Math.max(80f, getWidth() * 0.14f);
                if (Math.abs(dx) > minDist && Math.abs(dx) > Math.abs(dy) * 1.5f && Math.abs(velocityX) > 300f) {
                    gestureListener.onSwipeFilm(dx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        pinching = true;
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (gestureListener != null) {
                            gestureListener.onPinchZoom(detector.getScaleFactor());
                        }
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        postDelayed(() -> pinching = false, 120);
                    }
                });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = scaleDetector.onTouchEvent(event);
        if (!scaleDetector.isInProgress()) {
            handled |= tapDetector.onTouchEvent(event);
        }
        return handled || super.onTouchEvent(event);
    }

    public void setOnSurfaceReadyListener(OnSurfaceReadyListener l) {
        surfaceListener = l;
        SurfaceTexture st = readyTexture;
        if (l != null && st != null) {
            post(() -> l.onReady(st));
        }
    }

    public void setOnGestureListener(OnGestureListener l) {
        gestureListener = l;
    }

    public void setOnRendererErrorListener(OnRendererErrorListener l) {
        rendererErrorListener = l;
    }

    void notifyRendererError(String message) {
        OnRendererErrorListener l = rendererErrorListener;
        if (l != null) l.onRendererError(message);
    }

    /**
     * Releases the camera SurfaceTexture and every GL object owned by the renderer.
     * Called when the hosting activity is destroyed so repeated sessions do not leak
     * a BufferQueue plus three shader programs each time.
     */
    public void releaseResources() {
        renderer.setHostResumed(false);
        try {
            queueEvent(renderer::releaseGlObjects);
        } catch (RuntimeException ignored) {
            // GL thread already gone; nothing to release.
        }
    }

    @Override
    public void onPause() {
        renderer.setHostResumed(false);
        super.onPause();
    }

    @Override
    public void onResume() {
        renderer.setHostResumed(true);
        super.onResume();
    }

    public void notifySurfaceReady(SurfaceTexture st) {
        readyTexture = st;
        OnSurfaceReadyListener l = surfaceListener;
        if (l != null) l.onReady(st);
    }

    public SurfaceTexture getSurfaceTexture() {
        return readyTexture;
    }

    public void setPreset(FilmPreset preset) {
        renderer.setPreset(preset);
    }

    public void setGrainBoost(boolean enabled) {
        renderer.setGrainBoost(enabled);
    }

    public void setLightLeakEnabled(boolean enabled) {
        renderer.setLightLeakEnabled(enabled);
    }

    public void setBlackMistEnabled(boolean enabled) {
        renderer.setBlackMistEnabled(enabled);
    }

    public void setLeakSeed(float seed) {
        renderer.setLeakSeed(seed);
    }

    public void setFrameScale(float scaleX, float scaleY) {
        renderer.setFrameScale(scaleX, scaleY);
    }

    public void setOnAxisSwapListener(GLFilmRenderer.OnAxisSwapListener listener) {
        renderer.setOnAxisSwapListener(listener);
    }

    public void setOnPreviewStatsListener(GLFilmRenderer.OnPreviewStatsListener listener) {
        renderer.setOnPreviewStatsListener(listener);
    }

    public void setTelemetryEnabled(boolean enabled) {
        renderer.setTelemetryEnabled(enabled);
    }

    public void setCameraTransform(
            boolean hasCameraTransform,
            int infoRotationDegrees,
            boolean infoMirroring,
            float cropLeftNorm,
            float cropTopNorm,
            float cropRightNorm,
            float cropBottomNorm,
            int displayRotationDegrees,
            int manualOffsetDegrees,
            boolean extraMirrorFlip) {
        renderer.setTransform(
                hasCameraTransform,
                infoRotationDegrees,
                infoMirroring,
                cropLeftNorm,
                cropTopNorm,
                cropRightNorm,
                cropBottomNorm,
                displayRotationDegrees,
                manualOffsetDegrees,
                extraMirrorFlip);
    }

    public float getSensorAspectRatio() {
        return ratioH > 0 ? (float) ratioW / (float) ratioH : 0.75f;
    }

    /** Fit the viewfinder to the (rotated) camera buffer aspect ratio. */
    public void setAspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) return;
        Runnable apply = () -> {
            if (ratioW != width || ratioH != height) {
                ratioW = width;
                ratioH = height;
                requestLayout();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run();
        } else {
            post(apply);
        }
    }

    /**
     * Apply the very same film shader to a captured or imported bitmap.
     * Executed on the GL thread via a task queue drained in onDrawFrame.
     */
    public Bitmap renderPhoto(Bitmap source, FilmPreset preset, boolean grainBoost,
                              boolean leakEnabled, float leakSeed) throws Exception {
        return renderPhoto(source, preset, grainBoost, leakEnabled, false, leakSeed);
    }

    /**
     * Apply the very same film shader (including optional Black Mist highlight bloom) to a
     * captured or imported bitmap on the GL thread.
     */
    public Bitmap renderPhoto(Bitmap source, FilmPreset preset, boolean grainBoost,
                              boolean leakEnabled, boolean blackMistEnabled,
                              float leakSeed) throws Exception {
        if (!isAttachedToWindow()) {
            throw new IllegalStateException("Viewfinder is not attached");
        }
        if (renderer.initError() != null) {
            throw new IllegalStateException("GL init failed: " + renderer.initError());
        }
        final Bitmap[] result = new Bitmap[1];
        final Throwable[] failure = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(1);
        renderer.postTask(() -> {
            try {
                result[0] = renderer.renderPhoto(source, preset, grainBoost, leakEnabled, blackMistEnabled, leakSeed);
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(12, TimeUnit.SECONDS)) {
            throw new TimeoutException("GL render thread did not respond");
        }
        if (failure[0] != null) {
            throw new RuntimeException(failure[0]);
        }
        if (result[0] == null) {
            throw new IllegalStateException("Renderer produced no bitmap");
        }
        return result[0];
    }

    /** Captures a lightweight preview snapshot on the GL thread without blocking capture hardware. */
    public Bitmap capturePreviewSnapshot(int maxEdge) {
        if (!isAttachedToWindow() || renderer.initError() != null) {
            return null;
        }
        final Bitmap[] result = new Bitmap[1];
        final CountDownLatch latch = new CountDownLatch(1);
        renderer.postTask(() -> {
            try {
                result[0] = renderer.capturePreviewBitmap(maxEdge);
            } catch (Throwable ignored) {
                result[0] = null;
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result[0];
    }

    /** Driver-safe maximum edge for a developed print, queried once the GL context exists. */
    public int getMaxRenderEdge() {
        return renderer.maxRenderEdge();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (ratioW <= 0 || ratioH <= 0 || width <= 0 || height <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        float ratio = (float) ratioW / (float) ratioH;
        if ((float) width / (float) height > ratio) {
            width = Math.max(1, Math.round(height * ratio));
        } else {
            height = Math.max(1, Math.round(width / ratio));
        }
        setMeasuredDimension(width, height);
    }
}
