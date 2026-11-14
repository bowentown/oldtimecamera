package com.bowentown.oldtimecamera;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SceneClassifierTest {

    private static PreviewStats rgbStats(float luma, float r, float g, float b, float sat,
                                         float centerL, float borderL) {
        return new PreviewStats(32, 24, luma, 0f, 0f, centerL, borderL,
                r, g, b, sat, 0.5f, 0.5f, 0.4f);
    }

    @Test
    public void mapsScenesToExpectedVintageStocksAfterTemporalStability() {
        // NIGHT -> tungsten night stock
        SceneClassifier clf = new SceneClassifier();
        PreviewStats night = rgbStats(0.16f, 0.14f, 0.16f, 0.20f, 0.22f, 0.16f, 0.16f);
        assertNull(clf.evaluate(night, false, "warm400"));
        assertNull(clf.evaluate(night, false, "warm400"));
        SceneClassifier.Recommendation rec = clf.evaluate(night, false, "warm400");
        assertNotNull(rec);
        assertEquals(SceneClassifier.Scene.NIGHT, rec.scene);
        assertEquals("tungsten800", rec.presetId);
        assertEquals("钨丝 800", rec.shortLabel);

        // Already using suggested preset -> returns null (never redundant)
        assertNull(clf.evaluate(night, false, "tungsten800"));

        // Dismiss -> stays suppressed even on continued night frames
        clf.dismiss(SceneClassifier.Scene.NIGHT);
        assertNull(clf.evaluate(night, false, "warm400"));

        // Null stats must not corrupt streak or active scene
        assertNull(clf.evaluate(null, false, "warm400"));
        assertEquals(SceneClassifier.Scene.NIGHT, clf.getActiveScene());

        // Switching to another scene (GOLDEN_HOUR) and returning to NIGHT preserves NIGHT dismissal
        PreviewStats golden = rgbStats(0.52f, 0.65f, 0.50f, 0.34f, 0.42f, 0.52f, 0.52f);
        assertNull(clf.evaluate(golden, false, "warm400"));
        assertNull(clf.evaluate(golden, false, "warm400"));
        SceneClassifier.Recommendation goldRec = clf.evaluate(golden, false, "warm400");
        assertNotNull(goldRec);
        assertEquals(SceneClassifier.Scene.GOLDEN_HOUR, goldRec.scene);
        // Switching back to NIGHT holds GOLDEN_HOUR through 2 hysteresis frames, then re-activates NIGHT (still dismissed)
        assertNotNull(clf.evaluate(night, false, "warm400"));
        assertNotNull(clf.evaluate(night, false, "warm400"));
        for (int i = 0; i < 3; i++) {
            assertNull(clf.evaluate(night, false, "warm400"));
        }
        assertEquals(SceneClassifier.Scene.NIGHT, clf.getActiveScene());

        // Full reset clears dismissed memory
        clf.reset();
        for (int i = 0; i < 2; i++) {
            assertNull(clf.evaluate(night, false, "warm400"));
        }
        assertNotNull(clf.evaluate(night, false, "warm400"));
    }

    @Test
    public void instantClassificationCoversAllPriorityCategories() {
        // Backlit takes priority over generic portrait
        assertEquals(SceneClassifier.Scene.BACKLIT,
                SceneClassifier.classifyInstant(
                        rgbStats(0.50f, 0.5f, 0.5f, 0.5f, 0.20f, 0.25f, 0.72f), true));

        // Portrait -> warm skin stock
        assertEquals(SceneClassifier.Scene.PORTRAIT,
                SceneClassifier.classifyInstant(
                        rgbStats(0.52f, 0.58f, 0.50f, 0.45f, 0.25f, 0.52f, 0.52f), true));

        // Low saturation monochrome scene -> hard B&W
        assertEquals(SceneClassifier.Scene.LOW_SATURATION,
                SceneClassifier.classifyInstant(
                        rgbStats(0.45f, 0.45f, 0.45f, 0.45f, 0.05f, 0.45f, 0.45f), false));

        // Golden hour -> daylight stock
        assertEquals(SceneClassifier.Scene.GOLDEN_HOUR,
                SceneClassifier.classifyInstant(
                        rgbStats(0.52f, 0.65f, 0.50f, 0.34f, 0.42f, 0.52f, 0.52f), false));

        // Cool overcast -> cool stock
        assertEquals(SceneClassifier.Scene.COOL_OVERCAST,
                SceneClassifier.classifyInstant(
                        rgbStats(0.50f, 0.42f, 0.50f, 0.58f, 0.25f, 0.50f, 0.50f), false));

        // Vivid foliage/landscape -> vivid stock
        assertEquals(SceneClassifier.Scene.VIVID_LANDSCAPE,
                SceneClassifier.classifyInstant(
                        rgbStats(0.52f, 0.42f, 0.62f, 0.38f, 0.46f, 0.52f, 0.52f), false));
    }

    @Test
    public void invalidOrCoveredFrames_neverTriggerAFilmSuggestion() {
        SceneClassifier clf = new SceneClassifier();
        // A lens covered by a palm is dark and flat: it must not be read as "night".
        PreviewStats covered = rgbStats(0.005f, 0.005f, 0.005f, 0.005f, 0f, 0.005f, 0.005f);
        assertEquals(SceneClassifier.Scene.GENERAL,
                SceneClassifier.classifyInstant(covered, false));
        for (int i = 0; i < 5; i++) {
            assertNull(clf.evaluate(covered, false, "warm400"));
        }
        assertEquals(SceneClassifier.Scene.GENERAL, clf.getActiveScene());

        // Statistics from a frame that was never sampled (0x0) must be ignored entirely.
        PreviewStats empty = new PreviewStats(0, 0, 0.5f, 0f, 0f, 0.5f, 0.5f,
                0.5f, 0.5f, 0.5f, 0f, 0.5f, 0.5f, 0f);
        assertNull(clf.evaluate(empty, false, "warm400"));
        assertEquals(SceneClassifier.Scene.GENERAL,
                SceneClassifier.classifyInstant(empty, false));
    }

    @Test
    public void previewStats_parsesBottomUpAndTopDownSaliencyCorrectly() {
        int w = 4, h = 4;
        byte[] rgba = new byte[w * h * 4];
        // Bright high-contrast block at OpenGL row 0 (bottom) col 0 -> should map to top-down y > 0.5!
        for (int row = 0; row < h; row++) {
            for (int x = 0; x < w; x++) {
                int idx = (row * w + x) * 4;
                int v = (row == 0 && x == 0) ? 255 : 20;
                rgba[idx] = (byte) v;
                rgba[idx + 1] = (byte) v;
                rgba[idx + 2] = (byte) v;
                rgba[idx + 3] = (byte) 255;
            }
        }
        PreviewStats bottomUp = PreviewStats.fromRgbaBytes(rgba, w, h, true);
        assertTrue("Bottom GL row should map to bottom of top-down frame", bottomUp.saliencyY > 0.6f);
        assertTrue(bottomUp.saliencyX < 0.4f);

        PreviewStats topDown = PreviewStats.fromRgbaBytes(rgba, w, h, false);
        assertTrue("Top row should stay near top when bottomUp=false", topDown.saliencyY < 0.4f);
    }

    @Test
    public void previewStats_measuresHighlightsShadowsCenterBorderAndSaturation() {
        int w = 8, h = 8;
        byte[] rgba = new byte[w * h * 4];
        // Border bright near-clipped warm daylight (248, 244, 240 -> luma >= 0.92),
        // center quarter [2..5, 2..5] dark crushed shadow (8, 8, 8 -> luma <= 0.06).
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = (y * w + x) * 4;
                boolean inCenter = x >= 2 && x < 6 && y >= 2 && y < 6;
                int r = inCenter ? 8 : 250;
                int g = inCenter ? 8 : 245;
                int b = inCenter ? 8 : 240;
                rgba[idx] = (byte) r;
                rgba[idx + 1] = (byte) g;
                rgba[idx + 2] = (byte) b;
                rgba[idx + 3] = (byte) 255;
            }
        }
        PreviewStats stats = PreviewStats.fromRgbaBytes(rgba, w, h, false);
        assertEquals(8, stats.width);
        assertEquals(8, stats.height);
        // 16 center pixels out of 64 crushed, 48 border pixels out of 64 clipped
        assertEquals(16f / 64f, stats.crushedShadows, 0.001f);
        assertEquals(48f / 64f, stats.clippedHighlights, 0.001f);
        assertTrue("Center luma should be crushed dark", stats.centerLuma < 0.05f);
        assertTrue("Border luma should be near highlight clip", stats.borderLuma > 0.94f);

        // Pure saturated green frame -> high meanSaturation and dominant avgG
        byte[] greenRgba = new byte[4 * 4 * 4];
        for (int i = 0; i < 16; i++) {
            greenRgba[i * 4] = 0;
            greenRgba[i * 4 + 1] = (byte) 200;
            greenRgba[i * 4 + 2] = 0;
            greenRgba[i * 4 + 3] = (byte) 255;
        }
        PreviewStats green = PreviewStats.fromRgbaBytes(greenRgba, 4, 4, false);
        assertTrue(green.meanSaturation > 0.95f);
        assertTrue(green.avgG > 0.75f && green.avgR < 0.01f && green.avgB < 0.01f);
    }

    @Test
    public void previewStats_handlesNullZeroAndTruncatedBuffersSafely() {
        PreviewStats nullStats = PreviewStats.fromRgbaBytes(null, 32, 24, true);
        assertEquals(0, nullStats.width);
        assertEquals(0, nullStats.height);
        assertEquals(0.5f, nullStats.meanLuma, 0.001f);
        assertEquals(0f, nullStats.saliencyConfidence, 0.001f);

        PreviewStats zeroDim = PreviewStats.fromRgbaBytes(new byte[16], 0, 4, false);
        assertEquals(0, zeroDim.width);

        PreviewStats shortBuf = PreviewStats.fromRgbaBytes(new byte[30], 4, 4, true);
        assertEquals(0, shortBuf.width);
        assertEquals(0f, shortBuf.clippedHighlights, 0.001f);
    }
}
