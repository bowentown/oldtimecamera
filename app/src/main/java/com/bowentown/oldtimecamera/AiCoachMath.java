package com.bowentown.oldtimecamera;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java protocol, prompt, parsing, and sanitization helpers for the
 * BYOK (Bring Your Own Key) AI photography advisor. Keeps network payloads,
 * DeepSeek/OpenAI-compatible endpoint normalization, and strict response parsing
 * isolated and fully testable on the JVM without Android dependencies.
 */
public final class AiCoachMath {

    public enum ProviderPreset {
        DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-flash", true),
        QWEN("Qwen VL", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-vl-plus", true),
        OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", true),
        CUSTOM("Custom", "https://api.deepseek.com", "deepseek-flash", true);

        public final String label;
        public final String defaultBaseUrl;
        public final String defaultModel;
        public final boolean defaultVision;

        ProviderPreset(String label, String defaultBaseUrl, String defaultModel, boolean defaultVision) {
            this.label = label;
            this.defaultBaseUrl = defaultBaseUrl;
            this.defaultModel = defaultModel;
            this.defaultVision = defaultVision;
        }

        public static ProviderPreset fromName(String name) {
            if (name != null) {
                for (ProviderPreset p : values()) {
                    if (p.name().equals(name)) return p;
                }
            }
            return DEEPSEEK;
        }
    }

    public static final class AiCritique {
        public final String strength;
        public final String issue;
        public final String nextAction;

        public AiCritique(String strength, String issue, String nextAction) {
            this.strength = cleanText(strength, 72);
            this.issue = cleanText(issue, 72);
            this.nextAction = cleanText(nextAction, 72);
        }

        public boolean isEmpty() {
            return strength.isEmpty() && issue.isEmpty() && nextAction.isEmpty();
        }
    }

    public static final class AiRecipeProposal {
        public final String basePresetId;
        public final String name;
        public final FilmRecipe recipe;
        public final boolean blackMist;
        public final String reason;

        public AiRecipeProposal(String basePresetId, String name, FilmRecipe recipe,
                                boolean blackMist, String reason) {
            this.basePresetId = basePresetId;
            this.name = cleanText(name, 14);
            this.recipe = recipe != null ? recipe : FilmRecipe.DEFAULT;
            this.blackMist = blackMist;
            this.reason = cleanText(reason, 64);
        }
    }

    private AiCoachMath() {}

    /**
     * Resolves a user-supplied base URL into a chat-completions endpoint URL.
     * Supports DeepSeek root URLs ({@code https://api.deepseek.com -> .../chat/completions}),
     * OpenAI-style {@code .../v1} URLs, and full endpoint overrides.
     */
    public static String normalizeEndpointUrl(String rawBaseUrl) {
        if (rawBaseUrl == null) return "";
        String s = rawBaseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) return "";

        String lower = s.toLowerCase(Locale.US);
        if (lower.endsWith("/chat/completions")) {
            return s;
        }
        if (lower.endsWith("/v1") || lower.endsWith("/beta")) {
            return s + "/chat/completions";
        }
        int schemeIdx = lower.indexOf("://");
        if (schemeIdx >= 0) {
            int pathSlash = lower.indexOf('/', schemeIdx + 3);
            if (pathSlash < 0) {
                String host = lower.substring(schemeIdx + 3);
                if ("api.deepseek.com".equals(host)) {
                    return s + "/chat/completions";
                }
                return s + "/v1/chat/completions";
            }
        }
        return s + "/chat/completions";
    }

    /** Requires HTTPS to protect the user's API key in transit and match Android default TLS rules. */
    public static boolean isAllowedEndpointUrl(String endpointUrl) {
        if (endpointUrl == null) return false;
        String lower = endpointUrl.trim().toLowerCase(Locale.US);
        if (lower.startsWith("https://") && lower.length() > "https://".length()) {
            return true;
        }
        return lower.startsWith("http://localhost")
                || lower.startsWith("http://127.0.0.1");
    }

    public static String maskApiKey(String key) {
        if (key == null) return "";
        String s = key.trim();
        if (s.isEmpty()) return "";
        if (s.length() <= 8) return "••••";
        int prefixLen = s.startsWith("sk-") && s.length() >= 12 ? 5 : 3;
        return s.substring(0, prefixLen) + "•••" + s.substring(s.length() - 4);
    }

    public static String buildCritiqueSystemPrompt(boolean zh) {
        if (zh) {
            return "你是一位克制、专业的街头与人像胶片摄影教练。只讲具体的构图、用光、曝光或机位细节，禁止客套和空泛夸奖。"
                    + "必须只输出严格 JSON（不要 markdown 代码块）："
                    + "{\"strength\":\"不超过22字的一个具体亮点\",\"issue\":\"不超过22字的一个具体遗憾（若无明显问题可写无明显短板）\",\"nextAction\":\"不超过24字的一句下一次实操改进\"}";
        }
        return "You are a concise, practical film photography coach. Focus strictly on composition, lighting, exposure, or camera angle with zero fluff. "
                + "Output ONLY raw JSON: "
                + "{\"strength\":\"<=12 words concrete strength\",\"issue\":\"<=12 words main flaw or Minor none\",\"nextAction\":\"<=14 words next actionable step\"}";
    }

    public static String buildCritiqueUserContext(String exifSummary, String diagLine,
                                                  boolean withImage, boolean zh) {
        StringBuilder sb = new StringBuilder();
        if (zh) {
            sb.append(withImage
                    ? "请结合这张成片画面与下方拍摄参数，给出 3 句极简复盘。"
                    : "当前仅传参数（未附图片），请基于参数与测光结论给出 3 句构图/曝光复盘。");
        } else {
            sb.append(withImage
                    ? "Review this film print together with its capture metadata in 3 brief points."
                    : "Metadata-only review (no image attached). Give 3 brief technical takeaways.");
        }
        if (exifSummary != null && !exifSummary.trim().isEmpty()) {
            sb.append("\nEXIF: ").append(exifSummary.trim());
        }
        if (diagLine != null && !diagLine.trim().isEmpty()) {
            sb.append("\nMetering/Coach: ").append(diagLine.trim());
        }
        return sb.toString();
    }

    public static String buildInspireSystemPrompt(boolean zh) {
        if (zh) {
            return "你是复古相机取景助手。根据当前取景画面（或测光数据），只给出1句立刻能执行的机位、视角、用光或主体走位建议。"
                    + "要求：直接给动作，不超过24个汉字，不要标点堆砌，不要客套。";
        }
        return "You are an on-set vintage viewfinder coach. Give ONE immediate framing, angle, or light tip based on the preview. "
                + "Maximum 18 words, plain text, no greeting.";
    }

    public static String buildPreviewTelemetrySummary(PreviewStats stats, float tiltDeg,
                                                      String presetName, String aspectLabel,
                                                      boolean zh) {
        StringBuilder sb = new StringBuilder();
        if (presetName != null && !presetName.isEmpty()) {
            sb.append(zh ? "胶片:" : "Stock:").append(presetName);
        }
        if (aspectLabel != null && !aspectLabel.isEmpty()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(zh ? "画幅:" : "Aspect:").append(aspectLabel);
        }
        if (stats != null && stats.width > 0 && stats.height > 0) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(String.format(Locale.US,
                    "Luma:%.2f BrightClip:%.0f%% DarkClip:%.0f%% Center:%.2f Edge:%.2f SalientX:%.2f/%+.1fEV",
                    stats.meanLuma, stats.clippedHighlights * 100f, stats.crushedShadows * 100f,
                    stats.centerLuma, stats.borderLuma, stats.saliencyX,
                    FilmRenderMath.meterNeedleStops(stats)));
        }
        if (Math.abs(tiltDeg) >= 1f) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(String.format(Locale.US, "Tilt:%+.1fdeg", tiltDeg));
        }
        return sb.toString();
    }

    public static String buildRecipeSystemPrompt(boolean zh) {
        StringBuilder stocks = new StringBuilder();
        FilmPreset[] all = FilmPresets.all();
        for (int i = 0; i < all.length; i++) {
            if (i > 0) stocks.append("; ");
            stocks.append(all[i].id).append('(').append(all[i].shortNote).append(')');
        }
        if (zh) {
            return "你是专业胶片调色师。根据用户的画面氛围描述，从以下内置胶片基底中挑选最接近的一个并微调三项参数与黑柔光开关。"
                    + "\n可选 basePresetId: " + stocks
                    + "\n规则：intensity∈[0.25,1.0](浓度), grainScale∈[0.0,2.0](颗粒倍率), warmth∈[-1.0,1.0](负冷正暖), blackMist=true/false(高光黑柔漫射), name不超过6个汉字或12字符。"
                    + "\n只输出严格 JSON："
                    + "{\"basePresetId\":\"...\",\"name\":\"...\",\"intensity\":0.85,\"grainScale\":1.2,\"warmth\":0.2,\"blackMist\":false,\"reason\":\"不超过18字的调色思路\"}";
        }
        return "You are a film colorist. Pick the closest base film stock below and tune a custom recipe matching the user's mood."
                + "\nValid basePresetId values: " + stocks
                + "\nRanges: intensity[0.25..1.0], grainScale[0.0..2.0], warmth[-1.0..1.0], blackMist(bool), name<=12 chars."
                + "\nOutput ONLY raw JSON: "
                + "{\"basePresetId\":\"...\",\"name\":\"...\",\"intensity\":0.85,\"grainScale\":1.2,\"warmth\":0.2,\"blackMist\":false,\"reason\":\"<=12 words\"}";
    }

    public static AiCritique parseCritique(String raw, boolean zh) {
        String cleaned = stripThinkingAndFences(raw);
        if (cleaned.isEmpty()) {
            return new AiCritique("", "", "");
        }
        String jsonObj = extractFirstJsonObject(cleaned);
        if (!jsonObj.isEmpty()) {
            Map<String, String> map = parseFlatJsonObject(jsonObj);
            String s = cleanText(firstNonEmpty(map, "strength", "good", "highlight"), 72);
            String i = cleanText(firstNonEmpty(map, "issue", "flaw", "weakness"), 72);
            String n = cleanText(firstNonEmpty(map, "nextAction", "next", "tip", "suggestion"), 72);
            if (!s.isEmpty() || !i.isEmpty() || !n.isEmpty()) {
                return new AiCritique(s, i, n);
            }
        }
        // Graceful fallback if an endpoint replied in plain text rather than JSON.
        String[] rawLines = cleaned.replace("\r", "").split("\n");
        List<String> lines = new ArrayList<>();
        for (String line : rawLines) {
            String t = cleanText(line.replaceAll("^[-*•0-9.、\\s]+", ""), 72);
            if (!t.isEmpty()) lines.add(t);
            if (lines.size() >= 3) break;
        }
        if (lines.isEmpty()) {
            return new AiCritique(cleanText(cleaned, 72), "", "");
        }
        String s = lines.get(0);
        String i = lines.size() > 1 ? lines.get(1) : (zh ? "画面整体成立" : "Clean overall frame");
        String n = lines.size() > 2 ? lines.get(2) : (zh ? "保持当前节奏多拍一张微调角度" : "Shoot one more angle slightly closer");
        return new AiCritique(s, i, n);
    }

    public static AiRecipeProposal parseRecipeProposal(String raw, String fallbackPresetId, boolean zh) {
        String cleaned = stripThinkingAndFences(raw);
        String jsonObj = extractFirstJsonObject(cleaned);
        if (jsonObj.isEmpty()) return null;

        Map<String, String> map = parseFlatJsonObject(jsonObj);
        if (map.isEmpty()) return null;

        String requestedId = firstNonEmpty(map, "basePresetId", "presetId", "base");
        String validPresetId = resolveValidPresetId(requestedId, fallbackPresetId);
        FilmPreset base = FilmPresets.all()[FilmPresets.indexOfId(validPresetId)];

        float intensity = parseClampedFloat(map.get("intensity"), 0.85f,
                FilmRecipe.MIN_INTENSITY, FilmRecipe.MAX_INTENSITY);
        float grainScale = parseClampedFloat(map.get("grainScale"), 1.0f,
                FilmRecipe.MIN_GRAIN_SCALE, FilmRecipe.MAX_GRAIN_SCALE);
        float warmth = parseClampedFloat(map.get("warmth"), 0.0f,
                FilmRecipe.MIN_WARMTH, FilmRecipe.MAX_WARMTH);
        boolean blackMist = parseBoolean(map.get("blackMist"), false);

        String name = cleanText(map.get("name"), 14);
        if (name.isEmpty()) {
            name = zh ? ("AI · " + base.name) : ("AI " + base.isoTag);
        }
        String reason = cleanText(firstNonEmpty(map, "reason", "note", "desc"), 64);
        return new AiRecipeProposal(validPresetId, name,
                new FilmRecipe(intensity, grainScale, warmth), blackMist, reason);
    }

    public static String parseInspireTip(String raw) {
        String cleaned = stripThinkingAndFences(raw);
        if (cleaned.isEmpty()) return "";
        cleaned = cleaned.replace('\n', ' ').replace('\r', ' ').trim();
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("“") && cleaned.endsWith("”"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleanText(cleaned, 48);
    }

    // ------------------------------------------------------------------ persistence codec

    public static String encodeCritiqueLog(Map<String, AiCritique> map) {
        if (map == null || map.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, AiCritique> e : map.entrySet()) {
            String key = cleanDelim(e.getKey());
            AiCritique c = e.getValue();
            if (key.isEmpty() || c == null || c.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(key).append('\t')
                    .append(cleanDelim(c.strength)).append('\t')
                    .append(cleanDelim(c.issue)).append('\t')
                    .append(cleanDelim(c.nextAction));
        }
        return sb.toString();
    }

    public static Map<String, AiCritique> decodeCritiqueLog(String raw) {
        Map<String, AiCritique> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t", -1);
            if (parts.length < 4 || parts[0].isEmpty()) continue;
            AiCritique c = new AiCritique(parts[1], parts[2], parts[3]);
            if (!c.isEmpty()) {
                out.put(parts[0], c);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ JSON & text utilities

    public static String stripThinkingAndFences(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // Remove <think>...</think> blocks emitted by reasoning models.
        while (true) {
            int start = s.indexOf("<think>");
            int end = s.indexOf("</think>");
            if (start >= 0 && end > start) {
                s = (s.substring(0, start) + " " + s.substring(end + "</think>".length())).trim();
            } else {
                break;
            }
        }
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl >= 0) {
                s = s.substring(firstNl + 1).trim();
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3).trim();
            }
        }
        return s.trim();
    }

    public static String extractFirstJsonObject(String s) {
        if (s == null || s.isEmpty()) return "";
        int start = s.indexOf('{');
        if (start < 0) return "";
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    public static Map<String, String> parseFlatJsonObject(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        if (json == null) return map;
        String s = json.trim();
        if (s.length() < 2 || s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}') {
            return map;
        }
        int i = 1;
        int n = s.length() - 1;
        while (i < n) {
            i = skipWsAndCommas(s, i, n);
            if (i >= n || s.charAt(i) != '"') break;
            int[] next = new int[1];
            String key = readJsonString(s, i, n, next);
            i = skipWs(s, next[0], n);
            if (i >= n || s.charAt(i) != ':') break;
            i = skipWs(s, i + 1, n);
            if (i >= n) break;

            String val;
            char vc = s.charAt(i);
            if (vc == '"') {
                val = readJsonString(s, i, n, next);
                i = next[0];
            } else {
                int start = i;
                while (i < n && s.charAt(i) != ',' && s.charAt(i) != '}') {
                    i++;
                }
                val = s.substring(start, i).trim();
                if ("null".equals(val)) val = "";
            }
            if (!key.isEmpty()) {
                map.put(key, val);
            }
        }
        return map;
    }

    private static int skipWs(String s, int i, int n) {
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int skipWsAndCommas(String s, int i, int n) {
        while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
        return i;
    }

    private static String readJsonString(String s, int quoteIdx, int limit, int[] outNext) {
        StringBuilder sb = new StringBuilder();
        int i = quoteIdx + 1;
        while (i < limit) {
            char c = s.charAt(i++);
            if (c == '"') {
                outNext[0] = i;
                return sb.toString();
            }
            if (c == '\\' && i < limit) {
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 <= limit) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                sb.append("\\u");
                            }
                        }
                        break;
                    default:
                        sb.append(esc);
                        break;
                }
            } else {
                sb.append(c);
            }
        }
        outNext[0] = limit;
        return sb.toString();
    }

    private static String resolveValidPresetId(String candidate, String fallbackId) {
        if (candidate != null) {
            String trimmed = candidate.trim().toLowerCase(Locale.US);
            for (FilmPreset p : FilmPresets.all()) {
                if (p.id.equals(trimmed)) return p.id;
            }
        }
        if (fallbackId != null) {
            for (FilmPreset p : FilmPresets.all()) {
                if (p.id.equals(fallbackId)) return p.id;
            }
        }
        return FilmPresets.all()[FilmPresets.DEFAULT_INDEX].id;
    }

    private static float parseClampedFloat(String raw, float fallback, float min, float max) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try {
            float v = Float.parseFloat(raw.trim());
            if (Float.isNaN(v) || Float.isInfinite(v)) return fallback;
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null) return fallback;
        String s = raw.trim().toLowerCase(Locale.US);
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) return false;
        return fallback;
    }

    private static String firstNonEmpty(Map<String, String> map, String... keys) {
        for (String k : keys) {
            String v = map.get(k);
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return "";
    }

    private static String cleanDelim(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String cleanText(String s, int maxLen) {
        if (s == null) return "";
        String t = cleanDelim(s);
        if (t.length() > maxLen) {
            return t.substring(0, maxLen).trim();
        }
        return t;
    }
}
