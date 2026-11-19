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

    /** Bounding box mapped into normalized [0..1, 0..1] active-frame coordinates. */
    public static final class ActiveBox {
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final float rawWidth;
        public final float rawHeight;
        public final boolean clipped;

        public ActiveBox(float left, float top, float right, float bottom,
                         float rawWidth, float rawHeight, boolean clipped) {
            this.left = clamp01(left);
            this.top = clamp01(top);
            this.right = clamp01(right);
            this.bottom = clamp01(bottom);
            this.rawWidth = Math.max(0f, rawWidth);
            this.rawHeight = Math.max(0f, rawHeight);
            this.clipped = clipped;
        }

        public float centerX() {
            return (left + right) * 0.5f;
        }

        public float centerY() {
            return (top + bottom) * 0.5f;
        }
    }

    public enum PanX { NONE, LEFT, RIGHT }
    public enum PanY { NONE, UP, DOWN }
    public enum DepthMove { NONE, CLOSER, BACK }

    /** Minimalist physical camera movement cue ready for edge-symbol rendering. */
    public static final class MoveCue {
        public static final MoveCue EMPTY = new MoveCue(PanX.NONE, PanY.NONE, DepthMove.NONE, 0f, 0f);

        public final PanX panX;
        public final PanY panY;
        public final DepthMove depth;
        public final float strengthX;
        public final float strengthY;

        public MoveCue(PanX panX, PanY panY, DepthMove depth, float strengthX, float strengthY) {
            this.panX = panX != null ? panX : PanX.NONE;
            this.panY = panY != null ? panY : PanY.NONE;
            this.depth = depth != null ? depth : DepthMove.NONE;
            this.strengthX = clamp01(strengthX);
            this.strengthY = clamp01(strengthY);
        }

        public boolean isEmpty() {
            return panX == PanX.NONE && panY == PanY.NONE && depth == DepthMove.NONE;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MoveCue)) return false;
            MoveCue other = (MoveCue) o;
            return panX == other.panX && panY == other.panY && depth == other.depth;
        }

        @Override
        public int hashCode() {
            return (panX.ordinal() * 31 + panY.ordinal()) * 31 + depth.ordinal();
        }
    }

    /**
     * Stateful anti-flicker evaluator turning face bounds, tap focus, or preview saliency
     * into real-time physical camera movement suggestions (pan left/right/up/down & step closer/back).
     */
    public static final class MoveGuideState {
        private int lastSourceTag = 0; // 0=none, 1=face, 2=tap, 3=saliency
        private boolean hasSmoothedPos = false;
        private float smoothX = 0.5f;
        private float smoothY = 0.5f;
        private float smoothFaceH = 0f;

        private PanX activePanX = PanX.NONE;
        private PanY activePanY = PanY.NONE;
        private DepthMove activeDepth = DepthMove.NONE;

        public void reset() {
            lastSourceTag = 0;
            hasSmoothedPos = false;
            smoothX = 0.5f;
            smoothY = 0.5f;
            smoothFaceH = 0f;
            activePanX = PanX.NONE;
            activePanY = PanY.NONE;
            activeDepth = DepthMove.NONE;
        }

        public MoveCue evaluate(
                boolean hasFace,
                ActiveBox faceBox,
                boolean hasTapSubject,
                float tapX,
                float tapY,
                PreviewStats stats,
                GridMode gridMode,
                int goldenRotSteps) {
            int sourceTag = 0;
            float rawX = Float.NaN;
            float rawY = Float.NaN;
            if (hasFace && faceBox != null) {
                sourceTag = 1;
                rawX = faceBox.centerX();
                // Bias anchor to eye line (~42% down face box) for natural portrait headroom.
                rawY = clamp01(faceBox.top + (faceBox.bottom - faceBox.top) * 0.42f);
            } else if (hasTapSubject && !Float.isNaN(tapX) && !Float.isNaN(tapY)) {
                sourceTag = 2;
                rawX = clamp01(tapX);
                rawY = clamp01(tapY);
            } else if (stats != null && stats.saliencyConfidence >= 0.24f) {
                sourceTag = 3;
                rawX = stats.saliencyX;
                rawY = stats.saliencyY;
            }

            if (sourceTag == 0) {
                reset();
                return MoveCue.EMPTY;
            }

            if (sourceTag != lastSourceTag || !hasSmoothedPos) {
                lastSourceTag = sourceTag;
                hasSmoothedPos = true;
                smoothX = rawX;
                smoothY = rawY;
                smoothFaceH = (sourceTag == 1) ? faceBox.rawHeight : 0f;
            } else {
                smoothX = smoothX + (rawX - smoothX) * 0.42f;
                smoothY = smoothY + (rawY - smoothY) * 0.42f;
                if (sourceTag == 1) {
                    smoothFaceH = smoothFaceH + (faceBox.rawHeight - smoothFaceH) * 0.35f;
                } else {
                    smoothFaceH = 0f;
                }
            }

            boolean faceSource = (sourceTag == 1);
            updateDepthState(faceSource, faceBox, stats);

            float dx = 0f;
            float dy = 0f;
            GridMode mode = gridMode != null ? gridMode : GridMode.OFF;

            if (mode != GridMode.OFF) {
                float[] target = pickGridTarget(smoothX, smoothY, faceSource, mode, goldenRotSteps);
                dx = target[0] - smoothX; // > 0 => subject left of target => move camera LEFT
                dy = target[1] - smoothY; // > 0 => subject above target => move camera UP

                activePanX = updateGridAxisX(activePanX, dx);
                activePanY = updateGridAxisY(activePanY, dy);
                if (faceSource && faceBox.top < 0.085f && activeDepth != DepthMove.BACK) {
                    activePanY = PanY.UP;
                    dy = Math.max(dy, 0.14f);
                }
            } else {
                activePanX = updateBalancedX(activePanX, smoothX, faceSource, faceBox);
                activePanY = updateBalancedY(activePanY, smoothY, faceSource, faceBox);
                dx = computeBalancedDx(activePanX, smoothX);
                dy = computeBalancedDy(activePanY, smoothY, faceSource, faceBox);
            }

            // When face fills nearly the full frame height, stepping back cleanly resolves both
            // cropping and distortion without noisy vertical arrows fighting the user.
            if (activeDepth == DepthMove.BACK && faceSource && smoothFaceH > 0.52f) {
                activePanY = PanY.NONE;
                dy = 0f;
            }

            float sx = activePanX == PanX.NONE ? 0f : clamp01((Math.abs(dx) - 0.04f) / 0.24f);
            float sy = activePanY == PanY.NONE ? 0f : clamp01((Math.abs(dy) - 0.04f) / 0.24f);
            return new MoveCue(activePanX, activePanY, activeDepth, sx, sy);
        }

        private void updateDepthState(boolean faceSource, ActiveBox faceBox, PreviewStats stats) {
            if (faceSource && faceBox != null) {
                boolean tooCloseEnter = smoothFaceH >= 0.46f || (smoothFaceH >= 0.31f && faceBox.clipped);
                boolean tooCloseExit = smoothFaceH < 0.40f && !faceBox.clipped;
                boolean smallSpread = stats == null || stats.saliencySpread < 0.22f;
                boolean tooFarEnter = smoothFaceH > 0f && smoothFaceH <= 0.115f && smallSpread;
                boolean tooFarExit = smoothFaceH >= 0.145f || (stats != null && stats.saliencySpread >= 0.26f);

                if (tooCloseEnter) {
                    activeDepth = DepthMove.BACK;
                } else if (tooFarEnter) {
                    activeDepth = DepthMove.CLOSER;
                } else if (activeDepth == DepthMove.BACK && tooCloseExit) {
                    activeDepth = DepthMove.NONE;
                } else if (activeDepth == DepthMove.CLOSER && tooFarExit) {
                    activeDepth = DepthMove.NONE;
                }
                return;
            }

            if (activeDepth == DepthMove.BACK) {
                activeDepth = DepthMove.NONE;
            }
            if (stats != null && stats.saliencyConfidence >= 0.30f) {
                if (stats.saliencySpread < 0.145f && stats.borderEdgeRatio < 0.22f) {
                    activeDepth = DepthMove.CLOSER;
                } else if (activeDepth == DepthMove.CLOSER && stats.saliencySpread > 0.180f) {
                    activeDepth = DepthMove.NONE;
                }
            } else {
                activeDepth = DepthMove.NONE;
            }
        }

        private float[] pickGridTarget(
                float x, float y, boolean faceSource, GridMode mode, int goldenRotSteps) {
            if (faceSource && mode == GridMode.CROSSHAIR) {
                return new float[]{0.5f, 0.43f};
            }
            float[][] pts = (faceSource && mode == GridMode.THIRDS)
                    ? new float[][]{{1f / 3f, 1f / 3f}, {2f / 3f, 1f / 3f}}
                    : powerPoints(mode, goldenRotSteps);
            float[] best = {0.5f, 0.5f};
            float bestD = Float.MAX_VALUE;
            for (float[] p : pts) {
                if (faceSource && mode == GridMode.GOLDEN_SPIRAL && p[1] > 0.65f) {
                    continue;
                }
                float d = dist(x, y, p[0], p[1]);
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            return best;
        }

        private PanX updateGridAxisX(PanX current, float dx) {
            final float enter = 0.085f;
            final float exit = 0.050f;
            if (dx >= enter) return PanX.LEFT;
            if (dx <= -enter) return PanX.RIGHT;
            if (current == PanX.LEFT && dx > exit) return PanX.LEFT;
            if (current == PanX.RIGHT && dx < -exit) return PanX.RIGHT;
            return PanX.NONE;
        }

        private PanY updateGridAxisY(PanY current, float dy) {
            final float enter = 0.085f;
            final float exit = 0.050f;
            if (dy >= enter) return PanY.UP;
            if (dy <= -enter) return PanY.DOWN;
            if (current == PanY.UP && dy > exit) return PanY.UP;
            if (current == PanY.DOWN && dy < -exit) return PanY.DOWN;
            return PanY.NONE;
        }

        private PanX updateBalancedX(PanX current, float x, boolean faceSource, ActiveBox box) {
            boolean edgeL = faceSource && box != null && box.left < 0.045f && activeDepth != DepthMove.BACK;
            boolean edgeR = faceSource && box != null && box.right > 0.955f && activeDepth != DepthMove.BACK;
            if (x < 0.26f || edgeL) return PanX.LEFT;
            if (x > 0.74f || edgeR) return PanX.RIGHT;
            if (current == PanX.LEFT && x < 0.31f) return PanX.LEFT;
            if (current == PanX.RIGHT && x > 0.69f) return PanX.RIGHT;
            return PanX.NONE;
        }

        private PanY updateBalancedY(PanY current, float y, boolean faceSource, ActiveBox box) {
            if (faceSource && box != null) {
                boolean headroomTight = box.top < 0.085f && activeDepth != DepthMove.BACK;
                if (headroomTight || y < 0.20f) return PanY.UP;
                if (y > 0.62f && activeDepth != DepthMove.BACK) return PanY.DOWN;
                if (current == PanY.UP && (box.top < 0.115f || y < 0.24f)) return PanY.UP;
                if (current == PanY.DOWN && y > 0.56f) return PanY.DOWN;
                return PanY.NONE;
            }
            if (y < 0.22f) return PanY.UP;
            if (y > 0.75f) return PanY.DOWN;
            if (current == PanY.UP && y < 0.27f) return PanY.UP;
            if (current == PanY.DOWN && y > 0.70f) return PanY.DOWN;
            return PanY.NONE;
        }

        private float computeBalancedDx(PanX panX, float x) {
            if (panX == PanX.LEFT) return Math.max(0.08f, 0.31f - x);
            if (panX == PanX.RIGHT) return Math.max(0.08f, x - 0.69f);
            return 0f;
        }

        private float computeBalancedDy(PanY panY, float y, boolean faceSource, ActiveBox box) {
            if (panY == PanY.UP) {
                float topGap = (faceSource && box != null) ? Math.max(0f, 0.115f - box.top) : 0f;
                return Math.max(0.08f, Math.max(0.26f - y, topGap * 2f));
            }
            if (panY == PanY.DOWN) {
                return Math.max(0.08f, y - (faceSource ? 0.56f : 0.70f));
            }
            return 0f;
        }
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
        float[] pt = sensorPointToActiveRaw(
                sensorNormX, sensorNormY,
                cropLeftNorm, cropTopNorm, cropRightNorm, cropBottomNorm,
                relativeCameraRotationDegrees, basePreviewMirrored,
                manualRotationDegrees, extraMirrorFlip,
                frameScaleX, frameScaleY, false);
        if (pt == null) return null;
        return new float[]{clamp01(pt[0]), clamp01(pt[1])};
    }

    /**
     * Maps a normalized sensor bounding box into masked active-frame coordinates while preserving
     * full unclipped span and reporting whether the box touches or extends past frame margins.
     */
    public static ActiveBox mapSensorBoxToActiveFrame(
            float sensorLeftNorm,
            float sensorTopNorm,
            float sensorRightNorm,
            float sensorBottomNorm,
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
        float[] p1 = sensorPointToActiveRaw(
                sensorLeftNorm, sensorTopNorm,
                cropLeftNorm, cropTopNorm, cropRightNorm, cropBottomNorm,
                relativeCameraRotationDegrees, basePreviewMirrored,
                manualRotationDegrees, extraMirrorFlip,
                frameScaleX, frameScaleY, true);
        float[] p2 = sensorPointToActiveRaw(
                sensorRightNorm, sensorBottomNorm,
                cropLeftNorm, cropTopNorm, cropRightNorm, cropBottomNorm,
                relativeCameraRotationDegrees, basePreviewMirrored,
                manualRotationDegrees, extraMirrorFlip,
                frameScaleX, frameScaleY, true);
        if (p1 == null || p2 == null) return null;

        float minX = Math.min(p1[0], p2[0]);
        float maxX = Math.max(p1[0], p2[0]);
        float minY = Math.min(p1[1], p2[1]);
        float maxY = Math.max(p1[1], p2[1]);
        if (maxX <= 0f || minX >= 1f || maxY <= 0f || minY >= 1f) {
            return null;
        }
        boolean clipped = minX < 0.03f || maxX > 0.97f || minY < 0.03f || maxY > 0.97f;
        return new ActiveBox(minX, minY, maxX, maxY, maxX - minX, maxY - minY, clipped);
    }

    private static float[] sensorPointToActiveRaw(
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
            float frameScaleY,
            boolean allowOutsideCrop) {
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
        if (!allowOutsideCrop && (x < -0.02f || x > 1.02f || y < -0.02f || y > 1.02f)) {
            return null;
        }
        if (!allowOutsideCrop) {
            x = clamp01(x);
            y = clamp01(y);
        }

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
        if (!allowOutsideCrop && (ax < -0.02f || ax > 1.02f || ay < -0.02f || ay > 1.02f)) {
            return null;
        }
        return new float[]{ax, ay};
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

    public static float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0f;
        return Math.max(0f, Math.min(1f, v));
    }
}
