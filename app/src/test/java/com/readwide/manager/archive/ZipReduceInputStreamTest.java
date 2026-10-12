package com.readwide.manager.archive;

import java.io.*;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Specification-built fixtures; no producer interoperability or execution claim. */
public class ZipReduceInputStreamTest {
    @Test public void allFactorsHandleLiteralsEscapesAndMixedReadSizes() throws Exception {
        byte[] raw = new byte[12001];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) i;
        for (int method = 2; method <= 5; method++) assertArrayEquals(raw, decode(literals(raw), method, raw.length));
    }
    @Test public void singleFollowerUsesOneIndexBitAndEscapeFlag() throws Exception {
        Bits bits = table(0, new byte[]{65});
        bits.put(0, 1); bits.put(0, 1); // follower A of initial zero
        bits.put(0, 8); // literal zero after A
        bits.put(1, 1); bits.put(66, 8); // explicit B after zero
        assertArrayEquals(new byte[]{65,0,66}, decode(bits.finish(), 2, 3));
    }
    @Test public void invalidFollowerIndexAndCountFailClosed() throws Exception {
        Bits bits = table(0, new byte[]{1,2,3}); bits.put(0,1); bits.put(3,2);
        assertThrows(IOException.class, () -> decode(bits.finish(), 2, 1));
        byte[] bad = new byte[193]; bad[0] = 33;
        assertThrows(IOException.class, () -> decode(bad, 2, 1));
    }
    @Test public void overlappingAndExtendedMatchesContinueAcrossCalls() throws Exception {
        for (int method = 2; method <= 5; method++) {
            int mask = (1 << (9 - method)) - 1;
            byte[] symbols = {(byte)'A',(byte)144,(byte)mask,7,0};
            byte[] raw = new byte[mask + 11]; Arrays.fill(raw, (byte)'A');
            assertArrayEquals(raw, decode(symbols(symbols), method, raw.length));
        }
    }
    @Test public void maximumDistancesUseZeroPrehistoryWithoutPayloadLimit() throws Exception {
        for (int method = 2; method <= 5; method++) {
            int control = ((1 << (method - 1)) - 1) << (9 - method);
            assertArrayEquals(new byte[3], decode(symbols(new byte[]{(byte)144,(byte)control,(byte)255}), method, 3));
        }
        try (InputStream in = new ZipReduceInputStream(new ByteArrayInputStream(new byte[0]), 5, Long.MAX_VALUE)) {
            assertThrows(IOException.class, in::read); // Truncation, not constructor size rejection.
        }
    }
    @Test public void truncatedOversizedAndTrailingStreamsFail() throws Exception {
        byte[] literal = literals(new byte[]{42});
        assertThrows(IOException.class, () -> decode(Arrays.copyOf(literal, literal.length - 1), 2, 1));
        assertThrows(IOException.class, () -> decode(Arrays.copyOf(literal, literal.length + 1), 2, 1));
        assertThrows(IOException.class, () -> decode(symbols(new byte[]{(byte)144,1,0}), 2, 3));
        assertArrayEquals(new byte[0], decode(literals(new byte[0]), 2, 0));
    }
    @Test public void cancellationIsTerminalAndCloseOwnsSource() throws Exception {
        boolean[] closed = {false};
        InputStream source = new ByteArrayInputStream(literals(new byte[]{1})) {
            @Override public void close() { closed[0] = true; }
        };
        try (ZipReduceInputStream in = new ZipReduceInputStream(source, 2, 1)) {
            Thread.currentThread().interrupt();
            IOException first;
            try { first = assertThrows(InterruptedIOException.class, in::read); }
            finally { Thread.interrupted(); }
            assertSame(first, assertThrows(IOException.class, in::read));
        }
        assertTrue(closed[0]);
    }
    static byte[] literals(byte[] raw) throws IOException {
        ByteArrayOutputStream symbols = new ByteArrayOutputStream();
        for (byte b : raw) { symbols.write(b); if ((b & 255) == 144) symbols.write(0); }
        return symbols(symbols.toByteArray());
    }
    private static byte[] symbols(byte[] symbols) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[192]); output.write(symbols); return output.toByteArray();
    }
    private static Bits table(int previous, byte[] values) {
        Bits bits = new Bits();
        for (int i = 255; i >= 0; i--) {
            bits.put(i == previous ? values.length : 0, 6);
            if (i == previous) for (byte b : values) bits.put(b & 255, 8);
        }
        return bits;
    }
    private static byte[] decode(byte[] packed, int method, long size) throws IOException {
        try (InputStream in = new ZipReduceInputStream(new ByteArrayInputStream(packed), method, size)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] block = new byte[37];
            int first = in.read(); if (first != -1) out.write(first);
            int n; while ((n = in.read(block)) != -1) out.write(block, 0, n);
            assertEquals(-1, in.read()); return out.toByteArray();
        }
    }
    private static final class Bits {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(); int byteValue, count;
        void put(int value, int length) {
            for (int i = 0; i < length; i++) {
                byteValue |= ((value >>> i) & 1) << count++;
                if (count == 8) { out.write(byteValue); byteValue = count = 0; }
            }
        }
        byte[] finish() { if (count != 0) { out.write(byteValue); byteValue = count = 0; } return out.toByteArray(); }
    }
}
