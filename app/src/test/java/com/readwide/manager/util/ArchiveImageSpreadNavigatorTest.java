package com.readwide.manager.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ArchiveImageSpreadNavigatorTest {
    @Test public void historyDoesNotReadAnyPairMetadata() {
        ArchiveImageSpreadNavigator navigator = new ArchiveImageSpreadNavigator();
        navigator.forward(0, 10, true);
        assertEquals(0, navigator.backwardWithPairLookup(2, 10, index -> {
            throw new AssertionError("History should not inspect pairs");
        }));
    }

    @Test public void localFallbackMatchesArrayMathWithAtMostOneLookup() {
        for (int count = 0; count <= 8; count++) {
            for (int mask = 0; mask < (1 << count); mask++) {
                boolean[] pairs = new boolean[count];
                for (int i = 0; i < count; i++) pairs[i] = (mask & (1 << i)) != 0;
                for (int index = -2; index <= count + 2; index++) {
                    java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
                    int actual = new ArchiveImageSpreadNavigator().backwardWithPairLookup(index, count, start -> {
                        org.junit.Assert.assertTrue(start >= 0 && start + 1 < pairs.length);
                        lookups.incrementAndGet();
                        return pairs[start];
                    });
                    assertEquals(ArchiveImageSpreadMath.previousTarget(index, count, pairs), actual);
                    org.junit.Assert.assertTrue(lookups.get() <= 1);
                }
            }
        }
    }

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
