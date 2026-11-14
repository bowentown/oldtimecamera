package com.bowentown.oldtimecamera;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Custom matte-black darkroom bottom sheet with cohesive type scale,
 * tactile segmented controls, custom warm-film switches, and Beginner Assist toggles.
 */
public final class DarkroomSheet {

    public interface Callbacks {
        void onAspectChanged(CameraPrefs.AspectRatio ratio);
        void onPaperChanged(CameraPrefs.PaperStyle style);
        void onDateModeChanged(CameraPrefs.DateMode mode);
        void onGrainBoostChanged(boolean enabled);
        void onLightLeakChanged(boolean enabled);
        void onLevelChanged(boolean enabled);
        void onShutterSoundChanged(boolean enabled);
        void onFrontMirrorChanged(boolean enabled);
        void onAssistSettingsChanged();
        int onCycleRotation();
        String getRotationLabel();
        void onLoadNewRoll();
    }

    private static final class ToggleRow {
        final LinearLayout row;
        final TextView label;
        final Switch sw;

        ToggleRow(LinearLayout row, TextView label, Switch sw) {
            this.row = row;
            this.label = label;
            this.sw = sw;
        }
    }

    private DarkroomSheet() {}

    public static void show(Context context, CameraPrefs prefs, Callbacks callbacks) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_darkroom_sheet);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            View decor = window.getDecorView();
            decor.setPadding(0, 0, 0, 0);
            applyBottomInset(decor);
        }

        Typeface mono = FilmUiHelper.monoFont(context);
        dialog.findViewById(R.id.sheetDoneButton).setOnClickListener(v -> dialog.dismiss());

        // Aspect Ratio segment
        LinearLayout segAspect = dialog.findViewById(R.id.segAspect);
        CameraPrefs.AspectRatio[] ratios = CameraPrefs.AspectRatio.values();
        String[] ratioLabels = new String[ratios.length];
        for (int i = 0; i < ratios.length; i++) ratioLabels[i] = ratios[i].label;
        buildSegmented(context, mono, segAspect, ratioLabels, prefs.getAspectRatio().ordinal(), idx -> {
            CameraPrefs.AspectRatio r = ratios[idx];
            prefs.setAspectRatio(r);
            callbacks.onAspectChanged(r);
        });

        // Paper Border segment
        LinearLayout segPaper = dialog.findViewById(R.id.segPaper);
        String[] paperLabels = {
                context.getString(R.string.paper_ivory),
                context.getString(R.string.paper_black),
                context.getString(R.string.paper_none)
        };
        CameraPrefs.PaperStyle[] papers = CameraPrefs.PaperStyle.values();
        buildSegmented(context, mono, segPaper, paperLabels, prefs.getPaperStyle().ordinal(), idx -> {
            CameraPrefs.PaperStyle p = papers[idx];
            prefs.setPaperStyle(p);
            callbacks.onPaperChanged(p);
        });

        // Date Stamp segment
        LinearLayout segDate = dialog.findViewById(R.id.segDate);
        String[] dateLabels = {
                context.getString(R.string.date_today),
                context.getString(R.string.date_retro),
                context.getString(R.string.date_off)
        };
        CameraPrefs.DateMode[] dates = CameraPrefs.DateMode.values();
        buildSegmented(context, mono, segDate, dateLabels, prefs.getDateMode().ordinal(), idx -> {
            CameraPrefs.DateMode d = dates[idx];
            prefs.setDateMode(d);
            callbacks.onDateModeChanged(d);
        });

        // Film texture & camera options
        LinearLayout toggleContainer = dialog.findViewById(R.id.toggleContainer);
        addToggle(context, toggleContainer, R.string.grain_boost, prefs.isGrainBoost(), enabled -> {
            prefs.setGrainBoost(enabled);
            callbacks.onGrainBoostChanged(enabled);
        }, true);
        addToggle(context, toggleContainer, R.string.light_leak, prefs.isLightLeakEnabled(), enabled -> {
            prefs.setLightLeakEnabled(enabled);
            callbacks.onLightLeakChanged(enabled);
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

        // Beginner Assist section ("辅助")
        LinearLayout assistContainer = dialog.findViewById(R.id.assistToggleContainer);
        final ToggleRow[] subRows = new ToggleRow[5];
        boolean masterInitial = prefs.isAssistEnabled();

        addToggle(context, assistContainer, R.string.assist_master, masterInitial, enabled -> {
            prefs.setAssistEnabled(enabled);
            updateAssistChildrenEnabled(subRows, enabled);
            callbacks.onAssistSettingsChanged();
        }, true);
        subRows[0] = addToggle(context, assistContainer, R.string.assist_comp,
                prefs.isAssistComposition(), enabled -> {
                    prefs.setAssistComposition(enabled);
                    callbacks.onAssistSettingsChanged();
                }, true);
        subRows[1] = addToggle(context, assistContainer, R.string.assist_exposure,
                prefs.isAssistExposure(), enabled -> {
                    prefs.setAssistExposure(enabled);
                    callbacks.onAssistSettingsChanged();
                }, true);
        subRows[2] = addToggle(context, assistContainer, R.string.assist_scene,
                prefs.isAssistScene(), enabled -> {
                    prefs.setAssistScene(enabled);
                    callbacks.onAssistSettingsChanged();
                }, true);
        subRows[3] = addToggle(context, assistContainer, R.string.assist_stability,
                prefs.isAssistStability(), enabled -> {
                    prefs.setAssistStability(enabled);
                    callbacks.onAssistSettingsChanged();
                }, true);
        subRows[4] = addToggle(context, assistContainer, R.string.assist_steady_shutter,
                prefs.isAssistSteadyShutter(), enabled -> {
                    prefs.setAssistSteadyShutter(enabled);
                    callbacks.onAssistSettingsChanged();
                }, false);
        updateAssistChildrenEnabled(subRows, masterInitial);

        // Calibration buttons
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

    private static void updateAssistChildrenEnabled(ToggleRow[] rows, boolean masterEnabled) {
        for (ToggleRow r : rows) {
            if (r == null) continue;
            r.sw.setEnabled(masterEnabled);
            r.row.setEnabled(masterEnabled);
            r.row.setAlpha(masterEnabled ? 1f : 0.45f);
        }
    }

    private interface OnSegmentListener {
        void onSelect(int index);
    }

    private static void buildSegmented(Context context, Typeface mono, LinearLayout container,
                                       String[] labels, int initialIndex, OnSegmentListener listener) {
        container.removeAllViews();
        final TextView[] buttons = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView tv = new TextView(context);
            tv.setTextAppearance(R.style.TextMonoCaption);
            tv.setText(labels[i]);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setContentDescription(labels[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            int idx = i;
            tv.setOnClickListener(v -> {
                updateSegments(context, mono, buttons, idx);
                listener.onSelect(idx);
            });
            container.addView(tv, lp);
            buttons[i] = tv;
        }
        updateSegments(context, mono, buttons, initialIndex);
    }

    private static void updateSegments(Context context, Typeface mono, TextView[] buttons, int activeIdx) {
        int ink = context.getResources().getColor(R.color.ink, context.getTheme());
        int paperDim = context.getResources().getColor(R.color.paper_dim, context.getTheme());
        for (int i = 0; i < buttons.length; i++) {
            boolean active = (i == activeIdx);
            buttons[i].setBackgroundResource(active ? R.drawable.bg_segment_active : R.drawable.bg_pill_ripple);
            buttons[i].setTextColor(active ? ink : paperDim);
            buttons[i].setTypeface(mono, active ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private interface OnToggleListener {
        void onChanged(boolean checked);
    }

    private static ToggleRow addToggle(Context context, LinearLayout parent, int labelRes,
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
        return new ToggleRow(row, label, sw);
    }

    private static void applyBottomInset(View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            v.setPadding(0, 0, 0, bottom);
            return insets;
        });
    }
}
