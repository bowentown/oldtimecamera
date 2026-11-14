package com.bowentown.oldtimecamera;

import java.util.EnumSet;
import java.util.Set;

/**
 * Pure-Java scene classifier that maps raw {@link PreviewStats} and face presence
 * into a suggested vintage film stock with temporal stabilization and dismiss memory.
 */
public final class SceneClassifier {

    public enum Scene {
        GENERAL(null, null),
        NIGHT("cinestill800t", "800T"),
        PORTRAIT("portra400", "PORTRA"),
        BACKLIT("gold200", "GOLD 200"),
        GOLDEN_HOUR("gold200", "GOLD 200"),
        COOL_OVERCAST("superia400", "SUPERIA"),
        VIVID_LANDSCAPE("velvia50", "VELVIA 50"),
        LOW_SATURATION("trix400", "TRI-X");

        public final String presetId;
        public final String shortLabel;

        Scene(String presetId, String shortLabel) {
            this.presetId = presetId;
            this.shortLabel = shortLabel;
        }
    }

    public static final class Recommendation {
        public final Scene scene;
        public final String presetId;
        public final String shortLabel;

        Recommendation(Scene scene, String presetId, String shortLabel) {
            this.scene = scene;
            this.presetId = presetId;
            this.shortLabel = shortLabel;
        }
    }

    private static final int STABLE_SAMPLES_REQUIRED = 3;

    private final Set<Scene> dismissedScenes = EnumSet.noneOf(Scene.class);
    private Scene activeScene = Scene.GENERAL;
    private Scene candidateScene = Scene.GENERAL;
    private int candidateStreak = 0;

    public void reset() {
        activeScene = Scene.GENERAL;
        candidateScene = Scene.GENERAL;
        candidateStreak = 0;
        dismissedScenes.clear();
    }

    public void dismiss(Scene scene) {
        if (scene != null && scene != Scene.GENERAL) {
            dismissedScenes.add(scene);
        }
    }

    public Scene getActiveScene() {
        return activeScene;
    }

    /**
     * Classifies the scene with temporal smoothing. Never auto-switches preset; returns {@code null}
     * if scene is general, already uses the target film stock, or was dismissed by the user.
     */
    public Recommendation evaluate(PreviewStats stats, boolean hasFace, String currentPresetId) {
        if (stats == null) {
            return null;
        }
        Scene instant = classifyInstant(stats, hasFace);
        if (instant == candidateScene) {
            candidateStreak++;
        } else {
            candidateScene = instant;
            candidateStreak = 1;
        }

        if (candidateStreak >= STABLE_SAMPLES_REQUIRED) {
            activeScene = candidateScene;
        }

        if (activeScene == Scene.GENERAL || dismissedScenes.contains(activeScene)) {
            return null;
        }

        String presetId = activeScene.presetId;
        String label = activeScene.shortLabel;
        if (activeScene == Scene.BACKLIT && hasFace) {
            presetId = "portra400";
            label = "PORTRA";
        }
        if (presetId == null || presetId.equals(currentPresetId)) {
            return null;
        }
        return new Recommendation(activeScene, presetId, label);
    }

    public static Scene classifyInstant(PreviewStats s, boolean hasFace) {
        if (s == null) return Scene.GENERAL;

        boolean backlit = s.borderLuma >= 0.62f && s.centerLuma <= 0.34f
                && (s.borderLuma - s.centerLuma) >= 0.25f;
        if (backlit) {
            return Scene.BACKLIT;
        }
        if (hasFace && s.meanLuma >= 0.20f) {
            return Scene.PORTRAIT;
        }
        if (s.meanLuma <= 0.22f) {
            return Scene.NIGHT;
        }
        if (s.meanSaturation <= 0.13f && s.meanLuma > 0.22f && s.meanLuma < 0.78f) {
            return Scene.LOW_SATURATION;
        }
        if ((s.avgR - s.avgB) >= 0.085f && s.meanSaturation >= 0.24f
                && s.meanLuma >= 0.26f && s.meanLuma <= 0.76f) {
            return Scene.GOLDEN_HOUR;
        }
        if ((s.avgB - s.avgR) >= 0.055f && s.meanSaturation >= 0.16f && s.meanLuma > 0.22f) {
            return Scene.COOL_OVERCAST;
        }
        if (s.meanSaturation >= 0.38f && s.avgG >= s.avgR * 0.90f && s.meanLuma >= 0.30f) {
            return Scene.VIVID_LANDSCAPE;
        }
        return Scene.GENERAL;
    }
}
