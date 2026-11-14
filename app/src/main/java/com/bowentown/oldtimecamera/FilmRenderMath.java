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
}
