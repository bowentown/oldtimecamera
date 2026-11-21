package com.bowentown.oldtimecamera;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Surface;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Cropping, paper print borders, amber date back-print, 35mm contact sheet
 * generation, and MediaStore album persistence.
 */
public final class PhotoSaver {

    public static final class PhotoItem {
        public final Uri uri;
        public final String displayName;
        public final long dateMillis;

        public PhotoItem(Uri uri, String displayName, long dateMillis) {
            this.uri = uri;
            this.displayName = displayName;
            this.dateMillis = dateMillis;
        }
    }

    private PhotoSaver() {}

    public static String dateString(CameraPrefs.DateMode mode) {
        if (mode == CameraPrefs.DateMode.OFF) return "";
        Date now = new Date();
        if (mode == CameraPrefs.DateMode.RETRO_98) {
            return "98 " + new SimpleDateFormat("MM dd", Locale.US).format(now);
        }
        return new SimpleDateFormat("yy MM dd", Locale.US).format(now);
    }

    /**
     * Pure integer crop rectangle {@code [left, top, cropW, cropH]} centered on a
     * {@code w x h} image to achieve {@code targetRatio} ({@code width / height}).
     */
    public static int[] computeCropBounds(int w, int h, float targetRatio) {
        if (w <= 0 || h <= 0) {
            return new int[]{0, 0, 0, 0};
        }
        if (targetRatio <= 0f) {
            return new int[]{0, 0, w, h};
        }
        float current = (float) w / (float) h;
        if (Math.abs(current - targetRatio) < 0.015f) {
            return new int[]{0, 0, w, h};
        }
        int cropW = w;
        int cropH = h;
        int x = 0;
        int y = 0;
        if (current > targetRatio) {
            cropW = Math.max(1, Math.min(w, Math.round(h * targetRatio)));
            x = (w - cropW) / 2;
        } else {
            cropH = Math.max(1, Math.min(h, Math.round(w / targetRatio)));
            y = (h - cropH) / 2;
        }
        return new int[]{x, y, cropW, cropH};
    }

    /**
     * Resolves effective {@code width / height} crop ratio for captured prints.
     * When the photographer holds a portrait-locked viewfinder horizontally
     * ({@code landscapeCapture == true}), portrait ratios like {@code 3:4} and {@code 2:3}
     * flip to {@code 4:3} and {@code 3:2}, whereas wide cinema ratios ({@code 16:9},
     * {@code XPAN}) remain landscape.
     *
     * <p>The live viewfinder frame must use this same value, otherwise the mask shows a
     * portrait rectangle while the saved print is a landscape crop.
     */
    public static float effectiveCropRatio(CameraPrefs.AspectRatio ratio, boolean landscapeCapture) {
        if (ratio == null) return 3f / 4f;
        if (landscapeCapture && ratio.widthOverHeight < 1f) {
            return 1f / ratio.widthOverHeight;
        }
        return ratio.widthOverHeight;
    }

    /** True when the device is physically held sideways for the given display rotation. */
    public static boolean isLandscapeRotation(int surfaceRotation) {
        return surfaceRotation == Surface.ROTATION_90
                || surfaceRotation == Surface.ROTATION_270;
    }

    /** Maps a display rotation to the effective print ratio the capture pipeline will use. */
    public static float previewCropRatio(CameraPrefs.AspectRatio ratio, int surfaceRotation) {
        return effectiveCropRatio(ratio, isLandscapeRotation(surfaceRotation));
    }

    public static Bitmap cropToAspect(Bitmap src, CameraPrefs.AspectRatio ratio) {
        return cropToAspect(src, ratio, src != null && src.getWidth() > src.getHeight());
    }

    public static Bitmap cropToAspect(Bitmap src, CameraPrefs.AspectRatio ratio, boolean landscapeCapture) {
        if (src == null || ratio == null) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        float target = effectiveCropRatio(ratio, landscapeCapture);
        int[] bounds = computeCropBounds(w, h, target);
        if (bounds[0] == 0 && bounds[1] == 0 && bounds[2] == w && bounds[3] == h) {
            return src;
        }

        Bitmap cropped = Bitmap.createBitmap(src, bounds[0], bounds[1], bounds[2], bounds[3]);
        if (cropped != src) src.recycle();
        return cropped;
    }

    /**
     * Pure Screen-blend math on packed ARGB_8888 pixel arrays for analog double exposure:
     * {@code 1 - (1 - a) * (1 - b)}.
     */
    public static int[] blendScreenPixels(int[] firstArgb, int[] secondArgb) {
        if (firstArgb == null || secondArgb == null) return null;
        int len = Math.min(firstArgb.length, secondArgb.length);
        int[] out = new int[len];
        for (int i = 0; i < len; i++) {
            int p1 = firstArgb[i];
            int p2 = secondArgb[i];
            int a = Math.max((p1 >>> 24) & 0xFF, (p2 >>> 24) & 0xFF);
            int r1 = (p1 >>> 16) & 0xFF;
            int g1 = (p1 >>> 8) & 0xFF;
            int b1 = p1 & 0xFF;
            int r2 = (p2 >>> 16) & 0xFF;
            int g2 = (p2 >>> 8) & 0xFF;
            int b2 = p2 & 0xFF;

            int r = 255 - ((255 - r1) * (255 - r2) + 127) / 255;
            int g = 255 - ((255 - g1) * (255 - g2) + 127) / 255;
            int b = 255 - ((255 - b1) * (255 - b2) + 127) / 255;
            out[i] = (a << 24) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
        }
        return out;
    }

    /** Blends two aligned camera frames using analog Screen double-exposure compositing. */
    public static Bitmap blendDoubleExposure(Bitmap first, Bitmap second) {
        if (first == null || second == null) return second != null ? second : first;
        int w = second.getWidth();
        int h = second.getHeight();
        Bitmap preparedFirst = first;
        if (first.getWidth() != w || first.getHeight() != h) {
            int[] bounds = computeCropBounds(first.getWidth(), first.getHeight(), (float) w / (float) h);
            Bitmap croppedFirst = (bounds[0] == 0 && bounds[1] == 0
                    && bounds[2] == first.getWidth() && bounds[3] == first.getHeight())
                    ? first
                    : Bitmap.createBitmap(first, bounds[0], bounds[1], bounds[2], bounds[3]);
            preparedFirst = (croppedFirst.getWidth() == w && croppedFirst.getHeight() == h)
                    ? croppedFirst
                    : Bitmap.createScaledBitmap(croppedFirst, w, h, true);
            if (croppedFirst != first && croppedFirst != preparedFirst) {
                croppedFirst.recycle();
            }
        }

        int[] px1 = new int[w * h];
        int[] px2 = new int[w * h];
        preparedFirst.getPixels(px1, 0, w, 0, 0, w, h);
        second.getPixels(px2, 0, w, 0, 0, w, h);
        if (preparedFirst != first) {
            preparedFirst.recycle();
        }
        int[] blended = blendScreenPixels(px1, px2);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(blended, 0, w, 0, 0, w, h);
        return out;
    }

    public static Bitmap flipHorizontal(Bitmap src) {
        if (src == null) return null;
        Matrix m = new Matrix();
        m.preScale(-1f, 1f);
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src) src.recycle();
        return out;
    }

    /** Bilinearly scales a decoded bitmap so its longest side fits within maxEdge, recycling {@code src}. */
    public static Bitmap capEdge(Bitmap src, int maxEdge) {
        Bitmap scaled = capEdgeNoRecycle(src, maxEdge);
        if (scaled != src && src != null && !src.isRecycled()) {
            src.recycle();
        }
        return scaled;
    }

    private static Bitmap capEdgeNoRecycle(Bitmap src, int maxEdge) {
        if (src == null || maxEdge <= 0) return src;
        int curLongest = Math.max(src.getWidth(), src.getHeight());
        if (curLongest <= maxEdge) return src;
        float scale = (float) maxEdge / (float) curLongest;
        int nw = Math.max(1, Math.round(src.getWidth() * scale));
        int nh = Math.max(1, Math.round(src.getHeight() * scale));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    /**
     * Pure print geometry {@code [pad, footer, outW, outH]} scaled off the short side so
     * ultra-wide ({@code XPAN}, {@code 16:9}) and landscape prints avoid excessive footers.
     */
    public static int[] computePaperGeometry(int photoW, int photoH,
                                             CameraPrefs.PaperStyle paperStyle,
                                             CameraPrefs.AspectRatio aspect) {
        if (photoW <= 0 || photoH <= 0) return new int[]{0, 0, 0, 0};
        boolean hasFrame = paperStyle != null && paperStyle != CameraPrefs.PaperStyle.NONE;
        if (!hasFrame) return new int[]{0, 0, photoW, photoH};

        int shortSide = Math.min(photoW, photoH);
        if (paperStyle == CameraPrefs.PaperStyle.SPROCKET_35MM) {
            int pad = Math.round(shortSide * 0.068f);
            int footer = Math.round(shortSide * 0.062f);
            return new int[]{pad, footer, photoW + pad * 2, photoH + pad * 2 + footer};
        }
        if (paperStyle == CameraPrefs.PaperStyle.EXIF_FRAME) {
            int pad = Math.round(shortSide * 0.026f);
            int footer = Math.round(shortSide
                    * (aspect == CameraPrefs.AspectRatio.RATIO_XPAN ? 0.145f : 0.132f));
            return new int[]{pad, footer, photoW + pad * 2, photoH + pad * 2 + footer};
        }
        float padRatio;
        float footerRatio;
        if (aspect == CameraPrefs.AspectRatio.RATIO_1_1) {
            padRatio = 0.055f;
            footerRatio = 0.185f;
        } else if (aspect == CameraPrefs.AspectRatio.RATIO_XPAN
                || aspect == CameraPrefs.AspectRatio.RATIO_16_9) {
            padRatio = 0.045f;
            footerRatio = 0.135f;
        } else {
            padRatio = 0.036f;
            footerRatio = 0.115f;
        }
        int pad = Math.round(shortSide * padRatio);
        int footer = Math.round(shortSide * footerRatio);
        return new int[]{pad, footer, photoW + pad * 2, photoH + pad * 2 + footer};
    }

    /** Adds the selected print border, frame badge, optional note, and LED date stamp. */
    public static Bitmap applyPaper(Bitmap photo, FilmPreset preset,
                                    CameraPrefs.PaperStyle paperStyle,
                                    CameraPrefs.DateMode dateMode,
                                    CameraPrefs.AspectRatio aspect,
                                    int frameNumber,
                                    String customNote) {
        return applyPaper(null, photo, preset, paperStyle, dateMode, aspect, frameNumber, customNote, null);
    }

    /** Adds the selected print border, frame badge, optional note, and LED date stamp. */
    public static Bitmap applyPaper(Context context, Bitmap photo, FilmPreset preset,
                                    CameraPrefs.PaperStyle paperStyle,
                                    CameraPrefs.DateMode dateMode,
                                    CameraPrefs.AspectRatio aspect,
                                    int frameNumber,
                                    String customNote) {
        return applyPaper(context, photo, preset, paperStyle, dateMode, aspect, frameNumber, customNote, null);
    }

    /** Adds print border (including 35mm sprocket carrier + EXIF telemetry), frame badge, note, and LED date. */
    public static Bitmap applyPaper(Context context, Bitmap photo, FilmPreset preset,
                                    CameraPrefs.PaperStyle paperStyle,
                                    CameraPrefs.DateMode dateMode,
                                    CameraPrefs.AspectRatio aspect,
                                    int frameNumber,
                                    String customNote,
                                    String exifTelemetry) {
        return applyPaper(context, photo, preset, paperStyle, dateMode, aspect,
                frameNumber, customNote, exifTelemetry, null);
    }

    /** Full print compositor supporting {@link CameraPrefs.PaperStyle#EXIF_FRAME} camera nameplate metadata. */
    public static Bitmap applyPaper(Context context, Bitmap photo, FilmPreset preset,
                                    CameraPrefs.PaperStyle paperStyle,
                                    CameraPrefs.DateMode dateMode,
                                    CameraPrefs.AspectRatio aspect,
                                    int frameNumber,
                                    String customNote,
                                    String exifTelemetry,
                                    ExifSpec exifSpec) {
        boolean hasFrame = paperStyle != null && paperStyle != CameraPrefs.PaperStyle.NONE;
        boolean exifNameplate = paperStyle == CameraPrefs.PaperStyle.EXIF_FRAME;
        String stampText = exifNameplate ? "" : dateString(dateMode);
        boolean hasStamp = !stampText.isEmpty();
        if (!hasFrame && !hasStamp) return photo;

        int w = photo.getWidth();
        int h = photo.getHeight();
        // Typography is scaled off the short edge, matching the border geometry above.
        // Scaling off the long edge made the date stamp and footer explode on XPAN.
        int shortSide = Math.min(w, h);
        int[] geo = computePaperGeometry(w, h, paperStyle, aspect);
        int pad = geo[0];
        int footer = geo[1];
        int outW = geo[2];
        int outH = geo[3];

        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        boolean sprocket = paperStyle == CameraPrefs.PaperStyle.SPROCKET_35MM;
        boolean darkPaper = sprocket || paperStyle == CameraPrefs.PaperStyle.BLACK;
        int bgColor = sprocket ? 0xFF13110F
                : (darkPaper ? 0xFF171513 : (exifNameplate ? 0xFFF6F2E9 : preset.paperColor));
        canvas.drawColor(bgColor);
        canvas.drawBitmap(photo, pad, pad, null);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (hasFrame) {
            // Crisp hairline around the photograph so light skies separate cleanly from paper.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, shortSide * 0.0014f));
            paint.setColor(darkPaper ? 0x33EFE7D6 : 0x1E1A1714);
            canvas.drawRect(pad, pad, pad + w, pad + h, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        paint.setTypeface(FilmUiHelper.monoFont(context));

        if (sprocket) {
            draw35mmSprockets(canvas, paint, outW, outH, pad, shortSide, preset, frameNumber);
        }

        if (hasStamp) {
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setColor(0xFFF2A65A);
            paint.setTextSize(shortSide * 0.040f);
            paint.setShadowLayer(shortSide * 0.006f, 0f, 0f, 0x80000000);
            canvas.drawText(stampText, pad + w * 0.952f, pad + h - h * 0.038f, paint);
            paint.clearShadowLayer();
        }

        if (exifNameplate) {
            drawExifNameplateFooter(canvas, paint, pad, w, h, outH, shortSide,
                    preset, dateMode, frameNumber, customNote, exifTelemetry, exifSpec);
        } else if (hasFrame) {
            int inkColor = sprocket ? 0xFFD99A4E : (darkPaper ? 0xFFB0A695 : 0xFF7D705C);
            float baseY = sprocket
                    ? (pad + h + (outH - (pad + h)) * 0.36f)
                    : (outH - footer * (aspect == CameraPrefs.AspectRatio.RATIO_1_1 ? 0.42f : 0.37f));

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(inkColor);
            paint.setTextSize(shortSide * (sprocket ? 0.021f : 0.024f));
            String leftLabel = frameNumber > 0
                    ? String.format(Locale.US, "%s  ·  NO.%02d", preset.name, frameNumber)
                    : preset.name;
            canvas.drawText(leftLabel, pad + w * 0.01f, baseY, paint);

            String note = customNote == null ? "" : customNote.trim();
            String rightText = !note.isEmpty() ? note : (sprocket && exifTelemetry != null ? exifTelemetry : "");
            if (!rightText.isEmpty()) {
                paint.setTextAlign(Paint.Align.RIGHT);
                paint.setColor(darkPaper ? 0xFFEFE7D6 : 0xFF473F35);
                paint.setTextSize(shortSide * (sprocket ? 0.021f : 0.025f));
                if (rightText.length() > 36) rightText = rightText.substring(0, 35) + "…";
                canvas.drawText(rightText, pad + w * 0.99f, baseY, paint);
            }
        }
        return out;
    }

    private static void drawExifNameplateFooter(Canvas canvas, Paint paint,
                                                int pad, int w, int h, int outH, int shortSide,
                                                FilmPreset preset, CameraPrefs.DateMode dateMode,
                                                int frameNumber, String customNote,
                                                String exifTelemetry, ExifSpec exifSpec) {
        float bandTop = pad + h;
        float bandH = outH - bandTop;
        float row1Y = bandTop + bandH * 0.44f;
        float row2Y = bandTop + bandH * 0.77f;

        String fallbackIso = preset != null ? preset.isoTag : "400";
        String specLine;
        if (exifSpec != null) {
            specLine = exifSpec.toSpecLine(fallbackIso);
        } else if (exifTelemetry != null && !exifTelemetry.trim().isEmpty()) {
            specLine = exifTelemetry.trim();
        } else {
            specLine = "35mm  ·  ISO " + fallbackIso;
        }

        String subRight;
        if (exifSpec != null && !exifSpec.cameraLabel().isEmpty()) {
            subRight = exifSpec.cameraLabel();
        } else {
            subRight = String.format(Locale.US, "35MM FILM  ·  ISO %s", fallbackIso);
        }

        // Right column first so we know exact remaining width for the left title/subline.
        paint.setFakeBoldText(true);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setColor(0xFF1C1917);
        paint.setTextSize(Math.max(12f, shortSide * 0.0225f));
        float rightX = pad + w;
        canvas.drawText(specLine, rightX, row1Y, paint);
        float rightTopWidth = paint.measureText(specLine);

        paint.setFakeBoldText(false);
        paint.setColor(0xFF9A6B3B);
        paint.setTextSize(Math.max(10f, shortSide * 0.0178f));
        canvas.drawText(subRight, rightX, row2Y, paint);
        float rightBotWidth = paint.measureText(subRight);

        float rightOccupied = Math.max(rightTopWidth, rightBotWidth);
        float dividerGap = shortSide * 0.024f;
        float divX = rightX - rightOccupied - dividerGap;
        if (divX > pad + w * 0.34f) {
            paint.setStrokeWidth(Math.max(1f, shortSide * 0.0014f));
            paint.setColor(0x241C1917);
            canvas.drawLine(divX, bandTop + bandH * 0.22f, divX, bandTop + bandH * 0.84f, paint);
        }

        // Emblem vermilion dot + bold stock/title on left row 1.
        float dotR = Math.max(3f, shortSide * 0.0068f);
        float dotCx = pad + dotR;
        float dotCy = row1Y - shortSide * 0.0080f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFD25A34);
        canvas.drawCircle(dotCx, dotCy, dotR, paint);

        float leftX = dotCx + dotR + shortSide * 0.012f;
        float maxLeftW = Math.max(shortSide * 0.22f, (divX - dividerGap) - leftX);
        String stockTitle = preset != null ? preset.name : "OLD TIME";
        String note = customNote == null ? "" : customNote.trim();
        String title = note.isEmpty() ? stockTitle : (stockTitle + "  ·  " + note);

        paint.setFakeBoldText(true);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(0xFF1C1917);
        paint.setTextSize(Math.max(12f, shortSide * 0.0240f));
        canvas.drawText(ellipsizeToWidth(paint, title, maxLeftW), leftX, row1Y, paint);

        // Left row 2: film signature + frame number + capture date.
        paint.setFakeBoldText(false);
        paint.setColor(0xFF787066);
        paint.setTextSize(Math.max(10f, shortSide * 0.0182f));
        StringBuilder subLeft = new StringBuilder("OLD TIME");
        if (frameNumber > 0) {
            subLeft.append(String.format(Locale.US, "  ·  NO.%02d", frameNumber));
        }
        String capDate = formatExifCaptureDate(
                exifSpec != null ? exifSpec.dateTimeRaw : null, dateMode);
        if (!capDate.isEmpty()) {
            subLeft.append("  ·  ").append(capDate);
        }
        canvas.drawText(ellipsizeToWidth(paint, subLeft.toString(),
                Math.max(shortSide * 0.24f, (divX - dividerGap) - pad)), pad, row2Y, paint);
    }

    private static String ellipsizeToWidth(Paint paint, String text, float maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0f) return "";
        if (paint.measureText(text) <= maxWidth) return text;
        int len = text.length();
        while (len > 1 && paint.measureText(text, 0, len) + paint.measureText("…") > maxWidth) {
            len--;
        }
        return text.substring(0, len).trim() + "…";
    }

    private static void draw35mmSprockets(Canvas canvas, Paint paint, int outW, int outH,
                                          int pad, int shortSide, FilmPreset preset, int frameNumber) {
        float holeH = Math.max(8f, shortSide * 0.025f);
        float holeW = holeH * 1.45f;
        float step = holeW * 1.85f;
        float corner = holeH * 0.24f;
        float topY = Math.max(4f, pad * 0.26f);
        float botY = outH - topY - holeH;

        paint.setColor(0xFFDCD3C2);
        RectF r = new RectF();
        for (float x = pad; x + holeW <= outW - pad; x += step) {
            r.set(x, topY, x + holeW, topY + holeH);
            canvas.drawRoundRect(r, corner, corner, paint);
            r.set(x, botY, x + holeW, botY + holeH);
            canvas.drawRoundRect(r, corner, corner, paint);
        }

        // Authentic subtle amber 35mm edge rebate code below top sprockets.
        float codeY = pad * 0.85f;
        if (codeY > topY + holeH + 4f) {
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(0xAAF2A65A);
            paint.setTextSize(Math.max(9f, shortSide * 0.016f));
            String code = String.format(Locale.US, "OTC DX  ·  %s  ·  %02dA",
                    preset != null ? preset.isoTag : "400", Math.max(1, frameNumber));
            canvas.drawText(code, pad, codeY, paint);
        }
    }

    /** Half-frame pairs portrait captures side-by-side and landscape captures top-over-bottom. */
    public static boolean shouldPairHalfFrameSideBySide(boolean landscapeCapture) {
        return !landscapeCapture;
    }

    /**
     * Composes two developed half-frame exposures into one cohesive analog diptych with a matte
     * carbon divider and frame edge indices before applying paper border.
     */
    public static Bitmap composeHalfFrameDiptych(Context context, Bitmap first, Bitmap second,
                                                 boolean sideBySide, int frameIndex) {
        if (first == null) return second;
        if (second == null) return first;
        int w1 = first.getWidth();
        int h1 = first.getHeight();
        Bitmap alignedSecond = second;
        if (second.getWidth() != w1 || second.getHeight() != h1) {
            int[] crop = computeCropBounds(second.getWidth(), second.getHeight(), (float) w1 / h1);
            Bitmap tmp = (crop[0] == 0 && crop[1] == 0 && crop[2] == second.getWidth() && crop[3] == second.getHeight())
                    ? second
                    : Bitmap.createBitmap(second, crop[0], crop[1], crop[2], crop[3]);
            alignedSecond = (tmp.getWidth() == w1 && tmp.getHeight() == h1)
                    ? tmp
                    : Bitmap.createScaledBitmap(tmp, w1, h1, true);
            if (tmp != second && tmp != alignedSecond) tmp.recycle();
        }

        // Cap individual cell to keep total composite well inside 2560px texture limits.
        final int cellLimit = 1160;
        Bitmap cell1 = capEdgeNoRecycle(first, cellLimit);
        Bitmap cell2 = capEdgeNoRecycle(alignedSecond, cellLimit);
        if (alignedSecond != second && alignedSecond != cell2 && !alignedSecond.isRecycled()) {
            alignedSecond.recycle();
        }

        int cw = cell1.getWidth();
        int ch = cell1.getHeight();
        int shortEdge = Math.min(cw, ch);
        int gap = Math.max(8, Math.round(shortEdge * 0.026f));
        int outer = Math.max(12, Math.round(shortEdge * 0.030f));

        int outW = sideBySide ? (outer * 2 + cw * 2 + gap) : (outer * 2 + cw);
        int outH = sideBySide ? (outer * 2 + ch) : (outer * 2 + ch * 2 + gap);

        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0xFF13110F);

        canvas.drawBitmap(cell1, outer, outer, null);
        int secondX = sideBySide ? (outer + cw + gap) : outer;
        int secondY = sideBySide ? outer : (outer + ch + gap);
        canvas.drawBitmap(cell2, secondX, secondY, null);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(FilmUiHelper.monoFont(context));
        p.setColor(0xCCF2A65A);
        p.setTextSize(Math.max(12f, shortEdge * 0.024f));
        p.setTextAlign(Paint.Align.LEFT);
        int baseNum = Math.max(1, (frameIndex + 1) / 2);
        canvas.drawText(String.format(Locale.US, "%02dA", baseNum), outer + 6f, outer + ch - 8f, p);
        canvas.drawText(String.format(Locale.US, "%02dB", baseNum), secondX + 6f, secondY + ch - 8f, p);

        if (cell1 != first) cell1.recycle();
        if (cell2 != second) cell2.recycle();
        return out;
    }

    /**
     * Arranges 2–9 frames onto a darkroom 35mm contact sheet complete with
     * sprocket holes, amber frame indices, and roll stamp.
     */
    public static Uri buildContactSheet(Context context, List<Uri> uris, int rollNumber)
            throws IOException {
        if (uris == null || uris.isEmpty()) {
            throw new IllegalArgumentException("No frames selected");
        }
        int count = Math.min(9, uris.size());
        int cols = count <= 4 ? 2 : 3;
        int rows = (count + cols - 1) / cols;

        int sheetW = 1600;
        int outerPad = 72;
        int gap = 28;
        int perfBand = 56;
        int headerH = 92;
        int footerH = 74;
        int cellW = (sheetW - outerPad * 2 - gap * (cols - 1)) / cols;
        int cellH = Math.round(cellW * 1.15f);
        int cellLabelH = 34;

        int sheetH = perfBand * 2 + headerH + rows * (cellH + cellLabelH)
                + Math.max(0, rows - 1) * gap + footerH;

        Bitmap out = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0xFF12100E);

        // Top and bottom 35mm film sprocket holes.
        Paint perfPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        perfPaint.setColor(0xFFDCD3C2);
        float holeW = 28f;
        float holeH = 18f;
        float holeGap = 24f;
        float topY = (perfBand - holeH) * 0.5f + 4f;
        float botY = sheetH - perfBand + (perfBand - holeH) * 0.5f - 4f;
        RectF r = new RectF();
        for (float x = 34f; x + holeW < sheetW - 24f; x += holeW + holeGap) {
            r.set(x, topY, x + holeW, topY + holeH);
            canvas.drawRoundRect(r, 4f, 4f, perfPaint);
            r.set(x, botY, x + holeW, botY + holeH);
            canvas.drawRoundRect(r, 4f, 4f, perfPaint);
        }

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(FilmUiHelper.monoFont(context));
        textPaint.setColor(0xFFF0E8D8);
        textPaint.setTextSize(26f);
        canvas.drawText(String.format(Locale.US, "ROLL %02d  ·  CONTACT SHEET", rollNumber),
                outerPad, perfBand + 54f, textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(0xFFF2A65A);
        textPaint.setTextSize(22f);
        String today = new SimpleDateFormat("yyyy.MM.dd", Locale.US).format(new Date());
        canvas.drawText(today, sheetW - outerPad, perfBand + 54f, textPaint);

        Paint cellBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        cellBorder.setStyle(Paint.Style.STROKE);
        cellBorder.setStrokeWidth(2f);
        cellBorder.setColor(0x33EFE7D6);

        Paint cellBg = new Paint();
        cellBg.setColor(0xFF1C1916);

        for (int i = 0; i < count; i++) {
            int col = i % cols;
            int row = i / cols;
            int left = outerPad + col * (cellW + gap);
            int top = perfBand + headerH + row * (cellH + cellLabelH + gap);

            canvas.drawRect(left, top, left + cellW, top + cellH, cellBg);
            Bitmap frameBmp = decodeScaledUri(context, uris.get(i), Math.max(cellW, cellH));
            if (frameBmp != null) {
                float scale = Math.min((float) cellW / frameBmp.getWidth(),
                        (float) cellH / frameBmp.getHeight());
                float dw = frameBmp.getWidth() * scale;
                float dh = frameBmp.getHeight() * scale;
                float dx = left + (cellW - dw) * 0.5f;
                float dy = top + (cellH - dh) * 0.5f;
                RectF dst = new RectF(dx, dy, dx + dw, dy + dh);
                canvas.drawBitmap(frameBmp, null, dst, null);
                frameBmp.recycle();
            }
            canvas.drawRect(left, top, left + cellW, top + cellH, cellBorder);

            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(0xFFF2A65A);
            textPaint.setTextSize(20f);
            canvas.drawText(String.format(Locale.US, "%02dA", i + 1),
                    left + 4f, top + cellH + 24f, textPaint);
        }

        Uri saved = save(context, out);
        out.recycle();
        return saved;
    }

    public static Uri save(Context context, Bitmap bitmap) throws IOException {
        return save(context, bitmap, null, null, null);
    }

    public static Uri save(Context context, Bitmap bitmap, byte[] sourceExifJpeg) throws IOException {
        return save(context, bitmap, sourceExifJpeg, null, null);
    }

    /**
     * Writes the print into the system gallery.
     *
     * @param recipeComment optional analog recipe string recorded in EXIF UserComment
     */
    public static Uri save(Context context, Bitmap bitmap, byte[] sourceExifJpeg,
                           String recipeComment) throws IOException {
        return save(context, bitmap, sourceExifJpeg, null, recipeComment);
    }

    /**
     * Writes the print into the system gallery, optionally copying EXIF camera attributes from
     * either raw JPEG bytes or an imported source {@link Uri}.
     */
    public static Uri save(Context context, Bitmap bitmap, byte[] sourceExifJpeg,
                           Uri sourceExifUri, String recipeComment) throws IOException {
        String name = "OTC_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                + ".jpg";
        ContentResolver resolver = context.getContentResolver();

        File cacheDir = context.getCacheDir();
        File tempFile = null;
        if (cacheDir != null) {
            tempFile = File.createTempFile("otc_print_", ".jpg", cacheDir);
        }

        try {
            if (tempFile != null) {
                try (OutputStream tos = new FileOutputStream(tempFile)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, tos)) {
                        throw new IOException("JPEG compression failed");
                    }
                }
                applyAnalogExif(context, tempFile, sourceExifJpeg, sourceExifUri, recipeComment);
            }

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/OldTimeCamera");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("MediaStore insert failed");
                try {
                    try (OutputStream os = resolver.openOutputStream(uri)) {
                        if (os == null) throw new IOException("Cannot open output stream");
                        if (tempFile != null) {
                            copyFileToStream(tempFile, os);
                        } else if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)) {
                            throw new IOException("JPEG compression failed");
                        }
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Images.Media.IS_PENDING, 0);
                    resolver.update(uri, done, null, null);
                    return uri;
                } catch (IOException | RuntimeException e) {
                    // A half-written pending row would linger in the gallery forever
                    // (and would be invisible to our own album query).
                    try {
                        resolver.delete(uri, null, null);
                    } catch (RuntimeException ignored) {
                    }
                    throw e;
                }
            }

            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "OldTimeCamera");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("Cannot create album directory");
            }
            File file = new File(dir, name);
            try (OutputStream os = new FileOutputStream(file)) {
                if (tempFile != null) {
                    copyFileToStream(tempFile, os);
                } else if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)) {
                    throw new IOException("JPEG compression failed");
                }
            }
            if (tempFile == null) {
                applyAnalogExif(context, file, sourceExifJpeg, sourceExifUri, recipeComment);
            }
            MediaScannerConnection.scanFile(context,
                    new String[]{file.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            // API 26-28: a file:// URI would throw FileUriExposedException the moment it is
            // shared, so hand out a content:// URI through our FileProvider instead.
            return FileProvider.getUriForFile(context, fileProviderAuthority(context), file);
        } finally {
            if (tempFile != null && tempFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
    }

    /** Resolves the persisted MediaStore {@code DISPLAY_NAME} (or file basename) of a saved print. */
    public static String resolveDisplayName(Context context, Uri uri) {
        if (uri == null) return "";
        if (context != null && "content".equals(uri.getScheme())) {
            try (Cursor c = context.getContentResolver().query(
                    uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String dn = c.getString(0);
                    if (dn != null && !dn.trim().isEmpty()) {
                        return dn.trim();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "";
    }

    public static String fileProviderAuthority(Context context) {
        return context.getPackageName() + ".fileprovider";
    }

    private static void copyFileToStream(File src, OutputStream os) throws IOException {
        byte[] buf = new byte[16384];
        try (java.io.InputStream is = new java.io.FileInputStream(src)) {
            int n;
            while ((n = is.read(buf)) >= 0) {
                if (n > 0) os.write(buf, 0, n);
            }
        }
    }

    /**
     * Builds the analog recipe written into EXIF {@code UserComment}, so a print can be
     * traced back to the stock and texture settings that produced it.
     */
    public static String buildRecipeComment(FilmPreset preset, boolean grainBoost,
                                            boolean leakEnabled, int rollNumber,
                                            int frameNumber, int rotationDegrees) {
        return buildRecipeComment(preset, grainBoost, leakEnabled, rollNumber,
                frameNumber, rotationDegrees, null, false, CameraPrefs.ShootMode.SINGLE);
    }

    /** Extended analog recipe EXIF UserComment carrying custom recipe, mist, and shooting mode. */
    public static String buildRecipeComment(FilmPreset preset, boolean grainBoost,
                                            boolean leakEnabled, int rollNumber,
                                            int frameNumber, int rotationDegrees,
                                            FilmRecipe recipe, boolean blackMist,
                                            CameraPrefs.ShootMode shootMode) {
        if (preset == null) return null;
        StringBuilder sb = new StringBuilder(String.format(Locale.US,
                "OTC|stock=%s|grain=%s|leak=%s|roll=%02d|frame=%02d|rot=%03d",
                preset.id,
                grainBoost ? "boost" : "normal",
                leakEnabled ? "on" : "off",
                Math.max(1, rollNumber),
                Math.max(1, frameNumber),
                ((rotationDegrees % 360) + 360) % 360));
        if (recipe != null && !recipe.isDefault()) {
            sb.append('|').append(recipe.toExifTag());
        }
        if (blackMist) {
            sb.append("|mist=on");
        }
        if (shootMode != null && shootMode != CameraPrefs.ShootMode.SINGLE) {
            sb.append("|mode=").append(shootMode.name().toLowerCase(Locale.US));
        }
        return sb.toString();
    }

    /** Structured EXIF metadata snapshot for {@link CameraPrefs.PaperStyle#EXIF_FRAME}. */
    public static final class ExifSpec {
        public final String focal35mmRaw;
        public final String focalRaw;
        public final String fNumberRaw;
        public final String exposureSecRaw;
        public final String isoRaw;
        public final String dateTimeRaw;
        public final String makeRaw;
        public final String modelRaw;

        public ExifSpec(String focal35mmRaw, String focalRaw, String fNumberRaw,
                        String exposureSecRaw, String isoRaw, String dateTimeRaw,
                        String makeRaw, String modelRaw) {
            this.focal35mmRaw = focal35mmRaw;
            this.focalRaw = focalRaw;
            this.fNumberRaw = fNumberRaw;
            this.exposureSecRaw = exposureSecRaw;
            this.isoRaw = isoRaw;
            this.dateTimeRaw = dateTimeRaw;
            this.makeRaw = makeRaw;
            this.modelRaw = modelRaw;
        }

        public String toSpecLine(String fallbackIsoTag) {
            return formatExifSpecLine(focal35mmRaw, focalRaw, fNumberRaw,
                    exposureSecRaw, isoRaw, fallbackIsoTag);
        }

        public String cameraLabel() {
            String make = makeRaw == null ? "" : makeRaw.trim();
            String model = modelRaw == null ? "" : modelRaw.trim();
            if (model.isEmpty() && make.isEmpty()) return "";
            String combined;
            if (model.isEmpty()) {
                combined = make;
            } else if (make.isEmpty()
                    || model.toUpperCase(Locale.US).startsWith(make.toUpperCase(Locale.US))) {
                combined = model;
            } else {
                combined = make + " " + model;
            }
            combined = combined.toUpperCase(Locale.US).replaceAll("\\s+", " ").trim();
            if (combined.length() > 26) {
                combined = combined.substring(0, 25).trim() + "…";
            }
            return combined;
        }
    }

    /** Parses decimal or EXIF rational ({@code "num/den"}) numbers, returning {@code -1} if invalid. */
    public static double parseExifNumber(String raw) {
        if (raw == null) return -1.0;
        String s = raw.trim();
        if (s.isEmpty()) return -1.0;
        int comma = s.indexOf(',');
        if (comma > 0) s = s.substring(0, comma).trim();
        int slash = s.indexOf('/');
        try {
            if (slash > 0) {
                double num = Double.parseDouble(s.substring(0, slash).trim());
                double den = Double.parseDouble(s.substring(slash + 1).trim());
                return den > 0.0 ? (num / den) : -1.0;
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return -1.0;
        }
    }

    /** Pure-Java magazine camera spec formatter: {@code "26mm  f/1.8  1/120s  ISO 200"}. */
    public static String formatExifSpecLine(String focal35mmRaw, String focalRaw,
                                            String fNumberRaw, String exposureSecRaw,
                                            String isoRaw, String fallbackIsoTag) {
        List<String> parts = new ArrayList<>(4);
        double f35 = parseExifNumber(focal35mmRaw);
        double fPhys = parseExifNumber(focalRaw);
        if (f35 >= 8.0 && f35 <= 800.0) {
            parts.add(Math.round(f35) + "mm");
        } else if (fPhys > 0.5 && fPhys <= 800.0) {
            if (fPhys >= 11.5 || Math.abs(fPhys - Math.round(fPhys)) < 0.06) {
                parts.add(Math.round(fPhys) + "mm");
            } else {
                parts.add(String.format(Locale.US, "%.1fmm", fPhys));
            }
        }

        double fn = parseExifNumber(fNumberRaw);
        if (fn > 0.5 && fn < 64.0) {
            parts.add(String.format(Locale.US, "f/%.1f", fn));
        }

        double sec = parseExifNumber(exposureSecRaw);
        if (sec > 0.0 && sec < 0.75) {
            long denom = Math.max(1L, Math.round(1.0 / sec));
            parts.add("1/" + denom + "s");
        } else if (sec >= 0.75 && sec < 3600.0) {
            parts.add(String.format(Locale.US, "%.1fs", sec));
        }

        boolean hasOpticalTag = !parts.isEmpty();
        String isoPart = "";
        if (isoRaw != null && !isoRaw.trim().isEmpty()) {
            String cleanIso = isoRaw.trim().split(",")[0].trim();
            if (!cleanIso.isEmpty()) {
                isoPart = "ISO " + cleanIso;
            }
        }
        if (isoPart.isEmpty() && hasOpticalTag
                && fallbackIsoTag != null && !fallbackIsoTag.trim().isEmpty()) {
            String tag = fallbackIsoTag.trim();
            if (Character.isDigit(tag.charAt(0))) {
                isoPart = "ISO " + tag;
            }
        }
        if (!isoPart.isEmpty()) {
            parts.add(isoPart);
        }

        if (parts.isEmpty()) {
            String fb = (fallbackIsoTag != null && !fallbackIsoTag.trim().isEmpty())
                    ? fallbackIsoTag.trim() : "400";
            return "35mm  ·  " + (Character.isDigit(fb.charAt(0)) ? "ISO " + fb : fb);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append("  ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** Formats capture timestamp for {@link CameraPrefs.PaperStyle#EXIF_FRAME}, honoring DateMode. */
    public static String formatExifCaptureDate(String exifDateTimeRaw, CameraPrefs.DateMode dateMode) {
        if (dateMode == CameraPrefs.DateMode.OFF) return "";
        if (exifDateTimeRaw != null) {
            String s = exifDateTimeRaw.trim();
            // Standard EXIF format: "YYYY:MM:DD HH:MM:SS"
            if (s.length() >= 16 && s.charAt(4) == ':' && s.charAt(7) == ':') {
                String yyyy = dateMode == CameraPrefs.DateMode.RETRO_98 ? "1998" : s.substring(0, 4);
                String mm = s.substring(5, 7);
                String dd = s.substring(8, 10);
                String hhmm = s.substring(11, 16);
                return yyyy + "." + mm + "." + dd + " " + hhmm;
            }
        }
        Date now = new Date();
        if (dateMode == CameraPrefs.DateMode.RETRO_98) {
            return "1998." + new SimpleDateFormat("MM.dd HH:mm", Locale.US).format(now);
        }
        return new SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.US).format(now);
    }

    /** Pure-Java formatter turning raw EXIF strings into a compact analog meter line. */
    public static String formatShortExifStrings(String exposureSecRaw, String fNumberRaw, String isoRaw) {
        StringBuilder sb = new StringBuilder();
        double sec = parseExifNumber(exposureSecRaw);
        if (sec > 0.0 && sec < 0.75) {
            long denom = Math.max(1L, Math.round(1.0 / sec));
            sb.append("1/").append(denom).append('s');
        } else if (sec >= 0.75) {
            sb.append(String.format(Locale.US, "%.1fs", sec));
        }
        double fn = parseExifNumber(fNumberRaw);
        if (fn > 0.0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(Locale.US, "f/%.1f", fn));
        }
        if (isoRaw != null && !isoRaw.trim().isEmpty()) {
            String cleanIso = isoRaw.trim().split(",")[0].trim();
            if (!cleanIso.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("ISO ").append(cleanIso);
            }
        }
        return sb.toString();
    }

    private static ExifSpec readExifSpecFromInterface(android.media.ExifInterface src) {
        if (src == null) return null;
        String dt = src.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL);
        if (dt == null || dt.trim().isEmpty()) {
            dt = src.getAttribute(android.media.ExifInterface.TAG_DATETIME);
        }
        return new ExifSpec(
                src.getAttribute(android.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM),
                src.getAttribute(android.media.ExifInterface.TAG_FOCAL_LENGTH),
                src.getAttribute(android.media.ExifInterface.TAG_F_NUMBER),
                src.getAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME),
                src.getAttribute(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS),
                dt,
                src.getAttribute(android.media.ExifInterface.TAG_MAKE),
                src.getAttribute(android.media.ExifInterface.TAG_MODEL));
    }

    public static ExifSpec extractExifSpec(byte[] sourceExifJpeg) {
        if (sourceExifJpeg == null || sourceExifJpeg.length == 0) return null;
        try {
            return readExifSpecFromInterface(new android.media.ExifInterface(
                    new java.io.ByteArrayInputStream(sourceExifJpeg)));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static ExifSpec extractExifSpec(Context context, Uri uri) {
        if (context == null || uri == null) return null;
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            try {
                return readExifSpecFromInterface(new android.media.ExifInterface(uri.getPath()));
            } catch (Exception ignored) {
                return null;
            }
        }
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            return readExifSpecFromInterface(new android.media.ExifInterface(is));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String extractShortExifTelemetry(byte[] sourceExifJpeg) {
        if (sourceExifJpeg == null || sourceExifJpeg.length == 0) return "";
        try {
            android.media.ExifInterface src = new android.media.ExifInterface(
                    new java.io.ByteArrayInputStream(sourceExifJpeg));
            return formatShortExifStrings(
                    src.getAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME),
                    src.getAttribute(android.media.ExifInterface.TAG_F_NUMBER),
                    src.getAttribute(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS));
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String readDisplayExifSummary(Context context, Uri uri) {
        if (context == null || uri == null) return "";
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return "";
            android.media.ExifInterface exif = new android.media.ExifInterface(is);
            return formatShortExifStrings(
                    exif.getAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME),
                    exif.getAttribute(android.media.ExifInterface.TAG_F_NUMBER),
                    exif.getAttribute(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS));
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Copies camera EXIF metadata onto a cached master negative file in {@link NegativeVault}. */
    public static void copyCameraExifToNegative(File negFile, byte[] sourceExifJpeg) {
        if (negFile == null || !negFile.isFile()) return;
        applyAnalogExif(null, negFile, sourceExifJpeg, null, null);
    }

    private static final String[] COPYABLE_EXIF_TAGS = {
            android.media.ExifInterface.TAG_MAKE,
            android.media.ExifInterface.TAG_MODEL,
            android.media.ExifInterface.TAG_DATETIME,
            android.media.ExifInterface.TAG_DATETIME_ORIGINAL,
            android.media.ExifInterface.TAG_DATETIME_DIGITIZED,
            android.media.ExifInterface.TAG_EXPOSURE_TIME,
            android.media.ExifInterface.TAG_F_NUMBER,
            android.media.ExifInterface.TAG_ISO_SPEED_RATINGS,
            android.media.ExifInterface.TAG_FOCAL_LENGTH,
            android.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            android.media.ExifInterface.TAG_WHITE_BALANCE,
            android.media.ExifInterface.TAG_FLASH
    };

    private static void copyTags(android.media.ExifInterface src, android.media.ExifInterface dst) {
        if (src == null || dst == null) return;
        for (String tag : COPYABLE_EXIF_TAGS) {
            String value = src.getAttribute(tag);
            if (value != null && !value.isEmpty()) {
                dst.setAttribute(tag, value);
            }
        }
    }

    private static void applyAnalogExif(Context context, File outFile,
                                        byte[] sourceExifJpeg, Uri sourceExifUri,
                                        String recipeComment) {
        try {
            android.media.ExifInterface outExif =
                    new android.media.ExifInterface(outFile.getAbsolutePath());
            if (sourceExifJpeg != null && sourceExifJpeg.length > 0) {
                copyTags(new android.media.ExifInterface(
                        new java.io.ByteArrayInputStream(sourceExifJpeg)), outExif);
            } else if (sourceExifUri != null) {
                if ("file".equals(sourceExifUri.getScheme()) && sourceExifUri.getPath() != null) {
                    copyTags(new android.media.ExifInterface(sourceExifUri.getPath()), outExif);
                } else if (context != null) {
                    try (InputStream is = context.getContentResolver().openInputStream(sourceExifUri)) {
                        if (is != null) {
                            copyTags(new android.media.ExifInterface(is), outExif);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            outExif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,
                    String.valueOf(android.media.ExifInterface.ORIENTATION_NORMAL));
            outExif.setAttribute(android.media.ExifInterface.TAG_SOFTWARE, "OldTimeCamera");
            if (recipeComment != null && !recipeComment.isEmpty()) {
                outExif.setAttribute(android.media.ExifInterface.TAG_USER_COMMENT, recipeComment);
            }
            outExif.saveAttributes();
        } catch (Exception ignored) {
            // Do not fail print saving if a vendor HAL produced unparseable EXIF bytes.
        }
    }

    /** Non-null when the last album query failed for a reason other than an empty album. */
    public static volatile String lastQueryError = null;

    public static List<PhotoItem> loadAlbumPhotos(Context context) {
        List<PhotoItem> items = new ArrayList<>();
        lastQueryError = null;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            String[] projection = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED
            };
            String selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? AND "
                    + MediaStore.Images.Media.IS_PENDING + "=0";
            String[] args = {"%OldTimeCamera%"};
            String sort = MediaStore.Images.Media.DATE_ADDED + " DESC";
            try (Cursor cursor = resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, args, sort)) {
                if (cursor != null) {
                    int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                    int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idCol);
                        Uri uri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                        String name = cursor.getString(nameCol);
                        long dateSec = cursor.getLong(dateCol);
                        items.add(new PhotoItem(uri, name, dateSec * 1000L));
                    }
                }
            } catch (Exception e) {
                // Do not disguise a permission/OEM failure as "no photos": the UI needs
                // to tell the difference between an empty album and a broken query.
                lastQueryError = e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "");
            }
            return items;
        }

        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "OldTimeCamera");
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : files) {
                String lower = f.getName().toLowerCase(Locale.US);
                if (f.isFile() && (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
                    items.add(new PhotoItem(Uri.fromFile(f), f.getName(), f.lastModified()));
                }
            }
        }
        return items;
    }

    public static Bitmap decodeScaledUri(Context context, Uri uri, int maxEdge) {
        if (uri == null) return null;
        try {
            ContentResolver resolver = context.getContentResolver();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = resolver.openInputStream(uri)) {
                if (is == null) return null;
                BitmapFactory.decodeStream(is, null, bounds);
            }
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            if (longest <= 0) return null;
            // Pick the smallest power-of-two sample that still covers maxEdge. The previous
            // 2x tolerance decoded a full 4000px lightbox image into ~48MB before scaling.
            int sample = 1;
            while (longest / sample > maxEdge) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded;
            try (InputStream is = resolver.openInputStream(uri)) {
                if (is == null) return null;
                decoded = BitmapFactory.decodeStream(is, null, opts);
            }
            if (decoded == null) return null;

            if (Math.max(decoded.getWidth(), decoded.getHeight()) > maxEdge) {
                decoded = capEdge(decoded, maxEdge);
            }

            int[] exifTx = readExifTransform(resolver, uri);
            if (exifTx[0] != 0 || exifTx[1] != 0) {
                Matrix m = new Matrix();
                if (exifTx[1] != 0) m.postScale(-1f, 1f);
                if (exifTx[0] != 0) m.postRotate(exifTx[0]);
                Bitmap rotated = Bitmap.createBitmap(
                        decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
                if (rotated != decoded) decoded.recycle();
                decoded = rotated;
            }
            return decoded;
        } catch (OutOfMemoryError oom) {
            // A single oversized frame must degrade to "no preview", never kill the process.
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int[] readExifTransform(ContentResolver resolver, Uri uri) {
        try (InputStream is = resolver.openInputStream(uri)) {
            if (is == null) return new int[]{0, 0};
            android.media.ExifInterface exif = new android.media.ExifInterface(is);
            int o = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            return CameraTransformMath.exifOrientationToTransform(o);
        } catch (Exception ignored) {
        }
        return new int[]{0, 0};
    }
}
