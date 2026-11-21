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

    /** Single prioritized physical motion step for the 3D viewfinder HUD. */
    public enum Step3D {
        NONE,
        STEP_CLOSER,
        STEP_BACK,
        MOVE_LEFT,
        MOVE_RIGHT,
        MOVE_UP,
        MOVE_DOWN,
        TILT_TOP_AWAY,
        TILT_TOP_TOWARD,
        LOCKED
    }

    /** Spatial & pitch target set by the AI Live Director ("指哪打哪"). */
    public static final class DirectorTarget {
        public final float targetX;
        public final float targetY;
        public final float targetSize;
        public final float targetElevationDeg;
        public final boolean checkElevation;

        public DirectorTarget(float targetX, float targetY, float targetSize,
                              float targetElevationDeg, boolean checkElevation) {
            this.targetX = clampRange(targetX, 0.15f, 0.85f);
            this.targetY = clampRange(targetY, 0.15f, 0.85f);
            this.targetSize = clampRange(targetSize, 0.12f, 0.58f);
            this.targetElevationDeg = clampRange(targetElevationDeg, -35f, 35f);
            this.checkElevation = checkElevation;
        }
    }

    /** Immutable 3D HUD instruction + optional AI director target frame + signed live offsets. */
    public static final class Guide3DCue {
        public static final Guide3DCue EMPTY = new Guide3DCue(
                Step3D.NONE, 0f, false, false, Float.NaN, Float.NaN, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f);

        public final Step3D step;
        public final float intensity;
        public final boolean directorActive;
        public final boolean locked;
        public final float subjectX;
        public final float subjectY;
        public final float targetBoxLeft;
        public final float targetBoxTop;
        public final float targetBoxRight;
        public final float targetBoxBottom;
        public final float offsetX;
        public final float offsetY;
        public final float offsetZ;
        public final float offsetPitch;

        public Guide3DCue(Step3D step, float intensity, boolean directorActive, boolean locked,
                          float subjectX, float subjectY,
                          float targetBoxLeft, float targetBoxTop,
                          float targetBoxRight, float targetBoxBottom) {
            this(step, intensity, directorActive, locked, subjectX, subjectY,
                    targetBoxLeft, targetBoxTop, targetBoxRight, targetBoxBottom,
                    0f, 0f, 0f, 0f);
        }

        public Guide3DCue(Step3D step, float intensity, boolean directorActive, boolean locked,
                          float subjectX, float subjectY,
                          float targetBoxLeft, float targetBoxTop,
                          float targetBoxRight, float targetBoxBottom,
                          float offsetX, float offsetY, float offsetZ, float offsetPitch) {
            this.step = step != null ? step : Step3D.NONE;
            this.intensity = clamp01(intensity);
            this.directorActive = directorActive;
            this.locked = locked;
            this.subjectX = Float.isNaN(subjectX) ? Float.NaN : clamp01(subjectX);
            this.subjectY = Float.isNaN(subjectY) ? Float.NaN : clamp01(subjectY);
            this.targetBoxLeft = clamp01(targetBoxLeft);
            this.targetBoxTop = clamp01(targetBoxTop);
            this.targetBoxRight = clamp01(targetBoxRight);
            this.targetBoxBottom = clamp01(targetBoxBottom);
            this.offsetX = clampRange(offsetX, -1f, 1f);
            this.offsetY = clampRange(offsetY, -1f, 1f);
            this.offsetZ = clampRange(offsetZ, -1f, 1f);
            this.offsetPitch = clampRange(offsetPitch, -1f, 1f);
        }

        public boolean isEmpty() {
            return step == Step3D.NONE && !directorActive;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Guide3DCue)) return false;
            Guide3DCue other = (Guide3DCue) o;
            return step == other.step
                    && directorActive == other.directorActive
                    && locked == other.locked
                    && Math.abs(targetBoxLeft - other.targetBoxLeft) < 0.005f
                    && Math.abs(targetBoxTop - other.targetBoxTop) < 0.005f;
        }

        @Override
        public int hashCode() {
            int h = step.ordinal();
            h = h * 31 + (directorActive ? 1 : 0);
            h = h * 31 + (locked ? 1 : 0);
            return h;
        }
    }

    /** 6-DoF normalized pose of the 3D wireframe mini-phone in view units relative to {@code halfH}. */
    public static final class Pose3D {
        public final float tx;
        public final float ty;
        public final float tz;
        public final float pitchDeg;
        public final float yawDeg;

        public Pose3D(float tx, float ty, float tz, float pitchDeg, float yawDeg) {
            this.tx = tx;
            this.ty = ty;
            this.tz = tz;
            this.pitchDeg = pitchDeg;
            this.yawDeg = yawDeg;
        }

        public static Pose3D lerp(Pose3D a, Pose3D b, float t) {
            float u = clamp01(t);
            return new Pose3D(
                    a.tx + (b.tx - a.tx) * u,
                    a.ty + (b.ty - a.ty) * u,
                    a.tz + (b.tz - a.tz) * u,
                    a.pitchDeg + (b.pitchDeg - a.pitchDeg) * u,
                    a.yawDeg + (b.yawDeg - a.yawDeg) * u);
        }
    }

    /** Stationary center target pose for the rangefinder coincidence 3D pod. */
    public static final Pose3D TARGET_POSE = new Pose3D(0f, 0f, 0f, 8f, 12f);

    /**
     * Optical-axis elevation in degrees from accelerometer gravity {@code (gx, gy, gz)}.
     * Positive when lens points UP (仰拍); negative when lens points DOWN (俯拍).
     */
    public static float computeCameraElevationDegrees(
            float gx, float gy, float gz, boolean facingFront) {
        float inPlane = (float) Math.hypot(gx, gy);
        if (inPlane < 1e-3f && Math.abs(gz) < 1e-3f) {
            return 0f;
        }
        float screenBackTiltDeg = (float) Math.toDegrees(Math.atan2(gz, inPlane));
        return facingFront ? screenBackTiltDeg : -screenBackTiltDeg;
    }

    /** Maps optical elevation error to physical hand tilt step for the phone's top edge. */
    public static Step3D computeHandTiltStep(
            float currentElevationDeg, float targetElevationDeg, boolean facingFront) {
        float deltaElev = targetElevationDeg - currentElevationDeg;
        float deltaScreenBackTilt = facingFront ? deltaElev : -deltaElev;
        return deltaScreenBackTilt >= 0f ? Step3D.TILT_TOP_AWAY : Step3D.TILT_TOP_TOWARD;
    }

    /**
     * Computes live rangefinder coincidence pose driven proportionally by signed remaining error.
     * Moving the phone in the suggested direction smoothly glides the live phone toward {@link #TARGET_POSE}.
     */
    public static Pose3D poseForLiveCue(Guide3DCue cue, float breathZeroToOne) {
        if (cue == null || cue.step == Step3D.NONE || cue.step == Step3D.LOCKED) {
            return TARGET_POSE;
        }
        float rawMag;
        switch (cue.step) {
            case MOVE_LEFT:
            case MOVE_RIGHT:
                rawMag = Math.abs(cue.offsetX) > 0.01f ? Math.abs(cue.offsetX) : cue.intensity;
                break;
            case MOVE_UP:
            case MOVE_DOWN:
                rawMag = Math.abs(cue.offsetY) > 0.01f ? Math.abs(cue.offsetY) : cue.intensity;
                break;
            case STEP_CLOSER:
            case STEP_BACK:
                rawMag = Math.abs(cue.offsetZ) > 0.01f ? Math.abs(cue.offsetZ) : cue.intensity;
                break;
            case TILT_TOP_AWAY:
            case TILT_TOP_TOWARD:
                rawMag = Math.abs(cue.offsetPitch) > 0.01f ? Math.abs(cue.offsetPitch) : cue.intensity;
                break;
            default:
                return TARGET_POSE;
        }
        float pulse = (float) Math.sin(clamp01(breathZeroToOne) * Math.PI * 2.0);
        float mag = clampRange(Math.max(0.14f, rawMag) - 0.06f * pulse, 0.05f, 1.0f);
        switch (cue.step) {
            case MOVE_LEFT:
                return new Pose3D(+0.72f * mag, 0f, 0f, 8f, 12f + 12f * mag);
            case MOVE_RIGHT:
                return new Pose3D(-0.72f * mag, 0f, 0f, 8f, 12f - 18f * mag);
            case MOVE_UP:
                return new Pose3D(0f, +0.56f * mag, 0f, 8f, 12f);
            case MOVE_DOWN:
                return new Pose3D(0f, -0.56f * mag, 0f, 8f, 12f);
            case STEP_CLOSER:
                return new Pose3D(0f, -0.14f * mag, +0.95f * mag, 8f, 12f);
            case STEP_BACK:
                return new Pose3D(0f, +0.12f * mag, -0.36f * mag, 8f, 12f);
            case TILT_TOP_AWAY:
                return new Pose3D(0f, 0f, 0f, 8f - 32f * mag, 12f);
            case TILT_TOP_TOWARD:
                return new Pose3D(0f, 0f, 0f, 8f + 32f * mag, 12f);
            default:
                return TARGET_POSE;
        }
    }

    /** Returns {@code {startPose, endPose}} keyframes for smoothstep 3D mini-phone animation. */
    public static Pose3D[] keyframesForStep(Step3D step, float intensity) {
        float gain = 0.72f + 0.28f * clamp01(intensity);
        switch (step) {
            case STEP_CLOSER:
                return new Pose3D[]{
                        new Pose3D(0f, 0.16f * gain, -0.30f * gain, 14f, 18f),
                        new Pose3D(0f, -0.20f * gain, 1.05f * gain, 14f, 18f)
                };
            case STEP_BACK:
                return new Pose3D[]{
                        new Pose3D(0f, -0.20f * gain, 1.05f * gain, 14f, 18f),
                        new Pose3D(0f, 0.16f * gain, -0.30f * gain, 14f, 18f)
                };
            case MOVE_LEFT:
                return new Pose3D[]{
                        new Pose3D(0.55f * gain, 0f, 0f, 8f, 12f),
                        new Pose3D(-0.55f * gain, 0f, 0f, 8f, -18f)
                };
            case MOVE_RIGHT:
                return new Pose3D[]{
                        new Pose3D(-0.55f * gain, 0f, 0f, 8f, -12f),
                        new Pose3D(0.55f * gain, 0f, 0f, 8f, 18f)
                };
            case MOVE_UP:
                return new Pose3D[]{
                        new Pose3D(0f, 0.42f * gain, 0f, 8f, 14f),
                        new Pose3D(0f, -0.42f * gain, 0f, 8f, 14f)
                };
            case MOVE_DOWN:
                return new Pose3D[]{
                        new Pose3D(0f, -0.42f * gain, 0f, 8f, 14f),
                        new Pose3D(0f, 0.42f * gain, 0f, 8f, 14f)
                };
            case TILT_TOP_AWAY:
                return new Pose3D[]{
                        new Pose3D(0f, 0f, 0.08f, -18f, 14f),
                        new Pose3D(0f, 0f, 0.08f, 36f, 14f)
                };
            case TILT_TOP_TOWARD:
                return new Pose3D[]{
                        new Pose3D(0f, 0f, 0.08f, 28f, 14f),
                        new Pose3D(0f, 0f, 0.08f, -30f, 14f)
                };
            default:
                Pose3D steady = new Pose3D(0f, 0f, 0f, 8f, 12f);
                return new Pose3D[]{steady, steady};
        }
    }

    /**
     * Projects the 4 corners of a 3D phone face (TL, TR, BR, BL) at local depth {@code localZ}
     * around origin {@code (0, 0)} with positive X right, Y down, Z into screen depth.
     */
    public static void projectPhoneCorners(
            Pose3D pose, float halfW, float halfH, float localZ,
            float focalLen, float[] out8XY) {
        if (pose == null || out8XY == null || out8XY.length < 8) return;
        double pr = Math.toRadians(pose.pitchDeg);
        double yr = Math.toRadians(pose.yawDeg);
        float cosP = (float) Math.cos(pr);
        float sinP = (float) Math.sin(pr);
        float cosY = (float) Math.cos(yr);
        float sinY = (float) Math.sin(yr);

        float txPx = pose.tx * halfH;
        float tyPx = pose.ty * halfH;
        float tzPx = pose.tz * halfH;

        float[] lx = {-halfW, halfW, halfW, -halfW};
        float[] ly = {-halfH, -halfH, halfH, halfH};
        for (int i = 0; i < 4; i++) {
            float y1 = ly[i] * cosP + localZ * sinP;
            float z1 = -ly[i] * sinP + localZ * cosP;
            float x2 = lx[i] * cosY - z1 * sinY;
            float z2 = lx[i] * sinY + z1 * cosY;

            float denom = Math.max(0.25f * focalLen, focalLen + z2 + tzPx);
            float scale = focalLen / denom;
            out8XY[i * 2] = (x2 + txPx) * scale;
            out8XY[i * 2 + 1] = (y1 + tyPx) * scale;
        }
    }

    /**
     * Pure-Java lightweight 5x5 RGB+Luma SAD patch tracker operating on 32x24 {@link PreviewStats#thumbRgb}
     * frames so tapped non-face subjects stay tracked across hand movement without global background drift.
     */
    public static final class SubjectPatchTracker {
        private static final int PATCH_R = 2;
        private static final int PATCH_SIZE = 5;
        private static final int SEARCH_R = 7;
        private static final float MIN_CONTRAST_LUMA = 0.08f;
        private static final float MAX_MEAN_SAD = 0.22f;
        private static final long TTL_MS = 10_000L;

        private boolean active = false;
        private int patchW = 0;
        private int patchH = 0;
        private final float[] tplR = new float[PATCH_SIZE * PATCH_SIZE];
        private final float[] tplG = new float[PATCH_SIZE * PATCH_SIZE];
        private final float[] tplB = new float[PATCH_SIZE * PATCH_SIZE];
        private final float[] tplL = new float[PATCH_SIZE * PATCH_SIZE];
        private float curX = Float.NaN;
        private float curY = Float.NaN;
        private long lockedAtMs = 0L;
        private int failStreak = 0;

        public void reset() {
            active = false;
            patchW = 0;
            patchH = 0;
            curX = Float.NaN;
            curY = Float.NaN;
            lockedAtMs = 0L;
            failStreak = 0;
        }

        public boolean isActive() {
            return active && !Float.isNaN(curX) && !Float.isNaN(curY);
        }

        public float getX() {
            return curX;
        }

        public float getY() {
            return curY;
        }

        public boolean lock(PreviewStats stats, float normX, float normY, long nowMs) {
            if (stats == null || stats.thumbRgb == null
                    || stats.width < 8 || stats.height < 8
                    || stats.thumbRgb.length < stats.width * stats.height * 3
                    || Float.isNaN(normX) || Float.isNaN(normY)) {
                reset();
                return false;
            }
            int w = stats.width;
            int h = stats.height;
            int cx = clampInt(Math.round(clamp01(normX) * w - 0.5f), PATCH_R, w - 1 - PATCH_R);
            int cy = clampInt(Math.round(clamp01(normY) * h - 0.5f), PATCH_R, h - 1 - PATCH_R);

            float minL = 1f;
            float maxL = 0f;
            int idx = 0;
            for (int dy = -PATCH_R; dy <= PATCH_R; dy++) {
                int rowOff = (cy + dy) * w;
                for (int dx = -PATCH_R; dx <= PATCH_R; dx++) {
                    int off = (rowOff + (cx + dx)) * 3;
                    float r = (stats.thumbRgb[off] & 0xFF) / 255f;
                    float g = (stats.thumbRgb[off + 1] & 0xFF) / 255f;
                    float b = (stats.thumbRgb[off + 2] & 0xFF) / 255f;
                    float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    tplR[idx] = r;
                    tplG[idx] = g;
                    tplB[idx] = b;
                    tplL[idx] = l;
                    if (l < minL) minL = l;
                    if (l > maxL) maxL = l;
                    idx++;
                }
            }
            if ((maxL - minL) < MIN_CONTRAST_LUMA) {
                reset();
                return false;
            }
            active = true;
            patchW = w;
            patchH = h;
            curX = (cx + 0.5f) / w;
            curY = (cy + 0.5f) / h;
            lockedAtMs = nowMs;
            failStreak = 0;
            return true;
        }

        public boolean update(PreviewStats stats, long nowMs) {
            if (!active) return false;
            if ((nowMs - lockedAtMs) > TTL_MS) {
                reset();
                return false;
            }
            if (stats == null || stats.thumbRgb == null
                    || stats.width != patchW || stats.height != patchH
                    || stats.thumbRgb.length < patchW * patchH * 3) {
                return true;
            }
            int w = patchW;
            int h = patchH;
            int predX = clampInt(Math.round(curX * w - 0.5f), PATCH_R, w - 1 - PATCH_R);
            int predY = clampInt(Math.round(curY * h - 0.5f), PATCH_R, h - 1 - PATCH_R);
            int minCx = Math.max(PATCH_R, predX - SEARCH_R);
            int maxCx = Math.min(w - 1 - PATCH_R, predX + SEARCH_R);
            int minCy = Math.max(PATCH_R, predY - SEARCH_R);
            int maxCy = Math.min(h - 1 - PATCH_R, predY + SEARCH_R);

            int bestX = predX;
            int bestY = predY;
            float bestScore = Float.MAX_VALUE;
            float bestSad = Float.MAX_VALUE;

            for (int cy = minCy; cy <= maxCy; cy++) {
                for (int cx = minCx; cx <= maxCx; cx++) {
                    float sad = patchMeanSad(stats.thumbRgb, w, cx, cy);
                    float distPenalty = 0.012f * (float) Math.hypot(cx - predX, cy - predY);
                    float score = sad + distPenalty;
                    if (score < bestScore) {
                        bestScore = score;
                        bestSad = sad;
                        bestX = cx;
                        bestY = cy;
                    }
                }
            }

            if (bestSad > MAX_MEAN_SAD) {
                failStreak++;
                if (failStreak >= 3) {
                    reset();
                    return false;
                }
                return true;
            }
            failStreak = 0;

            double sumW = 0.0;
            double sumX = 0.0;
            double sumY = 0.0;
            for (int oy = -1; oy <= 1; oy++) {
                int ny = bestY + oy;
                if (ny < PATCH_R || ny > h - 1 - PATCH_R) continue;
                for (int ox = -1; ox <= 1; ox++) {
                    int nx = bestX + ox;
                    if (nx < PATCH_R || nx > w - 1 - PATCH_R) continue;
                    float s = patchMeanSad(stats.thumbRgb, w, nx, ny);
                    double weight = Math.max(0.0, (bestSad + 0.04) - s);
                    sumW += weight;
                    sumX += nx * weight;
                    sumY += ny * weight;
                }
            }
            float subX = sumW > 1e-6 ? (float) (sumX / sumW) : bestX;
            float subY = sumW > 1e-6 ? (float) (sumY / sumW) : bestY;
            curX = clamp01((subX + 0.5f) / w);
            curY = clamp01((subY + 0.5f) / h);
            if (curX < 0.03f || curX > 0.97f || curY < 0.03f || curY > 0.97f) {
                failStreak++;
                if (failStreak >= 3) {
                    reset();
                    return false;
                }
            }
            return true;
        }

        private float patchMeanSad(byte[] rgb, int w, int cx, int cy) {
            float acc = 0f;
            int idx = 0;
            for (int dy = -PATCH_R; dy <= PATCH_R; dy++) {
                int rowOff = (cy + dy) * w;
                for (int dx = -PATCH_R; dx <= PATCH_R; dx++) {
                    int off = (rowOff + (cx + dx)) * 3;
                    float r = (rgb[off] & 0xFF) / 255f;
                    float g = (rgb[off + 1] & 0xFF) / 255f;
                    float b = (rgb[off + 2] & 0xFF) / 255f;
                    float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    acc += Math.abs(r - tplR[idx])
                            + Math.abs(g - tplG[idx])
                            + Math.abs(b - tplB[idx])
                            + Math.abs(l - tplL[idx]);
                    idx++;
                }
            }
            return acc / (4f * PATCH_SIZE * PATCH_SIZE);
        }

        private static int clampInt(int v, int min, int max) {
            return Math.max(min, Math.min(max, v));
        }
    }

    /**
     * Stateful anti-oscillation single-step evaluator prioritizing Depth -> Translation -> 3D Pitch.
     * Normal mode activates only for explicit subjects (detected face or patch-tracked tap target)
     * with wide comfort zones and a sign-aware zero-crossing lock latch.
     */
    public static final class MoveGuideState {
        private static final float PAN_ENTER = 0.15f;
        private static final float PAN_SWEET_EXIT = 0.12f;
        private static final float PAN_LATCH_ESCAPE = 0.18f;
        private static final float PITCH_ENTER_DEG = 12.0f;
        private static final float PITCH_SWEET_EXIT_DEG = 8.0f;
        private static final float PITCH_LATCH_ESCAPE_DEG = 13.5f;
        private static final long NORMAL_LOCK_DISPLAY_MS = 800L;
        private static final long NORMAL_POST_LOCK_GRACE_MS = 1500L;

        private int lastSourceTag = 0; // 0=none, 1=face, 2=tracked, 3=director saliency fallback
        private boolean hasSmoothedPos = false;
        private float smoothX = 0.5f;
        private float smoothY = 0.5f;
        private float smoothFaceH = 0f;
        private boolean hasSmoothedElev = false;
        private float smoothElevDeg = 0f;

        private Step3D activeStep = Step3D.NONE;
        private long guidedSinceMs = 0L;
        private long lockedUntilMs = 0L;
        private long postLockGraceUntilMs = 0L;
        private boolean lastDirectorMode = false;

        public void reset() {
            lastSourceTag = 0;
            hasSmoothedPos = false;
            smoothX = 0.5f;
            smoothY = 0.5f;
            smoothFaceH = 0f;
            hasSmoothedElev = false;
            smoothElevDeg = 0f;
            activeStep = Step3D.NONE;
            guidedSinceMs = 0L;
            lockedUntilMs = 0L;
            postLockGraceUntilMs = 0L;
            lastDirectorMode = false;
        }

        public Guide3DCue evaluate(
                boolean hasFace,
                ActiveBox faceBox,
                boolean hasTrackedSubject,
                float trackedX,
                float trackedY,
                PreviewStats stats,
                GridMode gridMode,
                int goldenRotSteps,
                float cameraElevationDeg,
                boolean facingFront,
                DirectorTarget directorTarget,
                long nowMs) {
            boolean director = directorTarget != null;
            if (director != lastDirectorMode) {
                activeStep = Step3D.NONE;
                guidedSinceMs = 0L;
                lockedUntilMs = 0L;
                postLockGraceUntilMs = 0L;
                lastDirectorMode = director;
            }

            if (!hasSmoothedElev) {
                hasSmoothedElev = true;
                smoothElevDeg = cameraElevationDeg;
            } else {
                smoothElevDeg = smoothElevDeg + (cameraElevationDeg - smoothElevDeg) * 0.65f;
            }

            int sourceTag = 0;
            float rawX = Float.NaN;
            float rawY = Float.NaN;
            if (hasFace && faceBox != null) {
                sourceTag = 1;
                rawX = faceBox.centerX();
                rawY = clamp01(faceBox.top + (faceBox.bottom - faceBox.top) * 0.42f);
            } else if (hasTrackedSubject && !Float.isNaN(trackedX) && !Float.isNaN(trackedY)) {
                sourceTag = 2;
                rawX = clamp01(trackedX);
                rawY = clamp01(trackedY);
            } else if (director && stats != null && stats.saliencyConfidence >= 0.35f) {
                sourceTag = 3;
                rawX = stats.saliencyX;
                rawY = stats.saliencyY;
            }

            if (sourceTag == 0) {
                lastSourceTag = 0;
                hasSmoothedPos = false;
                smoothFaceH = 0f;
                if (!director) {
                    activeStep = Step3D.NONE;
                    guidedSinceMs = 0L;
                    lockedUntilMs = 0L;
                    postLockGraceUntilMs = 0L;
                    return Guide3DCue.EMPTY;
                }
            } else if (sourceTag != lastSourceTag || !hasSmoothedPos) {
                lastSourceTag = sourceTag;
                hasSmoothedPos = true;
                smoothX = rawX;
                smoothY = rawY;
                smoothFaceH = (sourceTag == 1) ? faceBox.rawHeight : 0f;
            } else {
                smoothX = smoothX + (rawX - smoothX) * 0.72f;
                smoothY = smoothY + (rawY - smoothY) * 0.72f;
                smoothFaceH = (sourceTag == 1)
                        ? (smoothFaceH + (faceBox.rawHeight - smoothFaceH) * 0.60f)
                        : 0f;
            }

            boolean faceSource = (sourceTag == 1);
            boolean strictLatch = activeStep == Step3D.LOCKED || nowMs < postLockGraceUntilMs;
            Step3D nextStep = Step3D.NONE;
            float strength = 0f;
            float signedErrX = 0f;
            float signedErrY = 0f;
            float signedErrZ = 0f;
            float signedErrPitch = 0f;

            // Priority 1: Depth (step back / step closer)
            Step3D depthStep = evaluateDepthStep(faceSource, faceBox, stats, directorTarget, strictLatch);
            if (depthStep != Step3D.NONE) {
                nextStep = depthStep;
                float targetSz = director ? directorTarget.targetSize : (depthStep == Step3D.STEP_BACK ? 0.34f : 0.16f);
                float curSz = faceSource ? smoothFaceH : (stats != null ? stats.saliencySpread * 1.45f : targetSz);
                float diffZ = targetSz - curSz;
                signedErrZ = clampRange(diffZ / 0.25f, -1f, 1f);
                strength = Math.max(0.35f, Math.abs(signedErrZ));
            }

            // Priority 2: Single dominant pan direction (X vs Y) with sign-aware zero-crossing lock
            if (hasSmoothedPos) {
                GridMode mode = gridMode != null ? gridMode : GridMode.OFF;
                float dx;
                float dy;
                boolean singleTarget;
                if (director) {
                    singleTarget = true;
                    dx = directorTarget.targetX - smoothX;
                    dy = directorTarget.targetY - smoothY;
                } else if (mode == GridMode.CROSSHAIR || mode == GridMode.GOLDEN_SPIRAL) {
                    singleTarget = true;
                    float[] target = pickGridTarget(smoothX, smoothY, faceSource, mode, goldenRotSteps);
                    dx = target[0] - smoothX;
                    dy = target[1] - smoothY;
                } else {
                    singleTarget = false;
                    dx = balancedDeltaX(smoothX, faceSource, faceBox);
                    dy = balancedDeltaY(smoothY, faceSource, faceBox);
                }

                boolean xNeed;
                boolean yNeed;
                if (singleTarget) {
                    xNeed = axisNeed(dx, Step3D.MOVE_LEFT, Step3D.MOVE_RIGHT, activeStep,
                            strictLatch, PAN_ENTER, PAN_SWEET_EXIT, PAN_LATCH_ESCAPE);
                    yNeed = axisNeed(dy, Step3D.MOVE_UP, Step3D.MOVE_DOWN, activeStep,
                            strictLatch, PAN_ENTER, PAN_SWEET_EXIT, PAN_LATCH_ESCAPE);
                } else {
                    float minEdge = strictLatch ? 0.04f : 0.01f;
                    xNeed = Math.abs(dx) > minEdge;
                    yNeed = Math.abs(dy) > minEdge;
                }

                if (xNeed) signedErrX = clampRange(dx / 0.35f, -1f, 1f);
                if (yNeed) signedErrY = clampRange(dy / 0.35f, -1f, 1f);

                if (nextStep == Step3D.NONE && (xNeed || yNeed)) {
                    boolean wasX = (activeStep == Step3D.MOVE_LEFT || activeStep == Step3D.MOVE_RIGHT);
                    boolean wasY = (activeStep == Step3D.MOVE_UP || activeStep == Step3D.MOVE_DOWN);
                    float scoreX = xNeed ? Math.abs(dx) * (wasX ? 1.18f : 1f) : -1f;
                    float scoreY = yNeed ? Math.abs(dy) * (wasY ? 1.18f : 1f) : -1f;
                    if (scoreX >= scoreY) {
                        nextStep = dx >= 0f ? Step3D.MOVE_LEFT : Step3D.MOVE_RIGHT;
                        strength = clamp01((Math.abs(dx) - 0.06f) / 0.24f);
                    } else {
                        nextStep = dy >= 0f ? Step3D.MOVE_UP : Step3D.MOVE_DOWN;
                        strength = clamp01((Math.abs(dy) - 0.06f) / 0.24f);
                    }
                }
            }

            // Priority 3: True 3D camera pitch/elevation correction with sign-aware latch
            Step3D pitchStep = evaluatePitchStep(faceSource, facingFront, directorTarget, strictLatch);
            if (pitchStep != Step3D.NONE) {
                float targetElev = director
                        ? directorTarget.targetElevationDeg
                        : (smoothElevDeg >= 0f ? 2.0f : -14.0f);
                float deltaElev = targetElev - smoothElevDeg;
                float deltaBackTilt = facingFront ? deltaElev : -deltaElev;
                signedErrPitch = clampRange(deltaBackTilt / 25.0f, -1f, 1f);
                if (nextStep == Step3D.NONE) {
                    nextStep = pitchStep;
                    strength = clamp01((Math.abs(deltaBackTilt) - 6f) / 22f);
                }
            }

            if (nextStep != Step3D.NONE) {
                if (activeStep == Step3D.NONE || activeStep == Step3D.LOCKED) {
                    guidedSinceMs = nowMs;
                }
                activeStep = nextStep;
                lockedUntilMs = 0L;
            } else {
                if (director) {
                    activeStep = hasSmoothedPos ? Step3D.LOCKED : Step3D.NONE;
                } else {
                    boolean hadRealGuidance = activeStep != Step3D.NONE
                            && activeStep != Step3D.LOCKED
                            && guidedSinceMs > 0L
                            && (nowMs - guidedSinceMs) >= 240L;
                    if (hadRealGuidance && hasSmoothedPos) {
                        lockedUntilMs = nowMs + NORMAL_LOCK_DISPLAY_MS;
                        postLockGraceUntilMs = nowMs + NORMAL_POST_LOCK_GRACE_MS;
                        activeStep = Step3D.LOCKED;
                    } else if (activeStep == Step3D.LOCKED && nowMs < lockedUntilMs && hasSmoothedPos) {
                        activeStep = Step3D.LOCKED;
                    } else {
                        activeStep = Step3D.NONE;
                        lockedUntilMs = 0L;
                    }
                    guidedSinceMs = 0L;
                }
            }

            boolean locked = (activeStep == Step3D.LOCKED);
            if (locked || activeStep == Step3D.NONE) {
                signedErrX = 0f;
                signedErrY = 0f;
                signedErrZ = 0f;
                signedErrPitch = 0f;
            }

            float boxL = 0f, boxT = 0f, boxR = 0f, boxB = 0f;
            if (director) {
                float halfH = Math.max(0.09f, directorTarget.targetSize * 0.58f);
                float halfW = Math.max(0.08f, halfH * 0.84f);
                float centerY = faceSource
                        ? clamp01(directorTarget.targetY + 0.08f * directorTarget.targetSize)
                        : directorTarget.targetY;
                boxL = clamp01(directorTarget.targetX - halfW);
                boxT = clamp01(centerY - halfH);
                boxR = clamp01(directorTarget.targetX + halfW);
                boxB = clamp01(centerY + halfH);
            }
            return new Guide3DCue(
                    activeStep, strength, director, locked,
                    hasSmoothedPos ? smoothX : Float.NaN,
                    hasSmoothedPos ? smoothY : Float.NaN,
                    boxL, boxT, boxR, boxB,
                    signedErrX, signedErrY, signedErrZ, signedErrPitch);
        }

        private static boolean axisNeed(
                float delta, Step3D posStep, Step3D negStep, Step3D active,
                boolean strictLatch, float enter, float sweetExit, float latchEscape) {
            if (active == posStep) {
                return delta > sweetExit || delta < -latchEscape;
            }
            if (active == negStep) {
                return delta < -sweetExit || delta > latchEscape;
            }
            float threshold = strictLatch ? latchEscape : enter;
            return Math.abs(delta) >= threshold;
        }

        private Step3D evaluateDepthStep(
                boolean faceSource, ActiveBox faceBox, PreviewStats stats,
                DirectorTarget target, boolean strictLatch) {
            boolean wasCloser = (activeStep == Step3D.STEP_CLOSER);
            boolean wasBack = (activeStep == Step3D.STEP_BACK);

            if (target != null) {
                if (faceSource && faceBox != null) {
                    float enterPad = strictLatch ? 0.15f : 0.14f;
                    float tooCloseEnter = target.targetSize + enterPad;
                    float tooCloseExit = target.targetSize + 0.09f;
                    float tooFarEnter = target.targetSize - enterPad;
                    float tooFarExit = target.targetSize - 0.09f;
                    if (wasBack) {
                        return (smoothFaceH >= tooCloseExit || faceBox.clipped) ? Step3D.STEP_BACK : Step3D.NONE;
                    }
                    if (wasCloser) {
                        return (smoothFaceH > 0f && smoothFaceH < tooFarExit) ? Step3D.STEP_CLOSER : Step3D.NONE;
                    }
                    if (smoothFaceH >= tooCloseEnter
                            || (faceBox.clipped && smoothFaceH > target.targetSize + 0.04f)) {
                        return Step3D.STEP_BACK;
                    }
                    if (smoothFaceH > 0f && smoothFaceH <= tooFarEnter) {
                        return Step3D.STEP_CLOSER;
                    }
                    return Step3D.NONE;
                }
                if (stats != null && stats.saliencyConfidence >= 0.35f) {
                    float estSize = clamp01(stats.saliencySpread * 1.45f);
                    if (estSize <= target.targetSize - 0.13f && stats.borderEdgeRatio < 0.24f) {
                        return Step3D.STEP_CLOSER;
                    }
                    if (wasCloser && estSize < target.targetSize - 0.08f) {
                        return Step3D.STEP_CLOSER;
                    }
                }
                return Step3D.NONE;
            }

            if (faceSource && faceBox != null) {
                boolean tooCloseEnter = smoothFaceH >= 0.48f || (smoothFaceH >= 0.34f && faceBox.clipped);
                boolean tooCloseExit = smoothFaceH < 0.40f && !faceBox.clipped;
                boolean smallSpread = stats == null || stats.saliencySpread < 0.22f;
                boolean tooFarEnter = smoothFaceH > 0f && smoothFaceH <= 0.11f && smallSpread;
                boolean tooFarExit = smoothFaceH >= 0.15f || (stats != null && stats.saliencySpread >= 0.26f);

                if (wasBack) return tooCloseExit ? Step3D.NONE : Step3D.STEP_BACK;
                if (wasCloser) return tooFarExit ? Step3D.NONE : Step3D.STEP_CLOSER;
                if (tooCloseEnter) return Step3D.STEP_BACK;
                if (tooFarEnter) return Step3D.STEP_CLOSER;
            }
            return Step3D.NONE;
        }

        private Step3D evaluatePitchStep(
                boolean faceSource, boolean facingFront, DirectorTarget target, boolean strictLatch) {
            if (target != null) {
                if (!target.checkElevation) return Step3D.NONE;
                float deltaElev = target.targetElevationDeg - smoothElevDeg;
                float deltaBackTilt = facingFront ? deltaElev : -deltaElev;
                if (!axisNeed(deltaBackTilt, Step3D.TILT_TOP_AWAY, Step3D.TILT_TOP_TOWARD,
                        activeStep, strictLatch, PITCH_ENTER_DEG, PITCH_SWEET_EXIT_DEG,
                        PITCH_LATCH_ESCAPE_DEG)) {
                    return Step3D.NONE;
                }
                return deltaBackTilt >= 0f ? Step3D.TILT_TOP_AWAY : Step3D.TILT_TOP_TOWARD;
            }

            if (faceSource) {
                boolean wasTilt = (activeStep == Step3D.TILT_TOP_AWAY || activeStep == Step3D.TILT_TOP_TOWARD);
                if (smoothElevDeg >= 18.0f || (wasTilt && smoothElevDeg > 10.0f)) {
                    return computeHandTiltStep(smoothElevDeg, 2.0f, facingFront);
                }
                if (smoothElevDeg <= -34.0f || (wasTilt && smoothElevDeg < -24.0f)) {
                    return computeHandTiltStep(smoothElevDeg, -14.0f, facingFront);
                }
            }
            return Step3D.NONE;
        }

        private float[] pickGridTarget(
                float x, float y, boolean faceSource, GridMode mode, int goldenRotSteps) {
            if (mode == GridMode.CROSSHAIR) {
                return new float[]{0.5f, faceSource ? 0.42f : 0.50f};
            }
            float[][] pts = powerPoints(mode, goldenRotSteps);
            float[] best = {0.5f, faceSource ? 0.42f : 0.45f};
            float bestD = dist(x, y, best[0], best[1]);
            for (float[] p : pts) {
                if (faceSource && p[1] > 0.65f) {
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

        private float balancedDeltaX(float x, boolean faceSource, ActiveBox box) {
            boolean wasLeft = (activeStep == Step3D.MOVE_LEFT);
            boolean wasRight = (activeStep == Step3D.MOVE_RIGHT);
            boolean edgeL = faceSource && box != null && box.left < 0.03f;
            boolean edgeR = faceSource && box != null && box.right > 0.97f;
            if (x < 0.22f || edgeL || (wasLeft && x < 0.30f)) {
                return Math.max(0.14f, 0.34f - x);
            }
            if (x > 0.78f || edgeR || (wasRight && x > 0.70f)) {
                return -Math.max(0.14f, x - 0.66f);
            }
            return 0f;
        }

        private float balancedDeltaY(float y, boolean faceSource, ActiveBox box) {
            boolean wasUp = (activeStep == Step3D.MOVE_UP);
            boolean wasDown = (activeStep == Step3D.MOVE_DOWN);
            if (faceSource && box != null) {
                if (box.top < 0.065f || y < 0.20f || (wasUp && (box.top < 0.095f || y < 0.25f))) {
                    return Math.max(0.14f, Math.max(0.30f - y, (0.095f - box.top) * 2f));
                }
                if (y > 0.58f || (wasDown && y > 0.52f)) {
                    return -Math.max(0.14f, y - 0.48f);
                }
                return 0f;
            }
            if (y < 0.20f || (wasUp && y < 0.26f)) {
                return Math.max(0.14f, 0.32f - y);
            }
            if (y > 0.76f || (wasDown && y > 0.70f)) {
                return -Math.max(0.14f, y - 0.64f);
            }
            return 0f;
        }
    }

    private static float clampRange(float v, float min, float max) {
        if (Float.isNaN(v)) return min;
        return Math.max(min, Math.min(max, v));
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
