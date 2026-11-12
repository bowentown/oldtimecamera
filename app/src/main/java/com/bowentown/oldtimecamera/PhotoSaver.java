package com.bowentown.oldtimecamera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Paper border, date back-print and MediaStore persistence. */
public final class PhotoSaver {

    private PhotoSaver() {}

    /** Same amber date string used by the in-viewfinder preview. */
    public static String dateString() {
        return new SimpleDateFormat("yy MM dd", Locale.US).format(new Date());
    }

    /** Adds the classic print border and the orange date stamp. */
    public static Bitmap applyPaper(Bitmap photo, FilmPreset preset, boolean stamp, boolean frame) {
        if (!stamp && !frame) return photo;

        int w = photo.getWidth();
        int h = photo.getHeight();
        int pad = frame ? Math.round(w * 0.035f) : 0;
        int footer = frame ? Math.round(w * 0.11f) : 0;
        int outW = w + pad * 2;
        int outH = h + pad * 2 + footer;

        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(preset.paperColor);
        canvas.drawBitmap(photo, pad, pad, null);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.MONOSPACE);

        if (stamp) {
            // The amber dot-matrix date of a 90s compact camera.
            paint.setColor(0xFFF2A65A);
            paint.setTextSize(w * 0.042f);
            paint.setShadowLayer(w * 0.006f, 0f, 0f, 0x66000000);
            canvas.drawText(dateString(), pad + w * 0.045f, pad + h - h * 0.035f, paint);
            paint.clearShadowLayer();
        }

        if (frame) {
            paint.setColor(0xFF8A7C66);
            paint.setTextSize(w * 0.022f);
            canvas.drawText(preset.name + "   ·   OLD TIME CAMERA",
                    pad + w * 0.02f, outH - footer * 0.36f, paint);
        }
        return out;
    }

    public static Uri save(Context context, Bitmap bitmap) throws IOException {
        String name = "OTC_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
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
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, os)) {
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
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, os)) {
                throw new IOException("JPEG compression failed");
            }
        }
        MediaScannerConnection.scanFile(context,
                new String[]{file.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
        return Uri.fromFile(file);
    }
}
