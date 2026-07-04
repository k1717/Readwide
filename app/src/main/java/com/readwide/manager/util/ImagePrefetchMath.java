package com.readwide.manager.util;

/**
 * Offset planning for image-sequence prefetch.
 *
 * <p>Continuous paging through a sequential archive (RAR/7z/compressed TAR)
 * is decode-bound: the shared forward stream can only produce entries so
 * fast, and a fixed symmetric +-3 window re-planned on every page turn
 * leaves that stream idle exactly when the reader is about to need eight
 * more pages in the same direction. These helpers make the plan
 * direction-aware: once the user shows a sustained direction, the
 * file-extraction window deepens ahead of them (and keeps one page behind
 * for the occasional step back), so the stream converts reading pauses into
 * cache instead of idling. The bitmap-decode window stays at the nearest
 * neighbors regardless - decoded bitmaps cost real memory, extracted files
 * do not.</p>
 */
public final class ImagePrefetchMath {
    /** File-extraction look-ahead depth once a paging direction is sustained. */
    public static final int DIRECTED_AHEAD_DEPTH = 8;
    /** Pages kept ready behind the sustained direction. */
    public static final int DIRECTED_BEHIND_DEPTH = 1;
    /** Consecutive same-direction page turns before the window goes directional. */
    public static final int SUSTAINED_TURNS = 2;

    private ImagePrefetchMath() {
    }

    /**
     * Updates the sustained-direction streak. Returns the new streak value:
     * positive counts consecutive forward turns, negative counts consecutive
     * backward turns, zero for jumps and direction changes that broke the
     * streak (the changed direction itself starts a new streak of one).
     */
    public static int updateStreak(int previousStreak, int indexDelta) {
        if (indexDelta == 1) return previousStreak >= 0 ? previousStreak + 1 : 1;
        if (indexDelta == -1) return previousStreak <= 0 ? previousStreak - 1 : -1;
        return 0; // slider jumps and multi-page moves reset the streak
    }

    /** Returns -1, 0 or +1: the sustained direction implied by the streak. */
    public static int sustainedDirection(int streak) {
        if (streak >= SUSTAINED_TURNS) return 1;
        if (streak <= -SUSTAINED_TURNS) return -1;
        return 0;
    }

    /**
     * Offsets for archive file extraction, nearest first. Symmetric +-3 when
     * no direction is sustained (the historical plan); deep-ahead when one is.
     */
    public static int[] extractionOffsets(int sustainedDirection) {
        if (sustainedDirection == 0) {
            return new int[] {1, -1, 2, -2, 3, -3};
        }
        int[] offsets = new int[DIRECTED_AHEAD_DEPTH + DIRECTED_BEHIND_DEPTH];
        int out = 0;
        // Nearest pages first, with the single behind page planned early so a
        // quick step back stays instant, then the deep ahead run.
        offsets[out++] = sustainedDirection;
        offsets[out++] = -sustainedDirection;
        for (int step = 2; step <= DIRECTED_AHEAD_DEPTH; step++) {
            offsets[out++] = step * sustainedDirection;
        }
        return offsets;
    }

    /** Offsets for decoded-bitmap warm-up; always the nearest neighbors only. */
    public static int[] bitmapOffsets() {
        return new int[] {1, -1, 2, -2, 3, -3};
    }
}
