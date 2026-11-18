package com.bowentown.oldtimecamera;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Custom matte-black darkroom bottom sheet with streamlined optics toggles,
 * minimalist exposure/steady assist switches, and BYOK AI advisor configuration.
 */
public final class DarkroomSheet {

    public interface Callbacks {
        void onAspectChanged(CameraPrefs.AspectRatio ratio);
        void onPaperChanged(CameraPrefs.PaperStyle style);
        void onDateModeChanged(CameraPrefs.DateMode mode);
        void onGrainBoostChanged(boolean enabled);
        void onLightLeakChanged(boolean enabled);
        void onBlackMistChanged(boolean enabled);
        void onLevelChanged(boolean enabled);
        void onShutterSoundChanged(boolean enabled);
        void onFrontMirrorChanged(boolean enabled);
        void onAssistSettingsChanged();
        int onCycleRotation();
        String getRotationLabel();
        void onLoadNewRoll();
    }

    private DarkroomSheet() {}

    public static void show(Context context, CameraPrefs prefs, Callbacks callbacks) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_darkroom_sheet);
        FilmUiHelper.configureBottomSheet(dialog, false);

        Typeface mono = FilmUiHelper.monoFont(context);
        dialog.findViewById(R.id.sheetDoneButton).setOnClickListener(v -> dialog.dismiss());

        LinearLayout segAspect = dialog.findViewById(R.id.segAspect);
        CameraPrefs.AspectRatio[] ratios = CameraPrefs.AspectRatio.values();
        String[] ratioLabels = new String[ratios.length];
        for (int i = 0; i < ratios.length; i++) ratioLabels[i] = ratios[i].label;
        FilmUiHelper.buildSegmented(context, mono, segAspect, ratioLabels, prefs.getAspectRatio().ordinal(), idx -> {
            CameraPrefs.AspectRatio r = ratios[idx];
            prefs.setAspectRatio(r);
            callbacks.onAspectChanged(r);
        });

        LinearLayout segPaper = dialog.findViewById(R.id.segPaper);
        String[] paperLabels = {
                context.getString(R.string.paper_ivory),
                context.getString(R.string.paper_black),
                context.getString(R.string.paper_sprocket),
                context.getString(R.string.paper_none)
        };
        CameraPrefs.PaperStyle[] papers = CameraPrefs.PaperStyle.values();
        FilmUiHelper.buildSegmented(context, mono, segPaper, paperLabels, prefs.getPaperStyle().ordinal(), idx -> {
            CameraPrefs.PaperStyle p = papers[idx];
            prefs.setPaperStyle(p);
            callbacks.onPaperChanged(p);
        });

        LinearLayout segDate = dialog.findViewById(R.id.segDate);
        String[] dateLabels = {
                context.getString(R.string.date_today),
                context.getString(R.string.date_retro),
                context.getString(R.string.date_off)
        };
        CameraPrefs.DateMode[] dates = CameraPrefs.DateMode.values();
        FilmUiHelper.buildSegmented(context, mono, segDate, dateLabels, prefs.getDateMode().ordinal(), idx -> {
            CameraPrefs.DateMode d = dates[idx];
            prefs.setDateMode(d);
            callbacks.onDateModeChanged(d);
        });

        LinearLayout toggleContainer = dialog.findViewById(R.id.toggleContainer);
        addToggle(context, toggleContainer, R.string.grain_boost, prefs.isGrainBoost(), enabled -> {
            prefs.setGrainBoost(enabled);
            callbacks.onGrainBoostChanged(enabled);
        }, true);
        addToggle(context, toggleContainer, R.string.light_leak, prefs.isLightLeakEnabled(), enabled -> {
            prefs.setLightLeakEnabled(enabled);
            callbacks.onLightLeakChanged(enabled);
        }, true);
        addToggle(context, toggleContainer, R.string.black_mist, prefs.isBlackMistEnabled(), enabled -> {
            prefs.setBlackMistEnabled(enabled);
            callbacks.onBlackMistChanged(enabled);
        }, true);
        addToggle(context, toggleContainer, R.string.level_overlay, prefs.isLevelEnabled(), enabled -> {
            prefs.setLevelEnabled(enabled);
            callbacks.onLevelChanged(enabled);
        }, true);
        addToggle(context, toggleContainer, R.string.shutter_sound, prefs.isShutterSoundEnabled(), enabled -> {
            prefs.setShutterSoundEnabled(enabled);
            callbacks.onShutterSoundChanged(enabled);
        }, true);
        addToggle(context, toggleContainer, R.string.front_mirror, prefs.isFrontMirrorEnabled(), enabled -> {
            prefs.setFrontMirrorEnabled(enabled);
            callbacks.onFrontMirrorChanged(enabled);
        }, false);

        LinearLayout assistContainer = dialog.findViewById(R.id.assistToggleContainer);
        addToggle(context, assistContainer, R.string.assist_exposure, prefs.isAssistExposure(), enabled -> {
            prefs.setAssistExposure(enabled);
            callbacks.onAssistSettingsChanged();
        }, true);
        addToggle(context, assistContainer, R.string.assist_steady_shutter, prefs.isAssistSteadyShutter(), enabled -> {
            prefs.setAssistSteadyShutter(enabled);
            callbacks.onAssistSettingsChanged();
        }, false);

        TextView aiStatus = dialog.findViewById(R.id.sheetAiStatus);
        if (aiStatus != null) {
            aiStatus.setText(AiSettingsDialog.formatSummary(context, prefs));
        }
        View aiBtn = dialog.findViewById(R.id.sheetAiButton);
        if (aiBtn != null) {
            aiBtn.setOnClickListener(v -> AiSettingsDialog.show(context, prefs, () -> {
                if (aiStatus != null) {
                    aiStatus.setText(AiSettingsDialog.formatSummary(context, prefs));
                }
            }));
        }

        TextView rotateBtn = dialog.findViewById(R.id.sheetRotateButton);
        rotateBtn.setText(callbacks.getRotationLabel());
        rotateBtn.setOnClickListener(v -> {
            callbacks.onCycleRotation();
            rotateBtn.setText(callbacks.getRotationLabel());
        });

        dialog.findViewById(R.id.sheetNewRollButton).setOnClickListener(v -> {
            callbacks.onLoadNewRoll();
            dialog.dismiss();
        });

        dialog.findViewById(R.id.sheetLicensesButton).setOnClickListener(v -> LicenseDialog.show(context));
        dialog.findViewById(R.id.sheetUpdateButton).setOnClickListener(v -> UpdateChecker.show(context));

        dialog.show();
    }

    private interface OnToggleListener {
        void onChanged(boolean checked);
    }

    private static void addToggle(Context context, LinearLayout parent, int labelRes,
                                  boolean initial, OnToggleListener listener, boolean dividerAfter) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_row_ripple);
        int padV = FilmUiHelper.dp(context, 10);
        row.setPadding(0, padV, 0, padV);

        TextView label = new TextView(context);
        label.setTextAppearance(R.style.TextLabel);
        label.setText(labelRes);
        label.setTypeface(Typeface.SANS_SERIF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, lp);

        Switch sw = new Switch(context);
        sw.setChecked(initial);
        sw.setThumbResource(R.drawable.bg_switch_thumb);
        sw.setTrackResource(R.drawable.bg_switch_track);
        sw.setShowText(false);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> listener.onChanged(isChecked));
        row.addView(sw);

        row.setOnClickListener(v -> {
            if (sw.isEnabled()) sw.toggle();
        });
        parent.addView(row);

        if (dividerAfter) {
            View div = new View(context);
            div.setBackgroundColor(context.getResources().getColor(R.color.hairline_soft, context.getTheme()));
            parent.addView(div, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, FilmUiHelper.dp(context, 1))));
        }
    }
}
