package com.readwide.manager.archive;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic source failures: decoder state must not resume after partial consumption. */
public class ArchiveDecoderLifecycleTest {
    private static void raise(Throwable failure) throws IOException {
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        throw (Error) failure;
    }

    @Test public void swapUncheckedFailureInPartialWordIsTerminal() throws Exception {
        for (int width : new int[]{2, 4}) {
            for (Throwable failure : new Throwable[]{new IllegalStateException("read"), new AssertionError("read")}) {
                int[] reads = {0};
                InputStream source = new InputStream() {
                    @Override public int read() { throw new AssertionError("Use bulk reads"); }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        if (++reads[0] > 1) raise(failure);
                        bytes[offset] = 7;
                        return 1;
                    }
                };
                try (InputStream stream = new SevenZSwapInputStream(source, width)) {
                    assertSame(failure, assertThrows(Throwable.class, stream::read));
                    assertSame(failure, assertThrows(Throwable.class, stream::read));
                    assertSame(failure, assertThrows(Throwable.class, stream::available));
                    assertEquals(2, reads[0]);
                }
            }
        }
    }

    @Test public void swapCloseRetriesFailureButNotSuccessfulClose() throws Exception {
        for (Throwable failure : new Throwable[]{new IOException("close"), new SecurityException("close"), new AssertionError("close")}) {
            CloseProbe source = new CloseProbe(failure);
            InputStream stream = new SevenZSwapInputStream(source, 4);
            assertSame(failure, assertThrows(Throwable.class, stream::close));
            assertThrows(IOException.class, stream::read);
            stream.close(); stream.close();
            assertEquals(2, source.closes);
        }
    }

    @Test public void swapBadCallerRangeDoesNotRetireValidStream() throws Exception {
        try (InputStream stream = new SevenZSwapInputStream(new ByteArrayInputStream(new byte[]{1, 2}), 2)) {
            assertThrows(IndexOutOfBoundsException.class, () -> stream.read(new byte[2], 1, 2));
            assertEquals(2, stream.read());
            assertEquals(1, stream.read());
        }
    }

    @Test public void reduceUncheckedTableFailureIsTerminal() throws Exception {
        for (Throwable failure : new Throwable[]{new IllegalStateException("table"), new AssertionError("table")}) {
            int[] reads = {0};
            InputStream source = new InputStream() {
                @Override public int read() throws IOException {
                    if (++reads[0] > 1) raise(failure);
                    return 0;
                }
            };
            try (InputStream stream = new ZipReduceInputStream(source, 2, 1)) {
                assertSame(failure, assertThrows(Throwable.class, stream::read));
                assertSame(failure, assertThrows(Throwable.class, stream::read));
                assertEquals(2, reads[0]);
            }
        }
    }

    @Test public void reduceUncheckedPayloadFailureCannotReturnTheNextByte() throws Exception {
        IllegalStateException failure = new IllegalStateException("payload");
        InputStream source = new ByteArrayInputStream(ZipReduceInputStreamTest.literals(new byte[]{7, 8})) {
            boolean failed;
            @Override public synchronized int read() {
                if (pos == 193 && !failed) { failed = true; throw failure; }
                return super.read();
            }
        };
        try (InputStream stream = new ZipReduceInputStream(source, 2, 2)) {
            assertEquals(7, stream.read());
            assertSame(failure, assertThrows(IllegalStateException.class, stream::read));
            assertSame(failure, assertThrows(IllegalStateException.class, stream::read));
        }
    }

    @Test public void reduceCancellationDuringTableDoesNotReadTheWholeTable() throws Exception {
        int[] reads = {0};
        InputStream source = new InputStream() {
            @Override public int read() { reads[0]++; Thread.currentThread().interrupt(); return 0; }
        };
        try (InputStream stream = new ZipReduceInputStream(source, 2, 1)) {
            InterruptedIOException failure;
            try { failure = assertThrows(InterruptedIOException.class, stream::read); }
            finally { Thread.interrupted(); }
            assertSame(failure, assertThrows(InterruptedIOException.class, stream::read));
            assertEquals(1, reads[0]);
        } finally { Thread.interrupted(); }
    }

    @Test public void reduceRejectsInvalidScalarByteAndNullSource() throws Exception {
        assertThrows(NullPointerException.class, () -> new ZipReduceInputStream(null, 2, 1));
        try (InputStream stream = new ZipReduceInputStream(new InputStream() {
            @Override public int read() { return 256; }
        }, 2, 1)) {
            IOException failure = assertThrows(IOException.class, stream::read);
            assertTrue(failure.getMessage().contains("source byte"));
            assertSame(failure, assertThrows(IOException.class, stream::read));
        }
    }

    @Test public void reduceCloseRetriesFailureButNotSuccessfulClose() throws Exception {
        for (Throwable failure : new Throwable[]{new IOException("close"), new SecurityException("close"), new AssertionError("close")}) {
            CloseProbe source = new CloseProbe(failure);
            InputStream stream = new ZipReduceInputStream(source, 2, 0);
            assertSame(failure, assertThrows(Throwable.class, stream::close));
            assertThrows(IOException.class, stream::read);
            stream.close(); stream.close();
            assertEquals(2, source.closes);
        }
    }

    private static final class CloseProbe extends ByteArrayInputStream {
        final Throwable failure;
        int closes;
        CloseProbe(Throwable failure) { super(new byte[0]); this.failure = failure; }
        @Override public void close() throws IOException { if (++closes == 1) raise(failure); }
    }
}
