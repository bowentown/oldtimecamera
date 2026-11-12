package com.bowentown.oldtimecamera;

/** The built-in film stock library for v1. */
public final class FilmPresets {

    private FilmPresets() {}

    public static FilmPreset[] all() {
        return new FilmPreset[]{
                FilmPreset.builder()
                        .id("raw")
                        .name("ORIGINAL")
                        .note("不施滤镜 · 仅相纸与日期背印")
                        .swatch(0xFF9A9A92)
                        .matrix(1, 0, 0, 0, 1, 0, 0, 0, 1)
                        .saturation(1f).contrast(1f).fade(0f)
                        .grain(0.012f).vignette(0.08f).leak(0f).halation(0f)
                        .paperColor(0xFFF4F0E6)
                        .build(),

                FilmPreset.builder()
                        .id("portra400")
                        .name("PORTRA 400")
                        .note("柯达人像负片 · 暖肤色 / 低反差 / 细颗粒 / 象牙白边")
                        .swatch(0xFFD9A583)
                        .matrix(1.05f, 0.01f, 0.00f,
                                0.00f, 1.00f, 0.00f,
                                0.00f, 0.00f, 0.95f)
                        .offset(0.008f, 0.002f, -0.004f)
                        .saturation(0.94f).contrast(0.92f).brightness(0.012f)
                        .fade(0.30f).grain(0.050f).vignette(0.24f)
                        .leak(0.06f).halation(0.48f)
                        .leakColor(1f, 0.46f, 0.20f)
                        .paperColor(0xFFF1E9D8)
                        .build(),

                FilmPreset.builder()
                        .id("gold200")
                        .name("GOLD 200")
                        .note("柯达金胶卷 · 金黄日光 / 暖高光 / 暖黄漏光 / 米黄相纸")
                        .swatch(0xFFD8A745)
                        .matrix(1.08f, 0.02f, 0.00f,
                                0.00f, 1.01f, 0.00f,
                                0.00f, 0.01f, 0.88f)
                        .offset(0.014f, 0.004f, -0.012f)
                        .saturation(1.06f).contrast(1.02f).brightness(0.010f)
                        .fade(0.20f).grain(0.062f).vignette(0.24f)
                        .leak(0.16f).halation(0.32f)
                        .leakColor(1f, 0.58f, 0.16f)
                        .paperColor(0xFFEFE1BE)
                        .build(),

                FilmPreset.builder()
                        .id("superia400")
                        .name("SUPERIA 400")
                        .note("富士民用负片 · 青绿阴影 / 冷调 / 日常街拍 / 冷白相纸")
                        .swatch(0xFF6F9182)
                        .matrix(0.96f, 0.00f, 0.00f,
                                0.00f, 1.03f, 0.01f,
                                0.00f, 0.01f, 1.06f)
                        .offset(-0.006f, 0.002f, 0.010f)
                        .saturation(1.02f).contrast(1.00f).brightness(0f)
                        .fade(0.22f).grain(0.070f).vignette(0.26f)
                        .leak(0.05f).halation(0.18f)
                        .leakColor(0.36f, 0.86f, 0.70f)
                        .paperColor(0xFFEDEEE6)
                        .build(),

                FilmPreset.builder()
                        .id("velvia50")
                        .name("VELVIA 50")
                        .note("富士反转片 · 高饱和 / 深蓝绿 / 强反差 / 无漏光")
                        .swatch(0xFF2F7A5B)
                        .matrix(1.02f, 0.00f, 0.00f,
                                0.00f, 1.04f, 0.00f,
                                0.00f, 0.00f, 1.05f)
                        .offset(0f, 0f, 0.004f)
                        .saturation(1.32f).contrast(1.20f).brightness(-0.006f)
                        .fade(0.04f).grain(0.028f).vignette(0.30f)
                        .leak(0f).halation(0.14f)
                        .paperColor(0xFFF2EFE4)
                        .build(),

                FilmPreset.builder()
                        .id("trix400")
                        .name("TRI-X 400")
                        .note("柯达黑白负片 · 硬调 / 粗颗粒 / 重暗角 / 无日期水印")
                        .swatch(0xFF5A5A58)
                        .matrix(1, 0, 0, 0, 1, 0, 0, 0, 1)
                        .saturation(0f).contrast(1.26f).brightness(0.004f)
                        .fade(0.12f).grain(0.135f).vignette(0.38f)
                        .leak(0f).halation(0f)
                        .grayscale(true)
                        .paperColor(0xFFE8E5DD)
                        .suggestStamp(false)
                        .build(),

                FilmPreset.builder()
                        .id("polaroid600")
                        .name("POLAROID 600")
                        .note("一次成像 · 褪色偏黄 / 强漏光 / 宽白边 / 橙色日期")
                        .swatch(0xFFC8B58C)
                        .matrix(1.04f, 0.02f, 0.00f,
                                0.00f, 1.00f, 0.01f,
                                0.00f, 0.00f, 0.86f)
                        .offset(0.018f, 0.010f, -0.010f)
                        .saturation(0.82f).contrast(0.88f).brightness(0.020f)
                        .fade(0.52f).grain(0.090f).vignette(0.42f)
                        .leak(0.30f).halation(0.34f)
                        .leakColor(1f, 0.52f, 0.24f)
                        .paperColor(0xFFF6F0DC)
                        .build()
        };
    }
}
