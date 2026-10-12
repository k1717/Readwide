package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises the existing Java callback directly, not a substitute native decoder. */
public class NativeVolumeCallbackTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File file(byte[] bytes) throws Exception {
        File file = temporary.newFile(); Files.write(file.toPath(), bytes); return file;
    }
    private Object open(File... files) throws Exception {
        Class<?> type = Class.forName(LibarchiveNativeBridge.class.getName() + "$VolumeInput");
        Constructor<?> constructor = type.getDeclaredConstructor(String[].class); constructor.setAccessible(true);
        String[] paths = Arrays.stream(files).map(File::getAbsolutePath).toArray(String[]::new);
        return constructor.newInstance((Object) paths);
    }
    private Object call(Object input, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = input.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(input, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }
    private ByteBuffer read(Object input) throws Exception { return (ByteBuffer) call(input, "read", new Class<?>[0]); }
    private long skip(Object input, long count) throws Exception { return (long) call(input, "skip", new Class<?>[]{long.class}, count); }
    private void close(Object input) throws Exception { call(input, "close", new Class<?>[0]); }

    @Test public void completeVolumesAndEmptyIntermediatePartRemainReadable() throws Exception {
        Object input = open(file(new byte[]{1,2}), file(new byte[0]), file(new byte[]{3,4}));
        try {
            ByteBuffer block = read(input); byte[] result = new byte[block.remaining()]; block.get(result);
            assertArrayEquals(new byte[]{1,2,3,4}, result);
            assertEquals(0, read(input).remaining());
        } finally { close(input); }
    }
    @Test public void shortenedVolumeCannotReturnNormalEof() throws Exception {
        File source = file(new byte[]{1,2,3}); Object input = open(source);
        try {
            Files.write(source.toPath(), new byte[]{1});
            IOException failure = assertThrows(IOException.class, () -> read(input));
            assertSame(failure, assertThrows(IOException.class, () -> read(input)));
            assertSame(failure, assertThrows(IOException.class, () -> skip(input, 1)));
        } finally { close(input); }
    }
    @Test public void changedLaterPartCannotBeSkipped() throws Exception {
        File later = file(new byte[]{3,4}); Object input = open(file(new byte[]{1,2}), later);
        try {
            Files.write(later.toPath(), new byte[]{3});
            assertThrows(IOException.class, () -> skip(input, 4));
        } finally { close(input); }
    }
    @Test public void sameLengthTimestampChangeIsRejected() throws Exception {
        File source = file(new byte[]{1,2}); Object input = open(source);
        try {
            Files.setLastModifiedTime(source.toPath(), java.nio.file.attribute.FileTime.fromMillis(source.lastModified() + 10000));
            assertThrows(IOException.class, () -> read(input));
        } finally { close(input); }
    }
    @Test public void cancellationRetiresEvenAfterFlagIsCleared() throws Exception {
        Object input = open(file(new byte[]{1})); IOException failure;
        try {
            try {
                Thread.currentThread().interrupt();
                failure = assertThrows(IOException.class, () -> read(input));
            } finally { Thread.interrupted(); }
            assertThrows(InterruptedIOException.class, () -> ArchiveCancellation.rethrowIfCancelled(failure, null));
            assertSame(failure, assertThrows(IOException.class, () -> read(input)));
        } finally { close(input); }
    }
    @Test public void closeCannotReopenFileThroughReadOrSkip() throws Exception {
        Object input = open(file(new byte[]{1})); close(input); close(input);
        assertThrows(IOException.class, () -> read(input));
        assertThrows(IOException.class, () -> skip(input, 1));
    }
    @Test public void skipIsBoundedAndNonpositiveRequestsDoNotMove() throws Exception {
        Object input = open(file(new byte[]{1,2,3}));
        try {
            assertEquals(0L, skip(input, -1));
            assertEquals(1L, skip(input, 1));
            assertEquals(2L, skip(input, Long.MAX_VALUE));
            assertEquals(0, read(input).remaining());
        } finally { close(input); }
    }
}
