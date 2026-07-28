package com.readwide.manager.util;

/**
 * Pure navigation rules for the optional landscape archive-image spread.
 *
 * <p>A screen consumes two logical pages only when both pages are known to be
 * portrait/tall. Landscape and square images remain single-page screens, as
 * does the final unpaired page.</p>
 */
public final class ArchiveImageSpreadMath {
    private ArchiveImageSpreadMath() {}

    /** Excludes square and nearly-square art from comic-page pairing. */
    public static boolean isTallPage(int width, int height) {
        return width > 0
                && height > 0
                && (long) height * 10L >= (long) width * 11L;
    }

    public static boolean canShowPair(int startIndex,
                                      int itemCount,
                                      boolean firstPortrait,
                                      boolean secondPortrait) {
        return startIndex >= 0
                && startIndex + 1 < itemCount
                && firstPortrait
                && secondPortrait;
    }

    public static int visibleEndIndex(int startIndex,
                                      int itemCount,
                                      boolean pairVisible) {
        int start = ImageSequenceNavigationMath.clampIndex(startIndex, itemCount);
        return pairVisible && start + 1 < itemCount ? start + 1 : start;
    }

    public static int forwardTarget(int currentIndex,
                                    int itemCount,
                                    boolean currentPairVisible) {
        int step = currentPairVisible ? 2 : 1;
        return ImageSequenceNavigationMath.nextIndex(currentIndex, step, itemCount);
    }

    /**
     * Finds the previous screen start without assuming that reading began at
     * page zero. {@code pairAtStart[i]} means pages {@code i} and {@code i+1}
     * are a valid portrait pair.
     *
     * <p>This local rule matters when a saved position opens on an odd page:
     * after showing {@code 1-2} and advancing to {@code 3-4}, Back must return
     * to {@code 1}, not rebuild an overlapping {@code 2-3} spread from a
     * page-zero partition.</p>
     */
    public static int previousTarget(int currentIndex,
                                     int itemCount,
                                     boolean[] pairAtStart) {
        int current = ImageSequenceNavigationMath.clampIndex(currentIndex, itemCount);
        if (current <= 0) return 0;
        int pairCandidate = current - 2;
        boolean previousWasPair = pairCandidate >= 0
                && pairAtStart != null
                && pairCandidate < pairAtStart.length
                && pairAtStart[pairCandidate]
                && pairCandidate + 1 < itemCount;
        return previousWasPair ? pairCandidate : current - 1;
    }
}
