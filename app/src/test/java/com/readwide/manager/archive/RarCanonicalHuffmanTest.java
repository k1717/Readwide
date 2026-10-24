package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.Test;

public class RarCanonicalHuffmanTest {
    @Test public void sliceUsesLocalSymbolsAndIgnoresOutsideLengths() throws Exception {
        int[] lengths = {-1, 16, 1, 3, 3, Integer.MAX_VALUE};
        int[] original = lengths.clone();
        RarCanonicalHuffman table = RarCanonicalHuffman.fromCodeLengths(lengths, 2, 3);
        RarBitInput input = new RarBitInput(new byte[]{0x4a}); // 0,100,101
        assertEquals(0, table.decode(input));
        assertEquals(1, table.decode(input));
        assertEquals(2, table.decode(input));
        assertEquals(7, input.bitsRead());
        org.junit.Assert.assertArrayEquals(original, lengths);
    }

    @Test public void sliceDoesNotRetainCallerTableAfterConstruction() throws Exception {
        int[] lengths = {0, 1, 1, 0};
        RarCanonicalHuffman table = RarCanonicalHuffman.fromCodeLengths(lengths, 1, 2);
        java.util.Arrays.fill(lengths, 15);
        RarBitInput input = new RarBitInput(new byte[]{0x40});
        assertEquals(0, table.decode(input));
        assertEquals(1, table.decode(input));
        assertEquals(2, input.bitsRead());
    }

    @Test public void sliceRejectsInvalidAndOverflowingRanges() {
        int[] lengths = {1, 1};
        for (int[] range : new int[][]{{-1,1}, {0,-1}, {3,1}, {1,2}, {1,Integer.MAX_VALUE}, {Integer.MAX_VALUE,1}}) {
            org.junit.Assert.assertThrows(IOException.class,
                    () -> RarCanonicalHuffman.fromCodeLengths(lengths, range[0], range[1]));
        }
        org.junit.Assert.assertThrows(IOException.class, () -> RarCanonicalHuffman.fromCodeLengths(null, 0, 1));
        org.junit.Assert.assertThrows(IOException.class, () -> RarCanonicalHuffman.fromCodeLengths(lengths, 0, 0));
    }

    @Test public void sliceRetainsInvalidLengthOversubscriptionAndEmptyTableRules() throws Exception {
        for (int[] lengths : new int[][]{{0,1,16,0}, {0,1,-1,0}, {0,1,1,1,0}}) {
            org.junit.Assert.assertThrows(IOException.class,
                    () -> RarCanonicalHuffman.fromCodeLengths(lengths, 1, lengths.length - 2));
        }
        RarCanonicalHuffman table = RarCanonicalHuffman.fromCodeLengths(new int[]{1,0,0,1}, 1, 2);
        org.junit.Assert.assertThrows(IOException.class, () -> table.decode(new RarBitInput(new byte[]{0})));
    }

    @Test public void slicedTablesKeepShortTailFallbackAtEveryAlignment() throws Exception {
        for (int[] slice : new int[][]{{1,0,3,0,5}, {1,0,15}, {1,1}}) {
            int[] lengths = new int[slice.length + 5];
            System.arraycopy(slice, 0, lengths, 3, slice.length);
            for (int start = 0; start < 8; start++) {
                RarCanonicalHuffman direct = RarCanonicalHuffman.fromCodeLengths(lengths, 3, slice.length);
                RarCanonicalHuffman copied = RarCanonicalHuffman.fromCodeLengths(slice.clone());
                // Zero prefix selects the one-bit symbol, including physical short tails.
                RarBitInput a = new RarBitInput(new java.io.ByteArrayInputStream(new byte[]{0}));
                RarBitInput b = new RarBitInput(new java.io.ByteArrayInputStream(new byte[]{0}));
                a.skipBits(start); b.skipBits(start);
                for (int bit = start; bit < 8; bit++) assertEquals(copied.decode(b), direct.decode(a));
                assertEquals(b.bitsRead(), a.bitsRead());
                org.junit.Assert.assertThrows(java.io.EOFException.class, () -> direct.decode(a));
            }
        }
    }

    @Test public void slicePreservesSparseAndFifteenBitCodes() throws Exception {
        RarCanonicalHuffman sparse = RarCanonicalHuffman.fromCodeLengths(new int[]{16,1,0,3,0,5,-1}, 1, 5);
        RarBitInput a = new RarBitInput(new byte[]{0x4a,0}); // 0,100,10100
        assertEquals(0, sparse.decode(a)); assertEquals(2, sparse.decode(a)); assertEquals(4, sparse.decode(a));
        assertEquals(9, a.bitsRead());
        RarCanonicalHuffman longCode = RarCanonicalHuffman.fromCodeLengths(new int[]{16,1,0,15,-1}, 1, 3);
        RarBitInput b = new RarBitInput(new byte[]{0x40,0}); // 0,100000000000000
        assertEquals(0, longCode.decode(b)); assertEquals(2, longCode.decode(b));
        assertEquals(16, b.bitsRead());
    }

    @Test public void resumedStreamShortTailMatchesKnownBoundsAtEveryBitAlignment() throws Exception {
        RarCanonicalHuffman table = RarCanonicalHuffman.fromCodeLengths(new int[]{1, 8});
        for (int start = 0; start < 8; start++) {
            RarBitInput input = new RarBitInput(new java.io.ByteArrayInputStream(new byte[]{0}));
            input.skipBits(start);
            for (int bit = start; bit < 8; bit++) assertEquals(0, table.decode(input));
            assertEquals(8, input.bitsRead());
            org.junit.Assert.assertThrows(java.io.EOFException.class, () -> table.decode(input));
        }
    }

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
