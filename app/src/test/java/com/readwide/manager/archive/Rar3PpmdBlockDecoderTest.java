package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class Rar3PpmdBlockDecoderTest {
    @Test
    public void decode_otherEscapeCodesEmitLiteralWithCustomEscape() throws Exception {
        Rar3PpmdState ppm = new Rar3PpmdState();
        ppm.setEscapeChar(0xfd);
        for (int control : new int[] {1, 6, 127, 255}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Rar3PpmdBlockDecoder.decodeUntilControlOrLimit(symbols(0xfd, control),
                    new RarLzWindow(64, out), new Rar3UnpackState(), ppm, 1);
            assertArrayEquals(new byte[] {(byte) 0xfd}, out.toByteArray());
        }
    }

    @Test
    public void decode_ppmdMatchesDoNotReplaceSavedClassicLzMatches() throws Exception {
        for (int control : new int[] {4, 5}) {
            Rar3UnpackState lz = new Rar3UnpackState();
            for (int distance = 1; distance <= 4; distance++) lz.rememberNewDistanceMatch(distance, 9);
            lz.rememberLowDistance(7);
            lz.startLowDistanceRepeat(15);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int[] encoded = control == 4 ? new int[] {'A', 'B', 2, 4, 0, 0, 0, 0}
                    : new int[] {'A', 'B', 2, 5, 0};
            int limit = control == 4 ? 34 : 6;
            Rar3PpmdBlockDecoder.decodeUntilControlOrLimit(symbols(encoded),
                    new RarLzWindow(64, out), lz, new Rar3PpmdState(), limit);
            assertEquals(limit, out.size());
            for (int i = 0; i < 4; i++) assertEquals(4 - i, lz.oldDistance(i));
            assertEquals(4, lz.lastDistance());
            assertEquals(9, lz.lastLength());
            assertEquals(7, lz.previousLowDistance());
            assertEquals(15, lz.lowDistanceRepeatCount());
        }
    }

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
