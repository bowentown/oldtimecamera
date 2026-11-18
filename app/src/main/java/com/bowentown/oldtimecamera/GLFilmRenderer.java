package com.bowentown.oldtimecamera;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Renders the CameraX preview through one film shader, and renders captured
 * JPEGs (and imported darkroom photos) through the *same* shader off-screen
 * so the saved print matches the live viewfinder.
 */
final class GLFilmRenderer implements GLSurfaceView.Renderer,
        SurfaceTexture.OnFrameAvailableListener {

    private static final String PRECISION_HEADER = String.join("\n",
            "#ifdef GL_FRAGMENT_PRECISION_HIGH",
            "precision highp float;",
            "#else",
            "precision mediump float;",
            "#endif"
    );

    private static final String VERTEX_SHADER = String.join("\n",
            "attribute vec4 aPosition;",
            "attribute vec2 aTexCoord;",
            "uniform mat4 uTexMatrix;",
            "varying vec2 vTexCoord;",
            "varying vec2 vScreenUv;",
            "void main() {",
            "    gl_Position = aPosition;",
            "    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;",
            "    vScreenUv = aPosition.xy * 0.5 + 0.5;",
            "}"
    );

    private static final String FILM_UNIFORMS = String.join("\n",
            "uniform mat3 uColorMatrix;",
            "uniform vec3 uColorOffset;",
            "uniform float uSaturation;",
            "uniform float uContrast;",
            "uniform float uBrightness;",
            "uniform float uFade;",
            "uniform float uGrain;",
            "uniform float uVignette;",
            "uniform float uLeak;",
            "uniform float uHalation;",
            "uniform float uGrayscale;",
            "uniform vec3 uLeakColor;",
            "uniform float uLeakSeed;",
            "uniform vec2 uFrameScale;",
            "uniform float uTime;",
            "uniform vec2 uResolution;",
            "uniform float uMist;",
            "uniform vec2 uMistStep;"
    );

    private static final String MIST_BODY = String.join("\n",
            "vec3 applyMist(vec3 src, vec2 uv) {",
            "    if (uMist <= 0.001) return src;",
            "    vec2 s1 = uMistStep;",
            "    vec2 s2 = uMistStep * 2.25;",
            "    vec3 bloom = vec3(0.0);",
            "    bloom += max(vec3(0.0), texture2D(uTexture, uv + vec2( s1.x,  s1.y)).rgb - vec3(0.62));",
            "    bloom += max(vec3(0.0), texture2D(uTexture, uv + vec2(-s1.x,  s1.y)).rgb - vec3(0.62));",
            "    bloom += max(vec3(0.0), texture2D(uTexture, uv + vec2( s1.x, -s1.y)).rgb - vec3(0.62));",
            "    bloom += max(vec3(0.0), texture2D(uTexture, uv + vec2(-s1.x, -s1.y)).rgb - vec3(0.62));",
            "    bloom += max(vec3(0.0), texture2D(uTexture, uv + vec2( s2.x,  0.0)).rgb - vec3(0.66)) * 0.7;",
            "    bloom += max(vec3(0.0), texture2D(uTexture, uv + vec2(-s2.x,  0.0)).rgb - vec3(0.66)) * 0.7;",
            "    bloom += max(vec3(0.0), texture2D(uTexture, uv + vec2( 0.0,  s2.y)).rgb - vec3(0.66)) * 0.7;",
            "    bloom += max(vec3(0.0), texture2D(uTexture, uv + vec2( 0.0, -s2.y)).rgb - vec3(0.66)) * 0.7;",
            "    return clamp(src + bloom * (0.28 * uMist), 0.0, 1.0);",
            "}"
    );

    private static final String FILM_BODY = String.join("\n",
            "float filmHash(vec2 p) {",
            "    vec2 q = mod(p, 512.0);",
            "    return fract(sin(dot(q, vec2(12.9898, 78.233))) * 43758.5453);",
            "}",
            "vec3 filmColor(vec3 src, vec2 screenUv) {",
            "    vec2 frameUv = (screenUv - 0.5) / max(uFrameScale, vec2(0.01)) + 0.5;",
            "    vec2 uv = clamp(frameUv, 0.0, 1.0);",
            "    vec3 c = clamp(uColorMatrix * src + uColorOffset, 0.0, 1.0);",
            "    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));",
            "    c = mix(vec3(lum), c, uSaturation);",
            "    c = clamp((c - 0.5) * uContrast + 0.5 + uBrightness, 0.0, 1.0);",
            "    vec3 sCurve = c * c * (3.0 - 2.0 * c);",
            "    c = mix(c, sCurve, 0.22);",
            "    c = c * (1.0 - uFade * 0.11) + vec3(uFade * 0.052) * (1.0 - c * 0.6);",
            "    if (uGrayscale > 0.5) {",
            "        float g = dot(clamp(c, 0.0, 1.0), vec3(0.299, 0.587, 0.114));",
            "        c = vec3(g) * vec3(1.012, 1.0, 0.980);",
            "    }",
            "    float peak = dot(clamp(c, 0.0, 1.0), vec3(0.299, 0.587, 0.114));",
            "    float hi = smoothstep(0.86, 1.0, peak);",
            "    c += vec3(0.085, 0.022, 0.004) * (hi * hi) * uHalation;",
            "    float leakY = mix(0.24, 0.84, fract(uLeakSeed));",
            "    float leakX = uLeakSeed > 0.5 ? 1.06 : -0.06;",
            "    float d = distance(uv, vec2(leakX, leakY));",
            "    float leakMask = exp(-d * d * 5.2) * uLeak;",
            "    c += uLeakColor * leakMask * 0.52;",
            "    vec2 p = uv - 0.5;",
            "    float aspect = (uResolution.x * uFrameScale.x) / max(uResolution.y * uFrameScale.y, 1.0);",
            "    p.x *= mix(1.0, aspect, 0.45);",
            "    c *= clamp(1.0 - uVignette * dot(p, p) * 1.35, 0.0, 1.0);",
            "    vec2 framePx = uv * (uResolution * uFrameScale);",
            "    float shortEdge = min(uResolution.x * uFrameScale.x, uResolution.y * uFrameScale.y);",
            "    float grainScale = max(shortEdge / 1180.0, 1.0);",
            "    vec2 gx = floor(framePx / grainScale);",
            "    float fine = filmHash(gx + vec2(uTime * 37.0, uTime * 17.0));",
            "    float coarse = filmHash(floor(gx * 0.5) + vec2(uTime * 19.0, uTime * 43.0));",
            "    float grain = (fine * 0.62 + coarse * 0.38) - 0.5;",
            "    float gLum = dot(clamp(c, 0.0, 1.0), vec3(0.2126, 0.7152, 0.0722));",
            "    float midtone = 0.35 + 0.65 * (4.0 * gLum * (1.0 - gLum));",
            "    c += vec3(grain) * (uGrain * midtone);",
            "    return clamp(c, 0.0, 1.0);",
            "}"
    );

    private static final String PREVIEW_FRAGMENT = String.join("\n",
            "#extension GL_OES_EGL_image_external : require",
            PRECISION_HEADER,
            "varying vec2 vTexCoord;",
            "varying vec2 vScreenUv;",
            "uniform samplerExternalOES uTexture;",
            FILM_UNIFORMS,
            MIST_BODY,
            FILM_BODY,
            "void main() {",
            "    vec3 src = texture2D(uTexture, vTexCoord).rgb;",
            "    src = applyMist(src, vTexCoord);",
            "    gl_FragColor = vec4(filmColor(src, vScreenUv), 1.0);",
            "}"
    );

    private static final String METER_VERTEX_SHADER = String.join("\n",
            "attribute vec4 aPosition;",
            "attribute vec2 aTexCoord;",
            "uniform mat4 uTexMatrix;",
            "uniform vec2 uFrameScale;",
            "varying vec2 vTexCoord;",
            "void main() {",
            "    gl_Position = aPosition;",
            "    vec2 activeUv = (aTexCoord - 0.5) * uFrameScale + 0.5;",
            "    vTexCoord = (uTexMatrix * vec4(activeUv, 0.0, 1.0)).xy;",
            "}"
    );

    private static final String METER_FRAGMENT_SHADER = String.join("\n",
            "#extension GL_OES_EGL_image_external : require",
            "precision mediump float;",
            "varying vec2 vTexCoord;",
            "uniform samplerExternalOES uTexture;",
            "void main() {",
            "    gl_FragColor = vec4(texture2D(uTexture, vTexCoord).rgb, 1.0);",
            "}"
    );

    private static final String PHOTO_FRAGMENT = String.join("\n",
            PRECISION_HEADER,
            "varying vec2 vTexCoord;",
            "varying vec2 vScreenUv;",
            "uniform sampler2D uTexture;",
            FILM_UNIFORMS,
            MIST_BODY,
            FILM_BODY,
            "void main() {",
            "    vec3 src = texture2D(uTexture, vTexCoord).rgb;",
            "    src = applyMist(src, vTexCoord);",
            "    gl_FragColor = vec4(filmColor(src, vScreenUv), 1.0);",
            "}"
    );

    private static final float[] QUAD = {
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
    };
    private static final float[] TEX_CAMERA = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
    };
    private static final float[] TEX_PHOTO = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
    };

    private static final class Locs {
        int position, texCoord, texMatrix;
        int texture, colorMatrix, colorOffset, saturation, contrast, brightness;
        int fade, grain, vignette, leak, halation, grayscale, leakColor;
        int leakSeed, frameScale, time, resolution, mist, mistStep;

        static Locs of(int program) {
            Locs l = new Locs();
            l.position = GLES20.glGetAttribLocation(program, "aPosition");
            l.texCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            l.texMatrix = uniform(program, "uTexMatrix");
            l.texture = uniform(program, "uTexture");
            l.colorMatrix = uniform(program, "uColorMatrix");
            l.colorOffset = uniform(program, "uColorOffset");
            l.saturation = uniform(program, "uSaturation");
            l.contrast = uniform(program, "uContrast");
            l.brightness = uniform(program, "uBrightness");
            l.fade = uniform(program, "uFade");
            l.grain = uniform(program, "uGrain");
            l.vignette = uniform(program, "uVignette");
            l.leak = uniform(program, "uLeak");
            l.halation = uniform(program, "uHalation");
            l.grayscale = uniform(program, "uGrayscale");
            l.leakColor = uniform(program, "uLeakColor");
            l.leakSeed = uniform(program, "uLeakSeed");
            l.frameScale = uniform(program, "uFrameScale");
            l.time = uniform(program, "uTime");
            l.resolution = uniform(program, "uResolution");
            l.mist = uniform(program, "uMist");
            l.mistStep = uniform(program, "uMistStep");
            return l;
        }

        private static int uniform(int program, String name) {
            return GLES20.glGetUniformLocation(program, name);
        }
    }

    private final FilmCameraView view;
    private final ConcurrentLinkedQueue<Runnable> glTasks = new ConcurrentLinkedQueue<>();

    private static final int METER_W = 32;
    private static final int METER_H = 24;
    private static final long METER_INTERVAL_MS = 300L;

    private SurfaceTexture surfaceTexture;
    private int oesTextureId;
    private int previewProgram, photoProgram, meterProgram;
    private Locs previewLocs, photoLocs, meterLocs;
    private int meterFboId = 0, meterTexId = 0;
    private ByteBuffer meterReadBuffer;
    private byte[] meterPixelBytes;
    private long lastMeterMs = 0L;
    private FloatBuffer quadBuffer, cameraTexBuffer, photoTexBuffer;

    interface OnAxisSwapListener {
        void onAxisSwapChanged(boolean swapped);
    }

    interface OnPreviewStatsListener {
        void onPreviewStats(PreviewStats stats);
    }

    private static final class TransformConfig {
        final boolean hasCameraTransform;
        final int infoRotationDegrees;
        final boolean infoMirroring;
        final float cropLeftNorm;
        final float cropTopNorm;
        final float cropRightNorm;
        final float cropBottomNorm;
        final int displayRotationDegrees;
        final int manualOffsetDegrees;
        final boolean extraMirrorFlip;

        TransformConfig(
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
            this.hasCameraTransform = hasCameraTransform;
            this.infoRotationDegrees = infoRotationDegrees;
            this.infoMirroring = infoMirroring;
            this.cropLeftNorm = cropLeftNorm;
            this.cropTopNorm = cropTopNorm;
            this.cropRightNorm = cropRightNorm;
            this.cropBottomNorm = cropBottomNorm;
            this.displayRotationDegrees = displayRotationDegrees;
            this.manualOffsetDegrees = manualOffsetDegrees;
            this.extraMirrorFlip = extraMirrorFlip;
        }
    }

    private final float[] stMatrix = new float[16];
    private final float[] texMatrix = new float[16];
    private final float[] identity = new float[16];

    private int viewportW = 1, viewportH = 1;
    private volatile TransformConfig transformConfig = new TransformConfig(
            true, 0, false, 0f, 0f, 1f, 1f, 0, 0, false);
    private volatile OnAxisSwapListener axisSwapListener;
    private volatile OnPreviewStatsListener previewStatsListener;
    private volatile boolean telemetryEnabled = true;
    private Boolean lastReportedAxisSwap = null;
    private volatile FilmPreset preset;
    private volatile boolean grainBoost = false;
    private volatile boolean lightLeakEnabled = true;
    private volatile boolean blackMistEnabled = false;
    private volatile float leakSeed = 0.28f;
    private volatile float frameScaleX = 1f;
    private volatile float frameScaleY = 1f;
    /** Last timestamp used for the live preview, so the print can freeze the same grain pattern. */
    private volatile float previewTimeSec = 0f;
    /** False while the host is paused: renderPhoto must fail fast instead of waiting for a stopped GL loop. */
    private volatile boolean hostResumed = true;
    /** Driver texture limit; prints are capped to it because applyPaper makes them larger still. */
    private int glMaxTexture = 2048;
    /** Non-null when the GL context could not be initialised (shader/driver failure). */
    private volatile String initError = null;

    GLFilmRenderer(FilmCameraView view, FilmPreset initialPreset) {
        this.view = view;
        this.preset = initialPreset;
        Matrix.setIdentityM(identity, 0);
        Matrix.setIdentityM(stMatrix, 0);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0.067f, 0.063f, 0.055f, 1f);

        // An EGL context loss re-enters this method; drop the previous generation first
        // so textures/programs/FBOs and the old SurfaceTexture are not leaked.
        releaseGlObjects();

        try {
            int[] maxTex = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTex, 0);
            if (maxTex[0] >= 1024) {
                glMaxTexture = maxTex[0];
            }

            oesTextureId = createOesTexture();
            surfaceTexture = new SurfaceTexture(oesTextureId);
            surfaceTexture.setOnFrameAvailableListener(this);

            previewProgram = createProgram(VERTEX_SHADER, PREVIEW_FRAGMENT);
            photoProgram = createProgram(VERTEX_SHADER, PHOTO_FRAGMENT);
            meterProgram = createProgram(METER_VERTEX_SHADER, METER_FRAGMENT_SHADER);
            previewLocs = Locs.of(previewProgram);
            photoLocs = Locs.of(photoProgram);
            meterLocs = Locs.of(meterProgram);

            quadBuffer = floatBuffer(QUAD);
            cameraTexBuffer = floatBuffer(TEX_CAMERA);
            photoTexBuffer = floatBuffer(TEX_PHOTO);

            initMeterFbo();
            initError = null;
        } catch (Throwable t) {
            // A shader compile/link failure used to surface as a silent black viewfinder.
            initError = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            releaseGlObjects();
            view.post(() -> view.notifyRendererError(initError));
            return;
        }

        SurfaceTexture ready = surfaceTexture;
        view.post(() -> view.notifySurfaceReady(ready));
    }

    /**
     * Deletes every GL object owned by the renderer and releases the SurfaceTexture.
     * Must run on the GL thread. Safe to call repeatedly.
     */
    void releaseGlObjects() {
        if (surfaceTexture != null) {
            surfaceTexture.setOnFrameAvailableListener(null);
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (oesTextureId != 0) {
            int[] tex = {oesTextureId};
            GLES20.glDeleteTextures(1, tex, 0);
            oesTextureId = 0;
        }
        if (meterFboId != 0) {
            int[] fbo = {meterFboId};
            GLES20.glDeleteFramebuffers(1, fbo, 0);
            meterFboId = 0;
        }
        if (meterTexId != 0) {
            int[] tex = {meterTexId};
            GLES20.glDeleteTextures(1, tex, 0);
            meterTexId = 0;
        }
        int[] programs = {previewProgram, photoProgram, meterProgram};
        for (int p : programs) {
            if (p != 0) GLES20.glDeleteProgram(p);
        }
        previewProgram = 0;
        photoProgram = 0;
        meterProgram = 0;
        previewLocs = null;
        photoLocs = null;
        meterLocs = null;
        lastReportedAxisSwap = null;
    }

    int maxRenderEdge() {
        return Math.min(glMaxTexture, 4096);
    }

    String initError() {
        return initError;
    }

    void setHostResumed(boolean resumed) {
        hostResumed = resumed;
    }

    private void initMeterFbo() {
        meterFboId = 0;
        meterTexId = 0;
        int[] tex = new int[1];
        int[] fbo = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glGenFramebuffers(1, fbo, 0);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, METER_W, METER_H, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tex[0], 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        if (status == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            meterTexId = tex[0];
            meterFboId = fbo[0];
            int bytes = METER_W * METER_H * 4;
            if (meterReadBuffer == null || meterReadBuffer.capacity() < bytes) {
                meterReadBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
                meterPixelBytes = new byte[bytes];
            }
        } else {
            GLES20.glDeleteFramebuffers(1, fbo, 0);
            GLES20.glDeleteTextures(1, tex, 0);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        viewportW = Math.max(1, width);
        viewportH = Math.max(1, height);
        view.requestRender();
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        Runnable task;
        while ((task = glTasks.poll()) != null) {
            task.run();
        }

        if (surfaceTexture == null) return;
        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(stMatrix);
        buildTexMatrix();

        // Draw primary preview immediately first, then run optional tiny 32x24 raw sample pass.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewportW, viewportH);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glUseProgram(previewProgram);
        FilmPreset p = preset != null ? preset : FilmPresets.all()[0];
        previewTimeSec = (System.nanoTime() % 1_000_000_000L) / 1_000_000_000f;
        applyUniformsFor(previewLocs, p, grainBoost, lightLeakEnabled, blackMistEnabled, leakSeed,
                frameScaleX, frameScaleY, viewportW, viewportH, previewTimeSec);
        GLES20.glUniformMatrix4fv(previewLocs.texMatrix, 1, false, texMatrix, 0);
        drawQuad(previewLocs, cameraTexBuffer);

        sampleTelemetryIfDue();
    }

    private void sampleTelemetryIfDue() {
        OnPreviewStatsListener listener = previewStatsListener;
        if (!telemetryEnabled || listener == null || meterFboId == 0 || meterProgram == 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastMeterMs < METER_INTERVAL_MS) {
            return;
        }
        lastMeterMs = now;

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, meterFboId);
        GLES20.glViewport(0, 0, METER_W, METER_H);
        GLES20.glUseProgram(meterProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glUniform1i(meterLocs.texture, 0);
        GLES20.glUniform2f(meterLocs.frameScale, frameScaleX, frameScaleY);
        GLES20.glUniformMatrix4fv(meterLocs.texMatrix, 1, false, texMatrix, 0);
        drawQuad(meterLocs, cameraTexBuffer);

        meterReadBuffer.position(0);
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1);
        GLES20.glReadPixels(0, 0, METER_W, METER_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, meterReadBuffer);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewportW, viewportH);

        meterReadBuffer.position(0);
        meterReadBuffer.get(meterPixelBytes, 0, METER_W * METER_H * 4);
        PreviewStats stats = PreviewStats.fromRgbaBytes(meterPixelBytes, METER_W, METER_H, true);
        view.post(() -> listener.onPreviewStats(stats));
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        view.requestRender();
    }

    // ---------------------------------------------------------------- public API

    void setOnAxisSwapListener(OnAxisSwapListener listener) {
        this.axisSwapListener = listener;
        Boolean reported = lastReportedAxisSwap;
        if (listener != null && reported != null) {
            boolean val = reported;
            view.post(() -> listener.onAxisSwapChanged(val));
        }
    }

    void setOnPreviewStatsListener(OnPreviewStatsListener listener) {
        this.previewStatsListener = listener;
    }

    void setTelemetryEnabled(boolean enabled) {
        this.telemetryEnabled = enabled;
    }

    void setTransform(
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
        this.transformConfig = new TransformConfig(
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
        this.lastReportedAxisSwap = null;
        view.requestRender();
    }

    void setPreset(FilmPreset p) {
        preset = p;
        view.requestRender();
    }

    void setGrainBoost(boolean enabled) {
        grainBoost = enabled;
        view.requestRender();
    }

    void setLightLeakEnabled(boolean enabled) {
        lightLeakEnabled = enabled;
        view.requestRender();
    }

    void setBlackMistEnabled(boolean enabled) {
        blackMistEnabled = enabled;
        view.requestRender();
    }

    void setLeakSeed(float seed) {
        leakSeed = seed;
        view.requestRender();
    }

    void setFrameScale(float scaleX, float scaleY) {
        frameScaleX = Math.max(0.1f, scaleX);
        frameScaleY = Math.max(0.1f, scaleY);
        view.requestRender();
    }

    SurfaceTexture surfaceTexture() {
        return surfaceTexture;
    }

    void postTask(Runnable r) {
        glTasks.add(r);
        view.requestRender();
    }

    /**
     * Runs on the GL thread (via postTask). Reuses the preview shader on the
     * cropped capture or imported bitmap, renders to an FBO and reads pixels back.
     */
    Bitmap renderPhoto(Bitmap source, FilmPreset p, boolean boostGrain,
                       boolean leakEnabled, float seed) {
        return renderPhoto(source, p, boostGrain, leakEnabled, false, seed);
    }

    Bitmap renderPhoto(Bitmap source, FilmPreset p, boolean boostGrain,
                       boolean leakEnabled, boolean mistEnabled, float seed) {
        if (!hostResumed) {
            throw new IllegalStateException("GL preview paused");
        }
        if (photoProgram == 0) {
            throw new IllegalStateException("GL renderer not ready");
        }
        // applyPaper later adds a border on top, so keep the development target inside
        // the driver's texture limit instead of producing an incomplete FBO.
        int maxEdge = maxRenderEdge();
        Bitmap renderSrc = source;
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest > maxEdge) {
            float scale = (float) maxEdge / longest;
            renderSrc = Bitmap.createScaledBitmap(source,
                    Math.max(1, Math.round(source.getWidth() * scale)),
                    Math.max(1, Math.round(source.getHeight() * scale)), true);
        }
        int w = renderSrc.getWidth();
        int h = renderSrc.getHeight();

        int[] textures = new int[2];
        int[] fbo = new int[1];
        GLES20.glGenTextures(2, textures, 0);
        int srcTex = textures[0];
        int dstTex = textures[1];
        GLES20.glGenFramebuffers(1, fbo, 0);

        try {
            // Upload source bitmap to a read-only texture and render into a
            // separate destination texture so the GPU never samples and writes
            // the same storage in one draw call (FBO feedback loop).
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, renderSrc, 0);

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dstTex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, dstTex, 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new RuntimeException("Incomplete framebuffer: 0x" + Integer.toHexString(status));
            }

            GLES20.glViewport(0, 0, w, h);
            GLES20.glUseProgram(photoProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
            applyUniformsFor(photoLocs, p, boostGrain, leakEnabled, mistEnabled, seed, 1f, 1f, w, h,
                    previewTimeSec);
            GLES20.glUniformMatrix4fv(photoLocs.texMatrix, 1, false, identity, 0);
            drawQuad(photoLocs, photoTexBuffer);
            GLES20.glFinish();

            ByteBuffer byteBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            IntBuffer pixels = byteBuf.asIntBuffer();
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            checkGlError("glReadPixels");

            int[] srcPixels = new int[w * h];
            pixels.rewind();
            pixels.get(srcPixels);
            int[] argb = new int[w * h];
            for (int y = 0; y < h; y++) {
                int row = (h - 1 - y) * w;
                for (int x = 0; x < w; x++) {
                    int c = srcPixels[row + x];
                    int r = c & 0xFF;
                    int g = (c >>> 8) & 0xFF;
                    int b = (c >>> 16) & 0xFF;
                    int a = (c >>> 24) & 0xFF;
                    argb[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
        } finally {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glDeleteFramebuffers(1, fbo, 0);
            GLES20.glDeleteTextures(2, textures, 0);
            GLES20.glViewport(0, 0, viewportW, viewportH);
            if (renderSrc != source && !renderSrc.isRecycled()) {
                renderSrc.recycle();
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private static void applyUniformsFor(Locs l, FilmPreset p, boolean boostGrain,
                                         boolean leakEnabled, boolean mistEnabled, float seed,
                                         float frameScaleX, float frameScaleY,
                                         int w, int h, float timeSec) {
        GLES20.glUniform1i(l.texture, 0);
        GLES20.glUniformMatrix3fv(l.colorMatrix, 1, false, p.colorMatrix, 0);
        GLES20.glUniform3f(l.colorOffset, p.colorOffset[0], p.colorOffset[1], p.colorOffset[2]);
        GLES20.glUniform1f(l.saturation, p.saturation);
        GLES20.glUniform1f(l.contrast, p.contrast);
        GLES20.glUniform1f(l.brightness, p.brightness);
        GLES20.glUniform1f(l.fade, p.fade);
        GLES20.glUniform1f(l.grain, p.grain * (boostGrain ? 1.7f : 1f));
        GLES20.glUniform1f(l.vignette, p.vignette);
        GLES20.glUniform1f(l.leak, leakEnabled ? p.leak : 0f);
        GLES20.glUniform1f(l.halation, p.halation);
        GLES20.glUniform1f(l.grayscale, p.grayscale ? 1f : 0f);
        GLES20.glUniform3f(l.leakColor, p.leakColor[0], p.leakColor[1], p.leakColor[2]);
        GLES20.glUniform1f(l.leakSeed, seed);
        GLES20.glUniform2f(l.frameScale, frameScaleX, frameScaleY);
        GLES20.glUniform1f(l.time, timeSec);
        GLES20.glUniform2f(l.resolution, w, h);
        GLES20.glUniform1f(l.mist, mistEnabled ? 1f : 0f);
        float[] mistStep = FilmRenderMath.mistTexelStep(w, h);
        GLES20.glUniform2f(l.mistStep, mistStep[0], mistStep[1]);
    }

    private void buildTexMatrix() {
        TransformConfig cfg = transformConfig;
        float[] computed = CameraTransformMath.computePreviewTexMatrix(
                stMatrix,
                cfg.hasCameraTransform,
                cfg.infoRotationDegrees,
                cfg.infoMirroring,
                cfg.cropLeftNorm,
                cfg.cropTopNorm,
                cfg.cropRightNorm,
                cfg.cropBottomNorm,
                cfg.displayRotationDegrees,
                cfg.manualOffsetDegrees,
                cfg.extraMirrorFlip);
        System.arraycopy(computed, 0, texMatrix, 0, 16);

        boolean swapped = CameraTransformMath.isMatrixAxisSwapped(texMatrix);
        if (lastReportedAxisSwap == null || lastReportedAxisSwap != swapped) {
            lastReportedAxisSwap = swapped;
            OnAxisSwapListener listener = axisSwapListener;
            if (listener != null) {
                view.post(() -> listener.onAxisSwapChanged(swapped));
            }
        }
    }

    private void drawQuad(Locs l, FloatBuffer texCoords) {
        GLES20.glEnableVertexAttribArray(l.position);
        GLES20.glVertexAttribPointer(l.position, 2, GLES20.GL_FLOAT, false, 8, quadBuffer);
        GLES20.glEnableVertexAttribArray(l.texCoord);
        GLES20.glVertexAttribPointer(l.texCoord, 2, GLES20.GL_FLOAT, false, 8, texCoords);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(l.position);
        GLES20.glDisableVertexAttribArray(l.texCoord);
    }

    private static FloatBuffer floatBuffer(float[] values) {
        ByteBuffer bb = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(values);
        fb.position(0);
        return fb;
    }

    private static int createOesTexture() {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        int id = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("createOesTexture");
        return id;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + log + "\n" + source);
        }
        return shader;
    }

    private static int createProgram(String vertex, String fragment) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            throw new RuntimeException("Program link failed: " + log);
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return program;
    }

    @SuppressLint("CheckResult")
    private static void checkGlError(String where) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            throw new RuntimeException(where + ": GL error 0x" + Integer.toHexString(error));
        }
    }
}
