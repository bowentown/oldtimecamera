package com.bowentown.oldtimecamera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.ExifInterface;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.DisplayOrientedMeteringPointFactory;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main viewfinder activity: CameraX preview & capture, unified OpenGL ES film
 * emulation, tactile shutter, darkroom controls, and gallery import/archive.
 */
public class MainActivity extends ComponentActivity {

    private static final int REQUEST_PERMISSIONS = 1001;
    private static final int MAX_PHOTO_EDGE = 2560;

    private CameraPrefs prefs;
    private FilmPreset[] presets;
    private final List<LinearLayout> chips = new ArrayList<>();

    private AspectFrameLayout previewHost;
    private FilmCameraView previewView;
    private ViewfinderOverlayView viewfinderOverlay;
    private TextView rollCounter, dateStampPreview, countdownText;
    private TextView zoomPill, aspectPill, evPill, evValueText, timerLabel;
    private ImageButton flashButton, gridPill, doubleExposurePill;
    private ImageView timerIcon, thumbnail, thumbPlaceholder;
    private HorizontalScrollView filmStripScroll;
    private LinearLayout chipContainer, evPanel;
    private SeekBar evSeekBar;
    private FrameLayout shutterButton, thumbnailFrame;
    private View shutterCore, flashOverlay, permissionGate;

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger bindGeneration = new AtomicInteger(0);
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageCapture imageCapture;
    private MediaActionSound shutterSound;

    private int currentPreset = 1;
    private volatile boolean facingFront = false;
    private int flashMode = ImageCapture.FLASH_MODE_AUTO;
    private CameraPrefs.AspectRatio aspectRatio = CameraPrefs.AspectRatio.RATIO_3_4;
    private CameraPrefs.PaperStyle paperStyle = CameraPrefs.PaperStyle.IVORY;
    private CameraPrefs.DateMode dateMode = CameraPrefs.DateMode.TODAY;
    private boolean grainBoost = false;
    private boolean lightLeakEnabled = true;
    private boolean gridEnabled = false;
    private boolean levelEnabled = false;
    private boolean shutterSoundEnabled = true;
    private volatile boolean frontMirrorEnabled = true;
    private boolean doubleExposureMode = false;
    private Bitmap doubleExposureFirstFrame = null;
    private int timerSeconds = 0;
    private int rollNumber = 1;
    private int exposures = 0;
    private float leakSeed = 0.28f;

    private volatile boolean capturing = false;
    private boolean latestAxisSwapped = true;
    private CountDownTimer activeTimer;
    private float currentZoom = 1f;
    private int evMin = 0, evMax = 0;
    private float evStep = 0.333f;

    // Latest CameraX transform metadata + optional user manual calibration.
    private volatile int manualRotation = -1;
    private volatile int autoDegrees = 90;
    private volatile int bufferW = 4, bufferH = 3;
    private volatile boolean latestHasCameraTransform = true;
    private volatile int latestInfoRotation = 90;
    private volatile boolean latestInfoMirroring = false;
    private volatile float latestCropL = 0f, latestCropT = 0f, latestCropR = 1f, latestCropB = 1f;
    private volatile int deviceSurfaceRotation = Surface.ROTATION_0;

    private OrientationEventListener orientationListener;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private float smoothGravityX = 0f, smoothGravityY = 9.81f;
    private final SensorEventListener levelSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!levelEnabled || event.values == null || event.values.length < 2) return;
            smoothGravityX = smoothGravityX * 0.82f + event.values[0] * 0.18f;
            smoothGravityY = smoothGravityY * 0.82f + event.values[1] * 0.18f;
            float tiltDeg = (float) Math.toDegrees(Math.atan2(-smoothGravityX, smoothGravityY));
            viewfinderOverlay.setTiltDegrees(tiltDeg);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private final ActivityResultLauncher<String> pickPhotoLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    DarkroomEditorDialog.show(this, previewView, prefs, uri, currentPreset,
                            (savedUri, thumb) -> onExternalPrintSaved(savedUri, thumb));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        prefs = new CameraPrefs(this);
        presets = FilmPresets.all();
        deviceSurfaceRotation = getDisplaySurfaceRotation();
        loadPrefs();

        bindViews();
        FilmUiHelper.applySystemBarPadding(findViewById(R.id.mainColumn));
        FilmUiHelper.buildFilmStrip(this, filmStripScroll, chipContainer, presets, chips, idx -> {
            shutterButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            applyPreset(idx);
        });
        wireControls();
        initOrientationAndLevelSensors();

        shutterSound = new MediaActionSound();
        cameraExecutor.execute(() -> shutterSound.load(MediaActionSound.SHUTTER_CLICK));

        applyPreset(currentPreset);
        applyAspectRatio(false);
        updateGridUi();
        updateLevelUi();
        updateDoubleExposureUi();
        updateTimerUi();
        updateStampPreview();
        updateRollCounter();
        refreshThumbnailAsync();
        ensurePermissions();
    }

    private void loadPrefs() {
        currentPreset = FilmPresets.indexOfId(prefs.getPresetId("portra400"));
        aspectRatio = prefs.getAspectRatio();
        paperStyle = prefs.getPaperStyle();
        dateMode = prefs.getDateMode();
        grainBoost = prefs.isGrainBoost();
        lightLeakEnabled = prefs.isLightLeakEnabled();
        gridEnabled = prefs.isGridEnabled();
        levelEnabled = prefs.isLevelEnabled();
        shutterSoundEnabled = prefs.isShutterSoundEnabled();
        frontMirrorEnabled = prefs.isFrontMirrorEnabled();
        timerSeconds = prefs.getTimerSeconds();
        manualRotation = prefs.getManualRotation();
        rollNumber = prefs.getRollNumber();
        exposures = prefs.getExposures();
    }

    // ------------------------------------------------------------------ view setup

    private void bindViews() {
        previewHost = findViewById(R.id.previewHost);
        previewView = findViewById(R.id.preview);
        viewfinderOverlay = findViewById(R.id.viewfinderOverlay);
        rollCounter = findViewById(R.id.rollCounter);
        dateStampPreview = findViewById(R.id.dateStamp);
        countdownText = findViewById(R.id.countdownText);
        flashButton = findViewById(R.id.flashButton);
        timerIcon = findViewById(R.id.timerIcon);
        timerLabel = findViewById(R.id.timerLabel);
        zoomPill = findViewById(R.id.zoomPill);
        aspectPill = findViewById(R.id.aspectPill);
        evPill = findViewById(R.id.evPill);
        evPanel = findViewById(R.id.evPanel);
        evSeekBar = findViewById(R.id.evSeekBar);
        evValueText = findViewById(R.id.evValueText);
        gridPill = findViewById(R.id.gridPill);
        doubleExposurePill = findViewById(R.id.doubleExposurePill);
        filmStripScroll = findViewById(R.id.filmStripScroll);
        chipContainer = findViewById(R.id.chipContainer);
        shutterButton = findViewById(R.id.shutterButton);
        shutterCore = findViewById(R.id.shutterCore);
        flashOverlay = findViewById(R.id.flashOverlay);
        permissionGate = findViewById(R.id.permissionGate);
        thumbnail = findViewById(R.id.thumbnail);
        thumbPlaceholder = findViewById(R.id.thumbPlaceholder);
        thumbnailFrame = findViewById(R.id.thumbnailFrame);
    }

    private void wireControls() {
        shutterButton.setOnClickListener(v -> takePhoto());
        flashButton.setOnClickListener(v -> cycleFlash());
        findViewById(R.id.timerButton).setOnClickListener(v -> cycleTimer());
        findViewById(R.id.switchButton).setOnClickListener(v -> switchLens());
        findViewById(R.id.settingsButton).setOnClickListener(v -> openDarkroomSheet());
        findViewById(R.id.grantButton).setOnClickListener(v -> ensurePermissions());

        thumbnailFrame.setOnClickListener(v ->
                startActivity(new Intent(this, GalleryActivity.class)));
        findViewById(R.id.importButton).setOnClickListener(v ->
                pickPhotoLauncher.launch("image/*"));

        aspectPill.setOnClickListener(v -> {
            if (capturing) {
                toast(R.string.capture_busy);
                return;
            }
            aspectRatio = aspectRatio.next();
            prefs.setAspectRatio(aspectRatio);
            clearDoubleExposureFrame();
            updateDoubleExposureUi();
            applyAspectRatio(true);
        });

        dateStampPreview.setOnClickListener(v -> {
            dateMode = (dateMode == CameraPrefs.DateMode.TODAY)
                    ? CameraPrefs.DateMode.RETRO_98
                    : CameraPrefs.DateMode.TODAY;
            prefs.setDateMode(dateMode);
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            updateStampPreview();
        });
        dateStampPreview.setOnLongClickListener(v -> {
            dateMode = CameraPrefs.DateMode.OFF;
            prefs.setDateMode(dateMode);
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            updateStampPreview();
            return true;
        });

        zoomPill.setOnClickListener(v -> cycleZoom());

        evPill.setOnClickListener(v ->
                evPanel.setVisibility(evPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        gridPill.setOnClickListener(v -> {
            gridEnabled = !gridEnabled;
            prefs.setGridEnabled(gridEnabled);
            updateGridUi();
        });

        doubleExposurePill.setOnClickListener(v -> toggleDoubleExposure());

        evSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && camera != null && evMax > evMin) {
                    int idx = evMin + progress;
                    camera.getCameraControl().setExposureCompensationIndex(idx);
                    updateEvUi(idx);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        viewfinderOverlay.setOnFrameBoundsListener((rect, vw, vh) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dateStampPreview.getLayoutParams();
            lp.rightMargin = Math.max(0, Math.round(vw - rect.right)) + FilmUiHelper.dp(this, 8);
            lp.bottomMargin = Math.max(0, Math.round(vh - rect.bottom)) + FilmUiHelper.dp(this, 8);
            dateStampPreview.setLayoutParams(lp);
        });

        previewView.setOnAxisSwapListener(swapped -> updateViewfinderAspectRatio(swapped, false));

        previewView.setOnGestureListener(new FilmCameraView.OnGestureListener() {
            @Override
            public void onTapFocus(float viewX, float viewY, int viewWidth, int viewHeight) {
                triggerTapFocus(viewX, viewY, viewWidth, viewHeight);
            }

            @Override
            public void onPinchZoom(float scaleFactor) {
                applyPinchZoom(scaleFactor);
            }

            @Override
            public void onDoubleTapFlip() {
                if (!capturing) switchLens();
            }

            @Override
            public void onSwipeFilm(int direction) {
                int next = (currentPreset + direction + presets.length) % presets.length;
                shutterButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                applyPreset(next);
            }
        });
    }

    private void initOrientationAndLevelSensors() {
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) return;
                int next = CameraTransformMath.quantizeDeviceOrientation(
                        orientation, deviceSurfaceRotation);
                if (next != deviceSurfaceRotation) {
                    deviceSurfaceRotation = next;
                    if (imageCapture != null) {
                        imageCapture.setTargetRotation(next);
                    }
                }
            }
        };
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
    }

    @SuppressWarnings("deprecation")
    private Display getDisplayCompat() {
        if (Build.VERSION.SDK_INT >= 30) {
            Display d = getDisplay();
            if (d != null) return d;
        }
        return getWindowManager().getDefaultDisplay();
    }

    private int getDisplaySurfaceRotation() {
        Display d = getDisplayCompat();
        return d != null ? d.getRotation() : Surface.ROTATION_0;
    }

    // ------------------------------------------------------------------ film & UI state

    private void applyPreset(int index) {
        currentPreset = index;
        FilmPreset p = presets[index];
        prefs.setPresetId(p.id);

        previewView.setPreset(p);
        previewView.setGrainBoost(grainBoost);
        previewView.setLightLeakEnabled(lightLeakEnabled);
        previewView.setLeakSeed(leakSeed);

        FilmUiHelper.updateChipSelection(this, chips, index);
        FilmUiHelper.centerChip(filmStripScroll, chips.get(index));
    }

    private void updateViewfinderAspectRatio(boolean axisSwapped, boolean animate) {
        latestAxisSwapped = axisSwapped;
        int[] dims = CameraTransformMath.croppedDisplayDimensions(
                bufferW, bufferH, latestCropL, latestCropT, latestCropR, latestCropB, axisSwapped);
        if (dims[0] > 0 && dims[1] > 0) {
            if (previewHost != null) previewHost.setAspectRatio(dims[0], dims[1]);
            previewView.setAspectRatio(dims[0], dims[1]);
        }
        applyAspectRatio(animate);
    }

    private void applyAspectRatio(boolean animate) {
        aspectPill.setText(aspectRatio.label);
        float[] scales = CameraTransformMath.computeFrameScale(
                previewView.getSensorAspectRatio(), aspectRatio.widthOverHeight);
        previewView.setFrameScale(scales[0], scales[1]);
        viewfinderOverlay.setFrameScale(scales[0], scales[1], animate);
    }

    private void updateStampPreview() {
        String text = PhotoSaver.dateString(dateMode);
        if (text.isEmpty()) {
            dateStampPreview.setVisibility(View.GONE);
        } else {
            dateStampPreview.setText(text);
            dateStampPreview.setVisibility(View.VISIBLE);
        }
    }

    private void updateGridUi() {
        viewfinderOverlay.setGridEnabled(gridEnabled);
        gridPill.setColorFilter(getResources().getColor(
                gridEnabled ? R.color.amber_led : R.color.paper, getTheme()));
    }

    private void updateLevelUi() {
        viewfinderOverlay.setLevelEnabled(levelEnabled);
        syncLevelSensorRegistration();
    }

    private void syncLevelSensorRegistration() {
        if (sensorManager == null || accelerometer == null) return;
        sensorManager.unregisterListener(levelSensorListener);
        if (levelEnabled && isActivityResumed) {
            sensorManager.registerListener(levelSensorListener, accelerometer,
                    SensorManager.SENSOR_DELAY_UI);
        }
    }

    private void updateDoubleExposureUi() {
        boolean hasGhost = (doubleExposureFirstFrame != null);
        doubleExposurePill.setColorFilter(getResources().getColor(
                doubleExposureMode ? R.color.amber_led : R.color.paper, getTheme()));
        doubleExposurePill.setAlpha((doubleExposureMode || hasGhost) ? 1f : 0.72f);
        updateRollCounter();
    }

    private void toggleDoubleExposure() {
        if (capturing) {
            toast(R.string.capture_busy);
            return;
        }
        if (doubleExposureFirstFrame != null) {
            clearDoubleExposureFrame();
            doubleExposureMode = false;
        } else {
            doubleExposureMode = !doubleExposureMode;
        }
        shutterButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        updateDoubleExposureUi();
    }

    private void clearDoubleExposureFrame() {
        if (doubleExposureFirstFrame != null && !doubleExposureFirstFrame.isRecycled()) {
            doubleExposureFirstFrame.recycle();
        }
        doubleExposureFirstFrame = null;
        viewfinderOverlay.setDoubleExposureGhost(null);
    }

    private void updateTimerUi() {
        if (timerSeconds > 0) {
            timerIcon.setVisibility(View.GONE);
            timerLabel.setVisibility(View.VISIBLE);
            timerLabel.setText(timerSeconds + "s");
        } else {
            timerIcon.setVisibility(View.VISIBLE);
            timerLabel.setVisibility(View.GONE);
        }
    }

    private void updateRollCounter() {
        int frameInRoll = Math.min(exposures, CameraPrefs.FRAMES_PER_ROLL);
        String text = String.format(Locale.US,
                "R%02d · %02d/%02d", rollNumber, frameInRoll, CameraPrefs.FRAMES_PER_ROLL);
        if (doubleExposureMode) {
            text = text + (doubleExposureFirstFrame != null ? " · 2/2" : " · 1/2");
        }
        rollCounter.setText(text);
    }

    private void cycleTimer() {
        if (timerSeconds == 0) timerSeconds = 3;
        else if (timerSeconds == 3) timerSeconds = 10;
        else timerSeconds = 0;
        prefs.setTimerSeconds(timerSeconds);
        updateTimerUi();
    }

    private void cycleFlash() {
        if (flashMode == ImageCapture.FLASH_MODE_AUTO) {
            flashMode = ImageCapture.FLASH_MODE_ON;
            flashButton.setImageResource(R.drawable.ic_flash_on);
        } else if (flashMode == ImageCapture.FLASH_MODE_ON) {
            flashMode = ImageCapture.FLASH_MODE_OFF;
            flashButton.setImageResource(R.drawable.ic_flash_off);
        } else {
            flashMode = ImageCapture.FLASH_MODE_AUTO;
            flashButton.setImageResource(R.drawable.ic_flash_auto);
        }
        if (imageCapture != null) imageCapture.setFlashMode(flashMode);
    }

    private void switchLens() {
        if (capturing) {
            toast(R.string.capture_busy);
            return;
        }
        cancelCountdown();
        facingFront = !facingFront;
        SurfaceTexture st = previewView.getSurfaceTexture();
        if (st != null) bindCamera(st);
    }

    private void openDarkroomSheet() {
        if (capturing) {
            toast(R.string.capture_busy);
            return;
        }
        DarkroomSheet.show(this, prefs, new DarkroomSheet.Callbacks() {
            @Override
            public void onAspectChanged(CameraPrefs.AspectRatio ratio) {
                if (capturing) return;
                aspectRatio = ratio;
                clearDoubleExposureFrame();
                updateDoubleExposureUi();
                applyAspectRatio(true);
            }

            @Override
            public void onPaperChanged(CameraPrefs.PaperStyle style) {
                paperStyle = style;
            }

            @Override
            public void onDateModeChanged(CameraPrefs.DateMode mode) {
                dateMode = mode;
                updateStampPreview();
            }

            @Override
            public void onGrainBoostChanged(boolean enabled) {
                grainBoost = enabled;
                previewView.setGrainBoost(enabled);
            }

            @Override
            public void onLightLeakChanged(boolean enabled) {
                lightLeakEnabled = enabled;
                previewView.setLightLeakEnabled(enabled);
            }

            @Override
            public void onLevelChanged(boolean enabled) {
                levelEnabled = enabled;
                updateLevelUi();
            }

            @Override
            public void onShutterSoundChanged(boolean enabled) {
                shutterSoundEnabled = enabled;
            }

            @Override
            public void onFrontMirrorChanged(boolean enabled) {
                frontMirrorEnabled = enabled;
                pushPreviewTransform();
            }

            @Override
            public int onCycleRotation() {
                cycleRotation();
                return manualRotation;
            }

            @Override
            public String getRotationLabel() {
                return rotationLabel();
            }

            @Override
            public void onLoadNewRoll() {
                rollNumber++;
                exposures = 0;
                prefs.setRollNumber(rollNumber);
                prefs.setExposures(0);
                updateRollCounter();
                Toast.makeText(MainActivity.this,
                        getString(R.string.new_roll_loaded, rollNumber - 1, rollNumber),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String rotationLabel() {
        String value = manualRotation < 0
                ? getString(R.string.rotation_auto)
                : manualRotation + "°";
        return getString(R.string.rotate_viewfinder) + " · " + value;
    }

    private void cycleRotation() {
        if (manualRotation < 0) manualRotation = 0;
        else if (manualRotation == 0) manualRotation = 90;
        else if (manualRotation == 90) manualRotation = 180;
        else if (manualRotation == 180) manualRotation = 270;
        else manualRotation = -1;
        prefs.setManualRotation(manualRotation);
        pushPreviewTransform();
    }

    private void pushPreviewTransform() {
        int displayDeg = CameraTransformMath.surfaceRotationToDegrees(getDisplaySurfaceRotation());
        int manualOffset = manualRotation > 0 ? manualRotation : 0;
        boolean extraMirror = facingFront && !frontMirrorEnabled;
        previewView.setCameraTransform(
                latestHasCameraTransform,
                latestInfoRotation,
                latestInfoMirroring,
                latestCropL,
                latestCropT,
                latestCropR,
                latestCropB,
                displayDeg,
                manualOffset,
                extraMirror);
    }

    // ------------------------------------------------------------------ focus, zoom, EV

    private void triggerTapFocus(float viewX, float viewY, int vw, int vh) {
        if (!viewfinderOverlay.isPointInActiveFrame(viewX, viewY)) return;
        viewfinderOverlay.showFocusAt(viewX, viewY);
        viewfinderOverlay.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (camera == null) return;
        MeteringPoint point = new DisplayOrientedMeteringPointFactory(
                getDisplayCompat(), camera.getCameraInfo(), vw, vh).createPoint(viewX, viewY);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build();
        camera.getCameraControl().startFocusAndMetering(action);
    }

    private void applyPinchZoom(float scaleFactor) {
        if (camera == null) return;
        ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
        float minZ = zs != null ? zs.getMinZoomRatio() : 1f;
        float maxZ = zs != null ? Math.min(zs.getMaxZoomRatio(), 6f) : 4f;
        currentZoom = Math.max(minZ, Math.min(maxZ, currentZoom * scaleFactor));
        camera.getCameraControl().setZoomRatio(currentZoom);
        updateZoomUi();
    }

    private void cycleZoom() {
        if (camera == null) return;
        ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
        float maxZ = zs != null ? zs.getMaxZoomRatio() : 2f;
        if (currentZoom < 1.35f && maxZ >= 1.5f) {
            currentZoom = 1.5f;
        } else if (currentZoom < 1.85f && maxZ >= 2.0f) {
            currentZoom = 2.0f;
        } else {
            currentZoom = 1.0f;
        }
        camera.getCameraControl().setZoomRatio(currentZoom);
        updateZoomUi();
    }

    private void updateZoomUi() {
        zoomPill.setText(String.format(Locale.US, "%.1fx", currentZoom));
    }

    private void setupCameraControlsUi() {
        if (camera == null) return;
        currentZoom = 1f;
        updateZoomUi();

        ExposureState es = camera.getCameraInfo().getExposureState();
        if (es.isExposureCompensationSupported()) {
            Range<Integer> range = es.getExposureCompensationRange();
            evMin = range.getLower();
            evMax = range.getUpper();
            evStep = es.getExposureCompensationStep().floatValue();
            evSeekBar.setMax(Math.max(1, evMax - evMin));
            int currentIdx = es.getExposureCompensationIndex();
            evSeekBar.setProgress(Math.max(0, currentIdx - evMin));
            updateEvUi(currentIdx);
            evPill.setVisibility(View.VISIBLE);
        } else {
            evPill.setVisibility(View.GONE);
            evPanel.setVisibility(View.GONE);
        }
    }

    private void updateEvUi(int index) {
        float ev = index * evStep;
        if (Math.abs(ev) < 0.05f) {
            evPill.setText("EV 0");
            evPill.setTextColor(getResources().getColor(R.color.paper, getTheme()));
            evValueText.setText("0.0");
        } else {
            String formatted = String.format(Locale.US, "%+.1f", ev);
            evPill.setText("EV " + formatted);
            evPill.setTextColor(getResources().getColor(R.color.amber_led, getTheme()));
            evValueText.setText(formatted);
        }
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

    private boolean isActivityResumed = false;
    private float savedWindowBrightness = -2f;

    @SuppressWarnings("deprecation")
    private void bindCamera(SurfaceTexture st) {
        final int gen = bindGeneration.incrementAndGet();
        final boolean wantFront = facingFront;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (gen != bindGeneration.get() || isDestroyed() || isFinishing()) {
                return;
            }
            try {
                cameraProvider = future.get();
                int displayRotation = getDisplaySurfaceRotation();
                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(wantFront ? CameraSelector.LENS_FACING_FRONT
                                : CameraSelector.LENS_FACING_BACK)
                        .build();
                Preview preview = new Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(displayRotation)
                        .build();
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(deviceSurfaceRotation)
                        .build();

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
                imageCapture.setFlashMode(flashMode);
                setupCameraControlsUi();
                preview.setSurfaceProvider(cameraExecutor,
                        request -> provideSurface(request, st, wantFront, gen));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.camera_start_failed, String.valueOf(e.getMessage())),
                        Toast.LENGTH_LONG).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // CameraX 1.3.4 marks TransformationInfo#hasCameraTransform() and #getMirroring() @RestrictTo(LIBRARY_GROUP)
    // until CameraX 1.4.
    @SuppressLint("RestrictedApi")
    private void provideSurface(SurfaceRequest request, SurfaceTexture st, boolean lensFront, int gen) {
        Size size = request.getResolution();
        int w = size.getWidth();
        int h = size.getHeight();
        st.setDefaultBufferSize(w, h);
        bufferW = w;
        bufferH = h;

        int displayRot = getDisplaySurfaceRotation();
        int fallbackRot = (displayRot == Surface.ROTATION_90 || displayRot == Surface.ROTATION_270)
                ? 0 : 90;
        latestHasCameraTransform = true;
        latestInfoRotation = fallbackRot;
        latestInfoMirroring = lensFront;
        latestCropL = 0f;
        latestCropT = 0f;
        latestCropR = 1f;
        latestCropB = 1f;
        autoDegrees = fallbackRot;
        pushPreviewTransform();
        runOnUiThread(() -> {
            if (gen != bindGeneration.get()) return;
            int manualOff = manualRotation > 0 ? manualRotation : 0;
            boolean quarter = ((fallbackRot + manualOff) % 180 != 0);
            updateViewfinderAspectRatio(quarter, false);
        });

        request.setTransformationInfoListener(cameraExecutor, info -> {
            if (gen != bindGeneration.get()) return;
            latestHasCameraTransform = info.hasCameraTransform();
            latestInfoRotation = info.getRotationDegrees();
            latestInfoMirroring = info.getMirroring();
            Rect crop = info.getCropRect();
            if (crop != null && bufferW > 0 && bufferH > 0 && !crop.isEmpty()) {
                latestCropL = (float) crop.left / bufferW;
                latestCropT = (float) crop.top / bufferH;
                latestCropR = (float) crop.right / bufferW;
                latestCropB = (float) crop.bottom / bufferH;
            } else {
                latestCropL = 0f;
                latestCropT = 0f;
                latestCropR = 1f;
                latestCropB = 1f;
            }
            autoDegrees = info.getRotationDegrees();
            pushPreviewTransform();
            int manualOff = manualRotation > 0 ? manualRotation : 0;
            boolean quarter = ((latestInfoRotation + manualOff) % 180 != 0);
            runOnUiThread(() -> {
                if (gen != bindGeneration.get()) return;
                updateViewfinderAspectRatio(quarter, false);
            });
        });

        Surface surface = new Surface(st);
        request.provideSurface(surface, cameraExecutor, result -> surface.release());
    }

    // ------------------------------------------------------------------ shutter

    private void takePhoto() {
        if (activeTimer != null) {
            cancelCountdown();
            return;
        }
        if (capturing) {
            toast(R.string.capture_busy);
            return;
        }
        if (imageCapture == null) {
            toast(R.string.camera_permission_needed);
            return;
        }
        if (timerSeconds > 0) {
            startCountdown(timerSeconds);
            return;
        }
        captureNow();
    }

    private void startCountdown(int seconds) {
        countdownText.setVisibility(View.VISIBLE);
        activeTimer = new CountDownTimer(seconds * 1000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                int sec = (int) Math.ceil(millisUntilFinished / 1000.0);
                countdownText.setText(String.valueOf(sec));
                countdownText.setScaleX(1.25f);
                countdownText.setScaleY(1.25f);
                countdownText.setAlpha(0.95f);
                countdownText.animate().scaleX(1f).scaleY(1f).alpha(0.75f).setDuration(750).start();
                shutterButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }

            @Override
            public void onFinish() {
                activeTimer = null;
                countdownText.setVisibility(View.GONE);
                captureNow();
            }
        }.start();
    }

    private void cancelCountdown() {
        if (activeTimer != null) {
            activeTimer.cancel();
            activeTimer = null;
        }
        countdownText.setVisibility(View.GONE);
    }

    private void beginScreenFlash() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (savedWindowBrightness < -1.5f) {
            savedWindowBrightness = lp.screenBrightness;
        }
        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);
        flashOverlay.setBackgroundColor(Color.parseColor("#FFF4E2"));
        flashOverlay.animate().cancel();
        flashOverlay.setAlpha(0f);
        flashOverlay.animate().alpha(0.95f).setDuration(75).start();
    }

    private void endScreenFlash() {
        if (savedWindowBrightness >= -1.5f) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = savedWindowBrightness;
            getWindow().setAttributes(lp);
            savedWindowBrightness = -2f;
        }
        flashOverlay.animate().alpha(0f).setDuration(220).start();
    }

    private void captureNow() {
        if (capturing || imageCapture == null) return;
        capturing = true;

        shutterButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (shutterSoundEnabled && shutterSound != null) {
            try {
                shutterSound.play(MediaActionSound.SHUTTER_CLICK);
            } catch (Exception ignored) {
            }
        }

        shutterCore.animate().scaleX(0.84f).scaleY(0.84f).setDuration(80)
                .withEndAction(() -> shutterCore.animate().scaleX(1f).scaleY(1f)
                        .setDuration(160).start()).start();

        final boolean useScreenFlash = facingFront && flashMode == ImageCapture.FLASH_MODE_ON;
        final boolean shotDoubleExposure = doubleExposureMode;
        final Bitmap firstFrameSnapshot = doubleExposureFirstFrame;

        if (useScreenFlash) {
            beginScreenFlash();
            previewView.postDelayed(
                    () -> executeImageCapture(shotDoubleExposure, firstFrameSnapshot, true),
                    120L);
        } else {
            viewfinderOverlay.triggerShutterEffect();
            executeImageCapture(shotDoubleExposure, firstFrameSnapshot, false);
        }
    }

    private void executeImageCapture(
            final boolean shotDoubleExposure,
            final Bitmap firstFrameSnapshot,
            final boolean usedScreenFlash) {
        if (isDestroyed() || isFinishing() || imageCapture == null) {
            if (usedScreenFlash) endScreenFlash();
            capturing = false;
            return;
        }

        final FilmPreset preset = presets[currentPreset];
        final CameraPrefs.AspectRatio shotAspect = aspectRatio;
        final CameraPrefs.PaperStyle shotPaper = paperStyle;
        final CameraPrefs.DateMode shotDate = dateMode;
        final boolean boostGrain = grainBoost;
        final boolean useLeak = lightLeakEnabled;
        final float shotLeakSeed = leakSeed;
        final boolean shotFacingFront = facingFront;
        final boolean mirrorSelfie = CameraTransformMath.shouldMirrorCapture(
                shotFacingFront, frontMirrorEnabled);
        final int shotTargetRotation = deviceSurfaceRotation;
        final boolean shotLandscape = (shotTargetRotation == Surface.ROTATION_90
                || shotTargetRotation == Surface.ROTATION_270);
        final int nextFrame = (exposures % CameraPrefs.FRAMES_PER_ROLL) + 1;

        imageCapture.setTargetRotation(shotTargetRotation);
        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                try {
                    byte[] jpeg;
                    int proxyRotation;
                    try {
                        jpeg = readJpeg(image);
                        proxyRotation = image.getImageInfo().getRotationDegrees();
                    } finally {
                        image.close();
                    }
                    int[] exifTx = exifOrientationTransform(jpeg);

                    Bitmap source = decode(jpeg, MAX_PHOTO_EDGE);
                    if (source == null) throw new IllegalStateException("Cannot decode capture");
                    int rotation = CameraTransformMath.resolveCaptureRotation(
                            exifTx[0],
                            proxyRotation,
                            source.getWidth(),
                            source.getHeight(),
                            shotTargetRotation,
                            autoDegrees,
                            manualRotation);
                    Bitmap oriented = transformBitmap(source, rotation, exifTx[1] != 0, mirrorSelfie);
                    Bitmap cropped = PhotoSaver.cropToAspect(oriented, shotAspect, shotLandscape);

                    if (shotDoubleExposure && firstFrameSnapshot == null) {
                        int gw = Math.min(720, cropped.getWidth());
                        int gh = Math.max(1, Math.round(
                                (float) cropped.getHeight() * gw / Math.max(1, cropped.getWidth())));
                        Bitmap ghost = Bitmap.createScaledBitmap(cropped, gw, gh, true);
                        if (ghost == cropped) {
                            ghost = cropped.copy(Bitmap.Config.ARGB_8888, false);
                        }
                        final Bitmap finalGhost = ghost;
                        runOnUiThread(() -> {
                            if (usedScreenFlash) endScreenFlash();
                            if (isDestroyed() || isFinishing()) {
                                cropped.recycle();
                                finalGhost.recycle();
                                capturing = false;
                                return;
                            }
                            doubleExposureFirstFrame = cropped;
                            viewfinderOverlay.setDoubleExposureGhost(finalGhost);
                            updateDoubleExposureUi();
                            capturing = false;
                        });
                        return;
                    }

                    Bitmap developedInput = cropped;
                    if (shotDoubleExposure && firstFrameSnapshot != null) {
                        Bitmap blended = PhotoSaver.blendDoubleExposure(firstFrameSnapshot, cropped);
                        if (blended != cropped) cropped.recycle();
                        developedInput = blended;
                    }

                    Bitmap filtered = previewView.renderPhoto(
                            developedInput, preset, boostGrain, useLeak, shotLeakSeed);
                    if (filtered != developedInput) developedInput.recycle();

                    Bitmap print = PhotoSaver.applyPaper(
                            filtered, preset, shotPaper, shotDate, shotAspect, nextFrame, null);
                    if (print != filtered) filtered.recycle();

                    Uri savedUri = PhotoSaver.save(MainActivity.this, print, jpeg);
                    int thumbSize = FilmUiHelper.dp(MainActivity.this, 96);
                    Bitmap thumb = Bitmap.createScaledBitmap(print, thumbSize, thumbSize, true);
                    print.recycle();

                    final boolean clearDouble = (firstFrameSnapshot != null);
                    runOnUiThread(() -> {
                        if (usedScreenFlash) endScreenFlash();
                        if (clearDouble) {
                            clearDoubleExposureFrame();
                            doubleExposureMode = false;
                            updateDoubleExposureUi();
                        }
                        onPhotoSaved(savedUri, thumb);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        if (usedScreenFlash) endScreenFlash();
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
                    if (usedScreenFlash) endScreenFlash();
                    capturing = false;
                    Toast.makeText(MainActivity.this,
                            getString(R.string.capture_failed, String.valueOf(exception.getMessage())),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void onPhotoSaved(Uri savedUri, Bitmap thumb) {
        advanceRollAndThumbnail(savedUri, thumb);
        // Advance the organic leak seed for the next exposure so consecutive
        // shots on the roll get subtle edge variation while staying WYSIWYG.
        leakSeed = (leakSeed + 0.37f) % 1.0f;
        previewView.setLeakSeed(leakSeed);
        toast(R.string.saved_to_gallery);
    }

    private void onExternalPrintSaved(Uri savedUri, Bitmap thumb) {
        advanceRollAndThumbnail(savedUri, thumb);
    }

    private void advanceRollAndThumbnail(Uri savedUri, Bitmap thumb) {
        prefs.setLastPhotoUri(savedUri);
        setThumbnailBitmap(thumb);

        exposures++;
        if (exposures >= CameraPrefs.FRAMES_PER_ROLL) {
            int finishedRoll = rollNumber;
            rollNumber++;
            exposures = 0;
            prefs.setRollNumber(rollNumber);
            Toast.makeText(this,
                    getString(R.string.new_roll_loaded, finishedRoll, rollNumber),
                    Toast.LENGTH_LONG).show();
        }
        prefs.setExposures(exposures);
        updateRollCounter();
        capturing = false;
    }

    private void setThumbnailBitmap(Bitmap thumb) {
        if (thumb != null) {
            thumbnail.setImageBitmap(thumb);
            thumbPlaceholder.setVisibility(View.GONE);
        } else {
            thumbnail.setImageDrawable(null);
            thumbPlaceholder.setVisibility(View.VISIBLE);
        }
    }

    private void refreshThumbnailAsync() {
        cameraExecutor.execute(() -> {
            Uri target = prefs.getLastPhotoUri();
            Bitmap bmp = target != null ? PhotoSaver.decodeScaledUri(this, target, 192) : null;
            if (bmp == null) {
                List<PhotoSaver.PhotoItem> list = PhotoSaver.loadAlbumPhotos(this);
                if (!list.isEmpty()) {
                    target = list.get(0).uri;
                    prefs.setLastPhotoUri(target);
                    bmp = PhotoSaver.decodeScaledUri(this, target, 192);
                } else {
                    prefs.setLastPhotoUri(null);
                }
            }
            Bitmap finalBmp = bmp;
            runOnUiThread(() -> setThumbnailBitmap(finalBmp));
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                && permissionGate.getVisibility() != View.VISIBLE) {
            if (event.getRepeatCount() == 0) {
                takePhoto();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ bitmap helpers

    private static byte[] readJpeg(ImageProxy image) {
        java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static int[] exifOrientationTransform(byte[] jpeg) {
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            return CameraTransformMath.exifOrientationToTransform(o);
        } catch (Exception ignored) {
        }
        return new int[]{0, 0};
    }

    private static Bitmap decode(byte[] jpeg, int maxEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        if (longest <= 0) return null;
        int sample = 1;
        while (longest / sample > maxEdge * 2) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
        return PhotoSaver.capEdge(decoded, maxEdge);
    }

    private static Bitmap transformBitmap(
            Bitmap src, int cwDegrees, boolean flipHBeforeRotate, boolean flipHAfterRotate) {
        if (src == null) return null;
        if (cwDegrees == 0 && !flipHBeforeRotate && !flipHAfterRotate) return src;
        Matrix m = new Matrix();
        if (flipHBeforeRotate) m.postScale(-1f, 1f);
        if (cwDegrees != 0) m.postRotate(cwDegrees);
        if (flipHAfterRotate) m.postScale(-1f, 1f);
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src) src.recycle();
        return out;
    }

    // ------------------------------------------------------------------ lifecycle

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityResumed = true;
        previewView.onResume();
        if (orientationListener != null && orientationListener.canDetectOrientation()) {
            orientationListener.enable();
        }
        syncLevelSensorRegistration();
        refreshThumbnailAsync();
    }

    @Override
    protected void onPause() {
        isActivityResumed = false;
        cancelCountdown();
        endScreenFlash();
        if (orientationListener != null) {
            orientationListener.disable();
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(levelSensorListener);
        }
        previewView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelCountdown();
        if (!capturing) {
            clearDoubleExposureFrame();
        }
        MediaActionSound sound = shutterSound;
        shutterSound = null;
        if (sound != null) {
            cameraExecutor.execute(sound::release);
        }
        cameraExecutor.shutdown();
        if (cameraProvider != null) cameraProvider.unbindAll();
    }
}
