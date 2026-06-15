package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Rar3PpmdOrder0ModelTest {

    // Numeric assertions against the retired pre-rewrite diagnostic
    // PPMd skeleton were removed. The live PPMd var.H engine is CRC-verified in
    // Rar3PpmdEngineFixtureProbeTest; the remaining tests keep the honest-failure guards only.

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
    public void emptyAlphabetFailsCleanly() throws Exception {
        try {
            new Rar3PpmdOrder0Model(new int[Rar3PpmdOrder0Model.SYMBOL_COUNT]);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("empty alphabet"));
            return;
        }
        throw new AssertionError("Empty PPMd alphabet must fail cleanly");
    }

}
