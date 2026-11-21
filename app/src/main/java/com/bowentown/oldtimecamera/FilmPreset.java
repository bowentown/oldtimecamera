package com.bowentown.oldtimecamera;

/**
 * One classic film / retro camera look, combining a 3x3 base response, non-linear 3D HaldCLUT
 * chemical parameters (split-toning, foliage/sky/skin hue warps, filmic highlight roll-off),
 * and disposable-lens optical physics (chromatic aberration, corner softness, direct-flash pop).
 */
public final class FilmPreset {

    public final String id;
    public final String name;
    public final String isoTag;
    public final String note;
    public final String shortNote;
    public final int swatch;

    /** 3x3 colour transform in column-major order (ready for glUniformMatrix3fv). */
    public final float[] colorMatrix;
    public final float[] colorOffset;

    public final float saturation;
    public final float contrast;
    public final float brightness;
    public final float fade;
    public final float grain;
    public final float vignette;
    public final float leak;
    public final float halation;
    public final float[] leakColor;
    public final boolean grayscale;

    /** 3D LUT & optical lens characteristics. */
    public final float[] shadowTint;
    public final float[] highlightTint;
    public final float highlightRolloff;
    public final float foliageShift;
    public final float skyShift;
    public final float skinWarmth;
    public final float lutStrength;
    public final float chromaAberration;
    public final float cornerSoftness;
    public final float flashPop;

    public final int paperColor;
    public final boolean suggestStamp;
    public final boolean suggestFrame;

    private FilmPreset(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.isoTag = b.isoTag;
        this.note = b.note;
        this.shortNote = b.shortNote.isEmpty() ? b.note : b.shortNote;
        this.swatch = b.swatch;
        this.colorMatrix = toColumnMajor(b.matrix);
        this.colorOffset = b.offset;
        this.saturation = b.saturation;
        this.contrast = b.contrast;
        this.brightness = b.brightness;
        this.fade = b.fade;
        this.grain = b.grain;
        this.vignette = b.vignette;
        this.leak = b.leak;
        this.halation = b.halation;
        this.leakColor = b.leakColor;
        this.grayscale = b.grayscale;
        this.shadowTint = b.shadowTint;
        this.highlightTint = b.highlightTint;
        this.highlightRolloff = b.highlightRolloff;
        this.foliageShift = b.foliageShift;
        this.skyShift = b.skyShift;
        this.skinWarmth = b.skinWarmth;
        this.lutStrength = b.lutStrength;
        this.chromaAberration = b.chromaAberration;
        this.cornerSoftness = b.cornerSoftness;
        this.flashPop = b.flashPop;
        this.paperColor = b.paperColor;
        this.suggestStamp = b.suggestStamp;
        this.suggestFrame = b.suggestFrame;
    }

    private FilmPreset(
            String id, String name, String isoTag, String note, String shortNote, int swatch,
            float[] colMajorMatrix, float[] offset, float saturation, float contrast,
            float brightness, float fade, float grain, float vignette, float leak,
            float halation, float[] leakColor, boolean grayscale,
            float[] shadowTint, float[] highlightTint, float highlightRolloff,
            float foliageShift, float skyShift, float skinWarmth, float lutStrength,
            float chromaAberration, float cornerSoftness, float flashPop,
            int paperColor, boolean suggestStamp, boolean suggestFrame) {
        this.id = id;
        this.name = name;
        this.isoTag = isoTag;
        this.note = note;
        this.shortNote = shortNote;
        this.swatch = swatch;
        this.colorMatrix = colMajorMatrix;
        this.colorOffset = offset;
        this.saturation = saturation;
        this.contrast = contrast;
        this.brightness = brightness;
        this.fade = fade;
        this.grain = grain;
        this.vignette = vignette;
        this.leak = leak;
        this.halation = halation;
        this.leakColor = leakColor;
        this.grayscale = grayscale;
        this.shadowTint = shadowTint;
        this.highlightTint = highlightTint;
        this.highlightRolloff = highlightRolloff;
        this.foliageShift = foliageShift;
        this.skyShift = skyShift;
        this.skinWarmth = skinWarmth;
        this.lutStrength = lutStrength;
        this.chromaAberration = chromaAberration;
        this.cornerSoftness = cornerSoftness;
        this.flashPop = flashPop;
        this.paperColor = paperColor;
        this.suggestStamp = suggestStamp;
        this.suggestFrame = suggestFrame;
    }

    /** Creates a derived preset preserving pre-transposed column-major color matrix order. */
    public static FilmPreset deriveColMajor(
            FilmPreset base, String customName,
            float[] colMajorMatrix, float[] offset,
            float saturation, float contrast, float brightness,
            float fade, float grain, float vignette, float leak, float halation) {
        return deriveColMajor(base, customName, colMajorMatrix, offset, saturation, contrast,
                brightness, fade, grain, vignette, leak, halation, 1f);
    }

    /** Creates a derived preset scaling 3D LUT and optical lens traits cleanly by {@code effectScale}. */
    public static FilmPreset deriveColMajor(
            FilmPreset base, String customName,
            float[] colMajorMatrix, float[] offset,
            float saturation, float contrast, float brightness,
            float fade, float grain, float vignette, float leak, float halation,
            float effectScale) {
        float s = Math.max(0f, Math.min(1f, effectScale));
        return new FilmPreset(
                base.id,
                (customName != null && !customName.isEmpty()) ? customName : base.name,
                base.isoTag, base.note, base.shortNote, base.swatch,
                colMajorMatrix, offset, saturation, contrast, brightness,
                fade, grain, vignette, leak, halation,
                base.leakColor, base.grayscale,
                base.shadowTint, base.highlightTint, base.highlightRolloff,
                base.foliageShift, base.skyShift, base.skinWarmth,
                base.lutStrength * s,
                base.chromaAberration * s,
                base.cornerSoftness * s,
                base.flashPop * s,
                base.paperColor, base.suggestStamp, base.suggestFrame);
    }

    /** Accepts a row-major 3x3 matrix and returns column-major for GLES. */
    private static float[] toColumnMajor(float[] m) {
        return new float[]{
                m[0], m[3], m[6],
                m[1], m[4], m[7],
                m[2], m[5], m[8]
        };
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        String id = "stock";
        String name = "FILM";
        String isoTag = "400";
        String note = "";
        String shortNote = "";
        int swatch = 0xFF8C7A5C;
        float[] matrix = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        float[] offset = {0, 0, 0};
        float saturation = 1f;
        float contrast = 1f;
        float brightness = 0f;
        float fade = 0f;
        float grain = 0.04f;
        float vignette = 0.2f;
        float leak = 0f;
        float halation = 0f;
        float[] leakColor = {1f, 0.42f, 0.16f};
        boolean grayscale = false;
        float[] shadowTint = {0f, 0f, 0f};
        float[] highlightTint = {0f, 0f, 0f};
        float highlightRolloff = 0.4f;
        float foliageShift = 0f;
        float skyShift = 0f;
        float skinWarmth = 0f;
        float lutStrength = 1f;
        float chromaAberration = 0f;
        float cornerSoftness = 0f;
        float flashPop = 0f;
        int paperColor = 0xFFF1E9D8;
        boolean suggestStamp = true;
        boolean suggestFrame = true;

        public Builder id(String v) { id = v; return this; }
        public Builder name(String v) { name = v; return this; }
        public Builder isoTag(String v) { isoTag = v; return this; }
        public Builder note(String v) { note = v; return this; }
        public Builder shortNote(String v) { shortNote = v; return this; }
        public Builder swatch(int v) { swatch = v; return this; }
        /** row-major 3x3 */
        public Builder matrix(float... v) { matrix = v; return this; }
        public Builder offset(float... v) { offset = v; return this; }
        public Builder saturation(float v) { saturation = v; return this; }
        public Builder contrast(float v) { contrast = v; return this; }
        public Builder brightness(float v) { brightness = v; return this; }
        public Builder fade(float v) { fade = v; return this; }
        public Builder grain(float v) { grain = v; return this; }
        public Builder vignette(float v) { vignette = v; return this; }
        public Builder leak(float v) { leak = v; return this; }
        public Builder halation(float v) { halation = v; return this; }
        public Builder leakColor(float... v) { leakColor = v; return this; }
        public Builder grayscale(boolean v) { grayscale = v; return this; }
        public Builder shadowTint(float r, float g, float b) { shadowTint = new float[]{r, g, b}; return this; }
        public Builder highlightTint(float r, float g, float b) { highlightTint = new float[]{r, g, b}; return this; }
        public Builder highlightRolloff(float v) { highlightRolloff = v; return this; }
        public Builder foliageShift(float v) { foliageShift = v; return this; }
        public Builder skyShift(float v) { skyShift = v; return this; }
        public Builder skinWarmth(float v) { skinWarmth = v; return this; }
        public Builder lutStrength(float v) { lutStrength = v; return this; }
        public Builder chromaAberration(float v) { chromaAberration = v; return this; }
        public Builder cornerSoftness(float v) { cornerSoftness = v; return this; }
        public Builder flashPop(float v) { flashPop = v; return this; }
        public Builder paperColor(int v) { paperColor = v; return this; }
        public Builder suggestStamp(boolean v) { suggestStamp = v; return this; }
        public Builder suggestFrame(boolean v) { suggestFrame = v; return this; }

        public FilmPreset build() { return new FilmPreset(this); }
    }
}
