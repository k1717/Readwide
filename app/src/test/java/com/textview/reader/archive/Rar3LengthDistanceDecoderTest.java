package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class Rar3LengthDistanceDecoderTest {
    @Test
    public void longLengthSlotMapping_usesBaseAndExtraRanges() throws Exception {
        assertEquals(3, Rar3LengthDistanceDecoder.decodeLengthSlotForTest(0));
        assertEquals(10, Rar3LengthDistanceDecoder.decodeLengthSlotForTest(7));
        assertEquals(11, Rar3LengthDistanceDecoder.decodeLengthSlotForTest(8));
        assertEquals(131, Rar3LengthDistanceDecoder.decodeLengthSlotForTest(24));
    }

    @Test
    public void distanceSlotMapping_usesRar3BaseAndExtraRanges() throws Exception {
        assertEquals(1, Rar3LengthDistanceDecoder.distanceBaseForSlotForTest(0));
        assertEquals(4, Rar3LengthDistanceDecoder.distanceBaseForSlotForTest(3));
        assertEquals(5, Rar3LengthDistanceDecoder.distanceBaseForSlotForTest(4));
        assertEquals(7, Rar3LengthDistanceDecoder.distanceBaseForSlotForTest(5));
        assertEquals(33, Rar3LengthDistanceDecoder.distanceBaseForSlotForTest(10));

        assertEquals(0, Rar3LengthDistanceDecoder.distanceExtraBitsForTest(0));
        assertEquals(1, Rar3LengthDistanceDecoder.distanceExtraBitsForTest(4));
        assertEquals(4, Rar3LengthDistanceDecoder.distanceExtraBitsForTest(10));
    }
    @Test
    public void longDistanceSlotTenUsesLowDistanceTable() throws Exception {
        Rar3HuffmanTables tables = tablesWithLengths(
                new int[Rar3HuffmanTables.NC],
                oneSymbol(Rar3HuffmanTables.DC, 10),
                oneSymbol(Rar3HuffmanTables.LDC, 4),
                new int[Rar3HuffmanTables.RC]);

        Rar3LengthDistanceDecoder.Match match = Rar3LengthDistanceDecoder.decodeLongMatch(
                Rar3DecodeAction.longMatch(0),
                tables,
                new RarBitInput(new byte[] {0}),
                new Rar3UnpackState());

        assertEquals(37, match.distance);
        assertEquals(3, match.length);
    }


    @Test
    public void oldDistanceMatchDoesNotApplyLongDistanceLengthCorrection() throws Exception {
        Rar3HuffmanTables tables = tablesWithLengths(
                new int[Rar3HuffmanTables.NC],
                new int[Rar3HuffmanTables.DC],
                new int[Rar3HuffmanTables.LDC],
                oneSymbol(Rar3HuffmanTables.RC, 6));
        Rar3UnpackState state = new Rar3UnpackState();
        state.rememberNewDistanceMatch(424, 26);

        Rar3LengthDistanceDecoder.Match match = Rar3LengthDistanceDecoder.decodeOldDistanceMatch(
                Rar3DecodeAction.oldDistanceMatch(0),
                tables,
                new RarBitInput(new byte[] {0}),
                state);

        assertEquals(424, match.distance);
        assertEquals(8, match.length);
    }

    @Test
    public void shortDistanceMatchAlwaysHasLengthTwo() throws Exception {
        Rar3LengthDistanceDecoder.Match match = Rar3LengthDistanceDecoder.decodeShortDistanceMatch(
                Rar3DecodeAction.shortDistanceMatch(7, 9),
                new RarBitInput(new byte[] {0}));

        assertEquals(193, match.distance);
        assertEquals(2, match.length);
    }

    private static Rar3HuffmanTables tablesWithLengths(int[] literalLengths,
                                                       int[] distanceLengths,
                                                       int[] lowDistanceLengths,
                                                       int[] repeatLengths) throws Exception {
        int[] all = new int[Rar3HuffmanTables.TABLE_SIZE];
        System.arraycopy(literalLengths, 0, all, 0, literalLengths.length);
        System.arraycopy(distanceLengths, 0, all, Rar3HuffmanTables.NC, distanceLengths.length);
        System.arraycopy(lowDistanceLengths, 0, all,
                Rar3HuffmanTables.NC + Rar3HuffmanTables.DC, lowDistanceLengths.length);
        System.arraycopy(repeatLengths, 0, all,
                Rar3HuffmanTables.NC + Rar3HuffmanTables.DC + Rar3HuffmanTables.LDC,
                repeatLengths.length);
        return new Rar3HuffmanTables(
                false,
                all,
                RarCanonicalHuffman.fromCodeLengths(literalLengths),
                RarCanonicalHuffman.fromCodeLengths(distanceLengths),
                RarCanonicalHuffman.fromCodeLengths(lowDistanceLengths),
                RarCanonicalHuffman.fromCodeLengths(repeatLengths));
    }

    private static int[] oneSymbol(int size, int symbol) {
        int[] lengths = new int[size];
        lengths[symbol] = 1;
        return lengths;
    }

}
