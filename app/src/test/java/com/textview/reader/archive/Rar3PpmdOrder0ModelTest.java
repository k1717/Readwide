package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Rar3PpmdOrder0ModelTest {
    @Test
    public void decodeSymbolMapsRangeCountToFrequencyBucket() throws Exception {
        int[] frequencies = new int[Rar3PpmdOrder0Model.SYMBOL_COUNT];
        frequencies['A'] = 1;
        frequencies['B'] = 1;
        frequencies['C'] = 2;
        Rar3PpmdOrder0Model model = new Rar3PpmdOrder0Model(frequencies);
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[0]),
                0,
                0x08000000L,
                0x10000000L);

        int symbol = model.decodeSymbol(decoder);

        assertEquals('C', symbol);
        assertEquals(3, model.frequency('C'));
        assertEquals(5, model.scale());
    }

    @Test
    public void order0SymbolSourceConnectsModelToPpmdControlLayer() throws Exception {
        int[] frequencies = new int[Rar3PpmdOrder0Model.SYMBOL_COUNT];
        frequencies['Z'] = 1;
        Rar3PpmdOrder0Model model = new Rar3PpmdOrder0Model(frequencies);
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[0]),
                0,
                0,
                0x10000000L);
        Rar3PpmdOrder0SymbolSource source = new Rar3PpmdOrder0SymbolSource(decoder, model);

        assertEquals('Z', source.decodeSymbol());
        assertEquals(2, source.modelForTest().frequency('Z'));
    }

    @Test
    public void emptyAlphabetFailsCleanly() throws Exception {
        try {
            new Rar3PpmdOrder0Model(new int[Rar3PpmdOrder0Model.SYMBOL_COUNT]);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("empty alphabet"));
            return;
        }
        throw new AssertionError("Empty PPMd alphabet must fail cleanly");
    }

    @Test
    public void modelSymbolSourceKeepsFullPpmdGapButOwnsDiagnosticSkeleton() throws Exception {
        Rar3PpmdModelSymbolSource source = new Rar3PpmdModelSymbolSource(
                new RarPpmdByteInput.ArrayInput(new byte[] {1, 2, 3, 4}),
                false);

        assertEquals(0x01020304L, source.rangeDecoderForTest().code());
        assertEquals(64 * 1024, source.subAllocatorForTest().capacityBytes());
        assertEquals(Rar3PpmdOrder0Model.SYMBOL_COUNT, source.order0ModelForTest().scale());
        assertEquals(1, source.rootContextForTest().stateCount());
        assertEquals(0, source.rootContextForTest().stateArrayPointer());
        try {
            source.decodeSymbol();
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("diagnostic primitives"));
            assertTrue(expected.getMessage().contains("masked-symbol"));
            assertTrue(expected.getMessage().contains("keepOldTable=false"));
            return;
        }
        throw new AssertionError("Full PPMd model must remain a precise first-party gap");
    }
}
