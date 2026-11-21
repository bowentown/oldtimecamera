package com.bowentown.oldtimecamera;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java protocol, prompt, parsing, and sanitization helpers for the
 * BYOK (Bring Your Own Key) AI photography mentor.
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

    /** Actionable coaching result usable both in viewfinder live advice and gallery lightbox review. */
    public static final class AiAdvice {
        public final String diagnosis;
        public final String action;
        public final String recommendedPresetId;
        public final float evDelta;
        public final CompositionMath.GridMode recommendedGrid;

        public AiAdvice(String diagnosis, String action, String recommendedPresetId,
                        float evDelta, CompositionMath.GridMode recommendedGrid) {
            this.diagnosis = cleanText(diagnosis, 110);
            this.action = cleanText(action, 140);
            this.recommendedPresetId = resolveOptionalPresetId(recommendedPresetId);
            this.evDelta = clampEv(evDelta);
            this.recommendedGrid = (recommendedGrid == CompositionMath.GridMode.OFF)
                    ? null : recommendedGrid;
        }

        public boolean isEmpty() {
            return diagnosis.isEmpty() && action.isEmpty();
        }

        public boolean hasActionablePresetChange(String activePresetId) {
            return !recommendedPresetId.isEmpty() && !recommendedPresetId.equals(activePresetId);
        }

        public boolean hasActionableGridChange(CompositionMath.GridMode activeGrid) {
            return recommendedGrid != null && recommendedGrid != activeGrid;
        }

        public boolean hasCameraTweaks(String activePresetId, CompositionMath.GridMode activeGrid) {
            return hasActionablePresetChange(activePresetId)
                    || Math.abs(evDelta) >= 0.25f
                    || hasActionableGridChange(activeGrid);
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

    /** Actionable AI Live Director plan pairing plain-language cue with a 3D target pose & frame. */
    public static final class AiDirectorPlan {
        public final String instruction;
        public final String poseTip;
        public final CompositionMath.DirectorTarget target;
        public final String recommendedPresetId;
        public final float evDelta;
        public final float subjectX;
        public final float subjectY;

        public AiDirectorPlan(String instruction, String poseTip,
                              CompositionMath.DirectorTarget target,
                              String recommendedPresetId, float evDelta) {
            this(instruction, poseTip, target, recommendedPresetId, evDelta, Float.NaN, Float.NaN);
        }

        public AiDirectorPlan(String instruction, String poseTip,
                              CompositionMath.DirectorTarget target,
                              String recommendedPresetId, float evDelta,
                              float subjectX, float subjectY) {
            this.instruction = cleanText(instruction, 48);
            this.poseTip = cleanText(poseTip, 26);
            this.target = target;
            this.recommendedPresetId = resolveOptionalPresetId(recommendedPresetId);
            this.evDelta = clampEv(evDelta);
            this.subjectX = (Float.isNaN(subjectX) || Float.isNaN(subjectY))
                    ? Float.NaN : Math.max(0.05f, Math.min(0.95f, subjectX));
            this.subjectY = (Float.isNaN(subjectX) || Float.isNaN(subjectY))
                    ? Float.NaN : Math.max(0.05f, Math.min(0.95f, subjectY));
        }

        public boolean isEmpty() {
            return instruction.isEmpty() && target == null;
        }

        public String displayBannerText() {
            if (instruction.isEmpty()) return poseTip;
            if (poseTip.isEmpty()) return instruction;
            return instruction + " · " + poseTip;
        }
    }

    private AiCoachMath() {}

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

    public static boolean isDeepSeekEndpoint(String endpointUrl) {
        return endpointUrl != null && endpointUrl.toLowerCase(Locale.US).contains("deepseek.com");
    }

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

    private static String stockCatalogText() {
        StringBuilder sb = new StringBuilder();
        FilmPreset[] all = FilmPresets.all();
        for (int i = 0; i < all.length; i++) {
            if (i > 0) sb.append("; ");
            sb.append(all[i].id).append(':').append(all[i].name)
                    .append('(').append(all[i].shortNote).append(')');
        }
        return sb.toString();
    }

    public static String buildViewfinderCoachSystemPrompt(boolean zh) {
        if (zh) {
            return "你是直率且精通人像与街头胶片摄影的现场教练。直接看当前取景画面与测光参数，绝不说空洞夸奖或套话。"
                    + "\n必须只输出严格 JSON（不要 markdown 代码块，不要前缀后缀）："
                    + "\n{\"diagnosis\":\"1-2句(<=55字)直指最毁照片的具体物理问题，如杂乱背景物体/顶光鼻下阴影/眼镜反光/低机位大头畸变/过曝高光\","
                    + "\"action\":\"1-2句(<=75字)手把手站位与机位改法：向哪边转身避光、手机抬高/放低多少、退步留白、视线看向哪里\","
                    + "\"recommendedPresetId\":\"仅当换片更出片时从下方选1个ID，否则空串\","
                    + "\"evDelta\":0.0,"
                    + "\"recommendedGrid\":\"THIRDS|GOLDEN_SPIRAL|CROSSHAIR|\"}"
                    + "\n说明：evDelta 为推荐增加或减少的曝光档位(-1.5..+1.5，无需调整填0)；可选胶片ID: "
                    + stockCatalogText();
        }
        return "You are a candid, practical film photography coach reading a live camera preview. No flattery, no vague platitudes. "
                + "Output ONLY raw JSON: "
                + "{\"diagnosis\":\"1-2 crisp sentences(<=30 words) pinpointing concrete flaws like harsh overhead light, distracting background clutter, eyewear glare, low-angle distortion, or clipped highlights\","
                + "\"action\":\"1-2 sentences(<=40 words) with exact physical steps: which way to turn, camera height/distance, head tilt, or background cleanup\","
                + "\"recommendedPresetId\":\"one stock ID below only if switching improves the mood, else empty string\","
                + "\"evDelta\":0.0,"
                + "\"recommendedGrid\":\"THIRDS|GOLDEN_SPIRAL|CROSSHAIR|\"}"
                + "\nValid evDelta [-1.5..+1.5], stock IDs: " + stockCatalogText();
    }

    public static String buildPrintReviewSystemPrompt(boolean zh) {
        if (zh) {
            return "你是直言不讳的胶片暗房导师。看着成片与拍摄 EXIF 复盘，给出真正能提高下一张成片率的指导。"
                    + "\n必须只输出严格 JSON（不要 markdown 代码块）："
                    + "\n{\"diagnosis\":\"1-2句(<=60字)客观指出这张成片的优势与最核心短板（构图位置、光比反差、视角或背景干扰）\","
                    + "\"action\":\"1-2句(<=80字)总结下次遇到同类光线与场景该怎么调整站位、光线方向、曝光或用哪类胶片\","
                    + "\"recommendedPresetId\":\"可选胶片ID(见下)，无建议填空串\","
                    + "\"evDelta\":0.0,"
                    + "\"recommendedGrid\":\"\"}"
                    + "\n可选胶片ID: " + stockCatalogText();
        }
        return "You are a sharp film darkroom mentor reviewing a finished print and its EXIF. Focus on craft and transferable lessons. "
                + "Output ONLY raw JSON: "
                + "{\"diagnosis\":\"1-2 concrete sentences(<=35 words) on framing, light contrast, and background cleanly\","
                + "\"action\":\"1-2 concrete sentences(<=45 words) on what to do differently next shot\","
                + "\"recommendedPresetId\":\"optional stock ID below else empty\","
                + "\"evDelta\":0.0,\"recommendedGrid\":\"\"}"
                + "\nStock IDs: " + stockCatalogText();
    }

    public static String buildViewfinderUserContext(
            PreviewStats stats, float tiltDeg, String presetId, String presetName,
            float currentEvStops, String aspectLabel, boolean facingFront,
            boolean withImage, boolean zh) {
        return buildViewfinderUserContext(stats, tiltDeg, presetId, presetName,
                currentEvStops, aspectLabel, facingFront, withImage, zh, null);
    }

    public static String buildViewfinderUserContext(
            PreviewStats stats, float tiltDeg, String presetId, String presetName,
            float currentEvStops, String aspectLabel, boolean facingFront,
            boolean withImage, boolean zh, CompositionMath.Guide3DCue liveCue) {
        StringBuilder sb = new StringBuilder();
        sb.append(withImage
                ? (zh ? "请诊断这张当前取景画面，并给出可立刻执行的拍摄调整。" : "Diagnose this live viewfinder frame and give concrete adjustment steps.")
                : (zh ? "当前无图片（纯参数模式），请基于测光与角度数据给拍摄建议。" : "Telemetry-only mode: give concrete exposure and framing advice."));
        sb.append("\nCamera: lens=").append(facingFront ? "front" : "rear")
                .append(" preset=").append(presetId).append('(').append(presetName).append(')')
                .append(" aspect=").append(aspectLabel)
                .append(String.format(Locale.US, " currentEV=%+.1f", currentEvStops));
        if (stats != null && stats.width > 0 && stats.height > 0) {
            sb.append(String.format(Locale.US,
                    "\nMetering: luma=%.2f highlightClip=%.0f%% shadowCrush=%.0f%% centerLuma=%.2f edgeLuma=%.2f needle=%+.1fEV",
                    stats.meanLuma, stats.clippedHighlights * 100f, stats.crushedShadows * 100f,
                    stats.centerLuma, stats.borderLuma, FilmRenderMath.meterNeedleStops(stats)));
        }
        if (liveCue != null && liveCue.step != CompositionMath.Step3D.NONE) {
            sb.append("\nLiveFramingStep: ").append(liveCue.step.name());
        }
        if (Math.abs(tiltDeg) >= 1f) {
            sb.append(String.format(Locale.US, " tilt=%+.1fdeg", tiltDeg));
        }
        return sb.toString();
    }

    public static String buildDirectorSystemPrompt(boolean zh) {
        if (zh) {
            return "你是站在摄影小白身旁的实景领拍教练。看当前取景图，直接告诉他下一步去哪里拍、怎么拿手机，禁用「二分法/三分法/对比度/氛围感」等空洞摄影术语。"
                    + "\n必须只输出严格 JSON（不要 markdown 代码块）："
                    + "\n{\"instruction\":\"1句<=30字大白话：往哪挪避开什么背景或迎哪束光\",\"poseTip\":\"可选<=16字姿态眼神提示，无人像填空串\","
                    + "\"subjectX\":0.50,\"subjectY\":0.48,\"targetX\":0.50,\"targetY\":0.36,\"targetSize\":0.26,\"targetElevationDeg\":-6.0,"
                    + "\"recommendedPresetId\":\"更搭的胶片ID，不变填空串\",\"evDelta\":0.0}"
                    + "\n参数说明："
                    + "\n- subjectX,subjectY∈[0.05,0.95]: 当前画面主角所在归一化坐标(可选，非人像静物/食物/风景务必标出);"
                    + "\n- targetX∈[0.25,0.75]: 主体在画面横向目标位置(0.5居中, 0.35/0.65避杂物);"
                    + "\n- targetY∈[0.25,0.65]: 主体/人眼纵向目标位置(人像推荐0.35~0.40);"
                    + "\n- targetSize∈[0.16,0.44]: 主体占画面高度比(日常半身约0.24~0.28, 太近要退就设0.22);"
                    + "\n- targetElevationDeg∈[-25,+15]: 目标镜头仰俯角(0平视, -6~-10手机举高微俯显脸小, -18桌拍俯视, +6低角微仰);"
                    + "\n- evDelta∈[-1.5,+1.5]; 可选胶片ID: " + stockCatalogText();
        }
        return "You are a hands-on shooting director standing next to a camera beginner. Look at the live frame and tell them in plain physical language what background distraction or lighting to fix—no photography jargon."
                + "\nOutput ONLY raw JSON:"
                + "\n{\"instruction\":\"<=18 words plain physical action (step direction / light direction)\",\"poseTip\":\"optional <=8 words pose cue or empty\","
                + "\"subjectX\":0.50,\"subjectY\":0.48,\"targetX\":0.50,\"targetY\":0.36,\"targetSize\":0.26,\"targetElevationDeg\":-6.0,"
                + "\"recommendedPresetId\":\"better stock ID or empty\",\"evDelta\":0.0}"
                + "\nRanges: subjectX,subjectY[0.05..0.95] (current main object coords when non-portrait), targetX[0.25..0.75], targetY[0.25..0.65], targetSize[0.16..0.44], targetElevationDeg[-25..+15] (negative=tilt down/higher phone), evDelta[-1.5..+1.5]. Stock IDs: "
                + stockCatalogText();
    }

    public static String buildDirectorUserContext(
            PreviewStats stats, float tiltDeg, float elevationDeg,
            String presetId, String presetName, float currentEvStops,
            String aspectLabel, boolean facingFront, boolean hasFace,
            boolean withImage, boolean zh) {
        StringBuilder sb = new StringBuilder();
        sb.append(withImage
                ? (zh ? "请根据这张实时取景画面，规划本张最佳拍摄机位、目标落点和曝光/胶片。"
                      : "Plan the best framing target, phone elevation angle, film stock, and EV for this live scene.")
                : (zh ? "纯参数模式：请基于镜头与测光参数给出机位落点与角度建议。"
                      : "Telemetry-only mode: output framing target and elevation plan."));
        sb.append("\nCamera: lens=").append(facingFront ? "front" : "rear")
                .append(" hasFace=").append(hasFace)
                .append(" preset=").append(presetId).append('(').append(presetName).append(')')
                .append(" aspect=").append(aspectLabel)
                .append(String.format(Locale.US, " currentEV=%+.1f currentElev=%+.1fdeg tilt=%+.1fdeg",
                        currentEvStops, elevationDeg, tiltDeg));
        if (stats != null && stats.width > 0 && stats.height > 0) {
            sb.append(String.format(Locale.US,
                    "\nMetering: luma=%.2f clip=%.0f%% crush=%.0f%% center=%.2f border=%.2f needle=%+.1fEV",
                    stats.meanLuma, stats.clippedHighlights * 100f, stats.crushedShadows * 100f,
                    stats.centerLuma, stats.borderLuma, FilmRenderMath.meterNeedleStops(stats)));
        }
        return sb.toString();
    }

    public static String buildPrintUserContext(String exifSummary, boolean withImage, boolean zh) {
        StringBuilder sb = new StringBuilder();
        sb.append(withImage
                ? (zh ? "请结合成片画面与拍摄参数，给出实用复盘与下次改进动作。" : "Review this print with its capture EXIF and state what to improve next time.")
                : (zh ? "当前仅传参数（未附图片），请基于参数给出复盘建议。" : "Metadata-only review: analyze capture EXIF."));
        if (exifSummary != null && !exifSummary.trim().isEmpty()) {
            sb.append("\nEXIF: ").append(exifSummary.trim());
        }
        return sb.toString();
    }

    public static String buildRecipeSystemPrompt(boolean zh) {
        String stocks = stockCatalogText();
        if (zh) {
            return "你是专业胶片调色师。根据用户描述挑选最接近的内置胶片基底并微调三项参数与黑柔光。"
                    + "\n可选 basePresetId: " + stocks
                    + "\n规则：intensity∈[0.25,1.0], grainScale∈[0.0,2.0], warmth∈[-1.0,1.0](负冷正暖), blackMist=true/false, name不超过6个汉字或12字符。"
                    + "\n只输出严格 JSON："
                    + "{\"basePresetId\":\"...\",\"name\":\"...\",\"intensity\":0.85,\"grainScale\":1.2,\"warmth\":0.2,\"blackMist\":false,\"reason\":\"不超过20字的调色思路\"}";
        }
        return "You are a film colorist. Pick the closest base film stock below and tune a custom recipe."
                + "\nValid basePresetId values: " + stocks
                + "\nRanges: intensity[0.25..1.0], grainScale[0.0..2.0], warmth[-1.0..1.0], blackMist(bool), name<=12 chars."
                + "\nOutput ONLY raw JSON: "
                + "{\"basePresetId\":\"...\",\"name\":\"...\",\"intensity\":0.85,\"grainScale\":1.2,\"warmth\":0.2,\"blackMist\":false,\"reason\":\"<=14 words\"}";
    }

    public static AiAdvice parseAdvice(String raw, boolean zh) {
        String cleaned = stripThinkingAndFences(raw);
        if (cleaned.isEmpty()) {
            return new AiAdvice("", "", "", 0f, null);
        }
        String jsonObj = extractFirstJsonObject(cleaned);
        if (!jsonObj.isEmpty()) {
            Map<String, String> map = parseFlatJsonObject(jsonObj);
            String diag = firstNonEmpty(map, "diagnosis", "issue", "problem", "strength");
            String act = firstNonEmpty(map, "action", "howToShoot", "nextAction", "tip", "suggestion");
            String preset = firstNonEmpty(map, "recommendedPresetId", "presetId", "film");
            float ev = parseClampedFloat(firstNonEmpty(map, "evDelta", "recommendedEvDelta", "ev"),
                    0f, -2.0f, 2.0f);
            CompositionMath.GridMode grid = parseGridMode(firstNonEmpty(map, "recommendedGrid", "grid"));
            if (!diag.isEmpty() || !act.isEmpty()) {
                return new AiAdvice(diag, act, preset, ev, grid);
            }
        }
        String[] rawLines = cleaned.replace("\r", "").split("\n");
        List<String> lines = new ArrayList<>();
        for (String line : rawLines) {
            String t = cleanText(line.replaceAll("^[-*•0-9.、\\s]+", ""), 140);
            if (!t.isEmpty()) lines.add(t);
            if (lines.size() >= 2) break;
        }
        if (lines.isEmpty()) {
            return new AiAdvice(cleanText(cleaned, 110), "", "", 0f, null);
        }
        String diag = lines.get(0);
        String act = lines.size() > 1 ? lines.get(1)
                : (zh ? "保持视线平齐略微侧转避开杂乱背景后再按快门" : "Step sideways for a cleaner background and keep lens at eye level");
        return new AiAdvice(diag, act, "", 0f, null);
    }

    public static AiDirectorPlan parseDirectorPlan(String raw, boolean hasFace, boolean zh) {
        String cleaned = stripThinkingAndFences(raw);
        if (cleaned.isEmpty()) {
            return null;
        }
        float defX = 0.50f;
        float defY = hasFace ? 0.36f : 0.50f;
        float defSize = hasFace ? 0.26f : 0.28f;
        float defElev = hasFace ? -5.0f : 0.0f;

        String jsonObj = extractFirstJsonObject(cleaned);
        if (!jsonObj.isEmpty()) {
            Map<String, String> map = parseFlatJsonObject(jsonObj);
            String inst = firstNonEmpty(map, "instruction", "action", "tip", "advice");
            String pose = firstNonEmpty(map, "poseTip", "pose", "look");
            float tx = parseClampedFloat(firstNonEmpty(map, "targetX", "x"), defX, 0.15f, 0.85f);
            float ty = parseClampedFloat(firstNonEmpty(map, "targetY", "y"), defY, 0.15f, 0.85f);
            float sx = parseClampedFloat(firstNonEmpty(map, "subjectX", "anchorX"), Float.NaN, 0.05f, 0.95f);
            float sy = parseClampedFloat(firstNonEmpty(map, "subjectY", "anchorY"), Float.NaN, 0.05f, 0.95f);
            float tsize = parseClampedFloat(firstNonEmpty(map, "targetSize", "size"), defSize, 0.12f, 0.58f);
            float telev = parseClampedFloat(
                    firstNonEmpty(map, "targetElevationDeg", "elevationDeg", "pitchDeg", "targetPitch"),
                    defElev, -35f, 35f);
            String preset = firstNonEmpty(map, "recommendedPresetId", "presetId", "film");
            float ev = parseClampedFloat(firstNonEmpty(map, "evDelta", "ev"), 0f, -2.0f, 2.0f);
            if (inst.isEmpty() && !pose.isEmpty()) {
                inst = pose;
                pose = "";
            }
            if (!inst.isEmpty()) {
                return new AiDirectorPlan(
                        inst, pose,
                        new CompositionMath.DirectorTarget(tx, ty, tsize, telev, true),
                        preset, ev, sx, sy);
            }
        }

        String fallbackLine = cleanText(cleaned.replace('\n', ' ').replaceAll("^[-*•0-9.、\\s]+", ""), 48);
        if (fallbackLine.isEmpty()) {
            return null;
        }
        return new AiDirectorPlan(
                fallbackLine, "",
                new CompositionMath.DirectorTarget(defX, defY, defSize, defElev, true),
                "", 0f);
    }

    public static AiRecipeProposal parseRecipeProposal(String raw, String fallbackPresetId, boolean zh) {
        String cleaned = stripThinkingAndFences(raw);
        String jsonObj = extractFirstJsonObject(cleaned);
        if (jsonObj.isEmpty()) return null;

        Map<String, String> map = parseFlatJsonObject(jsonObj);
        if (map.isEmpty()) return null;

        String requestedId = firstNonEmpty(map, "basePresetId", "presetId", "base");
        String validPresetId = resolveRequiredPresetId(requestedId, fallbackPresetId);
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

    // ------------------------------------------------------------------ persistence codec

    public static String encodeAdviceLog(Map<String, AiAdvice> map) {
        if (map == null || map.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, AiAdvice> e : map.entrySet()) {
            String key = cleanDelim(e.getKey());
            AiAdvice a = e.getValue();
            if (key.isEmpty() || a == null || a.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(key).append('\t')
                    .append(cleanDelim(a.diagnosis)).append('\t')
                    .append(cleanDelim(a.action)).append('\t')
                    .append(cleanDelim(a.recommendedPresetId)).append('\t')
                    .append(String.format(Locale.US, "%.2f", a.evDelta)).append('\t')
                    .append(a.recommendedGrid != null ? a.recommendedGrid.name() : "");
        }
        return sb.toString();
    }

    public static Map<String, AiAdvice> decodeAdviceLog(String raw) {
        Map<String, AiAdvice> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t", -1);
            if (parts.length < 3 || parts[0].isEmpty()) continue;
            String preset = parts.length > 3 ? parts[3] : "";
            float ev = parts.length > 4 ? parseClampedFloat(parts[4], 0f, -2f, 2f) : 0f;
            CompositionMath.GridMode grid = parts.length > 5 ? parseGridMode(parts[5]) : null;
            AiAdvice a = new AiAdvice(parts[1], parts[2], preset, ev, grid);
            if (!a.isEmpty()) {
                out.put(parts[0], a);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ JSON & text utilities

    public static String stripThinkingAndFences(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
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

    private static String resolveOptionalPresetId(String candidate) {
        if (candidate == null) return "";
        String trimmed = candidate.trim().toLowerCase(Locale.US);
        if (trimmed.isEmpty()) return "";
        for (FilmPreset p : FilmPresets.all()) {
            if (p.id.equals(trimmed)) return p.id;
        }
        return "";
    }

    private static String resolveRequiredPresetId(String candidate, String fallbackId) {
        String match = resolveOptionalPresetId(candidate);
        if (!match.isEmpty()) return match;
        String fb = resolveOptionalPresetId(fallbackId);
        return !fb.isEmpty() ? fb : FilmPresets.all()[FilmPresets.DEFAULT_INDEX].id;
    }

    private static CompositionMath.GridMode parseGridMode(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String up = raw.trim().toUpperCase(Locale.US);
        if ("THIRDS".equals(up)) return CompositionMath.GridMode.THIRDS;
        if ("GOLDEN_SPIRAL".equals(up) || "GOLDEN".equals(up)) return CompositionMath.GridMode.GOLDEN_SPIRAL;
        if ("CROSSHAIR".equals(up) || "CROSS".equals(up)) return CompositionMath.GridMode.CROSSHAIR;
        return null;
    }

    private static float clampEv(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 0f;
        float clamped = Math.max(-2.0f, Math.min(2.0f, v));
        return Math.round(clamped * 10f) / 10f;
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
