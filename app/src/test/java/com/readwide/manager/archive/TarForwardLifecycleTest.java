package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Constructor;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.Test;

/** Fault-injected lifecycle seams; not producer/native interoperability tests. */
public class TarForwardLifecycleTest {
    @Test public void postReadCancellationRetiresDataAndEof() throws Exception {
        for (int count : new int[]{1, -1}) {
            ProbeTar tar = new ProbeTar(); tar.count = count; tar.interrupt = true;
            ProbeInput source = new ProbeInput();
            ArchiveSupport.ForwardArchiveReader reader = reader(tar, source);
            try {
                IOException failure = assertThrows(InterruptedIOException.class, () -> reader.read(new byte[8]));
                assertTrue(Thread.currentThread().isInterrupted());
                Thread.interrupted();
                assertSame(failure, assertThrows(IOException.class, reader::nextEntry));
                assertEquals(1, tar.closes); assertEquals(1, source.closes);
            } finally { Thread.interrupted(); reader.close(); }
        }
    }

    @Test public void invalidReadProgressIsStickyAndRetiresOwnership() throws Exception {
        for (int count : new int[]{-2, 0, 9}) {
            ProbeTar tar = new ProbeTar(); tar.count = count;
            ProbeInput source = new ProbeInput();
            try (ArchiveSupport.ForwardArchiveReader reader = reader(tar, source)) {
                IOException failure = assertThrows(IOException.class, () -> reader.read(new byte[8]));
                assertSame(failure, assertThrows(IOException.class, () -> reader.read(new byte[8])));
                assertEquals(1, tar.closes); assertEquals(1, source.closes);
            }
        }
    }

    @Test public void failedTarCleanupKeepsPrimaryAndRetriesOnlyFailedComponents() throws Exception {
        for (Throwable failure : new Throwable[]{new IOException("close"),
                new IllegalStateException("close"), new AssertionError("close")}) {
            ProbeTar tar = new ProbeTar(); tar.closeFailure = failure;
            ProbeInput source = new ProbeInput();
            ArchiveSupport.ForwardArchiveReader reader = reader(tar, source);
            assertSame(failure, assertThrows(Throwable.class, reader::close));
            assertEquals(1, source.closes);
            assertThrows(IOException.class, () -> reader.read(new byte[8]));
            reader.close(); reader.close();
            assertEquals(2, tar.closes); assertEquals(1, source.closes);
        }
    }

    @Test public void secondarySourceCleanupFailureIsVisibleAndRetryable() throws Exception {
        ProbeTar tar = new ProbeTar();
        ProbeInput source = new ProbeInput(); source.closeFailure = new IOException("source close");
        ArchiveSupport.ForwardArchiveReader reader = reader(tar, source);
        assertSame(source.closeFailure, assertThrows(IOException.class, reader::close));
        reader.close(); reader.close();
        assertEquals(1, tar.closes); assertEquals(2, source.closes);
    }

    @Test public void readErrorRemainsPrimaryAndSuppressesCleanupFailure() throws Exception {
        ProbeTar tar = new ProbeTar();
        tar.readFailure = new AssertionError("decode");
        tar.closeFailure = new IOException("decoder close");
        ProbeInput source = new ProbeInput(); source.closeFailure = new IOException("file close");
        ArchiveSupport.ForwardArchiveReader reader = reader(tar, source);
        assertSame(tar.readFailure, assertThrows(AssertionError.class, () -> reader.read(new byte[8])));
        assertSame(tar.closeFailure, tar.readFailure.getSuppressed()[0]);
        assertSame(source.closeFailure, tar.closeFailure.getSuppressed()[0]);
        assertSame(tar.readFailure, assertThrows(IOException.class, reader::nextEntry).getCause());
        reader.close();
        assertEquals(2, tar.closes); assertEquals(2, source.closes);
    }

    private static ArchiveSupport.ForwardArchiveReader reader(ProbeTar tar, InputStream source) throws Exception {
        Class<?> preparedType = Class.forName(ArchiveSupport.class.getName() + "$PreparedArchive");
        Constructor<?> preparedConstructor = preparedType.getDeclaredConstructor(
                File.class, ArchiveSupport.Type.class, File.class);
        preparedConstructor.setAccessible(true);
        Object prepared = preparedConstructor.newInstance(new File("unused.tar"), ArchiveSupport.Type.TAR, null);
        Class<?> readerType = Class.forName(ArchiveSupport.class.getName() + "$TarForwardReader");
        Constructor<?> constructor = readerType.getDeclaredConstructor(
                preparedType, TarArchiveInputStream.class, InputStream.class, InputStream.class);
        constructor.setAccessible(true);
        return (ArchiveSupport.ForwardArchiveReader) constructor.newInstance(prepared, tar, source, source);
    }

    private static final class ProbeTar extends TarArchiveInputStream {
        int count = -1, closes;
        boolean interrupt;
        Error readFailure;
        Throwable closeFailure;
        ProbeTar() { super(new ByteArrayInputStream(new byte[0])); }
        @Override public int read(byte[] bytes, int offset, int length) {
            if (readFailure != null) throw readFailure;
            if (interrupt) Thread.currentThread().interrupt();
            return count;
        }
        @Override public void close() throws IOException {
            if (++closes == 1) throwFailure(closeFailure);
        }
    }

    private static final class ProbeInput extends ByteArrayInputStream {
        int closes; IOException closeFailure;
        ProbeInput() { super(new byte[0]); }
        @Override public void close() throws IOException {
            if (++closes == 1 && closeFailure != null) throw closeFailure;
        }
    }

    private static void throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }
}
