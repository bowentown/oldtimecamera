package com.bowentown.oldtimecamera;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java beginner "Theme Practice Rolls" (6 frames each) and post-shot
 * One-Change micro-review engine.
 */
public final class PracticeRoll {

    public static final int ROLL_FRAMES = 6;

    public enum Theme {
        COMP_BASICS,
        LIGHT_METER,
        STREET_RHYTHM;

        public static Theme fromName(String name) {
            if (name != null) {
                for (Theme t : values()) {
                    if (t.name().equals(name)) return t;
                }
            }
            return null;
        }
    }

    public static final class StepSpec {
        public final String titleZh;
        public final String titleEn;
        public final String coachZh;
        public final String coachEn;
        public final CompositionMath.GridMode recommendedGrid;
        public final CompositionMath.GuideTemplate recommendedTemplate;

        StepSpec(String titleZh, String titleEn, String coachZh, String coachEn,
                 CompositionMath.GridMode recommendedGrid,
                 CompositionMath.GuideTemplate recommendedTemplate) {
            this.titleZh = titleZh;
            this.titleEn = titleEn;
            this.coachZh = coachZh;
            this.coachEn = coachEn;
            this.recommendedGrid = recommendedGrid;
            this.recommendedTemplate = recommendedTemplate;
        }

        public String title(boolean zh) {
            return zh ? titleZh : titleEn;
        }

        public String coach(boolean zh) {
            return zh ? coachZh : coachEn;
        }
    }

    public static final class ShotDiagnostic {
        public final String badge;
        public final String headline;
        public final String nextTip;
        public final boolean missionPassed;

        public ShotDiagnostic(boolean missionPassed, String badge, String headline, String nextTip) {
            this.missionPassed = missionPassed;
            this.badge = badge;
            this.headline = headline;
            this.nextTip = nextTip;
        }

        public String singleLine() {
            if (nextTip != null && !nextTip.isEmpty()) {
                return headline + " · " + nextTip;
            }
            return headline;
        }
    }

    private static final StepSpec[] COMP_STEPS = {
            new StepSpec("三分交点", "Thirds Point", "把主角放进任意一条三分交点环", "Place subject on a thirds intersection",
                    CompositionMath.GridMode.THIRDS, CompositionMath.GuideTemplate.NONE),
            new StepSpec("人像留白", "Headroom", "人物眼睛贴齐上三分线，头顶留空", "Align eyes to upper third with headroom",
                    CompositionMath.GridMode.THIRDS, CompositionMath.GuideTemplate.PORTRAIT_THIRDS),
            new StepSpec("留白呼吸", "Negative Space", "主体占下三分之一，留出大片天空", "Keep subject low, leave open space above",
                    CompositionMath.GridMode.THIRDS, CompositionMath.GuideTemplate.BACK_SILHOUETTE),
            new StepSpec("黄金螺旋", "Golden Spiral", "顺着螺旋曲率把焦点落到卷心", "Lead eye curves into the spiral eye",
                    CompositionMath.GridMode.GOLDEN_SPIRAL, CompositionMath.GuideTemplate.NONE),
            new StepSpec("对角张力", "Diagonal", "沿对角线摆放静物或街头延伸线", "Place lines or props along the diagonal",
                    CompositionMath.GridMode.OFF, CompositionMath.GuideTemplate.GOLDEN_DIAGONAL),
            new StepSpec("构图毕业", "Comp Finale", "综合构图法则拍下本卷最后一格", "Combine any composition rule cleanly",
                    CompositionMath.GridMode.THIRDS, CompositionMath.GuideTemplate.NONE)
    };

    private static final StepSpec[] LIGHT_STEPS = {
            new StepSpec("守住高光", "Protect Highlights", "看光表直方条：右端不过曝溢顶", "Keep rightmost luma bar off the ceiling",
                    CompositionMath.GridMode.OFF, CompositionMath.GuideTemplate.NONE),
            new StepSpec("暗调氛围", "Moody Exposure", "尝试下压半档 EV 保留立体暗影", "Dial EV slightly down for rich shadow mood",
                    CompositionMath.GridMode.OFF, CompositionMath.GuideTemplate.NONE),
            new StepSpec("逆光测光", "Backlit Subject", "迎光拍摄时轻点人物面部对焦点测光", "Tap face/subject in backlight to meter",
                    CompositionMath.GridMode.THIRDS, CompositionMath.GuideTemplate.NONE),
            new StepSpec("暖调光影", "Warm Side Light", "捕捉窗边或斜阳的明暗交界质感", "Frame warm directional light on side",
                    CompositionMath.GridMode.THIRDS, CompositionMath.GuideTemplate.PORTRAIT_THIRDS),
            new StepSpec("黑白反差", "B&W Tones", "用黑白卷拍下高对比纯影调", "Shoot clear light-dark contrast in B&W",
                    CompositionMath.GridMode.OFF, CompositionMath.GuideTemplate.NONE),
            new StepSpec("测光毕业", "Meter Finale", "让底部光表指针靠近 0 位稳拍一格", "Center meter needle near 0 for clean exposure",
                    CompositionMath.GridMode.OFF, CompositionMath.GuideTemplate.NONE)
    };

    private static final StepSpec[] STREET_STEPS = {
            new StepSpec("端正对称", "Level Symmetry", "打开水平仪卡准中轴拍建筑门框", "Align center axis with horizon level",
                    CompositionMath.GridMode.CROSSHAIR, CompositionMath.GuideTemplate.SYMMETRY),
            new StepSpec("低角仰拍", "Low Angle", "蹲低机位向上仰拍突出纵深线条", "Shoot from low angle looking upward",
                    CompositionMath.GridMode.OFF, CompositionMath.GuideTemplate.LOW_ANGLE),
            new StepSpec("半格双拼", "Half-Frame Pair", "切到 ½ 半格连拍一组两张对比故事", "Use ½ Half-Frame to shoot a 2-shot story",
                    CompositionMath.GridMode.OFF, CompositionMath.GuideTemplate.NONE),
            new StepSpec("黑柔光夜色", "Black Mist Glow", "开黑柔光捕获灯牌或高光柔散光晕", "Enable Black Mist around glowing lights",
                    CompositionMath.GridMode.OFF, CompositionMath.GuideTemplate.NONE),
            new StepSpec("四格定格", "4-Frame Strip", "切到 4格模式抓拍一段连续小动作", "Switch to 4F strip for continuous motion",
                    CompositionMath.GridMode.OFF, CompositionMath.GuideTemplate.NONE),
            new StepSpec("街头毕业", "Street Finale", "稳稳握持，用最顺手的模式收尾本卷", "Hold steady and finish your street roll",
                    CompositionMath.GridMode.THIRDS, CompositionMath.GuideTemplate.NONE)
    };

    private PracticeRoll() {}

    public static String themeTitle(Theme theme, boolean zh) {
        if (theme == null) return "";
        switch (theme) {
            case COMP_BASICS:
                return zh ? "卷一 · 三分与留白" : "Roll I · Thirds & Space";
            case LIGHT_METER:
                return zh ? "卷二 · 光线与测光" : "Roll II · Light & Meter";
            case STREET_RHYTHM:
                return zh ? "卷三 · 水平与街头" : "Roll III · Street & Rhythm";
            default:
                return "";
        }
    }

    public static String themeShortLabel(Theme theme, boolean zh) {
        if (theme == null) return "";
        switch (theme) {
            case COMP_BASICS:
                return zh ? "练习·构图" : "LAB·COMP";
            case LIGHT_METER:
                return zh ? "练习·测光" : "LAB·LIGHT";
            case STREET_RHYTHM:
                return zh ? "练习·街头" : "LAB·STREET";
            default:
                return "";
        }
    }

    public static StepSpec stepSpec(Theme theme, int stepIndex) {
        StepSpec[] arr;
        if (theme == Theme.LIGHT_METER) {
            arr = LIGHT_STEPS;
        } else if (theme == Theme.STREET_RHYTHM) {
            arr = STREET_STEPS;
        } else {
            arr = COMP_STEPS;
        }
        int clamped = Math.max(0, Math.min(ROLL_FRAMES - 1, stepIndex));
        return arr[clamped];
    }

    /**
     * Evaluates a captured frame according to the One-Change Rule and optional mission objective.
     * Prioritizes the single most helpful improvement rather than flooding the beginner.
     */
    public static ShotDiagnostic evaluateShot(
            PreviewStats stats,
            CompositionMath.FramingAdvice framing,
            boolean stableAtShutter,
            boolean hasFace,
            boolean levelActive,
            float tiltDegrees,
            int evIndex,
            boolean blackMistEnabled,
            boolean grayscalePreset,
            CameraPrefs.ShootMode shootMode,
            Theme activeTheme,
            int stepIndex,
            boolean zh) {

        float needleStops = stats != null ? FilmRenderMath.meterNeedleStops(stats) : 0f;
        boolean overClip = stats != null && (stats.clippedHighlights >= 0.14f || needleStops > 1.25f);
        boolean tooDark = stats != null && (stats.meanLuma <= 0.19f || (stats.crushedShadows >= 0.50f && needleStops < -1.1f));
        boolean backlit = stats != null && stats.borderLuma >= 0.62f && stats.centerLuma <= 0.34f
                && (stats.borderLuma - stats.centerLuma) >= 0.24f;
        boolean tilted = levelActive && Math.abs(tiltDegrees - Math.round(tiltDegrees / 90f) * 90f) > 3.0f;
        boolean compLocked = framing != null && framing.hasSubject && framing.locked;
        boolean headroomTight = framing != null && framing.headroomTight;

        boolean missionPassed = evaluateMissionStep(
                activeTheme, stepIndex, stats, compLocked, headroomTight, stableAtShutter,
                levelActive, tilted, evIndex, blackMistEnabled, grayscalePreset, shootMode, needleStops);

        // One-Change Rule: report 1 clear observation + 1 next step.
        if (!stableAtShutter) {
            return new ShotDiagnostic(missionPassed,
                    zh ? "防抖" : "STEADY",
                    zh ? "按快门时略有晃动" : "Slight shake at shutter",
                    zh ? "下次夹紧手肘呼吸停半秒再按" : "Tuck elbows & exhale for next shot");
        }
        if (backlit) {
            return new ShotDiagnostic(missionPassed,
                    zh ? "逆光" : "BACKLIT",
                    zh ? "背景偏亮压暗了主体" : "Bright surround darkened subject",
                    zh ? "下次轻点画面主体或+EV补亮" : "Tap subject face or nudge EV +");
        }
        if (overClip) {
            return new ShotDiagnostic(missionPassed,
                    zh ? "高光" : "HIGHLIGHT",
                    zh ? "亮部高光略有过曝" : "Highlights slightly clipped",
                    zh ? "下次可下压半档 EV 留住天空纹理" : "Dial EV down slightly to keep highlights");
        }
        if (tooDark) {
            return new ShotDiagnostic(missionPassed,
                    zh ? "欠曝" : "SHADOW",
                    zh ? "整体偏暗细节较闷" : "Frame leans underexposed",
                    zh ? "下次可+0.5 EV或迎向光源转身" : "Nudge EV up or face toward soft light");
        }
        if (headroomTight) {
            return new ShotDiagnostic(missionPassed,
                    zh ? "留白" : "HEADROOM",
                    zh ? "人像头顶空间偏紧" : "Tight headroom above subject",
                    zh ? "下次略微抬高机位给头顶留空" : "Tilt up slightly to add headroom");
        }
        if (tilted) {
            return new ShotDiagnostic(missionPassed,
                    zh ? "水平" : "HORIZON",
                    zh ? "地平线略微倾斜" : "Horizon tilted slightly",
                    zh ? "下次瞄准绿色/琥珀水平卡点再拍" : "Wait for amber horizon lock tick");
        }
        if (compLocked) {
            return new ShotDiagnostic(missionPassed,
                    zh ? "构图 ✓" : "COMP ✓",
                    zh ? "主体落点很稳，层次清楚" : "Crisp framing on composition point",
                    zh ? "试试换个低角度或逆光继续出片" : "Try a lower angle or warm light next");
        }
        if (stats != null && Math.abs(needleStops) <= 0.7f) {
            return new ShotDiagnostic(missionPassed,
                    zh ? "测光 ✓" : "METER ✓",
                    zh ? "明暗分布均衡，高光干净" : "Balanced tones with clean highlights",
                    hasFace
                            ? (zh ? "可将人物面部再靠近三分交点" : "Nudge face closer to thirds anchor")
                            : (zh ? "画面干净，可继续尝试不同胶卷" : "Clean roll frame — explore stocks"));
        }
        return new ShotDiagnostic(missionPassed,
                zh ? "记录" : "LOG",
                zh ? "成片已显影归档" : "Frame developed cleanly",
                zh ? "点相册可查看 EXIF 参数与印样" : "Check Archive for EXIF & contact sheet");
    }

    private static boolean evaluateMissionStep(
            Theme theme, int stepIndex, PreviewStats stats,
            boolean compLocked, boolean headroomTight, boolean stable,
            boolean levelActive, boolean tilted, int evIndex,
            boolean mistEnabled, boolean grayscale, CameraPrefs.ShootMode shootMode,
            float needleStops) {
        if (theme == null) return true;
        int s = Math.max(0, Math.min(ROLL_FRAMES - 1, stepIndex));
        if (theme == Theme.COMP_BASICS) {
            if (!stable) return false;
            switch (s) {
                case 0: return compLocked;
                case 1: return !headroomTight;
                case 2: return stats == null || stats.borderLuma >= 0.20f;
                default: return true;
            }
        } else if (theme == Theme.LIGHT_METER) {
            switch (s) {
                case 0: return stats == null || stats.clippedHighlights <= 0.10f;
                case 1: return evIndex <= 0 && (stats == null || stats.clippedHighlights <= 0.08f);
                case 2: return stats == null || stats.centerLuma >= 0.24f;
                case 4: return grayscale;
                case 5: return Math.abs(needleStops) <= 0.85f && stable;
                default: return true;
            }
        } else {
            switch (s) {
                case 0: return (!levelActive || !tilted) && stable;
                case 2: return shootMode == CameraPrefs.ShootMode.HALF_FRAME;
                case 3: return mistEnabled;
                case 4: return shootMode == CameraPrefs.ShootMode.QUAD_STRIP;
                default: return stable;
            }
        }
    }

    /** Encodes at most 48 recent shot diagnostics (keyed by filename or URI tail) cleanly. */
    public static String encodeDiagnosticLog(Map<String, ShotDiagnostic> log) {
        if (log == null || log.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, ShotDiagnostic> e : log.entrySet()) {
            if (count >= 48) break;
            ShotDiagnostic d = e.getValue();
            if (e.getKey() == null || d == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(clean(e.getKey())).append('\t')
                    .append(d.missionPassed ? "1" : "0").append('\t')
                    .append(clean(d.badge)).append('\t')
                    .append(clean(d.headline)).append('\t')
                    .append(clean(d.nextTip));
            count++;
        }
        return sb.toString();
    }

    public static Map<String, ShotDiagnostic> decodeDiagnosticLog(String raw) {
        Map<String, ShotDiagnostic> map = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return map;
        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t", -1);
            if (parts.length < 5) continue;
            map.put(parts[0], new ShotDiagnostic("1".equals(parts[1]), parts[2], parts[3], parts[4]));
        }
        return map;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').trim();
    }
}
