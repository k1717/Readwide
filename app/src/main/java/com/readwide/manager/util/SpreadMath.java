package com.readwide.manager.util;

/**
 * Pure math for the landscape two-page spread, shared by the document (EPUB)
 * and PDF viewers so their paging semantics cannot drift apart. Activities keep
 * most mode gates (document type / continuous-mode checks); everything
 * index-related and the EPUB large-screen policy lives here and is unit-tested
 * off-device.
 */
public final class SpreadMath {

    private SpreadMath() {
    }

    /** Pages advanced per user page-turn: 2 in the spread, 1 otherwise. */
    public static int displayStep(boolean spreadActive) {
        return spreadActive ? 2 : 1;
    }

    /**
     * EPUB spread policy. Image-page publications keep the existing landscape
     * spread on every device. Reflowable/text publications gain the same
     * side-by-side layout only on Android large screens (sw600dp or wider), so
     * a narrow phone in landscape is not forced into two cramped WebViews.
     */
    public static boolean shouldUseEpubSpread(boolean landscape,
                                              int pageCount,
                                              boolean imagePageLike,
                                              int smallestScreenWidthDp) {
        return landscape
                && pageCount > 1
                && (imagePageLike || smallestScreenWidthDp >= 600);
    }

    /**
     * Index of the spread's right page, or -1 when there is none (spread off,
     * or the current left page is the last page).
     */
    public static int rightIndex(int currentPage, int pageCount, boolean spreadActive) {
        if (!spreadActive) return -1;
        int right = currentPage + 1;
        return right >= 0 && right < pageCount ? right : -1;
    }

    /** Clamps a page index into [0, pageCount-1]; 0 when there are no pages. */
    public static int clampIndex(int page, int pageCount) {
        if (pageCount <= 0) return 0;
        if (page < 0) return 0;
        return Math.min(page, pageCount - 1);
    }

    /** Last visible page index for the current display (left page or right spread page). */
    public static int visibleEndIndex(int currentPage, int pageCount, boolean spreadActive) {
        int current = clampIndex(currentPage, pageCount);
        int right = rightIndex(current, pageCount, spreadActive);
        return right >= 0 ? right : current;
    }

    /**
     * Target page for a user page-turn. A forward spread turn is all-or-nothing:
     * when there is no new page beyond the currently visible spread, it stays on
     * the current page instead of clamping to the already-visible last page.
     * This prevents an even-page document from showing its final page twice
     * (first as the right page of the last spread, then again by itself).
     */
    public static int turnTarget(int currentPage, int direction, int pageCount, boolean spreadActive) {
        int current = clampIndex(currentPage, pageCount);
        int sign = Integer.signum(direction);
        if (pageCount <= 0 || sign == 0) return current;
        int step = displayStep(spreadActive);
        if (sign > 0) {
            int target = current + step;
            return target < pageCount ? target : current;
        }
        return Math.max(0, current - step);
    }

    /** True when a user page-turn would move to a different display start page. */
    public static boolean canTurn(int currentPage, int direction, int pageCount, boolean spreadActive) {
        int current = clampIndex(currentPage, pageCount);
        return turnTarget(current, direction, pageCount, spreadActive) != current;
    }
}
