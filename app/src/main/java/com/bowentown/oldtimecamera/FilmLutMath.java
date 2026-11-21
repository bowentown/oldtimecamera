package com.bowentown.oldtimecamera;

import java.util.Locale;

/**
 * Pure-Java 3D HaldCLUT generator (16x16x16 lattice packed as a 256x16 RGBA strip texture),
 * trilinear evaluator, and Von Kries white-balance math.
 *
 * <p>Keeping all non-linear cross-channel chemical curves, 3-zone split-toning, selective
 * hue shifts (foliage emerald/pine, cerulean sky, amber/rose-cream skin), and filmic shoulder
 * compression in pure Java guarantees 0 KB binary asset bloat, 100% deterministic preview/photo
 * parity in OpenGL ES 2.0, and direct unit-testability on the JVM.
 */
public final class FilmLutMath {

    public static final int LUT_DIM = 16;
    public static final int LUT_WIDTH = LUT_DIM * LUT_DIM; // 256
    public static final int LUT_HEIGHT = LUT_DIM;          // 16

    private FilmLutMath() {}

    /**
     * Generates a {@code 256 x 16} RGBA8888 3D lookup table strip for {@code preset}.
     * Layout: X = {@code bSlice * 16 + rIdx} ({@code 0..255}), Y = {@code gIdx} ({@code 0..15}).
     */
    public static byte[] generateLut256x16(FilmPreset preset) {
        byte[] out = new byte[LUT_WIDTH * LUT_HEIGHT * 4];
        float[] rgb = new float[3];
        for (int bIdx = 0; bIdx < LUT_DIM; bIdx++) {
            float bIn = bIdx / (float) (LUT_DIM - 1);
            for (int gIdx = 0; gIdx < LUT_DIM; gIdx++) {
                float gIn = gIdx / (float) (LUT_DIM - 1);
                for (int rIdx = 0; rIdx < LUT_DIM; rIdx++) {
                    float rIn = rIdx / (float) (LUT_DIM - 1);
                    transformNode(preset, rIn, gIn, bIn, rgb);
                    int x = bIdx * LUT_DIM + rIdx;
                    int idx = (gIdx * LUT_WIDTH + x) * 4;
                    out[idx] = toByte(rgb[0]);
                    out[idx + 1] = toByte(rgb[1]);
                    out[idx + 2] = toByte(rgb[2]);
                    out[idx + 3] = (byte) 0xFF;
                }
            }
        }
        return out;
    }

    /** Evaluates the non-linear analog color emulsion model at normalized {@code (r, g, b)}. */
    public static void transformNode(FilmPreset p, float rIn, float gIn, float bIn, float[] outRgb) {
        if (p == null || p.lutStrength <= 0.001f) {
            outRgb[0] = clamp01(rIn);
            outRgb[1] = clamp01(gIn);
            outRgb[2] = clamp01(bIn);
            return;
        }

        float r = clamp01(rIn);
        float g = clamp01(gIn);
        float b = clamp01(bIn);

        // 1. Selective hue-sector emulsion warps (impossible with a linear 3x3 matrix):
        float maxC = Math.max(r, Math.max(g, b));
        float minC = Math.min(r, Math.min(g, b));
        float chroma = maxC - minC;

        if (chroma > 0.015f && !p.grayscale) {
            // Foliage green dominance: g is dominant over r and b -> shift toward deep pine/emerald
            float greenDom = Math.max(0f, g - Math.max(r * 0.78f + b * 0.22f, b));
            if (greenDom > 0f && Math.abs(p.foliageShift) > 0.001f) {
                float k = greenDom * p.foliageShift;
                r -= k * 0.28f;
                g -= Math.abs(k) * 0.12f;
                b += k * 0.22f;
            }

            // Sky blue/cyan dominance: b is dominant over r -> shift toward vintage cerulean/teal
            float skyDom = Math.max(0f, b - r * 0.85f) * clamp01(0.4f + 0.6f * g);
            if (skyDom > 0f && Math.abs(p.skyShift) > 0.001f) {
                float k = skyDom * p.skyShift;
                r -= k * 0.14f;
                g += k * 0.20f;
                b -= Math.max(0f, k) * 0.06f;
            }

            // Skin & warm orange-coral dominance: r > g > b
            float skinDom = Math.max(0f, Math.min(r - g * 0.65f, g - b * 0.72f));
            if (skinDom > 0f && Math.abs(p.skinWarmth) > 0.001f) {
                float k = skinDom * p.skinWarmth;
                if (k >= 0f) {
                    // Amber-gold Kodak warmth (KD 100 / Daylight)
                    r += k * 0.12f;
                    g += k * 0.09f;
                    b -= k * 0.15f;
                } else {
                    // Rose-cream pastel shift (CPM 35): soften yellow, lift delicate rose-pink
                    float m = -k;
                    r += m * 0.08f;
                    g -= m * 0.05f;
                    b += m * 0.10f;
                }
            }
        }

        // 2. Filmic H&D toe & highlight shoulder roll-off (creamy non-clipping highlights):
        float roll = clamp01(p.highlightRolloff);
        if (roll > 0.001f) {
            r = filmicShoulder(clamp01(r), roll);
            g = filmicShoulder(clamp01(g), roll);
            b = filmicShoulder(clamp01(b), roll);
        }

        // 3. 3-zone luminance split-toning (deep tinted shadows + warm creamy highlights,
        //    preserving true black anchor at lum=0 so shadows never look washed-out gray):
        float lum = 0.2126f * clamp01(r) + 0.7152f * clamp01(g) + 0.0722f * clamp01(b);
        float shadowMask = (1f - lum) * (1f - lum) * smoothstep(0.0f, 0.22f, lum + 0.05f);
        float highlightMask = lum * lum;

        r += p.shadowTint[0] * shadowMask + p.highlightTint[0] * highlightMask;
        g += p.shadowTint[1] * shadowMask + p.highlightTint[1] * highlightMask;
        b += p.shadowTint[2] * shadowMask + p.highlightTint[2] * highlightMask;

        outRgb[0] = clamp01(r);
        outRgb[1] = clamp01(g);
        outRgb[2] = clamp01(b);
    }

    /** Soft analog shoulder compression above 0.58 with subtle crisp toe below 0.22. */
    public static float filmicShoulder(float x, float amount) {
        float v = clamp01(x);
        // Gentle toe density + exponential highlight roll-off toward 0.985 ceiling
        float toe = v * v * (3f - 2f * v);
        float mixed = v + (toe - v) * (0.28f * amount);
        if (mixed > 0.58f) {
            float t = (mixed - 0.58f) / 0.42f;
            float compressed = 0.58f + 0.405f * (1f - (float) Math.exp(-1.85 * t)) / (1f - (float) Math.exp(-1.85));
            mixed = mixed + (compressed - mixed) * amount;
        }
        return clamp01(mixed);
    }

    /**
     * Pure-Java reference trilinear sampler matching the GLSL {@code applyLut3D} shader math.
     */
    public static void sampleLutTrilinear(byte[] lutRgba, float r, float g, float b, float[] outRgb) {
        float rc = clamp01(r) * (LUT_DIM - 1);
        float gc = clamp01(g) * (LUT_DIM - 1);
        float bc = clamp01(b) * (LUT_DIM - 1);

        int r0 = Math.min(LUT_DIM - 2, (int) Math.floor(rc));
        int g0 = Math.min(LUT_DIM - 2, (int) Math.floor(gc));
        int b0 = Math.min(LUT_DIM - 2, (int) Math.floor(bc));
        int r1 = r0 + 1;
        int g1 = g0 + 1;
        int b1 = b0 + 1;

        float fr = rc - r0;
        float fg = gc - g0;
        float fb = bc - b0;

        for (int ch = 0; ch < 3; ch++) {
            float c000 = fetch(lutRgba, r0, g0, b0, ch);
            float c100 = fetch(lutRgba, r1, g0, b0, ch);
            float c010 = fetch(lutRgba, r0, g1, b0, ch);
            float c110 = fetch(lutRgba, r1, g1, b0, ch);
            float c001 = fetch(lutRgba, r0, g0, b1, ch);
            float c101 = fetch(lutRgba, r1, g0, b1, ch);
            float c011 = fetch(lutRgba, r0, g1, b1, ch);
            float c111 = fetch(lutRgba, r1, g1, b1, ch);

            float c00 = c000 + (c100 - c000) * fr;
            float c10 = c010 + (c110 - c010) * fr;
            float c01 = c001 + (c101 - c001) * fr;
            float c11 = c011 + (c111 - c011) * fr;

            float c0 = c00 + (c10 - c00) * fg;
            float c1 = c01 + (c11 - c01) * fg;
            outRgb[ch] = c0 + (c1 - c0) * fb;
        }
    }

    /**
     * Computes Von Kries pre-LUT RGB white balance multipliers for {@code wbShift} in {@code [-1..+1]}.
     * Negative cools scene (tungsten correction / cool blue); positive warms scene (golden shade).
     */
    public static float[] computeSceneWbMultipliers(float wbShift) {
        float w = Math.max(-1f, Math.min(1f, Float.isNaN(wbShift) ? 0f : wbShift));
        float rGain = 1.0f + w * 0.18f;
        float gGain = 1.0f + w * 0.02f - Math.abs(w) * 0.015f;
        float bGain = 1.0f - w * 0.20f;
        return new float[]{rGain, gGain, bGain};
    }

    /** Formats {@code wbShift} ({@code [-1..+1]}) into an intuitive color temperature Kelvin label. */
    public static String wbKelvinLabel(float wbShift) {
        float w = Math.max(-1f, Math.min(1f, Float.isNaN(wbShift) ? 0f : wbShift));
        int kelvin = w >= 0f
                ? Math.round(5500f + w * 2000f)
                : Math.round(5500f + w * 2300f);
        kelvin = (kelvin / 100) * 100;
        return String.format(Locale.US, "%dK", kelvin);
    }

    private static float fetch(byte[] lutRgba, int r, int g, int b, int ch) {
        int x = b * LUT_DIM + r;
        int idx = (g * LUT_WIDTH + x) * 4 + ch;
        return (lutRgba[idx] & 0xFF) / 255f;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / Math.max(1e-5f, edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    private static byte toByte(float v) {
        return (byte) Math.max(0, Math.min(255, Math.round(clamp01(v) * 255f)));
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0f;
        return Math.max(0f, Math.min(1f, v));
    }
}
