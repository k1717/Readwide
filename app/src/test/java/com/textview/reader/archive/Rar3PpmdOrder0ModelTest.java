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
                new RarPpmdByteInput.ArrayInput(new byte[] {0, 0, 0, 0}),
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
    public void modelSymbolSourceDiagnosticOrder0CanDecodeInitialSymbol() throws Exception {
        long piece = Long.divideUnsigned(0xffffffffL, Rar3PpmdOrder0Model.SYMBOL_COUNT);
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[0]),
                0,
                piece * 'K',
                0xffffffffL);
        Rar3PpmdState state = new Rar3PpmdState();
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.syntheticForTest(false);
        Rar3PpmdModelSymbolSource source = Rar3PpmdModelSymbolSource.diagnosticOrder0ForTest(
                decoder, state, header);

        assertEquals('K', source.decodeSymbol());
        assertEquals(1, source.rootContextForTest().stateCount());
        assertEquals('K', source.rootContextForTest().stateAt(0).symbol());
        assertEquals('K', source.modelForTest().latestSymbolForTest());
        assertEquals(2, source.order0ModelForTest().frequency('K'));
        assertTrue(source.ppmdStateForTest().hasModel());
    }


    @Test
    public void order0DecodeHonorsEscapeMaskForFallback() throws Exception {
        int[] frequencies = new int[Rar3PpmdOrder0Model.SYMBOL_COUNT];
        frequencies['A'] = 1;
        frequencies['B'] = 1;
        Rar3PpmdOrder0Model model = new Rar3PpmdOrder0Model(frequencies);
        RarPpmdEscapeMask mask = new RarPpmdEscapeMask();
        mask.mark('A');
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[0]),
                0,
                0,
                0x10000000L);

        int symbol = model.decodeSymbol(decoder, mask);

        assertEquals('B', symbol);
        assertEquals(1, model.frequency('A'));
        assertEquals(2, model.frequency('B'));
    }

    @Test
    public void diagnosticContextDecodeCanReturnRootStateWithoutFallback() throws Exception {
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[0]),
                0,
                0,
                0x10000000L);
        Rar3PpmdState state = new Rar3PpmdState();
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.syntheticForTest(false);
        Rar3PpmdModelSymbolSource source = Rar3PpmdModelSymbolSource.diagnosticContextFallbackForTest(
                decoder, state, header);
        source.rootContextForTest().insertOrUpdateState('A', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        source.rootContextForTest().ensureStateArrayCapacity(source.subAllocatorForTest());

        assertEquals('A', source.decodeSymbol());
        assertEquals(2, source.rootContextForTest().findState('A').frequency());
        assertEquals('A', source.modelForTest().latestSymbolForTest());
        assertEquals(0, source.modelForTest().diagnosticContextEscapeCountForTest());
        assertEquals(0, source.modelForTest().diagnosticOrder0FallbackCountForTest());
    }

    @Test
    public void diagnosticContextEscapeMasksRootAndFallsBackToOrder0() throws Exception {
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[] {0, 0, 0, 0}),
                0,
                0x08000000L,
                0x10000000L);
        Rar3PpmdState state = new Rar3PpmdState();
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.syntheticForTest(false);
        Rar3PpmdModelSymbolSource source = Rar3PpmdModelSymbolSource.diagnosticContextFallbackForTest(
                decoder, state, header);
        source.rootContextForTest().insertOrUpdateState('A', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        source.rootContextForTest().ensureStateArrayCapacity(source.subAllocatorForTest());

        assertEquals(0, source.decodeSymbol());
        assertTrue(source.escapeMaskForTest().isMasked('A'));
        assertEquals(1, source.modelForTest().diagnosticContextEscapeCountForTest());
        assertEquals(1, source.modelForTest().diagnosticOrder0FallbackCountForTest());
        assertEquals(2, source.order0ModelForTest().frequency(0));
    }


    @Test
    public void diagnosticOrder1ContextDecodesBeforeRootContext() throws Exception {
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[0]),
                0,
                0,
                0x10000000L);
        Rar3PpmdState state = new Rar3PpmdState();
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.syntheticForTest(false);
        Rar3PpmdModelSymbolSource source = Rar3PpmdModelSymbolSource.diagnosticContextFallbackForTest(
                decoder, state, header);
        source.seedPreviousSymbolForTest('A');
        source.rootContextForTest().insertOrUpdateState('C', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        source.ensureOrder1ContextForTest('A').insertOrUpdateState('B', 1, RarPpmdStateRecord.NO_SUCCESSOR);

        assertEquals('B', source.decodeSymbol());

        assertEquals(1, source.allocatedOrder1ContextCountForTest());
        assertEquals(2, source.order1ContextForTest('A').findState('B').frequency());
        assertEquals(1, source.rootContextForTest().findState('B').frequency());
        assertEquals('B', source.modelForTest().latestSymbolForTest());
    }

    @Test
    public void diagnosticOrder1EscapeFallsBackToRootBeforeOrder0() throws Exception {
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[0]),
                0,
                0x08000000L,
                0x10000000L);
        Rar3PpmdState state = new Rar3PpmdState();
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.syntheticForTest(false);
        Rar3PpmdModelSymbolSource source = Rar3PpmdModelSymbolSource.diagnosticContextFallbackForTest(
                decoder, state, header);
        source.seedPreviousSymbolForTest('A');
        source.rootContextForTest().insertOrUpdateState('B', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        source.ensureOrder1ContextForTest('A').insertOrUpdateState('A', 1, RarPpmdStateRecord.NO_SUCCESSOR);

        assertEquals('B', source.decodeSymbol());

        assertTrue(source.escapeMaskForTest().isMasked('A'));
        assertEquals(1, source.modelForTest().diagnosticContextEscapeCountForTest());
        assertEquals(0, source.modelForTest().diagnosticOrder0FallbackCountForTest());
        assertEquals(2, source.rootContextForTest().findState('B').frequency());
        assertEquals(1, source.order1ContextForTest('A').findState('B').frequency());
    }


    @Test
    public void diagnosticOrder2ContextDecodesBeforeOrder1AndRoot() throws Exception {
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[0]),
                0,
                0,
                0x10000000L);
        Rar3PpmdState state = new Rar3PpmdState();
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.syntheticForTest(false);
        Rar3PpmdModelSymbolSource source = Rar3PpmdModelSymbolSource.diagnosticContextFallbackForTest(
                decoder, state, header);
        source.seedPreviousSymbolForTest('B');
        source.seedPreviousSymbolForTest('A');
        source.rootContextForTest().insertOrUpdateState('E', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        source.ensureOrder1ContextForTest('A').insertOrUpdateState('D', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        source.ensureOrder2ContextForTest('A', 'B').insertOrUpdateState('C', 1, RarPpmdStateRecord.NO_SUCCESSOR);

        assertEquals('C', source.decodeSymbol());

        assertEquals(1, source.allocatedOrder2ContextCountForTest());
        assertEquals(2, source.order2ContextForTest('A', 'B').findState('C').frequency());
        assertEquals(1, source.order1ContextForTest('A').findState('C').frequency());
        assertEquals(1, source.rootContextForTest().findState('C').frequency());
        assertEquals('C', source.symbolHistoryForTest(0));
        assertEquals('A', source.symbolHistoryForTest(1));
    }

    @Test
    public void diagnosticOrder2EscapeFallsBackToOrder1BeforeRoot() throws Exception {
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[0]),
                0,
                0x08000000L,
                0x10000000L);
        Rar3PpmdState state = new Rar3PpmdState();
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.syntheticForTest(false);
        Rar3PpmdModelSymbolSource source = Rar3PpmdModelSymbolSource.diagnosticContextFallbackForTest(
                decoder, state, header);
        source.seedPreviousSymbolForTest('B');
        source.seedPreviousSymbolForTest('A');
        source.rootContextForTest().insertOrUpdateState('R', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        source.ensureOrder1ContextForTest('A').insertOrUpdateState('D', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        source.ensureOrder2ContextForTest('A', 'B').insertOrUpdateState('C', 1, RarPpmdStateRecord.NO_SUCCESSOR);

        assertEquals('D', source.decodeSymbol());

        assertTrue(source.escapeMaskForTest().isMasked('C'));
        assertEquals(1, source.modelForTest().diagnosticContextEscapeCountForTest());
        assertEquals(0, source.modelForTest().diagnosticOrder0FallbackCountForTest());
        assertEquals(2, source.order1ContextForTest('A').findState('D').frequency());
        assertEquals(1, source.order2ContextForTest('A', 'B').findState('D').frequency());
    }

    @Test
    public void diagnosticSeeTableUsesSeparateBucketsDuringSuffixFallback() throws Exception {
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[0]),
                0,
                0x08000000L,
                0x10000000L);
        Rar3PpmdState state = new Rar3PpmdState();
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.syntheticForTest(false);
        Rar3PpmdModelSymbolSource source = Rar3PpmdModelSymbolSource.diagnosticContextFallbackForTest(
                decoder, state, header);
        source.seedPreviousSymbolForTest('B');
        source.seedPreviousSymbolForTest('A');
        source.ensureOrder1ContextForTest('A').insertOrUpdateState('D', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        source.ensureOrder2ContextForTest('A', 'B').insertOrUpdateState('C', 1, RarPpmdStateRecord.NO_SUCCESSOR);

        RarPpmdSeeContext order2See = source.seeTableForTest().contextForTest(
                RarPpmdSeeTable.ORDER2, 1, 0);
        RarPpmdSeeContext order1MaskedSee = source.seeTableForTest().contextForTest(
                RarPpmdSeeTable.ORDER1, 1, 1);

        assertEquals(4, order2See.count());
        assertEquals(4, order1MaskedSee.count());
        assertEquals('D', source.decodeSymbol());

        assertEquals(3, order2See.count());
        assertEquals(5, order1MaskedSee.count());
        assertEquals(RarPpmdSeeTable.ORDER1, source.seeTableForTest().lastOrderBucketForTest());
        assertEquals(0, source.seeTableForTest().lastStateBucketForTest());
        assertEquals(1, source.seeTableForTest().lastMaskBucketForTest());
        assertEquals(2, source.seeTableForTest().selectionCountForTest());
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
        assertEquals(0, source.rootContextForTest().stateCount());
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
