package com.readwide.manager.archive;

import static org.junit.Assert.*;

import com.readwide.manager.util.ArchiveZipWriter;
import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.zip.ZipFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ArchiveZipSourceMutationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void unchangedFilesAndEmptyDirectoriesStillRoundTrip() throws Exception {
        for (boolean showProgress : new boolean[]{false, true}) {
            File root = temporary.newFolder();
            File input = new File(root, "input");
            assertTrue(input.mkdir());
            assertTrue(new File(input, "empty").mkdir());
            byte[] contents = payload(150_000);
            File source = new File(input, "text.bin");
            Files.write(source.toPath(), contents);
            File output = new File(root, "result.zip");
            FileOperationProgress progress = showProgress
                    ? new FileOperationProgress("zip", null) : null;
            assertTrue(ArchiveZipWriter.create(Collections.singletonList(input), output, progress));
            try (ZipFile zip = new ZipFile(output)) {
                assertNotNull(zip.getEntry("input/"));
                assertNotNull(zip.getEntry("input/empty/"));
                try (java.io.InputStream data = zip.getInputStream(zip.getEntry("input/text.bin"))) {
                    assertArrayEquals(contents, data.readAllBytes());
                }
            }
        }
    }

    @Test public void sameSizeEditBeforeOpeningSourceRejectsNewZip() throws Exception {
        assertMutationRejected(19,
                snapshot -> snapshot.detail.equals("source.bin") && snapshot.doneBytes == 0, true);
    }

    @Test public void sameSizeEditBetweenChunksRejectsNewZip() throws Exception {
        assertMutationRejected(256 * 1024,
                snapshot -> snapshot.doneBytes > 0 && snapshot.doneBytes < snapshot.totalBytes, true);
    }

    @Test public void sameSizeEditAfterFinalChunkRejectsNewZip() throws Exception {
        assertMutationRejected(19,
                snapshot -> snapshot.doneBytes > 0 && snapshot.doneBytes == snapshot.totalBytes, true);
    }

    @Test public void changedTimestampAfterFinalChunkRejectsNewZip() throws Exception {
        assertMutationRejected(19,
                snapshot -> snapshot.doneBytes > 0 && snapshot.doneBytes == snapshot.totalBytes, false);
    }

    @Test public void existingDestinationIsNeverOverwrittenOrRemoved() throws Exception {
        File root = temporary.newFolder();
        File source = new File(root, "source.bin");
        File output = new File(root, "existing.zip");
        byte[] original = payload(29);
        Files.write(source.toPath(), payload(17));
        Files.write(output.toPath(), original);
        assertFalse(ArchiveZipWriter.create(Collections.singletonList(source), output, null));
        assertArrayEquals(original, Files.readAllBytes(output.toPath()));
    }

    @Test public void cancellationStillRemovesOnlyTheNewDestination() throws Exception {
        File root = temporary.newFolder();
        File source = new File(root, "source.bin");
        File output = new File(root, "cancelled.zip");
        byte[] contents = payload(256 * 1024);
        Files.write(source.toPath(), contents);
        FileOperationProgress progress = new FileOperationProgress("zip", null);
        AtomicBoolean cancelled = new AtomicBoolean();
        progress.setListener(snapshot -> {
            if (snapshot.doneBytes > 0 && cancelled.compareAndSet(false, true)) progress.cancel();
        });
        assertFalse(ArchiveZipWriter.create(Collections.singletonList(source), output, progress));
        assertTrue(cancelled.get());
        assertFalse(output.exists());
        assertArrayEquals(contents, Files.readAllBytes(source.toPath()));
    }

    private void assertMutationRejected(int length, Predicate<FileOperationProgress.Snapshot> when,
                                        boolean changeBytes) throws Exception {
        File root = temporary.newFolder();
        File source = new File(root, "source.bin");
        File output = new File(root, "result.zip");
        byte[] contents = payload(length);
        Files.write(source.toPath(), contents);
        long changedTime = Files.getLastModifiedTime(source.toPath()).toMillis() + 120_000;
        AtomicBoolean changed = new AtomicBoolean();
        FileOperationProgress progress = new FileOperationProgress("zip", snapshot -> {
            if (!when.test(snapshot) || !changed.compareAndSet(false, true)) return;
            try {
                if (changeBytes) {
                    try (RandomAccessFile edit = new RandomAccessFile(source, "rw")) {
                        edit.write(contents[0] ^ 255);
                    }
                }
                // Make the change observable even on coarse-timestamp filesystems.
                Files.setLastModifiedTime(source.toPath(), FileTime.fromMillis(changedTime));
            } catch (IOException failure) {
                throw new AssertionError("Could not change the test source", failure);
            }
        });
        assertFalse(ArchiveZipWriter.create(Collections.singletonList(source), output, progress));
        assertTrue("Mutation callback must have run", changed.get());
        assertFalse("Failed creation must remove its own ZIP", output.exists());
        if (changeBytes) contents[0] ^= (byte) 255;
        assertArrayEquals("Source edits must remain untouched", contents, Files.readAllBytes(source.toPath()));
    }

    private static byte[] payload(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 29);
        return bytes;
    }
}
