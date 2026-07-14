package com.readwide.manager;

/** Pure layout policy for reserving the measured visible reader toolbar frame. */
final class ReaderChromeLayoutMath {
    private ReaderChromeLayoutMath() {}

    /**
     * PDF uses an orientation-specific frame which is independent of chrome
     * visibility: portrait keeps the measured toolbar-ON reserve for the
     * current system-bar configuration, while
     * landscape keeps the toolbar-OFF safe strip.
     */
    static int pdfTopReserve(boolean landscape,
                             int fullscreenSafePx,
                             int cachedPortraitToolbarPx,
                             int livePortraitToolbarPx,
                             int portraitFallbackPx) {
        if (landscape) return Math.max(0, fullscreenSafePx);
        if (cachedPortraitToolbarPx > 0) return cachedPortraitToolbarPx;
        if (livePortraitToolbarPx > 0) return livePortraitToolbarPx;
        return Math.max(0, portraitFallbackPx);
    }

    /** Landscape chrome overlays the full canvas; portrait keeps its ON reserve. */
    static int pdfBottomReserve(boolean landscape,
                                int cachedPortraitToolbarPx,
                                int livePortraitToolbarPx,
                                int portraitFallbackPx) {
        if (landscape) return 0;
        if (cachedPortraitToolbarPx > 0) return cachedPortraitToolbarPx;
        if (livePortraitToolbarPx > 0) return livePortraitToolbarPx;
        return Math.max(0, portraitFallbackPx);
    }

    /**
     * A cached portrait reserve is valid only for the same expected system-bar
     * frame. Reading preferences and gesture/3-button navigation can change
     * that frame while the Activity remains alive.
     */
    static int compatibleCachedReserve(int cachedPx, int expectedPx, int tolerancePx) {
        if (cachedPx <= 0 || expectedPx <= 0) return 0;
        long difference = Math.abs((long) cachedPx - expectedPx);
        return difference <= Math.max(0, tolerancePx) ? cachedPx : 0;
    }

    static int toolbarOnReserve(int cachedExpandedPx,
                                int liveMeasuredPx,
                                boolean chromeVisible,
                                int fallbackPx) {
        if (chromeVisible && liveMeasuredPx > 0) return liveMeasuredPx;
        if (cachedExpandedPx > 0) return cachedExpandedPx;
        if (!chromeVisible && fallbackPx > 0) return fallbackPx;
        if (liveMeasuredPx > 0) return liveMeasuredPx;
        return Math.max(0, fallbackPx);
    }
}
