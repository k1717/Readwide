package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Inflater;
import org.junit.Test;

public class DeflateConstructionOwnershipTest {
    @Test public void constructorFailureReleasesInflaterAndKeepsSourceWithCaller() throws Exception {
        for (Throwable expected : new Throwable[] {
                new IllegalStateException("constructor"),
                new OutOfMemoryError("injected allocation failure"),
                new StackOverflowError("injected stack failure")}) {
            int[] closes = {0};
            Inflater[] allocated = {null};
            InputStream source = new ByteArrayInputStream(new byte[0]) {
                @Override public void close() { closes[0]++; }
            };
            try {
                Throwable actual = assertThrows(Throwable.class, () ->
                        OwnedDeflateInputStream.create(source, null, (input, inflater, progress) -> {
                            assertSame(source, input);
                            assertNull(progress);
                            assertEquals(0, inflater.getBytesRead());
                            allocated[0] = inflater;
                            if (expected instanceof Error) throw (Error) expected;
                            throw (RuntimeException) expected;
                        }));
                assertSame(expected, actual);
                assertNotNull(allocated[0]);
                assertThrows(NullPointerException.class, allocated[0]::getBytesRead);
                assertEquals(0, closes[0]);
            } finally { source.close(); }
            assertEquals(1, closes[0]);
        }
    }

    @Test public void failedConstructionLeavesSameInputUsableForRetry() throws Exception {
        byte[] plain = Round4Fixtures.data(8193);
        int[] closes = {0};
        InputStream source = new ByteArrayInputStream(Round4Fixtures.deflate(plain)) {
            @Override public void close() { closes[0]++; }
        };
        OutOfMemoryError expected = new OutOfMemoryError("injected buffer allocation failure");
        assertSame(expected, assertThrows(OutOfMemoryError.class, () ->
                OwnedDeflateInputStream.create(source, null, (input, inflater, progress) -> {
                    throw expected;
                })));
        assertEquals(0, closes[0]);
        try (InputStream retry = OwnedDeflateInputStream.open(source)) {
            assertArrayEquals(plain, retry.readAllBytes());
        }
        assertEquals(1, closes[0]);
    }

    @Test public void callerCloseFailureDoesNotReplaceConstructionFailure() throws Exception {
        int[] closes = {0};
        Inflater[] allocated = {null};
        OutOfMemoryError expected = new OutOfMemoryError("injected construction failure");
        IOException closeFailure = new IOException("source close");
        Throwable actual = assertThrows(OutOfMemoryError.class, () -> {
            try (InputStream source = new ByteArrayInputStream(new byte[0]) {
                @Override public void close() throws IOException { closes[0]++; throw closeFailure; }
            }) {
                OwnedDeflateInputStream.create(source, null, (input, inflater, progress) -> {
                    allocated[0] = inflater;
                    throw expected;
                });
            }
        });
        assertSame(expected, actual);
        assertArrayEquals(new Throwable[]{closeFailure}, actual.getSuppressed());
        assertEquals(1, closes[0]);
        assertThrows(NullPointerException.class, allocated[0]::getBytesRead);
    }

    @Test public void nullFactoryResultCannotLeaveInflaterAllocated() {
        Inflater[] allocated = {null};
        assertThrows(NullPointerException.class, () -> OwnedDeflateInputStream.create(
                new ByteArrayInputStream(new byte[0]), null, (input, inflater, progress) -> {
                    allocated[0] = inflater;
                    return null;
                }));
        assertNotNull(allocated[0]);
        assertThrows(NullPointerException.class, allocated[0]::getBytesRead);
    }

    @Test public void nullInputIsRejectedBeforeFactoryRuns() {
        int[] calls = {0};
        assertThrows(NullPointerException.class, () -> OwnedDeflateInputStream.create(
                null, null, (input, inflater, progress) -> {
                    calls[0]++;
                    return null;
                }));
        assertEquals(0, calls[0]);
        assertThrows(NullPointerException.class, () -> OwnedDeflateInputStream.open(null));
    }

    @Test public void nullFactoryDoesNotCloseCallerInput() throws Exception {
        int[] closes = {0};
        try (InputStream source = new ByteArrayInputStream(new byte[]{42}) {
            @Override public void close() { closes[0]++; }
        }) {
            assertThrows(NullPointerException.class, () -> OwnedDeflateInputStream.create(source, null, null));
            assertEquals(0, closes[0]);
            assertEquals(42, source.read());
        }
        assertEquals(1, closes[0]);
    }
}
