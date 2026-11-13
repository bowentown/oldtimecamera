package com.bowentown.oldtimecamera;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Shared UI helpers: system bar inset padding and film-stock swatch chips. */
public final class FilmUiHelper {

    public interface OnChipSelectedListener {
        void onSelected(int index);
    }

    private FilmUiHelper() {}

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
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

    /** Populates a horizontal strip of minimalist film-stock chips with swatch dots. */
    public static void buildFilmStrip(Context context,
                                      HorizontalScrollView scroller,
                                      LinearLayout container,
                                      FilmPreset[] presets,
                                      List<LinearLayout> outChips,
                                      OnChipSelectedListener listener) {
        outChips.clear();
        container.removeAllViews();
        int padH = dp(context, 10);
        int dotSize = dp(context, 6);
        int chipH = dp(context, 30);
        int marginH = dp(context, 3);

        for (int i = 0; i < presets.length; i++) {
            FilmPreset preset = presets[i];
            LinearLayout chip = new LinearLayout(context);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(padH, 0, padH, 0);
            chip.setBackgroundResource(R.drawable.bg_chip);

            View dot = new View(context);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(preset.swatch);
            dotBg.setStroke(dp(context, 1), 0x44000000);
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
            dotLp.setMarginEnd(dp(context, 6));
            chip.addView(dot, dotLp);

            TextView label = new TextView(context);
            label.setText(preset.name);
            label.setTypeface(Typeface.MONOSPACE);
            label.setTextSize(10f);
            label.setLetterSpacing(0.05f);
            label.setSingleLine(true);
            chip.addView(label);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, chipH);
            lp.setMargins(marginH, 0, marginH, 0);

            int index = i;
            chip.setOnClickListener(v -> {
                listener.onSelected(index);
                centerChip(scroller, chip);
            });
            container.addView(chip, lp);
            outChips.add(chip);
        }
    }

    public static void updateChipSelection(Context context, List<LinearLayout> chips, int selectedIndex) {
        int selectedBg = context.getResources().getColor(R.color.chip_selected, context.getTheme());
        int idleBg = context.getResources().getColor(R.color.chip_idle, context.getTheme());
        int ink = context.getResources().getColor(R.color.ink, context.getTheme());
        int paper = context.getResources().getColor(R.color.paper, context.getTheme());

        for (int i = 0; i < chips.size(); i++) {
            boolean selected = (i == selectedIndex);
            LinearLayout chip = chips.get(i);
            chip.setBackgroundTintList(ColorStateList.valueOf(selected ? selectedBg : idleBg));
            TextView label = (TextView) chip.getChildAt(1);
            label.setTextColor(selected ? ink : paper);
            label.setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
            chip.setAlpha(selected ? 1f : 0.86f);
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
