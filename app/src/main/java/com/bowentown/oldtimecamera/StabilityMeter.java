package com.bowentown.oldtimecamera;

/**
 * Pure-Java sliding-window RMS hand-stability detector supporting gyroscope angular rate
 * (preferred) and high-pass filtered accelerometer motion fallback.
 */
public final class StabilityMeter {

    private static final int WINDOW_CAPACITY = 24;
    private static final long WINDOW_NS = 450_000_000L;
    /** Below this many samples the window is too short to judge hands, so callers must not gate on it. */
    private static final int MIN_READY_SAMPLES = 3;

    public static final float GYRO_UNSTABLE_RMS = 0.24f; // rad/s
    public static final float GYRO_STABLE_RMS = 0.15f;   // rad/s

    public static final float ACCEL_UNSTABLE_RMS = 0.85f; // m/s^2 high-pass
    public static final float ACCEL_STABLE_RMS = 0.50f;   // m/s^2 high-pass

    private final float[] energies = new float[WINDOW_CAPACITY];
    private final long[] timestampsNs = new long[WINDOW_CAPACITY];
    private int head = 0;
    private int count = 0;

    private boolean usingGyro = true;
    private boolean unstable = false;
    private float latestRms = 0f;

    // Gravity low-pass tracker for accelerometer high-pass fallback.
    private boolean hasAccelBaseline = false;
    private float gravX = 0f;
    private float gravY = 0f;
    private float gravZ = 0f;

    public void reset() {
        head = 0;
        count = 0;
        unstable = false;
        latestRms = 0f;
        hasAccelBaseline = false;
    }

    public boolean isStable() {
        return !unstable;
    }

    /**
     * Whether enough samples have arrived for {@link #isStable()} to mean anything.
     * Sensors need a few frames after registration (or after returning from the
     * background); until then a shutter must fall through instead of being held.
     */
    public boolean isReady() {
        return count >= MIN_READY_SAMPLES;
    }

    public float getLatestRms() {
        return latestRms;
    }

    /** Normalized [0..1] instability where 1.0 corresponds to the unstable threshold. */
    public float getInstabilityRatio() {
        float thresh = usingGyro ? GYRO_UNSTABLE_RMS : ACCEL_UNSTABLE_RMS;
        return Math.max(0f, Math.min(1f, latestRms / thresh));
    }

    public void pushGyro(float wx, float wy, float wz, long timestampNs) {
        if (!usingGyro) {
            reset();
            usingGyro = true;
        }
        float magSq = wx * wx + wy * wy + wz * wz;
        recordSample(magSq, timestampNs, GYRO_UNSTABLE_RMS, GYRO_STABLE_RMS);
    }

    public void pushAccelerometer(float ax, float ay, float az, long timestampNs) {
        if (usingGyro && count > 0) {
            // Gyroscope takes precedence when available.
            return;
        }
        usingGyro = false;
        if (count > 0) {
            int prevIdx = (head - 1 + WINDOW_CAPACITY) % WINDOW_CAPACITY;
            if (timestampNs < timestampsNs[prevIdx]) {
                reset();
            }
        }
        if (!hasAccelBaseline) {
            gravX = ax;
            gravY = ay;
            gravZ = az;
            hasAccelBaseline = true;
            recordSample(0f, timestampNs, ACCEL_UNSTABLE_RMS, ACCEL_STABLE_RMS);
            return;
        }
        final float alpha = 0.82f;
        gravX = alpha * gravX + (1f - alpha) * ax;
        gravY = alpha * gravY + (1f - alpha) * ay;
        gravZ = alpha * gravZ + (1f - alpha) * az;
        float lx = ax - gravX;
        float ly = ay - gravY;
        float lz = az - gravZ;
        float magSq = lx * lx + ly * ly + lz * lz;
        recordSample(magSq, timestampNs, ACCEL_UNSTABLE_RMS, ACCEL_STABLE_RMS);
    }

    private void recordSample(float magSq, long timestampNs, float enterThresh, float exitThresh) {
        if (count > 0) {
            int prevIdx = (head - 1 + WINDOW_CAPACITY) % WINDOW_CAPACITY;
            if (timestampNs < timestampsNs[prevIdx]) {
                head = 0;
                count = 0;
                unstable = false;
                latestRms = 0f;
            }
        }
        energies[head] = magSq;
        timestampsNs[head] = timestampNs;
        head = (head + 1) % WINDOW_CAPACITY;
        if (count < WINDOW_CAPACITY) count++;

        double sum = 0.0;
        int valid = 0;
        long minTs = timestampNs - WINDOW_NS;
        for (int i = 0; i < count; i++) {
            int idx = (head - 1 - i + WINDOW_CAPACITY) % WINDOW_CAPACITY;
            if (timestampsNs[idx] >= minTs || valid == 0) {
                sum += energies[idx];
                valid++;
            } else {
                break;
            }
        }
        latestRms = valid > 0 ? (float) Math.sqrt(sum / valid) : 0f;

        if (valid >= 3) {
            if (!unstable && latestRms >= enterThresh) {
                unstable = true;
            } else if (unstable && latestRms <= exitThresh) {
                unstable = false;
            }
        }
    }

    /**
     * Pure-Java intentional shake detector (two high-g linear impulses within 450 ms,
     * separated by a 1.2 s cooldown) used for shake-to-shuffle light leaks without
     * misfiring on everyday walking or tap vibration.
     */
    public static final class ShakeDetector {
        private static final float PEAK_THRESHOLD = 13.5f; // m/s^2 over gravity
        private static final long PEAK_WINDOW_NS = 450_000_000L;
        private static final long COOLDOWN_NS = 1_200_000_000L;

        private boolean hasBaseline = false;
        private float gx, gy, gz;
        private int peakCount = 0;
        private long firstPeakNs = 0L;
        private long lastTriggerNs = Long.MIN_VALUE / 4;
        private boolean inPeak = false;

        public void reset() {
            hasBaseline = false;
            peakCount = 0;
            firstPeakNs = 0L;
            inPeak = false;
        }

        /** Feeds accelerometer reading; returns {@code true} once when a deliberate shake completes. */
        public boolean pushAccelerometer(float ax, float ay, float az, long timestampNs) {
            if (!hasBaseline) {
                gx = ax;
                gy = ay;
                gz = az;
                hasBaseline = true;
                return false;
            }
            gx = 0.8f * gx + 0.2f * ax;
            gy = 0.8f * gy + 0.2f * ay;
            gz = 0.8f * gz + 0.2f * az;
            float mag = (float) Math.hypot(ax - gx, Math.hypot(ay - gy, az - gz));
            if (timestampNs - lastTriggerNs < COOLDOWN_NS) {
                return false;
            }
            if (peakCount > 0 && timestampNs - firstPeakNs > PEAK_WINDOW_NS) {
                peakCount = 0;
            }
            if (!inPeak && mag >= PEAK_THRESHOLD) {
                inPeak = true;
                if (peakCount == 0) {
                    firstPeakNs = timestampNs;
                }
                peakCount++;
                if (peakCount >= 2) {
                    peakCount = 0;
                    lastTriggerNs = timestampNs;
                    return true;
                }
            } else if (inPeak && mag < PEAK_THRESHOLD * 0.55f) {
                inPeak = false;
            }
            return false;
        }
    }
}
