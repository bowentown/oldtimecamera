package com.bowentown.oldtimecamera;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the invariant that the live preview and the developed print produce grain of the
 * same physical size. The two passes run at different resolutions, so grain must be
 * defined relative to the frame, with a one-device-pixel floor.
 */
public class FilmRenderMathTest {

    @Test
    public void grainNeverFallsBelowOneDevicePixel() {
        assertEquals(1f, FilmRenderMath.grainCellSize(480), 1e-4f);
        assertEquals(1f, FilmRenderMath.grainCellSize(720), 1e-4f);
        assertEquals(1f, FilmRenderMath.grainCellSize(1080), 1e-4f);
        assertEquals(2560f / 1180f, FilmRenderMath.grainCellSize(2560), 1e-4f);
        assertEquals(1f / 1180f, FilmRenderMath.grainRelativeSize(1180), 1e-6f);
        assertEquals(1f / 1180f, FilmRenderMath.grainRelativeSize(0), 1e-6f);
    }

    @Test
    public void previewAndPrintGrainAgreeWithinOneDevicePixel() {
        // A 1080p viewfinder and a 2560px print must land within ~10% of each other.
        assertTrue(FilmRenderMath.grainScalesAgree(1080, 2560));
        assertTrue(FilmRenderMath.grainScalesAgree(1440, 1920));
        assertTrue(FilmRenderMath.grainScalesAgree(1920, 2560));
        // Identical targets are trivially identical.
        assertEquals(FilmRenderMath.grainRelativeSize(1920),
                FilmRenderMath.grainRelativeSize(1920), 0f);
        // A deliberately tiny preview cannot match a huge print, and we must notice.
        assertFalse(FilmRenderMath.grainScalesAgree(360, 4096));
    }
}
