package com.readwide.manager;

/** Pure layout policy for reserving PDF chrome and system-safe edges. */
final class ReaderChromeLayoutMath {
    private ReaderChromeLayoutMath() {}

    /**
     * Visible PDF chrome owns its measured app-bar frame. Hidden chrome releases
     * that frame and keeps only the Android status-bar/display-cutout safe edge.
     */
    static int pdfTopReserve(boolean chromeVisible,
                             int hiddenSafePx,
                             int cachedToolbarPx,
                             int liveToolbarPx,
                             int visibleFallbackPx) {
        if (!chromeVisible) return Math.max(0, hiddenSafePx);
        if (liveToolbarPx > 0) return liveToolbarPx;
        if (cachedToolbarPx > 0) return cachedToolbarPx;
        return Math.max(0, visibleFallbackPx);
    }

    /** Hidden chrome keeps only the Android navigation/cutout safe edge. */
    static int pdfBottomReserve(boolean chromeVisible,
                                int hiddenSafePx,
                                int cachedToolbarPx,
                                int liveToolbarPx,
                                int visibleFallbackPx) {
        if (!chromeVisible) return Math.max(0, hiddenSafePx);
        if (liveToolbarPx > 0) return liveToolbarPx;
        if (cachedToolbarPx > 0) return cachedToolbarPx;
        return Math.max(0, visibleFallbackPx);
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

}
