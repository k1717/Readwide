package com.readwide.manager.util;

/**
 * Pure math for the landscape two-page spread, shared by the document (EPUB)
 * and PDF viewers so their paging semantics cannot drift apart. Activities keep
 * their own mode gates (orientation / document type / continuous-mode checks);
 * everything index-related lives here and is unit-tested off-device.
 */
public final class SpreadMath {

    private SpreadMath() {
    }

    /** Pages advanced per user page-turn: 2 in the spread, 1 otherwise. */
    public static int displayStep(boolean spreadActive) {
        return spreadActive ? 2 : 1;
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

    /**
     * Target page for a user page-turn: current + direction * step, clamped.
     * The caller no-ops when the result equals the current page (both edges).
     */
    public static int turnTarget(int currentPage, int direction, int pageCount, boolean spreadActive) {
        return clampIndex(currentPage + direction * displayStep(spreadActive), pageCount);
    }
}
