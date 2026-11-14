package com.bowentown.oldtimecamera;

import android.app.AlertDialog;
import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shows the third-party notices that ship inside the APK. MIT and OFL both require
 * the license text to travel with every copy of the binary, so this dialog is the
 * in-app copy (the repository carries the same files under LICENSES/).
 */
final class LicenseDialog {

    private static final String[] ASSETS = {
            "licenses/NOTICE.txt",
            "licenses/MIT-Phosphor-Icons.txt",
            "licenses/OFL-1.1-JetBrains-Mono.txt"
    };

    private LicenseDialog() {}

    static void show(Context context) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.licenses_title)
                .setMessage(readAll(context))
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private static String readAll(Context context) {
        StringBuilder sb = new StringBuilder();
        for (String path : ASSETS) {
            String text = read(context, path);
            if (text == null) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(text.trim());
        }
        return sb.length() > 0 ? sb.toString() : context.getString(R.string.licenses_missing);
    }

    private static String read(Context context, String path) {
        try (InputStream in = context.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
