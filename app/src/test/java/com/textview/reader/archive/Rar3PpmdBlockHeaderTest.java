package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Rar3PpmdBlockHeaderTest {
    @Test
    public void parsesPpmdDecodeInitResetHeader() throws Exception {
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.fromPackedPayload(
                new byte[] {(byte) 0xa7, (byte) 0x18, 1, 2, 3, 4});

        assertTrue(header.isPpmd());
        assertFalse(header.keepOldTable());
        assertTrue(header.resetModel());
        assertFalse(header.escapeCharPresent());
        assertEquals(0xa718, header.rawFlags());
        assertEquals(0xa7, header.controlByte());
        assertEquals(8, header.maxOrderHint());
        assertEquals(25, header.memoryMbHint());
        assertEquals(-1, header.escapeCharHint());
        assertEquals(2, header.payloadOffset());
    }

    @Test
    public void parsesPpmdContinuationEscapeHeader() throws Exception {
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.fromPackedPayload(
                new byte[] {(byte) 0xc7, (byte) 0x15, 1, 2, 3, 4});

        assertTrue(header.isPpmd());
        assertTrue(header.keepOldTable());
        assertFalse(header.resetModel());
        assertTrue(header.escapeCharPresent());
        assertEquals(0xc715, header.rawFlags());
        assertEquals(-1, header.maxOrderHint());
        assertEquals(-1, header.memoryMbHint());
        assertEquals(0x15, header.escapeCharHint());
        assertEquals(2, header.payloadOffset());
    }

    @Test
    public void continuationRequiresInitializedModelState() throws Exception {
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.fromPackedPayload(
                new byte[] {(byte) 0xc7, (byte) 0x15, 1, 2, 3, 4});
        Rar3PpmdState state = new Rar3PpmdState();

        try {
            state.applyHeader(header);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("continuation block"));
            return;
        }
        throw new AssertionError("continuation PPMd blocks must require initialized model state");
    }

    @Test
    public void stateRetainsInitializedPpmdHeaderForSolidContinuation() throws Exception {
        Rar3PpmdState state = new Rar3PpmdState();
        state.applyHeader(Rar3PpmdBlockHeader.fromPackedPayload(
                new byte[] {(byte) 0xa7, (byte) 0x18, 1, 2, 3, 4}));
        state.applyHeader(Rar3PpmdBlockHeader.fromPackedPayload(
                new byte[] {(byte) 0xc7, (byte) 0x15, 1, 2, 3, 4}));

        assertTrue(state.modelInitialized());
        assertTrue(state.lastKeepOldTable());
        assertEquals(2, state.blockSequence());
        assertEquals(8, state.maxOrderHint());
        assertEquals(25, state.memoryMbHint());
        assertEquals(0x15, state.escapeChar());
    }
}
