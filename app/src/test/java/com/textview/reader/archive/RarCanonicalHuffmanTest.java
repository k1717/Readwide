package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.Test;

public class RarCanonicalHuffmanTest {
    @Test
    public void decode_canonicalCodesByLength() throws Exception {
        RarCanonicalHuffman table = RarCanonicalHuffman.fromCodeLengths(new int[] {1, 3, 3});
        RarBitInput input = new RarBitInput(new byte[] {(byte) 0b0100_1010});

        assertEquals(0, table.decode(input));
        assertEquals(1, table.decode(input));
        assertEquals(2, table.decode(input));
    }

    @Test(expected = IOException.class)
    public void fromCodeLengths_rejectsOversubscribedTree() throws Exception {
        RarCanonicalHuffman.fromCodeLengths(new int[] {1, 1, 1});
    }

    @Test(expected = IOException.class)
    public void decode_emptySubTableFailsOnlyWhenUsed() throws Exception {
        RarCanonicalHuffman table = RarCanonicalHuffman.fromCodeLengths(new int[] {0, 0, 0});

        table.decode(new RarBitInput(new byte[] {(byte) 0xff}));
    }
}
