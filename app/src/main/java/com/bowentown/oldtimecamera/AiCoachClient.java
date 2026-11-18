package com.bowentown.oldtimecamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Zero-dependency HTTPS client for OpenAI-compatible AI endpoints (DeepSeek default,
 * Qwen VL, OpenAI, or user-supplied proxies). Never runs in the background; executes
 * strictly on explicit user tap via caller-owned worker threads.
 */
public final class AiCoachClient {

    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 35_000;

    public static final class PingResult {
        public final long latencyMs;
        public final String modelEcho;

        PingResult(long latencyMs, String modelEcho) {
            this.latencyMs = latencyMs;
            this.modelEcho = modelEcho;
        }
    }

    private AiCoachClient() {}

    public static PingResult testConnection(Context context, String rawBaseUrl, String rawModel,
                                            String rawApiKey, boolean zh) throws Exception {
        String endpoint = AiCoachMath.normalizeEndpointUrl(rawBaseUrl);
        if (!AiCoachMath.isAllowedEndpointUrl(endpoint)) {
            throw new IllegalArgumentException(context.getString(R.string.ai_err_https_required));
        }
        String key = rawApiKey == null ? "" : rawApiKey.trim();
        if (key.isEmpty()) {
            key = AiKeyStore.loadApiKey(context);
        }
        if (key.isEmpty()) {
            throw new IllegalStateException(context.getString(R.string.ai_err_no_key));
        }
        String model = (rawModel == null || rawModel.trim().isEmpty())
                ? AiCoachMath.ProviderPreset.DEEPSEEK.defaultModel
                : rawModel.trim();

        long start = SystemClock.uptimeMillis();
        String reply = requestChatCompletion(
                endpoint, key, model,
                zh ? "仅回复 OK" : "Reply OK only.",
                "ping", null, 16, 0.0f);
        long elapsed = Math.max(1L, SystemClock.uptimeMillis() - start);
        return new PingResult(elapsed, AiCoachMath.parseInspireTip(reply));
    }

    public static AiCoachMath.AiCritique critiquePhoto(
            Context context, CameraPrefs prefs, Bitmap sourceBitmap,
            String exifSummary, String diagLine, boolean zh) throws Exception {
        Config cfg = readConfig(context, prefs);
        boolean useImage = cfg.visionEnabled && sourceBitmap != null && !sourceBitmap.isRecycled();
        String jpegDataUrl = useImage ? encodeScaledDataUrl(sourceBitmap, 768, 82) : null;
        String system = AiCoachMath.buildCritiqueSystemPrompt(zh);
        String userText = AiCoachMath.buildCritiqueUserContext(exifSummary, diagLine, useImage, zh);
        String reply = requestChatCompletion(
                cfg.endpointUrl, cfg.apiKey, cfg.model, system, userText, jpegDataUrl, 220, 0.35f);
        AiCoachMath.AiCritique critique = AiCoachMath.parseCritique(reply, zh);
        if (critique.isEmpty()) {
            throw new IllegalStateException(context.getString(R.string.ai_err_empty_reply));
        }
        return critique;
    }

    public static String inspireViewfinder(
            Context context, CameraPrefs prefs, Bitmap previewBitmap,
            PreviewStats stats, float tiltDeg, String presetName,
            String aspectLabel, boolean zh) throws Exception {
        Config cfg = readConfig(context, prefs);
        boolean useImage = cfg.visionEnabled && previewBitmap != null && !previewBitmap.isRecycled();
        String jpegDataUrl = useImage ? encodeScaledDataUrl(previewBitmap, 512, 76) : null;
        String telemetry = AiCoachMath.buildPreviewTelemetrySummary(
                stats, tiltDeg, presetName, aspectLabel, zh);
        String userText;
        if (zh) {
            userText = (useImage ? "这是当前取景框预览。" : "当前为纯文本测光模式。")
                    + (telemetry.isEmpty() ? "" : "\n实时参数：" + telemetry);
        } else {
            userText = (useImage ? "Current viewfinder preview." : "Telemetry-only mode.")
                    + (telemetry.isEmpty() ? "" : "\nTelemetry: " + telemetry);
        }
        String reply = requestChatCompletion(
                cfg.endpointUrl, cfg.apiKey, cfg.model,
                AiCoachMath.buildInspireSystemPrompt(zh), userText, jpegDataUrl, 96, 0.45f);
        String tip = AiCoachMath.parseInspireTip(reply);
        if (tip.isEmpty()) {
            throw new IllegalStateException(context.getString(R.string.ai_err_empty_reply));
        }
        return tip;
    }

    public static AiCoachMath.AiRecipeProposal generateRecipe(
            Context context, CameraPrefs prefs, String moodPrompt,
            String fallbackPresetId, boolean zh) throws Exception {
        Config cfg = readConfig(context, prefs);
        String system = AiCoachMath.buildRecipeSystemPrompt(zh);
        String reply = requestChatCompletion(
                cfg.endpointUrl, cfg.apiKey, cfg.model,
                system, moodPrompt == null ? "" : moodPrompt.trim(), null, 220, 0.55f);
        AiCoachMath.AiRecipeProposal proposal =
                AiCoachMath.parseRecipeProposal(reply, fallbackPresetId, zh);
        if (proposal == null) {
            throw new IllegalStateException(context.getString(R.string.ai_err_parse_recipe));
        }
        return proposal;
    }

    // ------------------------------------------------------------------ internals

    private static final class Config {
        final String endpointUrl;
        final String apiKey;
        final String model;
        final boolean visionEnabled;

        Config(String endpointUrl, String apiKey, String model, boolean visionEnabled) {
            this.endpointUrl = endpointUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.visionEnabled = visionEnabled;
        }
    }

    private static Config readConfig(Context context, CameraPrefs prefs) {
        String key = AiKeyStore.loadApiKey(context);
        if (key.isEmpty()) {
            throw new IllegalStateException(context.getString(R.string.ai_err_no_key));
        }
        String endpoint = AiCoachMath.normalizeEndpointUrl(prefs.getAiBaseUrl());
        if (!AiCoachMath.isAllowedEndpointUrl(endpoint)) {
            throw new IllegalArgumentException(context.getString(R.string.ai_err_https_required));
        }
        String model = prefs.getAiModel();
        boolean vision = prefs.isAiVisionEnabled();
        return new Config(endpoint, key, model, vision);
    }

    private static String encodeScaledDataUrl(Bitmap src, int maxEdge, int quality) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longest = Math.max(w, h);
        Bitmap target = src;
        boolean needRecycle = false;
        if (longest > maxEdge && longest > 0) {
            float scale = (float) maxEdge / longest;
            target = Bitmap.createScaledBitmap(
                    src, Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
            needRecycle = (target != src);
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32768);
            target.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            return "data:image/jpeg;base64," + b64;
        } finally {
            if (needRecycle && target != null && !target.isRecycled()) {
                target.recycle();
            }
        }
    }

    private static String requestChatCompletion(
            String endpointUrl, String apiKey, String model,
            String systemPrompt, String userText, String imageDataUrl,
            int maxTokens, float temperature) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("max_tokens", maxTokens);
        payload.put("temperature", temperature);
        payload.put("stream", false);

        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.put(sys);
        }

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        if (imageDataUrl != null && !imageDataUrl.isEmpty()) {
            JSONArray parts = new JSONArray();
            JSONObject textPart = new JSONObject();
            textPart.put("type", "text");
            textPart.put("text", userText);
            parts.put(textPart);

            JSONObject imgObj = new JSONObject();
            imgObj.put("url", imageDataUrl);
            JSONObject imgPart = new JSONObject();
            imgPart.put("type", "image_url");
            imgPart.put("image_url", imgObj);
            parts.put(imgPart);

            userMsg.put("content", parts);
        } else {
            userMsg.put("content", userText);
        }
        messages.put(userMsg);
        payload.put("messages", messages);

        byte[] bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(endpointUrl).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(bodyBytes.length);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String respText = stream != null ? readAllBounded(stream, 262_144) : "";
            if (code < 200 || code >= 300) {
                throw new IllegalStateException(formatHttpError(code, respText));
            }
            return extractAssistantContent(respText);
        } finally {
            conn.disconnect();
        }
    }

    private static String extractAssistantContent(String jsonBody) throws Exception {
        JSONObject root = new JSONObject(jsonBody);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return "";
        JSONObject first = choices.optJSONObject(0);
        if (first == null) return "";
        JSONObject msg = first.optJSONObject("message");
        if (msg == null) return "";
        Object rawContent = msg.opt("content");
        if (rawContent instanceof String) {
            String s = ((String) rawContent).trim();
            if (!s.isEmpty()) return s;
        } else if (rawContent instanceof JSONArray) {
            JSONArray parts = (JSONArray) rawContent;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null && part.has("text")) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(part.optString("text", ""));
                }
            }
            if (sb.length() > 0) return sb.toString().trim();
        }
        return msg.optString("reasoning_content", "").trim();
    }

    private static String formatHttpError(int status, String body) {
        String msg = "";
        if (body != null && !body.isEmpty()) {
            try {
                JSONObject root = new JSONObject(body);
                JSONObject err = root.optJSONObject("error");
                if (err != null) {
                    msg = err.optString("message", "");
                } else {
                    msg = root.optString("message", "");
                }
            } catch (Exception ignored) {
            }
        }
        msg = msg.replace('\n', ' ').trim();
        if (msg.length() > 96) msg = msg.substring(0, 96) + "…";
        return msg.isEmpty() ? ("HTTP " + status) : ("HTTP " + status + ": " + msg);
    }

    private static String readAllBounded(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) {
                int take = Math.min(n, maxBytes - total);
                if (take > 0) {
                    out.write(buf, 0, take);
                    total += take;
                }
                if (total >= maxBytes) break;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
