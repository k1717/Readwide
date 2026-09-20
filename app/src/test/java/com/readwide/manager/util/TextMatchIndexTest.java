package com.readwide.manager.util;

import org.junit.Test;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class TextMatchIndexTest {
    private static final SearchOptions REGEX = new SearchOptions(true, false, true, false);
    private TextMatchIndex index(String text, String query, SearchOptions options) {
        return TextMatchIndex.build(text, query, options, () -> false);
    }

    @Test public void regexNavigationUsesTheCountedNonOverlappingSequence() {
        TextMatchIndex index = index("aaaa", "aa", REGEX);
        assertEquals(2, index.count());
        assertEquals(0, index.occurrence(1, null).position);
        assertEquals(2, index.nearest(1, true, null).position);
        assertEquals(2, index.nearest(1, true, null).ordinal);
        assertEquals(0, index.ordinalAt(1, null));
    }

    @Test public void literalSearchStillIncludesOverlappingMatches() {
        TextMatchIndex index = index("aaaa", "aa", SearchOptions.literal());
        assertEquals(3, index.count());
        assertEquals(1, index.nearest(1, true, null).position);
        assertEquals(2, index.occurrence(3, null).position);
    }

    @Test public void previousBeforeZeroWrapsToLastMatch() {
        TextMatchIndex index = index("aa xx aa", "aa", REGEX);
        assertEquals(6, index.nearest(-1, false, null).position);
        assertEquals(2, index.nearest(-1, false, null).ordinal);
        assertEquals(0, index.nearest(0, false, null).position);
        assertEquals(0, index.nearest(7, true, null).position);
    }

    @Test public void singleMatchWrapAndOutOfRangeAreDefined() {
        TextMatchIndex index = index("cat", "cat", SearchOptions.literal());
        assertEquals(0, index.nearest(-1, false, null).position);
        assertEquals(0, index.nearest(1, true, null).position);
        assertEquals(-1, index.occurrence(0, null).position);
        assertEquals(-1, index.occurrence(2, null).position);
    }

    @Test public void emptyAndInvalidQueriesProduceEmptyIndexes() {
        for (TextMatchIndex index : new TextMatchIndex[]{index("", "a", REGEX), index("a", "", REGEX),
                index("a", "[", REGEX), index("a", "z", REGEX)}) {
            assertEquals(0, index.count());
            assertEquals(-1, index.nearest(0, true, null).position);
        }
    }

    @Test public void snapshotIdentityIncludesContentReferenceQueryAndOptions() {
        String text = new String("cat CAT");
        TextMatchIndex index = index(text, "cat", SearchOptions.literal());
        assertTrue(index.matches(text, "cat", SearchOptions.literal()));
        assertFalse(index.matches(new String(text), "cat", SearchOptions.literal()));
        assertFalse(index.matches(text, "CAT", SearchOptions.literal()));
        assertFalse(index.matches(text, "cat", REGEX));
    }

    @Test public void boundedCacheStillCountsAndNavigatesEveryResult() {
        TextMatchIndex index = TextMatchIndex.build("aaaaaa", "aa", REGEX, null, 1);
        assertEquals(3, index.count());
        assertEquals(4, index.occurrence(3, null).position);
        assertEquals(2, index.nearest(1, true, null).position);
        assertEquals(4, index.nearest(-1, false, null).position);
        assertEquals(2, index.nearest(3, false, null).position);
        assertEquals(0, index.nearest(5, true, null).position);
        assertEquals(3, index.ordinalAt(4, null));
    }

    @Test public void cachedAndOverflowNavigationAgreeForAllOffsets() {
        for (SearchOptions options : new SearchOptions[]{REGEX, SearchOptions.literal()}) {
            TextMatchIndex dense = index("aa aaaa z aaa", "aa", options);
            TextMatchIndex sparse = TextMatchIndex.build("aa aaaa z aaa", "aa", options, null, 0);
            for (int from = -2; from < 20; from++) for (boolean forward : new boolean[]{true, false}) {
                TextMatchIndex.Hit expected = dense.nearest(from, forward, null);
                TextMatchIndex.Hit actual = sparse.nearest(from, forward, null);
                assertEquals(expected.position, actual.position);
                assertEquals(expected.ordinal, actual.ordinal);
            }
        }
    }

    @Test public void wholeWordAndCaseFoldingRetainOriginalOffsets() {
        TextMatchIndex index = index("xcat CAT cat!", "cat", new SearchOptions(false, true, false, true));
        assertEquals(2, index.count());
        assertEquals(5, index.occurrence(1, null).position);
        assertEquals(9, index.occurrence(2, null).position);
    }

    @Test(expected = CancellationException.class) public void cancellationNeverPublishesAPartialCount() {
        AtomicInteger checks = new AtomicInteger();
        TextMatchIndex.build("aaaaaa", "a", SearchOptions.literal(), () -> checks.incrementAndGet() > 3);
    }

    @Test(expected = CancellationException.class) public void cachedNavigationHonorsCancellation() {
        index("aaaa", "a", SearchOptions.literal()).nearest(0, true, () -> true);
    }

    @Test public void interruptionPreventsIndexPublication() {
        Thread.currentThread().interrupt();
        try {
            index("aa", "a", SearchOptions.literal());
            fail("Interrupted search published an index");
        } catch (CancellationException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void denseNavigationKeepsAllResultsBeyondTheOldArrayCapacity() {
        char[] chars = new char[200_005];
        java.util.Arrays.fill(chars, 'x');
        TextMatchIndex index = index(new String(chars), "x", SearchOptions.literal());
        assertEquals(chars.length, index.count());
        assertEquals(200_004, index.occurrence(200_005, null).position);
        assertEquals(200_004, index.nearest(-1, false, null).position);
        assertEquals(0, index.nearest(Integer.MAX_VALUE, true, null).position);
        assertEquals(200_004, index.nearest(Integer.MAX_VALUE, false, null).position);
    }

    @Test public void denseBitmapAndForcedFallbackAgreeForLiteralAndRegexQueries() {
        String text = "aa aaaa aaaaa aa aa";
        for (SearchOptions options : new SearchOptions[]{REGEX, SearchOptions.literal()}) {
            TextMatchIndex bitmap = TextMatchIndex.build(text, "aa", options, null, 8);
            TextMatchIndex fallback = TextMatchIndex.build(text, "aa", options, null, 0);
            for (int from = -1; from <= text.length(); from++) for (boolean forward : new boolean[]{true, false}) {
                TextMatchIndex.Hit a = bitmap.nearest(from, forward, null);
                TextMatchIndex.Hit b = fallback.nearest(from, forward, null);
                assertEquals(a.position, b.position);
                assertEquals(a.ordinal, b.ordinal);
            }
        }
    }
}
