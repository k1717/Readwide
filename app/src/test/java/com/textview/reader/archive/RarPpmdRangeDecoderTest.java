package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarPpmdRangeDecoderTest {
    @Test
    public void constructorReadsInitialCodeBigEndian() throws Exception {
        RarPpmdByteInput.ArrayInput input = new RarPpmdByteInput.ArrayInput(
                new byte[] {0x12, 0x34, 0x56, 0x78});

        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(input);

        assertEquals(0x12345678L, decoder.code());
        assertEquals(0xffffffffL, decoder.range());
        assertEquals(4, input.offset());
    }

    @Test
    public void currentCountAndRemoveSubrangeUpdateArithmeticState() throws Exception {
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[0]),
                0,
                0x04500000L,
                0x10000000L);

        assertEquals(4, decoder.currentCount(16));
        decoder.removeSubrange(4, 5, 16);

        assertEquals(0x04000000L, decoder.low());
        assertEquals(0x00500000L, decoder.code());
        assertEquals(0x01000000L, decoder.range());
    }

    @Test
    public void removeSubrangeNormalizesSmallRangesByReadingBytes() throws Exception {
        RarPpmdByteInput.ArrayInput input = new RarPpmdByteInput.ArrayInput(
                new byte[] {(byte) 0xaa, (byte) 0xbb});
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                input,
                0,
                0x00000012L,
                0x00000100L);

        decoder.removeSubrange(0, 1, 1);

        assertEquals(2, input.offset());
        assertEquals(0x0012aabbL, decoder.code());
        assertEquals(0x80000000L, decoder.range());
    }

    @Test
    public void invalidScaleFailsCleanly() throws Exception {
        RarPpmdRangeDecoder decoder = new RarPpmdRangeDecoder(
                new RarPpmdByteInput.ArrayInput(new byte[0]),
                0,
                0,
                0xffffffffL);

        try {
            decoder.currentCount(0);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("invalid scale"));
            return;
        }
        throw new AssertionError("Invalid PPMd scale must fail cleanly");
    }

    @Test
    public void modelSymbolSourceOwnsRangeDecoderButStillReportsModelGap() throws Exception {
        Rar3PpmdModelSymbolSource source = new Rar3PpmdModelSymbolSource(
                new RarPpmdByteInput.ArrayInput(new byte[] {1, 2, 3, 4}),
                true);

        assertEquals(0x01020304L, source.rangeDecoderForTest().code());
        try {
            source.decodeSymbol();
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("statistical model"));
            assertTrue(expected.getMessage().contains("diagnostic primitives"));
            assertTrue(expected.getMessage().contains("keepOldTable=true"));
            return;
        }
        throw new AssertionError("PPMd model must remain a precise first-party gap");
    }
}
