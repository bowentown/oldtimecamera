package com.bowentown.oldtimecamera;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** v1: one viewfinder, seven classic film stocks, one shutter button. */
public class MainActivity extends ComponentActivity {

    private static final int REQUEST_PERMISSIONS = 1001;
    private static final int MAX_PHOTO_EDGE = 2048;

    private FilmCameraView previewView;
    private FilmPreset[] presets;
    private final List<TextView> chips = new ArrayList<>();
    private int currentPreset = 1;

    private TextView caption, rollCounter, frameCounter, dateStampPreview;
    private TextView flashButton;
    private HorizontalScrollView filmStripScroll;
    private LinearLayout chipContainer;
    private FrameLayout shutterButton;
    private View shutterCore, flashOverlay, permissionGate;
    private ImageView thumbnail;
    private FrameLayout thumbnailFrame;

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageCapture imageCapture;

    private boolean facingFront = false;
    private int flashMode = ImageCapture.FLASH_MODE_AUTO;
    private boolean dateStamp = true;
    private boolean paperFrame = true;
    private boolean grainBoost = false;
    private boolean capturing = false;
    private int exposures = 0;
    private Uri lastPhotoUri;

    // Sensor orientation differs across devices. -1 follows CameraX; otherwise
    // the user can force a rotation from the settings sheet.
    private volatile int manualRotation = -1;
    private volatile int autoDegrees = 90;
    private volatile int bufferW = 4, bufferH = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        presets = FilmPresets.all();
        bindViews();
        buildFilmStrip();
        wireControls();
        applyPreset(currentPreset);
        updateStampPreview();
        ensurePermissions();
    }

    // ------------------------------------------------------------------ view setup

    private void bindViews() {
        previewView = findViewById(R.id.preview);
        caption = findViewById(R.id.presetCaption);
        rollCounter = findViewById(R.id.rollCounter);
        frameCounter = findViewById(R.id.frameCounter);
        dateStampPreview = findViewById(R.id.dateStamp);
        flashButton = findViewById(R.id.flashButton);
        filmStripScroll = findViewById(R.id.filmStripScroll);
        chipContainer = findViewById(R.id.chipContainer);
        shutterButton = findViewById(R.id.shutterButton);
        shutterCore = findViewById(R.id.shutterCore);
        flashOverlay = findViewById(R.id.flashOverlay);
        permissionGate = findViewById(R.id.permissionGate);
        thumbnail = findViewById(R.id.thumbnail);
        thumbnailFrame = findViewById(R.id.thumbnailFrame);
    }

    private void buildFilmStrip() {
        for (int i = 0; i < presets.length; i++) {
            TextView chip = new TextView(this);
            chip.setText(presets[i].name);
            chip.setTag(i);
            chip.setTypeface(Typeface.MONOSPACE);
            chip.setTextSize(10f);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setPadding(dp(12), 0, dp(12), 0);
            chip.setBackgroundResource(R.drawable.bg_chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
            lp.setMargins(dp(4), 0, dp(4), 0);
            int index = i;
            chip.setOnClickListener(v -> {
                applyPreset(index);
                filmStripScroll.smoothScrollTo(chip.getLeft() - dp(100), 0);
            });
            chipContainer.addView(chip, lp);
            chips.add(chip);
        }
    }

    private void wireControls() {
        shutterButton.setOnClickListener(v -> takePhoto());
        flashButton.setOnClickListener(v -> cycleFlash());
        findViewById(R.id.switchButton).setOnClickListener(v -> {
            facingFront = !facingFront;
            SurfaceTexture st = previewView.getSurfaceTexture();
            if (st != null) bindCamera(st);
        });
        findViewById(R.id.settingsButton).setOnClickListener(v -> showSettings());
        findViewById(R.id.grantButton).setOnClickListener(v -> ensurePermissions());
        thumbnailFrame.setOnClickListener(v -> openLastPhoto());
    }

    // ------------------------------------------------------------------ film stocks

    private void applyPreset(int index) {
        currentPreset = index;
        FilmPreset p = presets[index];
        previewView.setPreset(p);
        previewView.setGrainBoost(grainBoost);
        // Each stock ships with its own recommended print pairing.
        dateStamp = p.suggestStamp;
        paperFrame = p.suggestFrame;
        caption.setText(p.note);
        dateStampPreview.setText(PhotoSaver.dateString());
        updateStampPreview();
        for (int i = 0; i < chips.size(); i++) {
            boolean selected = i == index;
            TextView chip = chips.get(i);
            chip.setBackgroundTintList(ColorStateList.valueOf(
                    selected ? getResources().getColor(R.color.chip_selected, getTheme())
                            : getResources().getColor(R.color.chip_idle, getTheme())));
            chip.setTextColor(selected ? getResources().getColor(R.color.ink, getTheme())
                    : getResources().getColor(R.color.paper, getTheme()));
            chip.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.MONOSPACE);
        }
    }

    private void updateStampPreview() {
        dateStampPreview.setVisibility(dateStamp ? View.VISIBLE : View.GONE);
    }

    private void cycleFlash() {
        if (flashMode == ImageCapture.FLASH_MODE_AUTO) {
            flashMode = ImageCapture.FLASH_MODE_ON;
            flashButton.setText(getString(R.string.flash_on));
        } else if (flashMode == ImageCapture.FLASH_MODE_ON) {
            flashMode = ImageCapture.FLASH_MODE_OFF;
            flashButton.setText(getString(R.string.flash_off));
        } else {
            flashMode = ImageCapture.FLASH_MODE_AUTO;
            flashButton.setText(getString(R.string.flash_auto));
        }
        if (imageCapture != null) imageCapture.setFlashMode(flashMode);
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(18), dp(24), dp(8));

        CheckBox stamp = checkBox(getString(R.string.date_stamp), dateStamp);
        CheckBox frame = checkBox(getString(R.string.paper_frame), paperFrame);
        CheckBox grain = checkBox(getString(R.string.grain_boost), grainBoost);
        box.addView(stamp);
        box.addView(frame);
        box.addView(grain);

        TextView rotate = new TextView(this);
        rotate.setText(rotationLabel());
        rotate.setTextColor(getResources().getColor(R.color.paper, getTheme()));
        rotate.setGravity(Gravity.CENTER);
        rotate.setBackgroundResource(R.drawable.bg_chip);
        rotate.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams rotateLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        rotateLp.setMargins(0, dp(8), 0, dp(4));
        rotate.setOnClickListener(v -> {
            cycleRotation();
            rotate.setText(rotationLabel());
        });
        box.addView(rotate, rotateLp);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_title))
                .setView(box)
                .setPositiveButton(getString(R.string.done), (d, w) -> {
                    dateStamp = stamp.isChecked();
                    paperFrame = frame.isChecked();
                    grainBoost = grain.isChecked();
                    previewView.setGrainBoost(grainBoost);
                    updateStampPreview();
                })
                .show();
    }

    private CheckBox checkBox(String label, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(checked);
        cb.setTextColor(getResources().getColor(R.color.paper, getTheme()));
        cb.setPadding(dp(4), dp(8), dp(4), dp(8));
        return cb;
    }

    private String rotationLabel() {
        String value = manualRotation < 0
                ? getString(R.string.rotation_auto)
                : manualRotation + "°";
        return getString(R.string.rotate_viewfinder) + " : " + value;
    }

    private void cycleRotation() {
        if (manualRotation < 0) manualRotation = 0;
        else if (manualRotation == 0) manualRotation = 90;
        else if (manualRotation == 90) manualRotation = 180;
        else if (manualRotation == 180) manualRotation = 270;
        else manualRotation = -1;
        applyTransform(autoDegrees, facingFront);
    }

    private void applyTransform(int cameraDegrees, boolean mirrored) {
        autoDegrees = cameraDegrees;
        int degrees = manualRotation >= 0 ? manualRotation : cameraDegrees;
        boolean quarterTurn = (degrees % 180 != 0);
        previewView.setCameraTransform(degrees, mirrored);
        previewView.setAspectRatio(quarterTurn ? bufferH : bufferW,
                quarterTurn ? bufferW : bufferH);
    }

    // ------------------------------------------------------------------ permissions

    private void ensurePermissions() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (missing.isEmpty()) {
            permissionGate.setVisibility(View.GONE);
            previewView.setOnSurfaceReadyListener(this::onSurfaceReady);
        } else {
            permissionGate.setVisibility(View.VISIBLE);
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        boolean granted = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) granted = false;
        }
        if (granted) {
            ensurePermissions();
        } else {
            permissionGate.setVisibility(View.VISIBLE);
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ camera

    private void onSurfaceReady(SurfaceTexture st) {
        bindCamera(st);
    }

    @SuppressWarnings("deprecation")
    private void bindCamera(SurfaceTexture st) {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(facingFront ? CameraSelector.LENS_FACING_FRONT
                                : CameraSelector.LENS_FACING_BACK)
                        .build();
                Preview preview = new Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(rotation)
                        .build();
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(rotation)
                        .build();

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
                imageCapture.setFlashMode(flashMode);
                preview.setSurfaceProvider(cameraExecutor, request -> provideSurface(request, st));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "相机启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void provideSurface(SurfaceRequest request, SurfaceTexture st) {
        Size size = request.getResolution();
        int w = size.getWidth();
        int h = size.getHeight();
        st.setDefaultBufferSize(w, h);
        bufferW = w;
        bufferH = h;

        // A landscape buffer in a portrait activity is the common case. Apply
        // a usable default immediately; TransformationInfo refines it shortly.
        applyTransform(90, facingFront);
        request.setTransformationInfoListener(cameraExecutor,
                info -> applyTransform(info.getRotationDegrees(), facingFront));

        Surface surface = new Surface(st);
        request.provideSurface(surface, cameraExecutor, result -> surface.release());
    }

    // ------------------------------------------------------------------ shutter

    private void takePhoto() {
        if (capturing) {
            toast(R.string.capture_busy);
            return;
        }
        if (imageCapture == null) {
            toast(R.string.camera_permission_needed);
            return;
        }
        capturing = true;
        shutterCore.animate().scaleX(0.84f).scaleY(0.84f).setDuration(80)
                .withEndAction(() -> shutterCore.animate().scaleX(1f).scaleY(1f)
                        .setDuration(160).start()).start();
        flashOverlay.setAlpha(0f);
        flashOverlay.animate().alpha(0.8f).setDuration(55)
                .withEndAction(() -> flashOverlay.animate().alpha(0f).setDuration(240).start())
                .start();

        FilmPreset preset = presets[currentPreset];
        final boolean wantStamp = dateStamp;
        final boolean wantFrame = paperFrame;
        final boolean boostGrain = grainBoost;

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                try {
                    byte[] jpeg = readJpeg(image);
                    int rotation = exifRotation(jpeg);
                    image.close();

                    Bitmap source = decode(jpeg, MAX_PHOTO_EDGE);
                    if (source == null) throw new IllegalStateException("Cannot decode capture");
                    Bitmap rotated = rotate(source, rotation);
                    Bitmap filtered = previewView.renderPhoto(rotated, preset, boostGrain);
                    if (filtered != rotated) rotated.recycle();
                    Bitmap print = PhotoSaver.applyPaper(filtered, preset, wantStamp, wantFrame);
                    if (print != filtered) filtered.recycle();
                    lastPhotoUri = PhotoSaver.save(MainActivity.this, print);
                    Bitmap thumb = Bitmap.createScaledBitmap(print, dp(96), dp(96), true);
                    print.recycle();

                    runOnUiThread(() -> onPhotoSaved(thumb));
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        toast(R.string.save_failed);
                        Toast.makeText(MainActivity.this, String.valueOf(e.getMessage()),
                                Toast.LENGTH_LONG).show();
                    });
                } finally {
                    runOnUiThread(() -> capturing = false);
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> {
                    capturing = false;
                    Toast.makeText(MainActivity.this,
                            "拍摄失败: " + exception.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void onPhotoSaved(Bitmap thumb) {
        thumbnail.setImageBitmap(thumb);
        exposures++;
        frameCounter.setText(String.format(java.util.Locale.US,
                "%02d / 36", Math.min(exposures, 36)));
        rollCounter.setText(String.format(java.util.Locale.US,
                "ROLL %02d · EXP %02d", (exposures / 36) + 1, exposures % 36));
        capturing = false;
        toast(R.string.saved_to_gallery);
    }

    private void openLastPhoto() {
        if (lastPhotoUri == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(lastPhotoUri, "image/jpeg");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.thumbnail)));
        } catch (Exception ignored) {
            // No gallery app available; the photo is still in MediaStore.
        }
    }

    // ------------------------------------------------------------------ bitmap helpers

    private static byte[] readJpeg(ImageProxy image) {
        java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static int exifRotation(byte[] jpeg) {
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static Bitmap decode(byte[] jpeg, int maxEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longest / sample > maxEdge) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
    }

    private static Bitmap rotate(Bitmap src, int degrees) {
        if (degrees == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src) src.recycle();
        return out;
    }

    // ------------------------------------------------------------------ misc

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        previewView.onResume();
    }

    @Override
    protected void onPause() {
        previewView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (cameraProvider != null) cameraProvider.unbindAll();
    }
}
