package com.bowentown.oldtimecamera;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PracticeRollTest {

    private static PreviewStats makeStats(float meanL, float clip, float shadow, float centerL, float borderL) {
        return new PreviewStats(32, 24, meanL, clip, shadow, centerL, borderL,
                meanL, meanL, meanL, 0.25f, 0.5f, 0.5f, 0.5f);
    }

    @Test
    public void themesHaveSixBilingualStepsAndValidLabels() {
        for (PracticeRoll.Theme theme : PracticeRoll.Theme.values()) {
            assertFalse(PracticeRoll.themeTitle(theme, true).isEmpty());
            assertFalse(PracticeRoll.themeTitle(theme, false).isEmpty());
            assertFalse(PracticeRoll.themeShortLabel(theme, true).isEmpty());
            assertFalse(PracticeRoll.themeShortLabel(theme, false).isEmpty());
            for (int step = 0; step < PracticeRoll.ROLL_FRAMES; step++) {
                PracticeRoll.StepSpec spec = PracticeRoll.stepSpec(theme, step);
                assertNotNull(spec);
                assertFalse(spec.title(true).isEmpty());
                assertFalse(spec.coach(true).isEmpty());
                assertFalse(spec.coach(false).isEmpty());
            }
        }
    }

    @Test
    public void evaluateShot_followsOneChangeRuleByPriority() {
        PreviewStats balanced = makeStats(0.46f, 0.02f, 0.04f, 0.46f, 0.46f);
        PreviewStats overexposed = makeStats(0.84f, 0.22f, 0f, 0.84f, 0.84f);
        CompositionMath.FramingAdvice locked = new CompositionMath.FramingAdvice(
                true, true, 0.33f, 0.33f, 0.33f, 0.33f, 0.01f, false, false);
        CompositionMath.FramingAdvice tightHead = new CompositionMath.FramingAdvice(
                true, true, 0.33f, 0.14f, 0.33f, 0.33f, 0.19f, true, true);

        // 1. Shake takes top priority
        PracticeRoll.ShotDiagnostic shaken = PracticeRoll.evaluateShot(
                overexposed, locked, false, true, false, 0f, 0,
                false, false, CameraPrefs.ShootMode.SINGLE, PracticeRoll.Theme.COMP_BASICS, 0, true);
        assertEquals("防抖", shaken.badge);
        assertFalse(shaken.missionPassed);

        // 2. Highlight clipping beats framing nits
        PracticeRoll.ShotDiagnostic bright = PracticeRoll.evaluateShot(
                overexposed, tightHead, true, true, false, 0f, 0,
                false, false, CameraPrefs.ShootMode.SINGLE, PracticeRoll.Theme.LIGHT_METER, 0, true);
        assertEquals("高光", bright.badge);
        assertFalse(bright.missionPassed);

        // 3. Headroom warning fires on clean exposure when head hits top border
        PracticeRoll.ShotDiagnostic tight = PracticeRoll.evaluateShot(
                balanced, tightHead, true, true, false, 0f, 0,
                false, false, CameraPrefs.ShootMode.SINGLE, PracticeRoll.Theme.COMP_BASICS, 1, true);
        assertEquals("留白", tight.badge);
        assertFalse(tight.missionPassed);

        // 4. Clean locked shot passes composition mission cleanly
        PracticeRoll.ShotDiagnostic cleanComp = PracticeRoll.evaluateShot(
                balanced, locked, true, true, false, 0f, 0,
                false, false, CameraPrefs.ShootMode.SINGLE, PracticeRoll.Theme.COMP_BASICS, 0, true);
        assertEquals("构图 ✓", cleanComp.badge);
        assertTrue(cleanComp.missionPassed);
    }

    @Test
    public void diagnosticLogEncoding_roundTripsCleanly() {
        Map<String, PracticeRoll.ShotDiagnostic> map = new LinkedHashMap<>();
        map.put("content://media/external/images/media/42",
                new PracticeRoll.ShotDiagnostic(true, "构图 ✓", "落点稳健", "尝试低角度"));
        String encoded = PracticeRoll.encodeDiagnosticLog(map);
        Map<String, PracticeRoll.ShotDiagnostic> decoded = PracticeRoll.decodeDiagnosticLog(encoded);
        assertEquals(1, decoded.size());
        PracticeRoll.ShotDiagnostic d = decoded.get("content://media/external/images/media/42");
        assertNotNull(d);
        assertTrue(d.missionPassed);
        assertEquals("构图 ✓", d.badge);
        assertEquals("落点稳健", d.headline);
        assertEquals("尝试低角度", d.nextTip);
    }
}
