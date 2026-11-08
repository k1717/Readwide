package com.readwide.manager.util;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileClipboardSourceSnapshotTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final FileClipboardController clipboard = new FileClipboardController();

    @Test public void missingSourceIsNotQueued() {
        assertEquals(FileClipboardController.StartResult.INVALID_SOURCE,
                clipboard.start(new File(temp.getRoot(), "missing"), false));
        assertFalse(clipboard.hasPending());
    }

    @Test public void unidentifiedSourceIsNotQueued() throws Exception {
        File source = temp.newFolder("source");
        Assume.assumeFalse(hasIdentity(source));
        assertEquals(FileClipboardController.StartResult.INVALID_SOURCE, clipboard.start(source, false));
        assertFalse(clipboard.hasPending());
    }

    @Test public void selectingFolderDoesNotTraverseItsChildren() throws Exception {
        File directory = temp.newFolder("source");
        File source = new File(directory.getPath()) {
            @Override public File[] listFiles() { throw new AssertionError("Selection must not scan children"); }
        };
        assertEquals(hasIdentity(directory) ? FileClipboardController.StartResult.STARTED
                        : FileClipboardController.StartResult.INVALID_SOURCE,
                clipboard.start(source, true));
    }

    @Test public void replacedFileRejectsPrepareOverwriteAndExecution() throws Exception {
        File source = temp.newFile("source.txt");
        write(source, "original");
        assumeFileIdentity(source);
        assertEquals(FileClipboardController.StartResult.STARTED, clipboard.start(source, false));
        FileClipboardController.PendingItem item = clipboard.getActiveItem();
        File output = writableOutput();
        File destination = new File(output, source.getName());
        assertEquals(FileClipboardController.PasteStatus.READY, clipboard.preparePaste(item, output).getStatus());
        File retained = new File(temp.getRoot(), "retained.txt");
        assertTrue(source.renameTo(retained));
        write(source, "replaced");
        assertEquals(FileClipboardController.PasteStatus.SOURCE_UNAVAILABLE,
                clipboard.preparePaste(item, output).getStatus());
        assertFalse(clipboard.canOverwrite(item, destination));
        assertEquals(FileSystemOps.TransferResult.FAILED,
                clipboard.performOperationWithResult(item, destination, true, null, true, null));
        assertEquals("replaced", read(source));
        assertEquals("original", read(retained));
        assertFalse(destination.exists());
    }

    @Test public void sameSizeFileEditRejectsQueuedCopy() throws Exception {
        File source = temp.newFile("source.txt");
        write(source, "original");
        assumeFileIdentity(source);
        assertEquals(FileClipboardController.StartResult.STARTED, clipboard.start(source, true));
        long modified = source.lastModified();
        write(source, "modified");
        assertTrue(source.setLastModified(modified + 60_000L));
        assertEquals(FileClipboardController.PasteStatus.SOURCE_UNAVAILABLE,
                clipboard.preparePaste(writableOutput()).getStatus());
    }

    @Test public void replacedDirectoryRejectsPreviouslyPreparedMove() throws Exception {
        File source = temp.newFolder("source");
        Assume.assumeTrue(hasIdentity(source));
        assertEquals(FileClipboardController.StartResult.STARTED, clipboard.start(source, false));
        FileClipboardController.PendingItem item = clipboard.getActiveItem();
        File output = writableOutput();
        assertEquals(FileClipboardController.PasteStatus.READY, clipboard.preparePaste(item, output).getStatus());
        File retained = new File(temp.getRoot(), "retained");
        assertTrue(source.renameTo(retained));
        assertTrue(source.mkdir());
        File newChild = new File(source, "new.txt");
        write(newChild, "keep");
        assertEquals(FileSystemOps.TransferResult.FAILED, clipboard.performOperationWithResult(
                item, new File(output, source.getName()), false, null, true, null));
        assertEquals("keep", read(newChild));
        assertTrue(retained.isDirectory());
        assertFalse(new File(output, source.getName()).exists());
    }

    @Test public void childEditsDoNotInvalidateUnchangedFolderRoot() throws Exception {
        File source = temp.newFolder("source");
        Assume.assumeTrue(hasIdentity(source));
        assertEquals(FileClipboardController.StartResult.STARTED, clipboard.start(source, true));
        write(new File(source, "added.txt"), "new contents");
        assertEquals(FileClipboardController.PasteStatus.READY,
                clipboard.preparePaste(writableOutput()).getStatus());
    }

    @Test public void rootReplacedAfterInventoryCannotReachRenameCommit() throws Exception {
        File source = temp.newFolder("source");
        Assume.assumeTrue(hasIdentity(source));
        assertEquals(FileClipboardController.StartResult.STARTED, clipboard.start(source, false));
        FileClipboardController.PendingItem item = clipboard.getActiveItem();
        File output = writableOutput();
        File retained = new File(temp.getRoot(), "retained");
        FileOperationProgress progress = new FileOperationProgress("move", null);
        boolean[] replaced = {false};
        progress.setListener(snapshot -> {
            if (snapshot.totalBytes >= 0 && !replaced[0]) {
                replaced[0] = true;
                assertTrue(source.renameTo(retained));
                assertTrue(source.mkdir());
            }
        });
        assertEquals(FileSystemOps.TransferResult.FAILED, clipboard.performOperationWithResult(
                item, new File(output, source.getName()), false, progress, true, null));
        assertTrue(replaced[0]);
        assertTrue(source.isDirectory());
        assertTrue(retained.isDirectory());
        assertFalse(new File(output, source.getName()).exists());
    }

    private File writableOutput() throws IOException {
        File output = temp.newFolder("output");
        return new File(output.getPath()) {
            @Override public boolean canWrite() { return true; }
        };
    }

    private static boolean hasIdentity(File source) throws IOException {
        boolean[] identifiable = {false};
        FileTreeWalk.walk(source, () -> true, entry -> {
            identifiable[0] = entry.identity != null;
            return false;
        });
        return identifiable[0];
    }

    private static void assumeFileIdentity(File source) throws IOException {
        Assume.assumeTrue(hasIdentity(source));
        try { FileMutationGuard.capture(source); }
        catch (IOException unsupported) { Assume.assumeNoException(unsupported); }
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
