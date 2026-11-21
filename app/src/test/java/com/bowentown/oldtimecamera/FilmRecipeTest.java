package com.bowentown.oldtimecamera;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class FilmRecipeTest {

    @Test
    public void defaultRecipe_returnsExactBaseInstanceWithoutDrift() {
        FilmPreset warm = FilmPresets.all()[1];
        assertTrue(FilmRecipe.DEFAULT.isDefault());
        assertSame(warm, FilmRecipe.DEFAULT.apply(warm));
    }

    @Test
    public void zeroIntensity_convergesLookTowardNeutralWhileRespectingWarmthAndGrain() {
        FilmPreset stock = FilmPresets.all()[1]; // kd100 flagship stock
        FilmRecipe softNeutral = new FilmRecipe(0.0f, 0.5f, 0.0f);
        FilmPreset derived = softNeutral.apply(stock, "Soft KD");
        assertNotNull(derived);
        assertNotSame(stock, derived);
        assertEquals("Soft KD", derived.name);
        assertEquals(1.0f, derived.saturation, 1e-4f);
        assertEquals(1.0f, derived.contrast, 1e-4f);
        assertEquals(0.0f, derived.fade, 1e-4f);
        assertEquals(0.0f, derived.leak, 1e-4f);
        assertEquals(0.0f, derived.lutStrength, 1e-4f);
        assertEquals(0.0f, derived.chromaAberration, 1e-4f);
        assertEquals(0.0f, derived.cornerSoftness, 1e-4f);
        assertEquals(0.006f, derived.grain, 1e-4f);

        FilmRecipe warmed = new FilmRecipe(0.5f, 1.5f, 0.8f);
        FilmPreset warmDerived = warmed.apply(stock);
        assertTrue("Positive warmth boosts red channel over blue",
                warmDerived.colorMatrix[0] > warmDerived.colorMatrix[8]);
        assertTrue("Half intensity scales LUT strength", warmDerived.lutStrength > 0.4f
                && warmDerived.lutStrength < stock.lutStrength);
        assertTrue("Grain multiplier scales smoothly", warmDerived.grain > derived.grain);
    }

    @Test
    public void savedRecipeSerialization_roundTripsAndSanitizesDelimiters() {
        List<FilmRecipe.SavedRecipe> input = Arrays.asList(
                new FilmRecipe.SavedRecipe("r1", "微醺\t暖肤\n卷", "warm400", new FilmRecipe(0.75f, 1.4f, 0.6f)),
                new FilmRecipe.SavedRecipe("r2", "冷清夜景", "tungsten800", new FilmRecipe(0.90f, 0.8f, -0.5f))
        );
        String encoded = FilmRecipe.encodeSavedList(input);
        List<FilmRecipe.SavedRecipe> decoded = FilmRecipe.decodeSavedList(encoded);

        assertEquals(2, decoded.size());
        assertEquals("r1", decoded.get(0).id);
        assertFalse(decoded.get(0).name.contains("\t"));
        assertFalse(decoded.get(0).name.contains("\n"));
        assertEquals("warm400", decoded.get(0).basePresetId);
        assertEquals(0.75f, decoded.get(0).recipe.intensity, 0.01f);
        assertEquals(1.4f, decoded.get(0).recipe.grainScale, 0.01f);
        assertEquals(0.6f, decoded.get(0).recipe.warmth, 0.01f);
        assertEquals(-0.5f, decoded.get(1).recipe.warmth, 0.01f);

        assertTrue(FilmRecipe.decodeSavedList("").isEmpty());
        assertTrue(FilmRecipe.decodeSavedList("broken\tline\n").isEmpty());
    }

    @Test
    public void meterNeedleAndMistTexelMath_stayBoundedAndScaleConsistently() {
        PreviewStats balanced = new PreviewStats(32, 24, 0.46f, 0.01f, 0.05f,
                0.46f, 0.46f, 0.46f, 0.46f, 0.46f, 0.25f, 0.5f, 0.5f, 0.5f);
        assertEquals(0f, FilmRenderMath.meterNeedleStops(balanced), 0.05f);

        PreviewStats bright = new PreviewStats(32, 24, 0.90f, 0.35f, 0f,
                0.90f, 0.90f, 0.9f, 0.9f, 0.9f, 0.1f, 0.5f, 0.5f, 0.5f);
        assertTrue(FilmRenderMath.meterNeedleStops(bright) > 1.5f);
        assertTrue(FilmRenderMath.meterNeedleStops(bright) <= 2.0f);

        PreviewStats dark = new PreviewStats(32, 24, 0.08f, 0f, 0.80f,
                0.08f, 0.08f, 0.08f, 0.08f, 0.08f, 0.1f, 0.5f, 0.5f, 0.5f);
        assertTrue(FilmRenderMath.meterNeedleStops(dark) < -1.5f);
        assertTrue(FilmRenderMath.meterNeedleStops(dark) >= -2.0f);

        float[] prevStep = FilmRenderMath.mistTexelStep(900, 1200);
        float[] printStep = FilmRenderMath.mistTexelStep(1800, 2400);
        // Normalized blur radius in screen percentage remains identical between 1200p preview and 2400p export.
        assertEquals(prevStep[0], printStep[0], 1e-4f);
        assertEquals(prevStep[1], printStep[1], 1e-4f);
    }
}
