package com.bowentown.oldtimecamera;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AiCoachMathTest {

    @Test
    public void normalizeEndpointUrl_andDeepSeekDetection_handleAllFormats() {
        assertEquals("https://api.deepseek.com/chat/completions",
                AiCoachMath.normalizeEndpointUrl("https://api.deepseek.com"));
        assertEquals("https://api.deepseek.com/chat/completions",
                AiCoachMath.normalizeEndpointUrl("  https://api.deepseek.com/  "));
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                AiCoachMath.normalizeEndpointUrl("https://api.deepseek.com/v1"));
        assertEquals("https://api.openai.com/v1/chat/completions",
                AiCoachMath.normalizeEndpointUrl("https://api.openai.com/v1/"));
        assertEquals("https://api.openai.com/v1/chat/completions",
                AiCoachMath.normalizeEndpointUrl("https://api.openai.com"));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                AiCoachMath.normalizeEndpointUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"));
        assertEquals("", AiCoachMath.normalizeEndpointUrl("  "));

        assertTrue(AiCoachMath.isDeepSeekEndpoint("https://api.deepseek.com/chat/completions"));
        assertFalse(AiCoachMath.isDeepSeekEndpoint("https://api.openai.com/v1/chat/completions"));
    }

    @Test
    public void endpointSchemeAndKeyMasking_enforceTransportSafetyAndPrivacy() {
        assertTrue(AiCoachMath.isAllowedEndpointUrl("https://api.deepseek.com/chat/completions"));
        assertTrue(AiCoachMath.isAllowedEndpointUrl("http://localhost:11434/v1/chat/completions"));
        assertFalse(AiCoachMath.isAllowedEndpointUrl("http://api.deepseek.com/chat/completions"));
        assertFalse(AiCoachMath.isAllowedEndpointUrl(""));

        assertEquals("", AiCoachMath.maskApiKey(null));
        assertEquals("••••", AiCoachMath.maskApiKey("short"));
        String masked = AiCoachMath.maskApiKey("sk-1234567890abcdef");
        assertEquals("sk-12•••cdef", masked);
        assertFalse(masked.contains("6789"));
    }

    @Test
    public void parseAdvice_parsesStructuredCoachingAndValidatesCameraSuggestions() {
        String rawJson = "<think>internal reasoning ignored</think>\n"
                + "```json\n"
                + "{\"diagnosis\":\"后方强光顶板干扰且低机位仰拍\","
                + "\"action\":\"手机举至眉心微俯拍，人向左转35°避开格子墙\","
                + "\"recommendedPresetId\":\"softbw400\","
                + "\"evDelta\":-0.7,"
                + "\"recommendedGrid\":\"THIRDS\"}\n"
                + "```";
        AiCoachMath.AiAdvice a = AiCoachMath.parseAdvice(rawJson, true);
        assertEquals("后方强光顶板干扰且低机位仰拍", a.diagnosis);
        assertEquals("手机举至眉心微俯拍，人向左转35°避开格子墙", a.action);
        assertEquals("softbw400", a.recommendedPresetId);
        assertEquals(-0.7f, a.evDelta, 0.01f);
        assertEquals(CompositionMath.GridMode.THIRDS, a.recommendedGrid);
        assertTrue(a.hasCameraTweaks("warm400", CompositionMath.GridMode.OFF));
        assertFalse(a.hasActionablePresetChange("softbw400"));

        // Unknown stock ID is dropped instead of switching randomly; evDelta is clamped cleanly.
        String bogus = "{\"diagnosis\":\"逆光过暗\",\"action\":\"侧转迎光\",\"recommendedPresetId\":\"fake_stock\",\"evDelta\":9.5,\"recommendedGrid\":\"BOGUS\"}";
        AiCoachMath.AiAdvice sanitized = AiCoachMath.parseAdvice(bogus, true);
        assertEquals("", sanitized.recommendedPresetId);
        assertEquals(2.0f, sanitized.evDelta, 0.01f);
        assertNull(sanitized.recommendedGrid);

        // Plain-text fallback splits cleanly into diagnosis & action without throwing.
        AiCoachMath.AiAdvice plain = AiCoachMath.parseAdvice(
                "1. Bright glare behind glasses\n2. Lower chin and step sideways", false);
        assertEquals("Bright glare behind glasses", plain.diagnosis);
        assertEquals("Lower chin and step sideways", plain.action);
    }

    @Test
    public void parseRecipeProposal_clampsRangesAndRejectsUnknownBaseStocks() {
        String raw = "```json\n"
                + "{\"basePresetId\":\"tungsten800\",\"name\":\"重庆雨夜霓虹\","
                + "\"intensity\":2.5,\"grainScale\":1.45,\"warmth\":-0.35,"
                + "\"blackMist\":true,\"reason\":\"冷青暗部叠黑柔光\"}\n"
                + "```";
        AiCoachMath.AiRecipeProposal p = AiCoachMath.parseRecipeProposal(raw, "warm400", true);
        assertNotNull(p);
        assertEquals("tungsten800", p.basePresetId);
        assertEquals("重庆雨夜霓虹", p.name);
        assertEquals(1.0f, p.recipe.intensity, 0.001f);
        assertEquals(1.45f, p.recipe.grainScale, 0.001f);
        assertEquals(-0.35f, p.recipe.warmth, 0.001f);
        assertTrue(p.blackMist);

        String badStock = "{\"basePresetId\":\"kodak_fake\",\"name\":\"Test\",\"intensity\":0.5,\"grainScale\":-1,\"warmth\":5,\"blackMist\":false}";
        AiCoachMath.AiRecipeProposal fallback = AiCoachMath.parseRecipeProposal(badStock, "coolcast400", false);
        assertNotNull(fallback);
        assertEquals("coolcast400", fallback.basePresetId);
        assertEquals(0.0f, fallback.recipe.grainScale, 0.001f);
        assertEquals(1.0f, fallback.recipe.warmth, 0.001f);
        assertFalse(fallback.blackMist);
        assertNull(AiCoachMath.parseRecipeProposal("not json at all", "warm400", false));
    }

    @Test
    public void adviceLogEncoding_roundTripsAllFields() {
        Map<String, AiCoachMath.AiAdvice> log = new LinkedHashMap<>();
        log.put("content://media/external/images/media/101",
                new AiCoachMath.AiAdvice("背景高光略杂", "向右移一步避开灯箱", "warm400", -0.3f,
                        CompositionMath.GridMode.THIRDS));
        String encoded = AiCoachMath.encodeAdviceLog(log);
        Map<String, AiCoachMath.AiAdvice> decoded = AiCoachMath.decodeAdviceLog(encoded);
        assertEquals(1, decoded.size());
        AiCoachMath.AiAdvice restored = decoded.get("content://media/external/images/media/101");
        assertNotNull(restored);
        assertEquals("背景高光略杂", restored.diagnosis);
        assertEquals("向右移一步避开灯箱", restored.action);
        assertEquals("warm400", restored.recommendedPresetId);
        assertEquals(-0.3f, restored.evDelta, 0.01f);
        assertEquals(CompositionMath.GridMode.THIRDS, restored.recommendedGrid);
    }

    @Test
    public void promptBuilders_includeAllStocksAndCameraContext() {
        String coachSys = AiCoachMath.buildViewfinderCoachSystemPrompt(true);
        String directorSys = AiCoachMath.buildDirectorSystemPrompt(true);
        String recipeSys = AiCoachMath.buildRecipeSystemPrompt(true);
        for (FilmPreset p : FilmPresets.all()) {
            assertTrue("Missing preset id in coach: " + p.id, coachSys.contains(p.id));
            assertTrue("Missing preset id in director: " + p.id, directorSys.contains(p.id));
            assertTrue("Missing preset id in recipe: " + p.id, recipeSys.contains(p.id));
        }
        PreviewStats stats = new PreviewStats(32, 24, 0.52f, 0.04f, 0.05f, 0.50f, 0.48f,
                0.5f, 0.5f, 0.5f, 0.25f, 0.5f, 0.5f, 0.5f);
        String ctx = AiCoachMath.buildViewfinderUserContext(
                stats, -2.5f, "warm400", "暖肤 400", -0.3f, "3:4", true, true, true,
                new CompositionMath.Guide3DCue(CompositionMath.Step3D.MOVE_LEFT, 0.5f, false, false,
                        0.4f, 0.4f, 0f, 0f, 0f, 0f));
        assertTrue(ctx.contains("warm400"));
        assertTrue(ctx.contains("lens=front"));
        assertTrue(ctx.contains("needle="));
        assertTrue(ctx.contains("tilt="));
        assertTrue(ctx.contains("LiveFramingStep: MOVE_LEFT"));

        String dirCtx = AiCoachMath.buildDirectorUserContext(
                stats, 1.5f, -6.0f, "warm400", "暖肤 400", -0.3f, "3:4", false, true, true, true);
        assertTrue(dirCtx.contains("currentElev=-6.0deg"));
        assertTrue(dirCtx.contains("hasFace=true"));
    }

    @Test
    public void parseDirectorPlan_clampsGeometryAndHandlesFencesAndErrors() {
        String fenced = "<think>hmm</think>\n```json\n"
                + "{\"instruction\":\"左移两步避开右侧杂物，手机举高微俯拍\","
                + "\"poseTip\":\"看镜头左侧微侧脸\","
                + "\"subjectX\":0.01,\"subjectY\":0.99,"
                + "\"targetX\":0.05,\"targetY\":0.92,\"targetSize\":0.80,"
                + "\"targetElevationDeg\":-40.0,\"recommendedPresetId\":\"daylight200\",\"evDelta\":-3.0}"
                + "\n```";
        AiCoachMath.AiDirectorPlan plan = AiCoachMath.parseDirectorPlan(fenced, true, true);
        assertNotNull(plan);
        assertEquals("左移两步避开右侧杂物，手机举高微俯拍", plan.instruction);
        assertEquals("看镜头左侧微侧脸", plan.poseTip);
        assertEquals("左移两步避开右侧杂物，手机举高微俯拍 · 看镜头左侧微侧脸", plan.displayBannerText());
        assertEquals("daylight200", plan.recommendedPresetId);
        assertEquals(-2.0f, plan.evDelta, 0.01f);
        assertEquals(0.05f, plan.subjectX, 0.001f);
        assertEquals(0.95f, plan.subjectY, 0.001f);
        assertEquals(0.15f, plan.target.targetX, 0.001f);
        assertEquals(0.85f, plan.target.targetY, 0.001f);
        assertEquals(0.58f, plan.target.targetSize, 0.001f);
        assertEquals(-35.0f, plan.target.targetElevationDeg, 0.001f);

        AiCoachMath.AiDirectorPlan plainFallback = AiCoachMath.parseDirectorPlan("站定往左走一步", false, true);
        assertNotNull(plainFallback);
        assertEquals("站定往左走一步", plainFallback.instruction);
        assertEquals(0.50f, plainFallback.target.targetX, 0.001f);
        assertTrue(Float.isNaN(plainFallback.subjectX));

        assertNull(AiCoachMath.parseDirectorPlan("<think>reasoning only</think>", false, true));
    }
}
