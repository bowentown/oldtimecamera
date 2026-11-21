package com.bowentown.oldtimecamera;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM tests for the v2.0 3D HaldCLUT synthesizer, trilinear sampler,
 * Kelvin white-balance math, and rolling 36-frame {@link NegativeVault} index.
 */
public class FilmLutAndNegativeVaultTest {

    @Test
    public void rawLut_isExactIdentityWithinEightBitQuantization() {
        FilmPreset raw = FilmPresets.all()[0];
        assertEquals("raw", raw.id);
        byte[] lut = FilmLutMath.generateLut256x16(raw);
        assertEquals(FilmLutMath.LUT_WIDTH * FilmLutMath.LUT_HEIGHT * 4, lut.length);

        float[] out = new float[3];
        FilmLutMath.sampleLutTrilinear(lut, 0f, 0f, 0f, out);
        assertEquals(0f, out[0], 1e-4f);
        assertEquals(0f, out[1], 1e-4f);
        assertEquals(0f, out[2], 1e-4f);

        float[][] probes = {
                {0.25f, 0.50f, 0.75f},
                {0.82f, 0.34f, 0.19f},
                {1.0f, 1.0f, 1.0f}
        };
        for (float[] p : probes) {
            FilmLutMath.sampleLutTrilinear(lut, p[0], p[1], p[2], out);
            assertEquals(p[0], out[0], 0.006f);
            assertEquals(p[1], out[1], 0.006f);
            assertEquals(p[2], out[2], 0.006f);
        }
    }

    @Test
    public void kd100Lut_exhibitsSplitToningFoliageDepthAndHighlightShoulder() {
        FilmPreset kd = FilmPresets.all()[FilmPresets.indexOfId("kd100")];
        byte[] lut = FilmLutMath.generateLut256x16(kd);
        float[] out = new float[3];

        // True black stays anchored so contrast never washes out unintentionally.
        FilmLutMath.sampleLutTrilinear(lut, 0f, 0f, 0f, out);
        assertEquals(0f, out[0], 0.005f);
        assertEquals(0f, out[1], 0.005f);
        assertEquals(0f, out[2], 0.005f);

        // Deep shadows carry cool cyan-green split-toning relative to red.
        FilmLutMath.sampleLutTrilinear(lut, 0.14f, 0.14f, 0.14f, out);
        assertTrue("KD shadow green/blue > red", out[1] > out[0] && out[2] > out[0]);

        // Warm sunlit highlights favor creamy golden red/green over blue.
        FilmLutMath.sampleLutTrilinear(lut, 0.85f, 0.85f, 0.85f, out);
        assertTrue("KD highlight red > blue", out[0] > out[2]);

        // Highlight shoulder compresses extreme highlights below hard 1.0 clipping.
        FilmLutMath.sampleLutTrilinear(lut, 0.96f, 0.96f, 0.96f, out);
        assertTrue("Filmic shoulder prevents premature 1.0 clip", out[2] < 0.96f);

        // Foliage green shifts away from neon yellow-green toward deeper analog emerald.
        FilmLutMath.sampleLutTrilinear(lut, 0.22f, 0.64f, 0.20f, out);
        assertTrue("Foliage red reduced for emerald depth", out[0] < 0.22f);
        assertTrue("Foliage cool undertone lifted", out[2] > 0.20f);
    }

    @Test
    public void allTenFilmStocks_produceMonotonicBounded3DLuts() {
        float[] low = new float[3];
        float[] mid = new float[3];
        float[] high = new float[3];
        for (FilmPreset preset : FilmPresets.all()) {
            byte[] lut = FilmLutMath.generateLut256x16(preset);
            assertNotNull(lut);
            assertEquals(16384, lut.length);

            FilmLutMath.sampleLutTrilinear(lut, 0.12f, 0.12f, 0.12f, low);
            FilmLutMath.sampleLutTrilinear(lut, 0.50f, 0.50f, 0.50f, mid);
            FilmLutMath.sampleLutTrilinear(lut, 0.88f, 0.88f, 0.88f, high);

            float lLow = 0.2126f * low[0] + 0.7152f * low[1] + 0.0722f * low[2];
            float lMid = 0.2126f * mid[0] + 0.7152f * mid[1] + 0.0722f * mid[2];
            float lHigh = 0.2126f * high[0] + 0.7152f * high[1] + 0.0722f * high[2];
            assertTrue(preset.id + " monotonic low < mid", lLow < lMid);
            assertTrue(preset.id + " monotonic mid < high", lMid < lHigh);
            for (float c : high) {
                assertTrue(c >= 0f && c <= 1f);
            }
        }
    }

    @Test
    public void sceneWbMath_mapsKelvinLabelsAndVonKriesMultipliersCleanly() {
        float[] neutral = FilmLutMath.computeSceneWbMultipliers(0f);
        assertEquals(1f, neutral[0], 1e-4f);
        assertEquals(1f, neutral[1], 1e-4f);
        assertEquals(1f, neutral[2], 1e-4f);
        assertEquals("5500K", FilmLutMath.wbKelvinLabel(0f));

        float[] warm = FilmLutMath.computeSceneWbMultipliers(1f);
        assertTrue(warm[0] > 1.1f && warm[2] < 0.9f);
        assertEquals("7500K", FilmLutMath.wbKelvinLabel(1f));

        float[] cool = FilmLutMath.computeSceneWbMultipliers(-1f);
        assertTrue(cool[2] > 1.1f && cool[0] < 0.9f);
        assertEquals("3200K", FilmLutMath.wbKelvinLabel(-1f));

        assertEquals("5500K", FilmLutMath.wbKelvinLabel(Float.NaN));
        assertEquals("7500K", FilmLutMath.wbKelvinLabel(5f));
    }

    @Test
    public void negativeVaultCodecAndRolling36Cap_roundTripAndEvictOldest() {
        List<NegativeVault.Record> records = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            records.add(new NegativeVault.Record(
                    "OTC_" + i + ".jpg",
                    "content://media/external/images/media/" + i,
                    "neg_" + i + ".jpg",
                    CameraPrefs.AspectRatio.RATIO_3_4,
                    1000L + i));
        }

        List<NegativeVault.Record> evicted = NegativeVault.pruneToMax(
                records, NegativeVault.MAX_NEGATIVES);
        assertEquals(4, evicted.size());
        assertEquals("OTC_1.jpg", evicted.get(0).printDisplayName);
        assertEquals("OTC_4.jpg", evicted.get(3).printDisplayName);
        assertEquals(36, records.size());
        assertEquals("OTC_5.jpg", records.get(0).printDisplayName);
        assertEquals("OTC_40.jpg", records.get(35).printDisplayName);

        String tsv = NegativeVault.encodeRecords(records);
        List<NegativeVault.Record> decoded = NegativeVault.decodeRecords(tsv);
        assertEquals(36, decoded.size());
        assertEquals("neg_5.jpg", decoded.get(0).negFileName);
        assertTrue(NegativeVault.isFileReferenced(decoded, "neg_40.jpg"));
        assertFalse(NegativeVault.isFileReferenced(decoded, "neg_1.jpg"));

        // Path traversal entries are rejected on decode.
        List<NegativeVault.Record> unsafe = NegativeVault.decodeRecords(
                "a.jpg\tcontent://1\t../secret.jpg\tRATIO_3_4\t123\n");
        assertTrue(unsafe.isEmpty());

        // Shared negative ref-count stays true when a derived re-washed print shares negFileName.
        List<NegativeVault.Record> shared = new ArrayList<>(Arrays.asList(
                new NegativeVault.Record("OTC_A.jpg", "u1", "neg_shared.jpg",
                        CameraPrefs.AspectRatio.RATIO_3_2, 10L),
                new NegativeVault.Record("OTC_B.jpg", "u2", "neg_shared.jpg",
                        CameraPrefs.AspectRatio.RATIO_3_2, 20L)));
        shared.remove(0);
        assertTrue(NegativeVault.isFileReferenced(shared, "neg_shared.jpg"));
    }
}
