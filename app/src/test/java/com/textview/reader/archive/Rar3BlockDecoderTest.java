package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class Rar3BlockDecoderTest {
    @Test
    public void decodeUntilEndOrUnsupported_writesLiteralAndStopsAtEndBlock() throws Exception {
        int[] literalLengths = new int[Rar3HuffmanTables.NC];
        literalLengths['A'] = 1;
        literalLengths[Rar3SymbolDecoder.SYMBOL_END_BLOCK] = 1;
        Rar3HuffmanTables tables = tablesWithLengths(
                literalLengths,
                new int[Rar3HuffmanTables.DC],
                new int[Rar3HuffmanTables.LDC],
                new int[Rar3HuffmanTables.RC]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarLzWindow window = new RarLzWindow(16, out);

        Rar3BlockDecoder.EndOfBlock ended = Rar3BlockDecoder.decodeUntilEndOrUnsupported(
                tables,
                new RarBitInput(new byte[] {(byte) 0b0100_0000, 0, 0}),
                window,
                new Rar3UnpackState(),
                10);

        assertTrue(ended != Rar3BlockDecoder.EndOfBlock.LIMIT_REACHED);
        assertArrayEquals("A".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }

    @Test
    public void decodeUntilEndOrUnsupported_executesLiteralAndLongMatch() throws Exception {
        int[] literalLengths = new int[Rar3HuffmanTables.NC];
        literalLengths['A'] = 2;
        literalLengths[Rar3SymbolDecoder.SYMBOL_END_BLOCK] = 2;
        literalLengths[Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST] = 2;
        Rar3HuffmanTables tables = tablesWithLengths(
                literalLengths,
                oneSymbol(Rar3HuffmanTables.DC, 0),
                new int[Rar3HuffmanTables.LDC],
                new int[Rar3HuffmanTables.RC]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Rar3BlockDecoder.EndOfBlock ended = Rar3BlockDecoder.decodeUntilEndOrUnsupported(
                tables,
                bitInput("00100010" + endBlockPadding()),
                new RarLzWindow(16, out),
                new Rar3UnpackState(),
                10);

        assertTrue(ended != Rar3BlockDecoder.EndOfBlock.LIMIT_REACHED);
        assertArrayEquals("AAAA".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }

    @Test
    public void decodeUntilEndOrUnsupported_oldDistanceReusesPriorDistance() throws Exception {
        int[] literalLengths = new int[Rar3HuffmanTables.NC];
        literalLengths['A'] = 2;
        literalLengths[Rar3SymbolDecoder.SYMBOL_END_BLOCK] = 2;
        literalLengths[Rar3SymbolDecoder.SYMBOL_OLD_DISTANCE_FIRST] = 2;
        literalLengths[Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST] = 2;
        Rar3HuffmanTables tables = tablesWithLengths(
                literalLengths,
                oneSymbol(Rar3HuffmanTables.DC, 0),
                new int[Rar3HuffmanTables.LDC],
                oneSymbol(Rar3HuffmanTables.RC, 0));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Rar3BlockDecoder.EndOfBlock ended = Rar3BlockDecoder.decodeUntilEndOrUnsupported(
                tables,
                bitInput("0011010001" + endBlockPadding()),
                new RarLzWindow(16, out),
                new Rar3UnpackState(),
                10);

        assertTrue(ended != Rar3BlockDecoder.EndOfBlock.LIMIT_REACHED);
        assertArrayEquals("AAAAAA".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }

    @Test
    public void decodeUntilEndOrUnsupported_repeatLastMatchReusesPreviousLengthAndDistance() throws Exception {
        int[] literalLengths = new int[Rar3HuffmanTables.NC];
        literalLengths['A'] = 2;
        literalLengths[Rar3SymbolDecoder.SYMBOL_END_BLOCK] = 2;
        literalLengths[Rar3SymbolDecoder.SYMBOL_REPEAT_LAST_MATCH] = 2;
        literalLengths[Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST] = 2;
        Rar3HuffmanTables tables = tablesWithLengths(
                literalLengths,
                oneSymbol(Rar3HuffmanTables.DC, 0),
                new int[Rar3HuffmanTables.LDC],
                new int[Rar3HuffmanTables.RC]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Rar3BlockDecoder.EndOfBlock ended = Rar3BlockDecoder.decodeUntilEndOrUnsupported(
                tables,
                bitInput("001101001" + endBlockPadding()),
                new RarLzWindow(16, out),
                new Rar3UnpackState(),
                12);

        assertTrue(ended != Rar3BlockDecoder.EndOfBlock.LIMIT_REACHED);
        assertArrayEquals("AAAAAAA".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }

    @Test
    public void decodeUntilEndOrUnsupported_clippedMatchKeepsDecodedLastLength() throws Exception {
        int[] literalLengths = new int[Rar3HuffmanTables.NC];
        literalLengths['A'] = 2;
        literalLengths[Rar3SymbolDecoder.SYMBOL_END_BLOCK] = 2;
        literalLengths[Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST] = 2;
        Rar3HuffmanTables tables = tablesWithLengths(
                literalLengths,
                oneSymbol(Rar3HuffmanTables.DC, 0),
                new int[Rar3HuffmanTables.LDC],
                new int[Rar3HuffmanTables.RC]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Rar3UnpackState state = new Rar3UnpackState();

        Rar3BlockDecoder.EndOfBlock ended = Rar3BlockDecoder.decodeUntilEndOrUnsupported(
                tables,
                bitInput("00100"),
                new RarLzWindow(16, out),
                state,
                3);

        assertEquals(Rar3BlockDecoder.EndOfBlock.LIMIT_REACHED, ended);
        assertArrayEquals("AAA".getBytes(StandardCharsets.UTF_8), out.toByteArray());
        assertEquals(1, state.lastDistance());
        assertEquals(3, state.lastLength());
    }

    @Test
    public void decodeUntilEndOrUnsupported_shortDistanceMatchUsesShortDistanceSlot() throws Exception {
        int[] literalLengths = new int[Rar3HuffmanTables.NC];
        literalLengths['a'] = 3;
        literalLengths['b'] = 3;
        literalLengths['c'] = 3;
        literalLengths['d'] = 3;
        literalLengths[Rar3SymbolDecoder.SYMBOL_END_BLOCK] = 3;
        literalLengths[Rar3SymbolDecoder.SYMBOL_SHORT_DISTANCE_FIRST] = 3;
        Rar3HuffmanTables tables = tablesWithLengths(
                literalLengths,
                new int[Rar3HuffmanTables.DC],
                new int[Rar3HuffmanTables.LDC],
                new int[Rar3HuffmanTables.RC]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Rar3BlockDecoder.EndOfBlock ended = Rar3BlockDecoder.decodeUntilEndOrUnsupported(
                tables,
                bitInput("00000101001110100100" + endBlockPadding()),
                new RarLzWindow(16, out),
                new Rar3UnpackState(),
                16);

        assertTrue(ended != Rar3BlockDecoder.EndOfBlock.LIMIT_REACHED);
        assertArrayEquals("abcddd".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }


    @Test
    public void decodeUntilEndOrUnsupported_vmFilterReportsOutputOffset() throws Exception {
        int[] literalLengths = new int[Rar3HuffmanTables.NC];
        literalLengths['A'] = 2;
        literalLengths[Rar3SymbolDecoder.SYMBOL_VM_FILTER] = 2;
        Rar3HuffmanTables tables = tablesWithLengths(
                literalLengths,
                new int[Rar3HuffmanTables.DC],
                new int[Rar3HuffmanTables.LDC],
                new int[Rar3HuffmanTables.RC]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            Rar3BlockDecoder.decodeUntilEndOrUnsupported(
                    tables,
                    bitInput("00010000000011100000"),
                    new RarLzWindow(16, out),
                    new Rar3UnpackState(),
                    10);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("VM filters"));
            assertTrue(expected.getMessage().contains("outputOffset=1"));
            assertTrue(expected.getMessage().contains("codeLength=1"));
            assertTrue(expected.getMessage().contains("standardFilter=unknown"));
            assertArrayEquals("A".getBytes(StandardCharsets.UTF_8), out.toByteArray());
            return;
        }
        throw new AssertionError("VM-filter blocks must remain a precise first-party gap");
    }

    @Test(expected = IOException.class)
    public void decodeUntilEndOrUnsupported_oldDistanceWithoutStateFailsCleanly() throws Exception {
        int[] literalLengths = new int[Rar3HuffmanTables.NC];
        literalLengths[Rar3SymbolDecoder.SYMBOL_OLD_DISTANCE_FIRST] = 1;
        Rar3HuffmanTables tables = tablesWithLengths(
                literalLengths,
                new int[Rar3HuffmanTables.DC],
                new int[Rar3HuffmanTables.LDC],
                oneSymbol(Rar3HuffmanTables.RC, 0));

        Rar3BlockDecoder.decodeUntilEndOrUnsupported(
                tables,
                bitInput("0"),
                new RarLzWindow(16, new ByteArrayOutputStream()),
                new Rar3UnpackState(),
                10);
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

    private static RarBitInput bitInput(String bits) {
        int byteCount = (bits.length() + 7) / 8;
        byte[] data = new byte[byteCount];
        for (int i = 0; i < bits.length(); i++) {
            if (bits.charAt(i) == '1') {
                data[i / 8] |= (byte) (1 << (7 - (i % 8)));
            }
        }
        return new RarBitInput(data);
    }

    private static String endBlockPadding() {
        return "0000000000000000";
    }
}
