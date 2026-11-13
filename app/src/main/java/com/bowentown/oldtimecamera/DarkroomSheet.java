package com.bowentown.oldtimecamera;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
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
 * Custom matte-black darkroom bottom sheet replacing the stock AlertDialog.
 */
public final class DarkroomSheet {

    public interface Callbacks {
        void onAspectChanged(CameraPrefs.AspectRatio ratio);
        void onPaperChanged(CameraPrefs.PaperStyle style);
        void onDateModeChanged(CameraPrefs.DateMode mode);
        void onGrainBoostChanged(boolean enabled);
        void onLightLeakChanged(boolean enabled);
        void onGridChanged(boolean enabled);
        void onShutterSoundChanged(boolean enabled);
        void onFrontMirrorChanged(boolean enabled);
        int onCycleRotation();
        String getRotationLabel();
        void onLoadNewRoll();
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

        dialog.findViewById(R.id.sheetDoneButton).setOnClickListener(v -> dialog.dismiss());

        // Aspect Ratio segment
        LinearLayout segAspect = dialog.findViewById(R.id.segAspect);
        CameraPrefs.AspectRatio[] ratios = CameraPrefs.AspectRatio.values();
        String[] ratioLabels = new String[ratios.length];
        for (int i = 0; i < ratios.length; i++) ratioLabels[i] = ratios[i].label;
        buildSegmented(context, segAspect, ratioLabels, prefs.getAspectRatio().ordinal(), idx -> {
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
        buildSegmented(context, segPaper, paperLabels, prefs.getPaperStyle().ordinal(), idx -> {
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
        buildSegmented(context, segDate, dateLabels, prefs.getDateMode().ordinal(), idx -> {
            CameraPrefs.DateMode d = dates[idx];
            prefs.setDateMode(d);
            callbacks.onDateModeChanged(d);
        });

        // Darkroom toggles
        LinearLayout toggleContainer = dialog.findViewById(R.id.toggleContainer);
        addToggle(context, toggleContainer, R.string.grain_boost, prefs.isGrainBoost(), enabled -> {
            prefs.setGrainBoost(enabled);
            callbacks.onGrainBoostChanged(enabled);
        }, true);
        addToggle(context, toggleContainer, R.string.light_leak, prefs.isLightLeakEnabled(), enabled -> {
            prefs.setLightLeakEnabled(enabled);
            callbacks.onLightLeakChanged(enabled);
        }, true);
        addToggle(context, toggleContainer, R.string.grid_overlay, prefs.isGridEnabled(), enabled -> {
            prefs.setGridEnabled(enabled);
            callbacks.onGridChanged(enabled);
        }, true);
        addToggle(context, toggleContainer, R.string.shutter_sound, prefs.isShutterSoundEnabled(), enabled -> {
            prefs.setShutterSoundEnabled(enabled);
            callbacks.onShutterSoundChanged(enabled);
        }, true);
        addToggle(context, toggleContainer, R.string.front_mirror, prefs.isFrontMirrorEnabled(), enabled -> {
            prefs.setFrontMirrorEnabled(enabled);
            callbacks.onFrontMirrorChanged(enabled);
        }, false);

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

        dialog.show();
    }

    private interface OnSegmentListener {
        void onSelect(int index);
    }

    private static void buildSegmented(Context context, LinearLayout container, String[] labels,
                                       int initialIndex, OnSegmentListener listener) {
        container.removeAllViews();
        final TextView[] buttons = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView tv = new TextView(context);
            tv.setText(labels[i]);
            tv.setGravity(Gravity.CENTER);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(10.5f);
            tv.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            int idx = i;
            tv.setOnClickListener(v -> {
                updateSegments(context, buttons, idx);
                listener.onSelect(idx);
            });
            container.addView(tv, lp);
            buttons[i] = tv;
        }
        updateSegments(context, buttons, initialIndex);
    }

    private static void updateSegments(Context context, TextView[] buttons, int activeIdx) {
        int ink = context.getResources().getColor(R.color.ink, context.getTheme());
        int paperDim = context.getResources().getColor(R.color.paper_dim, context.getTheme());
        for (int i = 0; i < buttons.length; i++) {
            boolean active = (i == activeIdx);
            buttons[i].setBackgroundResource(active ? R.drawable.bg_segment_active : 0);
            buttons[i].setTextColor(active ? ink : paperDim);
            buttons[i].setTypeface(Typeface.MONOSPACE, active ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private interface OnToggleListener {
        void onChanged(boolean checked);
    }

    private static void addToggle(Context context, LinearLayout parent, int labelRes,
                                  boolean initial, OnToggleListener listener, boolean dividerAfter) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padV = FilmUiHelper.dp(context, 10);
        row.setPadding(0, padV, 0, padV);

        TextView label = new TextView(context);
        label.setText(labelRes);
        label.setTextColor(context.getResources().getColor(R.color.paper, context.getTheme()));
        label.setTextSize(13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, lp);

        Switch sw = new Switch(context);
        sw.setChecked(initial);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{0xFFF0E8D8, 0xFF9B9180}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{0xFFD25A34, 0xFF3A342C}));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> listener.onChanged(isChecked));
        row.addView(sw);

        row.setOnClickListener(v -> sw.toggle());
        parent.addView(row);

        if (dividerAfter) {
            View div = new View(context);
            div.setBackgroundColor(0x18EFE7D6);
            parent.addView(div, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, FilmUiHelper.dp(context, 1))));
        }
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
