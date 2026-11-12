package com.bowentown.oldtimecamera;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Renders the CameraX preview through one film shader, and renders captured
 * JPEGs through the *same* shader off-screen so the saved photo matches what
 * the viewfinder showed.
 */
final class GLFilmRenderer implements GLSurfaceView.Renderer,
        SurfaceTexture.OnFrameAvailableListener {

    private static final String VERTEX_SHADER = String.join("\n",
            "attribute vec4 aPosition;",
            "attribute vec2 aTexCoord;",
            "uniform mat4 uTexMatrix;",
            "varying vec2 vTexCoord;",
            "void main() {",
            "    gl_Position = aPosition;",
            "    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;",
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
            "uniform float uTime;",
            "uniform vec2 uResolution;"
    );

    private static final String FILM_BODY = String.join("\n",
            "vec3 filmColor(vec3 src, vec2 uv) {",
            "    vec3 c = clamp(uColorMatrix * src + uColorOffset, 0.0, 1.0);",
            "    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));",
            "    c = mix(vec3(lum), c, uSaturation);",
            "    c = (c - 0.5) * uContrast + 0.5 + uBrightness;",
            // Lifted blacks and compressed whites, the signature of a faded negative.
            "    c = c * (1.0 - uFade * 0.16) + vec3(uFade * 0.085);",
            "    if (uGrayscale > 0.5) {",
            "        float g = dot(clamp(c, 0.0, 1.0), vec3(0.299, 0.587, 0.114));",
            "        c = vec3(g) * vec3(1.015, 1.0, 0.975);",
            "    }",
            // Warm bloom around near-clipped highlights.
            "    float hi = smoothstep(0.72, 1.0, dot(clamp(c, 0.0, 1.0), vec3(0.3333)));",
            "    c += vec3(0.115, 0.030, 0.004) * hi * uHalation;",
            // Light leak creeping in from the left edge of the frame.
            "    float d = distance(uv, vec2(-0.06, 0.82));",
            "    float leakMask = exp(-d * d * 5.0) * uLeak;",
            "    c += uLeakColor * leakMask * 0.62;",
            "    vec2 p = uv - 0.5;",
            "    c *= clamp(1.0 - uVignette * dot(p, p) * 1.75, 0.0, 1.0);",
            "    float n = fract(sin(dot(uv * uResolution + vec2(uTime * 37.0, uTime * 17.0),",
            "            vec2(12.9898, 78.233))) * 43758.5453);",
            "    c += vec3(n - 0.5) * uGrain;",
            "    return clamp(c, 0.0, 1.0);",
            "}"
    );

    private static final String PREVIEW_FRAGMENT = String.join("\n",
            "#extension GL_OES_EGL_image_external : require",
            "precision mediump float;",
            "varying vec2 vTexCoord;",
            "uniform samplerExternalOES uTexture;",
            FILM_UNIFORMS,
            FILM_BODY,
            "void main() {",
            "    vec3 src = texture2D(uTexture, vTexCoord).rgb;",
            "    gl_FragColor = vec4(filmColor(src, vTexCoord), 1.0);",
            "}"
    );

    private static final String PHOTO_FRAGMENT = String.join("\n",
            "precision mediump float;",
            "varying vec2 vTexCoord;",
            "uniform sampler2D uTexture;",
            FILM_UNIFORMS,
            FILM_BODY,
            "void main() {",
            "    vec3 src = texture2D(uTexture, vTexCoord).rgb;",
            "    gl_FragColor = vec4(filmColor(src, vTexCoord), 1.0);",
            "}"
    );

    private static final float[] QUAD = {
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
    };
    /** Camera textures rely on SurfaceTexture.getTransformMatrix for the flip. */
    private static final float[] TEX_CAMERA = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
    };
    /** Bitmap uploads are top-row-first, so v=0 is the top of the picture. */
    private static final float[] TEX_PHOTO = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
    };

    private static final class Locs {
        int position, texCoord, texMatrix;
        int texture, colorMatrix, colorOffset, saturation, contrast, brightness;
        int fade, grain, vignette, leak, halation, grayscale, leakColor, time, resolution;

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
            l.time = uniform(program, "uTime");
            l.resolution = uniform(program, "uResolution");
            return l;
        }

        private static int uniform(int program, String name) {
            return GLES20.glGetUniformLocation(program, name);
        }
    }

    private final FilmCameraView view;
    private final ConcurrentLinkedQueue<Runnable> glTasks = new ConcurrentLinkedQueue<>();

    private SurfaceTexture surfaceTexture;
    private int oesTextureId;
    private int previewProgram, photoProgram;
    private Locs previewLocs, photoLocs;
    private FloatBuffer quadBuffer, cameraTexBuffer, photoTexBuffer;

    private final float[] stMatrix = new float[16];
    private final float[] rotMatrix = new float[16];
    private final float[] texMatrix = new float[16];
    private final float[] identity = new float[16];

    private int viewportW = 1, viewportH = 1;
    private volatile int rotationDegrees = 0;
    private volatile boolean mirror = false;
    private volatile FilmPreset preset;
    private volatile boolean grainBoost = false;

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
        GLES20.glClearColor(0.078f, 0.071f, 0.063f, 1f);

        oesTextureId = createOesTexture();
        surfaceTexture = new SurfaceTexture(oesTextureId);
        surfaceTexture.setOnFrameAvailableListener(this);

        previewProgram = createProgram(VERTEX_SHADER, PREVIEW_FRAGMENT);
        photoProgram = createProgram(VERTEX_SHADER, PHOTO_FRAGMENT);
        previewLocs = Locs.of(previewProgram);
        photoLocs = Locs.of(photoProgram);

        quadBuffer = floatBuffer(QUAD);
        cameraTexBuffer = floatBuffer(TEX_CAMERA);
        photoTexBuffer = floatBuffer(TEX_PHOTO);

        SurfaceTexture ready = surfaceTexture;
        view.post(() -> view.notifySurfaceReady(ready));
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

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewportW, viewportH);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glUseProgram(previewProgram);
        applyUniforms(previewLocs, viewportW, viewportH);
        GLES20.glUniformMatrix4fv(previewLocs.texMatrix, 1, false, texMatrix, 0);
        drawQuad(previewLocs, cameraTexBuffer);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        view.requestRender();
    }

    // ---------------------------------------------------------------- public API

    void setTransform(int degrees, boolean mirrored) {
        rotationDegrees = degrees;
        mirror = mirrored;
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

    SurfaceTexture surfaceTexture() {
        return surfaceTexture;
    }

    void postTask(Runnable r) {
        glTasks.add(r);
        view.requestRender();
    }

    /**
     * Runs on the GL thread (via postTask). Reuses the preview shader on the
     * full-resolution capture, renders to an FBO and reads the pixels back.
     */
    Bitmap renderPhoto(Bitmap source, FilmPreset p, boolean boostGrain) {
        int w = source.getWidth();
        int h = source.getHeight();

        int[] tex = new int[1];
        int[] fbo = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, source, 0);

        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tex[0], 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Incomplete framebuffer: 0x" + Integer.toHexString(status));
        }

        GLES20.glViewport(0, 0, w, h);
        GLES20.glUseProgram(photoProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        applyUniformsFor(photoLocs, p, boostGrain, w, h);
        GLES20.glUniformMatrix4fv(photoLocs.texMatrix, 1, false, identity, 0);
        drawQuad(photoLocs, photoTexBuffer);

        ByteBuffer byteBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        IntBuffer pixels = byteBuf.asIntBuffer();
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        checkGlError("glReadPixels");

        int[] srcPixels = new int[w * h];
        pixels.rewind();
        pixels.get(srcPixels);
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            int row = (h - 1 - y) * w; // glReadPixels is bottom-up
            for (int x = 0; x < w; x++) {
                int c = srcPixels[row + x]; // little-endian: 0xAABBGGRR
                int r = c & 0xFF;
                int g = (c >>> 8) & 0xFF;
                int b = (c >>> 16) & 0xFF;
                int a = (c >>> 24) & 0xFF;
                argb[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(1, fbo, 0);
        GLES20.glDeleteTextures(1, tex, 0);
        GLES20.glViewport(0, 0, viewportW, viewportH);
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
    }

    // ---------------------------------------------------------------- internals

    private void applyUniforms(Locs l, int w, int h) {
        FilmPreset p = preset;
        if (p == null) p = FilmPresets.all()[0];
        applyUniformsFor(l, p, grainBoost, w, h);
    }

    private static void applyUniformsFor(Locs l, FilmPreset p, boolean boostGrain, int w, int h) {
        GLES20.glUniform1i(l.texture, 0);
        GLES20.glUniformMatrix3fv(l.colorMatrix, 1, false, p.colorMatrix, 0);
        GLES20.glUniform3f(l.colorOffset, p.colorOffset[0], p.colorOffset[1], p.colorOffset[2]);
        GLES20.glUniform1f(l.saturation, p.saturation);
        GLES20.glUniform1f(l.contrast, p.contrast);
        GLES20.glUniform1f(l.brightness, p.brightness);
        GLES20.glUniform1f(l.fade, p.fade);
        GLES20.glUniform1f(l.grain, p.grain * (boostGrain ? 1.7f : 1f));
        GLES20.glUniform1f(l.vignette, p.vignette);
        GLES20.glUniform1f(l.leak, p.leak);
        GLES20.glUniform1f(l.halation, p.halation);
        GLES20.glUniform1f(l.grayscale, p.grayscale ? 1f : 0f);
        GLES20.glUniform3f(l.leakColor, p.leakColor[0], p.leakColor[1], p.leakColor[2]);
        GLES20.glUniform1f(l.time, (System.nanoTime() % 1_000_000_000L) / 1_000_000_000f);
        GLES20.glUniform2f(l.resolution, w, h);
    }

    private void buildTexMatrix() {
        Matrix.setIdentityM(rotMatrix, 0);
        Matrix.translateM(rotMatrix, 0, 0.5f, 0.5f, 0f);
        Matrix.rotateM(rotMatrix, 0, (float) rotationDegrees, 0f, 0f, 1f);
        if (mirror) {
            Matrix.scaleM(rotMatrix, 0, -1f, 1f, 1f);
        }
        Matrix.translateM(rotMatrix, 0, -0.5f, -0.5f, 0f);
        Matrix.multiplyMM(texMatrix, 0, rotMatrix, 0, stMatrix, 0);
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
