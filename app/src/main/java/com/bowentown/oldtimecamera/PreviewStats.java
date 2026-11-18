package com.bowentown.oldtimecamera;

/**
 * Compact raw-frame telemetry extracted from a tiny 32x24 off-screen FBO pass
 * inside the active masked aspect frame. Pure Java so all downstream advisors
 * remain directly unit-testable on the JVM.
 */
public final class PreviewStats {

    public static final int LUMA_BIN_COUNT = 12;

    public final int width;
    public final int height;
    public final float meanLuma;
    public final float clippedHighlights;
    public final float crushedShadows;
    public final float centerLuma;
    public final float borderLuma;
    public final float avgR;
    public final float avgG;
    public final float avgB;
    public final float meanSaturation;
    public final float saliencyX;
    public final float saliencyY;
    public final float saliencyConfidence;
    public final float[] lumaBins;

    public PreviewStats(
            int width,
            int height,
            float meanLuma,
            float clippedHighlights,
            float crushedShadows,
            float centerLuma,
            float borderLuma,
            float avgR,
            float avgG,
            float avgB,
            float meanSaturation,
            float saliencyX,
            float saliencyY,
            float saliencyConfidence) {
        this(width, height, meanLuma, clippedHighlights, crushedShadows, centerLuma, borderLuma,
                avgR, avgG, avgB, meanSaturation, saliencyX, saliencyY, saliencyConfidence, null);
    }

    public PreviewStats(
            int width,
            int height,
            float meanLuma,
            float clippedHighlights,
            float crushedShadows,
            float centerLuma,
            float borderLuma,
            float avgR,
            float avgG,
            float avgB,
            float meanSaturation,
            float saliencyX,
            float saliencyY,
            float saliencyConfidence,
            float[] lumaBins) {
        this.width = width;
        this.height = height;
        this.meanLuma = clamp01(meanLuma);
        this.clippedHighlights = clamp01(clippedHighlights);
        this.crushedShadows = clamp01(crushedShadows);
        this.centerLuma = clamp01(centerLuma);
        this.borderLuma = clamp01(borderLuma);
        this.avgR = clamp01(avgR);
        this.avgG = clamp01(avgG);
        this.avgB = clamp01(avgB);
        this.meanSaturation = clamp01(meanSaturation);
        this.saliencyX = clamp01(saliencyX);
        this.saliencyY = clamp01(saliencyY);
        this.saliencyConfidence = clamp01(saliencyConfidence);
        float[] bins = new float[LUMA_BIN_COUNT];
        if (lumaBins != null) {
            int copy = Math.min(LUMA_BIN_COUNT, lumaBins.length);
            for (int i = 0; i < copy; i++) {
                bins[i] = clamp01(lumaBins[i]);
            }
        }
        this.lumaBins = bins;
    }

    /**
     * Parses raw RGBA8888 pixels read from OpenGL ({@code bottomUp == true} when coming directly
     * from {@code glReadPixels} where row 0 is screen bottom) into normalized active-frame metrics
     * with top-left origin coordinates.
     */
    public static PreviewStats fromRgbaBytes(byte[] rgba, int width, int height, boolean bottomUp) {
        if (rgba == null || width <= 0 || height <= 0 || rgba.length < width * height * 4) {
            return new PreviewStats(0, 0, 0.5f, 0f, 0f, 0.5f, 0.5f,
                    0.5f, 0.5f, 0.5f, 0f, 0.5f, 0.5f, 0f);
        }

        int n = width * height;
        float[] luma = new float[n];
        int[] binCounts = new int[LUMA_BIN_COUNT];
        double sumL = 0.0;
        double sumR = 0.0;
        double sumG = 0.0;
        double sumB = 0.0;
        double sumSat = 0.0;
        double sumCenterL = 0.0;
        double sumBorderL = 0.0;
        int centerCount = 0;
        int borderCount = 0;
        int clipCount = 0;
        int shadowCount = 0;
        int satCount = 0;

        int cLeft = width / 4;
        int cRight = (width * 3) / 4;
        int cTop = height / 4;
        int cBottom = (height * 3) / 4;

        for (int row = 0; row < height; row++) {
            int topY = bottomUp ? (height - 1 - row) : row;
            int srcRowOff = row * width * 4;
            int dstRowOff = topY * width;
            for (int x = 0; x < width; x++) {
                int idx = srcRowOff + x * 4;
                float r = (rgba[idx] & 0xFF) / 255f;
                float g = (rgba[idx + 1] & 0xFF) / 255f;
                float b = (rgba[idx + 2] & 0xFF) / 255f;
                float yVal = 0.2126f * r + 0.7152f * g + 0.0722f * b;

                luma[dstRowOff + x] = yVal;
                int bin = Math.min(LUMA_BIN_COUNT - 1, Math.max(0, (int) (yVal * LUMA_BIN_COUNT)));
                binCounts[bin]++;
                sumL += yVal;
                sumR += r;
                sumG += g;
                sumB += b;

                float maxC = Math.max(r, Math.max(g, b));
                float minC = Math.min(r, Math.min(g, b));
                if (maxC > 0.04f) {
                    sumSat += (maxC - minC) / maxC;
                    satCount++;
                }

                if (yVal >= 0.92f) clipCount++;
                if (yVal <= 0.06f) shadowCount++;

                if (x >= cLeft && x < cRight && topY >= cTop && topY < cBottom) {
                    sumCenterL += yVal;
                    centerCount++;
                } else {
                    sumBorderL += yVal;
                    borderCount++;
                }
            }
        }

        // Compute luminance-gradient saliency centroid in top-left normalized space.
        double weightSum = 0.0;
        double wxSum = 0.0;
        double wySum = 0.0;
        for (int y = 0; y < height; y++) {
            int ym = Math.max(0, y - 1);
            int yp = Math.min(height - 1, y + 1);
            float ny = (y + 0.5f) / height;
            for (int x = 0; x < width; x++) {
                int xm = Math.max(0, x - 1);
                int xp = Math.min(width - 1, x + 1);
                float nx = (x + 0.5f) / width;

                float gx = Math.abs(luma[y * width + xp] - luma[y * width + xm]);
                float gy = Math.abs(luma[yp * width + x] - luma[ym * width + x]);
                float grad = gx + gy;
                if (grad > 0.05f) {
                    float dx = nx - 0.5f;
                    float dy = ny - 0.5f;
                    float radialBias = 1.0f - 0.35f * Math.min(1f, (float) Math.hypot(dx, dy) * 1.414f);
                    double w = grad * grad * radialBias;
                    weightSum += w;
                    wxSum += nx * w;
                    wySum += ny * w;
                }
            }
        }

        float salX = 0.5f;
        float salY = 0.5f;
        float salConf = 0f;
        if (weightSum > 1e-4) {
            salX = (float) (wxSum / weightSum);
            salY = (float) (wySum / weightSum);
            salConf = clamp01((float) (weightSum / (n * 0.025)));
        }

        float meanL = (float) (sumL / n);
        float centerL = centerCount > 0 ? (float) (sumCenterL / centerCount) : meanL;
        float borderL = borderCount > 0 ? (float) (sumBorderL / borderCount) : meanL;
        float[] bins = new float[LUMA_BIN_COUNT];
        for (int i = 0; i < LUMA_BIN_COUNT; i++) {
            bins[i] = (float) binCounts[i] / n;
        }

        return new PreviewStats(
                width,
                height,
                meanL,
                (float) clipCount / n,
                (float) shadowCount / n,
                centerL,
                borderL,
                (float) (sumR / n),
                (float) (sumG / n),
                (float) (sumB / n),
                // Average only over pixels that actually carry chroma; dividing by the full
                // pixel count biased dark/night frames towards "desaturated".
                satCount > 0 ? (float) (sumSat / satCount) : 0f,
                salX,
                salY,
                salConf,
                bins
        );
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0f;
        return Math.max(0f, Math.min(1f, v));
    }
}
