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
    public void normalizeEndpointUrl_handlesDeepSeekOpenAiQwenAndFullOverrides() {
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
        assertEquals("https://proxy.example.com/v2/chat/completions",
                AiCoachMath.normalizeEndpointUrl("https://proxy.example.com/v2/chat/completions"));
        assertEquals("", AiCoachMath.normalizeEndpointUrl("  "));
    }

    @Test
    public void endpointSchemeAndKeyMasking_enforceTransportSafetyAndPrivacy() {
        assertTrue(AiCoachMath.isAllowedEndpointUrl("https://api.deepseek.com/chat/completions"));
        assertTrue(AiCoachMath.isAllowedEndpointUrl("http://localhost:11434/v1/chat/completions"));
        assertTrue(AiCoachMath.isAllowedEndpointUrl("http://127.0.0.1:8080/v1/chat/completions"));
        assertFalse(AiCoachMath.isAllowedEndpointUrl("http://api.deepseek.com/chat/completions"));
        assertFalse(AiCoachMath.isAllowedEndpointUrl(""));

        assertEquals("", AiCoachMath.maskApiKey(null));
        assertEquals("••••", AiCoachMath.maskApiKey("short"));
        String masked = AiCoachMath.maskApiKey("sk-1234567890abcdef");
        assertEquals("sk-12•••cdef", masked);
        assertFalse(masked.contains("6789"));
    }

    @Test
    public void parseCritique_stripsThinkingAndMarkdownFencesAndFallsBackToLines() {
        String rawJson = "<think>Evaluating composition...</think>\n"
                + "```json\n"
                + "{\"strength\":\"三分线落点清晰\",\"issue\":\"头顶\\n略紧\",\"nextAction\":\"退后一步再拍\\u2713\"}\n"
                + "```";
        AiCoachMath.AiCritique c = AiCoachMath.parseCritique(rawJson, true);
        assertEquals("三分线落点清晰", c.strength);
        assertEquals("头顶 略紧", c.issue);
        assertEquals("退后一步再拍✓", c.nextAction);

        AiCoachMath.AiCritique plain = AiCoachMath.parseCritique(
                "1. Good side light on face\n2. Horizon tilts slightly\n3. Lower angle one step", false);
        assertEquals("Good side light on face", plain.strength);
        assertEquals("Horizon tilts slightly", plain.issue);
        assertEquals("Lower angle one step", plain.nextAction);
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

        // Unknown base preset ID falls back safely to fallbackPresetId instead of arbitrary default.
        String badStock = "{\"basePresetId\":\"kodak_portra_400\",\"name\":\"Test\",\"intensity\":0.5,\"grainScale\":-1,\"warmth\":5,\"blackMist\":false}";
        AiCoachMath.AiRecipeProposal fallback = AiCoachMath.parseRecipeProposal(badStock, "coolcast400", false);
        assertNotNull(fallback);
        assertEquals("coolcast400", fallback.basePresetId);
        assertEquals(0.0f, fallback.recipe.grainScale, 0.001f);
        assertEquals(1.0f, fallback.recipe.warmth, 0.001f);
        assertFalse(fallback.blackMist);

        assertNull(AiCoachMath.parseRecipeProposal("not json at all", "warm400", false));
    }

    @Test
    public void parseInspireTipAndCritiqueLog_roundTripCleanly() {
        String tip = AiCoachMath.parseInspireTip("<think>hmm</think>\n“低机位仰拍避开杂乱地面，主体靠右三分线。”");
        assertEquals("低机位仰拍避开杂乱地面，主体靠右三分线。", tip);
        assertTrue(tip.length() <= 48);

        Map<String, AiCoachMath.AiCritique> log = new LinkedHashMap<>();
        log.put("content://media/external/images/media/101",
                new AiCoachMath.AiCritique("光线通透", "高光微过", "降一格EV"));
        String encoded = AiCoachMath.encodeCritiqueLog(log);
        Map<String, AiCoachMath.AiCritique> decoded = AiCoachMath.decodeCritiqueLog(encoded);
        assertEquals(1, decoded.size());
        AiCoachMath.AiCritique restored = decoded.get("content://media/external/images/media/101");
        assertNotNull(restored);
        assertEquals("光线通透", restored.strength);
        assertEquals("高光微过", restored.issue);
        assertEquals("降一格EV", restored.nextAction);
    }

    @Test
    public void promptBuilders_includeAllStocksAndTelemetrySummary() {
        String prompt = AiCoachMath.buildRecipeSystemPrompt(true);
        for (FilmPreset p : FilmPresets.all()) {
            assertTrue("Missing preset id " + p.id, prompt.contains(p.id));
        }
        PreviewStats stats = new PreviewStats(32, 24, 0.52f, 0.04f, 0.05f, 0.50f, 0.48f,
                0.5f, 0.5f, 0.5f, 0.25f, 0.5f, 0.5f, 0.5f);
        String summary = AiCoachMath.buildPreviewTelemetrySummary(stats, -2.5f, "暖肤 400", "3:4", true);
        assertTrue(summary.contains("暖肤 400"));
        assertTrue(summary.contains("3:4"));
        assertTrue(summary.contains("EV"));
        assertTrue(summary.contains("Tilt:"));
    }
}
