package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarPpmdSymbolHistoryTest {
    @Test
    public void historyStoresNewestFirstAndDropsOldestPastCapacity() throws Exception {
        RarPpmdSymbolHistory history = new RarPpmdSymbolHistory(3);

        history.remember('A');
        history.remember('B');
        history.remember('C');
        history.remember('D');

        assertEquals(3, history.count());
        assertEquals('D', history.newest());
        assertEquals('C', history.older());
        assertEquals('B', history.symbolAt(2));
        assertEquals(-1, history.symbolAt(3));
        assertArrayEquals(new int[] {'D', 'C', 'B'}, history.snapshotForTest());
        assertTrue(history.hasAtLeast(2));
        assertFalse(history.hasAtLeast(4));
    }

    @Test(expected = RarArchiveReader.UnsupportedRarFeatureException.class)
    public void historyRejectsOutOfRangeSymbol() throws Exception {
        new RarPpmdSymbolHistory(2).remember(256);
    }
}
