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
    public void guideTemplatesAndFramingAdvice_stayBoundedAndTrackHeadroom() {
        assertEquals(0, CompositionMath.buildTemplateShapes(CompositionMath.GuideTemplate.NONE).length);
        CompositionMath.GuideTemplate cur = CompositionMath.GuideTemplate.NONE;
        for (int i = 0; i < CompositionMath.GuideTemplate.values().length; i++) {
            cur = cur.next();
            if (cur != CompositionMath.GuideTemplate.NONE) {
                CompositionMath.GuideShape[] shapes = CompositionMath.buildTemplateShapes(cur);
                assertTrue(shapes.length >= 3);
                for (CompositionMath.GuideShape s : shapes) {
                    assertTrue(s.a >= 0f && s.a <= 1f);
                    assertTrue(s.b >= 0f && s.b <= 1f);
                    assertTrue(s.c >= 0f && s.c <= 1f);
                    assertTrue(s.d >= 0f && s.d <= 1f);
                }
            }
        }
        assertEquals(CompositionMath.GuideTemplate.NONE, cur);

        CompositionMath.FramingAdvice offCenter = CompositionMath.evaluateFraming(
                0.15f, 0.15f, true, 0.04f, CompositionMath.GridMode.THIRDS, 0, -1);
        assertTrue(offCenter.hasSubject);
        assertFalse(offCenter.locked);
        assertTrue(offCenter.showArrow);
        assertTrue(offCenter.headroomTight);
        assertEquals(1f / 3f, offCenter.targetX, 1e-4f);
        assertEquals(1f / 3f, offCenter.targetY, 1e-4f);

        CompositionMath.FramingAdvice onPoint = CompositionMath.evaluateFraming(
                0.34f, 0.34f, true, 0.20f, CompositionMath.GridMode.THIRDS, 0, 0);
        assertTrue(onPoint.locked);
        assertFalse(onPoint.showArrow);
        assertFalse(onPoint.headroomTight);
    }
}
