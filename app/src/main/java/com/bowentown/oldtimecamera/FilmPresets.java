package com.bowentown.oldtimecamera;

import java.util.Locale;

/**
 * The curated 10-stock flagship film & retro camera library.
 *
 * <p>Stock names are original descriptive works of this project. Each stock pairs a 3D HaldCLUT
 * emulsion response (split-toning, selective foliage/sky/skin shifts, filmic highlight roll-off)
 * with lens-level optical traits (disposable resin chromatic fringing, corner softness, flash pop).
 */
public final class FilmPresets {

    private static final FilmPreset[] STOCKS = {
            // 0: Clean optical pass-through
            FilmPreset.builder()
                    .id("raw")
                    .name("原色")
                    .isoTag("RAW")
                    .note("无滤镜原色直出 · 仅保留相纸与色温")
                    .shortNote("原片直出")
                    .swatch(0xFF9E988E)
                    .matrix(1, 0, 0, 0, 1, 0, 0, 0, 1)
                    .saturation(1f).contrast(1f).fade(0f)
                    .grain(0.010f).vignette(0.05f).leak(0f).halation(0f)
                    .lutStrength(0f)
                    .paperColor(0xFFF5F1E8)
                    .build(),

            // 1: Flagship 90s Disposable Nostalgia (KD Pro benchmark)
            FilmPreset.builder()
                    .id("kd100")
                    .name("怀旧 100")
                    .isoTag("KD")
                    .note("90s 一次性浓怀旧 / 墨青暗影 / 琥珀暖金 / 树脂镜片色散")
                    .shortNote("一次性浓怀旧 · 琥珀墨青")
                    .swatch(0xFFD98B38)
                    .matrix(1.06f, 0.02f, -0.02f,
                            0.00f, 1.01f, 0.01f,
                            -0.02f, 0.02f, 0.90f)
                    .offset(0.008f, 0.004f, -0.008f)
                    .saturation(1.14f).contrast(1.12f).brightness(0.006f)
                    .fade(0.08f).grain(0.062f).vignette(0.36f)
                    .leak(0.14f).halation(0.30f)
                    .leakColor(1f, 0.44f, 0.14f)
                    .shadowTint(-0.032f, 0.022f, 0.028f)
                    .highlightTint(0.048f, 0.026f, -0.038f)
                    .highlightRolloff(0.76f)
                    .foliageShift(0.65f)
                    .skyShift(0.48f)
                    .skinWarmth(0.55f)
                    .chromaAberration(0.65f)
                    .cornerSoftness(0.46f)
                    .flashPop(0.88f)
                    .paperColor(0xFFF2E8D5)
                    .build(),

            // 2: Y2K Rose-Cream Point-and-Shoot (CPM35 benchmark)
            FilmPreset.builder()
                    .id("cpm35")
                    .name("蜜桃 35")
                    .isoTag("CPM")
                    .note("千禧玫瑰奶油 / 柔雾粉调肤色 / 低饱和复古胶片")
                    .shortNote("玫瑰奶油 · 千禧柔雾")
                    .swatch(0xFFE0A49B)
                    .matrix(1.04f, 0.03f, 0.01f,
                            0.01f, 0.99f, 0.01f,
                            0.02f, 0.01f, 0.94f)
                    .offset(0.012f, 0.003f, 0.006f)
                    .saturation(0.90f).contrast(0.98f).brightness(0.012f)
                    .fade(0.14f).grain(0.048f).vignette(0.24f)
                    .leak(0.06f).halation(0.24f)
                    .leakColor(1f, 0.56f, 0.42f)
                    .shadowTint(0.018f, -0.008f, 0.016f)
                    .highlightTint(0.042f, 0.018f, 0.008f)
                    .highlightRolloff(0.82f)
                    .foliageShift(0.35f)
                    .skyShift(0.22f)
                    .skinWarmth(-0.62f)
                    .chromaAberration(0.28f)
                    .cornerSoftness(0.34f)
                    .flashPop(0.72f)
                    .paperColor(0xFFF6ECE6)
                    .build(),

            // 3: Clean Japanese Street 35mm Negative (FXN R benchmark)
            FilmPreset.builder()
                    .id("fxn400")
                    .name("清透 400")
                    .isoTag("FXN")
                    .note("日系清透负片 / 青蓝天空 / 翠绿树影 / 温润肤色")
                    .shortNote("日系街拍 · 清透青翠")
                    .swatch(0xFF5FA693)
                    .matrix(0.99f, 0.01f, 0.00f,
                            0.00f, 1.03f, 0.02f,
                            -0.01f, 0.03f, 1.03f)
                    .offset(-0.004f, 0.005f, 0.006f)
                    .saturation(1.02f).contrast(1.04f).brightness(0.008f)
                    .fade(0.06f).grain(0.042f).vignette(0.16f)
                    .leak(0f).halation(0.16f)
                    .shadowTint(-0.022f, 0.018f, 0.026f)
                    .highlightTint(0.022f, 0.016f, -0.008f)
                    .highlightRolloff(0.68f)
                    .foliageShift(0.72f)
                    .skyShift(0.65f)
                    .skinWarmth(0.20f)
                    .chromaAberration(0.12f)
                    .cornerSoftness(0.10f)
                    .flashPop(0.35f)
                    .paperColor(0xFFF2F1EA)
                    .build(),

            // 4: Early-2000s Pocket CCD Digicam
            FilmPreset.builder()
                    .id("ccd2000")
                    .name("千禧 CCD")
                    .isoTag("CCD")
                    .note("早期口袋数码 / 冷白瓷感高光 / 直闪聚光与微紫边")
                    .shortNote("千禧数码 · 冷白直闪")
                    .swatch(0xFF7CA4C4)
                    .matrix(0.98f, 0.01f, 0.01f,
                            0.00f, 1.02f, 0.02f,
                            0.01f, 0.02f, 1.08f)
                    .offset(-0.004f, 0.002f, 0.010f)
                    .saturation(1.08f).contrast(1.14f).brightness(0.006f)
                    .fade(0.03f).grain(0.030f).vignette(0.26f)
                    .leak(0f).halation(0.20f)
                    .shadowTint(-0.012f, -0.004f, 0.028f)
                    .highlightTint(-0.006f, 0.012f, 0.030f)
                    .highlightRolloff(0.42f)
                    .foliageShift(0.18f)
                    .skyShift(0.30f)
                    .skinWarmth(-0.28f)
                    .chromaAberration(0.52f)
                    .cornerSoftness(0.22f)
                    .flashPop(0.92f)
                    .paperColor(0xFFEEF1F4)
                    .build(),

            // 5: Soft Golden-Hour Portrait Negative
            FilmPreset.builder()
                    .id("warm400")
                    .name("暖肤 400")
                    .isoTag("400")
                    .note("柔光人像负片 / 细腻奶油肤质 / 高宽容度柔调")
                    .shortNote("暖肤人像 · 高宽容度")
                    .swatch(0xFFD9A583)
                    .matrix(1.04f, 0.01f, 0.00f,
                            0.00f, 1.00f, 0.00f,
                            0.00f, 0.01f, 0.94f)
                    .offset(0.008f, 0.003f, -0.004f)
                    .saturation(0.97f).contrast(1.00f).brightness(0.010f)
                    .fade(0.08f).grain(0.038f).vignette(0.16f)
                    .leak(0.04f).halation(0.28f)
                    .leakColor(1f, 0.48f, 0.22f)
                    .shadowTint(-0.010f, 0.008f, 0.012f)
                    .highlightTint(0.036f, 0.020f, -0.018f)
                    .highlightRolloff(0.84f)
                    .foliageShift(0.42f)
                    .skyShift(0.32f)
                    .skinWarmth(0.38f)
                    .paperColor(0xFFF3ECE0)
                    .build(),

            // 6: Golden Sunset Consumer Roll
            FilmPreset.builder()
                    .id("daylight200")
                    .name("暖阳 200")
                    .isoTag("200")
                    .note("浓郁落日金黄 / 焦糖暖棕暗影 / 随机暖橙漏光")
                    .shortNote("落日金黄 · 暖橙漏光")
                    .swatch(0xFFD8A745)
                    .matrix(1.08f, 0.02f, -0.01f,
                            0.01f, 1.01f, 0.00f,
                            -0.01f, 0.01f, 0.87f)
                    .offset(0.014f, 0.005f, -0.014f)
                    .saturation(1.10f).contrast(1.06f).brightness(0.008f)
                    .fade(0.09f).grain(0.050f).vignette(0.22f)
                    .leak(0.18f).halation(0.28f)
                    .leakColor(1f, 0.56f, 0.16f)
                    .shadowTint(0.018f, 0.004f, -0.016f)
                    .highlightTint(0.052f, 0.032f, -0.042f)
                    .highlightRolloff(0.72f)
                    .foliageShift(0.28f)
                    .skyShift(0.38f)
                    .skinWarmth(0.62f)
                    .chromaAberration(0.22f)
                    .paperColor(0xFFF1E4C6)
                    .build(),

            // 7: Cinema Tungsten Night Stock
            FilmPreset.builder()
                    .id("tungsten800")
                    .name("霓虹 800")
                    .isoTag("800")
                    .note("电影钨丝夜景 / 冷青蓝夜色暗部 / 强烈朱红高光晕")
                    .shortNote("钨丝夜景 · 朱红光晕")
                    .swatch(0xFF3E849E)
                    .matrix(0.93f, 0.00f, 0.02f,
                            0.00f, 1.01f, 0.03f,
                            0.00f, 0.04f, 1.12f)
                    .offset(-0.010f, 0.004f, 0.016f)
                    .saturation(1.08f).contrast(1.10f).brightness(0.004f)
                    .fade(0.06f).grain(0.072f).vignette(0.28f)
                    .leak(0.06f).halation(0.85f)
                    .leakColor(1f, 0.24f, 0.12f)
                    .shadowTint(-0.038f, 0.016f, 0.046f)
                    .highlightTint(0.024f, -0.004f, -0.012f)
                    .highlightRolloff(0.66f)
                    .skyShift(0.55f)
                    .skinWarmth(0.25f)
                    .chromaAberration(0.32f)
                    .paperColor(0xFFEAECE8)
                    .build(),

            // 8: Panchromatic Silver Gelatin B&W
            FilmPreset.builder()
                    .id("hardbw400")
                    .name("银盐黑白")
                    .isoTag("B&W")
                    .note("纪实银盐黑白 / 厚重双层颗粒 / 戏剧明暗反差")
                    .shortNote("纪实银盐 · 厚重反差")
                    .swatch(0xFF4C4A46)
                    .matrix(1, 0, 0, 0, 1, 0, 0, 0, 1)
                    .saturation(0f).contrast(1.22f).brightness(-0.004f)
                    .fade(0.07f).grain(0.088f).vignette(0.30f)
                    .leak(0f).halation(0.14f)
                    .grayscale(true)
                    .shadowTint(0.006f, 0.005f, 0.002f)
                    .highlightTint(0.018f, 0.014f, 0.004f)
                    .highlightRolloff(0.74f)
                    .paperColor(0xFFEEECE6)
                    .suggestStamp(false)
                    .build(),

            // 9: Instant Integral Film Pack
            FilmPreset.builder()
                    .id("instant600")
                    .name("一次成像")
                    .isoTag("600")
                    .note("复古相纸显影 / 青绿暗影偏色 / 奶油高光与重暗角")
                    .shortNote("一次成像 · 青影奶白")
                    .swatch(0xFFC8B58C)
                    .matrix(1.03f, 0.02f, 0.00f,
                            0.00f, 1.01f, 0.02f,
                            -0.01f, 0.02f, 0.90f)
                    .offset(0.012f, 0.010f, -0.006f)
                    .saturation(0.88f).contrast(1.02f).brightness(0.012f)
                    .fade(0.16f).grain(0.056f).vignette(0.38f)
                    .leak(0.18f).halation(0.28f)
                    .leakColor(1f, 0.52f, 0.24f)
                    .shadowTint(-0.028f, 0.028f, 0.018f)
                    .highlightTint(0.044f, 0.032f, -0.022f)
                    .highlightRolloff(0.80f)
                    .foliageShift(0.45f)
                    .skinWarmth(0.24f)
                    .chromaAberration(0.40f)
                    .cornerSoftness(0.42f)
                    .flashPop(0.78f)
                    .paperColor(0xFFF6F0DC)
                    .build()
    };

    /** Default stock for first launch: flagship KD 100 nostalgic disposable film. */
    public static final int DEFAULT_INDEX = 1;

    private FilmPresets() {}

    public static FilmPreset[] all() {
        return STOCKS;
    }

    /** Resolves current and legacy v1.x stock IDs seamlessly so saved recipes never orphan. */
    public static String normalizeId(String id) {
        if (id == null) return "";
        String clean = id.trim().toLowerCase(Locale.US);
        switch (clean) {
            case "vivid50":
                return "kd100";
            case "archive64":
            case "vintage64":
                return "cpm35";
            case "coolcast400":
            case "cool400":
                return "fxn400";
            case "softbw400":
                return "hardbw400";
            default:
                return clean;
        }
    }

    public static int indexOfId(String id) {
        if (id == null) return DEFAULT_INDEX;
        String norm = normalizeId(id);
        for (int i = 0; i < STOCKS.length; i++) {
            if (STOCKS[i].id.equals(norm)) return i;
        }
        return DEFAULT_INDEX;
    }
}
