package com.bowentown.oldtimecamera;

/** The built-in classic film stock library. */
public final class FilmPresets {

    private static final FilmPreset[] STOCKS = {
            FilmPreset.builder()
                    .id("raw")
                    .name("ORIGINAL")
                    .isoTag("RAW")
                    .note("原片直出 · 仅保留相纸与日期")
                    .shortNote("原片直出 · 真实色彩")
                    .swatch(0xFF9E988E)
                    .matrix(1, 0, 0, 0, 1, 0, 0, 0, 1)
                    .saturation(1f).contrast(1f).fade(0f)
                    .grain(0.012f).vignette(0.08f).leak(0f).halation(0f)
                    .paperColor(0xFFF4F0E6)
                    .build(),

            FilmPreset.builder()
                    .id("portra400")
                    .name("PORTRA 400")
                    .isoTag("400")
                    .note("暖肤色 / 低反差 / 细颗粒 / 象牙相纸")
                    .shortNote("暖肤色 · 低反差 · 温柔人像")
                    .swatch(0xFFD9A583)
                    .matrix(1.05f, 0.01f, 0.00f,
                            0.00f, 1.00f, 0.00f,
                            0.00f, 0.00f, 0.95f)
                    .offset(0.008f, 0.002f, -0.004f)
                    .saturation(0.94f).contrast(0.92f).brightness(0.012f)
                    .fade(0.28f).grain(0.048f).vignette(0.22f)
                    .leak(0.06f).halation(0.45f)
                    .leakColor(1f, 0.46f, 0.20f)
                    .paperColor(0xFFF1E9D8)
                    .build(),

            FilmPreset.builder()
                    .id("gold200")
                    .name("GOLD 200")
                    .isoTag("200")
                    .note("金黄日光 / 暖高光 / 暖黄漏光 / 米黄相纸")
                    .shortNote("金黄日光 · 暖调街头")
                    .swatch(0xFFD8A745)
                    .matrix(1.08f, 0.02f, 0.00f,
                            0.00f, 1.01f, 0.00f,
                            0.00f, 0.01f, 0.88f)
                    .offset(0.014f, 0.004f, -0.012f)
                    .saturation(1.06f).contrast(1.02f).brightness(0.010f)
                    .fade(0.20f).grain(0.060f).vignette(0.24f)
                    .leak(0.16f).halation(0.32f)
                    .leakColor(1f, 0.58f, 0.16f)
                    .paperColor(0xFFEFE1BE)
                    .build(),

            FilmPreset.builder()
                    .id("superia400")
                    .name("SUPERIA 400")
                    .isoTag("400")
                    .note("青绿阴影 / 冷调街拍 / 冷白相纸")
                    .shortNote("青绿暗部 · 清透日常")
                    .swatch(0xFF6F9182)
                    .matrix(0.96f, 0.00f, 0.00f,
                            0.00f, 1.03f, 0.01f,
                            0.00f, 0.01f, 1.06f)
                    .offset(-0.006f, 0.002f, 0.010f)
                    .saturation(1.02f).contrast(1.00f).brightness(0f)
                    .fade(0.22f).grain(0.066f).vignette(0.26f)
                    .leak(0.05f).halation(0.18f)
                    .leakColor(0.36f, 0.86f, 0.70f)
                    .paperColor(0xFFEDEEE6)
                    .build(),

            FilmPreset.builder()
                    .id("velvia50")
                    .name("VELVIA 50")
                    .isoTag("50")
                    .note("反转正片 / 高饱和 / 深蓝绿 / 强反差")
                    .shortNote("高饱和反转 · 浓郁风景")
                    .swatch(0xFF2F7A5B)
                    .matrix(1.02f, 0.00f, 0.00f,
                            0.00f, 1.04f, 0.00f,
                            0.00f, 0.00f, 1.05f)
                    .offset(0f, 0f, 0.004f)
                    .saturation(1.30f).contrast(1.18f).brightness(-0.006f)
                    .fade(0.04f).grain(0.026f).vignette(0.28f)
                    .leak(0f).halation(0.14f)
                    .paperColor(0xFFF2EFE4)
                    .build(),

            FilmPreset.builder()
                    .id("cinestill800t")
                    .name("800T CINE")
                    .isoTag("800T")
                    .note("钨丝电影卷 / 冷青夜色 / 霓虹红晕")
                    .shortNote("夜景电影 · 冷青红晕")
                    .swatch(0xFF4F7C94)
                    .matrix(0.94f, 0.00f, 0.02f,
                            0.00f, 0.99f, 0.03f,
                            0.01f, 0.02f, 1.12f)
                    .offset(-0.010f, 0.002f, 0.018f)
                    .saturation(1.04f).contrast(1.08f).brightness(-0.004f)
                    .fade(0.16f).grain(0.068f).vignette(0.32f)
                    .leak(0.08f).halation(0.88f)
                    .leakColor(0.96f, 0.28f, 0.14f)
                    .paperColor(0xFFECECE8)
                    .build(),

            FilmPreset.builder()
                    .id("kodachrome64")
                    .name("KODA 64")
                    .isoTag("64")
                    .note("纪实暖褐 / 浓郁红黄 / 岁月质感")
                    .shortNote("复古纪实 · 暖红厚重")
                    .swatch(0xFFB85D43)
                    .matrix(1.09f, 0.01f, -0.02f,
                            0.01f, 0.99f, -0.01f,
                            -0.01f, 0.00f, 0.91f)
                    .offset(0.010f, -0.002f, -0.008f)
                    .saturation(1.08f).contrast(1.14f).brightness(0.002f)
                    .fade(0.14f).grain(0.042f).vignette(0.30f)
                    .leak(0.06f).halation(0.26f)
                    .leakColor(1f, 0.38f, 0.18f)
                    .paperColor(0xFFF0E6D2)
                    .build(),

            FilmPreset.builder()
                    .id("trix400")
                    .name("TRI-X 400")
                    .isoTag("B&W")
                    .note("黑白硬调 / 粗银盐颗粒 / 重暗角")
                    .shortNote("经典黑白 · 硬朗颗粒")
                    .swatch(0xFF5A5A58)
                    .matrix(1, 0, 0, 0, 1, 0, 0, 0, 1)
                    .saturation(0f).contrast(1.24f).brightness(0.004f)
                    .fade(0.12f).grain(0.125f).vignette(0.38f)
                    .leak(0f).halation(0f)
                    .grayscale(true)
                    .paperColor(0xFFE8E5DD)
                    .suggestStamp(false)
                    .build(),

            FilmPreset.builder()
                    .id("hp5")
                    .name("HP5 PLUS")
                    .isoTag("B&W")
                    .note("人文黑白 / 柔和灰阶 / 宽容影调")
                    .shortNote("人文黑白 · 柔滑灰阶")
                    .swatch(0xFF7B7973)
                    .matrix(1, 0, 0, 0, 1, 0, 0, 0, 1)
                    .saturation(0f).contrast(1.06f).brightness(0.012f)
                    .fade(0.24f).grain(0.082f).vignette(0.26f)
                    .leak(0f).halation(0.10f)
                    .grayscale(true)
                    .paperColor(0xFFEDEAE2)
                    .suggestStamp(false)
                    .build(),

            FilmPreset.builder()
                    .id("polaroid600")
                    .name("POLAROID 600")
                    .isoTag("600")
                    .note("一次成像 / 褪色偏黄 / 奶油漏光")
                    .shortNote("一次成像 · 褪色奶油")
                    .swatch(0xFFC8B58C)
                    .matrix(1.04f, 0.02f, 0.00f,
                            0.00f, 1.00f, 0.01f,
                            0.00f, 0.00f, 0.86f)
                    .offset(0.018f, 0.010f, -0.010f)
                    .saturation(0.84f).contrast(0.88f).brightness(0.018f)
                    .fade(0.48f).grain(0.085f).vignette(0.40f)
                    .leak(0.26f).halation(0.34f)
                    .leakColor(1f, 0.52f, 0.24f)
                    .paperColor(0xFFF6F0DC)
                    .build()
    };

    private FilmPresets() {}

    public static FilmPreset[] all() {
        return STOCKS;
    }

    public static int indexOfId(String id) {
        if (id == null) return 1;
        for (int i = 0; i < STOCKS.length; i++) {
            if (STOCKS[i].id.equals(id)) return i;
        }
        return 1;
    }
}
