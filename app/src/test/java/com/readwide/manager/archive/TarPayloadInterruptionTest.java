package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import org.junit.Test;

/** Injected payload I/O only: no large archive, timing, or device dependency. */
public class TarPayloadInterruptionTest {
    @Test public void interruptedReadAndSkipDoNotEnterTheCodec() throws Exception {
        InputStream untouched = new InputStream() {
            @Override public int read() { throw new AssertionError("Codec must not run after cancellation"); }
            @Override public long skip(long count) { throw new AssertionError("Codec must not skip after cancellation"); }
        };
        try (InputStream wrapped = wrap(untouched)) {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, wrapped::read);
            assertThrows(InterruptedIOException.class, () -> wrapped.read(new byte[4]));
            assertThrows(InterruptedIOException.class, () -> wrapped.skip(Long.MAX_VALUE));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void scalarReadCancellationCannotBecomeDataOrCleanEof() throws Exception {
        for (int result : new int[]{12, -1}) {
            InputStream interrupting = new InputStream() {
                @Override public int read() { Thread.currentThread().interrupt(); return result; }
            };
            try (InputStream wrapped = wrap(interrupting)) {
                assertThrows(InterruptedIOException.class, wrapped::read);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void bulkReadCancellationCannotBecomeDataOrCleanEof() throws Exception {
        for (int result : new int[]{1, -1}) {
            InputStream interrupting = new InputStream() {
                @Override public int read() { throw new AssertionError("Unexpected scalar read"); }
                @Override public int read(byte[] bytes, int offset, int length) {
                    Thread.currentThread().interrupt();
                    return result;
                }
            };
            try (InputStream wrapped = wrap(interrupting)) {
                assertThrows(InterruptedIOException.class, () -> wrapped.read(new byte[4]));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void hugeSkipPollsCancellationBetweenBoundedCodecCalls() throws Exception {
        for (int cancelAt : new int[]{1, 2}) {
            int[] calls = {0};
            InputStream interrupting = new InputStream() {
                @Override public int read() { throw new AssertionError("Skip must not read an unrelated tail"); }
                @Override public long skip(long count) {
                    assertTrue(count > 0L && count <= 64L * 1024L);
                    if (++calls[0] == cancelAt) Thread.currentThread().interrupt();
                    return count;
                }
            };
            try (InputStream wrapped = wrap(interrupting)) {
                assertThrows(InterruptedIOException.class, () -> wrapped.skip(Long.MAX_VALUE));
                assertEquals(cancelAt, calls[0]);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void successfulLargeSkipPreservesExactRequestedCount() throws Exception {
        int[] calls = {0};
        InputStream source = new InputStream() {
            @Override public int read() { throw new AssertionError("Unexpected read"); }
            @Override public long skip(long count) {
                assertTrue(count > 0L && count <= 64L * 1024L);
                calls[0]++;
                return count;
            }
        };
        try (InputStream wrapped = wrap(source)) {
            long count = 3L * 64L * 1024L + 7L;
            assertEquals(count, wrapped.skip(count));
            assertEquals(4, calls[0]);
        }
    }

    @Test public void shortAndZeroSkipsRemainLegalAndDoNotReadAhead() throws Exception {
        ByteArrayInputStream bytes = new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5});
        int[] calls = {0};
        InputStream source = new InputStream() {
            @Override public int read() { return bytes.read(); }
            @Override public long skip(long count) {
                if (++calls[0] == 2) return 0L;
                return bytes.skip(Math.min(count, 2L));
            }
        };
        try (InputStream wrapped = wrap(source)) {
            assertEquals(2L, wrapped.skip(20L));
            assertEquals(2, calls[0]);
            assertEquals(3, wrapped.read());
            assertEquals(2L, wrapped.skip(20L));
            assertEquals(-1, wrapped.read());
        }
    }

    @Test public void partialBulkReadAndEofArePreserved() throws Exception {
        InputStream source = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 1));
            }
        };
        try (InputStream wrapped = wrap(source)) {
            byte[] result = new byte[5];
            assertEquals(1, wrapped.read(result, 1, 3));
            assertEquals(1, wrapped.read(result, 2, 2));
            assertEquals(1, wrapped.read(result, 3, 1));
            assertArrayEquals(new byte[]{0, 1, 2, 3, 0}, result);
            assertEquals(-1, wrapped.read(result));
        }
    }

    @Test public void nonpositiveSkipAndEmptyReadDoNotCallTheCodec() throws Exception {
        InputStream untouched = new InputStream() {
            @Override public int read() { throw new AssertionError("Unexpected read"); }
            @Override public long skip(long count) { throw new AssertionError("Unexpected skip"); }
        };
        try (InputStream wrapped = wrap(untouched)) {
            assertEquals(0L, wrapped.skip(0L));
            assertEquals(0L, wrapped.skip(-1L));
            assertEquals(0L, wrapped.skip(Long.MIN_VALUE));
            assertEquals(0, wrapped.read(new byte[0]));
            assertThrows(NullPointerException.class, () -> wrapped.read(null, 0, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> wrapped.read(new byte[2], 3, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> wrapped.read(new byte[2], 1, Integer.MAX_VALUE));
        }
    }

    @Test public void invalidCodecSkipCountsRemainTerminalIntegrityFailures() throws Exception {
        for (long result : new long[]{-1L, 65537L}) {
            InputStream invalid = new InputStream() {
                @Override public int read() { throw new AssertionError("Unexpected read"); }
                @Override public long skip(long count) { return result; }
            };
            try (InputStream wrapped = wrap(invalid)) {
                IOException failure = assertThrows(ArchiveSupport.TarIntegrityException.class,
                        () -> wrapped.skip(65536L));
                assertTrue(failure.getCause() instanceof IOException);
                assertFalse(Thread.currentThread().isInterrupted());
            }
        }
    }

    @Test public void wrappedSkipCancellationRetainsItsOriginalException() throws Exception {
        InterruptedIOException cancelled = new InterruptedIOException("codec cancelled");
        InputStream source = new InputStream() {
            @Override public int read() { throw new AssertionError("Unexpected read"); }
            @Override public long skip(long count) throws IOException {
                throw new IOException("codec wrapper", cancelled);
            }
        };
        try (InputStream wrapped = wrap(source)) {
            assertSame(cancelled, assertThrows(InterruptedIOException.class, () -> wrapped.skip(1L)));
        }
    }

    @Test public void closeStillReleasesTheSourceWhileInterruptedWithoutReading() throws Exception {
        int[] closes = {0};
        InputStream source = new InputStream() {
            @Override public int read() { throw new AssertionError("Close must not scan the tail"); }
            @Override public void close() { closes[0]++; }
        };
        InputStream wrapped = wrap(source);
        try {
            Thread.currentThread().interrupt();
            wrapped.close();
            assertEquals(1, closes[0]);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private static InputStream wrap(InputStream source) throws Exception {
        Method method = ArchiveSupport.class.getDeclaredMethod("checkedTarPayload", InputStream.class);
        method.setAccessible(true);
        return (InputStream) method.invoke(null, source);
    }
}
