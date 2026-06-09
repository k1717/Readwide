package com.textview.reader.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarCompressedPayloadDecoderTest {
    @Test
    public void isRar3Or4CompressionMethod_acceptsNormalRar4Methods() {
        assertFalse(RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(0x30));
        assertTrue(RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(0x31));
        assertTrue(RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(0x35));
        assertFalse(RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(0x36));
    }
}
