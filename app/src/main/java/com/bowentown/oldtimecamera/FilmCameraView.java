package com.bowentown.oldtimecamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
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
    }

    private final GLFilmRenderer renderer;
    private final GestureDetector tapDetector;
    private final ScaleGestureDetector scaleDetector;

    private OnSurfaceReadyListener surfaceListener;
    private OnGestureListener gestureListener;
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
            public boolean onSingleTapUp(MotionEvent e) {
                if (!pinching && gestureListener != null && getWidth() > 0 && getHeight() > 0) {
                    gestureListener.onTapFocus(e.getX(), e.getY(), getWidth(), getHeight());
                }
                return true;
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

    void notifySurfaceReady(SurfaceTexture st) {
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

    public void setLeakSeed(float seed) {
        renderer.setLeakSeed(seed);
    }

    public void setFrameScale(float scaleX, float scaleY) {
        renderer.setFrameScale(scaleX, scaleY);
    }

    public void setCameraTransform(int rotationDegrees, boolean mirrored) {
        renderer.setTransform(rotationDegrees, mirrored);
    }

    public float getSensorAspectRatio() {
        return ratioH > 0 ? (float) ratioW / (float) ratioH : 0.75f;
    }

    /** Fit the viewfinder to the (rotated) camera buffer aspect ratio. */
    public void setAspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) return;
        post(() -> {
            if (ratioW != width || ratioH != height) {
                ratioW = width;
                ratioH = height;
                requestLayout();
            }
        });
    }

    /**
     * Apply the very same film shader to a captured or imported bitmap.
     * Executed on the GL thread via a task queue drained in onDrawFrame.
     */
    public Bitmap renderPhoto(Bitmap source, FilmPreset preset, boolean grainBoost,
                              boolean leakEnabled, float leakSeed) throws Exception {
        final Bitmap[] result = new Bitmap[1];
        final Throwable[] failure = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(1);
        renderer.postTask(() -> {
            try {
                result[0] = renderer.renderPhoto(source, preset, grainBoost, leakEnabled, leakSeed);
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(15, TimeUnit.SECONDS)) {
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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (ratioW <= 0 || ratioH <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        float ratio = (float) ratioW / (float) ratioH;
        if ((float) width / (float) height > ratio) {
            width = Math.round(height * ratio);
        } else {
            height = Math.round(width / ratio);
        }
        setMeasuredDimension(width, height);
    }
}
