package com.bowentown.oldtimecamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * Persists roll state, darkroom preferences, and beginner assist toggles across launches
 * without introducing any database or network dependency.
 */
public final class CameraPrefs {

    public enum AspectRatio {
        RATIO_3_4("3:4", 3f / 4f),
        RATIO_3_2("3:2", 3f / 2f),
        RATIO_1_1("1:1", 1f),
        RATIO_16_9("16:9", 16f / 9f),
        RATIO_XPAN("XPAN", 65f / 24f);

        public final String label;
        public final float widthOverHeight;

        AspectRatio(String label, float widthOverHeight) {
            this.label = label;
            this.widthOverHeight = widthOverHeight;
        }

        public AspectRatio next() {
            AspectRatio[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public static AspectRatio fromName(String name) {
            for (AspectRatio r : values()) {
                if (r.name().equals(name)) return r;
            }
            return RATIO_3_4;
        }
    }

    public enum PaperStyle {
        IVORY,
        BLACK,
        SPROCKET_35MM,
        NONE;

        public static PaperStyle fromName(String name) {
            for (PaperStyle p : values()) {
                if (p.name().equals(name)) return p;
            }
            return IVORY;
        }
    }

    public enum ShootMode {
        SINGLE,
        DOUBLE_EXP,
        HALF_FRAME,
        QUAD_STRIP;

        public ShootMode next() {
            ShootMode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public static ShootMode fromName(String name) {
            if (name != null) {
                for (ShootMode m : values()) {
                    if (m.name().equals(name)) return m;
                }
            }
            return SINGLE;
        }
    }

    public enum DateMode {
        TODAY,
        RETRO_98,
        OFF;

        public static DateMode fromName(String name) {
            for (DateMode d : values()) {
                if (d.name().equals(name)) return d;
            }
            return TODAY;
        }
    }

    public static final int FRAMES_PER_ROLL = 36;
    public static final int HALF_FRAMES_PER_ROLL = 72;

    public static int framesForMode(ShootMode mode) {
        return mode == ShootMode.HALF_FRAME ? HALF_FRAMES_PER_ROLL : FRAMES_PER_ROLL;
    }

    private static final String PREFS_NAME = "old_time_camera_prefs";
    private static final String KEY_PRESET_ID = "preset_id";
    private static final String KEY_ROLL_NUMBER = "roll_number";
    private static final String KEY_EXPOSURES = "exposures";
    private static final String KEY_ASPECT = "aspect_ratio";
    private static final String KEY_PAPER = "paper_style";
    private static final String KEY_DATE_MODE = "date_mode";
    private static final String KEY_GRAIN_BOOST = "grain_boost";
    private static final String KEY_LIGHT_LEAK = "light_leak";
    private static final String KEY_GRID = "grid_enabled";
    private static final String KEY_GRID_MODE = "grid_mode";
    private static final String KEY_GOLDEN_ROT = "golden_rot";
    private static final String KEY_GUIDE_TEMPLATE = "guide_template";
    private static final String KEY_SHOOT_MODE = "shoot_mode";
    private static final String KEY_BLACK_MIST = "black_mist";
    private static final String KEY_SHAKE_LEAK = "shake_leak";
    private static final String KEY_SOUND = "shutter_sound";
    private static final String KEY_FRONT_MIRROR = "front_mirror";
    private static final String KEY_TIMER_SECONDS = "timer_seconds";
    private static final String KEY_LEVEL_ENABLED = "level_enabled";
    private static final String KEY_MANUAL_ROTATION = "manual_rotation";
    private static final String KEY_LAST_PHOTO_URI = "last_photo_uri";

    private static final String KEY_ASSIST_ENABLED = "assist_enabled";
    private static final String KEY_ASSIST_COMP = "assist_comp";
    private static final String KEY_ASSIST_EXPOSURE = "assist_exposure";
    private static final String KEY_ASSIST_SCENE = "assist_scene";
    private static final String KEY_ASSIST_STABILITY = "assist_stability";
    private static final String KEY_ASSIST_STEADY_SHUTTER = "assist_steady_shutter";
    private static final String KEY_ASSIST_METER_BAR = "assist_meter_bar";

    private static final String KEY_RECIPE_INTENSITY = "recipe_intensity";
    private static final String KEY_RECIPE_GRAIN = "recipe_grain";
    private static final String KEY_RECIPE_WARMTH = "recipe_warmth";
    private static final String KEY_ACTIVE_CUSTOM_RECIPE = "active_custom_recipe";
    private static final String KEY_CUSTOM_RECIPES_RAW = "custom_recipes_raw";
    private static final String KEY_PRACTICE_THEME = "practice_theme";
    private static final String KEY_PRACTICE_STEP = "practice_step";
    private static final String KEY_DIAGNOSTICS_LOG = "diagnostics_log";

    private static final String KEY_AI_PROVIDER = "ai_provider";
    private static final String KEY_AI_BASE_URL = "ai_base_url";
    private static final String KEY_AI_MODEL = "ai_model";
    private static final String KEY_AI_VISION = "ai_vision_enabled";
    private static final String KEY_AI_CRITIQUES_LOG = "ai_critiques_log";

    private final SharedPreferences sp;

    public CameraPrefs(Context context) {
        this.sp = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getPresetId(String fallback) {
        return sp.getString(KEY_PRESET_ID, fallback);
    }

    public void setPresetId(String id) {
        sp.edit().putString(KEY_PRESET_ID, id).apply();
    }

    public int getRollNumber() {
        return Math.max(1, sp.getInt(KEY_ROLL_NUMBER, 1));
    }

    public void setRollNumber(int roll) {
        sp.edit().putInt(KEY_ROLL_NUMBER, Math.max(1, roll)).apply();
    }

    public int getExposures() {
        return Math.max(0, sp.getInt(KEY_EXPOSURES, 0));
    }

    public void setExposures(int exposures) {
        sp.edit().putInt(KEY_EXPOSURES, Math.max(0, exposures)).apply();
    }

    public AspectRatio getAspectRatio() {
        return AspectRatio.fromName(sp.getString(KEY_ASPECT, AspectRatio.RATIO_3_4.name()));
    }

    public void setAspectRatio(AspectRatio ratio) {
        sp.edit().putString(KEY_ASPECT, ratio.name()).apply();
    }

    public PaperStyle getPaperStyle() {
        return PaperStyle.fromName(sp.getString(KEY_PAPER, PaperStyle.IVORY.name()));
    }

    public void setPaperStyle(PaperStyle style) {
        sp.edit().putString(KEY_PAPER, style.name()).apply();
    }

    public DateMode getDateMode() {
        return DateMode.fromName(sp.getString(KEY_DATE_MODE, DateMode.TODAY.name()));
    }

    public void setDateMode(DateMode mode) {
        sp.edit().putString(KEY_DATE_MODE, mode.name()).apply();
    }

    public boolean isGrainBoost() {
        return sp.getBoolean(KEY_GRAIN_BOOST, false);
    }

    public void setGrainBoost(boolean enabled) {
        sp.edit().putBoolean(KEY_GRAIN_BOOST, enabled).apply();
    }

    public boolean isLightLeakEnabled() {
        return sp.getBoolean(KEY_LIGHT_LEAK, true);
    }

    public void setLightLeakEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_LIGHT_LEAK, enabled).apply();
    }

    public CompositionMath.GridMode getGridMode() {
        String raw = sp.getString(KEY_GRID_MODE, null);
        if (raw != null) {
            return CompositionMath.GridMode.fromName(raw);
        }
        return sp.getBoolean(KEY_GRID, false)
                ? CompositionMath.GridMode.THIRDS
                : CompositionMath.GridMode.OFF;
    }

    public void setGridMode(CompositionMath.GridMode mode) {
        CompositionMath.GridMode m = mode != null ? mode : CompositionMath.GridMode.OFF;
        sp.edit()
                .putString(KEY_GRID_MODE, m.name())
                .putBoolean(KEY_GRID, m != CompositionMath.GridMode.OFF)
                .apply();
    }

    public boolean isGridEnabled() {
        return getGridMode() != CompositionMath.GridMode.OFF;
    }

    public void setGridEnabled(boolean enabled) {
        setGridMode(enabled ? CompositionMath.GridMode.THIRDS : CompositionMath.GridMode.OFF);
    }

    public int getGoldenRotation() {
        return ((sp.getInt(KEY_GOLDEN_ROT, 0) % 4) + 4) % 4;
    }

    public void setGoldenRotation(int rotSteps) {
        sp.edit().putInt(KEY_GOLDEN_ROT, ((rotSteps % 4) + 4) % 4).apply();
    }

    public boolean isShutterSoundEnabled() {
        return sp.getBoolean(KEY_SOUND, true);
    }

    public void setShutterSoundEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_SOUND, enabled).apply();
    }

    public boolean isFrontMirrorEnabled() {
        return sp.getBoolean(KEY_FRONT_MIRROR, true);
    }

    public void setFrontMirrorEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_FRONT_MIRROR, enabled).apply();
    }

    public int getTimerSeconds() {
        int sec = sp.getInt(KEY_TIMER_SECONDS, 0);
        return (sec == 3 || sec == 10) ? sec : 0;
    }

    public void setTimerSeconds(int seconds) {
        sp.edit().putInt(KEY_TIMER_SECONDS, seconds).apply();
    }

    public boolean isLevelEnabled() {
        return sp.getBoolean(KEY_LEVEL_ENABLED, false);
    }

    public void setLevelEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_LEVEL_ENABLED, enabled).apply();
    }

    public int getManualRotation() {
        return sp.getInt(KEY_MANUAL_ROTATION, -1);
    }

    public void setManualRotation(int degrees) {
        sp.edit().putInt(KEY_MANUAL_ROTATION, degrees).apply();
    }

    public boolean isAssistEnabled() {
        return sp.getBoolean(KEY_ASSIST_ENABLED, true);
    }

    public void setAssistEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_ASSIST_ENABLED, enabled).apply();
    }

    public boolean isAssistComposition() {
        return sp.getBoolean(KEY_ASSIST_COMP, true);
    }

    public void setAssistComposition(boolean enabled) {
        sp.edit().putBoolean(KEY_ASSIST_COMP, enabled).apply();
    }

    public boolean isAssistExposure() {
        return sp.getBoolean(KEY_ASSIST_EXPOSURE, true);
    }

    public void setAssistExposure(boolean enabled) {
        sp.edit().putBoolean(KEY_ASSIST_EXPOSURE, enabled).apply();
    }

    public boolean isAssistScene() {
        return sp.getBoolean(KEY_ASSIST_SCENE, true);
    }

    public void setAssistScene(boolean enabled) {
        sp.edit().putBoolean(KEY_ASSIST_SCENE, enabled).apply();
    }

    public boolean isAssistStability() {
        return sp.getBoolean(KEY_ASSIST_STABILITY, true);
    }

    public void setAssistStability(boolean enabled) {
        sp.edit().putBoolean(KEY_ASSIST_STABILITY, enabled).apply();
    }

    public boolean isAssistSteadyShutter() {
        return sp.getBoolean(KEY_ASSIST_STEADY_SHUTTER, true);
    }

    public void setAssistSteadyShutter(boolean enabled) {
        sp.edit().putBoolean(KEY_ASSIST_STEADY_SHUTTER, enabled).apply();
    }

    public Uri getLastPhotoUri() {
        String raw = sp.getString(KEY_LAST_PHOTO_URI, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Uri.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public void setLastPhotoUri(Uri uri) {
        sp.edit().putString(KEY_LAST_PHOTO_URI, uri == null ? null : uri.toString()).apply();
    }

    public CompositionMath.GuideTemplate getGuideTemplate() {
        return CompositionMath.GuideTemplate.fromName(sp.getString(KEY_GUIDE_TEMPLATE, null));
    }

    public void setGuideTemplate(CompositionMath.GuideTemplate template) {
        CompositionMath.GuideTemplate safe = template != null
                ? template : CompositionMath.GuideTemplate.NONE;
        sp.edit().putString(KEY_GUIDE_TEMPLATE, safe.name()).apply();
    }

    public ShootMode getShootMode() {
        return ShootMode.fromName(sp.getString(KEY_SHOOT_MODE, ShootMode.SINGLE.name()));
    }

    public void setShootMode(ShootMode mode) {
        ShootMode safe = mode != null ? mode : ShootMode.SINGLE;
        sp.edit().putString(KEY_SHOOT_MODE, safe.name()).apply();
    }

    public boolean isBlackMistEnabled() {
        return sp.getBoolean(KEY_BLACK_MIST, false);
    }

    public void setBlackMistEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_BLACK_MIST, enabled).apply();
    }

    public boolean isShakeLeakEnabled() {
        return sp.getBoolean(KEY_SHAKE_LEAK, true);
    }

    public void setShakeLeakEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_SHAKE_LEAK, enabled).apply();
    }

    public boolean isAssistMeterBar() {
        return sp.getBoolean(KEY_ASSIST_METER_BAR, true);
    }

    public void setAssistMeterBar(boolean enabled) {
        sp.edit().putBoolean(KEY_ASSIST_METER_BAR, enabled).apply();
    }

    public FilmRecipe getFilmRecipe() {
        return new FilmRecipe(
                sp.getFloat(KEY_RECIPE_INTENSITY, 1.0f),
                sp.getFloat(KEY_RECIPE_GRAIN, 1.0f),
                sp.getFloat(KEY_RECIPE_WARMTH, 0.0f));
    }

    public void setFilmRecipe(FilmRecipe recipe) {
        FilmRecipe safe = recipe != null ? recipe : FilmRecipe.DEFAULT;
        sp.edit()
                .putFloat(KEY_RECIPE_INTENSITY, safe.intensity)
                .putFloat(KEY_RECIPE_GRAIN, safe.grainScale)
                .putFloat(KEY_RECIPE_WARMTH, safe.warmth)
                .apply();
    }

    public String getActiveCustomRecipeId() {
        return sp.getString(KEY_ACTIVE_CUSTOM_RECIPE, "");
    }

    public void setActiveCustomRecipeId(String id) {
        sp.edit().putString(KEY_ACTIVE_CUSTOM_RECIPE, id == null ? "" : id).apply();
    }

    public java.util.List<FilmRecipe.SavedRecipe> getSavedRecipes() {
        return FilmRecipe.decodeSavedList(sp.getString(KEY_CUSTOM_RECIPES_RAW, ""));
    }

    public void setSavedRecipes(java.util.List<FilmRecipe.SavedRecipe> recipes) {
        sp.edit().putString(KEY_CUSTOM_RECIPES_RAW, FilmRecipe.encodeSavedList(recipes)).apply();
    }

    public PracticeRoll.Theme getPracticeTheme() {
        return PracticeRoll.Theme.fromName(sp.getString(KEY_PRACTICE_THEME, null));
    }

    public int getPracticeStep() {
        return Math.max(0, Math.min(PracticeRoll.ROLL_FRAMES - 1, sp.getInt(KEY_PRACTICE_STEP, 0)));
    }

    public void setPracticeState(PracticeRoll.Theme theme, int stepIndex) {
        int clamped = Math.max(0, Math.min(PracticeRoll.ROLL_FRAMES - 1, stepIndex));
        sp.edit()
                .putString(KEY_PRACTICE_THEME, theme == null ? null : theme.name())
                .putInt(KEY_PRACTICE_STEP, theme == null ? 0 : clamped)
                .apply();
    }

    public void recordShotDiagnostic(String fileKey, PracticeRoll.ShotDiagnostic diag) {
        if (fileKey == null || fileKey.isEmpty() || diag == null) return;
        java.util.Map<String, PracticeRoll.ShotDiagnostic> updated = putBoundedFirst(
                fileKey, diag, PracticeRoll.decodeDiagnosticLog(sp.getString(KEY_DIAGNOSTICS_LOG, "")));
        sp.edit().putString(KEY_DIAGNOSTICS_LOG, PracticeRoll.encodeDiagnosticLog(updated)).apply();
    }

    public PracticeRoll.ShotDiagnostic getShotDiagnostic(String fileKey) {
        if (fileKey == null || fileKey.isEmpty()) return null;
        return PracticeRoll.decodeDiagnosticLog(sp.getString(KEY_DIAGNOSTICS_LOG, "")).get(fileKey);
    }

    public AiCoachMath.ProviderPreset getAiProviderPreset() {
        return AiCoachMath.ProviderPreset.fromName(
                sp.getString(KEY_AI_PROVIDER, AiCoachMath.ProviderPreset.DEEPSEEK.name()));
    }

    public void setAiProviderPreset(AiCoachMath.ProviderPreset preset) {
        AiCoachMath.ProviderPreset safe = preset != null ? preset : AiCoachMath.ProviderPreset.DEEPSEEK;
        sp.edit().putString(KEY_AI_PROVIDER, safe.name()).apply();
    }

    public String getAiBaseUrl() {
        AiCoachMath.ProviderPreset preset = getAiProviderPreset();
        String raw = sp.getString(KEY_AI_BASE_URL, preset.defaultBaseUrl);
        return (raw == null || raw.trim().isEmpty()) ? preset.defaultBaseUrl : raw.trim();
    }

    public void setAiBaseUrl(String baseUrl) {
        sp.edit().putString(KEY_AI_BASE_URL, baseUrl == null ? "" : baseUrl.trim()).apply();
    }

    public String getAiModel() {
        AiCoachMath.ProviderPreset preset = getAiProviderPreset();
        String raw = sp.getString(KEY_AI_MODEL, preset.defaultModel);
        return (raw == null || raw.trim().isEmpty()) ? preset.defaultModel : raw.trim();
    }

    public void setAiModel(String model) {
        sp.edit().putString(KEY_AI_MODEL, model == null ? "" : model.trim()).apply();
    }

    public boolean isAiVisionEnabled() {
        return sp.getBoolean(KEY_AI_VISION, getAiProviderPreset().defaultVision);
    }

    public void setAiVisionEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_AI_VISION, enabled).apply();
    }

    public void recordAiCritique(String fileKey, AiCoachMath.AiCritique critique) {
        if (fileKey == null || fileKey.isEmpty() || critique == null || critique.isEmpty()) return;
        java.util.Map<String, AiCoachMath.AiCritique> updated = putBoundedFirst(
                fileKey, critique, AiCoachMath.decodeCritiqueLog(sp.getString(KEY_AI_CRITIQUES_LOG, "")));
        sp.edit().putString(KEY_AI_CRITIQUES_LOG, AiCoachMath.encodeCritiqueLog(updated)).apply();
    }

    public AiCoachMath.AiCritique getAiCritique(String fileKey) {
        if (fileKey == null || fileKey.isEmpty()) return null;
        return AiCoachMath.decodeCritiqueLog(sp.getString(KEY_AI_CRITIQUES_LOG, "")).get(fileKey);
    }

    private static <V> java.util.Map<String, V> putBoundedFirst(
            String key, V value, java.util.Map<String, V> existing) {
        java.util.Map<String, V> updated = new java.util.LinkedHashMap<>();
        updated.put(key, value);
        for (java.util.Map.Entry<String, V> e : existing.entrySet()) {
            if (updated.size() >= 48) break;
            if (!key.equals(e.getKey())) {
                updated.put(e.getKey(), e.getValue());
            }
        }
        return updated;
    }
}
