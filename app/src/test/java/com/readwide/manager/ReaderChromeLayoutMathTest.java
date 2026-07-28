package com.readwide.manager;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ReaderChromeLayoutMathTest {
    @Test
    public void hiddenChromeUsesOnlySystemSafeEdgesInEveryOrientation() {
        assertEquals(24, ReaderChromeLayoutMath.pdfTopReserve(
                false, 24, 180, 220, 144));
        assertEquals(48, ReaderChromeLayoutMath.pdfBottomReserve(
                false, 48, 320, 400, 288));
    }

    @Test
    public void visibleChromePrefersCurrentMeasuredBars() {
        assertEquals(220, ReaderChromeLayoutMath.pdfTopReserve(
                true, 24, 180, 220, 144));
        assertEquals(400, ReaderChromeLayoutMath.pdfBottomReserve(
                true, 48, 320, 400, 288));
    }

    @Test
    public void visibleChromeFallsBackToCachedOrConfiguredBars() {
        assertEquals(176, ReaderChromeLayoutMath.pdfTopReserve(
                true, 24, 0, 176, 144));
        assertEquals(180, ReaderChromeLayoutMath.pdfTopReserve(
                true, 24, 180, 0, 144));
        assertEquals(144, ReaderChromeLayoutMath.pdfTopReserve(
                true, 24, 0, 0, 144));
        assertEquals(304, ReaderChromeLayoutMath.pdfBottomReserve(
                true, 48, 0, 304, 288));
        assertEquals(288, ReaderChromeLayoutMath.pdfBottomReserve(
                true, 48, 0, 0, 288));
    }

    @Test
    public void portraitCacheIsInvalidatedWhenSystemBarFrameChanges() {
        assertEquals(180, ReaderChromeLayoutMath.compatibleCachedReserve(180, 181, 2));
        assertEquals(0, ReaderChromeLayoutMath.compatibleCachedReserve(180, 205, 2));
        assertEquals(0, ReaderChromeLayoutMath.compatibleCachedReserve(0, 205, 2));
    }

}
