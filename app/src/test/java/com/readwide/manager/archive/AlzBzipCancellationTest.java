package com.readwide.manager.archive;

import static org.junit.Assert.*;

import com.readwide.manager.util.FileOperationProgress;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.Test;

/** Token-only cancellation; existing synthetic fixtures are not producer validation. */
public class AlzBzipCancellationTest {
    @Test public void cancelledConstructorDoesNotReadOrTakeSourceOwnership() {
        FileOperationProgress progress = token();
        progress.cancel();
        int[] reads = {0}, closes = {0};
        InputStream source = new InputStream() {
            @Override public int read() { reads[0]++; return -1; }
            @Override public void close() { closes[0]++; }
        };
        assertThrows(InterruptedIOException.class, () -> new AlzBzip2InputStream(source, progress));
        assertEquals(0, reads[0]);
        assertEquals(0, closes[0]); // The owning factory closes a failed constructor's input.
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void bufferedOutputCancellationIsStickyForScalarAndBulkReads() throws Exception {
        for (boolean bulk : new boolean[]{false, true}) {
            FileOperationProgress progress = token();
            int[] closes = {0};
            InputStream source = new ByteArrayInputStream(packed()) {
                @Override public void close() { closes[0]++; }
            };
            AlzBzip2InputStream decoder = new AlzBzip2InputStream(source, progress);
            try {
                assertEquals(plain()[0] & 255, decoder.read());
                progress.cancel();
                byte[] destination = new byte[32];
                Arrays.fill(destination, (byte) 91);
                InterruptedIOException failure = assertThrows(InterruptedIOException.class,
                        () -> { if (bulk) decoder.read(destination, 2, 28); else decoder.read(); });
                byte[] unchanged = new byte[32]; Arrays.fill(unchanged, (byte) 91);
                assertArrayEquals(unchanged, destination);
                assertSame(failure, assertThrows(IOException.class, decoder::read));
                assertEquals(0, progress.snapshot().doneBytes);
                assertFalse(Thread.currentThread().isInterrupted());
            } finally { decoder.close(); decoder.close(); }
            assertEquals(1, closes[0]);
        }
    }

    @Test public void successfulCompressedRefillStillObservesCancellation() {
        FileOperationProgress progress = token();
        int[] reads = {0};
        InputStream source = new ByteArrayInputStream(packed()) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length);
                reads[0]++;
                progress.cancel();
                return count;
            }
        };
        assertThrows(InterruptedIOException.class, () -> new AlzBzip2InputStream(source, progress));
        assertEquals(1, reads[0]);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void inverseBwtPreparationHasItsOwnCancellationCheckpoint() throws Exception {
        FileOperationProgress progress = token();
        try (AlzBzip2InputStream decoder = new AlzBzip2InputStream(new ByteArrayInputStream(packed()), progress)) {
            progress.cancel();
            // Bypass read()'s outer checkpoint to cover the table preparation loop itself.
            Method setup = AlzBzip2InputStream.class.getDeclaredMethod("setupBlock");
            setup.setAccessible(true);
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> setup.invoke(decoder));
            assertTrue(failure.getCause() instanceof InterruptedIOException);
        }
    }

    @Test public void activeTokenKeepsSingleAndMultipleBlockOutputUnchanged() throws Exception {
        byte[] multi = new byte[260000];
        for (int i = 0; i < multi.length; i++) multi[i] = (byte) (33 + i % 87);
        byte[][] inputs = {packed(), Base64.getDecoder().decode(Round6Fixtures.MULTI_BZIP)};
        byte[][] expected = {plain(), multi};
        for (int fixture = 0; fixture < inputs.length; fixture++) {
            for (int chunk : new int[]{1, 7, 65536}) {
                FileOperationProgress progress = token();
                InputStream source = new ByteArrayInputStream(inputs[fixture]) {
                    @Override public synchronized int read(byte[] bytes, int offset, int length) {
                        return super.read(bytes, offset, Math.min(length, chunk));
                    }
                };
                try (InputStream decoder = new AlzBzip2InputStream(source, progress)) {
                    assertArrayEquals(expected[fixture], decoder.readAllBytes());
                    assertEquals(-1, decoder.read());
                    assertEquals(0, progress.snapshot().doneBytes);
                    assertFalse(progress.isCancelled());
                }
            }
        }
    }

    private static FileOperationProgress token() { return new FileOperationProgress("test", null); }
    private static byte[] packed() { return Base64.getDecoder().decode(Round4Fixtures.ALZ_BZ_SINGLE_BLOCK_B64); }
    private static byte[] plain() {
        return "ALZ bzip2 single block payload. ".repeat(40).getBytes(StandardCharsets.US_ASCII);
    }
}
