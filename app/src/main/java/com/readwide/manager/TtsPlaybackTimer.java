package com.readwide.manager;

/** Playback-only elapsed time. The controller supplies a monotonic clock. */
final class TtsPlaybackTimer {
    private long targetMs;
    private long accruedMs;
    private long startedAt = -1L;
    private boolean finishSentence;

    void reset(int minutes, boolean finishSentence) {
        accruedMs = 0L;
        startedAt = -1L;
        configure(minutes, finishSentence);
    }

    void configure(int minutes, boolean finishSentence) {
        targetMs = Math.max(0, minutes) * 60_000L;
        this.finishSentence = finishSentence;
    }

    void start(long now) {
        // Idempotence prevents duplicate start callbacks from losing elapsed time.
        if (startedAt < 0L) startedAt = now;
    }

    void pause(long now) {
        accruedMs = elapsed(now);
        startedAt = -1L;
    }

    long elapsed(long now) {
        return accruedMs + (startedAt < 0L ? 0L : Math.max(0L, now - startedAt));
    }

    long deadlineDelay(long now) {
        if (targetMs <= 0L || finishSentence || startedAt < 0L) return -1L;
        return Math.max(0L, targetMs - elapsed(now));
    }

    boolean shouldStop(long now, boolean atSentenceBoundary) {
        return targetMs > 0L && elapsed(now) >= targetMs
                && (!finishSentence || atSentenceBoundary);
    }
}
