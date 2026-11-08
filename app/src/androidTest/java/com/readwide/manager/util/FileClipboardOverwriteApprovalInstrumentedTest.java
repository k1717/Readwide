package com.readwide.manager.util;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FileClipboardOverwriteApprovalInstrumentedTest {
    private File root, source, target;
    private FileClipboardController clipboard;
    private FileClipboardController.PendingItem item;

    @Before public void prepare() throws Exception {
        root = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "overwrite-approval-" + UUID.randomUUID());
        assertTrue(root.mkdir());
        source = write(new File(root, "source.txt"), "source");
        target = write(new File(root, "target.txt"), "old");
        clipboard = new FileClipboardController();
        select(true);
    }

    @After public void cleanUp() { FileSystemOps.delete(root); }

    @Test public void unchangedApprovedTargetCanBeOverwritten() throws Exception {
        FileSystemOps.OverwriteTarget approval = FileSystemOps.captureOverwriteTarget(target);
        assertEquals(FileSystemOps.TransferResult.COMPLETED, copy(target, approval, null));
        assertEquals(source.length(), target.length());
    }

    @Test public void replacementBeforeWorkerStartIsNotOverwritten() throws Exception {
        FileSystemOps.OverwriteTarget approval = FileSystemOps.captureOverwriteTarget(target);
        assertTrue(target.renameTo(new File(root, "previous.txt")));
        write(target, "replacement");
        assertEquals(FileSystemOps.TransferResult.FAILED, copy(target, approval, null));
        assertEquals("replacement".length(), target.length());
        assertTrue(source.exists());
    }

    @Test public void editedNestedFileInvalidatesDirectoryApproval() throws Exception {
        directoryTarget();
        File child = write(new File(target, "child.txt"), "old");
        FileSystemOps.OverwriteTarget approval = FileSystemOps.captureOverwriteTarget(target);
        write(child, "edited content");
        assertEquals(FileSystemOps.TransferResult.FAILED, copy(target, approval, null));
        assertTrue(target.isDirectory());
        assertEquals("edited content".length(), child.length());
    }

    @Test public void addedNestedFileInvalidatesDirectoryApproval() throws Exception {
        directoryTarget();
        FileSystemOps.OverwriteTarget approval = FileSystemOps.captureOverwriteTarget(target);
        File added = write(new File(target, "added.txt"), "keep");
        assertEquals(FileSystemOps.TransferResult.FAILED, copy(target, approval, null));
        assertTrue(added.exists());
    }

    @Test public void approvalCannotBeReusedForAnotherDestination() throws Exception {
        FileSystemOps.OverwriteTarget approval = FileSystemOps.captureOverwriteTarget(target);
        File other = write(new File(root, "other.txt"), "keep other");
        assertEquals(FileSystemOps.TransferResult.FAILED, copy(other, approval, null));
        assertEquals("keep other".length(), other.length());
    }

    @Test public void changedTargetAfterWorkerInventoryIsNotAdopted() throws Exception {
        FileSystemOps.OverwriteTarget approval = FileSystemOps.captureOverwriteTarget(target);
        FileOperationProgress progress = new FileOperationProgress("copy", null);
        boolean[] changed = {false};
        progress.setListener(snapshot -> {
            if (snapshot.totalBytes > 0 && !changed[0]) {
                changed[0] = true;
                try { write(target, "changed during wait"); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }
        });
        assertEquals(FileSystemOps.TransferResult.FAILED, copy(target, approval, progress));
        assertTrue(changed[0]);
        assertEquals("changed during wait".length(), target.length());
    }

    @Test public void removedTargetCannotTurnApprovedMoveIntoUnconditionalRename() throws Exception {
        select(false);
        FileSystemOps.OverwriteTarget approval = FileSystemOps.captureOverwriteTarget(target);
        assertTrue(target.delete());
        assertEquals(FileSystemOps.TransferResult.FAILED, copy(target, approval, null));
        assertTrue(source.exists());
        assertFalse(target.exists());
    }

    private void select(boolean copy) {
        clipboard.cancel();
        assertEquals(FileClipboardController.StartResult.STARTED, clipboard.start(source, copy));
        item = clipboard.getActiveItem();
    }

    private FileSystemOps.TransferResult copy(File destination, FileSystemOps.OverwriteTarget approval,
                                               FileOperationProgress progress) {
        return clipboard.performOperationWithResult(item, destination, true, progress, true, null, approval);
    }

    private void directoryTarget() {
        assertTrue(target.delete());
        assertTrue(target.mkdir());
    }

    private static File write(File file, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
}
