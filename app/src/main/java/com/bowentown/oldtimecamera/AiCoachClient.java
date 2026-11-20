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
 * Qwen VL, OpenAI, or custom proxies). Explicitly disables DeepSeek default Thinking Mode
 * on direct coaching calls and never surfaces internal reasoning traces as user advice.
 */
public final class AiCoachClient {

    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 35_000;

    public static final class PingResult {
        public final long latencyMs;

        PingResult(long latencyMs) {
            this.latencyMs = latencyMs;
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
        requestChatCompletion(
                context, endpoint, key, model,
                zh ? "仅回复 OK" : "Reply OK only.",
                "ping", null, 32, 0.0f);
        long elapsed = Math.max(1L, SystemClock.uptimeMillis() - start);
        return new PingResult(elapsed);
    }

    public static AiCoachMath.AiAdvice adviseViewfinder(
            Context context, CameraPrefs prefs, Bitmap previewBitmap,
            PreviewStats stats, float tiltDeg, String presetId, String presetName,
            float currentEvStops, String aspectLabel, boolean facingFront,
            boolean zh) throws Exception {
        return adviseViewfinder(context, prefs, previewBitmap, stats, tiltDeg,
                presetId, presetName, currentEvStops, aspectLabel, facingFront, zh, null);
    }

    public static AiCoachMath.AiAdvice adviseViewfinder(
            Context context, CameraPrefs prefs, Bitmap previewBitmap,
            PreviewStats stats, float tiltDeg, String presetId, String presetName,
            float currentEvStops, String aspectLabel, boolean facingFront,
            boolean zh, CompositionMath.Guide3DCue liveCue) throws Exception {
        Config cfg = readConfig(context, prefs);
        boolean useImage = cfg.visionEnabled && previewBitmap != null && !previewBitmap.isRecycled();
        String jpegDataUrl = useImage ? encodeScaledDataUrl(previewBitmap, 640, 80) : null;
        String userText = AiCoachMath.buildViewfinderUserContext(
                stats, tiltDeg, presetId, presetName, currentEvStops, aspectLabel,
                facingFront, useImage, zh, liveCue);
        String reply = requestChatCompletion(
                context, cfg.endpointUrl, cfg.apiKey, cfg.model,
                AiCoachMath.buildViewfinderCoachSystemPrompt(zh),
                userText, jpegDataUrl, 700, 0.35f);
        AiCoachMath.AiAdvice advice = AiCoachMath.parseAdvice(reply, zh);
        if (advice.isEmpty()) {
            throw new IllegalStateException(context.getString(R.string.ai_err_empty_reply));
        }
        return advice;
    }

    public static AiCoachMath.AiDirectorPlan directViewfinder(
            Context context, CameraPrefs prefs, Bitmap previewBitmap,
            PreviewStats stats, float tiltDeg, float elevationDeg,
            String presetId, String presetName, float currentEvStops,
            String aspectLabel, boolean facingFront, boolean hasFace,
            boolean zh) throws Exception {
        Config cfg = readConfig(context, prefs);
        boolean useImage = cfg.visionEnabled && previewBitmap != null && !previewBitmap.isRecycled();
        String jpegDataUrl = useImage ? encodeScaledDataUrl(previewBitmap, 640, 80) : null;
        String userText = AiCoachMath.buildDirectorUserContext(
                stats, tiltDeg, elevationDeg, presetId, presetName,
                currentEvStops, aspectLabel, facingFront, hasFace, useImage, zh);
        String reply = requestChatCompletion(
                context, cfg.endpointUrl, cfg.apiKey, cfg.model,
                AiCoachMath.buildDirectorSystemPrompt(zh),
                userText, jpegDataUrl, 650, 0.3f);
        AiCoachMath.AiDirectorPlan plan = AiCoachMath.parseDirectorPlan(reply, hasFace, zh);
        if (plan == null || plan.isEmpty()) {
            throw new IllegalStateException(context.getString(R.string.ai_err_empty_reply));
        }
        return plan;
    }

    public static AiCoachMath.AiAdvice reviewPrint(
            Context context, CameraPrefs prefs, Bitmap sourceBitmap,
            String exifSummary, boolean zh) throws Exception {
        Config cfg = readConfig(context, prefs);
        boolean useImage = cfg.visionEnabled && sourceBitmap != null && !sourceBitmap.isRecycled();
        String jpegDataUrl = useImage ? encodeScaledDataUrl(sourceBitmap, 768, 82) : null;
        String userText = AiCoachMath.buildPrintUserContext(exifSummary, useImage, zh);
        String reply = requestChatCompletion(
                context, cfg.endpointUrl, cfg.apiKey, cfg.model,
                AiCoachMath.buildPrintReviewSystemPrompt(zh),
                userText, jpegDataUrl, 700, 0.35f);
        AiCoachMath.AiAdvice advice = AiCoachMath.parseAdvice(reply, zh);
        if (advice.isEmpty()) {
            throw new IllegalStateException(context.getString(R.string.ai_err_empty_reply));
        }
        return advice;
    }

    public static AiCoachMath.AiRecipeProposal generateRecipe(
            Context context, CameraPrefs prefs, String moodPrompt,
            String fallbackPresetId, boolean zh) throws Exception {
        Config cfg = readConfig(context, prefs);
        String reply = requestChatCompletion(
                context, cfg.endpointUrl, cfg.apiKey, cfg.model,
                AiCoachMath.buildRecipeSystemPrompt(zh),
                moodPrompt == null ? "" : moodPrompt.trim(), null, 500, 0.5f);
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
        return new Config(endpoint, key, prefs.getAiModel(), prefs.isAiVisionEnabled());
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
            Context context, String endpointUrl, String apiKey, String model,
            String systemPrompt, String userText, String imageDataUrl,
            int maxTokens, float temperature) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("max_tokens", maxTokens);
        payload.put("temperature", temperature);
        payload.put("stream", false);
        // deepseek-flash enables thinking mode by default, which can consume short budgets
        // inside reasoning_content and leave message.content empty unless disabled cleanly.
        if (AiCoachMath.isDeepSeekEndpoint(endpointUrl)) {
            payload.put("thinking", new JSONObject().put("type", "disabled"));
        }

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
            return extractAssistantContent(context, respText);
        } finally {
            conn.disconnect();
        }
    }

    private static String extractAssistantContent(Context context, String jsonBody) throws Exception {
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

        // Never surface internal chain-of-thought (reasoning_content) as user advice.
        if (!msg.optString("reasoning_content", "").trim().isEmpty()) {
            throw new IllegalStateException(context.getString(R.string.ai_err_thinking_only));
        }
        return "";
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
