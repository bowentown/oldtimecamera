package com.bowentown.oldtimecamera;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full-screen darkroom editor for developing existing gallery photos with any
 * built-in film stock, paper print border, date stamp, and custom print note.
 */
public final class DarkroomEditorDialog {

    public interface OnPrintSavedListener {
        void onSaved(Uri uri, Bitmap thumb);
    }

    private static final int PREVIEW_EDGE = 1080;
    private static final int EXPORT_EDGE = 2560;

    private DarkroomEditorDialog() {}

    public static void show(Activity activity,
                            FilmCameraView glEngine,
                            CameraPrefs prefs,
                            Uri sourceUri,
                            int initialPresetIndex,
                            OnPrintSavedListener listener) {
        Dialog dialog = new Dialog(activity, R.style.AppTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_darkroom_editor);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        View root = dialog.findViewById(R.id.editorRoot);
        FilmUiHelper.applySystemBarPadding(root);

        ImageView previewImage = dialog.findViewById(R.id.editorPreview);
        TextView busyLabel = dialog.findViewById(R.id.editorBusy);
        EditText noteInput = dialog.findViewById(R.id.editorNoteInput);
        HorizontalScrollView scroll = dialog.findViewById(R.id.editorFilmScroll);
        LinearLayout chipContainer = dialog.findViewById(R.id.editorChipContainer);
        TextView saveBtn = dialog.findViewById(R.id.editorSave);

        FilmPreset[] presets = FilmPresets.all();
        List<LinearLayout> chips = new ArrayList<>();
        int[] selectedIndex = {Math.max(0, Math.min(initialPresetIndex, presets.length - 1))};

        ExecutorService worker = Executors.newSingleThreadExecutor();
        Handler main = new Handler(Looper.getMainLooper());
        AtomicInteger renderSeq = new AtomicInteger(0);
        Bitmap[] previewSource = {null};
        Bitmap[] currentPreview = {null};
        boolean[] saving = {false};
        // Each editing session gets its own organic leak seed; a hard-coded 0.28f put the
        // leak in exactly the same corner on every imported photo.
        final float sessionSeed = (System.currentTimeMillis() % 997L) / 997f;

        Runnable renderPreview = () -> {
            Bitmap src = previewSource[0];
            if (src == null || src.isRecycled()) return;
            int seq = renderSeq.incrementAndGet();
            FilmPreset preset = presets[selectedIndex[0]];
            boolean boostGrain = prefs.isGrainBoost();
            boolean leakEnabled = prefs.isLightLeakEnabled();
            CameraPrefs.PaperStyle paper = prefs.getPaperStyle();
            CameraPrefs.DateMode dateMode = prefs.getDateMode();
            CameraPrefs.AspectRatio aspect = prefs.getAspectRatio();
            String note = noteInput.getText().toString();

            try {
                worker.execute(() -> {
                    try {
                        FilmPreset effective = prefs.getFilmRecipe().apply(preset);
                        boolean blackMist = prefs.isBlackMistEnabled();
                        Bitmap filtered = glEngine.renderPhoto(src, effective, boostGrain,
                                leakEnabled, blackMist, sessionSeed);
                        Bitmap print = PhotoSaver.applyPaper(activity, filtered, effective, paper,
                                dateMode, aspect, prefs.getExposures() + 1, note);
                        if (print != filtered) filtered.recycle();
                        main.post(() -> {
                            if (seq != renderSeq.get() || !dialog.isShowing()) {
                                print.recycle();
                                return;
                            }
                            Bitmap prev = currentPreview[0];
                            currentPreview[0] = print;
                            previewImage.setImageBitmap(print);
                            if (prev != null && !prev.isRecycled()) prev.recycle();
                            busyLabel.setVisibility(View.GONE);
                        });
                    } catch (Exception e) {
                        main.post(() -> busyLabel.setVisibility(View.GONE));
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Dialog already dismissed while a debounced refresh was in flight.
            }
        };

        FilmUiHelper.buildFilmStrip(activity, scroll, chipContainer, presets, chips, idx -> {
            selectedIndex[0] = idx;
            FilmUiHelper.updateChipSelection(activity, chips, idx);
            busyLabel.setVisibility(View.VISIBLE);
            renderPreview.run();
        });
        FilmUiHelper.updateChipSelection(activity, chips, selectedIndex[0]);
        FilmUiHelper.centerChip(scroll, chips.get(selectedIndex[0]));

        noteInput.setOnEditorActionListener((v, actionId, event) -> {
            renderPreview.run();
            return false;
        });
        Runnable debouncedNoteRefresh = renderPreview::run;
        noteInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                main.removeCallbacks(debouncedNoteRefresh);
                main.postDelayed(debouncedNoteRefresh, 350);
            }
        });

        dialog.findViewById(R.id.editorClose).setOnClickListener(v -> dialog.dismiss());

        saveBtn.setOnClickListener(v -> {
            if (saving[0]) return;
            saving[0] = true;
            busyLabel.setVisibility(View.VISIBLE);
            saveBtn.setEnabled(false);

            FilmPreset preset = presets[selectedIndex[0]];
            boolean boostGrain = prefs.isGrainBoost();
            boolean leakEnabled = prefs.isLightLeakEnabled();
            CameraPrefs.PaperStyle paper = prefs.getPaperStyle();
            CameraPrefs.DateMode dateMode = prefs.getDateMode();
            CameraPrefs.AspectRatio aspect = prefs.getAspectRatio();
            String note = noteInput.getText().toString();
            int nextFrame = (prefs.getExposures() % CameraPrefs.FRAMES_PER_ROLL) + 1;

            worker.execute(() -> {
                try {
                    int exportEdge = Math.min(EXPORT_EDGE, glEngine.getMaxRenderEdge());
                    Bitmap fullSrc = PhotoSaver.decodeScaledUri(activity, sourceUri, exportEdge);
                    if (fullSrc == null) {
                        throw new IllegalStateException(activity.getString(R.string.editor_load_failed));
                    }
                    Bitmap cropped = PhotoSaver.cropToAspect(fullSrc, aspect);
                    FilmRecipe recipe = prefs.getFilmRecipe();
                    FilmPreset effective = recipe.apply(preset);
                    boolean blackMist = prefs.isBlackMistEnabled();
                    Bitmap filtered = glEngine.renderPhoto(cropped, effective, boostGrain,
                            leakEnabled, blackMist, sessionSeed);
                    if (filtered != cropped) cropped.recycle();
                    Bitmap print = PhotoSaver.applyPaper(activity, filtered, effective, paper,
                            dateMode, aspect, nextFrame, note);
                    if (print != filtered) filtered.recycle();

                    Uri savedUri = PhotoSaver.save(activity, print, null,
                            PhotoSaver.buildRecipeComment(preset, boostGrain, leakEnabled,
                                    prefs.getRollNumber(), nextFrame, 0, recipe, blackMist,
                                    CameraPrefs.ShootMode.SINGLE));
                    int thumbSize = FilmUiHelper.dp(activity, 96);
                    Bitmap thumb = Bitmap.createScaledBitmap(print, thumbSize, thumbSize, true);
                    print.recycle();

                    main.post(() -> {
                        if (listener != null) listener.onSaved(savedUri, thumb);
                        Toast.makeText(activity, R.string.saved_to_gallery, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    });
                } catch (Exception e) {
                    main.post(() -> {
                        saving[0] = false;
                        saveBtn.setEnabled(true);
                        busyLabel.setVisibility(View.GONE);
                        Toast.makeText(activity, R.string.save_failed, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });

        dialog.setOnDismissListener(d -> {
            // Drop the 350ms debounced note refresh before the executor dies, otherwise a
            // still-queued runnable throws RejectedExecutionException on the main thread.
            main.removeCallbacks(debouncedNoteRefresh);
            main.removeCallbacksAndMessages(null);
            renderSeq.incrementAndGet();
            worker.shutdownNow();
            if (currentPreview[0] != null && !currentPreview[0].isRecycled()) {
                previewImage.setImageDrawable(null);
                currentPreview[0].recycle();
                currentPreview[0] = null;
            }
            if (previewSource[0] != null) {
                // Do not recycle: a worker may still be uploading this bitmap to a GL
                // texture, and recycling it mid-upload can crash the driver. Dropping the
                // reference is enough; the GC frees the native pixel buffer.
                previewSource[0] = null;
            }
        });

        dialog.show();
        busyLabel.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            Bitmap raw = PhotoSaver.decodeScaledUri(activity, sourceUri, PREVIEW_EDGE);
            if (raw == null) {
                main.post(() -> {
                    Toast.makeText(activity, R.string.editor_load_failed, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
                return;
            }
            Bitmap cropped = PhotoSaver.cropToAspect(raw, prefs.getAspectRatio());
            main.post(() -> {
                if (!dialog.isShowing()) {
                    cropped.recycle();
                    return;
                }
                previewSource[0] = cropped;
                renderPreview.run();
            });
        });
    }
}
