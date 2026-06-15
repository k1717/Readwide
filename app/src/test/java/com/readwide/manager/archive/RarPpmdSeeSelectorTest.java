package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarPpmdSeeSelectorTest {
    @Test
    public void selectorMapsOrderStateMaskAndHistoryToStableTableCoordinates() throws Exception {
        RarPpmdSeeSelector.Selection selection = RarPpmdSeeSelector.select(
                2, 9, 5, 3, 2);

        assertEquals(13, selection.row);   // order-2 band * 5 + 5..8-state band.
        assertEquals(10, selection.column); // 2..4 masked band * 4 + two-history band.
        assertTrue(selection.diagnostic().contains("orderBand=2"));
        assertTrue(selection.diagnostic().contains("maskBand=2"));
    }

    @Test
    public void seeTableKeepsSeparate25By16Contexts() throws Exception {
        RarPpmdSeeTable table = new RarPpmdSeeTable();

        RarPpmdSeeContext rootCold = table.contextAtForTest(0, 0);
        RarPpmdSeeContext order2Masked = table.contextAtForTest(13, 10);

        assertNotSame(rootCold, order2Masked);
        assertEquals(4, rootCold.mean());
        assertTrue(order2Masked.mean() > rootCold.mean());
    }

    @Test
    public void diagnosticDecodeRecordsSelectorRowAndColumn() throws Exception {
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[0]),
                0,
                0x00000000L,
                0x10000000L);
        Rar3PpmdState state = new Rar3PpmdState();
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.fromPackedPayload(
                new byte[] {(byte) 0xa7, 0x18, 0, 0}); // reset header: maxOrder 8, memory 25.
        Rar3PpmdModelSymbolSource source = Rar3PpmdModelSymbolSource
                .diagnosticContextFallbackForTest(decoder, state, header);
        source.seedPreviousSymbolForTest('B');
        source.seedPreviousSymbolForTest('A');
        source.ensureOrder2ContextForTest('A', 'B')
                .insertOrUpdateState('D', 1, RarPpmdStateRecord.NO_SUCCESSOR);

        int decoded = source.decodeSymbol();

        assertEquals('D', decoded);
        assertEquals(10, source.seeTableForTest().lastRowForTest());
        assertEquals(2, source.seeTableForTest().lastColumnForTest());
    }
}
