package com.readwide.manager.archive;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

import static org.junit.Assert.*;

/** Source-only boundary cases; these do not execute a native codec. */
public class Rar5PackedEndTest {
    @Test public void plainStreamRequiresImmediateEof() throws Exception {
        check(new ByteArrayInputStream(new byte[0]), false);
        for (int size : new int[]{1, 16, 64}) {
            ByteArrayInputStream input = new ByteArrayInputStream(new byte[size]);
            assertThrows(IOException.class, () -> check(input, false));
            assertEquals(size - 1, input.available());
        }
    }

    @Test public void aesAllowsEveryAlignmentLengthAndArbitraryPaddingValues() throws Exception {
        for (int size = 0; size <= 15; size++) {
            byte[] padding = new byte[size];
            java.util.Arrays.fill(padding, (byte) 0xa5);
            check(new ByteArrayInputStream(padding), true);
            check(new ByteArrayInputStream(padding) {
                @Override public synchronized int read(byte[] b, int off, int len) {
                    return super.read(b, off, Math.min(1, len));
                }
            }, true);
        }
    }

    @Test public void excessiveAesTailStopsAfterSixteenBytes() {
        for (int size : new int[]{16, 17, 65536}) {
            ByteArrayInputStream input = new ByteArrayInputStream(new byte[size]);
            assertThrows(IOException.class, () -> check(input, true));
            assertEquals(size - 16, input.available());
        }
    }

    @Test public void shortReadsDoNotResetThePaddingAllowance() {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[32]) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(3, len));
            }
        };
        assertThrows(IOException.class, () -> check(input, true));
        assertEquals(16, input.available());
    }

    @Test public void invalidReadCountsFailWithoutRetryingOrClosingTheOwner() {
        for (boolean encrypted : new boolean[]{false, true}) {
            for (int result : new int[]{0, -2, 17}) {
                int[] calls = {0};
                InputStream input = new InputStream() {
                    @Override public int read() { throw new AssertionError("Unexpected scalar read"); }
                    @Override public int read(byte[] b, int off, int len) {
                        calls[0]++;
                        return result;
                    }
                    @Override public void close() { throw new AssertionError("Caller owns stream"); }
                };
                assertThrows(IOException.class, () -> check(input, encrypted));
                assertEquals(1, calls[0]);
            }
        }
    }

    @Test public void checksCancellationBeforeReadAndAfterEofOrPaddingRead() throws Exception {
        InputStream unread = new InputStream() {
            @Override public int read() { throw new AssertionError("Read after cancellation"); }
        };
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> check(unread, true));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        for (int result : new int[]{-1, 1}) {
            InputStream input = new InputStream() {
                @Override public int read() { throw new AssertionError("Unexpected scalar read"); }
                @Override public int read(byte[] b, int off, int len) {
                    Thread.currentThread().interrupt();
                    return result;
                }
            };
            try {
                assertThrows(InterruptedIOException.class, () -> check(input, true));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test public void finalizationFailureAfterAllowedPaddingIsNotIgnored() {
        IOException failure = new IOException("Synthetic split CRC or cipher finalization failure");
        InputStream input = new InputStream() {
            boolean padded;
            @Override public int read() { throw new AssertionError("Unexpected scalar read"); }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (padded) throw failure;
                padded = true;
                return 15;
            }
        };
        assertSame(failure, assertThrows(IOException.class, () -> check(input, true)));
    }

    private static void check(InputStream input, boolean encrypted) throws IOException {
        Rar5CompressedArchiveExtractor.verifyPackedEnd(input, encrypted, null);
    }
}
