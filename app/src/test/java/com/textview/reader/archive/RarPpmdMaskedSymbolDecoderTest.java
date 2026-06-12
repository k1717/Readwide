package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarPpmdMaskedSymbolDecoderTest {
    @Test
    public void maskedPrimitiveMapsCountToUnmaskedStateSubrange() throws Exception {
        RarPpmdContext context = new RarPpmdContext();
        context.insertOrUpdateState('A', 2, RarPpmdStateRecord.NO_SUCCESSOR);
        context.insertOrUpdateState('B', 3, RarPpmdStateRecord.NO_SUCCESSOR);
        RarPpmdEscapeMask mask = new RarPpmdEscapeMask();
        mask.mark('A');
        RarPpmdSeeContext see = RarPpmdSeeContext.defaultContext(); // escape scale = 1.
        RarPpmdRangeDecoder decoder = rangeDecoderForCount(0, 4);

        RarPpmdMaskedSymbolDecoder.Result result = RarPpmdMaskedSymbolDecoder.decode(
                context, decoder, mask, see);

        assertFalse(result.escape);
        assertEquals('B', result.symbol);
        assertEquals(0, result.lowCount);
        assertEquals(3, result.highCount);
        assertEquals(3, result.symbolScale);
        assertEquals(4, result.totalScale);
        assertEquals(1, result.escapeScale);
        assertEquals(1, result.maskedCountAfter);
        assertEquals(4, context.findState('B').frequency());
    }

    @Test
    public void maskedPrimitiveMapsHighCountToEscapeAndMarksContextSymbols() throws Exception {
        RarPpmdContext context = new RarPpmdContext();
        context.insertOrUpdateState('A', 2, RarPpmdStateRecord.NO_SUCCESSOR);
        context.insertOrUpdateState('B', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        RarPpmdEscapeMask mask = new RarPpmdEscapeMask();
        RarPpmdSeeContext see = RarPpmdSeeContext.defaultContext(); // symbol scale 3 + escape scale 1.
        RarPpmdRangeDecoder decoder = rangeDecoderForCount(3, 4);

        RarPpmdMaskedSymbolDecoder.Result result = RarPpmdMaskedSymbolDecoder.decode(
                context, decoder, mask, see);

        assertTrue(result.escape);
        assertEquals(RarPpmdModel.DIAGNOSTIC_ESCAPE, result.symbolOrEscape());
        assertEquals(3, result.lowCount);
        assertEquals(4, result.highCount);
        assertEquals(0, result.maskedCountBefore);
        assertEquals(2, result.maskedCountAfter);
        assertTrue(mask.isMasked('A'));
        assertTrue(mask.isMasked('B'));
        assertTrue(result.diagnostic().contains("escape=true"));
    }

    @Test
    public void contextDecodeUsesSharedMaskedPrimitive() throws Exception {
        RarPpmdContext context = new RarPpmdContext();
        context.insertOrUpdateState('X', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        RarPpmdEscapeMask mask = new RarPpmdEscapeMask();
        RarPpmdSeeContext see = RarPpmdSeeContext.defaultContext(); // symbol scale 1 + escape scale 1.
        RarPpmdRangeDecoder decoder = rangeDecoderForCount(0, 2);

        RarPpmdMaskedSymbolDecoder.Result result = context.decodeMaskedSymbolOrEscape(
                decoder, mask, see);

        assertEquals('X', result.symbolOrEscape());
        assertEquals(2, context.findState('X').frequency());
    }

    private static RarPpmdRangeDecoder rangeDecoderForCount(int count, int scale) {
        long range = 0xffff_ffffL;
        long unit = Long.divideUnsigned(range, scale);
        long code = unit * count;
        return new RarPpmdRangeDecoder(new RarPpmdByteInput.ArrayInput(new byte[8]), 0, code, range);
    }
}
