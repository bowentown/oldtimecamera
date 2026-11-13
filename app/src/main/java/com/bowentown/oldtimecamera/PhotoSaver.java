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
import android.graphics.Typeface;
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

    public static Bitmap cropToAspect(Bitmap src, CameraPrefs.AspectRatio ratio) {
        if (src == null || ratio == null) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        float current = (float) w / (float) h;
        float target = ratio.widthOverHeight;
        if (Math.abs(current - target) < 0.015f) return src;

        int cropW = w;
        int cropH = h;
        if (current > target) {
            cropW = Math.max(1, Math.round(h * target));
        } else {
            cropH = Math.max(1, Math.round(w / target));
        }
        int x = Math.max(0, (w - cropW) / 2);
        int y = Math.max(0, (h - cropH) / 2);
        Bitmap cropped = Bitmap.createBitmap(src, x, y, cropW, cropH);
        if (cropped != src) src.recycle();
        return cropped;
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

    /** Adds the selected print border, frame badge, optional note, and LED date stamp. */
    public static Bitmap applyPaper(Bitmap photo, FilmPreset preset,
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
        int pad = hasFrame ? Math.round(w * (aspect == CameraPrefs.AspectRatio.RATIO_1_1 ? 0.055f : 0.036f)) : 0;
        int footer = hasFrame
                ? Math.round(w * (aspect == CameraPrefs.AspectRatio.RATIO_1_1 ? 0.185f : 0.115f))
                : 0;
        int outW = w + pad * 2;
        int outH = h + pad * 2 + footer;

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

        paint.setTypeface(Typeface.MONOSPACE);

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
            int mutedColor = darkPaper ? 0xFF787063 : 0xFFA29682;
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
            } else {
                paint.setTextAlign(Paint.Align.RIGHT);
                paint.setColor(mutedColor);
                paint.setTextSize(w * 0.021f);
                canvas.drawText("OLD TIME CAMERA", pad + w * 0.99f, baseY, paint);
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
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setColor(0xFFF0E8D8);
        textPaint.setTextSize(26f);
        canvas.drawText(String.format(Locale.US, "OLD TIME 400  ·  ROLL %02d  ·  CONTACT SHEET", rollNumber),
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

            textPaint.setTextAlign(Paint.Align.RIGHT);
            textPaint.setColor(0xFF7E7466);
            textPaint.setTextSize(18f);
            canvas.drawText("SAFETY FILM", left + cellW - 4f, top + cellH + 24f, textPaint);
        }

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(0xFF8C8273);
        textPaint.setTextSize(19f);
        canvas.drawText("OLD TIME CAMERA  ·  ARCHIVAL PRINT",
                sheetW * 0.5f, sheetH - perfBand - 16f, textPaint);

        Uri saved = save(context, out);
        out.recycle();
        return saved;
    }

    public static Uri save(Context context, Bitmap bitmap) throws IOException {
        String name = "OTC_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                + ".jpg";
        ContentResolver resolver = context.getContentResolver();

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
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)) {
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
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)) {
                throw new IOException("JPEG compression failed");
            }
        }
        MediaScannerConnection.scanFile(context,
                new String[]{file.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
        return Uri.fromFile(file);
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

            int exifDeg = readExifRotation(resolver, uri);
            if (exifDeg != 0) {
                Matrix m = new Matrix();
                m.postRotate(exifDeg);
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

    private static int readExifRotation(ContentResolver resolver, Uri uri) {
        try (InputStream is = resolver.openInputStream(uri)) {
            if (is == null) return 0;
            android.media.ExifInterface exif = new android.media.ExifInterface(is);
            int o = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            if (o == android.media.ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == android.media.ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == android.media.ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) {
        }
        return 0;
    }
}
