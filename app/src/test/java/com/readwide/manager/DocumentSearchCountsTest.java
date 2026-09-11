package com.readwide.manager;

import org.junit.Test;
import static org.junit.Assert.*;

public class DocumentSearchCountsTest {
    @Test public void emptyPagesAndNthBoundaries() {
        DocumentSearchCounts counts = new DocumentSearchCounts(new int[]{0, 2, 0, 3});
        assertEquals(5, counts.total());
        assertEquals(1, counts.pageForOccurrence(1));
        assertEquals(1, counts.pageForOccurrence(2));
        assertEquals(3, counts.pageForOccurrence(3));
        assertEquals(3, counts.pageForOccurrence(5));
        assertEquals(-1, counts.pageForOccurrence(6));
        assertEquals(-1, counts.pageForOccurrence(0));
        assertEquals(2, counts.before(3));
    }
    @Test public void snapshotsInputAndUsesLongPrefixTotals() {
        int[] input = {Integer.MAX_VALUE, 3};
        DocumentSearchCounts counts = new DocumentSearchCounts(input);
        input[0] = 0;
        assertEquals(Integer.MAX_VALUE, counts.total());
        assertEquals(2147483650L, counts.before(2));
        assertEquals(1, counts.pageForOccurrence(2147483648L));
    }
    @Test public void emptyDocumentHasNoOccurrences() {
        DocumentSearchCounts counts = new DocumentSearchCounts(new int[0]);
        assertEquals(0, counts.total());
        assertEquals(0, counts.onPage(0));
        assertEquals(-1, counts.pageForOccurrence(1));
    }
}
