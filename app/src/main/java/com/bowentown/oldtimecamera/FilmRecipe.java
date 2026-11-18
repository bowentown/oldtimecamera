package com.bowentown.oldtimecamera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java non-destructive film tuning parameters ("Film Recipe") applied on top of any
 * {@link FilmPreset} before both preview and full-resolution photo rendering, ensuring
 * 100% WYSIWYG output and custom recipe persistence.
 */
public final class FilmRecipe {

    public static final float MIN_INTENSITY = 0.0f;
    public static final float MAX_INTENSITY = 1.0f;
    public static final float MIN_GRAIN_SCALE = 0.0f;
    public static final float MAX_GRAIN_SCALE = 2.0f;
    public static final float MIN_WARMTH = -1.0f;
    public static final float MAX_WARMTH = 1.0f;

    public static final FilmRecipe DEFAULT = new FilmRecipe(1.0f, 1.0f, 0.0f);

    public final float intensity;
    public final float grainScale;
    public final float warmth;

    public FilmRecipe(float intensity, float grainScale, float warmth) {
        this.intensity = clamp(intensity, MIN_INTENSITY, MAX_INTENSITY);
        this.grainScale = clamp(grainScale, MIN_GRAIN_SCALE, MAX_GRAIN_SCALE);
        this.warmth = clamp(warmth, MIN_WARMTH, MAX_WARMTH);
    }

    public boolean isDefault() {
        return Math.abs(intensity - 1f) < 0.01f
                && Math.abs(grainScale - 1f) < 0.01f
                && Math.abs(warmth) < 0.01f;
    }

    public FilmPreset apply(FilmPreset base) {
        return apply(base, null);
    }

    /**
     * Produces an adjusted {@link FilmPreset} ready for OpenGL uniforms.
     * Intensity linearly interpolates between neutral RAW film stock and {@code base},
     * grainScale multiplies grain cleanly, and warmth gently shifts R/B balance.
     */
    public FilmPreset apply(FilmPreset base, String customName) {
        if (base == null) return null;
        if (isDefault() && (customName == null || customName.isEmpty())) {
            return base;
        }
        float t = intensity;
        float[] mat = new float[9];
        // Column-major identity indices with warmth R/B scale on diagonal + slight warm bias.
        float[] id = {1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
        for (int i = 0; i < 9; i++) {
            mat[i] = id[i] + (base.colorMatrix[i] - id[i]) * t;
        }
        float w = warmth;
        // Shift red gain & blue gain oppositely in colorMatrix diagonal (indices 0, 4, 8).
        mat[0] = Math.max(0.75f, mat[0] + w * 0.09f);
        mat[4] = Math.max(0.75f, mat[4] + w * 0.015f);
        mat[8] = Math.max(0.75f, mat[8] - w * 0.10f);

        float[] off = {
                base.colorOffset[0] * t + w * 0.012f,
                base.colorOffset[1] * t + w * 0.002f,
                base.colorOffset[2] * t - w * 0.014f
        };

        float sat = 1f + (base.saturation - 1f) * t;
        float con = 1f + (base.contrast - 1f) * t;
        float bri = base.brightness * t;
        float fade = base.fade * t;
        float rawBaseGrain = 0.012f;
        float scaledBaseGrain = rawBaseGrain + (base.grain - rawBaseGrain) * t;
        float grain = clamp(scaledBaseGrain * grainScale, 0f, 0.25f);
        float vig = base.vignette * (0.35f + 0.65f * t);
        float leak = base.leak * t;
        float hal = base.halation * t;

        return FilmPreset.deriveColMajor(
                base, customName, mat, off, sat, con, bri, fade, grain, vig, leak, hal);
    }

    public String toExifTag() {
        return String.format(Locale.US, "int=%.2f|gs=%.2f|warm=%+.2f", intensity, grainScale, warmth);
    }

    /** Saved custom recipe bound to a base film stock ID. */
    public static final class SavedRecipe {
        public final String id;
        public final String name;
        public final String basePresetId;
        public final FilmRecipe recipe;

        public SavedRecipe(String id, String name, String basePresetId, FilmRecipe recipe) {
            this.id = id;
            this.name = sanitize(name);
            this.basePresetId = basePresetId;
            this.recipe = recipe != null ? recipe : DEFAULT;
        }
    }

    public static String encodeSavedList(List<SavedRecipe> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            SavedRecipe r = list.get(i);
            if (r == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(sanitize(r.id)).append('\t')
                    .append(sanitize(r.name)).append('\t')
                    .append(sanitize(r.basePresetId)).append('\t')
                    .append(String.format(Locale.US, "%.3f\t%.3f\t%.3f",
                            r.recipe.intensity, r.recipe.grainScale, r.recipe.warmth));
        }
        return sb.toString();
    }

    public static List<SavedRecipe> decodeSavedList(String raw) {
        List<SavedRecipe> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t", -1);
            if (parts.length < 6) continue;
            try {
                float intensity = Float.parseFloat(parts[3]);
                float grain = Float.parseFloat(parts[4]);
                float warmth = Float.parseFloat(parts[5]);
                if (!parts[0].isEmpty() && !parts[1].isEmpty() && !parts[2].isEmpty()) {
                    out.add(new SavedRecipe(parts[0], parts[1], parts[2],
                            new FilmRecipe(intensity, grain, warmth)));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').trim();
    }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
