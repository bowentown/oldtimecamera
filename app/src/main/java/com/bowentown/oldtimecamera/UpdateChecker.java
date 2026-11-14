package com.bowentown.oldtimecamera;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * In-app update check against this project's GitHub Releases.
 *
 * <p>Network access happens only when the user taps "check update" in the darkroom
 * sheet; there is no background polling, no analytics and no account. The release APK
 * is downloaded into the app cache and its signing certificate is compared against the
 * installed app before the system installer is invoked, so a foreign APK served from a
 * compromised or mistyped source cannot be installed over this app.
 */
final class UpdateChecker {

    static final String RELEASES_PAGE =
            "https://github.com/bowentown/oldtimecamera/releases/latest";
    private static final String LATEST_API =
            "https://api.github.com/repos/bowentown/oldtimecamera/releases/latest";
    private static final int TIMEOUT_MS = 10_000;

    private UpdateChecker() {}

    private static final class Release {
        String tag = "";
        String notes = "";
        String pageUrl = RELEASES_PAGE;
        String apkUrl = null;
    }

    static void show(Context context) {
        // Dialogs and the installer prompt need a foreground Activity.
        if (!(context instanceof Activity)) return;
        final Context app = context.getApplicationContext();
        AlertDialog busy = new AlertDialog.Builder(context)
                .setMessage(R.string.update_checking)
                .setCancelable(true)
                .create();
        busy.show();

        new Thread(() -> {
            Release release = null;
            String error = null;
            try {
                release = fetchLatest(app);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final Release result = release;
            final String failure = error;
            if (context instanceof Activity) {
                ((Activity) context).runOnUiThread(() -> {
                    if (busy.isShowing()) busy.dismiss();
                    if (!((Activity) context).isFinishing()) {
                        showResult(context, result, failure);
                    }
                });
            }
        }, "otc-update-check").start();
    }

    private static void showResult(Context context, Release release, String error) {
        if (release == null) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.update_title)
                    .setMessage(context.getString(R.string.update_network_failed, String.valueOf(error)))
                    .setPositiveButton(R.string.update_open, (d, w) -> openBrowser(context, RELEASES_PAGE))
                    .setNegativeButton(R.string.close, null)
                    .show();
            return;
        }

        String current = currentVersion(context);
        boolean hasUpdate = compareVersions(release.tag, current) > 0;
        StringBuilder message = new StringBuilder();
        message.append(context.getString(R.string.update_current, current));
        if (!release.tag.isEmpty()) {
            message.append('\n').append(context.getString(R.string.update_latest, release.tag));
        }
        if (hasUpdate && release.notes != null && !release.notes.trim().isEmpty()) {
            String notes = release.notes.trim();
            if (notes.length() > 700) notes = notes.substring(0, 700) + "…";
            message.append("\n\n").append(notes);
        }
        if (!hasUpdate) {
            message.append("\n\n").append(context.getString(R.string.update_up_to_date));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(hasUpdate ? R.string.update_available : R.string.update_title)
                .setMessage(message.toString())
                .setNegativeButton(R.string.close, null);

        if (hasUpdate && release.apkUrl != null) {
            builder.setPositiveButton(R.string.update_download_install,
                    (d, w) -> downloadAndInstall(context, release.apkUrl));
            builder.setNeutralButton(R.string.update_open_browser,
                    (d, w) -> openBrowser(context, release.pageUrl));
        } else {
            builder.setPositiveButton(R.string.update_open, (d, w) -> openBrowser(context, release.pageUrl));
        }
        builder.show();
    }

    // ------------------------------------------------------------------ network

    private static Release fetchLatest(Context context) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(LATEST_API).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "OldTimeCamera/" + currentVersion(context));
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("HTTP " + code);
            }
            String body = readAll(conn.getInputStream());
            JSONObject json = new JSONObject(body);
            Release r = new Release();
            r.tag = json.optString("tag_name", "");
            r.notes = json.optString("body", "");
            String page = json.optString("html_url", "");
            if (!page.isEmpty()) r.pageUrl = page;

            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    if (name.toLowerCase().endsWith(".apk")) {
                        r.apkUrl = a.optString("browser_download_url", null);
                        break;
                    }
                }
            }
            return r;
        } finally {
            conn.disconnect();
        }
    }

    private static void downloadAndInstall(Context context, String apkUrl) {
        final Context app = context.getApplicationContext();
        AlertDialog progress = new AlertDialog.Builder(context)
                .setMessage(R.string.update_downloading)
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            File apk = null;
            String error = null;
            try {
                File dir = new File(app.getCacheDir(), "update");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("no cache dir");
                File out = new File(dir, "OldTimeCamera-update.apk");
                HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
                try {
                    conn.setConnectTimeout(TIMEOUT_MS);
                    conn.setReadTimeout(TIMEOUT_MS);
                    conn.setRequestProperty("User-Agent", "OldTimeCamera/" + currentVersion(app));
                    if (conn.getResponseCode() != 200) {
                        throw new IllegalStateException("HTTP " + conn.getResponseCode());
                    }
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            if (n > 0) fos.write(buf, 0, n);
                        }
                    }
                } finally {
                    conn.disconnect();
                }
                if (!isSignedByThisApp(app, out)) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    throw new SecurityException("signature mismatch");
                }
                apk = out;
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final File result = apk;
            final String failure = error;
            if (context instanceof Activity) {
                ((Activity) context).runOnUiThread(() -> {
                    if (progress.isShowing()) progress.dismiss();
                    if (result != null) {
                        installApk(context, result);
                    } else {
                        Toast.makeText(context,
                                context.getString(R.string.update_download_failed, String.valueOf(failure)),
                                Toast.LENGTH_LONG).show();
                        openBrowser(context, RELEASES_PAGE);
                    }
                });
            }
        }, "otc-update-download").start();
    }

    // ------------------------------------------------------------------ install

    private static void installApk(Context context, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(context,
                    PhotoSaver.fileProviderAuthority(context), apk);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show();
            openBrowser(context, RELEASES_PAGE);
        }
    }

    private static void openBrowser(Context context, String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, R.string.update_no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Compares the downloaded APK's signer with the installed app's; rejects anything else. */
    @SuppressWarnings("deprecation")
    private static boolean isSignedByThisApp(Context context, File apk) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo self;
            PackageInfo other;
            if (Build.VERSION.SDK_INT >= 28) {
                self = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                other = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                if (self == null || other == null
                        || self.signingInfo == null || other.signingInfo == null) {
                    return false;
                }
                Signature[] a = self.signingInfo.getApkContentsSigners();
                Signature[] b = other.signingInfo.getApkContentsSigners();
                return sameFirstSignature(a, b);
            }
            self = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            other = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            if (self == null || other == null) return false;
            return sameFirstSignature(self.signatures, other.signatures);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean sameFirstSignature(Signature[] a, Signature[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return false;
        return a[0].equals(b[0]);
    }

    static String currentVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName != null ? info.versionName : "0";
        } catch (Exception e) {
            return "0";
        }
    }

    /**
     * Compares dotted versions, tolerating a leading "v" and a pre-release suffix.
     * Returns &gt; 0 when {@code candidate} is newer than {@code current}.
     */
    static int compareVersions(String candidate, String current) {
        return VersionMath.compare(candidate, current);
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
