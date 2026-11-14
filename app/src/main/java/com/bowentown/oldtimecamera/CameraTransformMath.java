package com.bowentown.oldtimecamera;

/**
 * Pure-Java OpenGL ES 2D/4x4 matrix math and orientation resolution for
 * CameraX Preview + ImageCapture alignment.
 *
 * <p>Avoids calling Android framework stubs so every transform path is
 * directly unit-testable on the JVM.
 */
public final class CameraTransformMath {

    private CameraTransformMath() {}

    /** Normalizes any angle in degrees to [0, 360). */
    public static int normalizeDegrees(int degrees) {
        int r = degrees % 360;
        return r < 0 ? r + 360 : r;
    }

    /** Maps {@code Surface.ROTATION_0/90/180/270} (0..3) to counter-clockwise display degrees. */
    public static int surfaceRotationToDegrees(int surfaceRotation) {
        return switch (surfaceRotation) {
            case 1 -> 90;   // Surface.ROTATION_90
            case 2 -> 180;  // Surface.ROTATION_180
            case 3 -> 270;  // Surface.ROTATION_270
            default -> 0;   // Surface.ROTATION_0
        };
    }

    /**
     * Quantizes raw gravity-sensor orientation (0..359 degrees clockwise from natural upright)
     * into {@code Surface.ROTATION_*} with a hysteresis band around the 45-degree diagonals
     * so handheld tilting near thresholds does not oscillate.
     *
     * <p>Mapping follows Android {@link android.view.OrientationEventListener} convention:
     * sensor 90° (phone rotated 90° clockwise, right edge down) corresponds to
     * {@code Surface.ROTATION_270} (3); sensor 270° (left edge down) corresponds to
     * {@code Surface.ROTATION_90} (1).
     */
    public static int quantizeDeviceOrientation(int sensorDegrees, int currentSurfaceRotation) {
        if (sensorDegrees < 0) return currentSurfaceRotation;
        int deg = normalizeDegrees(sensorDegrees);

        // Keep current bucket within ±58° of its ideal center before switching.
        int currentCenter = switch (currentSurfaceRotation) {
            case 1 -> 270;
            case 2 -> 180;
            case 3 -> 90;
            default -> 0;
        };
        int delta = Math.abs(deg - currentCenter);
        if (delta > 180) delta = 360 - delta;
        if (delta <= 58) {
            return currentSurfaceRotation;
        }

        if (deg >= 45 && deg < 135) return 3;   // ROTATION_270
        if (deg >= 135 && deg < 225) return 2;  // ROTATION_180
        if (deg >= 225 && deg < 315) return 1;  // ROTATION_90
        return 0;                               // ROTATION_0
    }

    /** Returns a 4x4 column-major identity matrix. */
    public static float[] identityMatrix() {
        return new float[]{
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
        };
    }

    /** OpenGL Y-flip matrix: maps (u, v) -> (u, 1 - v). */
    public static float[] flipYMatrix() {
        return new float[]{
                1f,  0f, 0f, 0f,
                0f, -1f, 0f, 0f,
                0f,  0f, 1f, 0f,
                0f,  1f, 0f, 1f
        };
    }

    /** Horizontal mirror matrix: maps (u, v) -> (1 - u, v). */
    public static float[] mirrorXMatrix() {
        return new float[]{
                -1f, 0f, 0f, 0f,
                 0f, 1f, 0f, 0f,
                 0f, 0f, 1f, 0f,
                 1f, 0f, 0f, 1f
        };
    }

    /**
     * Exact counter-clockwise rotation matrix around (0.5, 0.5) in GL UV space
     * (origin at bottom-left, +v up). For 90-degree multiples uses exact integer values.
     */
    public static float[] rotateCcwAboutCenterMatrix(int degreesCcw) {
        int d = normalizeDegrees(degreesCcw);
        switch (d) {
            case 0:
                return identityMatrix();
            case 90:
                // (u, v) -> (1 - v, u)
                return new float[]{
                        0f, 1f, 0f, 0f,
                        -1f, 0f, 0f, 0f,
                        0f, 0f, 1f, 0f,
                        1f, 0f, 0f, 1f
                };
            case 180:
                // (u, v) -> (1 - u, 1 - v)
                return new float[]{
                        -1f, 0f, 0f, 0f,
                        0f, -1f, 0f, 0f,
                        0f, 0f, 1f, 0f,
                        1f, 1f, 0f, 1f
                };
            case 270:
                // (u, v) -> (v, 1 - u)
                return new float[]{
                        0f, -1f, 0f, 0f,
                        1f, 0f, 0f, 0f,
                        0f, 0f, 1f, 0f,
                        0f, 1f, 0f, 1f
                };
            default:
                double rad = Math.toRadians(d);
                float c = (float) Math.cos(rad);
                float s = (float) Math.sin(rad);
                float tx = 0.5f - 0.5f * c + 0.5f * s;
                float ty = 0.5f - 0.5f * s - 0.5f * c;
                return new float[]{
                        c, s, 0f, 0f,
                        -s, c, 0f, 0f,
                        0f, 0f, 1f, 0f,
                        tx, ty, 0f, 1f
                };
        }
    }

    /**
     * Sub-rectangle crop mapping inside GL buffer UV space (where topNorm=0 is buffer top).
     */
    public static float[] cropUvMatrix(float leftNorm, float topNorm, float rightNorm, float bottomNorm) {
        float w = Math.max(0.0001f, rightNorm - leftNorm);
        float h = Math.max(0.0001f, bottomNorm - topNorm);
        return new float[]{
                w, 0f, 0f, 0f,
                0f, h, 0f, 0f,
                0f, 0f, 1f, 0f,
                leftNorm, 1f - bottomNorm, 0f, 1f
        };
    }

    /** Column-major 4x4 matrix multiply: {@code result = lhs * rhs}. */
    public static float[] multiplyMM(float[] lhs, float[] rhs) {
        float[] out = new float[16];
        for (int col = 0; col < 4; col++) {
            int colOff = col * 4;
            for (int row = 0; row < 4; row++) {
                out[colOff + row] =
                        lhs[row] * rhs[colOff]
                                + lhs[4 + row] * rhs[colOff + 1]
                                + lhs[8 + row] * rhs[colOff + 2]
                                + lhs[12 + row] * rhs[colOff + 3];
            }
        }
        return out;
    }

    /** Transforms a 2D UV coordinate {@code (u, v)} using column-major 4x4 matrix {@code m}. */
    public static float[] transformUv(float[] m, float u, float v) {
        return new float[]{
                m[0] * u + m[4] * v + m[12],
                m[1] * u + m[5] * v + m[13]
        };
    }

    /**
     * Computes the final 4x4 {@code uTexMatrix} used by {@code GLFilmRenderer}'s vertex shader
     * (where {@code aTexCoord} is standard OpenGL bottom-left origin: (0,0)=BL, (0,1)=TL).
     *
     * <p>Two CameraX paths per {@code SurfaceRequest.TransformationInfo}:
     * <ul>
     *   <li>{@code hasCameraTransform == true}: {@code stMatrix} from
     *       {@code SurfaceTexture.getTransformMatrix()} ALREADY embeds sensor-to-natural rotation,
     *       vertical GL flip, and front-camera selfie mirroring. We ONLY pre-multiply
     *       remaining display-rotation compensation (sign-adjusted for mirroring) and optional
     *       user calibration offset in upright space BEFORE {@code stMatrix}
     *       ({@code stMatrix * M_upright}).</li>
     *   <li>{@code hasCameraTransform == false}: {@code stMatrix} only holds the vertical GL
     *       flip. We apply optional screen mirror, rotation from upright screen into buffer space,
     *       and normalized {@code cropRect} BEFORE {@code stMatrix}.</li>
     * </ul>
     */
    public static float[] computePreviewTexMatrix(
            float[] stMatrix,
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

        float[] baseSt = (stMatrix != null && stMatrix.length >= 16) ? stMatrix : flipYMatrix();
        int manual = normalizeDegrees(manualOffsetDegrees);
        int disp = normalizeDegrees(displayRotationDegrees);
        float cl = Math.max(0f, Math.min(1f, cropLeftNorm));
        float ct = Math.max(0f, Math.min(1f, cropTopNorm));
        float cr = Math.max(0f, Math.min(1f, cropRightNorm));
        float cb = Math.max(0f, Math.min(1f, cropBottomNorm));
        boolean hasCrop = (cr - cl) > 0.0001f && (cb - ct) > 0.0001f
                && (cl > 0.0001f || ct > 0.0001f || cr < 0.9999f || cb < 0.9999f);

        if (hasCameraTransform) {
            // SurfaceTexture.getTransformMatrix already accounts for sensor rotation AND
            // front-camera mirroring in natural device orientation (ROTATION_0), i.e. flipY * A_gl.
            // If CameraX reports a sub-buffer cropRect, inject A_gl^-1 * Crop_buf * A_gl in
            // natural GL UV space before compensating for display rotation and manual overrides.
            float[] m = baseSt;
            if (hasCrop) {
                int sensorRot = infoMirroring
                        ? normalizeDegrees(infoRotationDegrees - disp)
                        : normalizeDegrees(infoRotationDegrees + disp);
                float[] aGl = rotateCcwAboutCenterMatrix(sensorRot);
                if (infoMirroring) {
                    aGl = multiplyMM(aGl, mirrorXMatrix());
                }
                float[] aGlInv = rotateCcwAboutCenterMatrix(-sensorRot);
                if (infoMirroring) {
                    aGlInv = multiplyMM(mirrorXMatrix(), aGlInv);
                }
                float[] cropNatural = multiplyMM(
                        aGlInv,
                        multiplyMM(cropUvMatrix(cl, ct, cr, cb), aGl));
                m = multiplyMM(m, cropNatural);
            }
            int remainingCcw = normalizeDegrees(360 - disp + manual);
            float[] pre = rotateCcwAboutCenterMatrix(remainingCcw);
            if (extraMirrorFlip) {
                pre = multiplyMM(pre, mirrorXMatrix());
            }
            return multiplyMM(m, pre);
        }

        // No camera transform baked into stMatrix: build upright display transform first,
        // then apply screen-space manual calibration and extra mirror toggle in the exact
        // same order as the hasCameraTransform == true path.
        float[] pre = identityMatrix();
        if (hasCrop) {
            pre = multiplyMM(pre, cropUvMatrix(cl, ct, cr, cb));
        }
        pre = multiplyMM(pre, rotateCcwAboutCenterMatrix(infoRotationDegrees));
        if (infoMirroring) {
            pre = multiplyMM(pre, mirrorXMatrix());
        }
        if (manual != 0) {
            pre = multiplyMM(pre, rotateCcwAboutCenterMatrix(manual));
        }
        if (extraMirrorFlip) {
            pre = multiplyMM(pre, mirrorXMatrix());
        }
        return multiplyMM(baseSt, pre);
    }

    /**
     * Checks whether the combined 4x4 texture matrix swaps X and Y buffer axes
     * relative to screen X and Y (so width/height should be swapped for layout aspect).
     */
    public static boolean isMatrixAxisSwapped(float[] texMatrix) {
        if (texMatrix == null || texMatrix.length < 6) return false;
        return Math.abs(texMatrix[0]) < Math.abs(texMatrix[1]);
    }

    /**
     * Resolves clockwise JPEG rotation needed so the captured still print matches
     * the physical orientation at shutter time.
     */
    public static int resolveCaptureRotation(
            int exifDegrees,
            int proxyRotationDegrees,
            int bitmapW,
            int bitmapH,
            int targetCaptureSurfaceRotation,
            int autoSensorRotationDegrees,
            int manualRotationDegrees) {

        int base = exifDegrees != 0 ? exifDegrees : proxyRotationDegrees;
        boolean targetIsPortrait = (targetCaptureSurfaceRotation == 0 || targetCaptureSurfaceRotation == 2);
        // Only fall back to the sensor rotation when the photographer has NOT pinned a
        // manual angle. An explicit 0 degrees is a real instruction ("do not rotate"),
        // so it must also suppress the fallback.
        if (base == 0 && targetIsPortrait && bitmapW > bitmapH
                && manualRotationDegrees < 0 && (normalizeDegrees(autoSensorRotationDegrees) % 180 != 0)) {
            // Defensive fallback if HAL stripped EXIF and ImageInfo rotation on a portrait capture.
            base = autoSensorRotationDegrees;
        }
        int manual = manualRotationDegrees >= 0 ? manualRotationDegrees : 0;
        return normalizeDegrees(base + manual);
    }

    /** Determines whether a captured upright front-camera bitmap should be flipped horizontally. */
    public static boolean shouldMirrorCapture(boolean facingFront, boolean frontMirrorEnabled) {
        return facingFront && frontMirrorEnabled;
    }

    /**
     * Computes viewport scale factors {@code [scaleX, scaleY]} in (0, 1] to mask the
     * sensor viewfinder to {@code targetAspect} without distortion.
     */
    public static float[] computeFrameScale(float sensorAspect, float targetAspect) {
        if (sensorAspect <= 0f || targetAspect <= 0f) return new float[]{1f, 1f};
        float scaleX = targetAspect < sensorAspect ? targetAspect / sensorAspect : 1f;
        float scaleY = targetAspect > sensorAspect ? sensorAspect / targetAspect : 1f;
        return new float[]{scaleX, scaleY};
    }

    /**
     * Maps a TIFF/EXIF Orientation tag (1..8) into {@code [cwDegrees, flipHorizontalBeforeRotate]},
     * where applying {@code scale(-1, 1)} first (when flip=1) followed by clockwise rotation of
     * {@code cwDegrees} produces an upright, unmirrored bitmap matching all 8 EXIF cases.
     */
    public static int[] exifOrientationToTransform(int exifOrientation) {
        switch (exifOrientation) {
            case 2: // ORIENTATION_FLIP_HORIZONTAL
                return new int[]{0, 1};
            case 3: // ORIENTATION_ROTATE_180
                return new int[]{180, 0};
            case 4: // ORIENTATION_FLIP_VERTICAL
                return new int[]{180, 1};
            case 5: // ORIENTATION_TRANSPOSE
                return new int[]{270, 1};
            case 6: // ORIENTATION_ROTATE_90
                return new int[]{90, 0};
            case 7: // ORIENTATION_TRANSVERSE
                return new int[]{90, 1};
            case 8: // ORIENTATION_ROTATE_270
                return new int[]{270, 0};
            case 1: // ORIENTATION_NORMAL
            default:
                return new int[]{0, 0};
        }
    }

    /**
     * Computes effective upright display aspect dimensions from the raw buffer size,
     * normalized CameraX {@code cropRect}, and whether the GL matrix swaps axes.
     */
    public static int[] croppedDisplayDimensions(
            int bufferW,
            int bufferH,
            float cropL,
            float cropT,
            float cropR,
            float cropB,
            boolean axisSwapped) {
        if (bufferW <= 0 || bufferH <= 0) return new int[]{0, 0};
        float cl = Math.max(0f, Math.min(1f, cropL));
        float ct = Math.max(0f, Math.min(1f, cropT));
        float cr = Math.max(0f, Math.min(1f, cropR));
        float cb = Math.max(0f, Math.min(1f, cropB));
        float cw = Math.max(0.0001f, cr - cl);
        float ch = Math.max(0.0001f, cb - ct);
        int w = Math.max(1, Math.round(bufferW * cw));
        int h = Math.max(1, Math.round(bufferH * ch));
        return axisSwapped ? new int[]{h, w} : new int[]{w, h};
    }
}
