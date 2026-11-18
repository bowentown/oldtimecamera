package com.bowentown.oldtimecamera;

/**
 * Pure-Java film rendering geometry shared by the preview and the off-screen print
 * pass, so both can be asserted in JVM unit tests.
 */
public final class FilmRenderMath {

    /**
     * Reference frame short edge (px) at which one grain cell is exactly one pixel.
     * Grain is defined relative to the frame, not to the render target, otherwise a
     * 2560px print would carry visibly different grain from a 1080px viewfinder.
     */
    public static final float GRAIN_REFERENCE_PX = 1180f;

    private FilmRenderMath() {}

    /**
     * Grain cell size in device pixels for a render target whose short edge is
     * {@code shortEdge}. Never below one pixel, so low-resolution previews do not
     * alias into sub-pixel shimmer.
     */
    public static float grainCellSize(int shortEdge) {
        return Math.max(shortEdge / GRAIN_REFERENCE_PX, 1f);
    }

    /** Grain cell size expressed as a fraction of the frame short edge. */
    public static float grainRelativeSize(int shortEdge) {
        if (shortEdge <= 0) return 1f / GRAIN_REFERENCE_PX;
        return grainCellSize(shortEdge) / shortEdge;
    }

    /**
     * Frames of different sizes can only match to within one device pixel, so the
     * preview and print grain agree to roughly 10% for 1080p..2560p targets.
     */
    public static boolean grainScalesAgree(int shortEdgeA, int shortEdgeB) {
        float a = grainRelativeSize(shortEdgeA);
        float b = grainRelativeSize(shortEdgeB);
        if (a <= 0f || b <= 0f) return true;
        return Math.abs(a - b) / Math.max(a, b) <= 0.15f;
    }

    /**
     * Normalized UV step for the 8-tap Black Pro-Mist highlight bloom kernel, scaled off the
     * target short edge so preview and 2560px exports produce matched halation softness.
     */
    public static float[] mistTexelStep(int width, int height) {
        int shortEdge = Math.max(1, Math.min(Math.max(1, width), Math.max(1, height)));
        float radiusPx = Math.max(2.0f, shortEdge * 0.0085f);
        float dx = width > 0 ? radiusPx / width : 0.004f;
        float dy = height > 0 ? radiusPx / height : 0.004f;
        return new float[]{dx, dy};
    }

    /**
     * Converts raw preview telemetry luminance into a classic analog light-meter needle
     * deflection in {@code [-2.0 .. +2.0]} stops, where {@code 0.0} represents balanced
     * midtone metering ({@code luma ~= 0.46}). Highlight clipping adds positive bias.
     */
    public static float meterNeedleStops(PreviewStats stats) {
        if (stats == null || stats.width <= 0 || stats.height <= 0) return 0f;
        float target = 0.46f;
        float diff = stats.meanLuma - target;
        float stops = diff * 5.2f;
        if (stats.clippedHighlights > 0.08f) {
            stops += (stats.clippedHighlights - 0.08f) * 3.5f;
        }
        if (stats.crushedShadows > 0.36f && diff < 0f) {
            stops -= (stats.crushedShadows - 0.36f) * 1.8f;
        }
        return Math.max(-2.0f, Math.min(2.0f, stops));
    }
}
