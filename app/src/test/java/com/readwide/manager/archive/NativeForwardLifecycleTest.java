package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Constructor;
import org.junit.Test;

/** Java bridge state only; the delegate is a fake and no native codec is executed. */
public class NativeForwardLifecycleTest {
    private static final class Delegate implements ArchiveSupport.ForwardArchiveReader {
        int nextCalls, readCalls, drainCalls, closeCalls;
        Throwable nextFailure, readFailure, drainFailure, closeFailure;
        boolean interruptNext, interruptRead, interruptDrain, end, drained = true;
        int count = 1;
        long drainLimit;
        @Override public ArchiveSupport.ForwardEntry nextEntry() throws IOException {
            nextCalls++;
            rethrow(nextFailure);
            if (interruptNext) Thread.currentThread().interrupt();
            return end ? null : new ArchiveSupport.ForwardEntry("page", false, true);
        }
        @Override public int read(byte[] buffer) throws IOException {
            readCalls++;
            rethrow(readFailure);
            if (interruptRead) Thread.currentThread().interrupt();
            if (count > 0) buffer[0] = 1;
            return count;
        }
        @Override public boolean drainCurrentEntry(long limit) throws IOException {
            drainCalls++; drainLimit = limit;
            rethrow(drainFailure);
            if (interruptDrain) Thread.currentThread().interrupt();
            return drained;
        }
        @Override public void close() throws IOException {
            closeCalls++;
            rethrow(closeFailure);
        }
    }

    private static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }

    @Test public void postHeaderCancellationIncludesEndOfArchive() throws Exception {
        for (boolean end : new boolean[]{false, true}) {
            Delegate delegate = new Delegate(); delegate.interruptNext = true; delegate.end = end;
            LibarchiveNativeBridge.ForwardStream stream = open(delegate);
            try {
                assertThrows(InterruptedIOException.class, stream::nextEntry);
                assertEquals(1, delegate.closeCalls);
            } finally { Thread.interrupted(); stream.close(); }
            assertThrows(InterruptedIOException.class, stream::nextEntry);
            assertEquals(1, delegate.nextCalls);
        }
    }

    @Test public void postReadCancellationIncludesEntryEof() throws Exception {
        for (int count : new int[]{1, -1}) {
            Delegate delegate = new Delegate(); delegate.interruptRead = true; delegate.count = count;
            LibarchiveNativeBridge.ForwardStream stream = open(delegate); stream.nextEntry();
            try { assertThrows(InterruptedIOException.class, () -> stream.read(new byte[1])); }
            finally { Thread.interrupted(); stream.close(); }
            assertThrows(InterruptedIOException.class, () -> stream.read(new byte[1]));
            assertEquals(1, delegate.readCalls); assertEquals(1, delegate.closeCalls);
        }
    }

    @Test public void invalidReadCountsRetireTheDelegate() throws Exception {
        for (int count : new int[]{-2, 0, 2}) {
            Delegate delegate = new Delegate(); delegate.count = count;
            LibarchiveNativeBridge.ForwardStream stream = open(delegate); stream.nextEntry();
            assertThrows(IOException.class, () -> stream.read(new byte[1]));
            assertThrows(IOException.class, stream::nextEntry);
            assertEquals(1, delegate.readCalls); assertEquals(1, delegate.closeCalls);
        }
    }

    @Test public void postDrainCancellationIncludesBothDelegateResults() throws Exception {
        for (boolean drained : new boolean[]{false, true}) {
            Delegate delegate = new Delegate(); delegate.interruptDrain = true; delegate.drained = drained;
            LibarchiveNativeBridge.ForwardStream stream = open(delegate); stream.nextEntry();
            try { assertThrows(InterruptedIOException.class, () -> stream.drainCurrentEntry(-1)); }
            finally { Thread.interrupted(); stream.close(); }
            assertThrows(InterruptedIOException.class, stream::nextEntry);
            assertEquals(1, delegate.drainCalls); assertEquals(1, delegate.closeCalls);
        }
    }

    @Test public void uncheckedFailuresRemainPrimaryAndRetireAllOperations() throws Exception {
        for (int operation = 0; operation < 3; operation++) {
            for (Throwable failure : new Throwable[]{new IllegalStateException("decoder"), new AssertionError("decoder")}) {
                Delegate delegate = new Delegate();
                LibarchiveNativeBridge.ForwardStream stream = open(delegate); stream.nextEntry();
                if (operation == 0) delegate.nextFailure = failure;
                if (operation == 1) delegate.readFailure = failure;
                if (operation == 2) delegate.drainFailure = failure;
                IOException cleanup = new IOException("close"); delegate.closeFailure = cleanup;
                final int action = operation;
                assertSame(failure, assertThrows(Throwable.class, () -> {
                    if (action == 0) stream.nextEntry();
                    else if (action == 1) stream.read(new byte[1]);
                    else stream.drainCurrentEntry(-1);
                }));
                assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
                delegate.closeFailure = null; stream.close(); stream.close();
                assertSame(failure, assertThrows(IOException.class, stream::nextEntry).getCause());
                assertEquals(2, delegate.closeCalls);
            }
        }
    }

    @Test public void ownerCloseDoesNotMaskStreamCloseAndBothCanRetry() throws Exception {
        for (Throwable primary : new Throwable[]{new IOException("stream"), new IllegalStateException("stream"), new AssertionError("stream")}) {
            Delegate delegate = new Delegate(); delegate.closeFailure = primary;
            IOException secondary = new IOException("owner"); int[] ownerCloses = {0};
            LibarchiveForwardReader reader = new LibarchiveForwardReader(open(delegate), () -> {
                if (++ownerCloses[0] == 1) throw secondary;
            });
            assertSame(primary, assertThrows(Throwable.class, reader::close));
            assertArrayEquals(new Throwable[]{secondary}, primary.getSuppressed());
            delegate.closeFailure = null; reader.close(); reader.close();
            assertEquals(2, delegate.closeCalls); assertEquals(2, ownerCloses[0]);
        }
    }

    @Test public void successfulOwnerCloseIsNotRepeatedWhileStreamCloseRetries() throws Exception {
        Delegate delegate = new Delegate(); delegate.closeFailure = new IOException("stream");
        int[] ownerCloses = {0};
        LibarchiveForwardReader reader = new LibarchiveForwardReader(open(delegate), () -> ownerCloses[0]++);
        assertThrows(IOException.class, reader::close);
        delegate.closeFailure = null; reader.close(); reader.close();
        assertEquals(2, delegate.closeCalls); assertEquals(1, ownerCloses[0]);
    }

    private static LibarchiveNativeBridge.ForwardStream open(Delegate delegate) throws Exception {
        Constructor<LibarchiveNativeBridge.ForwardStream> constructor =
                LibarchiveNativeBridge.ForwardStream.class.getDeclaredConstructor(ArchiveSupport.ForwardArchiveReader.class);
        constructor.setAccessible(true);
        return constructor.newInstance(delegate);
    }

    @Test public void zeroLengthReadDoesNotConsumeDelegate() throws Exception {
        Delegate delegate = new Delegate();
        LibarchiveNativeBridge.ForwardStream stream = open(delegate);
        try {
            assertEquals(0, stream.read(new byte[0]));
            stream.nextEntry();
            assertEquals(0, stream.read(new byte[0]));
            assertEquals(0, delegate.readCalls);
            assertEquals(1, stream.read(new byte[1]));
        } finally { stream.close(); }
    }

    @Test public void cancelledDrainStopsBeforeCallingDelegateAndStaysFailed() throws Exception {
        Delegate delegate = new Delegate();
        LibarchiveNativeBridge.ForwardStream stream = open(delegate);
        stream.nextEntry();
        IOException failure;
        try {
            Thread.currentThread().interrupt();
            failure = assertThrows(InterruptedIOException.class, () -> stream.drainCurrentEntry(Long.MAX_VALUE));
        } finally { Thread.interrupted(); }
        assertEquals(0, delegate.drainCalls);
        assertSame(failure, assertThrows(IOException.class, stream::nextEntry));
        assertSame(failure, assertThrows(IOException.class, () -> stream.read(new byte[1])));
        assertEquals(1, delegate.closeCalls);
    }

    @Test public void failedReadCannotResumeOrAdvance() throws Exception {
        Delegate delegate = new Delegate();
        delegate.readFailure = new IOException("read");
        LibarchiveNativeBridge.ForwardStream stream = open(delegate);
        stream.nextEntry();
        assertSame(delegate.readFailure, assertThrows(IOException.class, () -> stream.read(new byte[1])));
        assertSame(delegate.readFailure, assertThrows(IOException.class, stream::nextEntry));
        assertSame(delegate.readFailure, assertThrows(IOException.class, () -> stream.drainCurrentEntry(1)));
        assertEquals(1, delegate.nextCalls);
        assertEquals(1, delegate.readCalls);
        assertEquals(1, delegate.closeCalls);
    }

    @Test public void failedHeaderIsTerminal() throws Exception {
        Delegate delegate = new Delegate();
        delegate.nextFailure = new IOException("header");
        LibarchiveNativeBridge.ForwardStream stream = open(delegate);
        assertSame(delegate.nextFailure, assertThrows(IOException.class, stream::nextEntry));
        assertSame(delegate.nextFailure, assertThrows(IOException.class, stream::nextEntry));
        assertEquals(1, delegate.nextCalls);
        assertEquals(1, delegate.closeCalls);
    }

    @Test public void failedDrainPreservesCleanupFailureAndCanRetryClose() throws Exception {
        Delegate delegate = new Delegate();
        delegate.drainFailure = new IOException("drain");
        delegate.closeFailure = new IOException("close");
        LibarchiveNativeBridge.ForwardStream stream = open(delegate);
        stream.nextEntry();
        IOException failure = assertThrows(IOException.class, () -> stream.drainCurrentEntry(10));
        assertSame(delegate.drainFailure, failure);
        assertArrayEquals(new Throwable[]{delegate.closeFailure}, failure.getSuppressed());
        delegate.closeFailure = null;
        stream.close(); stream.close();
        assertEquals(2, delegate.closeCalls);
        assertSame(failure, assertThrows(IOException.class, stream::nextEntry));
    }

    @Test public void successfulDrainRetainsUnlimitedConvention() throws Exception {
        Delegate delegate = new Delegate();
        LibarchiveNativeBridge.ForwardStream stream = open(delegate);
        try {
            stream.nextEntry();
            assertTrue(stream.drainCurrentEntry(-1));
            assertEquals(Long.MAX_VALUE, delegate.drainLimit);
            assertEquals(-1, stream.read(new byte[1]));
        } finally { stream.close(); }
    }
}
