package com.bowentown.oldtimecamera;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Shared UI helpers: system bar inset padding, bundled font loader, and film-stock chips. */
public final class FilmUiHelper {

    public interface OnChipSelectedListener {
        void onSelected(int index);
    }

    public interface OnChipLongClickListener {
        boolean onLongClick(int index);
    }

    private static volatile Typeface cachedMono;

    private FilmUiHelper() {}

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static Typeface monoFont(Context context) {
        if (cachedMono != null) return cachedMono;
        if (context == null) return Typeface.MONOSPACE;
        try {
            Typeface tf = context.getResources().getFont(R.font.jetbrains_mono_regular);
            if (tf != null) {
                cachedMono = tf;
                return tf;
            }
        } catch (Exception ignored) {
        }
        return Typeface.MONOSPACE;
    }

    /** Applies status/navigation bar & cutout padding so edge-to-edge on API 35 never clips UI. */
    public static void applySystemBarPadding(View target) {
        final int baseL = target.getPaddingLeft();
        final int baseT = target.getPaddingTop();
        final int baseR = target.getPaddingRight();
        final int baseB = target.getPaddingBottom();

        target.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            int left;
            int right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sys = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = sys.top;
                bottom = sys.bottom;
                left = sys.left;
                right = sys.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(baseL + left, baseT + top, baseR + right, baseB + bottom);
            return insets;
        });
        target.requestApplyInsets();
    }

    /** Styles a dialog as a darkroom bottom sheet and applies bottom system navigation bar inset. */
    @SuppressWarnings("deprecation")
    public static void configureBottomSheet(Dialog dialog, boolean withKeyboardResize) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.BOTTOM);
        if (withKeyboardResize) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        View decor = window.getDecorView();
        decor.setPadding(0, 0, 0, 0);
        decor.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            v.setPadding(0, 0, 0, bottom);
            return insets;
        });
    }

    /** Populates a segmented control row using uniform darkroom capsule styling. */
    public static TextView[] buildSegmented(Context context, Typeface mono, LinearLayout container,
                                            String[] labels, int initialIndex,
                                            OnChipSelectedListener listener) {
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
            final int idx = i;
            tv.setOnClickListener(v -> {
                updateSegments(context, mono, buttons, idx);
                if (listener != null) listener.onSelected(idx);
            });
            container.addView(tv, lp);
            buttons[i] = tv;
        }
        updateSegments(context, mono, buttons, initialIndex);
        return buttons;
    }

    public static void updateSegments(Context context, Typeface mono, TextView[] buttons, int activeIdx) {
        int ink = context.getResources().getColor(R.color.ink, context.getTheme());
        int paperDim = context.getResources().getColor(R.color.paper_dim, context.getTheme());
        for (int i = 0; i < buttons.length; i++) {
            boolean active = (i == activeIdx);
            buttons[i].setBackgroundResource(active ? R.drawable.bg_segment_active : R.drawable.bg_pill_ripple);
            buttons[i].setTextColor(active ? ink : paperDim);
            buttons[i].setTypeface(mono, active ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    /** Populates a horizontal strip of minimalist film-stock chips with swatch dots. */
    public static void buildFilmStrip(Context context,
                                      HorizontalScrollView scroller,
                                      LinearLayout container,
                                      FilmPreset[] presets,
                                      List<LinearLayout> outChips,
                                      OnChipSelectedListener listener) {
        buildFilmStrip(context, scroller, container, presets, null, outChips, listener, null);
    }

    /**
     * Populates built-in film stocks plus optional user-saved custom recipes, supporting
     * long-press tuning or custom deletion.
     */
    public static void buildFilmStrip(Context context,
                                      HorizontalScrollView scroller,
                                      LinearLayout container,
                                      FilmPreset[] presets,
                                      List<FilmRecipe.SavedRecipe> customRecipes,
                                      List<LinearLayout> outChips,
                                      OnChipSelectedListener listener,
                                      OnChipLongClickListener longListener) {
        outChips.clear();
        container.removeAllViews();
        int chipIdx = 0;

        for (FilmPreset preset : presets) {
            addFilmChip(context, scroller, container, outChips,
                    preset.name, preset.swatch, false, chipIdx, listener, longListener);
            chipIdx++;
        }
        if (customRecipes != null) {
            for (FilmRecipe.SavedRecipe recipe : customRecipes) {
                if (recipe == null) continue;
                int baseIdx = FilmPresets.indexOfId(recipe.basePresetId);
                int swatch = presets[baseIdx].swatch;
                addFilmChip(context, scroller, container, outChips,
                        recipe.name, swatch, true, chipIdx, listener, longListener);
                chipIdx++;
            }
        }
    }

    private static void addFilmChip(Context context,
                                    HorizontalScrollView scroller,
                                    LinearLayout container,
                                    List<LinearLayout> outChips,
                                    String labelText,
                                    int swatchColor,
                                    boolean customRing,
                                    int index,
                                    OnChipSelectedListener listener,
                                    OnChipLongClickListener longListener) {
        int padH = dp(context, 12);
        int dotSize = dp(context, customRing ? 7 : 6);
        int chipH = dp(context, 32);
        int marginH = dp(context, 4);

        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(padH, 0, padH, 0);
        chip.setBackgroundResource(R.drawable.bg_chip);

        View dot = new View(context);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(swatchColor);
        dotBg.setStroke(dp(context, customRing ? 2 : 1), customRing ? 0xFFF2A65A : 0x44000000);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
        dotLp.setMarginEnd(dp(context, 6));
        chip.addView(dot, dotLp);

        TextView label = new TextView(context);
        label.setTextAppearance(R.style.FilmLabel);
        label.setText(labelText);
        label.setSingleLine(true);
        chip.addView(label);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, chipH);
        lp.setMargins(marginH, 0, marginH, 0);

        chip.setOnClickListener(v -> {
            listener.onSelected(index);
            centerChip(scroller, chip);
        });
        if (longListener != null) {
            chip.setOnLongClickListener(v -> longListener.onLongClick(index));
        }
        container.addView(chip, lp);
        outChips.add(chip);
    }

    public static void updateChipSelection(Context context, List<LinearLayout> chips, int selectedIndex) {
        int ink = context.getResources().getColor(R.color.ink, context.getTheme());
        int paper = context.getResources().getColor(R.color.paper, context.getTheme());
        Typeface mono = monoFont(context);

        for (int i = 0; i < chips.size(); i++) {
            boolean selected = (i == selectedIndex);
            LinearLayout chip = chips.get(i);
            chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            TextView label = (TextView) chip.getChildAt(1);
            label.setTextColor(selected ? ink : paper);
            label.setTypeface(mono, selected ? Typeface.BOLD : Typeface.NORMAL);
            chip.setAlpha(selected ? 1f : 0.88f);
        }
    }

    public static void centerChip(HorizontalScrollView scroller, View chip) {
        if (scroller == null || chip == null) return;
        scroller.post(() -> {
            int target = chip.getLeft() - (scroller.getWidth() - chip.getWidth()) / 2;
            scroller.smoothScrollTo(Math.max(0, target), 0);
        });
    }
}
