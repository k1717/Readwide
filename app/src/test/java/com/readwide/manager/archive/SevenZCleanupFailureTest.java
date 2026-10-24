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

    @Test public void packedAdoptionTransfersOwnershipWithoutReadingAndPreservesCrc() throws Exception {
        byte[] data = {1, 2, 3, 4};
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(data);
        int[] reads = {0}, closes = {0};
        InputStream raw = new ByteArrayInputStream(data) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                reads[0]++;
                return super.read(bytes, offset, length);
            }
            @Override public void close() { closes[0]++; }
        };
        List<InputStream> owned = new ArrayList<>();
        InputStream packed = adoptPacked(raw, data.length, crc.getValue(), owned);
        assertEquals(0, reads[0]); assertEquals(0, closes[0]);
        assertEquals(Collections.singletonList(packed), owned);
        byte[] actual = new byte[data.length];
        assertEquals(data.length, packed.read(actual));
        assertArrayEquals(data, actual); assertEquals(-1, packed.read());
        assertNull(invokeCleanup(owned)); packed.close();
        assertEquals(1, closes[0]);
    }

    @Test public void failedPackedAdoptionClosesInputAndPreservesOriginalFailure() throws Exception {
        for (Throwable failure : new Throwable[]{new IllegalStateException("adoption"),
                new OutOfMemoryError("simulated adoption"), new AssertionError("adoption")}) {
            IOException cleanup = new IOException("close");
            List<Integer> order = new ArrayList<>();
            List<InputStream> owned = rejectingOwner(failure);
            InputStream previous = stream(0, null, order);
            owned.add(0, previous);
            InvocationTargetException caught = assertThrows(InvocationTargetException.class,
                    () -> adoptPacked(stream(1, cleanup, order), 0, -1, owned));
            assertSame(failure, caught.getCause());
            assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
            assertEquals(Collections.singletonList(1), order);
            assertEquals(Collections.singletonList(previous), owned);
            assertNull(invokeCleanup(owned));
            assertEquals(Arrays.asList(1, 0), order);
        }
    }

    @Test public void packedAdoptionDoesNotSelfSuppressCleanupFailure() throws Exception {
        AssertionError failure = new AssertionError("shared");
        List<Integer> order = new ArrayList<>();
        InvocationTargetException caught = assertThrows(InvocationTargetException.class,
                () -> adoptPacked(stream(0, failure, order), 0, -1, rejectingOwner(failure)));
        assertSame(failure, caught.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(Collections.singletonList(0), order);
    }

    @Test public void failedPackedAdoptionClosesOnlyTheSplitViewNotItsOwner() throws Exception {
        File file = temporary.newFile("packed.bin");
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        try (SplitVolumeInput source = new SplitVolumeInput(Collections.singletonList(
                new SplitVolumeInput.Segment(file, 0, 3)))) {
            InputStream view = source.boundedStream(0, 2);
            IllegalStateException failure = new IllegalStateException("adoption");
            InvocationTargetException caught = assertThrows(InvocationTargetException.class,
                    () -> adoptPacked(view, 2, -1, rejectingOwner(failure)));
            assertSame(failure, caught.getCause());
            assertThrows(IOException.class, view::read);
            try (InputStream sibling = source.boundedStream(2, 1)) {
                assertEquals(3, sibling.read()); assertEquals(-1, sibling.read());
            }
        }
    }

    private static List<InputStream> rejectingOwner(Throwable failure) {
        return new ArrayList<InputStream>() {
            @Override public boolean add(InputStream input) {
                if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                throw (Error) failure;
            }
        };
    }

    @Test public void failedOutputAdoptionRestoresOriginalBeforeFolderCleanup() throws Exception {
        for (Throwable failure : new Throwable[]{new IllegalStateException("adoption"),
                new OutOfMemoryError("simulated adoption")}) {
            File directory = temporary.newFolder();
            File target = new File(directory, "original.bin");
            byte[] original = {8, 9, 10}; Files.write(target.toPath(), original);
            assertOutputAdoptionFailure(target, failure);
            assertArrayEquals(original, Files.readAllBytes(target.toPath()));
            assertArrayEquals(new String[]{"original.bin"}, directory.list());
        }
    }

    @Test public void failedOutputAdoptionDoesNotLeaveANewTarget() throws Exception {
        File directory = temporary.newFolder();
        File target = new File(directory, "new.bin");
        assertOutputAdoptionFailure(target, new OutOfMemoryError("simulated adoption"));
        assertFalse(target.exists()); assertEquals(0, directory.list().length);
    }

    private static void assertOutputAdoptionFailure(File target, Throwable failure) throws Exception {
        List<RarOutputFileGuard> pending = new ArrayList<RarOutputFileGuard>() {
            @Override public boolean add(RarOutputFileGuard guard) {
                if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                throw (Error) failure;
            }
        };
        Method method = SevenZBcj2ArchiveReader.class.getDeclaredMethod("adoptOutputGuard", File.class, List.class);
        method.setAccessible(true);
        InvocationTargetException caught = assertThrows(InvocationTargetException.class,
                () -> method.invoke(null, target, pending));
        assertSame(failure, caught.getCause()); assertTrue(pending.isEmpty());
        assertEquals(0, failure.getSuppressed().length);
    }

    private static InputStream adoptPacked(InputStream input, long size, long crc,
                                           List<InputStream> owned) throws Exception {
        Method method = SevenZBcj2ArchiveReader.class.getDeclaredMethod("adoptPackedStream",
                InputStream.class, long.class, long.class, List.class);
        method.setAccessible(true);
        return (InputStream) method.invoke(null, input, size, crc, owned);
    }

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

    @Test public void cancelledZeroSizeFolderCannotCommitAnEmptyReplacement() throws Exception {
        checkDrainCancellation(0);
    }

    @Test public void tokenCancellationStopsPackedPaddingBeforeAnotherRead() throws Exception {
        checkDrainCancellation(1);
    }

    @Test public void tokenCancellationAtPackedEofCannotCommitPendingOutput() throws Exception {
        checkDrainCancellation(2);
    }

    @Test public void cancellationAfterFinalSourceCheckRollsBackTheVerifiedFolder() throws Exception {
        checkFinalSourceCancellation(true);
    }

    @Test public void activeTokenAfterFinalSourceCheckStillCommitsTheVerifiedFolder() throws Exception {
        checkFinalSourceCancellation(false);
    }

    @Test(timeout = 5000) public void pauseAfterFinalSourceCheckDoesNotWaitBeforeCommit() throws Exception {
        File archive = Round4Fixtures.sevenZ(temporary.newFolder(), new byte[]{1,2,3,4},
                4, false, false, false, true);
        File directory = temporary.newFolder();
        File first = new File(directory, "1.bin"), second = new File(directory, "2.bin");
        FileOperationProgress progress = new FileOperationProgress("7z", null);
        boolean[] checked = {false};
        File observed = new File(archive.getPath()) {
            @Override public boolean canRead() {
                boolean readable = super.canRead();
                if (first.length() == 2 && second.length() == 2) {
                    checked[0] = true;
                    progress.setPaused(true);
                }
                return readable;
            }
        };
        try {
            assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(observed, directory, null, progress, null));
            assertTrue(checked[0]);
            assertTrue(progress.isPaused());
            assertArrayEquals(new byte[]{1,2}, Files.readAllBytes(first.toPath()));
            assertArrayEquals(new byte[]{3,4}, Files.readAllBytes(second.toPath()));
        } finally { progress.setPaused(false); }
    }

    private void checkFinalSourceCancellation(boolean cancel) throws Exception {
        File archive = Round4Fixtures.sevenZ(temporary.newFolder(), new byte[]{1,2,3,4},
                4, false, false, false, true);
        File directory = temporary.newFolder();
        File first = new File(directory, "1.bin"), second = new File(directory, "2.bin");
        byte[] original = {8,9,10}; Files.write(first.toPath(), original);
        FileOperationProgress progress = new FileOperationProgress("7z", null);
        int[] finalChecks = {0};
        File observedArchive = new File(archive.getPath()) {
            @Override public boolean canRead() {
                boolean readable = super.canRead();
                // SingleSourceStamp keeps this File; volume reads use a canonical File.
                // Only the final source check sees both fully written two-byte slices.
                if (first.length() == 2 && second.length() == 2) {
                    finalChecks[0]++;
                    if (cancel) progress.cancel();
                }
                return readable;
            }
        };
        assertEquals(!cancel, SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(
                observedArchive, directory, null, progress, null));
        assertEquals(1, finalChecks[0]);
        assertEquals(cancel, progress.isCancelled());
        assertFalse(Thread.currentThread().isInterrupted());
        if (cancel) {
            assertArrayEquals(original, Files.readAllBytes(first.toPath()));
            assertFalse(second.exists());
            assertArrayEquals(new String[]{"1.bin"}, directory.list());
        } else {
            assertArrayEquals(new byte[]{1,2}, Files.readAllBytes(first.toPath()));
            assertArrayEquals(new byte[]{3,4}, Files.readAllBytes(second.toPath()));
            assertEquals(2, directory.list().length);
        }
    }

    private void checkDrainCancellation(int mode) throws Exception {
        File target = temporary.newFile("cancelled.bin");
        byte[] original = {8,9,10};
        byte[] replacement = mode == 0 ? new byte[0] : new byte[]{1,2,3};
        Files.write(target.toPath(),original);
        FileOperationProgress progress = new FileOperationProgress("7z",null);
        int[] reads = {0};
        InputStream padding = new InputStream() {
            @Override public int read() { throw new AssertionError("Bulk packed reads expected"); }
            @Override public int read(byte[] bytes, int offset, int length) {
                reads[0]++;
                assertEquals("Cancellation must stop the next packed read",1,reads[0]);
                progress.cancel();
                if (mode == 2) return -1;
                bytes[offset] = 0; return 1;
            }
        };
        Class<?> type = Class.forName(SevenZBcj2ArchiveReader.class.getName() + "$FolderStream");
        Constructor<?> constructor = type.getDeclaredConstructor(InputStream.class,long.class,
                List.class,List.class,FileOperationProgress.class);
        constructor.setAccessible(true);
        List<Integer> order = new ArrayList<>();
        Object folder = constructor.newInstance(new ByteArrayInputStream(replacement),(long)replacement.length,
                Collections.singletonList(stream(0,null,order)),Collections.singletonList(padding),progress);
        Method write = type.getDeclaredMethod("writeEntry",long.class,long.class,File.class,boolean.class);
        Method drain = type.getDeclaredMethod("drain");
        Method close = type.getDeclaredMethod("close");
        write.setAccessible(true); drain.setAccessible(true); close.setAccessible(true);
        try {
            write.invoke(folder,0L,(long)replacement.length,target,false);
            if (mode == 0) progress.cancel();
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,() -> drain.invoke(folder));
            assertTrue(failure.getCause() instanceof java.io.InterruptedIOException);
        } finally { close.invoke(folder); }
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals(mode == 0 ? 0 : 1,reads[0]);
        assertArrayEquals(original,Files.readAllBytes(target.toPath()));
        assertEquals(Collections.singletonList(0),order);
        assertArrayEquals(new String[]{"cancelled.bin"},temporary.getRoot().list());
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
