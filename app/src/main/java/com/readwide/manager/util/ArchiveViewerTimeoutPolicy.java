package com.readwide.manager.util;

/** Pure input/deadline rules; timestamps are elapsed realtime, never wall time. */
public final class ArchiveViewerTimeoutPolicy {
    public static final int MAX_MINUTES = 10_080;

    private ArchiveViewerTimeoutPolicy() {}

    public static int normalizeMinutes(int minutes) {
        return Math.max(0, Math.min(MAX_MINUTES, minutes));
    }

    /** Empty/invalid edits retain the previous value; only explicit zero disables. */
    public static int parseMinutes(CharSequence input, int previousMinutes) {
        String raw = input == null ? "" : input.toString().trim();
        if (raw.isEmpty()) return normalizeMinutes(previousMinutes);
        int value = 0;
        for (int i = 0; i < raw.length(); i++) {
            char digit = raw.charAt(i);
            if (digit < '0' || digit > '9') return normalizeMinutes(previousMinutes);
            // Saturate before the next digit, so arbitrarily long input cannot overflow.
            value = Math.min(MAX_MINUTES, value * 10 + digit - '0');
        }
        return value;
    }

    /** -1 means inactive/invalid; zero means expired, otherwise delay until expiry. */
    public static long remainingMillis(int minutes, long stoppedAt, long now) {
        int normalized = normalizeMinutes(minutes);
        if (normalized == 0 || stoppedAt < 0L || now < stoppedAt) return -1L;
        return Math.max(0L, normalized * 60_000L - (now - stoppedAt));
    }

    /** Only compare persisted elapsed timestamps when both belong to the same boot. */
    public static long restoreStoppedAt(long savedAt, int savedBoot, int currentBoot, long now) {
        return savedBoot >= 0 && savedBoot == currentBoot && savedAt >= 0L && savedAt <= now
                ? savedAt : -1L;
    }
}
