package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.Test;

/** Source regressions for shared buffering, not measured throughput or producer coverage. */
public class BzipDecoderAdapterIntegrationTest {
    @Test public void bothFactoriesUseBulkRefillsWithoutReadingPastPackedBoundary() throws Exception {
        byte[] expected = Round4Fixtures.data(190043);
        byte[] packed = concatenated(expected);
        byte[] physical = java.util.Arrays.copyOf(packed, packed.length + 4);
        physical[packed.length] = 91;
        for (int route = 0; route < 2; route++) {
            ByteArrayInputStream raw = new ByteArrayInputStream(physical);
            int[] scalar = {0}, bulk = {0}, remaining = {packed.length}, closes = {0};
            InputStream bounded = new InputStream() {
                @Override public int read() { scalar[0]++; throw new AssertionError("bulk refill expected"); }
                @Override public int read(byte[] bytes, int offset, int length) {
                    bulk[0]++; assertTrue(length <= 65536);
                    if (remaining[0] == 0) return -1;
                    int count = raw.read(bytes, offset, Math.min(length, remaining[0]));
                    if (count > 0) remaining[0] -= count;
                    return count;
                }
                @Override public void close() { closes[0]++; }
            };
            try (InputStream decoded = open(route, bounded)) {
                assertArrayEquals(expected, decoded.readAllBytes());
                assertEquals(-1, decoded.read());
            }
            assertEquals(0, scalar[0]);
            assertTrue(bulk[0] <= (packed.length + 65535) / 65536 + 3);
            assertEquals(1, closes[0]);
            assertEquals(91, raw.read());
        }
    }

    @Test public void lateMemberCrcTruncationAndJunkStayTerminal() throws Exception {
        byte[] packed = concatenated(Round4Fixtures.data(3001));
        byte[] badCrc = packed.clone(); badCrc[badCrc.length - 2] ^= 0x40;
        byte[] truncated = java.util.Arrays.copyOf(packed, packed.length - 5);
        byte[] junk = java.util.Arrays.copyOf(packed, packed.length + 1); junk[junk.length - 1] = 1;
        for (int route = 0; route < 2; route++) {
            for (byte[] invalid : new byte[][] {badCrc, truncated, junk}) {
                try (InputStream decoded = open(route, new ByteArrayInputStream(invalid))) {
                    IOException failure = assertThrows(IOException.class, decoded::readAllBytes);
                    if (route == 0) assertTrue(failure instanceof SevenZBcj2ArchiveReader.IntegrityException);
                    assertThrows(IOException.class, decoded::read);
                }
            }
        }
    }

    @Test public void rejectedConstructionLeavesSourceWithCaller() throws Exception {
        for (int route = 0; route < 2; route++) {
            int[] closes = {0};
            InputStream source = new ByteArrayInputStream(new byte[] {1, 2, 3}) {
                @Override public void close() { closes[0]++; }
            };
            final int selected = route;
            assertThrows(IOException.class, () -> open(selected, source));
            assertEquals(0, closes[0]);
            source.close(); assertEquals(1, closes[0]);
        }
    }

    @Test public void interruptionIsNotReclassifiedAsCorruptPayload() throws Exception {
        for (int route = 0; route < 2; route++) {
            try (InputStream decoded = open(route, new ByteArrayInputStream(concatenated(new byte[] {42})))) {
                Thread.currentThread().interrupt();
                try { assertThrows(InterruptedIOException.class, decoded::read); }
                finally { Thread.interrupted(); }
                assertThrows(InterruptedIOException.class, decoded::read);
            }
        }
    }

    @Test public void closeFailureIsPreservedWithoutRepeatedOwnershipRelease() throws Exception {
        for (int route = 0; route < 2; route++) {
            IOException expected = new IOException("source close");
            int[] closes = {0};
            InputStream source = new ByteArrayInputStream(concatenated(new byte[] {42})) {
                @Override public void close() throws IOException { closes[0]++; throw expected; }
            };
            InputStream decoded = open(route, source);
            assertArrayEquals(new byte[] {42}, decoded.readAllBytes());
            assertSame(expected, assertThrows(IOException.class, decoded::close));
            decoded.close(); assertEquals(1, closes[0]);
        }
    }

    private static InputStream open(int route, InputStream source) throws Exception {
        if (route == 0) return SevenZAdditionalCoders.open(new byte[] {4, 2, 2}, null, source);
        Method factory = ZipxAesArchiveReader.class.getDeclaredMethod(
                "wrapCompressedStream", InputStream.class, int.class, long.class, boolean.class);
        factory.setAccessible(true);
        try { return (InputStream) factory.invoke(null, source, 12, 0L, false); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw (Error) failure.getCause();
        }
    }

    private static byte[] concatenated(byte[] plain) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BZip2CompressorOutputStream empty = new BZip2CompressorOutputStream(bytes)) { }
        try (BZip2CompressorOutputStream data = new BZip2CompressorOutputStream(bytes)) { data.write(plain); }
        try (BZip2CompressorOutputStream empty = new BZip2CompressorOutputStream(bytes)) { }
        return bytes.toByteArray();
    }
}
