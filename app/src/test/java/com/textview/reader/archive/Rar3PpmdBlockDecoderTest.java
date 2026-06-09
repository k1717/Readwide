package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class Rar3PpmdBlockDecoderTest {
    @Test
    public void decode_writesLiteralAndEndsPpmdBlock() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarLzWindow window = new RarLzWindow(32, out);

        Rar3PpmdDecodeResult result = Rar3PpmdBlockDecoder.decodeUntilControlOrLimit(
                symbols('A', 'B', Rar3PpmdState.DEFAULT_ESCAPE_CHAR, 0),
                window,
                new Rar3UnpackState(),
                new Rar3PpmdState(),
                16);

        assertEquals(Rar3PpmdDecodeResult.END_BLOCK, result.type);
        assertEquals(2, result.written);
        assertEquals(4, result.symbolsRead);
        assertArrayEquals("AB".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }

    @Test
    public void decode_escapeOneWritesLiteralEscapeByte() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarLzWindow window = new RarLzWindow(32, out);

        Rar3PpmdDecodeResult result = Rar3PpmdBlockDecoder.decodeUntilControlOrLimit(
                symbols('A', Rar3PpmdState.DEFAULT_ESCAPE_CHAR, 1, 'B'),
                window,
                new Rar3UnpackState(),
                new Rar3PpmdState(),
                3);

        assertEquals(Rar3PpmdDecodeResult.LIMIT_REACHED, result.type);
        assertArrayEquals(new byte[] {'A', Rar3PpmdState.DEFAULT_ESCAPE_CHAR, 'B'}, out.toByteArray());
    }

    @Test
    public void decode_ppmdLzMatchCopiesPreviousBytes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarLzWindow window = new RarLzWindow(64, out);

        Rar3PpmdDecodeResult result = Rar3PpmdBlockDecoder.decodeUntilControlOrLimit(
                symbols('A', 'B', 'C',
                        Rar3PpmdState.DEFAULT_ESCAPE_CHAR, 4,
                        0, 0, 1, // distance = 1 + 2 = 3.
                        0),       // length = 0 + 32, clamped to remaining 3.
                window,
                new Rar3UnpackState(),
                new Rar3PpmdState(),
                6);

        assertEquals(Rar3PpmdDecodeResult.LIMIT_REACHED, result.type);
        assertArrayEquals("ABCABC".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }

    @Test
    public void decode_ppmdRleMatchCopiesDistanceOne() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarLzWindow window = new RarLzWindow(32, out);

        Rar3PpmdDecodeResult result = Rar3PpmdBlockDecoder.decodeUntilControlOrLimit(
                symbols('Z', Rar3PpmdState.DEFAULT_ESCAPE_CHAR, 5, 0), // length 4, clamp to 3.
                window,
                new Rar3UnpackState(),
                new Rar3PpmdState(),
                4);

        assertEquals(Rar3PpmdDecodeResult.LIMIT_REACHED, result.type);
        assertArrayEquals("ZZZZ".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }

    @Test
    public void decode_escapeTwoEndsFile() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarLzWindow window = new RarLzWindow(32, out);

        Rar3PpmdDecodeResult result = Rar3PpmdBlockDecoder.decodeUntilControlOrLimit(
                symbols('A', Rar3PpmdState.DEFAULT_ESCAPE_CHAR, 2),
                window,
                new Rar3UnpackState(),
                new Rar3PpmdState(),
                16);

        assertEquals(Rar3PpmdDecodeResult.END_FILE, result.type);
        assertArrayEquals("A".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }

    @Test
    public void decode_escapeThreeReportsVmFilterGap() throws Exception {
        try {
            Rar3PpmdBlockDecoder.decodeUntilControlOrLimit(
                    symbols('A', Rar3PpmdState.DEFAULT_ESCAPE_CHAR, 3),
                    new RarLzWindow(32, new ByteArrayOutputStream()),
                    new Rar3UnpackState(),
                    new Rar3PpmdState(),
                    16);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("PPMd"));
            assertTrue(expected.getMessage().contains("VM filters"));
            return;
        }
        throw new AssertionError("PPMd escape 3 must remain a precise VM-filter gap");
    }

    @Test
    public void decode_customEscapeStateIsHonored() throws Exception {
        Rar3PpmdState state = new Rar3PpmdState();
        state.setEscapeChar(0xff);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Rar3PpmdDecodeResult result = Rar3PpmdBlockDecoder.decodeUntilControlOrLimit(
                symbols('A', 0xff, 1, 'B', 0xff, 0),
                new RarLzWindow(32, out),
                new Rar3UnpackState(),
                state,
                16);

        assertEquals(Rar3PpmdDecodeResult.END_BLOCK, result.type);
        assertArrayEquals(new byte[] {'A', (byte) 0xff, 'B'}, out.toByteArray());
    }

    private static Rar3PpmdSymbolSource symbols(int... values) {
        return new Rar3PpmdSymbolSource() {
            int index;
            @Override public int decodeSymbol() {
                return index < values.length ? values[index++] : -1;
            }
        };
    }
}
