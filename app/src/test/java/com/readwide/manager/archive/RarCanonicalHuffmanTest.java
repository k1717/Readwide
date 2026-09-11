package com.readwide.manager.archive;

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

    @Test public void sparseLengthsDecodeIdenticallyFromArrayAndBoundedStream() throws Exception {
        RarCanonicalHuffman table=RarCanonicalHuffman.fromCodeLengths(new int[]{1,0,3,0,5});
        byte[] packed={0x4a,0}; // 0, 100, 10100
        for(RarBitInput input:new RarBitInput[]{new RarBitInput(packed),
                new RarBitInput(new java.io.ByteArrayInputStream(packed),packed.length)}) {
            assertEquals(0,table.decode(input));assertEquals(2,table.decode(input));
            assertEquals(4,table.decode(input));assertEquals(9,input.bitsRead());
        }
    }

    @Test public void oneBitCodeCanFinishAtPhysicalEndWithoutLookahead() throws Exception {
        RarCanonicalHuffman table=RarCanonicalHuffman.fromCodeLengths(new int[]{1,1});
        RarBitInput input=new RarBitInput(new java.io.ByteArrayInputStream(new byte[]{1}),1);
        input.skipBits(7);assertEquals(1,table.decode(input));assertEquals(0,input.remainingBits());
    }
}
