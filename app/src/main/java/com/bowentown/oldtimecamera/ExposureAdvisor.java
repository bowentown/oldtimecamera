package com.bowentown.oldtimecamera;

/**
 * Pure-Java real-time exposure advisor operating on raw {@link PreviewStats}.
 * Applies hysteresis and tap cooldowns so single-line viewfinder prompts remain calm.
 */
public final class ExposureAdvisor {

    public enum Advice {
        NONE(0),
        OVEREXPOSED(-2),
        UNDEREXPOSED(2),
        BACKLIT(2);

        public final int suggestedEvDelta;

        Advice(int suggestedEvDelta) {
            this.suggestedEvDelta = suggestedEvDelta;
        }
    }

    private static final int TRIGGER_STREAK = 2;
    private static final long DEFAULT_SNOOZE_MS = 5000L;

    private Advice currentAdvice = Advice.NONE;
    private Advice pendingCandidate = Advice.NONE;
    private int candidateStreak = 0;
    private long snoozeUntilMs = 0L;

    public Advice getCurrentAdvice() {
        return currentAdvice;
    }

    public void reset() {
        currentAdvice = Advice.NONE;
        pendingCandidate = Advice.NONE;
        candidateStreak = 0;
        snoozeUntilMs = 0L;
    }

    /** Called after user taps the exposure hint to apply EV so it does not immediately re-nag. */
    public void onHintApplied(long nowMs) {
        currentAdvice = Advice.NONE;
        pendingCandidate = Advice.NONE;
        candidateStreak = 0;
        snoozeUntilMs = nowMs + DEFAULT_SNOOZE_MS;
    }

    /** Evaluates raw frame telemetry with hysteresis bands and temporal debounce. */
    public Advice evaluate(PreviewStats stats, long nowMs) {
        if (stats == null || nowMs < snoozeUntilMs) {
            return currentAdvice;
        }

        // First check if current state has cleanly exited its wider exit band.
        if (currentAdvice == Advice.BACKLIT) {
            if (stats.borderLuma - stats.centerLuma < 0.18f || stats.centerLuma > 0.42f) {
                currentAdvice = Advice.NONE;
            }
        } else if (currentAdvice == Advice.OVEREXPOSED) {
            if (stats.meanLuma < 0.72f && stats.clippedHighlights < 0.09f) {
                currentAdvice = Advice.NONE;
            }
        } else if (currentAdvice == Advice.UNDEREXPOSED) {
            if (stats.meanLuma > 0.25f && stats.crushedShadows < 0.42f) {
                currentAdvice = Advice.NONE;
            }
        }

        Advice raw = classifyInstant(stats);
        if (raw == Advice.NONE) {
            pendingCandidate = Advice.NONE;
            candidateStreak = 0;
            return currentAdvice;
        }

        if (raw == currentAdvice) {
            pendingCandidate = raw;
            candidateStreak = TRIGGER_STREAK;
            return currentAdvice;
        }

        if (raw == pendingCandidate) {
            candidateStreak++;
        } else {
            pendingCandidate = raw;
            candidateStreak = 1;
        }

        if (candidateStreak >= TRIGGER_STREAK) {
            currentAdvice = raw;
        }
        return currentAdvice;
    }

    private static Advice classifyInstant(PreviewStats s) {
        // Backlit takes precedence: bright perimeter, dark center subject.
        if (s.borderLuma >= 0.62f && s.centerLuma <= 0.34f
                && (s.borderLuma - s.centerLuma) >= 0.26f) {
            return Advice.BACKLIT;
        }
        if (s.clippedHighlights >= 0.16f || s.meanLuma >= 0.80f) {
            return Advice.OVEREXPOSED;
        }
        if (s.meanLuma <= 0.18f || (s.crushedShadows >= 0.52f && s.meanLuma <= 0.26f)) {
            return Advice.UNDEREXPOSED;
        }
        return Advice.NONE;
    }
}
