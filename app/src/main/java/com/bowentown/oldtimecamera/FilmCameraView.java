package com.bowentown.oldtimecamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A GLSurfaceView that owns the camera SurfaceTexture and the film renderer. */
public final class FilmCameraView extends GLSurfaceView {

    public interface OnSurfaceReadyListener {
        void onReady(SurfaceTexture surfaceTexture);
    }

    private final GLFilmRenderer renderer;
    private OnSurfaceReadyListener listener;
    private volatile SurfaceTexture readyTexture;
    private int ratioW = 0, ratioH = 0;

    public FilmCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        renderer = new GLFilmRenderer(this, FilmPresets.all()[0]);
        setRenderer(renderer);
        // Camera frames drive rendering; no need to burn GPU on a fixed loop.
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    public void setOnSurfaceReadyListener(OnSurfaceReadyListener l) {
        listener = l;
        SurfaceTexture st = readyTexture;
        if (l != null && st != null) {
            post(() -> l.onReady(st));
        }
    }

    void notifySurfaceReady(SurfaceTexture st) {
        readyTexture = st;
        OnSurfaceReadyListener l = listener;
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

    public void setCameraTransform(int rotationDegrees, boolean mirrored) {
        renderer.setTransform(rotationDegrees, mirrored);
    }

    /** Fit the viewfinder to the (rotated) camera buffer aspect ratio. */
    public void setAspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) return;
        post(() -> {
            ratioW = width;
            ratioH = height;
            requestLayout();
        });
    }

    /**
     * Apply the very same film shader to a captured JPEG. Executed on the GL
     * thread via a task queue drained at the top of onDrawFrame.
     */
    public Bitmap renderPhoto(Bitmap source, FilmPreset preset, boolean grainBoost) throws Exception {
        final Bitmap[] result = new Bitmap[1];
        final Throwable[] failure = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(1);
        renderer.postTask(() -> {
            try {
                result[0] = renderer.renderPhoto(source, preset, grainBoost);
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
