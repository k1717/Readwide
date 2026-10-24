package com.readwide.manager.archive;

import static org.junit.Assert.*;

import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Injects ownership failures, not real heap exhaustion or decoder execution. */
public class EggSolidOwnershipTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void failedPendingAdoptionRestoresNewestBeforeEarlierOutputs() throws Exception {
        for (boolean duplicate : new boolean[]{false, true}) {
            for (boolean existing : new boolean[]{false, true}) {
                File directory = temp.newFolder(), first = new File(directory, "first");
                File second = duplicate ? first : new File(directory, "second");
                if (existing) {
                    Files.write(first.toPath(), new byte[]{41});
                    if (!duplicate) Files.write(second.toPath(), new byte[]{42});
                }
                OutputStream writer = writer(null, first, second);
                writer.write(new byte[]{1, 2});
                OutOfMemoryError failure = new OutOfMemoryError("simulated list adoption");
                RejectingList guards = new RejectingList(pending(writer), failure);
                field(writer, "awaitingVerification").set(writer, guards);
                assertSame(failure, assertThrows(OutOfMemoryError.class, writer::close));
                assertEquals(1, guards.attempts);
                writer.close();
                assertEquals(0, guards.size());
                assertNull(field(writer, "currentGuard").get(writer));
                if (existing) {
                    assertArrayEquals(new byte[]{41}, Files.readAllBytes(first.toPath()));
                    if (!duplicate) assertArrayEquals(new byte[]{42}, Files.readAllBytes(second.toPath()));
                } else {
                    assertFalse(first.exists()); assertFalse(second.exists());
                }
                assertEquals(existing ? (duplicate ? 1 : 2) : 0, directory.list().length);
            }
        }
    }

    @Test public void failedStreamCloseRollsBackWithoutAllocatingAnotherListSlot() throws Exception {
        File directory = temp.newFolder(), first = new File(directory, "first"), second = new File(directory, "second");
        Files.write(first.toPath(), new byte[]{41}); Files.write(second.toPath(), new byte[]{42});
        OutputStream writer = writer(null, first, second);
        writer.write(new byte[]{1, 2});
        RejectingList guards = new RejectingList(pending(writer), new OutOfMemoryError("must not allocate"));
        field(writer, "awaitingVerification").set(writer, guards);
        IOException failure = new IOException("destination close");
        OutputStream current = (OutputStream) field(writer, "current").get(writer);
        field(writer, "current").set(writer, new FilterOutputStream(current) {
            @Override public void close() throws IOException { super.close(); throw failure; }
        });
        assertSame(failure, assertThrows(IOException.class, writer::close));
        assertEquals(0, guards.attempts);
        writer.close();
        assertArrayEquals(new byte[]{41}, Files.readAllBytes(first.toPath()));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(second.toPath()));
        assertEquals(2, directory.list().length);
    }

    @Test public void cancellationWhileClosingLastOutputPreventsSolidCommit() throws Exception {
        File directory = temp.newFolder(), target = new File(directory, "entry");
        Files.write(target.toPath(), new byte[]{41});
        FileOperationProgress progress = new FileOperationProgress("solid", null);
        try (SplitVolumeInput source = source(temp.newFile()); OutputStream writer = writer(progress, target)) {
            writer.write(new byte[]{1});
            OutputStream current = (OutputStream) field(writer, "current").get(writer);
            field(writer, "current").set(writer, new FilterOutputStream(current) {
                @Override public void close() throws IOException { super.close(); progress.cancel(); }
            });
            assertThrows(InterruptedIOException.class, () -> commit(writer, source));
        }
        assertArrayEquals(new byte[]{41}, Files.readAllBytes(target.toPath()));
        assertEquals(1, directory.list().length);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void cancellationDuringSourceValidationPreventsSolidCommit() throws Exception {
        sourceBoundary(true);
    }

    @Test public void activeTokenDuringSourceValidationStillCommitsSolidOutput() throws Exception {
        sourceBoundary(false);
    }

    private void sourceBoundary(boolean cancel) throws Exception {
        File directory = temp.newFolder(), target = new File(directory, "entry");
        Files.write(target.toPath(), new byte[]{41});
        FileOperationProgress progress = new FileOperationProgress("solid", null);
        boolean[] armed = {false}; int[] checks = {0};
        File observed = new File(temp.newFile().getPath()) {
            @Override public File getCanonicalFile() { return this; }
            @Override public long lastModified() {
                if (armed[0]) { checks[0]++; if (cancel) progress.cancel(); }
                return super.lastModified();
            }
        };
        try (SplitVolumeInput source = source(observed); OutputStream writer = writer(progress, target)) {
            writer.write(new byte[]{1}); armed[0] = true;
            if (cancel) assertThrows(InterruptedIOException.class, () -> commit(writer, source));
            else commit(writer, source);
        }
        assertEquals(1, checks[0]);
        assertArrayEquals(new byte[]{cancel ? (byte)41 : (byte)1}, Files.readAllBytes(target.toPath()));
        assertEquals(1, directory.list().length);
    }

    @Test public void verifiedPrefixCommitsButUnfinishedNextOutputStillRollsBack() throws Exception {
        File directory = temp.newFolder(), first = new File(directory, "first"), second = new File(directory, "second");
        Files.write(first.toPath(), new byte[]{41}); Files.write(second.toPath(), new byte[]{42});
        // Two single-byte targets; after committing the first, open the second
        // with no payload, as a block can end before the next entry is complete.
        try (SplitVolumeInput source = source(temp.newFile()); OutputStream writer = writer(null, first, second)) {
            writer.write(new byte[]{1}); commit(writer, source);
            Method advance = writer.getClass().getDeclaredMethod("advance"); advance.setAccessible(true);
            invoke(advance, writer);
        }
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(first.toPath()));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(second.toPath()));
        assertEquals(2, directory.list().length);
    }

    private static OutputStream writer(FileOperationProgress progress, File... targets) throws Exception {
        Class<?> targetType = Class.forName(EggArchiveReader.class.getName() + "$SolidTarget");
        Constructor<?> targetConstructor = targetType.getDeclaredConstructor(String.class, long.class, File.class);
        targetConstructor.setAccessible(true);
        List<Object> entries = new ArrayList<>();
        for (File file : targets) entries.add(targetConstructor.newInstance(file.getName(), 1L, file));
        Class<?> writerType = Class.forName(EggArchiveReader.class.getName() + "$SolidEntryWriter");
        Constructor<?> constructor = writerType.getDeclaredConstructor(List.class, FileOperationProgress.class,
                ArchiveExtractionProgressTracker.class);
        constructor.setAccessible(true);
        return (OutputStream) constructor.newInstance(entries, progress, null);
    }

    private static SplitVolumeInput source(File file) throws IOException {
        return new SplitVolumeInput(Collections.singletonList(new SplitVolumeInput.Segment(file, 0, file.length())));
    }

    private static void commit(OutputStream writer, SplitVolumeInput source) throws Exception {
        Method method = writer.getClass().getDeclaredMethod("commitVerifiedFiles", SplitVolumeInput.class);
        method.setAccessible(true); invoke(method, writer, source);
    }

    @SuppressWarnings("unchecked")
    private static List<RarOutputFileGuard> pending(OutputStream writer) throws Exception {
        return (List<RarOutputFileGuard>) field(writer, "awaitingVerification").get(writer);
    }

    private static Field field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static Object invoke(Method method, Object owner, Object... args) throws Exception {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException wrapped) {
            Throwable failure = wrapped.getCause();
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new AssertionError(failure);
        }
    }

    private static final class RejectingList extends ArrayList<RarOutputFileGuard> {
        final OutOfMemoryError failure; int attempts;
        RejectingList(List<RarOutputFileGuard> guards, OutOfMemoryError failure) { super(guards); this.failure = failure; }
        @Override public boolean add(RarOutputFileGuard guard) { attempts++; throw failure; }
    }
}
