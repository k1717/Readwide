package com.readwide.manager.util;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileTransferOutcomeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void renameReportsCompleteAndRemovesSource() throws Exception {
        File source = temp.newFile("source.txt");
        File destination = new File(temp.getRoot(), "destination.txt");
        assertEquals(FileSystemOps.TransferResult.COMPLETED, FileSystemOps.moveWithResult(
                source, destination, false, null, true, null));
        assertFalse(source.exists());
        assertTrue(destination.exists());
    }

    @Test public void failureBeforeCommitPreservesOriginalDestination() throws Exception {
        File source = temp.newFolder("source");
        File destination = temp.newFolder("destination");
        FileOperationProgress progress = new FileOperationProgress("move", null);
        progress.cancel();
        assertEquals(FileSystemOps.TransferResult.FAILED, FileSystemOps.moveWithResult(
                source, destination, true, progress, true, null));
        assertTrue(source.exists());
        assertTrue(destination.exists());
    }

    @Test public void selectedSourceCheckRunsAfterInventoryBeforeRename() throws Exception {
        File source = temp.newFolder("source");
        File destination = new File(temp.getRoot(), "destination");
        FileOperationProgress progress = new FileOperationProgress("move", null);
        boolean[] inventoryReported = {false};
        progress.setListener(snapshot -> {
            if (snapshot.totalBytes >= 0) inventoryReported[0] = true;
        });
        assertEquals(FileSystemOps.TransferResult.FAILED, FileSystemOps.moveWithResult(
                source, destination, false, progress, true, null, () -> {
                    assertTrue(inventoryReported[0]);
                    throw new IOException("Selected source changed");
                }));
        assertTrue(source.isDirectory());
        assertFalse(destination.exists());
    }

    @Test public void selectedSourceCheckRunsAgainAfterStaging() throws Exception {
        File original = temp.newFolder("source");
        File destination = new File(temp.getRoot(), "destination");
        boolean[] copied = {false};
        File source = new File(original.getPath()) {
            @Override public File[] listFiles() {
                copied[0] = true;
                return new File[0];
            }
        };
        assertFalse(FileSystemOps.copyWithSourceCheck(source, destination, false, null, true, null, () -> {
            if (copied[0]) throw new IOException("Selected source changed");
        }));
        assertTrue(copied[0]);
        assertTrue(source.isDirectory());
        assertFalse(destination.exists());
    }

    @Test public void partialFolderRemovalReportsCommittedCopySeparately() throws Exception {
        File source = sourceWithBlockedRemoval();
        assumeIdentifiableDirectory(source);
        File destination = new File(temp.getRoot(), "destination");
        assertEquals(FileSystemOps.TransferResult.COPIED_SOURCE_REMAINS,
                FileSystemOps.moveWithResult(source, destination, true, null, true, null));
        assertTrue(new File(source, "blocked").isDirectory());
        assertFalse(new File(source, "removed").exists());
        assertTrue(new File(destination, "blocked").isDirectory());
        assertTrue(new File(destination, "removed").isDirectory());
    }

    @Test public void batchSeparatesPartialMoveFromCompletedTransfers() throws Exception {
        File source = sourceWithBlockedRemoval();
        assumeIdentifiableDirectory(source);
        File output = temp.newFolder("out");
        File destination = new File(output.getPath()) {
            @Override public boolean canWrite() { return true; }
        };
        FileClipboardController clipboard = new FileClipboardController();
        assertEquals(FileClipboardController.StartResult.STARTED, clipboard.start(source, false));
        FileClipboardController.PendingItem item = clipboard.getActiveItem();
        FileClipboardBatch batch = new FileClipboardBatch(clipboard, Collections.singletonList(item),
                destination, new FileOperationProgress("move", null));
        batch.run();
        assertTrue(batch.completedTransfers().isEmpty());
        assertEquals(1, batch.sourceRemainingTransfers().size());
        assertSame(item, batch.sourceRemainingTransfers().get(0).item);
        assertThrows(UnsupportedOperationException.class, () -> batch.sourceRemainingTransfers().clear());
        clipboard.clearAfterSuccess(batch.sourceRemainingTransfers().get(0).item);
        assertFalse(clipboard.hasPending());
        assertTrue(new File(destination, source.getName() + "/removed").isDirectory());
    }

    @Test public void normalDirectoryOverwriteStillReplacesOldContents() throws Exception {
        File source = temp.newFolder("source");
        assertTrue(new File(source, "new").mkdir());
        File destination = temp.newFolder("destination");
        assumeIdentifiableDirectory(destination);
        assertTrue(new File(destination, "old").mkdir());
        assertEquals(FileSystemOps.TransferResult.COMPLETED,
                FileSystemOps.moveWithResult(source, destination, true, null, true, null));
        assertFalse(source.exists());
        assertFalse(new File(destination, "old").exists());
        assertTrue(new File(destination, "new").isDirectory());
    }

    @Test public void destinationArrivingDuringStagingIsNotOverwritten() throws Exception {
        File original = temp.newFolder("source");
        File destination = new File(temp.getRoot(), "destination");
        File source = new File(original.getPath()) {
            @Override public File[] listFiles() {
                try {
                    Files.write(destination.toPath(), "arrived".getBytes(StandardCharsets.UTF_8));
                } catch (IOException failure) { throw new AssertionError(failure); }
                return new File[0];
            }
        };
        assertFalse(FileSystemOps.copy(source, destination, true));
        assertEquals("arrived", new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
        assertTrue(source.isDirectory());
    }

    @Test public void directoryReplacementDuringStagingIsNotOverwritten() throws Exception {
        File original = temp.newFolder("source");
        File destination = temp.newFolder("destination");
        assumeIdentifiableDirectory(destination);
        File retired = new File(temp.getRoot(), "retired");
        File source = new File(original.getPath()) {
            @Override public File[] listFiles() {
                assertTrue(destination.renameTo(retired));
                assertTrue(destination.mkdir());
                assertTrue(new File(destination, "arrived").mkdir());
                return new File[0];
            }
        };
        assertFalse(FileSystemOps.copy(source, destination, true));
        assertTrue(new File(destination, "arrived").isDirectory());
        assertTrue(retired.isDirectory());
    }

    @Test public void sameSizeDestinationEditDuringStagingIsNotOverwritten() throws Exception {
        File original = temp.newFolder("source");
        File destination = temp.newFile("destination.txt");
        Files.write(destination.toPath(), "before".getBytes(StandardCharsets.UTF_8));
        try {
            FileMutationGuard.capture(destination);
        } catch (IOException unsupportedIdentity) {
            Assume.assumeNoException("Host must expose file identity", unsupportedIdentity);
        }
        long modified = destination.lastModified();
        File source = new File(original.getPath()) {
            @Override public File[] listFiles() {
                try {
                    Files.write(destination.toPath(), "edited".getBytes(StandardCharsets.UTF_8));
                    assertTrue(destination.setLastModified(modified + 60_000L));
                } catch (IOException failure) { throw new AssertionError(failure); }
                return new File[0];
            }
        };
        assertFalse(FileSystemOps.copy(source, destination, true));
        assertEquals("edited", new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void childAddedToDestinationDuringStagingIsPreserved() throws Exception {
        File original = temp.newFolder("source");
        File destination = temp.newFolder("destination");
        assumeIdentifiableDirectory(destination);
        File source = stagingCallback(original, () -> assertTrue(new File(destination, "arrived").mkdir()));
        assertFalse(FileSystemOps.copy(source, destination, true));
        assertTrue(new File(destination, "arrived").isDirectory());
        assertTrue(source.isDirectory());
    }

    @Test public void changedNestedChildMembershipBlocksOverwrite() throws Exception {
        File original = temp.newFolder("source");
        File destination = temp.newFolder("destination");
        assumeIdentifiableDirectory(destination);
        File nested = new File(destination, "nested");
        assertTrue(nested.mkdir());
        File oldChild = new File(nested, "old");
        assertTrue(oldChild.mkdir());
        File renamed = new File(nested, "renamed");
        File source = stagingCallback(original, () -> assertTrue(oldChild.renameTo(renamed)));
        assertFalse(FileSystemOps.copy(source, destination, true));
        assertTrue(renamed.isDirectory());
        assertFalse(oldChild.exists());
    }

    @Test public void sameSizeNestedDestinationEditBlocksOverwrite() throws Exception {
        File original = temp.newFolder("source");
        File destination = temp.newFolder("destination");
        assumeIdentifiableDirectory(destination);
        File nested = new File(destination, "nested");
        assertTrue(nested.mkdir());
        File child = new File(nested, "saved.txt");
        Files.write(child.toPath(), "before".getBytes(StandardCharsets.UTF_8));
        assumeIdentifiableFile(child);
        long modified = child.lastModified();
        File source = stagingCallback(original, () -> {
            Files.write(child.toPath(), "edited".getBytes(StandardCharsets.UTF_8));
            assertTrue(child.setLastModified(modified + 60_000L));
        });
        assertFalse(FileSystemOps.copy(source, destination, true));
        assertEquals("edited", new String(Files.readAllBytes(child.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void deletedDestinationChildBlocksOverwrite() throws Exception {
        File original = temp.newFolder("source");
        File destination = temp.newFolder("destination");
        assumeIdentifiableDirectory(destination);
        File child = new File(destination, "old");
        assertTrue(child.mkdir());
        File source = stagingCallback(original, () -> assertTrue(child.delete()));
        assertFalse(FileSystemOps.copy(source, destination, true));
        assertTrue(destination.isDirectory());
    }

    @Test public void newBackupChildIsNotRecursivelyDeleted() throws Exception {
        File source = temp.newFolder("source");
        assertTrue(new File(source, "replacement-child").mkdir());
        File originalDestination = temp.newFolder("destination");
        assumeIdentifiableDirectory(originalDestination);
        assertTrue(new File(originalDestination, "old").mkdir());
        File[] backup = {null};
        File destination = new File(originalDestination.getPath()) {
            @Override public boolean renameTo(File target) {
                boolean renamed = super.renameTo(target);
                if (renamed) {
                    backup[0] = target;
                    assertTrue(new File(target, "arrived").mkdir());
                }
                return renamed;
            }
        };
        assertTrue(FileSystemOps.copy(source, destination, true));
        assertTrue(new File(destination, "replacement-child").isDirectory());
        assertNotNull(backup[0]);
        assertTrue(new File(backup[0], "arrived").isDirectory());
        assertTrue(new File(backup[0], "old").isDirectory());
    }

    @Test public void unchangedFileBackupCanBeRemovedAfterRename() throws Exception {
        File source = temp.newFolder("source");
        File destination = temp.newFile("destination");
        Files.write(destination.toPath(), "previous".getBytes(StandardCharsets.UTF_8));
        assumeIdentifiableFile(destination);
        assertTrue(FileSystemOps.copy(source, destination, true));
        assertTrue(destination.isDirectory());
        File[] siblings = temp.getRoot().listFiles();
        assertNotNull(siblings);
        for (File sibling : siblings) assertFalse(sibling.getName().startsWith(".rwtransfer_"));
    }

    @Test public void growingSourceNeverWritesPastCapturedSize() throws Exception {
        File source = temp.newFile("source.bin");
        byte[] initial = new byte[64 * 1024];
        Files.write(source.toPath(), initial);
        try {
            FileMutationGuard.capture(source);
        } catch (IOException unsupportedIdentity) {
            Assume.assumeNoException("Host must expose file identity", unsupportedIdentity);
        }
        File destination = temp.newFile("destination.bin");
        byte[] previous = "previous".getBytes(StandardCharsets.UTF_8);
        Files.write(destination.toPath(), previous);
        FileOperationProgress progress = new FileOperationProgress("copy", null);
        boolean[] appended = {false};
        long[] maximumStagedBytes = {0L};
        progress.setListener(snapshot -> {
            File[] siblings = source.getParentFile().listFiles();
            if (siblings != null) for (File sibling : siblings) {
                if (sibling.getName().startsWith(".rwtransfer_")) {
                    maximumStagedBytes[0] = Math.max(maximumStagedBytes[0],
                            new File(sibling, "replacement").length());
                }
            }
            if (snapshot.doneBytes > 0 && !appended[0]) {
                appended[0] = true;
                try (FileOutputStream out = new FileOutputStream(source, true)) {
                    out.write(initial);
                } catch (IOException failure) { throw new AssertionError(failure); }
            }
        });
        assertFalse(FileSystemOps.copy(source, destination, true, progress));
        assertTrue(appended[0]);
        assertEquals(initial.length, maximumStagedBytes[0]);
        assertArrayEquals(previous, Files.readAllBytes(destination.toPath()));
        assertEquals(2L * initial.length, source.length());
    }

    @Test public void unchangedFallbackMoveStillRemovesSource() throws Exception {
        File original = temp.newFolder("source");
        assumeIdentifiableDirectory(original);
        File payload = new File(original, "book.txt");
        byte[] bytes = "book content".getBytes(StandardCharsets.UTF_8);
        Files.write(payload.toPath(), bytes);
        assumeIdentifiableFile(payload);
        assertTrue(new File(original, "empty").mkdir());
        File destination = new File(temp.getRoot(), "destination");
        assertEquals(FileSystemOps.TransferResult.COMPLETED, FileSystemOps.moveWithResult(
                beforeSourceRemoval(original, () -> { }), destination, false, null, true, null));
        assertFalse(original.exists());
        assertArrayEquals(bytes, Files.readAllBytes(new File(destination, "book.txt").toPath()));
        assertTrue(new File(destination, "empty").isDirectory());
    }

    @Test public void removedCommittedDestinationKeepsSource() throws Exception {
        File original = temp.newFolder("source");
        assumeIdentifiableDirectory(original);
        File destination = new File(temp.getRoot(), "destination");
        File source = beforeSourceRemoval(original, () -> assertTrue(destination.delete()));
        assertEquals(FileSystemOps.TransferResult.COPIED_SOURCE_REMAINS,
                FileSystemOps.moveWithResult(source, destination, false, null, true, null));
        assertTrue(original.isDirectory());
        assertFalse(destination.exists());
    }

    @Test public void replacedCommittedDestinationKeepsSource() throws Exception {
        File original = temp.newFolder("source");
        assumeIdentifiableDirectory(original);
        File destination = new File(temp.getRoot(), "destination");
        File retired = new File(temp.getRoot(), "retired");
        File source = beforeSourceRemoval(original, () -> {
            assertTrue(destination.renameTo(retired));
            assertTrue(destination.mkdir());
        });
        assertEquals(FileSystemOps.TransferResult.COPIED_SOURCE_REMAINS,
                FileSystemOps.moveWithResult(source, destination, false, null, true, null));
        assertTrue(original.isDirectory());
        assertTrue(destination.isDirectory());
        assertTrue(retired.isDirectory());
    }

    @Test public void replacedDestinationParentKeepsSource() throws Exception {
        File original = temp.newFolder("source");
        assumeIdentifiableDirectory(original);
        File parent = temp.newFolder("target");
        File destination = new File(parent, "destination");
        File retired = new File(temp.getRoot(), "retired");
        File source = beforeSourceRemoval(original, () -> {
            assertTrue(parent.renameTo(retired));
            assertTrue(parent.mkdir());
            assertTrue(destination.mkdir());
        });
        assertEquals(FileSystemOps.TransferResult.COPIED_SOURCE_REMAINS,
                FileSystemOps.moveWithResult(source, destination, false, null, true, null));
        assertTrue(original.isDirectory());
        assertTrue(new File(retired, "destination").isDirectory());
    }

    @Test public void editedCommittedFileKeepsOriginalBytes() throws Exception {
        File original = temp.newFolder("source");
        assumeIdentifiableDirectory(original);
        File payload = new File(original, "book.txt");
        byte[] bytes = "book content".getBytes(StandardCharsets.UTF_8);
        Files.write(payload.toPath(), bytes);
        assumeIdentifiableFile(payload);
        File destination = new File(temp.getRoot(), "destination");
        File source = beforeSourceRemoval(original,
                () -> Files.write(new File(destination, "book.txt").toPath(), new byte[]{1}));
        assertEquals(FileSystemOps.TransferResult.COPIED_SOURCE_REMAINS,
                FileSystemOps.moveWithResult(source, destination, false, null, true, null));
        assertArrayEquals(bytes, Files.readAllBytes(payload.toPath()));
    }

    @Test public void destinationLossBetweenSourceLeavesKeepsUncopiedLeaf() throws Exception {
        File original = temp.newFolder("source");
        assumeIdentifiableDirectory(original);
        File retained = new File(original, "retained");
        File removed = new File(original, "removed");
        assertTrue(retained.mkdir());
        assertTrue(removed.mkdir());
        File destination = new File(temp.getRoot(), "destination");
        File removalCallback = new File(removed.getPath()) {
            @Override public boolean delete() {
                boolean deleted = super.delete();
                if (deleted) assertTrue(new File(destination, "retained").delete());
                return deleted;
            }
        };
        File source = new File(original.getPath()) {
            @Override public boolean renameTo(File target) { return false; }
            @Override public File[] listFiles() { return new File[]{retained, removalCallback}; }
        };
        assertEquals(FileSystemOps.TransferResult.COPIED_SOURCE_REMAINS,
                FileSystemOps.moveWithResult(source, destination, false, null, true, null));
        assertTrue(retained.isDirectory());
        assertFalse(removed.exists());
        assertTrue(new File(destination, "removed").isDirectory());
    }

    @Test public void destinationLostWhilePausedKeepsSource() throws Exception {
        File original = temp.newFolder("source");
        assumeIdentifiableDirectory(original);
        File destination = new File(temp.getRoot(), "destination");
        FileOperationProgress progress = new FileOperationProgress("move", null);
        CountDownLatch paused = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        File source = beforeSourceRemoval(original, () -> {
            progress.setPaused(true);
            paused.countDown();
        });
        Thread changer = new Thread(() -> {
            try {
                assertTrue(paused.await(5, TimeUnit.SECONDS));
                assertTrue(destination.delete());
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                progress.setPaused(false);
            }
        });
        changer.start();
        try {
            assertEquals(FileSystemOps.TransferResult.COPIED_SOURCE_REMAINS,
                    FileSystemOps.moveWithResult(source, destination, false, progress, true, null));
        } finally {
            progress.cancel();
            changer.join(6000);
        }
        assertFalse(changer.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue(original.isDirectory());
    }

    private File beforeSourceRemoval(File original, StagingChange change) {
        return new File(original.getPath()) {
            private boolean changed;
            @Override public boolean renameTo(File target) { return false; }
            @Override public File[] listFiles() { return original.listFiles(); }
            @Override public String[] list() {
                if (!changed) {
                    changed = true;
                    try { change.run(); }
                    catch (IOException failure) { throw new AssertionError(failure); }
                }
                return super.list();
            }
        };
    }

    private File sourceWithBlockedRemoval() throws Exception {
        File original = temp.newFolder("source");
        File blocked = new File(original, "blocked");
        File removed = new File(original, "removed");
        assertTrue(blocked.mkdir());
        assertTrue(removed.mkdir());
        File blockedRemoval = new File(blocked.getPath()) {
            @Override public boolean delete() { return false; }
        };
        return new File(original.getPath()) {
            @Override public boolean renameTo(File destination) { return false; }
            @Override public File[] listFiles() { return new File[]{blockedRemoval, removed}; }
        };
    }

    private static void assumeIdentifiableDirectory(File directory) throws IOException {
        FileTreeWalk.walk(directory, () -> true, entry -> {
            Assume.assumeNotNull(entry.identity);
            return false;
        });
    }

    private static void assumeIdentifiableFile(File file) {
        try {
            FileMutationGuard.capture(file);
        } catch (IOException unavailable) {
            Assume.assumeNoException("Host must expose file identity", unavailable);
        }
    }

    private interface StagingChange { void run() throws IOException; }

    private File stagingCallback(File original, StagingChange change) {
        return new File(original.getPath()) {
            @Override public File[] listFiles() {
                try {
                    change.run();
                } catch (IOException failure) { throw new AssertionError(failure); }
                return new File[0];
            }
        };
    }
}
