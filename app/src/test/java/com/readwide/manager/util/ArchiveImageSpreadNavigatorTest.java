package com.readwide.manager.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ArchiveImageSpreadNavigatorTest {
    @Test public void backwardReversesActualMixedScreenPath() {
        ArchiveImageSpreadNavigator navigator = new ArchiveImageSpreadNavigator();

        // 0-1 was a spread, page 2 was a single because page 3 was wide.
        assertEquals(2, navigator.forward(0, 6, true));
        assertEquals(3, navigator.forward(2, 6, false));

        // Even if asynchronously learned ratios say 1-2 could form a pair,
        // Back must return to the screen actually visited: page 2.
        boolean[] nowKnownPairs = {true, true, false, false, false, false};
        assertEquals(2, navigator.backward(3, 6, nowKnownPairs));
        assertEquals(0, navigator.backward(2, 6, nowKnownPairs));
    }

    @Test public void restoredPageUsesFallbackUntilHistoryExists() {
        ArchiveImageSpreadNavigator navigator = new ArchiveImageSpreadNavigator();
        boolean[] allTall = {true, true, true, true, true, false};

        assertEquals(1, navigator.backward(3, 6, allTall));
        assertEquals(0, navigator.historySizeForTest());
    }

    @Test public void clearDropsHistoryAfterSliderOrSequenceChange() {
        ArchiveImageSpreadNavigator navigator = new ArchiveImageSpreadNavigator();
        navigator.forward(0, 6, true);
        assertEquals(1, navigator.historySizeForTest());

        navigator.clear();
        assertEquals(0, navigator.historySizeForTest());
    }
}
