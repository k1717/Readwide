package com.readwide.manager.util;

/** Small per-window scroll sampler; it owns no text, I/O, callbacks or page model. */
public final class LargeTextScrollPrefetchState {
    private static final long MAX_SAMPLE_GAP_MS = 250L;
    private static final long FAST_HORIZON_HOLD_MS = 300L;
    private static final long RETRY_INTERVAL_MS = 500L;
    private long previousTime = Long.MIN_VALUE;
    private int previousY;
    private long fastUntil = Long.MIN_VALUE;
    private long lastRequestTime = Long.MIN_VALUE;

    public void reset() {
        previousTime = Long.MIN_VALUE;
        previousY = 0;
        fastUntil = Long.MIN_VALUE;
        lastRequestTime = Long.MIN_VALUE;
    }

    /** Two viewports normally, four briefly when moving forward at >= two/sec. */
    public int observeScroll(int scrollY, long now, int viewportHeight) {
        if (previousTime != Long.MIN_VALUE) {
            long elapsed = now - previousTime;
            long movement = (long) scrollY - previousY;
            if (movement < 0 || elapsed < 0 || elapsed > MAX_SAMPLE_GAP_MS) {
                fastUntil = Long.MIN_VALUE;
            } else if (elapsed > 0 && movement > 0
                    && movement * 500L >= (long) Math.max(1, viewportHeight) * elapsed) {
                fastUntil = now + FAST_HORIZON_HOLD_MS;
            }
        }
        previousTime = now;
        previousY = scrollY;
        return now <= fastUntil ? 4 : 2;
    }

    /** A failed/cancelled speculative read must not be relaunched on every frame. */
    public boolean shouldRequest(int projectedChar, int bodyEnd, long now) {
        if (bodyEnd <= 0 || projectedChar < bodyEnd) return false;
        if (lastRequestTime != Long.MIN_VALUE && now >= lastRequestTime
                && now - lastRequestTime < RETRY_INTERVAL_MS) return false;
        lastRequestTime = now;
        return true;
    }
}
