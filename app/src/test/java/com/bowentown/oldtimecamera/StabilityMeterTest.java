package com.bowentown.oldtimecamera;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StabilityMeterTest {

    @Test
    public void readinessGateRequiresEnoughSamples() {
        StabilityMeter meter = new StabilityMeter();
        // No samples yet: callers must not treat this as "steady".
        assertFalse(meter.isReady());

        long ts = 1_000_000_000L;
        meter.pushGyro(0.01f, 0f, 0f, ts);
        meter.pushGyro(0.01f, 0f, 0f, ts + 30_000_000L);
        assertFalse(meter.isReady());

        meter.pushGyro(0.01f, 0f, 0f, ts + 60_000_000L);
        assertTrue(meter.isReady());
        assertTrue(meter.isStable());

        meter.reset();
        assertFalse(meter.isReady());
    }

    @Test
    public void gyroRms_detectsShakeAndRecoveriesWithHysteresis() {
        StabilityMeter meter = new StabilityMeter();
        assertTrue(meter.isStable());

        long ts = 1_000_000_000L;
        for (int i = 0; i < 6; i++) {
            meter.pushGyro(0.02f, 0.01f, 0.02f, ts + i * 30_000_000L);
        }
        assertTrue(meter.isStable());

        // Strong hand tremor enters UNSTABLE
        long shakeStart = ts + 300_000_000L;
        for (int i = 0; i < 10; i++) {
            meter.pushGyro(0.45f, 0.25f, 0.15f, shakeStart + i * 50_000_000L);
        }
        assertFalse(meter.isStable());
        assertTrue(meter.getInstabilityRatio() > 0.9f);

        // Mild reduction to 0.18 rad/s stays UNSTABLE (inside 0.15..0.24 hysteresis band)
        long midStart = shakeStart + 1_000_000_000L;
        for (int i = 0; i < 12; i++) {
            meter.pushGyro(0.18f, 0f, 0f, midStart + i * 50_000_000L);
        }
        assertFalse(meter.isStable());

        // True calm <= 0.15 rad/s recovers STABLE
        long calmStart = midStart + 1_000_000_000L;
        for (int i = 0; i < 12; i++) {
            meter.pushGyro(0.03f, 0.02f, 0.01f, calmStart + i * 50_000_000L);
        }
        assertTrue(meter.isStable());
    }

    @Test
    public void accelerometerFallback_filtersConstantGravityAndDetectsMotion() {
        StabilityMeter meter = new StabilityMeter();
        long ts = 2_000_000_000L;

        // Constant 9.81 m/s^2 gravity only -> high-pass removes gravity, stays STABLE
        for (int i = 0; i < 12; i++) {
            meter.pushAccelerometer(0f, 9.81f, 0f, ts + i * 30_000_000L);
        }
        assertTrue(meter.isStable());

        // Sharp alternating lateral shakes -> triggers UNSTABLE
        long shakeTs = ts + 500_000_000L;
        for (int i = 0; i < 14; i++) {
            float spike = (i % 2 == 0) ? 2.5f : -2.5f;
            meter.pushAccelerometer(spike, 9.81f, 0.4f, shakeTs + i * 35_000_000L);
        }
        assertFalse(meter.isStable());

        // Non-monotonic backward accelerometer timestamp resets window cleanly
        meter.pushAccelerometer(0f, 9.81f, 0f, 100_000_000L);
        assertTrue(meter.isStable());
    }

    @Test
    public void backwardTimestampAndModeReset_clearStaleInstabilityWindow() {
        StabilityMeter meter = new StabilityMeter();
        long ts = 5_000_000_000L;
        for (int i = 0; i < 6; i++) {
            meter.pushGyro(0.5f, 0.4f, 0.3f, ts + i * 40_000_000L);
        }
        assertFalse(meter.isStable());

        // Timestamp jump backwards clears prior high-energy window instead of leaking past maxNs
        long jumpedBack = 100_000_000L;
        for (int i = 0; i < 4; i++) {
            meter.pushGyro(0.01f, 0.01f, 0f, jumpedBack + i * 40_000_000L);
        }
        assertTrue(meter.isStable());

        // Unstable gyro cleared via reset() allows immediate accelerometer fallback
        for (int i = 0; i < 6; i++) {
            meter.pushGyro(0.5f, 0.5f, 0f, jumpedBack + 500_000_000L + i * 40_000_000L);
        }
        assertFalse(meter.isStable());
        meter.reset();
        for (int i = 0; i < 6; i++) {
            meter.pushAccelerometer(0f, 9.81f, 0f, jumpedBack + 900_000_000L + i * 40_000_000L);
        }
        assertTrue(meter.isStable());
    }

    @Test
    public void shakeDetector_requiresTwoSharpPeaksAndRespectsCooldown() {
        StabilityMeter.ShakeDetector sd = new StabilityMeter.ShakeDetector();
        long t = 1_000_000_000L;
        assertFalse(sd.pushAccelerometer(0f, 9.81f, 0f, t));
        // Gentle sway never triggers shake
        for (int i = 1; i <= 8; i++) {
            assertFalse(sd.pushAccelerometer(1.5f, 9.81f, 0.5f, t + i * 40_000_000L));
        }
        // Two brisk peaks within 450ms trigger once
        assertFalse(sd.pushAccelerometer(22f, 9.81f, 0f, t + 400_000_000L));
        assertFalse(sd.pushAccelerometer(0f, 9.81f, 0f, t + 460_000_000L));
        assertTrue(sd.pushAccelerometer(-24f, 9.81f, 0f, t + 520_000_000L));
        // Immediate repeat inside 1.2s cooldown stays suppressed
        assertFalse(sd.pushAccelerometer(25f, 9.81f, 0f, t + 700_000_000L));
        assertFalse(sd.pushAccelerometer(0f, 9.81f, 0f, t + 760_000_000L));
        assertFalse(sd.pushAccelerometer(-25f, 9.81f, 0f, t + 820_000_000L));
    }
}
