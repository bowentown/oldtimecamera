package com.bowentown.oldtimecamera;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExposureAdvisorTest {

    private static PreviewStats stats(float meanL, float clip, float shadow, float centerL, float borderL) {
        return new PreviewStats(32, 24, meanL, clip, shadow, centerL, borderL,
                meanL, meanL, meanL, 0.2f, 0.5f, 0.5f, 0.5f);
    }

    @Test
    public void requiresTemporalStreakAndHysteresisToAvoidFlicker() {
        ExposureAdvisor advisor = new ExposureAdvisor();

        // Balanced frame -> NONE
        assertEquals(ExposureAdvisor.Advice.NONE,
                advisor.evaluate(stats(0.48f, 0.01f, 0.05f, 0.48f, 0.48f), 100L));

        // Single bright spike shouldn't trigger yet
        assertEquals(ExposureAdvisor.Advice.NONE,
                advisor.evaluate(stats(0.84f, 0.22f, 0f, 0.84f, 0.84f), 400L));

        // Second consecutive bright frame -> triggers OVEREXPOSED (-2 EV steps)
        assertEquals(ExposureAdvisor.Advice.OVEREXPOSED,
                advisor.evaluate(stats(0.84f, 0.22f, 0f, 0.84f, 0.84f), 700L));
        assertTrue(ExposureAdvisor.Advice.OVEREXPOSED.suggestedEvDelta < 0);

        // Mild improvement inside hysteresis band (meanL=0.75) stays OVEREXPOSED
        assertEquals(ExposureAdvisor.Advice.OVEREXPOSED,
                advisor.evaluate(stats(0.75f, 0.11f, 0f, 0.75f, 0.75f), 1000L));

        // Drops below exit band (meanL=0.60, clip=0.02) -> returns NONE
        assertEquals(ExposureAdvisor.Advice.NONE,
                advisor.evaluate(stats(0.60f, 0.02f, 0f, 0.60f, 0.60f), 1300L));
    }

    @Test
    public void detectsUnderexposedAndBacklitAndHonorsSnooze() {
        ExposureAdvisor advisor = new ExposureAdvisor();

        // Dark frame -> UNDEREXPOSED after streak
        assertEquals(ExposureAdvisor.Advice.NONE,
                advisor.evaluate(stats(0.14f, 0f, 0.70f, 0.14f, 0.14f), 100L));
        assertEquals(ExposureAdvisor.Advice.UNDEREXPOSED,
                advisor.evaluate(stats(0.14f, 0f, 0.70f, 0.14f, 0.14f), 400L));

        // Applying hint snoozes further tips for 5s
        advisor.onHintApplied(500L);
        assertEquals(ExposureAdvisor.Advice.NONE, advisor.getCurrentAdvice());
        assertEquals(ExposureAdvisor.Advice.NONE,
                advisor.evaluate(stats(0.12f, 0f, 0.80f, 0.12f, 0.12f), 2000L));

        // After snooze expires, backlit scene triggers BACKLIT over generic stats
        assertEquals(ExposureAdvisor.Advice.NONE,
                advisor.evaluate(stats(0.50f, 0.05f, 0.20f, 0.22f, 0.74f), 6000L));
        assertEquals(ExposureAdvisor.Advice.BACKLIT,
                advisor.evaluate(stats(0.50f, 0.05f, 0.20f, 0.22f, 0.74f), 6300L));
    }
}
