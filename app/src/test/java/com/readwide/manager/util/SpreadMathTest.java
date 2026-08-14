package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpreadMathTest {

    @Test
    public void epubTextSpreadRequiresLandscapeLargeScreen() {
        assertTrue(SpreadMath.shouldUseEpubSpread(true, 7, false, 600));
        assertTrue(SpreadMath.shouldUseEpubSpread(true, 7, false, 720));
        assertFalse(SpreadMath.shouldUseEpubSpread(true, 7, false, 599));
        assertFalse(SpreadMath.shouldUseEpubSpread(false, 7, false, 720));
    }

    @Test
    public void epubImageSpreadStillWorksOnLandscapePhones() {
        assertTrue(SpreadMath.shouldUseEpubSpread(true, 2, true, 360));
        assertFalse(SpreadMath.shouldUseEpubSpread(false, 2, true, 720));
        assertFalse(SpreadMath.shouldUseEpubSpread(true, 1, true, 720));
    }

    @Test
    public void rightIndexAndVisibleEndHandleOddLastPage() {
        assertEquals(1, SpreadMath.rightIndex(0, 5, true));
        assertEquals(1, SpreadMath.visibleEndIndex(0, 5, true));
        assertEquals(-1, SpreadMath.rightIndex(4, 5, true));
        assertEquals(4, SpreadMath.visibleEndIndex(4, 5, true));
    }

    @Test
    public void evenLastSpreadDoesNotRepeatItsRightPage() {
        // Pages 3-4 (zero-based start 2) are already fully visible. A forward
        // turn must not clamp to page 4 and display it again by itself.
        assertEquals(2, SpreadMath.turnTarget(2, 1, 4, true));
        assertFalse(SpreadMath.canTurn(2, 1, 4, true));
    }

    @Test
    public void oddLastPageRemainsReachableAsAStandaloneFinalPage() {
        assertEquals(4, SpreadMath.turnTarget(2, 1, 5, true));
        assertTrue(SpreadMath.canTurn(2, 1, 5, true));
        assertFalse(SpreadMath.canTurn(4, 1, 5, true));
    }

    @Test
    public void backwardSpreadTurnClampsToFirstPage() {
        assertEquals(0, SpreadMath.turnTarget(1, -1, 6, true));
        assertTrue(SpreadMath.canTurn(1, -1, 6, true));
        assertFalse(SpreadMath.canTurn(0, -1, 6, true));
    }

    @Test
    public void singlePageModeStillAdvancesOnePageAtATime() {
        assertEquals(3, SpreadMath.turnTarget(2, 1, 5, false));
        assertEquals(1, SpreadMath.turnTarget(2, -1, 5, false));
        assertEquals(4, SpreadMath.turnTarget(4, 1, 5, false));
    }
}
