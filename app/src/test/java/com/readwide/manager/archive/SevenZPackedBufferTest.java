package com.readwide.manager.archive;

import static org.junit.Assert.*;

import com.readwide.manager.util.FileOperationProgress;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.Test;

public final class SevenZPackedBufferTest {
    private static final int BUFFER_SIZE = 64 * 1024;

    @Test public void singleByteConsumersShareBulkIntegrityChecks() throws Exception {
        byte[] data = payload(BUFFER_SIZE * 2 + 7);
        int[] reads = {0}, closes = {0};
        InputStream raw = observed(data, reads, closes);
        List<InputStream> owned = new ArrayList<>();
        try (InputStream packed = adopt(raw, data.length, crc(data), owned)) {
            assertTrue(packed instanceof BufferedInputStream);
            assertSame(packed, owned.get(0));
            assertEquals(0, reads[0]);
            for (byte value : data) assertEquals(value & 255, packed.read());
            assertEquals(3, reads[0]);
            assertEquals(-1, packed.read());
            assertEquals(4, reads[0]);
        }
        assertEquals(1, closes[0]);
    }

    @Test public void badPackedCrcFailsAtFillBeforeReturningItsPrefix() throws Exception {
        byte[] data = payload(37);
        int[] reads = {0}, closes = {0};
        try (InputStream packed = adopt(observed(data, reads, closes), data.length, crc(data) ^ 1,
                new ArrayList<>())) {
            IOException failure = assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                    packed::read);
            assertSame(failure, assertThrows(IOException.class, packed::read));
            assertEquals(1, reads[0]);
        }
        assertEquals(1, closes[0]);
    }

    @Test public void skippedBufferedBytesStillParticipateInPackedCrc() throws Exception {
        byte[] data = payload(BUFFER_SIZE + 19);
        try (InputStream packed = adopt(new ByteArrayInputStream(data), data.length, crc(data) ^ 1,
                new ArrayList<>())) {
            assertEquals(data[0] & 255, packed.read());
            assertEquals(BUFFER_SIZE - 1, packed.skip(BUFFER_SIZE - 1));
            assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class, packed::read);
        }
    }

    @Test public void drainingPackedRemainderStillRejectsBadCrc() throws Exception {
        byte[] data = payload(BUFFER_SIZE + 19);
        try (InputStream packed = adopt(new ByteArrayInputStream(data), data.length, crc(data) ^ 1,
                new ArrayList<>())) {
            assertEquals(data[0] & 255, packed.read());
            byte[] discard = new byte[BUFFER_SIZE];
            assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class, () -> {
                while (packed.read(discard) != -1) { }
            });
        }
    }

    @Test public void truncatedPackedRemainderCannotBecomeCleanEof() throws Exception {
        byte[] data = payload(BUFFER_SIZE + 3);
        try (InputStream packed = adopt(new ByteArrayInputStream(data), data.length + 1L, -1,
                new ArrayList<>())) {
            for (byte value : data) assertEquals(value & 255, packed.read());
            IOException failure = assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                    packed::read);
            assertSame(failure, assertThrows(IOException.class, packed::read));
        }
    }

    @Test public void emptyPackedStreamStillValidatesItsCrc() throws Exception {
        try (InputStream valid = adopt(new ByteArrayInputStream(new byte[0]), 0, 0,
                new ArrayList<>())) {
            assertEquals(-1, valid.read());
        }
        try (InputStream invalid = adopt(new ByteArrayInputStream(new byte[0]), 0, 1,
                new ArrayList<>())) {
            IOException failure = assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                    invalid::read);
            assertSame(failure, assertThrows(IOException.class, invalid::read));
        }
    }

    @Test public void sourceFailureOnRefillKeepsItsIdentityAndIsNotRetried() throws Exception {
        IOException failure = new IOException("source refill");
        int[] reads = {0}, closes = {0};
        InputStream raw = new InputStream() {
            @Override public int read() { throw new AssertionError("bulk source reads expected"); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (++reads[0] > 1) throw failure;
                bytes[offset] = 7;
                return 1;
            }
            @Override public void close() { closes[0]++; }
        };
        try (InputStream packed = adopt(raw, 2, -1, new ArrayList<>())) {
            assertEquals(7, packed.read());
            assertSame(failure, assertThrows(IOException.class, packed::read));
            assertSame(failure, assertThrows(IOException.class, packed::read));
            assertEquals(2, reads[0]);
        }
        assertEquals(1, closes[0]);
    }

    @Test public void partialCloseDoesNotDrainAndClosesTheSourceOnce() throws Exception {
        byte[] data = payload(BUFFER_SIZE + 9);
        int[] reads = {0}, closes = {0};
        InputStream packed = adopt(observed(data, reads, closes), data.length, crc(data),
                new ArrayList<>());
        assertEquals(data[0] & 255, packed.read());
        assertEquals(1, reads[0]);
        packed.close();
        packed.close();
        assertEquals(1, reads[0]);
        assertEquals(1, closes[0]);
        assertThrows(IOException.class, packed::read);
    }

    @Test public void cancelledTokenStopsBeforeTheFirstPhysicalRead() throws Exception {
        FileOperationProgress progress = token();
        progress.setPaused(true);
        progress.cancel();
        int[] reads = {0}, closes = {0};
        byte[] data = payload(19);
        try (InputStream packed = adopt(observed(data, reads, closes), data.length, crc(data),
                new ArrayList<>(), progress)) {
            InterruptedIOException failure = assertThrows(InterruptedIOException.class, packed::read);
            assertSame(failure, assertThrows(IOException.class, packed::read));
            assertEquals(0, reads[0]);
            assertFalse(Thread.currentThread().isInterrupted());
        }
        assertEquals(1, closes[0]);
    }

    @Test public void cancellationDuringRefillIsStickyAndDoesNotExposeThePrefix() throws Exception {
        FileOperationProgress progress = token();
        byte[] data = payload(BUFFER_SIZE + 9);
        int[] reads = {0}, closes = {0};
        InputStream raw = new ByteArrayInputStream(data) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                reads[0]++;
                int count = super.read(bytes, offset, length);
                progress.cancel();
                return count;
            }
            @Override public void close() { closes[0]++; }
        };
        try (InputStream packed = adopt(raw, data.length, crc(data), new ArrayList<>(), progress)) {
            InterruptedIOException failure = assertThrows(InterruptedIOException.class, packed::read);
            assertSame(failure, assertThrows(IOException.class, packed::read));
            assertEquals(1, reads[0]);
            assertFalse(Thread.currentThread().isInterrupted());
        }
        assertEquals(1, closes[0]);
    }

    @Test public void physicalReadFailureKeepsItsIdentityWhenItAlsoCancels() throws Exception {
        FileOperationProgress progress = token();
        IOException original = new IOException("packed source");
        int[] reads = {0}, closes = {0};
        InputStream raw = new InputStream() {
            @Override public int read() { throw new AssertionError("bulk source reads expected"); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                reads[0]++; progress.cancel(); throw original;
            }
            @Override public void close() { closes[0]++; }
        };
        try (InputStream packed = adopt(raw, 1, -1, new ArrayList<>(), progress)) {
            assertSame(original, assertThrows(IOException.class, packed::read));
            assertSame(original, assertThrows(IOException.class, packed::read));
            assertEquals(1, reads[0]);
        }
        assertEquals(1, closes[0]);
    }

    @Test public void emptyDeflateBlocksObserveCancellationWithinOneDecodeRead() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        // Empty stored blocks make one decoder read refill packed input repeatedly.
        for (int i = 0; i < 30000; i++) bytes.write(new byte[]{0, 0, 0, -1, -1});
        bytes.write(new byte[]{1, 1, 0, -2, -1, 42});
        byte[] data = bytes.toByteArray();
        FileOperationProgress progress = token();
        int[] reads = {0}, closes = {0};
        InputStream raw = new ByteArrayInputStream(data) {
            @Override public synchronized int read(byte[] output, int offset, int length) {
                int count = super.read(output, offset, length);
                if (++reads[0] == 2) progress.cancel();
                return count;
            }
            @Override public void close() { closes[0]++; }
        };
        try (InputStream packed = adopt(raw, data.length, crc(data), new ArrayList<>(), progress);
             InputStream decoder = OwnedDeflateInputStream.open(packed)) {
            byte[] output = {91};
            InterruptedIOException failure = assertThrows(InterruptedIOException.class,
                    () -> decoder.read(output));
            assertArrayEquals(new byte[]{91}, output);
            assertSame(failure, assertThrows(IOException.class, decoder::read));
            assertEquals(2, reads[0]);
            assertFalse(Thread.currentThread().isInterrupted());
        }
        assertEquals(1, closes[0]);
        try (InputStream packed = adopt(new ByteArrayInputStream(data), data.length, crc(data),
                new ArrayList<>(), token());
             InputStream decoder = OwnedDeflateInputStream.open(packed)) {
            assertEquals(42, decoder.read());
            assertEquals(-1, decoder.read());
        }
    }

    @Test public void activeTokenPreservesPackedBytesAndDoesNotCountOutput() throws Exception {
        FileOperationProgress progress = token();
        byte[] data = payload(BUFFER_SIZE * 2 + 7);
        int[] reads = {0}, closes = {0};
        try (InputStream packed = adopt(observed(data, reads, closes), data.length, crc(data),
                new ArrayList<>(), progress)) {
            assertArrayEquals(data, packed.readAllBytes());
            assertFalse(progress.isCancelled());
            assertEquals(0, progress.snapshot().doneBytes);
        }
        assertEquals(1, closes[0]);
    }

    private static InputStream observed(byte[] data, int[] reads, int[] closes) {
        return new ByteArrayInputStream(data) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                reads[0]++;
                return super.read(bytes, offset, length);
            }
            @Override public int read() { reads[0]++; return super.read(); }
            @Override public void close() { closes[0]++; }
        };
    }

    private static InputStream adopt(InputStream raw, long size, long crc,
                                      List<InputStream> owned) throws Exception {
        Method method = SevenZBcj2ArchiveReader.class.getDeclaredMethod("adoptPackedStream",
                InputStream.class, long.class, long.class, List.class);
        method.setAccessible(true);
        return (InputStream) method.invoke(null, raw, size, crc, owned);
    }

    private static InputStream adopt(InputStream raw, long size, long crc,
                                      List<InputStream> owned, FileOperationProgress progress) throws Exception {
        Method method = SevenZBcj2ArchiveReader.class.getDeclaredMethod("adoptPackedStream",
                InputStream.class, long.class, long.class, List.class, FileOperationProgress.class);
        method.setAccessible(true);
        return (InputStream) method.invoke(null, raw, size, crc, owned, progress);
    }

    private static FileOperationProgress token() {
        return new FileOperationProgress("7z packed input", null);
    }

    private static byte[] payload(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) data[i] = (byte) (i * 31);
        return data;
    }

    private static long crc(byte[] data) {
        CRC32 crc = new CRC32(); crc.update(data); return crc.getValue();
    }
}
