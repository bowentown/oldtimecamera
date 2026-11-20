package com.bowentown.oldtimecamera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
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
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.media.ExifInterface;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
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
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main viewfinder activity: CameraX preview & capture, unified OpenGL ES film
 * emulation, tactile shutter, darkroom controls, and gallery import/archive.
 */
public class MainActivity extends ComponentActivity {

    private static final int REQUEST_PERMISSIONS = 1001;
    private static final int MAX_PHOTO_EDGE = 2560;
    private static final long FACE_STALE_MS = 900L;
    private static final long TAP_SUBJECT_TTL_MS = 4000L;
    private static final long FEEDBACK_HINT_MS = 1350L;
    private static final long EXPOSURE_HINT_AUTO_FADE_MS = 4800L;
    private static final long STEADY_WAIT_MAX_MS = 1500L;
    private static final long SNAP_HAPTIC_COOLDOWN_MS = 900L;

    private CameraPrefs prefs;
    private FilmPreset[] presets;
    private List<FilmRecipe.SavedRecipe> savedRecipes = new ArrayList<>();
    private final List<LinearLayout> chips = new ArrayList<>();

    private AspectFrameLayout previewHost;
    private FilmCameraView previewView;
    private ViewfinderOverlayView viewfinderOverlay;
    private TextView rollCounter, dateStampPreview, countdownText;
    private TextView zoomPill, aspectPill, evPill, evValueText, timerLabel;
    private TextView assistHintPill;
    private TextView recipePanelTitle, recipeIntensityVal, recipeGrainVal, recipeWarmthVal;
    private LinearLayout recipePanel;
    private ImageButton aiCoachButton;
    private ImageButton flashButton, gridPill, modePill;
    private ImageView timerIcon, thumbnail, thumbPlaceholder;
    private HorizontalScrollView filmStripScroll;
    private LinearLayout chipContainer, evPanel;
    private SeekBar evSeekBar, recipeIntensitySeek, recipeGrainSeek, recipeWarmthSeek;
    private FrameLayout shutterButton, thumbnailFrame;
    private ShutterProgressView shutterProgress;
    private View shutterCore, flashOverlay, permissionGate;

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger bindGeneration = new AtomicInteger(0);
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private volatile CameraInfo boundCameraInfo;
    private ImageCapture imageCapture;
    private MediaActionSound shutterSound;

    private int currentPreset = 1;
    private int selectedChipIndex = 1;
    private String activeCustomRecipeId = "";
    private FilmRecipe filmRecipe = FilmRecipe.DEFAULT;
    private volatile boolean facingFront = false;
    private int flashMode = ImageCapture.FLASH_MODE_AUTO;
    private CameraPrefs.AspectRatio aspectRatio = CameraPrefs.AspectRatio.RATIO_3_4;
    private CameraPrefs.PaperStyle paperStyle = CameraPrefs.PaperStyle.IVORY;
    private CameraPrefs.DateMode dateMode = CameraPrefs.DateMode.TODAY;
    private CameraPrefs.ShootMode shootMode = CameraPrefs.ShootMode.SINGLE;
    private boolean grainBoost = false;
    private boolean lightLeakEnabled = true;
    private boolean blackMistEnabled = false;
    private CompositionMath.GridMode gridMode = CompositionMath.GridMode.OFF;
    private int goldenRotation = 0;
    private boolean levelEnabled = false;
    private boolean shutterSoundEnabled = true;
    private volatile boolean frontMirrorEnabled = true;
    private volatile boolean assistMoveGuide = true;
    private volatile boolean assistExposure = true;
    private volatile boolean assistSteadyShutter = true;
    private Bitmap doubleExposureFirstFrame = null;
    private Bitmap halfFirstFrame = null;
    private boolean halfFirstLandscape = false;
    private int timerSeconds = 0;
    private int rollNumber = 1;
    private int exposures = 0;
    private float leakSeed = 0.28f;

    private volatile boolean capturing = false;
    /** Whether a runtime permission dialog has already been shown once this session. */
    private boolean permissionAsked = false;
    /** Pending front-camera screen-flash capture; removable so a backgrounded app cannot lock the shutter. */
    private Runnable pendingScreenFlashCapture = null;
    @SuppressWarnings("unused")
    private boolean latestAxisSwapped = true;
    private CountDownTimer activeTimer;
    private float currentZoom = 1f;
    private int evMin = 0, evMax = 0;
    private float evStep = 0.333f;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CompositionMath.SnapState snapState = new CompositionMath.SnapState();
    private final CompositionMath.MoveGuideState moveGuideState = new CompositionMath.MoveGuideState();
    private final ExposureAdvisor exposureAdvisor = new ExposureAdvisor();
    private final StabilityMeter stabilityMeter = new StabilityMeter();
    private Sensor gyroSensor;
    private boolean stabilitySensorRegistered = false;
    private boolean steadyWaiting = false;
    private long steadyWaitStartMs = 0L;
    private volatile int previewRelativeSensorRotation = 90;
    private volatile Rect sensorActiveArray = null;
    private volatile float latestFrameScaleX = 1f, latestFrameScaleY = 1f;
    private volatile float lastFaceActiveX = Float.NaN, lastFaceActiveY = Float.NaN;
    private volatile CompositionMath.ActiveBox lastFaceBox = null;
    private volatile long lastFaceActiveMs = 0L;
    private volatile long lastFaceUiTickMs = 0L;
    private volatile CompositionMath.Guide3DCue latestGuideCue = CompositionMath.Guide3DCue.EMPTY;
    private float lastTapSubjectX = Float.NaN, lastTapSubjectY = Float.NaN;
    private long lastTapSubjectMs = 0L;
    private final RectF latestFrameRect = new RectF();
    private float lastStatsSubjectX = Float.NaN, lastStatsSubjectY = Float.NaN;
    private long lastStatsSubjectMs = 0L;
    private PreviewStats latestStats = null;
    private float latestTiltDeg = 0f;
    private volatile float latestCameraElevationDeg = 0f;
    private long lastPitchUiTickMs = 0L;
    private String activeHintText = null;
    private boolean activeHintClickable = false;
    private int transientHintRes = 0;
    private long transientHintUntilMs = 0L;
    private ExposureAdvisor.Advice lastShownExposureAdvice = ExposureAdvisor.Advice.NONE;
    private boolean exposureHintAutoFaded = false;
    private boolean syncingRecipeSliders = false;
    private String pendingAiRecipeName = "";
    private volatile boolean aiCoachBusy = false;
    private volatile boolean aiDirectorThinking = false;
    private volatile boolean lastLockedState = false;
    private final AtomicInteger aiCoachGen = new AtomicInteger(0);
    private volatile AiCoachMath.AiDirectorPlan activeDirectorPlan = null;

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
    private float smoothGravityX = 0f, smoothGravityY = 9.81f, smoothGravityZ = 0f;
    private final SensorEventListener levelSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            boolean needPitch = assistMoveGuide || activeDirectorPlan != null;
            if ((!levelEnabled && !needPitch) || event.values == null || event.values.length < 2) {
                return;
            }
            smoothGravityX = smoothGravityX * 0.82f + event.values[0] * 0.18f;
            smoothGravityY = smoothGravityY * 0.82f + event.values[1] * 0.18f;
            if (event.values.length >= 3) {
                smoothGravityZ = smoothGravityZ * 0.82f + event.values[2] * 0.18f;
            }
            float tiltDeg = (float) Math.toDegrees(Math.atan2(-smoothGravityX, smoothGravityY));
            latestTiltDeg = tiltDeg;
            if (levelEnabled) {
                viewfinderOverlay.setTiltDegrees(tiltDeg);
            }
            latestCameraElevationDeg = CompositionMath.computeCameraElevationDegrees(
                    smoothGravityX, smoothGravityY, smoothGravityZ, facingFront);
            if (needPitch) {
                long now = SystemClock.uptimeMillis();
                if (now - lastPitchUiTickMs >= 95L) {
                    lastPitchUiTickMs = now;
                    mainHandler.post(faceMoveTickTask);
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private final SensorEventListener stabilitySensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!assistSteadyShutter || event.values == null || event.values.length < 3) {
                return;
            }
            if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                stabilityMeter.pushGyro(event.values[0], event.values[1], event.values[2], event.timestamp);
            } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                stabilityMeter.pushAccelerometer(event.values[0], event.values[1], event.values[2], event.timestamp);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private final Runnable clearTransientHintTask = () -> {
        transientHintRes = 0;
        transientHintUntilMs = 0L;
        refreshAssistHint();
    };

    private final Runnable autoFadeExposureTask = () -> {
        exposureHintAutoFaded = true;
        refreshAssistHint();
    };

    private final Runnable faceMoveTickTask = () -> {
        if (!isDestroyed() && !isFinishing()) {
            refreshMoveGuide();
        }
    };

    private final Runnable steadyTickTask = new Runnable() {
        @Override
        public void run() {
            if (!steadyWaiting || !isActivityResumed || isDestroyed() || isFinishing()) {
                return;
            }
            long elapsed = SystemClock.uptimeMillis() - steadyWaitStartMs;
            if (stabilityMeter.isStable() || elapsed >= STEADY_WAIT_MAX_MS || !assistSteadyShutter) {
                cancelSteadyWait(false);
                captureNow();
                return;
            }
            shutterProgress.setWaitingProgress(true, elapsed / (float) STEADY_WAIT_MAX_MS);
            mainHandler.postDelayed(this, 16L);
        }
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
        rebuildFilmStrip();
        wireControls();
        initOrientationAndLevelSensors();

        shutterSound = new MediaActionSound();
        cameraExecutor.execute(() -> shutterSound.load(MediaActionSound.SHUTTER_CLICK));

        applyPresetSelection(selectedChipIndex, false);
        applyAspectRatio(false);
        updateGridUi();
        updateLevelUi();
        updateModeUi();
        updateTimerUi();
        updateStampPreview();
        updateRollCounter();
        syncAssistSensorsAndTelemetry();
        refreshThumbnailAsync();
        ensurePermissions();
    }

    private void loadPrefs() {
        currentPreset = FilmPresets.indexOfId(prefs.getPresetId("warm400"));
        savedRecipes = prefs.getSavedRecipes();
        activeCustomRecipeId = prefs.getActiveCustomRecipeId();
        filmRecipe = prefs.getFilmRecipe();
        selectedChipIndex = resolveInitialChipIndex();
        aspectRatio = prefs.getAspectRatio();
        paperStyle = prefs.getPaperStyle();
        dateMode = prefs.getDateMode();
        shootMode = prefs.getShootMode();
        grainBoost = prefs.isGrainBoost();
        lightLeakEnabled = prefs.isLightLeakEnabled();
        blackMistEnabled = prefs.isBlackMistEnabled();
        gridMode = prefs.getGridMode();
        goldenRotation = prefs.getGoldenRotation();
        levelEnabled = prefs.isLevelEnabled();
        shutterSoundEnabled = prefs.isShutterSoundEnabled();
        frontMirrorEnabled = prefs.isFrontMirrorEnabled();
        assistMoveGuide = prefs.isAssistMoveGuide();
        assistExposure = prefs.isAssistExposure();
        assistSteadyShutter = prefs.isAssistSteadyShutter();
        timerSeconds = prefs.getTimerSeconds();
        manualRotation = prefs.getManualRotation();
        rollNumber = prefs.getRollNumber();
        exposures = prefs.getExposures();
    }

    private int resolveInitialChipIndex() {
        if (activeCustomRecipeId != null && !activeCustomRecipeId.isEmpty()) {
            for (int i = 0; i < savedRecipes.size(); i++) {
                if (activeCustomRecipeId.equals(savedRecipes.get(i).id)) {
                    FilmRecipe.SavedRecipe r = savedRecipes.get(i);
                    currentPreset = FilmPresets.indexOfId(r.basePresetId);
                    filmRecipe = r.recipe;
                    return presets.length + i;
                }
            }
            activeCustomRecipeId = "";
        }
        return currentPreset;
    }

    // ------------------------------------------------------------------ view setup

    private void bindViews() {
        previewHost = findViewById(R.id.previewHost);
        previewView = findViewById(R.id.preview);
        viewfinderOverlay = findViewById(R.id.viewfinderOverlay);
        aiCoachButton = findViewById(R.id.aiCoachButton);
        rollCounter = findViewById(R.id.rollCounter);
        dateStampPreview = findViewById(R.id.dateStamp);
        countdownText = findViewById(R.id.countdownText);
        assistHintPill = findViewById(R.id.assistHintPill);
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
        modePill = findViewById(R.id.modePill);
        recipePanel = findViewById(R.id.recipePanel);
        recipePanelTitle = findViewById(R.id.recipePanelTitle);
        recipeIntensitySeek = findViewById(R.id.recipeIntensitySeek);
        recipeGrainSeek = findViewById(R.id.recipeGrainSeek);
        recipeWarmthSeek = findViewById(R.id.recipeWarmthSeek);
        recipeIntensityVal = findViewById(R.id.recipeIntensityVal);
        recipeGrainVal = findViewById(R.id.recipeGrainVal);
        recipeWarmthVal = findViewById(R.id.recipeWarmthVal);
        filmStripScroll = findViewById(R.id.filmStripScroll);
        chipContainer = findViewById(R.id.chipContainer);
        shutterButton = findViewById(R.id.shutterButton);
        shutterProgress = findViewById(R.id.shutterProgress);
        shutterCore = findViewById(R.id.shutterCore);
        flashOverlay = findViewById(R.id.flashOverlay);
        permissionGate = findViewById(R.id.permissionGate);
        thumbnail = findViewById(R.id.thumbnail);
        thumbPlaceholder = findViewById(R.id.thumbPlaceholder);
        thumbnailFrame = findViewById(R.id.thumbnailFrame);
        thumbnailFrame.setClipToOutline(true);
    }

    private void wireControls() {
        shutterButton.setOnClickListener(v -> takePhoto());
        flashButton.setOnClickListener(v -> cycleFlash());
        findViewById(R.id.timerButton).setOnClickListener(v -> cycleTimer());
        findViewById(R.id.switchButton).setOnClickListener(v -> switchLens());
        findViewById(R.id.settingsButton).setOnClickListener(v -> openDarkroomSheet());
        findViewById(R.id.grantButton).setOnClickListener(v -> ensurePermissions());

        thumbnailFrame.setOnClickListener(v -> {
            cancelSteadyWait(true);
            startActivity(new Intent(this, GalleryActivity.class));
        });
        findViewById(R.id.importButton).setOnClickListener(v -> {
            cancelSteadyWait(true);
            pickPhotoLauncher.launch("image/*");
        });

        aspectPill.setOnClickListener(v -> {
            if (capturing) {
                toast(R.string.capture_busy);
                return;
            }
            cancelSteadyWait(true);
            aspectRatio = aspectRatio.next();
            prefs.setAspectRatio(aspectRatio);
            clearPendingMultiFrame();
            updateModeUi();
            applyAspectRatio(true);
        });

        aiCoachButton.setOnClickListener(v -> toggleAiDirector());

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

        evPill.setOnClickListener(v -> {
            boolean show = evPanel.getVisibility() != View.VISIBLE;
            closeBottomDrawers(show ? evPanel : null);
            evPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            refreshMoveGuide();
        });

        gridPill.setOnClickListener(v -> {
            gridMode = gridMode.next();
            prefs.setGridMode(gridMode);
            snapState.reset();
            viewfinderOverlay.setPowerPointHighlight(-1);
            updateGridUi();
            syncAssistSensorsAndTelemetry();
            int msg;
            switch (gridMode) {
                case THIRDS: msg = R.string.hint_grid_thirds; break;
                case GOLDEN_SPIRAL: msg = R.string.hint_grid_golden; break;
                case CROSSHAIR: msg = R.string.hint_grid_crosshair; break;
                default: msg = R.string.hint_grid_off; break;
            }
            showTransientHint(msg);
        });
        gridPill.setOnLongClickListener(v -> {
            if (gridMode != CompositionMath.GridMode.GOLDEN_SPIRAL) {
                return false;
            }
            goldenRotation = (goldenRotation + 1) % 4;
            prefs.setGoldenRotation(goldenRotation);
            snapState.reset();
            viewfinderOverlay.setPowerPointHighlight(-1);
            updateGridUi();
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showTransientHint(R.string.hint_golden_rotated);
            return true;
        });

        assistHintPill.setOnClickListener(v -> handleAssistHintClick());
        modePill.setOnClickListener(v -> cycleShootMode());

        wireRecipeSliders();

        evSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && camera != null && evMax > evMin) {
                    int idx = evMin + progress;
                    camera.getCameraControl().setExposureCompensationIndex(idx);
                    updateEvUi(idx);
                    exposureAdvisor.onHintApplied(SystemClock.uptimeMillis());
                    lastShownExposureAdvice = ExposureAdvisor.Advice.NONE;
                    exposureHintAutoFaded = false;
                    mainHandler.removeCallbacks(autoFadeExposureTask);
                    refreshAssistHint();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        viewfinderOverlay.setOnFrameBoundsListener((rect, vw, vh) -> {
            latestFrameRect.set(rect);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dateStampPreview.getLayoutParams();
            lp.rightMargin = Math.max(0, Math.round(vw - rect.right)) + FilmUiHelper.dp(this, 8);
            lp.bottomMargin = Math.max(0, Math.round(vh - rect.bottom)) + FilmUiHelper.dp(this, 8);
            dateStampPreview.setLayoutParams(lp);

            FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams) aiCoachButton.getLayoutParams();
            ilp.leftMargin = Math.max(0, Math.round(rect.left)) + FilmUiHelper.dp(this, 8);
            ilp.bottomMargin = Math.max(0, Math.round(vh - rect.bottom)) + FilmUiHelper.dp(this, 8);
            aiCoachButton.setLayoutParams(ilp);
        });

        previewView.setOnAxisSwapListener(swapped -> updateViewfinderAspectRatio(swapped, false));
        previewView.setOnPreviewStatsListener(this::handlePreviewStats);
        previewView.setOnRendererErrorListener(message ->
                Toast.makeText(this,
                        getString(R.string.render_init_failed, String.valueOf(message)),
                        Toast.LENGTH_LONG).show());

        previewView.setOnGestureListener(new FilmCameraView.OnGestureListener() {
            @Override
            public void onTapFocus(float viewX, float viewY, int viewWidth, int viewHeight) {
                closeBottomDrawers(null);
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
                int total = presets.length + savedRecipes.size();
                int next = (selectedChipIndex + direction + total) % total;
                shutterButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                applyPresetSelection(next, true);
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
                    boolean wasLandscape = PhotoSaver.isLandscapeRotation(deviceSurfaceRotation);
                    deviceSurfaceRotation = next;
                    if (imageCapture != null) {
                        imageCapture.setTargetRotation(next);
                    }
                    // Rotating the phone flips the effective print ratio (3:4 <-> 4:3),
                    // so the viewfinder mask has to follow immediately.
                    if (wasLandscape != PhotoSaver.isLandscapeRotation(next)) {
                        runOnUiThread(() -> {
                            if (!capturing) applyAspectRatio(false);
                        });
                    }
                }
            }
        };
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
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

    private void rebuildFilmStrip() {
        FilmUiHelper.buildFilmStrip(this, filmStripScroll, chipContainer, presets, savedRecipes,
                chips,
                idx -> {
                    shutterButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    applyPresetSelection(idx, true);
                },
                idx -> {
                    if (idx >= presets.length) {
                        promptDeleteCustomRecipe(idx - presets.length);
                    } else {
                        if (selectedChipIndex != idx) {
                            applyPresetSelection(idx, true);
                        }
                        openRecipePanel();
                    }
                    return true;
                });
    }

    private void applyPresetSelection(int chipIndex, boolean resetManualRecipeOnBuiltin) {
        int total = presets.length + savedRecipes.size();
        if (chipIndex < 0 || chipIndex >= total) {
            chipIndex = Math.max(0, Math.min(presets.length - 1, currentPreset));
        }
        selectedChipIndex = chipIndex;
        if (chipIndex < presets.length) {
            currentPreset = chipIndex;
            activeCustomRecipeId = "";
            prefs.setActiveCustomRecipeId("");
            if (resetManualRecipeOnBuiltin) {
                filmRecipe = FilmRecipe.DEFAULT;
                prefs.setFilmRecipe(filmRecipe);
            }
        } else {
            FilmRecipe.SavedRecipe saved = savedRecipes.get(chipIndex - presets.length);
            currentPreset = FilmPresets.indexOfId(saved.basePresetId);
            activeCustomRecipeId = saved.id;
            filmRecipe = saved.recipe;
            prefs.setActiveCustomRecipeId(saved.id);
            prefs.setFilmRecipe(filmRecipe);
        }

        FilmPreset base = presets[currentPreset];
        pendingAiRecipeName = "";
        prefs.setPresetId(base.id);
        applyActiveLookToPreview();

        FilmUiHelper.updateChipSelection(this, chips, chipIndex);
        FilmUiHelper.centerChip(filmStripScroll, chips.get(chipIndex));
        if (recipePanel.getVisibility() == View.VISIBLE) {
            syncRecipeSlidersFromState();
        }
    }

    private FilmPreset resolveEffectivePreset() {
        return filmRecipe.apply(presets[currentPreset]);
    }

    private void applyActiveLookToPreview() {
        previewView.setPreset(resolveEffectivePreset());
        previewView.setGrainBoost(grainBoost);
        previewView.setLightLeakEnabled(lightLeakEnabled);
        previewView.setBlackMistEnabled(blackMistEnabled);
        previewView.setLeakSeed(leakSeed);
    }

    private void wireRecipeSliders() {
        findViewById(R.id.recipeAiBtn).setOnClickListener(v -> AiRecipeDialog.show(
                this, prefs, presets[currentPreset].id, this::applyAiRecipeProposal));
        findViewById(R.id.recipeResetBtn).setOnClickListener(v -> {
            pendingAiRecipeName = "";
            filmRecipe = FilmRecipe.DEFAULT;
            activeCustomRecipeId = "";
            prefs.setActiveCustomRecipeId("");
            prefs.setFilmRecipe(filmRecipe);
            selectedChipIndex = currentPreset;
            FilmUiHelper.updateChipSelection(this, chips, selectedChipIndex);
            syncRecipeSlidersFromState();
            applyActiveLookToPreview();
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        });
        findViewById(R.id.recipeSaveBtn).setOnClickListener(v -> promptSaveCustomRecipe());

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || syncingRecipeSliders) return;
                float intensity = recipeIntensitySeek.getProgress() / 100f;
                float grain = recipeGrainSeek.getProgress() / 100f;
                float warmth = (recipeWarmthSeek.getProgress() - 10) / 10f;
                filmRecipe = new FilmRecipe(intensity, grain, warmth);
                activeCustomRecipeId = "";
                prefs.setActiveCustomRecipeId("");
                prefs.setFilmRecipe(filmRecipe);
                updateRecipeValueLabels();
                applyActiveLookToPreview();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        recipeIntensitySeek.setOnSeekBarChangeListener(listener);
        recipeGrainSeek.setOnSeekBarChangeListener(listener);
        recipeWarmthSeek.setOnSeekBarChangeListener(listener);
    }

    private void openRecipePanel() {
        closeBottomDrawers(recipePanel);
        syncRecipeSlidersFromState();
        recipePanel.setVisibility(View.VISIBLE);
        refreshMoveGuide();
        shutterButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private void syncRecipeSlidersFromState() {
        syncingRecipeSliders = true;
        recipePanelTitle.setText(getString(R.string.recipe_title, presets[currentPreset].name));
        recipeIntensitySeek.setProgress(Math.round(filmRecipe.intensity * 100f));
        recipeGrainSeek.setProgress(Math.round(filmRecipe.grainScale * 100f));
        recipeWarmthSeek.setProgress(Math.round((filmRecipe.warmth + 1f) * 10f));
        updateRecipeValueLabels();
        syncingRecipeSliders = false;
    }

    private void updateRecipeValueLabels() {
        recipeIntensityVal.setText(String.format(Locale.US, "%d%%", Math.round(filmRecipe.intensity * 100f)));
        recipeGrainVal.setText(String.format(Locale.US, "%d%%", Math.round(filmRecipe.grainScale * 100f)));
        int warmSteps = Math.round(filmRecipe.warmth * 10f);
        recipeWarmthVal.setText(warmSteps == 0 ? "0" : String.format(Locale.US, "%+d", warmSteps));
    }

    private void promptSaveCustomRecipe() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        String defaultName = !pendingAiRecipeName.isEmpty()
                ? pendingAiRecipeName
                : (presets[currentPreset].name + " · " + (savedRecipes.size() + 1));
        input.setText(defaultName);
        input.setSelection(defaultName.length());
        int pad = FilmUiHelper.dp(this, 20);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new AlertDialog.Builder(this)
                .setTitle(R.string.recipe_save)
                .setView(input)
                .setPositiveButton(R.string.recipe_save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = defaultName;
                    String id = "rec_" + System.currentTimeMillis();
                    FilmRecipe.SavedRecipe item = new FilmRecipe.SavedRecipe(
                            id, name, presets[currentPreset].id, filmRecipe);
                    List<FilmRecipe.SavedRecipe> next = new ArrayList<>(savedRecipes);
                    next.add(item);
                    savedRecipes = next;
                    prefs.setSavedRecipes(savedRecipes);
                    activeCustomRecipeId = id;
                    prefs.setActiveCustomRecipeId(id);
                    rebuildFilmStrip();
                    selectedChipIndex = presets.length + savedRecipes.size() - 1;
                    FilmUiHelper.updateChipSelection(this, chips, selectedChipIndex);
                    FilmUiHelper.centerChip(filmStripScroll, chips.get(selectedChipIndex));
                    recipePanel.setVisibility(View.GONE);
                    refreshMoveGuide();
                    Toast.makeText(this, getString(R.string.recipe_saved, name), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void promptDeleteCustomRecipe(int customIndex) {
        if (customIndex < 0 || customIndex >= savedRecipes.size()) return;
        FilmRecipe.SavedRecipe target = savedRecipes.get(customIndex);
        new AlertDialog.Builder(this)
                .setTitle(R.string.recipe_delete_title)
                .setMessage(target.name)
                .setPositiveButton(R.string.recipe_delete_ok, (d, w) -> {
                    List<FilmRecipe.SavedRecipe> next = new ArrayList<>(savedRecipes);
                    next.remove(customIndex);
                    savedRecipes = next;
                    prefs.setSavedRecipes(savedRecipes);
                    boolean wasActive = target.id.equals(activeCustomRecipeId);
                    rebuildFilmStrip();
                    if (wasActive) {
                        applyPresetSelection(FilmPresets.indexOfId(target.basePresetId), true);
                    } else {
                        selectedChipIndex = resolveInitialChipIndex();
                        FilmUiHelper.updateChipSelection(this, chips, selectedChipIndex);
                    }
                    toast(R.string.recipe_deleted);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void applyAiRecipeProposal(AiCoachMath.AiRecipeProposal proposal) {
        if (proposal == null) return;
        int baseIdx = FilmPresets.indexOfId(proposal.basePresetId);
        applyPresetSelection(baseIdx, false);
        filmRecipe = proposal.recipe;
        prefs.setFilmRecipe(filmRecipe);
        activeCustomRecipeId = "";
        prefs.setActiveCustomRecipeId("");
        pendingAiRecipeName = proposal.name;
        if (blackMistEnabled != proposal.blackMist) {
            blackMistEnabled = proposal.blackMist;
            prefs.setBlackMistEnabled(blackMistEnabled);
        }
        applyActiveLookToPreview();
        syncRecipeSlidersFromState();
        if (!proposal.name.isEmpty()) {
            recipePanelTitle.setText(proposal.name);
        }
        recipePanel.setVisibility(View.VISIBLE);
        closeBottomDrawers(recipePanel);
        shutterButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        String msg = !proposal.reason.isEmpty()
                ? getString(R.string.ai_recipe_applied, proposal.name, proposal.reason)
                : getString(R.string.ai_recipe_applied_short, proposal.name);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void closeBottomDrawers(View except) {
        if (evPanel != except && evPanel.getVisibility() == View.VISIBLE) {
            evPanel.setVisibility(View.GONE);
        }
        if (recipePanel != except && recipePanel.getVisibility() == View.VISIBLE) {
            recipePanel.setVisibility(View.GONE);
        }
        refreshMoveGuide();
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
        // The print pipeline flips portrait ratios when the phone is held sideways, so the
        // viewfinder mask must use the exact same effective ratio or the frame lies.
        float targetRatio = PhotoSaver.previewCropRatio(aspectRatio, deviceSurfaceRotation);
        float[] scales = CameraTransformMath.computeFrameScale(
                previewView.getSensorAspectRatio(), targetRatio);
        latestFrameScaleX = scales[0];
        latestFrameScaleY = scales[1];
        lastTapSubjectMs = 0L;
        lastFaceBox = null;
        lastFaceActiveMs = 0L;
        moveGuideState.reset();
        previewView.setFrameScale(scales[0], scales[1]);
        viewfinderOverlay.setFrameScale(scales[0], scales[1], animate);
        refreshMoveGuide();
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
        viewfinderOverlay.setGridMode(gridMode);
        viewfinderOverlay.setGoldenRotation(goldenRotation);
        if (gridMode == CompositionMath.GridMode.GOLDEN_SPIRAL) {
            gridPill.setImageResource(R.drawable.ic_spiral);
        } else if (gridMode == CompositionMath.GridMode.CROSSHAIR) {
            gridPill.setImageResource(R.drawable.ic_crosshair);
        } else {
            gridPill.setImageResource(R.drawable.ic_grid);
        }
        boolean active = gridMode != CompositionMath.GridMode.OFF;
        gridPill.setColorFilter(getColor(active ? R.color.amber_led : R.color.paper));
    }

    private void updateLevelUi() {
        viewfinderOverlay.setLevelEnabled(levelEnabled);
        syncLevelSensorRegistration();
    }

    private void syncLevelSensorRegistration() {
        if (sensorManager == null || accelerometer == null) return;
        sensorManager.unregisterListener(levelSensorListener);
        if (isActivityResumed && (levelEnabled || assistMoveGuide || activeDirectorPlan != null)) {
            sensorManager.registerListener(levelSensorListener, accelerometer,
                    SensorManager.SENSOR_DELAY_UI);
        }
    }

    private void syncAssistSensorsAndTelemetry() {
        boolean needTelemetry = assistExposure || assistMoveGuide || activeDirectorPlan != null
                || gridMode != CompositionMath.GridMode.OFF;
        previewView.setTelemetryEnabled(needTelemetry);
        syncLevelSensorRegistration();

        if (!assistExposure) {
            exposureAdvisor.reset();
            lastShownExposureAdvice = ExposureAdvisor.Advice.NONE;
            exposureHintAutoFaded = false;
            mainHandler.removeCallbacks(autoFadeExposureTask);
        }
        if (!assistMoveGuide && activeDirectorPlan == null) {
            moveGuideState.reset();
            latestGuideCue = CompositionMath.Guide3DCue.EMPTY;
            lastLockedState = false;
            if (shutterProgress != null) {
                shutterProgress.setReadyLocked(false);
            }
            viewfinderOverlay.setGuide3DCue(CompositionMath.Guide3DCue.EMPTY, "");
        }
        if (gridMode == CompositionMath.GridMode.OFF) {
            snapState.reset();
            viewfinderOverlay.setPowerPointHighlight(-1);
        }
        if (!isActivityResumed || !assistSteadyShutter) {
            cancelSteadyWait(false);
        }

        if (sensorManager != null) {
            sensorManager.unregisterListener(stabilitySensorListener);
            stabilitySensorRegistered = false;
            if (isActivityResumed && assistSteadyShutter) {
                if (gyroSensor != null) {
                    stabilitySensorRegistered = sensorManager.registerListener(
                            stabilitySensorListener, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
                }
                if (!stabilitySensorRegistered && accelerometer != null) {
                    stabilitySensorRegistered = sensorManager.registerListener(
                            stabilitySensorListener, accelerometer, SensorManager.SENSOR_DELAY_GAME);
                }
            }
            if (!stabilitySensorRegistered) {
                stabilityMeter.reset();
            }
        } else {
            stabilitySensorRegistered = false;
            stabilityMeter.reset();
        }
        refreshAssistHint();
        refreshMoveGuide();
    }

    private void updatePreviewRelativeSensorRotation(boolean front, int displayRotation) {
        CameraInfo ci = boundCameraInfo;
        if (ci == null) return;
        previewRelativeSensorRotation = ci.getSensorRotationDegrees(displayRotation);
    }

    private ExposureAdvisor.Advice effectiveExposureAdvice() {
        ExposureAdvisor.Advice advice = exposureAdvisor.getCurrentAdvice();
        if (advice == ExposureAdvisor.Advice.OVEREXPOSED) {
            if (evMax <= evMin || camera == null
                    || camera.getCameraInfo().getExposureState().getExposureCompensationIndex() <= evMin) {
                return ExposureAdvisor.Advice.NONE;
            }
        } else if (advice == ExposureAdvisor.Advice.UNDEREXPOSED) {
            if (evMax <= evMin || camera == null
                    || camera.getCameraInfo().getExposureState().getExposureCompensationIndex() >= evMax) {
                return ExposureAdvisor.Advice.NONE;
            }
        }
        return advice;
    }

    private boolean isChineseLocale() {
        return getResources().getConfiguration().getLocales().get(0)
                .getLanguage().startsWith("zh");
    }

    private void handlePreviewStats(PreviewStats stats) {
        if (stats == null || stats.width <= 0 || stats.height <= 0
                || isDestroyed() || isFinishing()) {
            return;
        }
        latestStats = stats;

        long now = SystemClock.uptimeMillis();
        lastStatsSubjectX = stats.saliencyConfidence >= 0.22f ? stats.saliencyX : Float.NaN;
        lastStatsSubjectY = stats.saliencyConfidence >= 0.22f ? stats.saliencyY : Float.NaN;
        lastStatsSubjectMs = stats.saliencyConfidence >= 0.22f ? now : 0L;

        if (assistExposure) {
            exposureAdvisor.evaluate(stats, now);
            ExposureAdvisor.Advice advice = effectiveExposureAdvice();
            if (advice != lastShownExposureAdvice) {
                lastShownExposureAdvice = advice;
                exposureHintAutoFaded = false;
                mainHandler.removeCallbacks(autoFadeExposureTask);
                if (advice != ExposureAdvisor.Advice.NONE) {
                    mainHandler.postDelayed(autoFadeExposureTask, EXPOSURE_HINT_AUTO_FADE_MS);
                }
            }
        } else {
            exposureAdvisor.reset();
            lastShownExposureAdvice = ExposureAdvisor.Advice.NONE;
            exposureHintAutoFaded = false;
            mainHandler.removeCallbacks(autoFadeExposureTask);
        }

        if (gridMode != CompositionMath.GridMode.OFF) {
            boolean freshFace = (now - lastFaceActiveMs) <= FACE_STALE_MS && !Float.isNaN(lastFaceActiveX);
            float subX = Float.NaN;
            float subY = Float.NaN;
            if (freshFace) {
                subX = lastFaceActiveX;
                subY = lastFaceActiveY;
            } else if ((now - lastTapSubjectMs) <= TAP_SUBJECT_TTL_MS && !Float.isNaN(lastTapSubjectX)) {
                subX = lastTapSubjectX;
                subY = lastTapSubjectY;
            } else if (stats.saliencyConfidence >= 0.22f) {
                subX = stats.saliencyX;
                subY = stats.saliencyY;
            }
            if (!Float.isNaN(subX) && !Float.isNaN(subY)) {
                float[][] pts = CompositionMath.powerPoints(gridMode, goldenRotation);
                boolean haptic = snapState.update(subX, subY, pts, now, SNAP_HAPTIC_COOLDOWN_MS);
                viewfinderOverlay.setPowerPointHighlight(snapState.getLockedPointIndex());
                if (haptic && !capturing) {
                    viewfinderOverlay.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
            } else {
                snapState.reset();
                viewfinderOverlay.setPowerPointHighlight(-1);
            }
        } else {
            snapState.reset();
            viewfinderOverlay.setPowerPointHighlight(-1);
        }

        refreshAssistHint();
        refreshMoveGuide();
    }

    private void refreshMoveGuide() {
        if (viewfinderOverlay == null) return;
        boolean guideEnabled = assistMoveGuide || activeDirectorPlan != null;
        boolean suppressed = !guideEnabled
                || !isActivityResumed
                || capturing
                || steadyWaiting
                || activeTimer != null
                || aiDirectorThinking
                || (permissionGate != null && permissionGate.getVisibility() == View.VISIBLE)
                || (evPanel != null && evPanel.getVisibility() == View.VISIBLE)
                || (recipePanel != null && recipePanel.getVisibility() == View.VISIBLE);
        if (suppressed) {
            moveGuideState.reset();
            latestGuideCue = CompositionMath.Guide3DCue.EMPTY;
            lastLockedState = false;
            if (shutterProgress != null) {
                shutterProgress.setReadyLocked(false);
            }
            viewfinderOverlay.setGuide3DCue(CompositionMath.Guide3DCue.EMPTY, "");
            return;
        }

        long now = SystemClock.uptimeMillis();
        CompositionMath.ActiveBox faceSnapshot = lastFaceBox;
        boolean freshFace = faceSnapshot != null && (now - lastFaceActiveMs) <= FACE_STALE_MS;
        boolean hasTap = (now - lastTapSubjectMs) <= TAP_SUBJECT_TTL_MS && !Float.isNaN(lastTapSubjectX);
        AiCoachMath.AiDirectorPlan plan = activeDirectorPlan;
        CompositionMath.DirectorTarget directorTarget = plan != null ? plan.target : null;
        CompositionMath.Guide3DCue previous = latestGuideCue;

        CompositionMath.Guide3DCue cue = moveGuideState.evaluate(
                freshFace, faceSnapshot,
                hasTap, lastTapSubjectX, lastTapSubjectY,
                latestStats, gridMode, goldenRotation,
                latestCameraElevationDeg, facingFront, directorTarget, now);
        latestGuideCue = cue;
        viewfinderOverlay.setGuide3DCue(cue, step3dLabel(cue.step));

        boolean directorLocked = cue.directorActive && cue.locked;
        if (shutterProgress != null) {
            shutterProgress.setReadyLocked(directorLocked);
        }
        if (cue.step == CompositionMath.Step3D.LOCKED
                && previous.step != CompositionMath.Step3D.LOCKED && !capturing) {
            shutterButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        if (directorLocked != lastLockedState) {
            lastLockedState = directorLocked;
            refreshAssistHint();
        }
    }

    private String step3dLabel(CompositionMath.Step3D step) {
        if (step == null) return "";
        switch (step) {
            case STEP_CLOSER: return getString(R.string.step3d_closer);
            case STEP_BACK: return getString(R.string.step3d_back);
            case MOVE_LEFT: return getString(R.string.step3d_left);
            case MOVE_RIGHT: return getString(R.string.step3d_right);
            case MOVE_UP: return getString(R.string.step3d_up);
            case MOVE_DOWN: return getString(R.string.step3d_down);
            case TILT_TOP_AWAY: return getString(R.string.step3d_tilt_away);
            case TILT_TOP_TOWARD: return getString(R.string.step3d_tilt_toward);
            case LOCKED: return getString(R.string.step3d_locked);
            default: return "";
        }
    }

    private void showTransientHint(int resId) {
        showTransientHintText(getString(resId));
    }

    private void showTransientHintText(String text) {
        mainHandler.removeCallbacks(clearTransientHintTask);
        transientHintRes = 0;
        transientHintText = text;
        transientHintUntilMs = SystemClock.uptimeMillis() + FEEDBACK_HINT_MS;
        refreshAssistHint();
        mainHandler.postDelayed(clearTransientHintTask, FEEDBACK_HINT_MS);
    }

    private String transientHintText = null;

    private void refreshAssistHint() {
        if (assistHintPill == null) return;
        long now = SystemClock.uptimeMillis();
        String nextText = null;
        boolean clickable = false;

        if (steadyWaiting) {
            nextText = getString(R.string.hint_steady_wait);
        } else if (transientHintText != null && now < transientHintUntilMs) {
            nextText = transientHintText;
        } else if (transientHintRes != 0 && now < transientHintUntilMs) {
            nextText = getString(transientHintRes);
        } else if (aiDirectorThinking) {
            nextText = getString(R.string.ai_director_thinking);
        } else if (activeDirectorPlan != null && activeTimer == null && !capturing
                && permissionGate.getVisibility() != View.VISIBLE) {
            nextText = (lastLockedState
                    ? getString(R.string.ai_director_locked)
                    : activeDirectorPlan.displayBannerText()) + "  ×";
            clickable = true;
        } else if (shootMode == CameraPrefs.ShootMode.HALF_FRAME && halfFirstFrame != null && !capturing) {
            nextText = getString(R.string.hint_half_first);
        } else if (activeTimer == null && !capturing
                && permissionGate.getVisibility() != View.VISIBLE
                && assistExposure && !exposureHintAutoFaded) {
            ExposureAdvisor.Advice advice = effectiveExposureAdvice();
            if (advice == ExposureAdvisor.Advice.OVEREXPOSED) {
                nextText = getString(R.string.hint_overexposed);
            } else if (advice == ExposureAdvisor.Advice.UNDEREXPOSED) {
                nextText = getString(R.string.hint_underexposed);
            } else if (advice == ExposureAdvisor.Advice.BACKLIT) {
                nextText = getString(R.string.hint_backlit);
            }
            clickable = nextText != null;
        }

        if (nextText == null) {
            if (activeHintText != null) {
                activeHintText = null;
                activeHintClickable = false;
                assistHintPill.setClickable(false);
                assistHintPill.animate().cancel();
                assistHintPill.animate().alpha(0f).setDuration(180L).withEndAction(() -> {
                    if (activeHintText == null) {
                        assistHintPill.setVisibility(View.GONE);
                    }
                }).start();
            }
            return;
        }

        boolean same = nextText.equals(activeHintText) && clickable == activeHintClickable;
        activeHintText = nextText;
        activeHintClickable = clickable;
        assistHintPill.setClickable(clickable);
        boolean amberTint = steadyWaiting || aiDirectorThinking || activeDirectorPlan != null
                || clickable || (shootMode == CameraPrefs.ShootMode.HALF_FRAME && halfFirstFrame != null);
        if (!same) {
            assistHintPill.setText(nextText);
            assistHintPill.setTextColor(getColor(amberTint ? R.color.amber_led : R.color.paper));
        }
        if (assistHintPill.getVisibility() != View.VISIBLE || assistHintPill.getAlpha() < 0.99f) {
            assistHintPill.animate().cancel();
            assistHintPill.setVisibility(View.VISIBLE);
            assistHintPill.animate().alpha(1f).setDuration(160L).start();
        }
    }

    private void handleAssistHintClick() {
        if (activeDirectorPlan != null) {
            assistHintPill.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            clearDirectorPlan();
            return;
        }
        applyExposureHint();
    }

    private void toggleAiDirector() {
        if (aiCoachBusy || activeDirectorPlan != null) {
            shutterButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            clearDirectorPlan();
            return;
        }
        if (!AiKeyStore.hasApiKey(this)) {
            AiSettingsDialog.show(this, prefs, this::toggleAiDirector);
            return;
        }
        closeBottomDrawers(null);
        aiCoachBusy = true;
        aiDirectorThinking = true;
        lastLockedState = false;
        final int gen = aiCoachGen.incrementAndGet();
        aiCoachButton.setAlpha(0.45f);
        refreshAssistHint();
        refreshMoveGuide();

        final PreviewStats statsSnapshot = latestStats;
        final float tiltSnapshot = latestTiltDeg;
        final float elevSnapshot = latestCameraElevationDeg;
        final String presetId = presets[currentPreset].id;
        final String presetName = presets[currentPreset].name;
        final float currentEvStops = (camera != null && evMax > evMin)
                ? camera.getCameraInfo().getExposureState().getExposureCompensationIndex() * evStep
                : 0f;
        final String aspectLabel = aspectRatio.label;
        final boolean frontSnapshot = facingFront;
        final boolean hadFaceSnapshot = lastFaceBox != null
                && (SystemClock.uptimeMillis() - lastFaceActiveMs) <= FACE_STALE_MS;
        final boolean zh = isChineseLocale();
        final boolean wantVision = prefs.isAiVisionEnabled();

        new Thread(() -> {
            Bitmap snapshot = null;
            AiCoachMath.AiDirectorPlan plan = null;
            String err = null;
            try {
                if (wantVision) {
                    snapshot = previewView.capturePreviewSnapshot(640);
                }
                plan = AiCoachClient.directViewfinder(
                        this, prefs, snapshot, statsSnapshot, tiltSnapshot, elevSnapshot,
                        presetId, presetName, currentEvStops, aspectLabel,
                        frontSnapshot, hadFaceSnapshot, zh);
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            } finally {
                if (snapshot != null && !snapshot.isRecycled()) {
                    snapshot.recycle();
                }
            }
            final AiCoachMath.AiDirectorPlan finalPlan = plan;
            final String finalErr = err;
            runOnUiThread(() -> {
                aiCoachBusy = false;
                aiDirectorThinking = false;
                if (!isActivityResumed || isDestroyed() || isFinishing() || gen != aiCoachGen.get()) {
                    aiCoachButton.setAlpha(activeDirectorPlan != null ? 1f : 0.82f);
                    refreshAssistHint();
                    return;
                }
                if (finalPlan != null && !finalPlan.isEmpty()) {
                    activeDirectorPlan = finalPlan;
                    aiCoachButton.setAlpha(1f);
                    applyDirectorSettings(finalPlan);
                    moveGuideState.reset();
                    syncAssistSensorsAndTelemetry();
                    shutterButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                } else {
                    clearDirectorPlan();
                    Toast.makeText(this,
                            getString(R.string.ai_coach_failed, String.valueOf(finalErr)),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "otc-ai-director").start();
    }

    private void clearDirectorPlan() {
        aiCoachGen.incrementAndGet();
        aiCoachBusy = false;
        aiDirectorThinking = false;
        activeDirectorPlan = null;
        lastLockedState = false;
        moveGuideState.reset();
        if (shutterProgress != null) {
            shutterProgress.setReadyLocked(false);
        }
        if (aiCoachButton != null) {
            aiCoachButton.setAlpha(0.82f);
        }
        syncAssistSensorsAndTelemetry();
    }

    private void applyDirectorSettings(AiCoachMath.AiDirectorPlan plan) {
        if (plan == null || capturing) return;
        List<String> applied = new ArrayList<>(2);
        if (!plan.recommendedPresetId.isEmpty()
                && !plan.recommendedPresetId.equals(presets[currentPreset].id)) {
            int idx = FilmPresets.indexOfId(plan.recommendedPresetId);
            applyPresetSelection(idx, true);
            applied.add(presets[idx].name);
        }
        if (Math.abs(plan.evDelta) >= 0.25f && evMax > evMin && camera != null) {
            int curIdx = camera.getCameraInfo().getExposureState().getExposureCompensationIndex();
            int deltaSteps = Math.round(plan.evDelta / Math.max(0.1f, evStep));
            if (deltaSteps == 0) {
                deltaSteps = plan.evDelta > 0f ? 1 : -1;
            }
            int nextIdx = Math.max(evMin, Math.min(evMax, curIdx + deltaSteps));
            if (nextIdx != curIdx) {
                camera.getCameraControl().setExposureCompensationIndex(nextIdx);
                evSeekBar.setProgress(nextIdx - evMin);
                updateEvUi(nextIdx);
                exposureAdvisor.onHintApplied(SystemClock.uptimeMillis());
                lastShownExposureAdvice = ExposureAdvisor.Advice.NONE;
                exposureHintAutoFaded = false;
                mainHandler.removeCallbacks(autoFadeExposureTask);
                applied.add(String.format(Locale.US, "EV %+.1f", (nextIdx - curIdx) * evStep));
            }
        }
        if (!applied.isEmpty()) {
            showTransientHintText(getString(R.string.ai_director_applied, String.join(" · ", applied)));
        }
    }

    private void applyExposureHint() {
        if (!activeHintClickable || capturing || camera == null) return;
        ExposureAdvisor.Advice advice = exposureAdvisor.getCurrentAdvice();
        if (advice == ExposureAdvisor.Advice.NONE) return;

        long now = SystemClock.uptimeMillis();
        if (advice == ExposureAdvisor.Advice.BACKLIT
                && latestFrameRect.width() > 1f && latestFrameRect.height() > 1f
                && previewView.getWidth() > 0 && previewView.getHeight() > 0) {
            float ax = 0.5f;
            float ay = 0.5f;
            if ((now - lastFaceActiveMs) <= FACE_STALE_MS && !Float.isNaN(lastFaceActiveX)) {
                ax = lastFaceActiveX;
                ay = lastFaceActiveY;
            } else if ((now - lastStatsSubjectMs) <= 1500L && !Float.isNaN(lastStatsSubjectX)) {
                ax = lastStatsSubjectX;
                ay = lastStatsSubjectY;
            }
            float vx = latestFrameRect.left + ax * latestFrameRect.width();
            float vy = latestFrameRect.top + ay * latestFrameRect.height();
            triggerTapFocus(vx, vy, previewView.getWidth(), previewView.getHeight());
        }

        if (evMax > evMin) {
            int curIdx = camera.getCameraInfo().getExposureState().getExposureCompensationIndex();
            int nextIdx = Math.max(evMin, Math.min(evMax, curIdx + advice.suggestedEvDelta));
            if (nextIdx != curIdx) {
                camera.getCameraControl().setExposureCompensationIndex(nextIdx);
                evSeekBar.setProgress(nextIdx - evMin);
                updateEvUi(nextIdx);
            }
        }

        exposureAdvisor.onHintApplied(now);
        lastShownExposureAdvice = ExposureAdvisor.Advice.NONE;
        exposureHintAutoFaded = false;
        mainHandler.removeCallbacks(autoFadeExposureTask);
        assistHintPill.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        refreshAssistHint();
    }

    private void updateModeUi() {
        switch (shootMode) {
            case DOUBLE_EXP:
                modePill.setImageResource(R.drawable.ic_double_exposure);
                break;
            case HALF_FRAME:
                modePill.setImageResource(R.drawable.ic_mode_half);
                break;
            default:
                modePill.setImageResource(R.drawable.ic_mode_single);
                break;
        }
        boolean activeMode = shootMode != CameraPrefs.ShootMode.SINGLE;
        modePill.setColorFilter(getColor(activeMode ? R.color.amber_led : R.color.paper));
        modePill.setAlpha(activeMode ? 1f : 0.78f);
        updateRollCounter();
        refreshAssistHint();
    }

    private void cycleShootMode() {
        if (capturing) {
            toast(R.string.capture_busy);
            return;
        }
        if (doubleExposureFirstFrame != null || halfFirstFrame != null) {
            clearPendingMultiFrame();
            shootMode = CameraPrefs.ShootMode.SINGLE;
        } else {
            clearPendingMultiFrame();
            shootMode = shootMode.next();
        }
        prefs.setShootMode(shootMode);
        shutterButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        updateModeUi();
        switch (shootMode) {
            case DOUBLE_EXP: showTransientHint(R.string.hint_mode_double); break;
            case HALF_FRAME: showTransientHint(R.string.hint_mode_half); break;
            default: showTransientHint(R.string.hint_mode_single); break;
        }
    }

    private void clearPendingMultiFrame() {
        if (doubleExposureFirstFrame != null && !doubleExposureFirstFrame.isRecycled()) {
            doubleExposureFirstFrame.recycle();
        }
        doubleExposureFirstFrame = null;
        viewfinderOverlay.setDoubleExposureGhost(null);
        if (halfFirstFrame != null && !halfFirstFrame.isRecycled()) {
            halfFirstFrame.recycle();
        }
        halfFirstFrame = null;
        viewfinderOverlay.setHalfFrameThumb(null);
    }

    private void clearDoubleExposureFrame() {
        clearPendingMultiFrame();
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
        rollCounter.setTextColor(getColor(R.color.paper_dim));
        int totalFrames = CameraPrefs.framesForMode(shootMode);
        int frameInRoll = Math.min(exposures, totalFrames);
        String text = String.format(Locale.US, "R%02d · %02d/%02d", rollNumber, frameInRoll, totalFrames);
        if (shootMode == CameraPrefs.ShootMode.DOUBLE_EXP) {
            text = text + (doubleExposureFirstFrame != null ? " · 2/2" : " · 1/2");
        } else if (shootMode == CameraPrefs.ShootMode.HALF_FRAME) {
            text = text + (halfFirstFrame != null ? " · ½B" : " · ½A");
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
        cancelSteadyWait(false);
        clearDirectorPlan();
        lastFaceActiveMs = 0L;
        lastFaceActiveX = Float.NaN;
        lastFaceActiveY = Float.NaN;
        lastFaceBox = null;
        lastTapSubjectMs = 0L;
        moveGuideState.reset();
        snapState.reset();
        viewfinderOverlay.setPowerPointHighlight(-1);
        exposureAdvisor.reset();
        lastShownExposureAdvice = ExposureAdvisor.Advice.NONE;
        exposureHintAutoFaded = false;
        mainHandler.removeCallbacks(autoFadeExposureTask);
        refreshAssistHint();
        refreshMoveGuide();

        facingFront = !facingFront;
        SurfaceTexture st = previewView.getSurfaceTexture();
        if (st != null) bindCamera(st);
    }

    private void openDarkroomSheet() {
        if (capturing) {
            toast(R.string.capture_busy);
            return;
        }
        cancelSteadyWait(true);
        clearDirectorPlan();
        DarkroomSheet.show(this, prefs, new DarkroomSheet.Callbacks() {
            @Override
            public void onAspectChanged(CameraPrefs.AspectRatio ratio) {
                if (capturing) return;
                aspectRatio = ratio;
                clearPendingMultiFrame();
                updateModeUi();
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
            public void onBlackMistChanged(boolean enabled) {
                blackMistEnabled = enabled;
                previewView.setBlackMistEnabled(enabled);
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
            public void onAssistSettingsChanged() {
                assistMoveGuide = prefs.isAssistMoveGuide();
                assistExposure = prefs.isAssistExposure();
                assistSteadyShutter = prefs.isAssistSteadyShutter();
                syncAssistSensorsAndTelemetry();
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
        lastTapSubjectMs = 0L;
        lastFaceActiveMs = 0L;
        lastFaceBox = null;
        moveGuideState.reset();
        snapState.reset();
        viewfinderOverlay.setPowerPointHighlight(-1);
        pushPreviewTransform();
        refreshMoveGuide();
    }

    private void pushPreviewTransform() {
        int displayDeg = CameraTransformMath.surfaceRotationToDegrees(getDisplaySurfaceRotation());
        int manualOffset = manualRotation >= 0 ? manualRotation : 0;
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
        float[] norm = CompositionMath.mapViewPointToActiveFrame(
                viewX, viewY,
                latestFrameRect.left, latestFrameRect.top,
                latestFrameRect.width(), latestFrameRect.height());
        if (norm != null) {
            lastTapSubjectX = norm[0];
            lastTapSubjectY = norm[1];
            lastTapSubjectMs = SystemClock.uptimeMillis();
        }
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
            evMin = 0;
            evMax = 0;
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
            if (permissionAsked
                    && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                // Already denied for good: asking again is a no-op, send the user to Settings.
                showPermissionSettingsDialog();
                return;
            }
            permissionAsked = true;
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
            return;
        }
        permissionGate.setVisibility(View.VISIBLE);
        // "Don't ask again" leaves the gate permanently stuck: offer a route to App info.
        boolean permanent = permissionAsked
                && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);
        if (permanent) {
            showPermissionSettingsDialog();
        } else {
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show();
        }
    }

    private void showPermissionSettingsDialog() {
        if (isFinishing() || isDestroyed()) return;
        new android.app.AlertDialog.Builder(this)
                .setMessage(R.string.permission_settings_message)
                .setPositiveButton(R.string.open_settings, (d, w) -> openAppSettings())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ camera

    private void onSurfaceReady(SurfaceTexture st) {
        bindCamera(st);
    }

    private boolean isActivityResumed = false;
    private float savedWindowBrightness = -2f;

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
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

                Preview.Builder previewBuilder = new Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(displayRotation);

                boolean faceSupported = false;
                sensorActiveArray = null;
                try {
                    List<CameraInfo> candidates = selector.filter(cameraProvider.getAvailableCameraInfos());
                    if (!candidates.isEmpty()) {
                        Camera2CameraInfo c2Info = Camera2CameraInfo.from(candidates.get(0));
                        sensorActiveArray = c2Info.getCameraCharacteristic(
                                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                        int[] modes = c2Info.getCameraCharacteristic(
                                CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
                        if (modes != null) {
                            boolean hasSimple = false;
                            boolean hasFull = false;
                            for (int m : modes) {
                                if (m == CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE) hasSimple = true;
                                else if (m == CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL) hasFull = true;
                            }
                            if (hasSimple || hasFull) {
                                faceSupported = true;
                                Camera2Interop.Extender<Preview> extender =
                                        new Camera2Interop.Extender<>(previewBuilder);
                                extender.setCaptureRequestOption(
                                        CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                                        hasSimple ? CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
                                                  : CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL);
                                extender.setSessionCaptureCallback(new CameraCaptureSession.CaptureCallback() {
                                    @Override
                                    public void onCaptureCompleted(
                                            @NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull TotalCaptureResult result) {
                                        if (gen != bindGeneration.get()
                                                || (!assistExposure && !assistMoveGuide
                                                && gridMode == CompositionMath.GridMode.OFF)) {
                                            return;
                                        }
                                        handleCamera2Faces(result, wantFront);
                                    }
                                });
                            }
                        }
                    }
                } catch (Exception ignored) {
                    faceSupported = false;
                }
                if (!faceSupported) {
                    lastFaceActiveMs = 0L;
                }

                Preview preview = previewBuilder.build();
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(deviceSurfaceRotation)
                        .build();

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
                boundCameraInfo = camera.getCameraInfo();
                updatePreviewRelativeSensorRotation(wantFront, displayRotation);
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

    private void handleCamera2Faces(CaptureResult result, boolean lensFront) {
        Face[] faces = result.get(CaptureResult.STATISTICS_FACES);
        if (faces == null || faces.length == 0) {
            lastFaceActiveMs = 0L;
            lastFaceBox = null;
            return;
        }
        Rect crop = result.get(CaptureResult.SCALER_CROP_REGION);
        if (crop == null || crop.width() <= 0 || crop.height() <= 0) {
            crop = sensorActiveArray;
        }
        if (crop == null || crop.width() <= 0 || crop.height() <= 0) {
            return;
        }
        Face best = null;
        long bestArea = -1L;
        for (Face f : faces) {
            Rect b = f.getBounds();
            if (b == null) continue;
            float cx = (b.centerX() - crop.left) / (float) crop.width();
            float cy = (b.centerY() - crop.top) / (float) crop.height();
            if (cx < -0.15f || cx > 1.15f || cy < -0.15f || cy > 1.15f) {
                continue;
            }
            long area = (long) Math.max(0, b.width()) * Math.max(0, b.height());
            if (area > bestArea) {
                bestArea = area;
                best = f;
            }
        }
        if (best == null) {
            lastFaceActiveMs = 0L;
            lastFaceBox = null;
            return;
        }
        Rect b = best.getBounds();
        float sL = (b.left - crop.left) / (float) crop.width();
        float sT = (b.top - crop.top) / (float) crop.height();
        float sR = (b.right - crop.left) / (float) crop.width();
        float sB = (b.bottom - crop.top) / (float) crop.height();

        int manualOff = manualRotation >= 0 ? manualRotation : 0;
        boolean baseMirrored = latestInfoMirroring;
        boolean extraMirror = lensFront && !frontMirrorEnabled;
        CompositionMath.ActiveBox activeBox = CompositionMath.mapSensorBoxToActiveFrame(
                sL, sT, sR, sB,
                latestCropL, latestCropT, latestCropR, latestCropB,
                previewRelativeSensorRotation,
                baseMirrored,
                manualOff,
                extraMirror,
                latestFrameScaleX, latestFrameScaleY);
        if (activeBox == null) {
            lastFaceActiveMs = 0L;
            lastFaceBox = null;
            return;
        }
        lastFaceBox = activeBox;
        lastFaceActiveX = activeBox.centerX();
        lastFaceActiveY = activeBox.centerY();
        long now = SystemClock.uptimeMillis();
        lastFaceActiveMs = now;
        if (assistMoveGuide && now - lastFaceUiTickMs >= 95L) {
            lastFaceUiTickMs = now;
            mainHandler.removeCallbacks(faceMoveTickTask);
            mainHandler.post(faceMoveTickTask);
        }
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
            int manualOff = manualRotation >= 0 ? manualRotation : 0;
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
            int manualOff = manualRotation >= 0 ? manualRotation : 0;
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
        if (steadyWaiting) {
            cancelSteadyWait(false);
            captureNow();
            return;
        }
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
        requestCaptureWithSteadyCheck();
    }

    private void startCountdown(int seconds) {
        countdownText.setVisibility(View.VISIBLE);
        refreshAssistHint();
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
                requestCaptureWithSteadyCheck();
            }
        }.start();
        refreshMoveGuide();
    }

    private void cancelCountdown() {
        if (activeTimer != null) {
            activeTimer.cancel();
            activeTimer = null;
        }
        countdownText.setVisibility(View.GONE);
        refreshAssistHint();
        refreshMoveGuide();
    }

    private void requestCaptureWithSteadyCheck() {
        if (capturing || imageCapture == null) return;
        if (assistSteadyShutter && stabilitySensorRegistered
                && stabilityMeter.isReady() && !stabilityMeter.isStable()) {
            steadyWaiting = true;
            steadyWaitStartMs = SystemClock.uptimeMillis();
            shutterButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            shutterProgress.setWaitingProgress(true, 0.02f);
            refreshAssistHint();
            refreshMoveGuide();
            mainHandler.removeCallbacks(steadyTickTask);
            mainHandler.postDelayed(steadyTickTask, 16L);
            return;
        }
        captureNow();
    }

    private void cancelSteadyWait(boolean refreshUi) {
        if (!steadyWaiting) return;
        steadyWaiting = false;
        mainHandler.removeCallbacks(steadyTickTask);
        if (shutterProgress != null) {
            shutterProgress.setWaitingProgress(false, 0f);
        }
        if (refreshUi) {
            refreshAssistHint();
            refreshMoveGuide();
        }
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
        refreshAssistHint();
        refreshMoveGuide();

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
        final CameraPrefs.ShootMode shotMode = shootMode;
        final Bitmap firstDoubleSnapshot = doubleExposureFirstFrame;
        final Bitmap firstHalfSnapshot = halfFirstFrame;
        final boolean firstHalfWasLandscape = halfFirstLandscape;

        if (useScreenFlash) {
            beginScreenFlash();
            // Keep the pending shutter on mainHandler so onPause/onDestroy can cancel it;
            // otherwise a backgrounded app loses the Runnable while capturing stays true.
            pendingScreenFlashCapture = () -> {
                pendingScreenFlashCapture = null;
                if (isDestroyed() || isFinishing() || !isActivityResumed) {
                    endScreenFlash();
                    capturing = false;
                    refreshAssistHint();
                    return;
                }
                executeImageCapture(shotMode, firstDoubleSnapshot, firstHalfSnapshot,
                        firstHalfWasLandscape, true);
            };
            mainHandler.postDelayed(pendingScreenFlashCapture, 120L);
        } else {
            viewfinderOverlay.triggerShutterEffect();
            executeImageCapture(shotMode, firstDoubleSnapshot, firstHalfSnapshot,
                    firstHalfWasLandscape, false);
        }
    }

    private void executeImageCapture(
            final CameraPrefs.ShootMode shotMode,
            final Bitmap firstDoubleSnapshot,
            final Bitmap firstHalfSnapshot,
            final boolean firstHalfWasLandscape,
            final boolean usedScreenFlash) {
        if (isDestroyed() || isFinishing() || imageCapture == null) {
            if (usedScreenFlash) endScreenFlash();
            capturing = false;
            return;
        }

        final FilmPreset basePreset = presets[currentPreset];
        final FilmRecipe shotRecipe = filmRecipe;
        final FilmPreset effectivePreset = shotRecipe.apply(basePreset);
        final CameraPrefs.AspectRatio shotAspect = aspectRatio;
        final CameraPrefs.PaperStyle shotPaper = paperStyle;
        final CameraPrefs.DateMode shotDate = dateMode;
        final boolean boostGrain = grainBoost;
        final boolean useLeak = lightLeakEnabled;
        final boolean useMist = blackMistEnabled;
        final float shotLeakSeed = leakSeed;
        final boolean shotFacingFront = facingFront;
        final boolean mirrorSelfie = CameraTransformMath.shouldMirrorCapture(
                shotFacingFront, frontMirrorEnabled);
        final int shotTargetRotation = deviceSurfaceRotation;
        final boolean shotLandscape = (shotTargetRotation == Surface.ROTATION_90
                || shotTargetRotation == Surface.ROTATION_270);
        final int rollCap = CameraPrefs.framesForMode(shotMode);
        final int nextFrame = (exposures % rollCap) + 1;
        final int shotRoll = rollNumber;

        imageCapture.setTargetRotation(shotTargetRotation);
        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                boolean releaseImmediately = true;
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

                    // Branch 1: Double Exposure frame 1/2
                    if (shotMode == CameraPrefs.ShootMode.DOUBLE_EXP && firstDoubleSnapshot == null) {
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
                            updateModeUi();
                            capturing = false;
                        });
                        return;
                    }

                    // Branch 2: Half-Frame 72 exposure 1/2 (render look now, keep reference inset)
                    if (shotMode == CameraPrefs.ShootMode.HALF_FRAME && firstHalfSnapshot == null) {
                        Bitmap filteredFirst = previewView.renderPhoto(
                                cropped, effectivePreset, boostGrain, useLeak, useMist, shotLeakSeed);
                        if (filteredFirst != cropped) cropped.recycle();

                        int tw = Math.min(280, filteredFirst.getWidth());
                        int th = Math.max(1, Math.round(
                                (float) filteredFirst.getHeight() * tw / Math.max(1, filteredFirst.getWidth())));
                        Bitmap insetThumb = Bitmap.createScaledBitmap(filteredFirst, tw, th, true);
                        if (insetThumb == filteredFirst) {
                            insetThumb = filteredFirst.copy(Bitmap.Config.ARGB_8888, false);
                        }
                        final Bitmap finalInset = insetThumb;
                        runOnUiThread(() -> {
                            if (usedScreenFlash) endScreenFlash();
                            if (isDestroyed() || isFinishing()) {
                                filteredFirst.recycle();
                                finalInset.recycle();
                                capturing = false;
                                return;
                            }
                            halfFirstFrame = filteredFirst;
                            halfFirstLandscape = shotLandscape;
                            exposures++;
                            prefs.setExposures(exposures);
                            leakSeed = (leakSeed + 0.37f) % 1.0f;
                            previewView.setLeakSeed(leakSeed);
                            viewfinderOverlay.setHalfFrameThumb(finalInset);
                            updateModeUi();
                            capturing = false;
                        });
                        return;
                    }

                    // Branch 3: Completed exposure (SINGLE, DOUBLE_EXP 2/2, or HALF_FRAME 2/2)
                    Bitmap filtered;
                    if (shotMode == CameraPrefs.ShootMode.HALF_FRAME && firstHalfSnapshot != null) {
                        Bitmap filteredSecond = previewView.renderPhoto(
                                cropped, effectivePreset, boostGrain, useLeak, useMist, shotLeakSeed);
                        if (filteredSecond != cropped) cropped.recycle();
                        boolean sideBySide = PhotoSaver.shouldPairHalfFrameSideBySide(firstHalfWasLandscape);
                        filtered = PhotoSaver.composeHalfFrameDiptych(
                                MainActivity.this, firstHalfSnapshot, filteredSecond, sideBySide, nextFrame);
                        if (filteredSecond != filtered && !filteredSecond.isRecycled()) {
                            filteredSecond.recycle();
                        }
                    } else {
                        Bitmap developedInput = cropped;
                        if (shotMode == CameraPrefs.ShootMode.DOUBLE_EXP && firstDoubleSnapshot != null) {
                            Bitmap blended = PhotoSaver.blendDoubleExposure(firstDoubleSnapshot, cropped);
                            if (blended != cropped) cropped.recycle();
                            developedInput = blended;
                        }
                        filtered = previewView.renderPhoto(
                                developedInput, effectivePreset, boostGrain, useLeak, useMist, shotLeakSeed);
                        if (filtered != developedInput) developedInput.recycle();
                    }

                    String exifTelemetry = shotPaper == CameraPrefs.PaperStyle.SPROCKET_35MM
                            ? PhotoSaver.extractShortExifTelemetry(jpeg) : null;
                    Bitmap print = PhotoSaver.applyPaper(
                            MainActivity.this, filtered, effectivePreset, shotPaper, shotDate,
                            shotAspect, nextFrame, null, exifTelemetry);
                    if (print != filtered) filtered.recycle();

                    Uri savedUri = PhotoSaver.save(MainActivity.this, print, jpeg,
                            PhotoSaver.buildRecipeComment(basePreset, boostGrain, useLeak,
                                    shotRoll, nextFrame, rotation, shotRecipe, useMist, shotMode));
                    int thumbSize = FilmUiHelper.dp(MainActivity.this, 96);
                    Bitmap thumb = Bitmap.createScaledBitmap(print, thumbSize, thumbSize, true);
                    print.recycle();

                    final boolean clearDouble = (shotMode == CameraPrefs.ShootMode.DOUBLE_EXP && firstDoubleSnapshot != null);
                    final boolean clearHalf = (shotMode == CameraPrefs.ShootMode.HALF_FRAME && firstHalfSnapshot != null);
                    runOnUiThread(() -> {
                        if (usedScreenFlash) endScreenFlash();
                        if (clearDouble) {
                            clearPendingMultiFrame();
                            shootMode = CameraPrefs.ShootMode.SINGLE;
                            prefs.setShootMode(shootMode);
                            updateModeUi();
                        } else if (clearHalf) {
                            clearPendingMultiFrame();
                            updateModeUi();
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
                    if (releaseImmediately) {
                        runOnUiThread(() -> {
                            capturing = false;
                            refreshAssistHint();
                            refreshMoveGuide();
                        });
                    }
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> {
                    if (usedScreenFlash) endScreenFlash();
                    capturing = false;
                    refreshAssistHint();
                    refreshMoveGuide();
                    Toast.makeText(MainActivity.this,
                            getString(R.string.capture_failed, String.valueOf(exception.getMessage())),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void onPhotoSaved(Uri savedUri, Bitmap thumb) {
        advanceRollAndThumbnail(savedUri, thumb);
        if (activeDirectorPlan != null) {
            clearDirectorPlan();
        }
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
        int rollCap = CameraPrefs.framesForMode(shootMode);
        if (exposures >= rollCap) {
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
        syncAssistSensorsAndTelemetry();
        refreshThumbnailAsync();
    }

    @Override
    protected void onPause() {
        isActivityResumed = false;
        cancelCountdown();
        cancelSteadyWait(true);
        if (pendingScreenFlashCapture != null) {
            // Never leave the shutter locked because a delayed capture was dropped.
            mainHandler.removeCallbacks(pendingScreenFlashCapture);
            pendingScreenFlashCapture = null;
            capturing = false;
        }
        mainHandler.removeCallbacks(clearTransientHintTask);
        mainHandler.removeCallbacks(autoFadeExposureTask);
        mainHandler.removeCallbacks(faceMoveTickTask);
        lastFaceBox = null;
        lastFaceActiveMs = 0L;
        moveGuideState.reset();
        latestGuideCue = CompositionMath.Guide3DCue.EMPTY;
        lastLockedState = false;
        if (shutterProgress != null) {
            shutterProgress.setReadyLocked(false);
        }
        if (viewfinderOverlay != null) {
            viewfinderOverlay.setGuide3DCue(CompositionMath.Guide3DCue.EMPTY, "");
        }
        aiCoachGen.incrementAndGet();
        aiCoachBusy = false;
        aiDirectorThinking = false;
        activeDirectorPlan = null;
        if (aiCoachButton != null) aiCoachButton.setAlpha(0.82f);
        endScreenFlash();
        if (orientationListener != null) {
            orientationListener.disable();
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(levelSensorListener);
            sensorManager.unregisterListener(stabilitySensorListener);
        }
        stabilitySensorRegistered = false;
        stabilityMeter.reset();
        previewView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // Unbind before touching the executor: unbindAll() still delivers final
        // callbacks on cameraExecutor, which would throw RejectedExecutionException
        // if the pool had already been shut down.
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        cancelCountdown();
        cancelSteadyWait(false);
        mainHandler.removeCallbacksAndMessages(null);
        if (sensorManager != null) {
            sensorManager.unregisterListener(levelSensorListener);
            sensorManager.unregisterListener(stabilitySensorListener);
            sensorManager = null;
        }
        if (!capturing) {
            clearPendingMultiFrame();
        }
        // Drop the surface callback so a late GL surface cannot rebind a dead camera.
        previewView.setOnSurfaceReadyListener(null);
        MediaActionSound sound = shutterSound;
        shutterSound = null;
        if (sound != null) {
            try {
                cameraExecutor.execute(sound::release);
            } catch (RejectedExecutionException alreadyStopped) {
                sound.release();
            }
        }
        cameraExecutor.shutdown();
        try {
            if (!cameraExecutor.awaitTermination(600, TimeUnit.MILLISECONDS)) {
                cameraExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cameraExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // Last: the framework tears down the window and the GL surface after this.
        super.onDestroy();
    }
}
