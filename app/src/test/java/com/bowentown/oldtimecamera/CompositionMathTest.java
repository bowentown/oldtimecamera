package com.bowentown.oldtimecamera;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CompositionMathTest {

    @Test
    public void goldenSpiral_isContinuousAcrossAllRotations() {
        for (int rot = 0; rot < 4; rot++) {
            CompositionMath.SpiralArc[] arcs = CompositionMath.buildGoldenSpiral(rot, 7);
            assertEquals(7, arcs.length);
            for (int k = 0; k < arcs.length - 1; k++) {
                assertEquals("startX continuity rot=" + rot + " step=" + k,
                        arcs[k].endX, arcs[k + 1].startX, 1e-4f);
                assertEquals("startY continuity rot=" + rot + " step=" + k,
                        arcs[k].endY, arcs[k + 1].startY, 1e-4f);
            }
            float[] eye = CompositionMath.goldenEye(rot);
            assertTrue(eye[0] > 0.1f && eye[0] < 0.9f);
            assertTrue(eye[1] > 0.1f && eye[1] < 0.9f);
        }
    }

    @Test
    public void powerPoints_matchExpectedCountsAndBounds() {
        assertEquals(0, CompositionMath.powerPoints(CompositionMath.GridMode.OFF, 0).length);
        assertEquals(4, CompositionMath.powerPoints(CompositionMath.GridMode.THIRDS, 0).length);
        assertEquals(5, CompositionMath.powerPoints(CompositionMath.GridMode.GOLDEN_SPIRAL, 1).length);
        float[][] cross = CompositionMath.powerPoints(CompositionMath.GridMode.CROSSHAIR, 0);
        assertEquals(1, cross.length);
        assertEquals(0.5f, cross[0][0], 1e-5f);
        assertEquals(0.5f, cross[0][1], 1e-5f);
    }

    @Test
    public void snapState_hysteresisAndCooldownSuppressJitter() {
        float[][] pts = CompositionMath.powerPoints(CompositionMath.GridMode.THIRDS, 0);
        CompositionMath.SnapState snap = new CompositionMath.SnapState();

        // Far away -> no lock, no haptic
        assertFalse(snap.update(0.5f, 0.5f, pts, 1000L, 600L));
        assertEquals(-1, snap.getLockedPointIndex());

        // Enter power point (1/3, 1/3) -> haptic fires once
        assertTrue(snap.update(0.34f, 0.34f, pts, 1500L, 600L));
        assertEquals(0, snap.getLockedPointIndex());

        // Hover inside enter radius -> no repeat buzz
        assertFalse(snap.update(0.35f, 0.35f, pts, 1600L, 600L));

        // Drift past enter (0.055) but inside exit (0.085) -> remains locked
        assertFalse(snap.update(0.333f + 0.065f, 0.333f, pts, 1700L, 600L));
        assertEquals(0, snap.getLockedPointIndex());

        // Drift cleanly past exit -> unlocks
        assertFalse(snap.update(0.333f + 0.11f, 0.333f, pts, 1800L, 600L));
        assertEquals(-1, snap.getLockedPointIndex());

        // Re-enter within 600ms cooldown -> locks visually without rapid re-buzz
        assertFalse(snap.update(0.34f, 0.34f, pts, 1900L, 600L));
        assertEquals(0, snap.getLockedPointIndex());

        // Unlock cleanly, wait past cooldown, re-enter -> haptic fires again
        assertFalse(snap.update(0.5f, 0.5f, pts, 2500L, 600L));
        assertTrue(snap.update(0.34f, 0.34f, pts, 2600L, 600L));
    }

    @Test
    public void mapSensorPointToActiveFrame_handlesBackFrontMirrorAndAspectCrop() {
        // Back camera portrait (relativeRot = 90): (sx=0.2, sy=0.25) -> dx=1-0.25=0.75, dy=0.2
        float[] back = CompositionMath.mapSensorPointToActiveFrame(
                0.2f, 0.25f, 90, false, true, 1f, 1f);
        assertNotNull(back);
        assertEquals(0.75f, back[0], 1e-4f);
        assertEquals(0.2f, back[1], 1e-4f);

        // Standard portrait front camera (relativeRot = 270, facingFront=true):
        // CW 270 maps (sx=0.2, sy=0.25) -> unmirrored (0.25, 0.8), mirrored (0.75, 0.8)
        float[] front270Mirrored = CompositionMath.mapSensorPointToActiveFrame(
                0.2f, 0.25f, 270, true, true, 1f, 1f);
        float[] front270Unmirrored = CompositionMath.mapSensorPointToActiveFrame(
                0.2f, 0.25f, 270, true, false, 1f, 1f);
        assertNotNull(front270Mirrored);
        assertNotNull(front270Unmirrored);
        assertEquals(0.75f, front270Mirrored[0], 1e-4f);
        assertEquals(0.8f, front270Mirrored[1], 1e-4f);
        assertEquals(0.25f, front270Unmirrored[0], 1e-4f);
        assertEquals(0.8f, front270Unmirrored[1], 1e-4f);

        // Front mirrored vs unmirrored flip X symmetrically at 90 deg as well
        float[] frontMirrored = CompositionMath.mapSensorPointToActiveFrame(
                0.2f, 0.25f, 90, true, true, 1f, 1f);
        float[] frontUnmirrored = CompositionMath.mapSensorPointToActiveFrame(
                0.2f, 0.25f, 90, true, false, 1f, 1f);
        assertNotNull(frontMirrored);
        assertNotNull(frontUnmirrored);
        assertEquals(1f - frontMirrored[0], frontUnmirrored[0], 1e-4f);
        assertEquals(frontMirrored[1], frontUnmirrored[1], 1e-4f);

        // Cropped XPAN strips top/bottom outside vertical scaleY=0.35 -> returns null when outside curtain
        float[] outsideCurtain = CompositionMath.mapSensorPointToActiveFrame(
                0.05f, 0.5f, 90, false, true, 1f, 0.35f);
        assertNull(outsideCurtain);
    }

    @Test
    public void gridModeCycleAndFromName_handlesFallback() {
        assertEquals(CompositionMath.GridMode.THIRDS, CompositionMath.GridMode.OFF.next());
        assertEquals(CompositionMath.GridMode.GOLDEN_SPIRAL, CompositionMath.GridMode.THIRDS.next());
        assertEquals(CompositionMath.GridMode.CROSSHAIR, CompositionMath.GridMode.GOLDEN_SPIRAL.next());
        assertEquals(CompositionMath.GridMode.OFF, CompositionMath.GridMode.CROSSHAIR.next());
        assertEquals(CompositionMath.GridMode.OFF, CompositionMath.GridMode.fromName("BOGUS"));
    }

    @Test
    public void mapSensorPointToActiveFrame_isExactInverseOfPreviewTexMatrixAcrossAllConfigs() {
        int[] rotations = {0, 90, 180, 270};
        float cl = 0.125f, ct = 0.05f, cr = 0.875f, cb = 0.90f;
        float fsx = 0.8f, fsy = 0.65f;
        float targetAx = 0.27f, targetAy = 0.71f;

        // Forward GL screen coordinates for (targetAx, targetAy) inside active frame curtains:
        float xScr = (targetAx - 0.5f) * fsx + 0.5f;
        float yScr = (targetAy - 0.5f) * fsy + 0.5f;
        float uScrGl = xScr;
        float vScrGl = 1f - yScr;

        boolean[] bools = {false, true};
        for (boolean hasCamTransform : bools) {
            for (int disp : rotations) {
                for (int infoRot : rotations) {
                    for (boolean infoMirrored : bools) {
                        for (int manual : rotations) {
                            for (boolean extraMirror : bools) {
                                float[] stMatrix = null;
                                if (hasCamTransform) {
                                    int sensorRot = infoMirrored
                                            ? CameraTransformMath.normalizeDegrees(infoRot - disp)
                                            : CameraTransformMath.normalizeDegrees(infoRot + disp);
                                    float[] aGl = CameraTransformMath.rotateCcwAboutCenterMatrix(sensorRot);
                                    if (infoMirrored) {
                                        aGl = CameraTransformMath.multiplyMM(aGl, CameraTransformMath.mirrorXMatrix());
                                    }
                                    stMatrix = CameraTransformMath.multiplyMM(CameraTransformMath.flipYMatrix(), aGl);
                                }

                                float[] texMatrix = CameraTransformMath.computePreviewTexMatrix(
                                        stMatrix,
                                        hasCamTransform,
                                        infoRot,
                                        infoMirrored,
                                        cl, ct, cr, cb,
                                        disp,
                                        manual,
                                        extraMirror);
                                float[] sensorUv = CameraTransformMath.transformUv(texMatrix, uScrGl, vScrGl);

                                float[] recovered = CompositionMath.mapSensorPointToActiveFrame(
                                        sensorUv[0], sensorUv[1],
                                        cl, ct, cr, cb,
                                        infoRot,
                                        infoMirrored,
                                        manual,
                                        extraMirror,
                                        fsx, fsy);
                                assertNotNull("Should recover active point for config", recovered);
                                assertEquals("ax mismatch", targetAx, recovered[0], 1e-4f);
                                assertEquals("ay mismatch", targetAy, recovered[1], 1e-4f);
                            }
                        }
                    }
                }
            }
        }

        // Front unmirrored selfie + manual 90 degree rotation regression: Y must follow flipped screen axis.
        float[] stFront = CameraTransformMath.multiplyMM(
                CameraTransformMath.flipYMatrix(),
                CameraTransformMath.rotateCcwAboutCenterMatrix(270));
        stFront = CameraTransformMath.multiplyMM(stFront,
                CameraTransformMath.rotateCcwAboutCenterMatrix(0));
        // Point outside non-trivial cropRect must return null.
        assertNull(CompositionMath.mapSensorPointToActiveFrame(
                0.05f, 0.50f,
                cl, ct, cr, cb,
                270, true, 90, true,
                1f, 1f));
        // Asymmetric point under front + unmirrored (!frontMirrorEnabled) + manualRotation=90
        float[] unmirroredManual = CompositionMath.mapSensorPointToActiveFrame(
                0.2f, 0.3f,
                0f, 0f, 1f, 1f,
                270, true, 90, true,
                1f, 1f);
        assertNotNull(unmirroredManual);
        // relRot=270 CW: (0.2, 0.3) -> (0.3, 0.8); infoMirror: (0.7, 0.8); manual 90 CW: (0.2, 0.7); extraMirror: (0.8, 0.7)
        assertEquals(0.8f, unmirroredManual[0], 1e-4f);
        assertEquals(0.7f, unmirroredManual[1], 1e-4f);
    }

    @Test
    public void mapSensorBoxToActiveFrame_preservesUnclippedSpanAndClippedState() {
        // Identity transform with XPAN curtain (scaleY = 0.35 => visible vertical band [0.325, 0.675]).
        // Face spans sensor Y [0.25 .. 0.60] -> partially extends above top curtain -> rawHeight = 1.0, clipped = true.
        CompositionMath.ActiveBox box = CompositionMath.mapSensorBoxToActiveFrame(
                0.35f, 0.25f, 0.65f, 0.60f,
                0f, 0f, 1f, 1f,
                0, false, 0, false,
                1f, 0.35f);
        assertNotNull(box);
        assertTrue(box.clipped);
        assertEquals(1.0f, box.rawHeight, 0.02f);

        // Completely above the masked XPAN curtain -> returns null.
        assertNull(CompositionMath.mapSensorBoxToActiveFrame(
                0.35f, 0.05f, 0.65f, 0.20f,
                0f, 0f, 1f, 1f,
                0, false, 0, false,
                1f, 0.35f));
    }

    @Test
    public void moveGuideState_guidesDirectionsAndDepthWithHysteresis() {
        CompositionMath.MoveGuideState state = new CompositionMath.MoveGuideState();

        // 1. Centered, well-proportioned portrait with Grid OFF -> zero hints
        CompositionMath.ActiveBox centeredPortrait =
                new CompositionMath.ActiveBox(0.38f, 0.22f, 0.62f, 0.48f, 0.24f, 0.26f, false);
        CompositionMath.MoveCue okCue = state.evaluate(
                true, centeredPortrait, false, Float.NaN, Float.NaN,
                null, CompositionMath.GridMode.OFF, 0);
        assertTrue(okCue.isEmpty());

        // 2. Face far to the LEFT edge -> camera should pan LEFT
        state.reset();
        CompositionMath.ActiveBox farLeft =
                new CompositionMath.ActiveBox(0.06f, 0.22f, 0.26f, 0.48f, 0.20f, 0.26f, false);
        CompositionMath.MoveCue leftCue = state.evaluate(
                true, farLeft, false, Float.NaN, Float.NaN,
                null, CompositionMath.GridMode.OFF, 0);
        assertEquals(CompositionMath.PanX.LEFT, leftCue.panX);
        assertEquals(CompositionMath.PanY.NONE, leftCue.panY);

        // 3. Hysteresis: moving slightly back inside [0.26 .. 0.31] stays LEFT without jitter
        CompositionMath.ActiveBox edgeBand =
                new CompositionMath.ActiveBox(0.18f, 0.22f, 0.38f, 0.48f, 0.20f, 0.26f, false);
        assertEquals(CompositionMath.PanX.LEFT,
                state.evaluate(true, edgeBand, false, Float.NaN, Float.NaN,
                        null, CompositionMath.GridMode.OFF, 0).panX);

        // 4. Face far to the RIGHT edge -> camera should pan RIGHT
        state.reset();
        CompositionMath.ActiveBox farRight =
                new CompositionMath.ActiveBox(0.74f, 0.22f, 0.94f, 0.48f, 0.20f, 0.26f, false);
        assertEquals(CompositionMath.PanX.RIGHT,
                state.evaluate(true, farRight, false, Float.NaN, Float.NaN,
                        null, CompositionMath.GridMode.OFF, 0).panX);

        // 5. Tight headroom at top -> camera should tilt UP
        state.reset();
        CompositionMath.ActiveBox tightHead =
                new CompositionMath.ActiveBox(0.38f, 0.04f, 0.62f, 0.30f, 0.24f, 0.26f, false);
        assertEquals(CompositionMath.PanY.UP,
                state.evaluate(true, tightHead, false, Float.NaN, Float.NaN,
                        null, CompositionMath.GridMode.OFF, 0).panY);

        // 6. Portrait too close -> BACK depth cue and suppressed noisy vertical tilt
        state.reset();
        CompositionMath.ActiveBox tooClose =
                new CompositionMath.ActiveBox(0.25f, 0.02f, 0.75f, 0.60f, 0.50f, 0.58f, true);
        CompositionMath.MoveCue backCue = state.evaluate(
                true, tooClose, false, Float.NaN, Float.NaN,
                null, CompositionMath.GridMode.OFF, 0);
        assertEquals(CompositionMath.DepthMove.BACK, backCue.depth);
        assertEquals(CompositionMath.PanY.NONE, backCue.panY);

        // 7. Tiny isolated subject (high saliency confidence + compact spread) -> CLOSER depth cue
        state.reset();
        PreviewStats smallSubject = new PreviewStats(
                32, 24, 0.45f, 0.01f, 0.05f, 0.45f, 0.45f,
                0.45f, 0.45f, 0.45f, 0.2f,
                0.5f, 0.5f, 0.55f, null, 0.09f, 0.02f);
        CompositionMath.MoveCue closerCue = state.evaluate(
                false, null, false, Float.NaN, Float.NaN,
                smallSubject, CompositionMath.GridMode.OFF, 0);
        assertEquals(CompositionMath.DepthMove.CLOSER, closerCue.depth);

        // 8. Portrait with THIRDS grid prefers upper power point (y=1/3), never pulling portrait down to y=2/3
        state.reset();
        CompositionMath.ActiveBox lowPortrait =
                new CompositionMath.ActiveBox(0.24f, 0.46f, 0.42f, 0.68f, 0.18f, 0.22f, false);
        CompositionMath.MoveCue thirdsPortrait = state.evaluate(
                true, lowPortrait, false, Float.NaN, Float.NaN,
                null, CompositionMath.GridMode.THIRDS, 0);
        assertEquals(CompositionMath.PanY.DOWN, thirdsPortrait.panY);
    }
}
