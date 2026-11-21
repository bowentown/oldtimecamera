package com.bowentown.oldtimecamera;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full-screen darkroom editor supporting:
 * <ul>
 *     <li>Single gallery photo developing ({@link #showSingle}),</li>
 *     <li>Batch 2–20 gallery photo developing ({@link #showBatch}),</li>
 *     <li>Lossless master negative re-washing ({@link #showRedevelop}),</li>
 *     <li>Live Paper Style ({@code EXIF_FRAME} nameplate, sprocket, ivory, black), Date Mode,
 *         Kelvin White Balance ({@code 3200K–7500K}), and press-and-hold original comparison.</li>
 * </ul>
 *
 * <p>Uses {@link GLFilmRenderer.OffscreenSession} on a dedicated worker thread so it runs
 * identically from both {@link MainActivity} and {@link GalleryActivity}.</p>
 */
public final class DarkroomEditorDialog {

    public interface OnPrintSavedListener {
        void onSaved(Uri uri, Bitmap thumb);
    }

    public interface OnBatchSavedListener {
        void onBatchSaved(int savedCount, Uri lastUri, Bitmap lastThumb);
    }

    public static final int MAX_BATCH_PHOTOS = 20;
    private static final int PREVIEW_EDGE = 1080;
    private static final int EXPORT_EDGE = 2560;

    private DarkroomEditorDialog() {}

    public static void show(Activity activity,
                            FilmCameraView ignoredGlEngine,
                            CameraPrefs prefs,
                            Uri sourceUri,
                            int initialPresetIndex,
                            OnPrintSavedListener listener) {
        showSingle(activity, prefs, sourceUri, initialPresetIndex, listener);
    }

    public static void showSingle(Activity activity,
                                  CameraPrefs prefs,
                                  Uri sourceUri,
                                  int initialPresetIndex,
                                  OnPrintSavedListener listener) {
        if (sourceUri == null) return;
        showInternal(activity, prefs, Collections.singletonList(sourceUri), null,
                initialPresetIndex, listener, null);
    }

    public static void showBatch(Activity activity,
                                 CameraPrefs prefs,
                                 List<Uri> sourceUris,
                                 int initialPresetIndex,
                                 OnBatchSavedListener batchListener) {
        if (sourceUris == null || sourceUris.isEmpty()) return;
        List<Uri> capped = new ArrayList<>();
        for (Uri u : sourceUris) {
            if (u != null) capped.add(u);
            if (capped.size() >= MAX_BATCH_PHOTOS) break;
        }
        if (capped.isEmpty()) return;
        if (sourceUris.size() > MAX_BATCH_PHOTOS) {
            Toast.makeText(activity,
                    activity.getString(R.string.editor_batch_limit_toast, MAX_BATCH_PHOTOS),
                    Toast.LENGTH_SHORT).show();
        }
        showInternal(activity, prefs, capped, null, initialPresetIndex, null, batchListener);
    }

    public static void showRedevelop(Activity activity,
                                     CameraPrefs prefs,
                                     NegativeVault.Entry negativeEntry,
                                     int initialPresetIndex,
                                     OnPrintSavedListener listener) {
        if (negativeEntry == null || negativeEntry.negFile == null
                || !negativeEntry.negFile.isFile()) {
            return;
        }
        Uri negUri = Uri.fromFile(negativeEntry.negFile);
        showInternal(activity, prefs, Collections.singletonList(negUri), negativeEntry,
                initialPresetIndex, listener, null);
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void showInternal(Activity activity,
                                     CameraPrefs prefs,
                                     List<Uri> sourceUris,
                                     NegativeVault.Entry redevelopEntry,
                                     int initialPresetIndex,
                                     OnPrintSavedListener singleListener,
                                     OnBatchSavedListener batchListener) {
        final boolean isRedevelop = redevelopEntry != null;
        final boolean isBatch = !isRedevelop && sourceUris.size() > 1;
        final int totalCount = sourceUris.size();
        final Uri primaryUri = sourceUris.get(0);

        Dialog dialog = new Dialog(activity, R.style.AppTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_darkroom_editor);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        View root = dialog.findViewById(R.id.editorRoot);
        FilmUiHelper.applySystemBarPadding(root);

        TextView titleView = dialog.findViewById(R.id.editorTitle);
        ImageView previewImage = dialog.findViewById(R.id.editorPreview);
        TextView compareBadge = dialog.findViewById(R.id.editorCompareBadge);
        TextView busyLabel = dialog.findViewById(R.id.editorBusy);
        TextView paperPill = dialog.findViewById(R.id.editorPaperPill);
        TextView datePill = dialog.findViewById(R.id.editorDatePill);
        SeekBar wbSeek = dialog.findViewById(R.id.editorWbSeek);
        TextView wbValue = dialog.findViewById(R.id.editorWbValue);
        EditText noteInput = dialog.findViewById(R.id.editorNoteInput);
        HorizontalScrollView scroll = dialog.findViewById(R.id.editorFilmScroll);
        LinearLayout chipContainer = dialog.findViewById(R.id.editorChipContainer);
        TextView saveBtn = dialog.findViewById(R.id.editorSave);

        if (isRedevelop) {
            titleView.setText(R.string.editor_redevelop_title);
        } else if (isBatch) {
            titleView.setText(activity.getString(R.string.editor_batch_title, totalCount));
            saveBtn.setText(activity.getString(R.string.editor_batch_save, totalCount));
        } else {
            titleView.setText(R.string.editor_title);
        }

        FilmPreset[] presets = FilmPresets.all();
        List<LinearLayout> chips = new ArrayList<>();
        int[] selectedIndex = {Math.max(0, Math.min(initialPresetIndex, presets.length - 1))};
        CameraPrefs.PaperStyle[] activePaper = {prefs.getPaperStyle()};
        CameraPrefs.DateMode[] activeDate = {prefs.getDateMode()};
        float[] wbShift = {prefs.getSceneWbShift()};
        boolean[] holdingCompare = {false};

        ExecutorService worker = Executors.newSingleThreadExecutor();
        Handler main = new Handler(Looper.getMainLooper());
        AtomicInteger renderSeq = new AtomicInteger(0);
        AtomicBoolean dismissed = new AtomicBoolean(false);
        GLFilmRenderer.OffscreenSession[] glSession = {null};
        Bitmap[] previewSource = {null};
        Bitmap[] currentPreview = {null};
        PhotoSaver.ExifSpec[] primaryExifSpec = {null};
        String[] primaryShortExif = {""};
        boolean[] saving = {false};
        final float sessionSeed = (System.currentTimeMillis() % 997L) / 997f;

        Runnable syncControlLabels = () -> {
            paperPill.setText(paperLabel(activity, activePaper[0]));
            datePill.setText(dateLabel(activity, activeDate[0]));
            wbValue.setText(FilmLutMath.wbKelvinLabel(wbShift[0]));
            wbValue.setTextColor(activity.getColor(
                    Math.abs(wbShift[0]) > 0.04f ? R.color.amber_led : R.color.paper_soft));
        };
        wbSeek.setProgress(Math.max(0, Math.min(20, Math.round((wbShift[0] + 1f) * 10f))));
        syncControlLabels.run();

        Runnable renderPreview = () -> {
            Bitmap src = previewSource[0];
            if (src == null || src.isRecycled() || dismissed.get()) return;
            int seq = renderSeq.incrementAndGet();
            FilmPreset preset = presets[selectedIndex[0]];
            boolean boostGrain = prefs.isGrainBoost();
            boolean leakEnabled = prefs.isLightLeakEnabled();
            CameraPrefs.PaperStyle paper = activePaper[0];
            CameraPrefs.DateMode dateMode = activeDate[0];
            CameraPrefs.AspectRatio aspect = isRedevelop
                    ? redevelopEntry.record.aspect
                    : prefs.getAspectRatio();
            float wb = wbShift[0];
            String note = noteInput.getText().toString();
            PhotoSaver.ExifSpec spec = primaryExifSpec[0];
            String shortExif = primaryShortExif[0];

            try {
                worker.execute(() -> {
                    if (dismissed.get() || seq != renderSeq.get()) return;
                    try {
                        if (glSession[0] == null) {
                            glSession[0] = new GLFilmRenderer.OffscreenSession();
                        }
                        FilmPreset effective = prefs.getFilmRecipe().apply(preset);
                        boolean blackMist = prefs.isBlackMistEnabled();
                        Bitmap filtered = glSession[0].render(src, effective, boostGrain,
                                leakEnabled, blackMist, wb, false, sessionSeed);
                        Bitmap print = PhotoSaver.applyPaper(activity, filtered, effective, paper,
                                dateMode, aspect, prefs.getExposures() + 1, note, shortExif, spec);
                        if (print != filtered) filtered.recycle();
                        main.post(() -> {
                            if (seq != renderSeq.get() || !dialog.isShowing() || dismissed.get()) {
                                print.recycle();
                                return;
                            }
                            Bitmap prev = currentPreview[0];
                            currentPreview[0] = print;
                            if (!holdingCompare[0]) {
                                previewImage.setImageBitmap(print);
                            }
                            if (prev != null && !prev.isRecycled()) prev.recycle();
                            busyLabel.setVisibility(View.GONE);
                        });
                    } catch (Exception e) {
                        main.post(() -> busyLabel.setVisibility(View.GONE));
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        };

        // Press-and-hold on the preview image shows the unfiltered raw source bitmap.
        previewImage.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (previewSource[0] != null && !previewSource[0].isRecycled() && !saving[0]) {
                    holdingCompare[0] = true;
                    previewImage.setImageBitmap(previewSource[0]);
                    compareBadge.setVisibility(View.VISIBLE);
                    return true;
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (holdingCompare[0]) {
                    holdingCompare[0] = false;
                    compareBadge.setVisibility(View.GONE);
                    if (currentPreview[0] != null && !currentPreview[0].isRecycled()) {
                        previewImage.setImageBitmap(currentPreview[0]);
                    }
                    return true;
                }
            }
            return false;
        });

        paperPill.setOnClickListener(v -> {
            activePaper[0] = activePaper[0].next();
            prefs.setPaperStyle(activePaper[0]);
            syncControlLabels.run();
            renderPreview.run();
        });

        datePill.setOnClickListener(v -> {
            activeDate[0] = activeDate[0].next();
            prefs.setDateMode(activeDate[0]);
            syncControlLabels.run();
            renderPreview.run();
        });

        wbSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                wbShift[0] = (progress - 10) / 10f;
                syncControlLabels.run();
                renderPreview.run();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        wbValue.setOnClickListener(v -> {
            wbShift[0] = 0f;
            wbSeek.setProgress(10);
            syncControlLabels.run();
            renderPreview.run();
        });

        FilmUiHelper.buildFilmStrip(activity, scroll, chipContainer, presets, chips, idx -> {
            selectedIndex[0] = idx;
            FilmUiHelper.updateChipSelection(activity, chips, idx);
            busyLabel.setText(R.string.editor_developing);
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
                main.postDelayed(debouncedNoteRefresh, 320);
            }
        });

        dialog.findViewById(R.id.editorClose).setOnClickListener(v -> dialog.dismiss());

        saveBtn.setOnClickListener(v -> {
            if (saving[0]) return;
            saving[0] = true;
            busyLabel.setText(isBatch
                    ? activity.getString(R.string.editor_batch_progress, 1, totalCount)
                    : activity.getString(R.string.editor_developing));
            busyLabel.setVisibility(View.VISIBLE);
            saveBtn.setEnabled(false);

            final FilmPreset preset = presets[selectedIndex[0]];
            final boolean boostGrain = prefs.isGrainBoost();
            final boolean leakEnabled = prefs.isLightLeakEnabled();
            final CameraPrefs.PaperStyle paper = activePaper[0];
            final CameraPrefs.DateMode dateMode = activeDate[0];
            final CameraPrefs.AspectRatio aspect = isRedevelop
                    ? redevelopEntry.record.aspect
                    : prefs.getAspectRatio();
            final float wb = wbShift[0];
            final String note = noteInput.getText().toString();
            final int baseFrame = prefs.getExposures();

            worker.execute(() -> {
                try {
                    if (glSession[0] == null) {
                        glSession[0] = new GLFilmRenderer.OffscreenSession();
                    }
                    int exportEdge = Math.min(EXPORT_EDGE, glSession[0].getMaxRenderEdge());
                    FilmRecipe recipe = prefs.getFilmRecipe();
                    FilmPreset effective = recipe.apply(preset);
                    boolean blackMist = prefs.isBlackMistEnabled();

                    byte[] redevelopExifBytes = isRedevelop ? redevelopEntry.readJpegBytes() : null;
                    int savedCount = 0;
                    Uri lastSavedUri = null;
                    Bitmap lastThumb = null;
                    int thumbSize = FilmUiHelper.dp(activity, 96);

                    for (int i = 0; i < totalCount; i++) {
                        if (dismissed.get()) break;
                        final int stepIdx = i + 1;
                        if (isBatch) {
                            main.post(() -> {
                                if (dialog.isShowing()) {
                                    busyLabel.setText(activity.getString(
                                            R.string.editor_batch_progress, stepIdx, totalCount));
                                }
                            });
                        }

                        Uri itemUri = sourceUris.get(i);
                        Bitmap fullSrc = PhotoSaver.decodeScaledUri(activity, itemUri, exportEdge);
                        if (fullSrc == null) {
                            if (!isBatch) {
                                throw new IllegalStateException(
                                        activity.getString(R.string.editor_load_failed));
                            }
                            continue;
                        }
                        Bitmap cropped = isRedevelop
                                ? fullSrc
                                : PhotoSaver.cropToAspect(fullSrc, aspect);

                        PhotoSaver.ExifSpec itemSpec = redevelopExifBytes != null
                                ? PhotoSaver.extractExifSpec(redevelopExifBytes)
                                : PhotoSaver.extractExifSpec(activity, itemUri);
                        String itemShortExif = redevelopExifBytes != null
                                ? PhotoSaver.extractShortExifTelemetry(redevelopExifBytes)
                                : PhotoSaver.readDisplayExifSummary(activity, itemUri);

                        float itemSeed = (sessionSeed + i * 0.29f) % 1.0f;
                        int frameNum = ((baseFrame + i) % CameraPrefs.FRAMES_PER_ROLL) + 1;

                        Bitmap filtered = glSession[0].render(cropped, effective, boostGrain,
                                leakEnabled, blackMist, wb, false, itemSeed);
                        if (filtered != cropped) cropped.recycle();

                        Bitmap print = PhotoSaver.applyPaper(activity, filtered, effective, paper,
                                dateMode, aspect, frameNum, note, itemShortExif, itemSpec);
                        if (print != filtered) filtered.recycle();

                        String comment = PhotoSaver.buildRecipeComment(preset, boostGrain,
                                leakEnabled, prefs.getRollNumber(), frameNum, 0, recipe,
                                blackMist, CameraPrefs.ShootMode.SINGLE);
                        Uri savedUri = PhotoSaver.save(activity, print, redevelopExifBytes,
                                isRedevelop ? null : itemUri, comment);

                        if (isRedevelop) {
                            String disp = PhotoSaver.resolveDisplayName(activity, savedUri);
                            NegativeVault.linkDerivedPrint(activity, redevelopEntry, savedUri, disp);
                        }

                        if (lastThumb != null && !lastThumb.isRecycled()) {
                            lastThumb.recycle();
                        }
                        lastThumb = Bitmap.createScaledBitmap(print, thumbSize, thumbSize, true);
                        print.recycle();
                        lastSavedUri = savedUri;
                        savedCount++;
                    }

                    if (savedCount == 0 || lastSavedUri == null) {
                        throw new IllegalStateException(activity.getString(R.string.save_failed));
                    }

                    final int finalCount = savedCount;
                    final Uri finalUri = lastSavedUri;
                    final Bitmap finalThumb = lastThumb;
                    main.post(() -> {
                        if (isBatch) {
                            if (batchListener != null) {
                                batchListener.onBatchSaved(finalCount, finalUri, finalThumb);
                            }
                            Toast.makeText(activity,
                                    activity.getString(R.string.editor_batch_done, finalCount),
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            if (singleListener != null) {
                                singleListener.onSaved(finalUri, finalThumb);
                            }
                            Toast.makeText(activity, R.string.saved_to_gallery,
                                    Toast.LENGTH_SHORT).show();
                        }
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
            dismissed.set(true);
            main.removeCallbacks(debouncedNoteRefresh);
            main.removeCallbacksAndMessages(null);
            renderSeq.incrementAndGet();
            try {
                worker.execute(() -> {
                    if (glSession[0] != null) {
                        glSession[0].close();
                        glSession[0] = null;
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
            worker.shutdown();
            if (currentPreview[0] != null && !currentPreview[0].isRecycled()) {
                previewImage.setImageDrawable(null);
                currentPreview[0].recycle();
                currentPreview[0] = null;
            }
            previewSource[0] = null;
        });

        dialog.show();
        busyLabel.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            Bitmap raw = PhotoSaver.decodeScaledUri(activity, primaryUri, PREVIEW_EDGE);
            if (raw == null) {
                main.post(() -> {
                    Toast.makeText(activity, R.string.editor_load_failed, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
                return;
            }
            Bitmap cropped = isRedevelop
                    ? raw
                    : PhotoSaver.cropToAspect(raw, prefs.getAspectRatio());
            byte[] negBytes = isRedevelop ? redevelopEntry.readJpegBytes() : null;
            PhotoSaver.ExifSpec spec = negBytes != null
                    ? PhotoSaver.extractExifSpec(negBytes)
                    : PhotoSaver.extractExifSpec(activity, primaryUri);
            String shortExif = negBytes != null
                    ? PhotoSaver.extractShortExifTelemetry(negBytes)
                    : PhotoSaver.readDisplayExifSummary(activity, primaryUri);

            main.post(() -> {
                if (!dialog.isShowing() || dismissed.get()) {
                    cropped.recycle();
                    return;
                }
                primaryExifSpec[0] = spec;
                primaryShortExif[0] = shortExif != null ? shortExif : "";
                previewSource[0] = cropped;
                renderPreview.run();
            });
        });
    }

    private static String paperLabel(Activity a, CameraPrefs.PaperStyle style) {
        int res;
        switch (style) {
            case BLACK:
                res = R.string.paper_black;
                break;
            case EXIF_FRAME:
                res = R.string.paper_exif;
                break;
            case SPROCKET_35MM:
                res = R.string.paper_sprocket;
                break;
            case NONE:
                res = R.string.paper_none;
                break;
            case IVORY:
            default:
                res = R.string.paper_ivory;
                break;
        }
        return a.getString(R.string.sheet_paper) + "·" + a.getString(res);
    }

    private static String dateLabel(Activity a, CameraPrefs.DateMode mode) {
        int res;
        switch (mode) {
            case RETRO_98:
                res = R.string.date_retro;
                break;
            case OFF:
                res = R.string.date_off;
                break;
            case TODAY:
            default:
                res = R.string.date_today;
                break;
        }
        return a.getString(R.string.sheet_date) + "·" + a.getString(res);
    }
}
