package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Test;

public class ZipDeflateOwnershipTest {
    private static InputStream open(InputStream packed) throws Exception {
        Method method = ZipxAesArchiveReader.class.getDeclaredMethod("wrapCompressedStream",
                InputStream.class, int.class, long.class, boolean.class);
        method.setAccessible(true);
        try { return (InputStream) method.invoke(null, packed, 8, 0L, false); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }

    @Test public void zipDeflateUsesOwnedInflaterAndRetainsRawPaddingBehavior() throws Exception {
        for (int size : new int[]{0,1,4096,131073}) {
            byte[] raw = Round4Fixtures.data(size);
            try (InputStream stream = open(new ByteArrayInputStream(Round4Fixtures.deflate(raw)))) {
                assertTrue(stream instanceof OwnedDeflateInputStream);
                assertArrayEquals(raw, stream.readAllBytes());
                assertEquals(-1, stream.read());
            }
        }
    }

    @Test public void earlyCloseReleasesAndClosesSourceOnce() throws Exception {
        int[] closes = {0};
        InputStream source = new ByteArrayInputStream(Round4Fixtures.deflate(Round4Fixtures.data(10000))) {
            @Override public void close() { closes[0]++; }
        };
        InputStream stream = open(source);
        assertTrue(stream.read() >= 0);
        stream.close(); stream.close();
        assertEquals(1, closes[0]);
        assertThrows(IOException.class, stream::read);
    }

    @Test public void sourceCloseFailureStillReleasesDecoder() throws Exception {
        int[] closes = {0};
        IOException expected = new IOException("close failed");
        InputStream source = new ByteArrayInputStream(new byte[0]) {
            @Override public void close() throws IOException { closes[0]++; throw expected; }
        };
        InputStream stream = open(source);
        assertSame(expected, assertThrows(IOException.class, stream::close));
        stream.close();
        assertEquals(1, closes[0]);
        assertThrows(IOException.class, stream::read);
    }

    @Test public void cancellationRemainsTerminalAfterInterruptIsCleared() throws Exception {
        try (InputStream stream = open(new ByteArrayInputStream(Round4Fixtures.deflate(new byte[]{1})))) {
            IOException failure;
            try {
                Thread.currentThread().interrupt();
                failure = assertThrows(InterruptedIOException.class, stream::read);
            } finally { Thread.interrupted(); }
            assertSame(failure, assertThrows(IOException.class, stream::read));
        }
    }

    @Test public void invalidDeflateRemainsTerminal() throws Exception {
        try (InputStream stream = open(new ByteArrayInputStream(new byte[]{7}))) {
            IOException failure = assertThrows(IOException.class, stream::read);
            assertSame(failure, assertThrows(IOException.class, stream::read));
        }
    }
}
