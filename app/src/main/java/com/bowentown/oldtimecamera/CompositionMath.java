package com.bowentown.oldtimecamera;

/**
 * Pure-Java composition geometry, golden spiral subdivisions, sensor-to-viewfinder coordinate
 * transforms, and snap hysteresis for beginner framing assistance.
 */
public final class CompositionMath {

    public enum GridMode {
        OFF,
        THIRDS,
        GOLDEN_SPIRAL,
        CROSSHAIR;

        public GridMode next() {
            GridMode[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }

        public static GridMode fromName(String name) {
            if (name != null) {
                for (GridMode m : values()) {
                    if (m.name().equals(name)) return m;
                }
            }
            return OFF;
        }
    }

    /** Minimalist vector framing & pose line-art templates inside the optical viewfinder. */
    public enum GuideTemplate {
        NONE,
        PORTRAIT_THIRDS,
        BACK_SILHOUETTE,
        LOW_ANGLE,
        SYMMETRY,
        OVERHEAD_STILL,
        GOLDEN_DIAGONAL;

        public GuideTemplate next() {
            GuideTemplate[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }

        public static GuideTemplate fromName(String name) {
            if (name != null) {
                for (GuideTemplate t : values()) {
                    if (t.name().equals(name)) return t;
                }
            }
            return NONE;
        }
    }

    /** One normalized [0..1] geometric primitive for pose/framing guide templates. */
    public static final class GuideShape {
        public enum Kind { LINE, DASHED_LINE, OVAL, ARC }

        public final Kind kind;
        public final float a, b, c, d;
        public final float startDeg, sweepDeg;

        private GuideShape(Kind kind, float a, float b, float c, float d,
                           float startDeg, float sweepDeg) {
            this.kind = kind;
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.startDeg = startDeg;
            this.sweepDeg = sweepDeg;
        }

        public static GuideShape line(float x1, float y1, float x2, float y2) {
            return new GuideShape(Kind.LINE, x1, y1, x2, y2, 0f, 0f);
        }

        public static GuideShape dashed(float x1, float y1, float x2, float y2) {
            return new GuideShape(Kind.DASHED_LINE, x1, y1, x2, y2, 0f, 0f);
        }

        public static GuideShape oval(float left, float top, float right, float bottom) {
            return new GuideShape(Kind.OVAL, left, top, right, bottom, 0f, 0f);
        }

        public static GuideShape arc(float left, float top, float right, float bottom,
                                     float startDeg, float sweepDeg) {
            return new GuideShape(Kind.ARC, left, top, right, bottom, startDeg, sweepDeg);
        }
    }

    /** Real-time spatial guidance from subject anchor toward the best composition anchor. */
    public static final class FramingAdvice {
        public final boolean hasSubject;
        public final boolean locked;
        public final float subjectX;
        public final float subjectY;
        public final float targetX;
        public final float targetY;
        public final float distance;
        public final boolean showArrow;
        public final boolean headroomTight;

        FramingAdvice(boolean hasSubject, boolean locked,
                      float subjectX, float subjectY,
                      float targetX, float targetY,
                      float distance, boolean showArrow, boolean headroomTight) {
            this.hasSubject = hasSubject;
            this.locked = locked;
            this.subjectX = subjectX;
            this.subjectY = subjectY;
            this.targetX = targetX;
            this.targetY = targetY;
            this.distance = distance;
            this.showArrow = showArrow;
            this.headroomTight = headroomTight;
        }

        public static final FramingAdvice EMPTY =
                new FramingAdvice(false, false, 0.5f, 0.5f, 0.5f, 0.5f, 0f, false, false);
    }

    public static final float INV_PHI = 0.61803398875f;
    public static final float REM_PHI = 1f - INV_PHI;

    public static final float DEFAULT_ENTER_RADIUS = 0.055f;
    public static final float DEFAULT_EXIT_RADIUS = 0.085f;

    /**
     * One golden-spiral step inside normalized [0..1, 0..1] active frame coordinates.
     * Coordinates use top-left origin matching Android {@link android.graphics.Canvas}.
     */
    public static final class SpiralArc {
        public final float ovalLeft;
        public final float ovalTop;
        public final float ovalRight;
        public final float ovalBottom;
        public final float startAngleDeg;
        public final float sweepAngleDeg;
        public final float startX;
        public final float startY;
        public final float endX;
        public final float endY;
        public final float divX1;
        public final float divY1;
        public final float divX2;
        public final float divY2;

        SpiralArc(
                float ovalLeft, float ovalTop, float ovalRight, float ovalBottom,
                float startAngleDeg, float sweepAngleDeg,
                float startX, float startY, float endX, float endY,
                float divX1, float divY1, float divX2, float divY2) {
            this.ovalLeft = ovalLeft;
            this.ovalTop = ovalTop;
            this.ovalRight = ovalRight;
            this.ovalBottom = ovalBottom;
            this.startAngleDeg = startAngleDeg;
            this.sweepAngleDeg = sweepAngleDeg;
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.divX1 = divX1;
            this.divY1 = divY1;
            this.divX2 = divX2;
            this.divY2 = divY2;
        }
    }

    public static final class SnapState {
        private int lockedPointIndex = -1;
        private long lastHapticMs = -10_000L;

        public int getLockedPointIndex() {
            return lockedPointIndex;
        }

        public void reset() {
            lockedPointIndex = -1;
        }

        /**
         * Updates alignment lock with enter/exit hysteresis so hovering near the threshold does not
         * flicker or buzz repeatedly.
         *
         * @return {@code true} if a fresh haptic tick should fire now
         */
        public boolean update(float subX, float subY, float[][] points, long nowMs, long cooldownMs) {
            if (points == null || points.length == 0 || Float.isNaN(subX) || Float.isNaN(subY)) {
                lockedPointIndex = -1;
                return false;
            }

            if (lockedPointIndex >= 0 && lockedPointIndex < points.length) {
                float dLocked = dist(subX, subY, points[lockedPointIndex][0], points[lockedPointIndex][1]);
                if (dLocked <= DEFAULT_EXIT_RADIUS) {
                    return false;
                }
            }

            int bestIdx = -1;
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < points.length; i++) {
                float d = dist(subX, subY, points[i][0], points[i][1]);
                if (d < bestDist) {
                    bestDist = d;
                    bestIdx = i;
                }
            }

            if (bestIdx >= 0 && bestDist <= DEFAULT_ENTER_RADIUS) {
                boolean newlyEntered = (lockedPointIndex != bestIdx);
                lockedPointIndex = bestIdx;
                if (newlyEntered && nowMs - lastHapticMs >= cooldownMs) {
                    lastHapticMs = nowMs;
                    return true;
                }
                return false;
            }

            lockedPointIndex = -1;
            return false;
        }
    }

    private CompositionMath() {}

    /**
     * Generates {@code depth} continuous C1 quarter-ellipse golden-spiral segments in normalized
     * [0..1, 0..1] coordinates. {@code rotSteps} in [0..3] rotates start side by 90-degree increments.
     */
    public static SpiralArc[] buildGoldenSpiral(int rotSteps, int depth) {
        int steps = Math.max(1, depth);
        int base = ((rotSteps % 4) + 4) % 4;
        SpiralArc[] out = new SpiralArc[steps];

        float l = 0f;
        float t = 0f;
        float r = 1f;
        float b = 1f;

        for (int k = 0; k < steps; k++) {
            int dir = (base + k) % 4;
            float w = r - l;
            float h = b - t;
            SpiralArc arc;
            if (dir == 0) {
                // Cut left INV_PHI*w, spiral runs from (l, b) to (splitX, t)
                float sx = l + w * INV_PHI;
                float rx = sx - l;
                float ry = h;
                arc = new SpiralArc(
                        sx - rx, b - ry, sx + rx, b + ry,
                        180f, 90f,
                        l, b, sx, t,
                        sx, t, sx, b);
                l = sx;
            } else if (dir == 1) {
                // Cut top INV_PHI*h, spiral runs from (l, t) to (r, splitY)
                float sy = t + h * INV_PHI;
                float rx = w;
                float ry = sy - t;
                arc = new SpiralArc(
                        l - rx, sy - ry, l + rx, sy + ry,
                        270f, 90f,
                        l, t, r, sy,
                        l, sy, r, sy);
                t = sy;
            } else if (dir == 2) {
                // Cut right INV_PHI*w, spiral runs from (r, t) to (splitX, b)
                float sx = l + w * REM_PHI;
                float rx = r - sx;
                float ry = h;
                arc = new SpiralArc(
                        sx - rx, t - ry, sx + rx, t + ry,
                        0f, 90f,
                        r, t, sx, b,
                        sx, t, sx, b);
                r = sx;
            } else {
                // Cut bottom INV_PHI*h, spiral runs from (r, b) to (l, splitY)
                float sy = t + h * REM_PHI;
                float rx = w;
                float ry = b - sy;
                arc = new SpiralArc(
                        r - rx, sy - ry, r + rx, sy + ry,
                        90f, 90f,
                        r, b, l, sy,
                        l, sy, r, sy);
                b = sy;
            }
            out[k] = arc;
        }
        return out;
    }

    /** Computes the converging focal eye of the golden spiral for {@code rotSteps}. */
    public static float[] goldenEye(int rotSteps) {
        SpiralArc[] arcs = buildGoldenSpiral(rotSteps, 10);
        SpiralArc last = arcs[arcs.length - 1];
        return new float[]{last.endX, last.endY};
    }

    /** Returns normalized [0..1, 0..1] power points for the active grid mode (empty when OFF). */
    public static float[][] powerPoints(GridMode mode, int goldenRotSteps) {
        if (mode == null || mode == GridMode.OFF) return new float[0][];
        switch (mode) {
            case THIRDS:
                return new float[][]{
                        {1f / 3f, 1f / 3f},
                        {2f / 3f, 1f / 3f},
                        {1f / 3f, 2f / 3f},
                        {2f / 3f, 2f / 3f}
                };
            case GOLDEN_SPIRAL:
                float[] eye = goldenEye(goldenRotSteps);
                return new float[][]{
                        eye,
                        {REM_PHI, REM_PHI},
                        {INV_PHI, REM_PHI},
                        {REM_PHI, INV_PHI},
                        {INV_PHI, INV_PHI}
                };
            case CROSSHAIR:
                return new float[][]{{0.5f, 0.5f}};
            default:
                return new float[0][];
        }
    }

    /**
     * Maps normalized Camera2 sensor face coordinates (in [0..1, 0..1] relative to the active
     * scalar crop region) into normalized [0..1, 0..1] coordinates inside the masked active
     * aspect frame. Returns {@code null} if outside the visible crop or active frame curtains.
     *
     * @param relativeCameraRotationDegrees matches {@code DisplayOrientedMeteringPointFactory}'s
     *                                      relative orientation ({@code 0, 90, 180, 270})
     */
    public static float[] mapSensorPointToActiveFrame(
            float sensorNormX,
            float sensorNormY,
            int relativeCameraRotationDegrees,
            boolean facingFront,
            boolean frontMirrorEnabled,
            float frameScaleX,
            float frameScaleY) {
        return mapSensorPointToActiveFrame(
                sensorNormX,
                sensorNormY,
                0f, 0f, 1f, 1f,
                relativeCameraRotationDegrees,
                facingFront,
                0,
                facingFront && !frontMirrorEnabled,
                frameScaleX,
                frameScaleY);
    }

    /**
     * Full geometric inverse of {@link CameraTransformMath#computePreviewTexMatrix} from
     * normalized top-left buffer/sensor coordinates into masked active-frame coordinates
     * {@code [0..1, 0..1]}. Handles sub-buffer {@code cropRect}, sensor-to-upright rotation,
     * stream-level front mirroring, screen-space manual calibration rotation, and post-rotation
     * screen mirror override ({@code facingFront && !frontMirrorEnabled}).
     */
    public static float[] mapSensorPointToActiveFrame(
            float sensorNormX,
            float sensorNormY,
            float cropLeftNorm,
            float cropTopNorm,
            float cropRightNorm,
            float cropBottomNorm,
            int relativeCameraRotationDegrees,
            boolean basePreviewMirrored,
            int manualRotationDegrees,
            boolean extraMirrorFlip,
            float frameScaleX,
            float frameScaleY) {

        float cl = clamp01(cropLeftNorm);
        float ct = clamp01(cropTopNorm);
        float cr = clamp01(cropRightNorm);
        float cb = clamp01(cropBottomNorm);
        float cw = cr - cl;
        float ch = cb - ct;

        float x;
        float y;
        if (cw > 0.0001f && ch > 0.0001f) {
            x = (sensorNormX - cl) / cw;
            y = (sensorNormY - ct) / ch;
        } else {
            x = sensorNormX;
            y = sensorNormY;
        }
        if (x < -0.02f || x > 1.02f || y < -0.02f || y > 1.02f) {
            return null;
        }
        x = clamp01(x);
        y = clamp01(y);

        // Undo CameraTransformMath's upright transform: rotate CW by relativeCameraRotationDegrees,
        // apply stream front mirroring, then apply screen-space manual calibration and extra flip.
        float[] upright = rotateCwNorm(x, y, relativeCameraRotationDegrees);
        float ux = upright[0];
        float uy = upright[1];
        if (basePreviewMirrored) {
            ux = 1f - ux;
        }

        int manual = CameraTransformMath.normalizeDegrees(manualRotationDegrees);
        if (manual != 0) {
            float[] rotatedManual = rotateCwNorm(ux, uy, manual);
            ux = rotatedManual[0];
            uy = rotatedManual[1];
        }
        if (extraMirrorFlip) {
            ux = 1f - ux;
        }

        float sx = Math.max(0.05f, frameScaleX);
        float sy = Math.max(0.05f, frameScaleY);
        float ax = (ux - 0.5f) / sx + 0.5f;
        float ay = (uy - 0.5f) / sy + 0.5f;
        if (ax < -0.02f || ax > 1.02f || ay < -0.02f || ay > 1.02f) {
            return null;
        }
        return new float[]{clamp01(ax), clamp01(ay)};
    }

    private static float[] rotateCwNorm(float x, float y, int degreesCw) {
        int rot = CameraTransformMath.normalizeDegrees(degreesCw);
        switch (rot) {
            case 90:
                return new float[]{1f - y, x};
            case 180:
                return new float[]{1f - x, 1f - y};
            case 270:
                return new float[]{y, 1f - x};
            default:
                return new float[]{x, y};
        }
    }

    /** Converts a pixel point in viewfinder space into active-frame normalized coords. */
    public static float[] mapViewPointToActiveFrame(
            float vx, float vy, float rectLeft, float rectTop, float rectWidth, float rectHeight) {
        if (rectWidth <= 1f || rectHeight <= 1f) return null;
        float ax = (vx - rectLeft) / rectWidth;
        float ay = (vy - rectTop) / rectHeight;
        if (ax < 0f || ax > 1f || ay < 0f || ay > 1f) return null;
        return new float[]{ax, ay};
    }

    /** Evaluates real-time subject offset toward the nearest composition power point and headroom. */
    public static FramingAdvice evaluateFraming(
            float subX,
            float subY,
            boolean hasFace,
            float faceTopNorm,
            GridMode gridMode,
            int goldenRotation,
            int lockedPointIndex) {
        if (Float.isNaN(subX) || Float.isNaN(subY)) {
            return FramingAdvice.EMPTY;
        }
        float sx = clamp01(subX);
        float sy = clamp01(subY);
        boolean headroomTight = hasFace && !Float.isNaN(faceTopNorm) && faceTopNorm < 0.085f;

        GridMode evalMode = (gridMode != null && gridMode != GridMode.OFF)
                ? gridMode : GridMode.THIRDS;
        float[][] pts = powerPoints(evalMode, goldenRotation);
        if (pts.length == 0) {
            return new FramingAdvice(true, false, sx, sy, 0.5f, 0.5f, 0f, false, headroomTight);
        }

        int bestIdx = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < pts.length; i++) {
            float d = dist(sx, sy, pts[i][0], pts[i][1]);
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }
        if (lockedPointIndex >= 0 && lockedPointIndex < pts.length) {
            float lockedD = dist(sx, sy, pts[lockedPointIndex][0], pts[lockedPointIndex][1]);
            if (lockedD <= DEFAULT_EXIT_RADIUS) {
                return new FramingAdvice(true, true, sx, sy,
                        pts[lockedPointIndex][0], pts[lockedPointIndex][1],
                        lockedD, false, headroomTight);
            }
        }
        boolean locked = bestDist <= DEFAULT_ENTER_RADIUS;
        boolean showArrow = !locked && bestDist > DEFAULT_ENTER_RADIUS * 1.25f;
        return new FramingAdvice(true, locked, sx, sy,
                pts[bestIdx][0], pts[bestIdx][1], bestDist, showArrow, headroomTight);
    }

    /** Builds normalized [0..1] vector line primitives for the chosen framing/pose template. */
    public static GuideShape[] buildTemplateShapes(GuideTemplate template) {
        if (template == null || template == GuideTemplate.NONE) {
            return new GuideShape[0];
        }
        switch (template) {
            case PORTRAIT_THIRDS:
                return new GuideShape[]{
                        GuideShape.dashed(0.08f, 0.13f, 0.92f, 0.13f),
                        GuideShape.dashed(0.14f, 0.34f, 0.86f, 0.34f),
                        GuideShape.oval(0.535f, 0.225f, 0.705f, 0.455f),
                        GuideShape.arc(0.41f, 0.48f, 0.83f, 0.96f, 195f, 150f),
                        GuideShape.line(0.47f, 0.34f, 0.34f, 0.34f),
                        GuideShape.line(0.37f, 0.31f, 0.34f, 0.34f),
                        GuideShape.line(0.37f, 0.37f, 0.34f, 0.34f)
                };
            case BACK_SILHOUETTE:
                return new GuideShape[]{
                        GuideShape.dashed(0.06f, 0.72f, 0.94f, 0.72f),
                        GuideShape.oval(0.29f, 0.51f, 0.39f, 0.63f),
                        GuideShape.arc(0.24f, 0.63f, 0.44f, 0.88f, 185f, 170f),
                        GuideShape.dashed(0.58f, 0.20f, 0.78f, 0.20f)
                };
            case LOW_ANGLE:
                return new GuideShape[]{
                        GuideShape.dashed(0.06f, 0.78f, 0.94f, 0.78f),
                        GuideShape.line(0.10f, 0.96f, 0.45f, 0.24f),
                        GuideShape.line(0.90f, 0.96f, 0.55f, 0.24f),
                        GuideShape.oval(0.44f, 0.18f, 0.56f, 0.30f)
                };
            case SYMMETRY:
                return new GuideShape[]{
                        GuideShape.dashed(0.50f, 0.06f, 0.50f, 0.94f),
                        GuideShape.dashed(0.08f, 0.50f, 0.92f, 0.50f),
                        GuideShape.line(0.24f, 0.16f, 0.76f, 0.16f),
                        GuideShape.line(0.76f, 0.16f, 0.76f, 0.84f),
                        GuideShape.line(0.76f, 0.84f, 0.24f, 0.84f),
                        GuideShape.line(0.24f, 0.84f, 0.24f, 0.16f)
                };
            case OVERHEAD_STILL:
                return new GuideShape[]{
                        GuideShape.oval(0.20f, 0.24f, 0.50f, 0.54f),
                        GuideShape.oval(0.25f, 0.29f, 0.45f, 0.49f),
                        GuideShape.line(0.55f, 0.48f, 0.82f, 0.48f),
                        GuideShape.line(0.82f, 0.48f, 0.82f, 0.78f),
                        GuideShape.line(0.82f, 0.78f, 0.55f, 0.78f),
                        GuideShape.line(0.55f, 0.78f, 0.55f, 0.48f),
                        GuideShape.dashed(0.16f, 0.82f, 0.84f, 0.18f)
                };
            case GOLDEN_DIAGONAL:
                return new GuideShape[]{
                        GuideShape.line(0.06f, 0.94f, 0.94f, 0.06f),
                        GuideShape.dashed(0.06f, 0.06f, 0.60f, 0.60f),
                        GuideShape.dashed(0.40f, 0.40f, 0.94f, 0.94f),
                        GuideShape.oval(0.29f, 0.29f, 0.38f, 0.38f)
                };
            default:
                return new GuideShape[0];
        }
    }

    public static float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
