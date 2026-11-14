package com.bowentown.oldtimecamera;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Exhaustive JVM unit tests for CameraX texture matrix transforms,
 * display-rotation compensation, cropRect mapping, axis-swap detection,
 * capture rotation fallback, and orientation quantization.
 */
public class CameraTransformMathTest {

    private static final float EPS = 1e-4f;

    /**
     * Builds the synthetic SurfaceTexture matrix produced by Android BufferQueue/GLConsumer
     * when {@code hasCameraTransform == true} for upright natural portrait (ROTATION_0).
     */
    private static float[] syntheticHwSurfaceTextureMatrix(int cwBufferToUprightDeg, boolean frontMirror) {
        float[] uprightToBuf = CameraTransformMath.rotateCcwAboutCenterMatrix(cwBufferToUprightDeg);
        if (frontMirror) {
            uprightToBuf = CameraTransformMath.multiplyMM(uprightToBuf, CameraTransformMath.mirrorXMatrix());
        }
        return CameraTransformMath.multiplyMM(CameraTransformMath.flipYMatrix(), uprightToBuf);
    }

    private static void assertUvEquals(float expectedU, float expectedV, float[] actual) {
        assertEquals(expectedU, actual[0], EPS);
        assertEquals(expectedV, actual[1], EPS);
    }

    @Test
    public void backPortrait_hasCameraTransform_trueVsFalseMatchIdentically() {
        float[] hwSt = syntheticHwSurfaceTextureMatrix(90, false);

        float[] mTrue = CameraTransformMath.computePreviewTexMatrix(
                hwSt, true, 90, false, 0f, 0f, 1f, 1f, 0, 0, false);
        float[] mFalse = CameraTransformMath.computePreviewTexMatrix(
                CameraTransformMath.flipYMatrix(), false, 90, false, 0f, 0f, 1f, 1f, 0, 0, false);

        assertArrayEquals(mTrue, mFalse, EPS);
        assertTrue(CameraTransformMath.isMatrixAxisSwapped(mTrue));

        // Verify all 4 quad corners map upright after rot90 -> flipY:
        // TL (0,1)->rot90(0,0)->flipY(0,1), TR (1,1)->rot90(0,1)->flipY(0,0),
        // BR (1,0)->rot90(1,1)->flipY(1,0), BL (0,0)->rot90(1,0)->flipY(1,1).
        assertUvEquals(0f, 1f, CameraTransformMath.transformUv(mTrue, 0f, 1f));
        assertUvEquals(0f, 0f, CameraTransformMath.transformUv(mTrue, 1f, 1f));
        assertUvEquals(1f, 0f, CameraTransformMath.transformUv(mTrue, 1f, 0f));
        assertUvEquals(1f, 1f, CameraTransformMath.transformUv(mTrue, 0f, 0f));
    }

    @Test
    public void frontPortrait_hasCameraTransform_trueDoesNotDoubleFlipOrInvertY() {
        // Reproduces the v1.1.1 bug scenario: front camera sensorOrientation=270, info.getMirroring()=true.
        // In v1.1.1, applying rotMatrix(270, mirror=true) * stFront inverted the vertical Y axis.
        float[] stFront = syntheticHwSurfaceTextureMatrix(270, true);

        float[] mTrue = CameraTransformMath.computePreviewTexMatrix(
                stFront, true, 270, true, 0f, 0f, 1f, 1f, 0, 0, false);
        float[] mFalse = CameraTransformMath.computePreviewTexMatrix(
                CameraTransformMath.flipYMatrix(), false, 270, true, 0f, 0f, 1f, 1f, 0, 0, false);

        assertArrayEquals(mTrue, mFalse, EPS);
        assertTrue(CameraTransformMath.isMatrixAxisSwapped(mTrue));

        // Expected mirrored OES mapping (computed directly from upright->mirror->rot270->flipY):
        // Screen TL (0,1) -> mirror (1,1) -> rot270 (1,0) -> flipY (1,1)
        // Screen BL (0,0) -> mirror (1,0) -> rot270 (0,0) -> flipY (0,1)
        // Screen TR (1,1) -> mirror (0,1) -> rot270 (1,1) -> flipY (1,0)
        // Screen BR (1,0) -> mirror (0,0) -> rot270 (0,1) -> flipY (0,0)
        assertUvEquals(1f, 1f, CameraTransformMath.transformUv(mTrue, 0f, 1f));
        assertUvEquals(0f, 1f, CameraTransformMath.transformUv(mTrue, 0f, 0f));
        assertUvEquals(1f, 0f, CameraTransformMath.transformUv(mTrue, 1f, 1f));
        assertUvEquals(0f, 0f, CameraTransformMath.transformUv(mTrue, 1f, 0f));
    }

    @Test
    public void frontPortrait_extraMirrorFlip_unmirrorsHorizontallyWhileKeepingUpright() {
        float[] stFront = syntheticHwSurfaceTextureMatrix(270, true);

        float[] unmirroredTrue = CameraTransformMath.computePreviewTexMatrix(
                stFront, true, 270, true, 0f, 0f, 1f, 1f, 0, 0, true);
        float[] unmirroredFalse = CameraTransformMath.computePreviewTexMatrix(
                CameraTransformMath.flipYMatrix(), false, 270, true, 0f, 0f, 1f, 1f, 0, 0, true);

        assertArrayEquals(unmirroredTrue, unmirroredFalse, EPS);
        // With selfie mirror disabled, TL maps to old TR (1,0) and TR maps to old TL (1,1):
        // horizontal left/right swapped, vertical top/bottom unchanged (NOT upside-down).
        assertUvEquals(1f, 0f, CameraTransformMath.transformUv(unmirroredTrue, 0f, 1f));
        assertUvEquals(1f, 1f, CameraTransformMath.transformUv(unmirroredTrue, 1f, 1f));
        assertUvEquals(0f, 0f, CameraTransformMath.transformUv(unmirroredTrue, 0f, 0f));
        assertUvEquals(0f, 1f, CameraTransformMath.transformUv(unmirroredTrue, 1f, 0f));
    }

    @Test
    public void nonZeroDisplayRotations_matchAcrossHasCameraTransformTrueAndFalse() {
        // Test all 4 display rotations (0, 90, 180, 270) for both back (sensor=90) and front (sensor=270).
        int[] displayRotDegs = {0, 90, 180, 270};
        for (int disp : displayRotDegs) {
            // Back camera: infoRotationDegrees = (sensor=90 - disp + 360) % 360
            int backInfoRot = CameraTransformMath.normalizeDegrees(90 - disp);
            float[] hwBack = syntheticHwSurfaceTextureMatrix(90, false);
            float[] backT = CameraTransformMath.computePreviewTexMatrix(
                    hwBack, true, backInfoRot, false, 0f, 0f, 1f, 1f, disp, 0, false);
            float[] backF = CameraTransformMath.computePreviewTexMatrix(
                    CameraTransformMath.flipYMatrix(), false, backInfoRot, false, 0f, 0f, 1f, 1f, disp, 0, false);
            assertArrayEquals("Back mismatch at disp=" + disp, backT, backF, EPS);

            // Front camera: infoRotationDegrees = (sensor=270 + disp) % 360
            int frontInfoRot = CameraTransformMath.normalizeDegrees(270 + disp);
            float[] hwFront = syntheticHwSurfaceTextureMatrix(270, true);
            float[] frontT = CameraTransformMath.computePreviewTexMatrix(
                    hwFront, true, frontInfoRot, true, 0f, 0f, 1f, 1f, disp, 0, false);
            float[] frontF = CameraTransformMath.computePreviewTexMatrix(
                    CameraTransformMath.flipYMatrix(), false, frontInfoRot, true, 0f, 0f, 1f, 1f, disp, 0, false);
            assertArrayEquals("Front mismatch at disp=" + disp, frontT, frontF, EPS);
        }
    }

    @Test
    public void cropRect_restrictsSampleRegionInsideNormalizedBufferBounds() {
        // No rotation (infoRot=0), 10% inset horizontally on buffer, vertical untouched.
        float[] m = CameraTransformMath.computePreviewTexMatrix(
                CameraTransformMath.flipYMatrix(),
                false,
                0,
                false,
                0.1f, 0.2f, 0.9f, 0.8f,
                0, 0, false);

        assertFalse(CameraTransformMath.isMatrixAxisSwapped(m));
        // Screen TL (0,1) -> buffer (left=0.1, top=0.2 in image -> OES v=0.2)
        assertUvEquals(0.1f, 0.2f, CameraTransformMath.transformUv(m, 0f, 1f));
        // Screen BR (1,0) -> buffer (right=0.9, bottom=0.8 in image -> OES v=0.8)
        assertUvEquals(0.9f, 0.8f, CameraTransformMath.transformUv(m, 1f, 0f));
    }

    @Test
    public void manualOffsetAndAxisSwapDetection_behavePredictably() {
        float[] rot0 = CameraTransformMath.rotateCcwAboutCenterMatrix(0);
        float[] rot90 = CameraTransformMath.rotateCcwAboutCenterMatrix(90);
        float[] rot180 = CameraTransformMath.rotateCcwAboutCenterMatrix(180);
        float[] rot270 = CameraTransformMath.rotateCcwAboutCenterMatrix(270);

        assertFalse(CameraTransformMath.isMatrixAxisSwapped(rot0));
        assertTrue(CameraTransformMath.isMatrixAxisSwapped(rot90));
        assertFalse(CameraTransformMath.isMatrixAxisSwapped(rot180));
        assertTrue(CameraTransformMath.isMatrixAxisSwapped(rot270));
        assertFalse(CameraTransformMath.isMatrixAxisSwapped(null));
        assertFalse(CameraTransformMath.isMatrixAxisSwapped(new float[3]));

        float[] hwBack = syntheticHwSurfaceTextureMatrix(90, false);
        float[] rotatedExtra90 = CameraTransformMath.computePreviewTexMatrix(
                hwBack, true, 90, false, 0f, 0f, 1f, 1f, 0, 90, false);
        // Rotating a 90deg swapped matrix by another 90deg produces a 0/180 unswapped axis orientation.
        assertFalse(CameraTransformMath.isMatrixAxisSwapped(rotatedExtra90));
    }

    @Test
    public void resolveCaptureRotation_respectsPortraitFallbackAndLandscapeTarget() {
        // Normal portrait EXIF=90
        assertEquals(90, CameraTransformMath.resolveCaptureRotation(90, 90, 4000, 3000, 0, 90, -1));
        // Front camera portrait EXIF=270
        assertEquals(270, CameraTransformMath.resolveCaptureRotation(270, 270, 4000, 3000, 0, 270, -1));
        // Stripped EXIF on portrait target with unrotated W>H JPEG -> uses autoDegrees=90 fallback
        assertEquals(90, CameraTransformMath.resolveCaptureRotation(0, 0, 4000, 3000, 0, 90, -1));
        // Landscape target (ROTATION_270=3, ROTATION_90=1) already upright W>H with exif=0 -> NO bogus 90deg fallback
        assertEquals(0, CameraTransformMath.resolveCaptureRotation(0, 0, 4000, 3000, 3, 90, -1));
        assertEquals(0, CameraTransformMath.resolveCaptureRotation(0, 0, 4000, 3000, 1, 90, -1));
        // Manual override adds cleanly mod 360
        assertEquals(180, CameraTransformMath.resolveCaptureRotation(90, 90, 4000, 3000, 0, 90, 90));
        assertEquals(90, CameraTransformMath.resolveCaptureRotation(180, 180, 4000, 3000, 0, 90, 270));
        // An explicit manual 0 is a real instruction: it suppresses the stripped-EXIF
        // fallback instead of silently becoming "auto".
        assertEquals(0, CameraTransformMath.resolveCaptureRotation(0, 0, 4000, 3000, 0, 90, 0));
        assertEquals(90, CameraTransformMath.resolveCaptureRotation(90, 90, 4000, 3000, 0, 90, 0));
    }

    @Test
    public void quantizeDeviceOrientation_appliesHysteresisAndMapsCardinals() {
        // Unknown (-1) keeps current
        assertEquals(0, CameraTransformMath.quantizeDeviceOrientation(-1, 0));
        assertEquals(3, CameraTransformMath.quantizeDeviceOrientation(-1, 3));

        // Near diagonal (45 deg from portrait 0) stays in ROTATION_0 due to +-58 deg hysteresis
        assertEquals(0, CameraTransformMath.quantizeDeviceOrientation(45, 0));
        assertEquals(0, CameraTransformMath.quantizeDeviceOrientation(310, 0));

        // Past hysteresis switches to ROTATION_270 (3) for 90 deg CW (right edge down)
        assertEquals(3, CameraTransformMath.quantizeDeviceOrientation(85, 0));
        // Upside-down -> ROTATION_180 (2)
        assertEquals(2, CameraTransformMath.quantizeDeviceOrientation(180, 3));
        // 270 deg (left edge down) -> ROTATION_90 (1)
        assertEquals(1, CameraTransformMath.quantizeDeviceOrientation(275, 0));

        assertEquals(0, CameraTransformMath.surfaceRotationToDegrees(0));
        assertEquals(90, CameraTransformMath.surfaceRotationToDegrees(1));
        assertEquals(180, CameraTransformMath.surfaceRotationToDegrees(2));
        assertEquals(270, CameraTransformMath.surfaceRotationToDegrees(3));
    }

    @Test
    public void frameScaleAndMirrorHelpers_coverBoundaries() {
        float[] same = CameraTransformMath.computeFrameScale(0.75f, 0.75f);
        assertEquals(1f, same[0], EPS);
        assertEquals(1f, same[1], EPS);

        float[] xpanOn34 = CameraTransformMath.computeFrameScale(3f / 4f, 65f / 24f);
        assertEquals(1f, xpanOn34[0], EPS);
        assertEquals((3f / 4f) / (65f / 24f), xpanOn34[1], EPS);

        float[] invalid = CameraTransformMath.computeFrameScale(0f, 1f);
        assertEquals(1f, invalid[0], EPS);
        assertEquals(1f, invalid[1], EPS);

        assertTrue(CameraTransformMath.shouldMirrorCapture(true, true));
        assertFalse(CameraTransformMath.shouldMirrorCapture(true, false));
        assertFalse(CameraTransformMath.shouldMirrorCapture(false, true));
    }

    @Test
    public void hasCameraTransform_trueVsFalse_matchAcrossAllCropDispManualAndMirrorCombos() {
        int[] rotDegs = {0, 90, 180, 270};
        boolean[] bools = {false, true};
        float[][] crops = {
                {0f, 0f, 1f, 1f},
                {0.1f, 0.15f, 0.9f, 0.85f}
        };

        for (boolean isFront : bools) {
            int sensorDeg = isFront ? 270 : 90;
            float[] hwSt = syntheticHwSurfaceTextureMatrix(sensorDeg, isFront);
            for (int disp : rotDegs) {
                int infoRot = isFront
                        ? CameraTransformMath.normalizeDegrees(sensorDeg + disp)
                        : CameraTransformMath.normalizeDegrees(sensorDeg - disp);
                for (int manual : rotDegs) {
                    for (boolean extraMirror : bools) {
                        for (float[] c : crops) {
                            float[] mTrue = CameraTransformMath.computePreviewTexMatrix(
                                    hwSt, true, infoRot, isFront,
                                    c[0], c[1], c[2], c[3], disp, manual, extraMirror);
                            float[] mFalse = CameraTransformMath.computePreviewTexMatrix(
                                    CameraTransformMath.flipYMatrix(), false, infoRot, isFront,
                                    c[0], c[1], c[2], c[3], disp, manual, extraMirror);
                            String msg = "Mismatch front=" + isFront + " disp=" + disp
                                    + " manual=" + manual + " mirror=" + extraMirror
                                    + " crop=" + c[0];
                            assertArrayEquals(msg, mFalse, mTrue, EPS);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void hasCameraTransform_trueWithCropRect_restrictsSampleRegion() {
        // Portrait back camera (sensor=90, disp=0), cropped to [0.1..0.9] x [0.2..0.8] of raw buffer.
        float[] hwBack = syntheticHwSurfaceTextureMatrix(90, false);
        float[] mCropped = CameraTransformMath.computePreviewTexMatrix(
                hwBack, true, 90, false,
                0.1f, 0.2f, 0.9f, 0.8f,
                0, 0, false);
        // Raw buffer after 90deg CW: buffer TL=(0.1,0.2) is screen TR=(1,1);
        // buffer BL=(0.1,0.8) is screen TL=(0,1); buffer TR=(0.9,0.2) is screen BR=(1,0).
        assertUvEquals(0.1f, 0.8f, CameraTransformMath.transformUv(mCropped, 0f, 1f));
        assertUvEquals(0.1f, 0.2f, CameraTransformMath.transformUv(mCropped, 1f, 1f));
        assertUvEquals(0.9f, 0.2f, CameraTransformMath.transformUv(mCropped, 1f, 0f));
        assertUvEquals(0.9f, 0.8f, CameraTransformMath.transformUv(mCropped, 0f, 0f));
    }

    private static float[] applyExifPoint(float x, float y, int cwRot, int flipH) {
        float u = flipH != 0 ? (1f - x) : x;
        float v = y;
        switch (CameraTransformMath.normalizeDegrees(cwRot)) {
            case 90:
                return new float[]{1f - v, u};
            case 180:
                return new float[]{1f - u, 1f - v};
            case 270:
                return new float[]{v, 1f - u};
            default:
                return new float[]{u, v};
        }
    }

    @Test
    public void exifOrientationToTransform_matchesAll8TiffSpecifications() {
        // Check each EXIF orientation tag 1..8 against exact image-space point transforms.
        // P = (x=0.2, y=0.1) in raw pixels (top-down y):
        float x = 0.2f;
        float y = 0.1f;

        // 1: NORMAL -> (x, y)
        int[] o1 = CameraTransformMath.exifOrientationToTransform(1);
        assertArrayEquals(new int[]{0, 0}, o1);
        assertUvEquals(x, y, applyExifPoint(x, y, o1[0], o1[1]));

        // 2: FLIP_HORIZONTAL -> (1 - x, y)
        int[] o2 = CameraTransformMath.exifOrientationToTransform(2);
        assertUvEquals(1f - x, y, applyExifPoint(x, y, o2[0], o2[1]));

        // 3: ROTATE_180 -> (1 - x, 1 - y)
        int[] o3 = CameraTransformMath.exifOrientationToTransform(3);
        assertUvEquals(1f - x, 1f - y, applyExifPoint(x, y, o3[0], o3[1]));

        // 4: FLIP_VERTICAL -> (x, 1 - y)
        int[] o4 = CameraTransformMath.exifOrientationToTransform(4);
        assertUvEquals(x, 1f - y, applyExifPoint(x, y, o4[0], o4[1]));

        // 5: TRANSPOSE -> (y, x)
        int[] o5 = CameraTransformMath.exifOrientationToTransform(5);
        assertUvEquals(y, x, applyExifPoint(x, y, o5[0], o5[1]));

        // 6: ROTATE_90 -> (1 - y, x)
        int[] o6 = CameraTransformMath.exifOrientationToTransform(6);
        assertUvEquals(1f - y, x, applyExifPoint(x, y, o6[0], o6[1]));

        // 7: TRANSVERSE -> (1 - y, 1 - x)
        int[] o7 = CameraTransformMath.exifOrientationToTransform(7);
        assertUvEquals(1f - y, 1f - x, applyExifPoint(x, y, o7[0], o7[1]));

        // 8: ROTATE_270 -> (y, 1 - x)
        int[] o8 = CameraTransformMath.exifOrientationToTransform(8);
        assertUvEquals(y, 1f - x, applyExifPoint(x, y, o8[0], o8[1]));

        // Fallback on 0 / undefined
        assertArrayEquals(new int[]{0, 0}, CameraTransformMath.exifOrientationToTransform(0));
    }

    @Test
    public void croppedDisplayDimensions_computesExactAspectForUncroppedAndCroppedBuffers() {
        assertArrayEquals(new int[]{4000, 3000},
                CameraTransformMath.croppedDisplayDimensions(4000, 3000, 0f, 0f, 1f, 1f, false));
        assertArrayEquals(new int[]{3000, 4000},
                CameraTransformMath.croppedDisplayDimensions(4000, 3000, 0f, 0f, 1f, 1f, true));
        assertArrayEquals(new int[]{1800, 3200},
                CameraTransformMath.croppedDisplayDimensions(4000, 3000, 0.1f, 0.2f, 0.9f, 0.8f, true));
        assertArrayEquals(new int[]{0, 0},
                CameraTransformMath.croppedDisplayDimensions(0, 3000, 0f, 0f, 1f, 1f, false));
    }
}
