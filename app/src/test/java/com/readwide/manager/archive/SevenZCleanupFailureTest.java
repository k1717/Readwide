package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Fault-injection checks for resource cleanup and output rollback, without JNI. */
public final class SevenZCleanupFailureTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void ioCloseStillClosesRemainingStreams() throws Exception {
        checkCleanup(new IOException("first"));
    }
    @Test public void runtimeCloseStillClosesRemainingStreams() throws Exception {
        checkCleanup(new IllegalStateException("first"));
    }
    @Test public void errorCloseStillClosesRemainingStreams() throws Exception {
        checkCleanup(new AssertionError("first"));
    }
    @Test public void repeatedSameCloseFailureDoesNotSelfSuppress() throws Exception {
        IOException failure = new IOException("shared");
        List<Integer> order = new ArrayList<>();
        Throwable caught = invokeCleanup(Arrays.asList(stream(0, null, order),
                stream(1, failure, order), stream(2, failure, order)));
        assertSame(failure, caught);
        assertEquals(Arrays.asList(2, 1, 0), order);
        assertEquals(0, failure.getSuppressed().length);
    }
    @Test public void ioCloseRollsBackVerifiedOutput() throws Exception {
        checkOutput(new IOException("close"));
    }
    @Test public void runtimeCloseRollsBackVerifiedOutput() throws Exception {
        checkOutput(new IllegalStateException("close"));
    }
    @Test public void errorCloseRollsBackVerifiedOutput() throws Exception {
        checkOutput(new AssertionError("close"));
    }
    @Test public void successfulCloseCommitsVerifiedOutput() throws Exception {
        checkOutput(null);
    }

    private void checkCleanup(Throwable primary) throws Exception {
        IOException secondary = new IOException("later close");
        List<Integer> order = new ArrayList<>();
        Throwable caught = invokeCleanup(Arrays.asList(stream(0, null, order),
                stream(1, secondary, order), stream(2, primary, order)));
        assertSame(primary, caught);
        assertEquals(Arrays.asList(2, 1, 0), order);
        assertArrayEquals(new Throwable[]{secondary}, primary.getSuppressed());
    }

    private void checkOutput(Throwable failure) throws Exception {
        File target = temporary.newFile("selected.bin");
        byte[] original = {8, 9, 10};
        byte[] replacement = {1, 2, 3};
        Files.write(target.toPath(), original);
        List<Integer> order = new ArrayList<>();
        List<InputStream> owned = Arrays.asList(stream(0, null, order), stream(1, failure, order));
        Class<?> type = Class.forName(SevenZBcj2ArchiveReader.class.getName() + "$FolderStream");
        Constructor<?> constructor = type.getDeclaredConstructor(InputStream.class, long.class,
                List.class, List.class, FileOperationProgress.class);
        constructor.setAccessible(true);
        Object folder = constructor.newInstance(new ByteArrayInputStream(replacement),
                (long) replacement.length, owned, Collections.emptyList(), null);
        Method write = type.getDeclaredMethod("writeEntry", long.class, long.class, File.class, boolean.class);
        Method close = type.getDeclaredMethod("close");
        write.setAccessible(true); close.setAccessible(true);
        write.invoke(folder, 0L, (long) replacement.length, target, true);
        assertArrayEquals(replacement, Files.readAllBytes(target.toPath()));
        Throwable caught = null;
        try { close.invoke(folder); }
        catch (InvocationTargetException e) { caught = e.getCause(); }
        assertSame(failure, caught);
        assertArrayEquals(failure == null ? replacement : original, Files.readAllBytes(target.toPath()));
        assertEquals(Arrays.asList(1, 0), order);
        close.invoke(folder);
        assertEquals("close must be idempotent", Arrays.asList(1, 0), order);
        assertArrayEquals(new String[]{"selected.bin"}, temporary.getRoot().list());
    }

    private static Throwable invokeCleanup(List<InputStream> streams) throws Exception {
        Method close = SevenZBcj2ArchiveReader.class.getDeclaredMethod("closeStreams", List.class);
        close.setAccessible(true);
        try { close.invoke(null, streams); return null; }
        catch (InvocationTargetException e) { return e.getCause(); }
    }

    private static InputStream stream(int id, Throwable failure, List<Integer> order) {
        return new InputStream() {
            @Override public int read() { return -1; }
            @Override public void close() throws IOException {
                order.add(id);
                if (failure instanceof IOException) throw (IOException) failure;
                if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                if (failure instanceof Error) throw (Error) failure;
            }
        };
    }
}
