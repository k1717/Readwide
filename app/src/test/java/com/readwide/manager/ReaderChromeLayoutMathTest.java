package com.readwide.manager;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ReaderChromeLayoutMathTest {
    @Test
    public void landscapeAlwaysUsesToolbarOffFrame() {
        assertEquals(24, ReaderChromeLayoutMath.pdfTopReserve(
                true, 24, 180, 220, 144));
        assertEquals(0, ReaderChromeLayoutMath.pdfBottomReserve(
                true, 320, 400, 288));
    }

    @Test
    public void portraitToggleCannotReplaceCachedToolbarOnFrame() {
        assertEquals(180, ReaderChromeLayoutMath.pdfTopReserve(
                false, 0, 180, 0, 144));
        assertEquals(180, ReaderChromeLayoutMath.pdfTopReserve(
                false, 0, 180, 220, 144));
        assertEquals(320, ReaderChromeLayoutMath.pdfBottomReserve(
                false, 320, 0, 288));
        assertEquals(320, ReaderChromeLayoutMath.pdfBottomReserve(
                false, 320, 400, 288));
    }

    @Test
    public void portraitFrameInitializesFromLiveMeasurementOrFallback() {
        assertEquals(176, ReaderChromeLayoutMath.pdfTopReserve(
                false, 0, 0, 176, 144));
        assertEquals(144, ReaderChromeLayoutMath.pdfTopReserve(
                false, 0, 0, 0, 144));
        assertEquals(304, ReaderChromeLayoutMath.pdfBottomReserve(
                false, 0, 304, 288));
        assertEquals(288, ReaderChromeLayoutMath.pdfBottomReserve(
                false, 0, 0, 288));
    }

    @Test
    public void portraitCacheIsInvalidatedWhenSystemBarFrameChanges() {
        assertEquals(180, ReaderChromeLayoutMath.compatibleCachedReserve(180, 181, 2));
        assertEquals(0, ReaderChromeLayoutMath.compatibleCachedReserve(180, 205, 2));
        assertEquals(0, ReaderChromeLayoutMath.compatibleCachedReserve(0, 205, 2));
    }

    @Test
    public void hiddenCompactStripCannotReplaceExpandedToolbarReserve() {
        assertEquals(168, ReaderChromeLayoutMath.toolbarOnReserve(
                168, 96, false, 0));
    }

    @Test
    public void hiddenBottomBarKeepsLastExpandedReserve() {
        assertEquals(312, ReaderChromeLayoutMath.toolbarOnReserve(
                312, 0, false, 0));
    }

    @Test
    public void visibleChromeRefreshesItsMeasuredReserve() {
        assertEquals(180, ReaderChromeLayoutMath.toolbarOnReserve(
                168, 180, true, 0));
    }

    @Test
    public void firstLayoutUsesFallbackUntilToolbarIsMeasured() {
        assertEquals(144, ReaderChromeLayoutMath.toolbarOnReserve(
                0, 0, true, 144));
    }

    @Test
    public void earlyHideUsesExpandedFallbackNotCompactLiveStrip() {
        assertEquals(144, ReaderChromeLayoutMath.toolbarOnReserve(
                0, 80, false, 144));
    }
}
