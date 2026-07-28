package com.readwide.manager.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArchiveImageSpreadMathTest {
    @Test public void tallPageRequiresMeaningfulPortraitRatio() {
        assertTrue(ArchiveImageSpreadMath.isTallPage(1000, 1100));
        assertFalse(ArchiveImageSpreadMath.isTallPage(1000, 1099));
        assertFalse(ArchiveImageSpreadMath.isTallPage(1000, 1001));
    }

    @Test public void pairRequiresTwoPortraitPages() {
        assertTrue(ArchiveImageSpreadMath.canShowPair(0, 4, true, true));
        assertFalse(ArchiveImageSpreadMath.canShowPair(0, 4, true, false));
        assertFalse(ArchiveImageSpreadMath.canShowPair(3, 4, true, true));
    }

    @Test public void forwardConsumesOnlyVisiblePair() {
        assertEquals(2, ArchiveImageSpreadMath.forwardTarget(0, 5, true));
        assertEquals(1, ArchiveImageSpreadMath.forwardTarget(0, 5, false));
        assertEquals(4, ArchiveImageSpreadMath.forwardTarget(4, 5, false));
    }

    @Test public void previousUsesLocalMixedScreenBoundaries() {
        boolean[] pairs = {true, false, false, true, false, false};
        assertEquals(0, ArchiveImageSpreadMath.previousTarget(2, 6, pairs));
        assertEquals(2, ArchiveImageSpreadMath.previousTarget(3, 6, pairs));
        assertEquals(3, ArchiveImageSpreadMath.previousTarget(5, 6, pairs));
    }

    @Test public void previousPreservesOddResumeSpread() {
        boolean[] allPortraitPairs = {true, true, true, true, true, false};
        assertEquals(1, ArchiveImageSpreadMath.previousTarget(
                3, 6, allPortraitPairs));
    }

    @Test public void finalOddPageStaysSingle() {
        assertEquals(4, ArchiveImageSpreadMath.visibleEndIndex(4, 5, true));
    }
}
