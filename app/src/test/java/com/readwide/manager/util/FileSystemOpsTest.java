package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Assume;

public class FileSystemOpsTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test public void ancestorOverwriteIsRejectedForCopyAndMoveAndPlanner() throws Exception {
        File base = tempFolder.newFolder("base");
        File ancestor = new File(base, "A");
        File source = new File(ancestor, "A");
        assertTrue(source.mkdirs());
        File precious = new File(source, "precious.txt");
        File other = new File(ancestor, "other.txt");
        writeText(precious, "precious");
        writeText(other, "other");
        for (boolean copy : new boolean[]{true, false}) {
            FileClipboardController clipboard = new FileClipboardController();
            clipboard.start(source, copy);
            assertEquals(FileClipboardController.PasteStatus.DIRECTORY_INTO_SELF,
                    clipboard.preparePaste(base).getStatus());
            assertFalse(clipboard.canOverwrite(ancestor));
            assertFalse(clipboard.performOperation(ancestor, true));
            assertText(precious, "precious");
            assertText(other, "other");
        }
    }

    @Test public void directCopyAndMoveIntoDescendantAreRejected() throws Exception {
        File source = tempFolder.newFolder("tree");
        File file = new File(source, "keep.txt");
        writeText(file, "keep");
        File target = new File(source, "child");
        assertFalse(FileSystemOps.copy(source, target, true));
        assertFalse(FileSystemOps.move(source, target, true));
        assertText(file, "keep");
        assertFalse(target.exists());
    }

    @Test public void preCancelledCopyAndMovePreserveDestination() throws Exception {
        File source = tempFolder.newFile("new.txt");
        File target = tempFolder.newFile("old.txt");
        writeText(source, "new");
        writeText(target, "old");
        FileOperationProgress progress = new FileOperationProgress("copy", null);
        progress.cancel();
        assertFalse(FileSystemOps.copy(source, target, true, progress));
        assertFalse(FileSystemOps.move(source, target, true, progress));
        assertText(source, "new");
        assertText(target, "old");
        assertNoTransactions();
    }

    @Test public void cancellationAfterFinalChunkDoesNotCommitReplacement() throws Exception {
        File source = tempFolder.newFile("new.bin");
        File target = tempFolder.newFile("old.bin");
        writeBytes(source, 64 * 1024);
        writeText(target, "old");
        FileOperationProgress progress = new FileOperationProgress("copy", null);
        progress.setListener(snapshot -> {
            if (snapshot.doneBytes > 0 && !snapshot.cancelled) progress.cancel();
        });
        assertFalse(FileSystemOps.copy(source, target, true, progress));
        assertText(target, "old");
        assertEquals(64 * 1024, source.length());
        assertNoTransactions();
    }

    @Test public void missingSourceAndDeclinedOverwritePreserveExistingData() throws Exception {
        File target = tempFolder.newFile("target.txt");
        writeText(target, "old");
        File missing = new File(tempFolder.getRoot(), "missing.txt");
        assertFalse(FileSystemOps.copy(missing, target, true));
        assertFalse(FileSystemOps.move(missing, target, true));
        File source = tempFolder.newFile("source.txt");
        writeText(source, "new");
        assertFalse(FileSystemOps.copy(source, target, false));
        assertFalse(FileSystemOps.move(source, target, false));
        assertText(target, "old");
        assertText(source, "new");
        assertNoTransactions();
    }

    @Test public void directoryCancellationRetainsBothTrees() throws Exception {
        File source = tempFolder.newFolder("source");
        File target = tempFolder.newFolder("target");
        writeText(new File(source, "new.txt"), "new");
        writeText(new File(target, "old.txt"), "old");
        FileOperationProgress progress = new FileOperationProgress("move", null);
        progress.setListener(snapshot -> {
            if (snapshot.doneBytes > 0 && !snapshot.cancelled) progress.cancel();
        });
        assertFalse(FileSystemOps.move(source, target, true, progress));
        assertText(new File(source, "new.txt"), "new");
        assertText(new File(target, "old.txt"), "old");
        assertFalse(new File(target, "new.txt").exists());
        assertNoTransactions();
    }

    @Test public void failedDirectoryReadPreservesDestination() throws Exception {
        File original = tempFolder.newFolder("source");
        File source = new File(original.getPath()) {
            @Override public File[] listFiles() { return null; }
        };
        File target = tempFolder.newFolder("target");
        File old = new File(target, "old.txt");
        writeText(old, "old");
        assertFalse(FileSystemOps.copy(source, target, true));
        assertText(old, "old");
        assertNoTransactions();
    }

    @Test public void failedCommitRollsBackExistingDestination() throws Exception {
        File source = tempFolder.newFile("source.txt");
        writeText(source, "new");
        File actualTarget = tempFolder.newFile("target.txt");
        writeText(actualTarget, "old");
        File target = new File(actualTarget.getPath()) {
            @Override public boolean renameTo(File backup) {
                boolean moved = super.renameTo(backup);
                // Inject loss of staging after the backup rename to force the
                // replacement rename to fail, without deleting original data.
                if (moved) new File(backup.getParentFile(), "replacement").delete();
                return moved;
            }
        };
        assertFalse(FileSystemOps.copy(source, target, true));
        assertText(actualTarget, "old");
        assertText(source, "new");
        assertNoTransactions();
    }

    @Test public void failedRollbackRetainsOriginalInRecoveryDirectory() throws Exception {
        File source = tempFolder.newFile("source.txt");
        writeText(source, "new");
        File actualTarget = tempFolder.newFile("target.txt");
        writeText(actualTarget, "old");
        File[] recovery = new File[1];
        File target = new File(actualTarget.getPath()) {
            @Override public boolean renameTo(File backup) {
                boolean moved = super.renameTo(backup);
                if (moved) {
                    recovery[0] = backup;
                    // Occupy the original name: rollback must not clobber it.
                    actualTarget.mkdir();
                }
                return moved;
            }
        };
        assertFalse(FileSystemOps.copy(source, target, true));
        assertText(recovery[0], "old");
        assertText(source, "new");
        assertTrue(actualTarget.isDirectory());
    }

    @Test public void directoryOverwriteReplacesRatherThanMerges() throws Exception {
        File source = tempFolder.newFolder("source");
        File target = tempFolder.newFolder("target");
        writeText(new File(source, "new.txt"), "new");
        writeText(new File(target, "old.txt"), "old");
        assertTrue(FileSystemOps.copy(source, target, true));
        assertText(new File(target, "new.txt"), "new");
        assertFalse(new File(target, "old.txt").exists());
        assertTrue(FileSystemOps.move(source, target, true));
        assertFalse(source.exists());
        assertText(new File(target, "new.txt"), "new");
        assertNoTransactions();
    }

    @Test public void caseOnlyRenameRejectsDistinctExistingEntry() throws Exception {
        File source = tempFolder.newFile("book.txt");
        File target = new File(tempFolder.getRoot(), "BOOK.txt");
        Assume.assumeFalse("Requires case-sensitive storage", target.exists());
        writeText(source, "first");
        writeText(target, "second");
        assertFalse(FileSystemOps.renameInPlace(source, "BOOK.txt"));
        assertText(source, "first");
        assertText(target, "second");
    }

    @Test public void caseOnlyRenameWorksOnHostStorageSemantics() throws Exception {
        File source = tempFolder.newFile("book.txt");
        writeText(source, "first");
        assertTrue(FileSystemOps.renameInPlace(source, "BOOK.txt"));
        assertText(new File(tempFolder.getRoot(), "BOOK.txt"), "first");
        assertTrue(java.util.Arrays.asList(tempFolder.getRoot().list()).contains("BOOK.txt"));
    }

    @Test public void renameRejectsMissingSourceAndPathComponents() throws Exception {
        File source = tempFolder.newFile("book.txt");
        assertFalse(FileSystemOps.renameInPlace(source, "../escape.txt"));
        assertFalse(FileSystemOps.renameInPlace(source, ".."));
        assertFalse(FileSystemOps.renameInPlace(new File(tempFolder.getRoot(), "missing"), "missing"));
        assertTrue(source.exists());
    }

    private void assertNoTransactions() {
        File[] leftovers = tempFolder.getRoot().listFiles((dir, name) -> name.startsWith(".rwtransfer_"));
        assertEquals(0, leftovers.length);
    }

    private static void writeText(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertText(File file, String expected) throws Exception {
        assertEquals(expected, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void moveWithSharedBatchProgress_copiesBytesAndDoesNotMarkWholeProgressComplete() throws Exception {
        File source = tempFolder.newFile("source.bin");
        writeBytes(source, 128 * 1024);
        File destination = new File(tempFolder.getRoot(), "destination.bin");
        FileOperationProgress progress = new FileOperationProgress("batch", null);
        progress.setTotalBytes(source.length() * 2L);

        boolean moved = FileSystemOps.move(source, destination, false, progress, false);

        assertTrue(moved);
        assertTrue(destination.exists());
        assertFalse(source.exists());
        assertFalse(progress.isComplete());
        assertEquals(destination.length(), progress.snapshot().doneBytes);
    }

    private static void writeBytes(File file, int size) throws Exception {
        byte[] buffer = new byte[8192];
        try (FileOutputStream out = new FileOutputStream(file)) {
            int remaining = size;
            while (remaining > 0) {
                int count = Math.min(remaining, buffer.length);
                out.write(buffer, 0, count);
                remaining -= count;
            }
        }
    }
}
