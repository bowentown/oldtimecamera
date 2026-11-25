package com.bowentown.oldtimecamera;

import org.junit.Test;

import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for aspect cropping bounds, XPAN/16:9 paper proportions,
 * Screen double-exposure blending, film stock presets, and darkroom prefs enums.
 */
public class PhotoSaverAndPrefsTest {

    @Test
    public void computeCropBounds_handlesAllRatiosAndEdgeInputs() {
        // 3:4 photo cropped to 3:4 -> untouched
        assertArrayEquals(new int[]{0, 0, 3000, 4000},
                PhotoSaver.computeCropBounds(3000, 4000, CameraPrefs.AspectRatio.RATIO_3_4.widthOverHeight));

        // 3:4 photo cropped to 1:1 -> centered square
        assertArrayEquals(new int[]{0, 500, 3000, 3000},
                PhotoSaver.computeCropBounds(3000, 4000, CameraPrefs.AspectRatio.RATIO_1_1.widthOverHeight));

        // 3:4 photo cropped to 16:9 -> horizontal band centered vertically
        int[] crop169 = PhotoSaver.computeCropBounds(
                3000, 4000, CameraPrefs.AspectRatio.RATIO_16_9.widthOverHeight);
        assertEquals(0, crop169[0]);
        assertEquals(3000, crop169[2]);
        assertEquals(1688, crop169[3]);
        assertEquals((4000 - 1688) / 2, crop169[1]);

        // 3:4 photo cropped to XPAN 65:24 -> ultra-wide cinema band centered vertically
        int[] cropXpan = PhotoSaver.computeCropBounds(
                3000, 4000, CameraPrefs.AspectRatio.RATIO_XPAN.widthOverHeight);
        int expectedH = Math.round(3000f / (65f / 24f));
        assertEquals(0, cropXpan[0]);
        assertEquals(3000, cropXpan[2]);
        assertEquals(expectedH, cropXpan[3]);
        assertEquals((4000 - expectedH) / 2, cropXpan[1]);

        // Tiny / degenerate inputs stay within bounds and never return 0 when positive
        int[] tiny = PhotoSaver.computeCropBounds(1, 1, 65f / 24f);
        assertEquals(1, tiny[2]);
        assertEquals(1, tiny[3]);

        assertArrayEquals(new int[]{0, 0, 0, 0}, PhotoSaver.computeCropBounds(0, 100, 1f));
        assertArrayEquals(new int[]{0, 0, 100, 100}, PhotoSaver.computeCropBounds(100, 100, -1f));
    }

    @Test
    public void effectiveCropRatio_flipsOnlyPortraitRatioOnLandscapeCapture() {
        assertEquals(3f / 4f,
                PhotoSaver.effectiveCropRatio(CameraPrefs.AspectRatio.RATIO_3_4, false), 1e-4f);
        assertEquals(4f / 3f,
                PhotoSaver.effectiveCropRatio(CameraPrefs.AspectRatio.RATIO_3_4, true), 1e-4f);
        assertEquals(3f / 2f,
                PhotoSaver.effectiveCropRatio(CameraPrefs.AspectRatio.RATIO_3_2, false), 1e-4f);
        assertEquals(3f / 2f,
                PhotoSaver.effectiveCropRatio(CameraPrefs.AspectRatio.RATIO_3_2, true), 1e-4f);
        assertEquals(1f,
                PhotoSaver.effectiveCropRatio(CameraPrefs.AspectRatio.RATIO_1_1, true), 1e-4f);
        assertEquals(16f / 9f,
                PhotoSaver.effectiveCropRatio(CameraPrefs.AspectRatio.RATIO_16_9, true), 1e-4f);
        assertEquals(65f / 24f,
                PhotoSaver.effectiveCropRatio(CameraPrefs.AspectRatio.RATIO_XPAN, true), 1e-4f);
    }

    @Test
    public void landscapePhoto_preservesLandscapeCropUnderRatio34AndOtherRatios() {
        int w = 4000;
        int h = 3000;
        boolean landscape = w > h;

        // Landscape 4000x3000 (4:3) imported or captured under RATIO_3_4 stays untouched 4:3 landscape
        float ratio34 = PhotoSaver.effectiveCropRatio(CameraPrefs.AspectRatio.RATIO_3_4, landscape);
        assertEquals(4f / 3f, ratio34, 1e-4f);
        assertArrayEquals(new int[]{0, 0, 4000, 3000},
                PhotoSaver.computeCropBounds(w, h, ratio34));

        // Wider landscape import (e.g. 4000x2250, 16:9) under RATIO_3_4 trims width to 4:3 landscape (3000x2250)
        float wideRatio34 = PhotoSaver.effectiveCropRatio(CameraPrefs.AspectRatio.RATIO_3_4, 4000 > 2250);
        int[] wideTo43 = PhotoSaver.computeCropBounds(4000, 2250, wideRatio34);
        assertArrayEquals(new int[]{500, 0, 3000, 2250}, wideTo43);
        assertTrue(wideTo43[2] > wideTo43[3]);

        // Landscape 4000x3000 under 3:2 / 1:1 / 16:9 / XPAN crops cleanly
        assertArrayEquals(new int[]{0, 166, 4000, 2667},
                PhotoSaver.computeCropBounds(w, h,
                        PhotoSaver.effectiveCropRatio(CameraPrefs.AspectRatio.RATIO_3_2, landscape)));
        assertArrayEquals(new int[]{500, 0, 3000, 3000},
                PhotoSaver.computeCropBounds(w, h,
                        PhotoSaver.effectiveCropRatio(CameraPrefs.AspectRatio.RATIO_1_1, landscape)));
        assertArrayEquals(new int[]{0, 375, 4000, 2250},
                PhotoSaver.computeCropBounds(w, h,
                        PhotoSaver.effectiveCropRatio(CameraPrefs.AspectRatio.RATIO_16_9, landscape)));
        assertArrayEquals(new int[]{0, 761, 4000, 1477},
                PhotoSaver.computeCropBounds(w, h,
                        PhotoSaver.effectiveCropRatio(CameraPrefs.AspectRatio.RATIO_XPAN, landscape)));
    }

    @Test
    public void computePaperGeometry_balancesXpanAndClassicBorders() {
        // NONE produces zero border
        assertArrayEquals(new int[]{0, 0, 1200, 1600},
                PhotoSaver.computePaperGeometry(1200, 1600,
                        CameraPrefs.PaperStyle.NONE, CameraPrefs.AspectRatio.RATIO_3_4));

        // 1:1 preserves the classic instant-print footer proportion. Values are pinned
        // numbers on purpose: recomputing them from the implementation would let a broken
        // formula keep the test green.
        int[] polaroid = PhotoSaver.computePaperGeometry(1200, 1200,
                CameraPrefs.PaperStyle.IVORY, CameraPrefs.AspectRatio.RATIO_1_1);
        assertEquals(66, polaroid[0]);
        assertEquals(222, polaroid[1]);

        // XPAN scales from short side (h=960) so footer stays sleek (< 16% of height)
        int[] xpan = PhotoSaver.computePaperGeometry(2600, 960,
                CameraPrefs.PaperStyle.BLACK, CameraPrefs.AspectRatio.RATIO_XPAN);
        assertEquals(43, xpan[0]);
        assertEquals(130, xpan[1]);
        assertTrue(xpan[1] < 960 * 0.16f);

        assertArrayEquals(new int[]{0, 0, 0, 0},
                PhotoSaver.computePaperGeometry(0, 960,
                        CameraPrefs.PaperStyle.IVORY, CameraPrefs.AspectRatio.RATIO_3_4));
    }

    @Test
    public void blendScreenPixels_preservesDoubleExposureIdentitiesAndSymmetry() {
        assertNull(PhotoSaver.blendScreenPixels(null, new int[]{0}));
        assertNull(PhotoSaver.blendScreenPixels(new int[]{0}, null));

        int color = 0xFF3478BC;
        int black = 0xFF000000;
        int white = 0xFFFFFFFF;
        int mid = 0xFF808080;

        // Black acts as neutral identity in Screen blending
        assertArrayEquals(new int[]{color},
                PhotoSaver.blendScreenPixels(new int[]{black}, new int[]{color}));
        // White saturates cleanly without overflow
        assertArrayEquals(new int[]{white},
                PhotoSaver.blendScreenPixels(new int[]{white}, new int[]{color}));
        // Symmetry + mismatched array lengths handled cleanly
        int[] ab = PhotoSaver.blendScreenPixels(new int[]{color, mid}, new int[]{mid});
        int[] ba = PhotoSaver.blendScreenPixels(new int[]{mid}, new int[]{color, white});
        assertEquals(1, ab.length);
        assertEquals(ab[0], ba[0]);

        // Mid-gray + mid-gray = 1 - (1 - 128/255)^2 ~= 192
        int[] midBlend = PhotoSaver.blendScreenPixels(new int[]{mid}, new int[]{mid});
        int r = (midBlend[0] >>> 16) & 0xFF;
        assertEquals(192, r);
    }

    @Test
    public void viewfinderMaskAndCaptureCropResolveToTheSameRatio() {
        // 0/1/2/3 == Surface.ROTATION_0/90/180/270 (inlined constants; no framework call).
        int[] rotations = {0, 1, 2, 3};
        for (CameraPrefs.AspectRatio ratio : CameraPrefs.AspectRatio.values()) {
            for (int rot : rotations) {
                boolean landscape = (rot == 1 || rot == 3);
                assertEquals("ratio " + ratio + " at rotation " + rot,
                        PhotoSaver.effectiveCropRatio(ratio, landscape),
                        PhotoSaver.previewCropRatio(ratio, rot), 1e-6f);
            }
        }
        // The concrete regression: 3:4 held sideways is a 4:3 print, so the mask must be 4:3 too.
        assertEquals(4f / 3f, PhotoSaver.previewCropRatio(CameraPrefs.AspectRatio.RATIO_3_4, 1), 1e-4f);
        assertEquals(3f / 4f, PhotoSaver.previewCropRatio(CameraPrefs.AspectRatio.RATIO_3_4, 0), 1e-4f);
        assertEquals(16f / 9f, PhotoSaver.previewCropRatio(CameraPrefs.AspectRatio.RATIO_16_9, 1), 1e-4f);
    }

    @Test
    public void recipeCommentRecordsStockAndFrameWithoutPersonalData() {
        String comment = PhotoSaver.buildRecipeComment(
                FilmPresets.all()[1], true, false, 3, 7, 450);
        assertTrue(comment.contains("stock=kd100"));
        assertTrue(comment.contains("grain=boost"));
        assertTrue(comment.contains("leak=off"));
        assertTrue(comment.contains("roll=03"));
        assertTrue(comment.contains("frame=07"));
        assertTrue("rotation wraps into 0..359", comment.contains("rot=090"));
        assertNull(PhotoSaver.buildRecipeComment(null, false, false, 1, 1, 0));
    }

    @Test
    public void filmStockNamesAvoidThirdPartyTrademarks() {
        String[] banned = {"PORTRA", "GOLD", "SUPERIA", "VELVIA", "TRI-X", "TRIX",
                "KODA", "POLA", "HP5", "800T", "CINESTILL"};
        for (FilmPreset p : FilmPresets.all()) {
            String haystack = (p.name + " " + p.id + " " + p.isoTag).toUpperCase();
            for (String mark : banned) {
                assertTrue(p.name + " must not use the trademark " + mark,
                        !haystack.contains(mark));
            }
        }
    }

    @Test
    public void aspectRatioAndFilmPresets_areCompleteAndConcise() {
        CameraPrefs.AspectRatio[] ratios = CameraPrefs.AspectRatio.values();
        assertEquals(5, ratios.length);
        CameraPrefs.AspectRatio cur = CameraPrefs.AspectRatio.RATIO_3_4;
        for (int i = 0; i < ratios.length; i++) {
            cur = cur.next();
        }
        assertEquals(CameraPrefs.AspectRatio.RATIO_3_4, cur);
        assertEquals(CameraPrefs.AspectRatio.RATIO_XPAN, CameraPrefs.AspectRatio.fromName("RATIO_XPAN"));
        assertEquals(CameraPrefs.AspectRatio.RATIO_3_4, CameraPrefs.AspectRatio.fromName("INVALID"));
        assertEquals(CameraPrefs.AspectRatio.RATIO_3_4, CameraPrefs.AspectRatio.fromName(null));

        FilmPreset[] all = FilmPresets.all();
        assertEquals(10, all.length);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < all.length; i++) {
            FilmPreset p = all[i];
            assertNotNull(p.id);
            assertTrue(ids.add(p.id));
            assertNotNull(p.name);
            assertTrue(p.name.length() <= 12);
            assertEquals(i, FilmPresets.indexOfId(p.id));
        }
        assertEquals(1, FilmPresets.indexOfId(null));
        assertEquals(1, FilmPresets.indexOfId("nonexistent"));
    }

    @Test
    public void sprocketPaperHalfFrameQuadGridAndExifFormatting_workCleanly() {
        int[] sp = PhotoSaver.computePaperGeometry(1200, 1600,
                CameraPrefs.PaperStyle.SPROCKET_35MM, CameraPrefs.AspectRatio.RATIO_3_4);
        assertTrue(sp[0] > 60 && sp[1] > 60);
        assertEquals(1200 + sp[0] * 2, sp[2]);

        assertEquals(36, CameraPrefs.framesForMode(CameraPrefs.ShootMode.SINGLE));
        assertEquals(72, CameraPrefs.framesForMode(CameraPrefs.ShootMode.HALF_FRAME));
        CameraPrefs.ShootMode sm = CameraPrefs.ShootMode.SINGLE;
        for (int i = 0; i < CameraPrefs.ShootMode.values().length; i++) {
            sm = sm.next();
        }
        assertEquals(CameraPrefs.ShootMode.SINGLE, sm);

        assertTrue(PhotoSaver.shouldPairHalfFrameSideBySide(false));
        assertEquals(false, PhotoSaver.shouldPairHalfFrameSideBySide(true));

        assertEquals("1/125s · f/1.8 · ISO 200",
                PhotoSaver.formatShortExifStrings("0.008", "1.8", "200, 200"));
        assertEquals("", PhotoSaver.formatShortExifStrings(null, "bad", ""));

        // Legacy v1.x stock ID aliasing
        assertEquals("kd100", FilmPresets.normalizeId("vivid50"));
        assertEquals("cpm35", FilmPresets.normalizeId("vintage64"));
        assertEquals("fxn400", FilmPresets.normalizeId("cool400"));
        assertEquals("hardbw400", FilmPresets.normalizeId("softbw400"));
        assertEquals(FilmPresets.indexOfId("kd100"), FilmPresets.indexOfId("vivid50"));

        // EXIF_FRAME geometry & nameplate spec formatting
        assertEquals(5, CameraPrefs.PaperStyle.values().length);
        int[] exifGeo = PhotoSaver.computePaperGeometry(1200, 1600,
                CameraPrefs.PaperStyle.EXIF_FRAME, CameraPrefs.AspectRatio.RATIO_3_4);
        assertEquals(31, exifGeo[0]);
        assertEquals(158, exifGeo[1]);
        assertEquals("26mm  f/1.8  1/125s  ISO 200",
                PhotoSaver.formatExifSpecLine("26", "5400/1000", "18/10", "0.008", "200", "100"));
        assertEquals("5.4mm  f/2.0  ISO 400",
                PhotoSaver.formatExifSpecLine("", "5400/1000", "2.0", "", "", "400"));
        assertEquals("35mm  ·  ISO 100",
                PhotoSaver.formatExifSpecLine(null, null, null, null, null, "100"));
        assertEquals("2026/11/21 22:25",
                PhotoSaver.formatExifCaptureDate("2026:11:21 22:25:09", CameraPrefs.DateMode.TODAY));
        assertEquals("1998/11/21 22:25",
                PhotoSaver.formatExifCaptureDate("2026:11:21 22:25:09", CameraPrefs.DateMode.RETRO_98));
        assertEquals("",
                PhotoSaver.formatExifCaptureDate("2026:11:21 22:25:09", CameraPrefs.DateMode.OFF));

        String extComment = PhotoSaver.buildRecipeComment(
                FilmPresets.all()[1], false, true, 2, 14, 0,
                new FilmRecipe(0.6f, 1.5f, 0.4f), true, CameraPrefs.ShootMode.HALF_FRAME);
        assertTrue(extComment.contains("int=0.60"));
        assertTrue(extComment.contains("mist=on"));
        assertTrue(extComment.contains("mode=half_frame"));
    }

    @Test
    public void dateString_formatsFullYearSlashAndDevelopingTime() {
        Calendar cal1 = Calendar.getInstance();
        cal1.set(2026, Calendar.NOVEMBER, 25, 22, 52, 0);
        Date t1 = cal1.getTime();

        Calendar cal2 = Calendar.getInstance();
        cal2.set(2026, Calendar.NOVEMBER, 25, 23, 8, 0);
        Date t2 = cal2.getTime();

        assertEquals("2026/11/25 22:52", PhotoSaver.dateString(CameraPrefs.DateMode.TODAY, t1));
        assertEquals("2026/11/25 23:08", PhotoSaver.dateString(CameraPrefs.DateMode.TODAY, t2));
        assertEquals("1998/11/25 22:52", PhotoSaver.dateString(CameraPrefs.DateMode.RETRO_98, t1));
        assertEquals("", PhotoSaver.dateString(CameraPrefs.DateMode.OFF, t1));
    }
}
