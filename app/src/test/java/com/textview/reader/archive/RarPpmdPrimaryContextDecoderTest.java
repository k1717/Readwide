package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarPpmdPrimaryContextDecoderTest {
    @Test
    public void fullAlphabetPrimaryRootMapsFixtureFirstCountToPngMagicByte() throws Exception {
        RarPpmdSubAllocator allocator = new RarPpmdSubAllocator(64 * 1024);
        RarPpmdContextChain chain = new RarPpmdContextChain(allocator);
        chain.initializeRootFullAlphabet(allocator);
        RarPpmdContext root = chain.rootContext();

        long unit = Long.divideUnsigned(0xffffffffL, 257);
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[] {0, 0, 0, 0}),
                0, unit * 0x89, 0xffffffffL);

        RarPpmdPrimaryContextDecoder.Result result =
                root.decodePrimaryContextSymbolOrEscape(decoder, new RarPpmdEscapeMask());

        assertFalse(result.escape);
        assertEquals(0x89, result.symbol);
        assertEquals(257, result.totalScale);
        assertEquals(1, result.escapeScale);
        assertEquals(5, root.findState(0x89).frequency());
    }

    @Test
    public void diagnosticPrimaryRootOptionInitializesFullRootOnResetBlock() throws Exception {
        byte[] packed = new byte[] {
                (byte) 0xa0, 0x00, // PPMd reset, maxOrder=1, memory=1MB.
                (byte) 0x89, 0x00, 0x00, 0x00,
                0, 0, 0, 0
        };
        Rar3PpmdState state = new Rar3PpmdState();
        Rar3PpmdLiveDiagnosticProbe.Row row =
                Rar3PpmdLiveDiagnosticProbe.probePackedPayloadWithOptionsForTest(
                        "magic.png", packed.length, 1, false, packed, state, 1,
                        RarPpmdDiagnosticOptions.rarPrimaryRoot());

        assertEquals("rar-primary-root", row.variantName);
        assertEquals(1, row.decodedSymbols);
        assertTrue(row.lastTraceDiagnostic().contains("primaryContext=true"));
        assertTrue(row.modelDiagnostic.contains("rootStates=256"));
        assertTrue(row.modelDiagnostic.contains("rootPrimaryEscapeScale=1"));
    }
    @Test
    public void primaryContextPromotesDecodedStateOnlyOneStep() throws Exception {
        RarPpmdSubAllocator allocator = new RarPpmdSubAllocator(64 * 1024);
        RarPpmdContextChain chain = new RarPpmdContextChain(allocator);
        chain.initializeRootFullAlphabet(allocator);
        RarPpmdContext root = chain.rootContext();

        long unit = Long.divideUnsigned(0xffffffffL, 257);
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[] {0, 0, 0, 0}),
                0, unit * 0x89, 0xffffffffL);

        root.decodePrimaryContextSymbolOrEscape(decoder, new RarPpmdEscapeMask());

        assertEquals(0x00, root.stateAt(0).symbol());
        assertEquals(0x89, root.stateAt(0x88).symbol());
        assertEquals(0x88, root.stateAt(0x89).symbol());
    }

}
