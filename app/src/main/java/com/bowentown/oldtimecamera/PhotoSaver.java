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
     */
    public static float effectiveCropRatio(CameraPrefs.AspectRatio ratio, boolean landscapeCapture) {
        if (ratio == null) return 3f / 4f;
        if (landscapeCapture && ratio.widthOverHeight < 1f) {
            return 1f / ratio.widthOverHeight;
        }
        return ratio.widthOverHeight;
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

    /** Bilinearly scales a decoded bitmap so its longest side fits within maxEdge. */
    public static Bitmap capEdge(Bitmap src, int maxEdge) {
        if (src == null || maxEdge <= 0) return src;
        int curLongest = Math.max(src.getWidth(), src.getHeight());
        if (curLongest <= maxEdge) return src;
        float scale = (float) maxEdge / (float) curLongest;
        int nw = Math.max(1, Math.round(src.getWidth() * scale));
        int nh = Math.max(1, Math.round(src.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);
        if (scaled != src) src.recycle();
        return scaled;
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
        return applyPaper(null, photo, preset, paperStyle, dateMode, aspect, frameNumber, customNote);
    }

    /** Adds the selected print border, frame badge, optional note, and LED date stamp. */
    public static Bitmap applyPaper(Context context, Bitmap photo, FilmPreset preset,
                                    CameraPrefs.PaperStyle paperStyle,
                                    CameraPrefs.DateMode dateMode,
                                    CameraPrefs.AspectRatio aspect,
                                    int frameNumber,
                                    String customNote) {
        boolean hasFrame = paperStyle != CameraPrefs.PaperStyle.NONE;
        String stampText = dateString(dateMode);
        boolean hasStamp = !stampText.isEmpty();
        if (!hasFrame && !hasStamp) return photo;

        int w = photo.getWidth();
        int h = photo.getHeight();
        int[] geo = computePaperGeometry(w, h, paperStyle, aspect);
        int pad = geo[0];
        int footer = geo[1];
        int outW = geo[2];
        int outH = geo[3];

        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        boolean darkPaper = paperStyle == CameraPrefs.PaperStyle.BLACK;
        int bgColor = darkPaper ? 0xFF171513 : preset.paperColor;
        canvas.drawColor(bgColor);
        canvas.drawBitmap(photo, pad, pad, null);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (hasFrame) {
            // Crisp hairline around the photograph so light skies separate cleanly from paper.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, w * 0.0014f));
            paint.setColor(darkPaper ? 0x33EFE7D6 : 0x1A1A1714);
            canvas.drawRect(pad, pad, pad + w, pad + h, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        paint.setTypeface(FilmUiHelper.monoFont(context));

        if (hasStamp) {
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setColor(0xFFF2A65A);
            paint.setTextSize(w * 0.040f);
            paint.setShadowLayer(w * 0.006f, 0f, 0f, 0x80000000);
            canvas.drawText(stampText, pad + w * 0.952f, pad + h - h * 0.038f, paint);
            paint.clearShadowLayer();
        }

        if (hasFrame) {
            int inkColor = darkPaper ? 0xFFB0A695 : 0xFF7D705C;
            float baseY = outH - footer * (aspect == CameraPrefs.AspectRatio.RATIO_1_1 ? 0.42f : 0.37f);

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(inkColor);
            paint.setTextSize(w * 0.024f);
            String leftLabel = frameNumber > 0
                    ? String.format(Locale.US, "%s  ·  NO.%02d", preset.name, frameNumber)
                    : preset.name;
            canvas.drawText(leftLabel, pad + w * 0.01f, baseY, paint);

            String note = customNote == null ? "" : customNote.trim();
            if (!note.isEmpty()) {
                paint.setTextAlign(Paint.Align.RIGHT);
                paint.setColor(darkPaper ? 0xFFEFE7D6 : 0xFF473F35);
                paint.setTextSize(w * 0.025f);
                if (note.length() > 36) note = note.substring(0, 35) + "…";
                canvas.drawText(note, pad + w * 0.99f, baseY, paint);
            }
        }
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
        return save(context, bitmap, null);
    }

    public static Uri save(Context context, Bitmap bitmap, byte[] sourceExifJpeg) throws IOException {
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
                applyAnalogExif(tempFile, sourceExifJpeg);
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
                try (OutputStream os = resolver.openOutputStream(uri)) {
                    if (os == null) throw new IOException("Cannot open output stream");
                    if (tempFile != null) {
                        copyFileToStream(tempFile, os);
                    } else if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)) {
                        throw new IOException("JPEG compression failed");
                    }
                }
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                return uri;
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
                applyAnalogExif(file, sourceExifJpeg);
            }
            MediaScannerConnection.scanFile(context,
                    new String[]{file.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            return Uri.fromFile(file);
        } finally {
            if (tempFile != null && tempFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
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

    private static void applyAnalogExif(File outFile, byte[] sourceExifJpeg) {
        try {
            android.media.ExifInterface outExif =
                    new android.media.ExifInterface(outFile.getAbsolutePath());
            if (sourceExifJpeg != null && sourceExifJpeg.length > 0) {
                android.media.ExifInterface src = new android.media.ExifInterface(
                        new java.io.ByteArrayInputStream(sourceExifJpeg));
                String[] tags = {
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
                for (String tag : tags) {
                    String value = src.getAttribute(tag);
                    if (value != null && !value.isEmpty()) {
                        outExif.setAttribute(tag, value);
                    }
                }
            }
            outExif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,
                    String.valueOf(android.media.ExifInterface.ORIENTATION_NORMAL));
            outExif.setAttribute(android.media.ExifInterface.TAG_SOFTWARE, "OldTimeCamera");
            outExif.saveAttributes();
        } catch (Exception ignored) {
            // Do not fail print saving if a vendor HAL produced unparseable EXIF bytes.
        }
    }

    public static List<PhotoItem> loadAlbumPhotos(Context context) {
        List<PhotoItem> items = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            String[] projection = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED
            };
            String selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
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
            } catch (Exception ignored) {
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
            int sample = 1;
            int sampleLimit = maxEdge >= 1024 ? maxEdge * 2 : maxEdge;
            while (longest / sample > sampleLimit) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded;
            try (InputStream is = resolver.openInputStream(uri)) {
                if (is == null) return null;
                decoded = BitmapFactory.decodeStream(is, null, opts);
            }
            if (decoded == null) return null;

            if (maxEdge >= 1024) {
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
