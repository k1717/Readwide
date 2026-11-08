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
public class FileDeletionPlanInstrumentedTest {
    private File workspace;

    @Before public void prepare() {
        workspace = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "delete-plan-" + UUID.randomUUID());
        assertTrue(workspace.mkdir());
    }

    @After public void cleanUp() { FileSystemOps.delete(workspace); }

    @Test public void unchangedTreeIsDeleted() throws Exception {
        File directory = directory("books");
        write(new File(directory, "book.txt"), "book");
        FileDeletionPlan plan = FileDeletionPlan.capture(directory);
        assertEquals(FileDeletionPlan.Result.DELETED, plan.delete(null, null));
        assertFalse(directory.exists());
    }

    @Test public void replacementFileAtSamePathIsNotDeleted() throws Exception {
        File file = write(new File(workspace, "book.txt"), "old");
        FileDeletionPlan plan = FileDeletionPlan.capture(file);
        assertTrue(file.renameTo(new File(workspace, "previous.txt")));
        write(file, "new");
        assertEquals(FileDeletionPlan.Result.CHANGED, plan.delete(null, null));
        assertTrue(file.exists());
    }

    @Test public void modifiedFileIsNotDeleted() throws Exception {
        File file = write(new File(workspace, "book.txt"), "old");
        FileDeletionPlan plan = FileDeletionPlan.capture(file);
        write(file, "updated contents");
        assertEquals(FileDeletionPlan.Result.CHANGED, plan.delete(null, null));
        assertTrue(file.exists());
    }

    @Test public void newNestedChildPreventsAnyDeletion() throws Exception {
        File directory = directory("books");
        File nested = new File(directory, "nested");
        assertTrue(nested.mkdir());
        File old = write(new File(nested, "old.txt"), "old");
        FileDeletionPlan plan = FileDeletionPlan.capture(directory);
        File added = write(new File(nested, "new.txt"), "new");
        assertEquals(FileDeletionPlan.Result.CHANGED, plan.delete(null, null));
        assertTrue(old.exists());
        assertTrue(added.exists());
    }

    @Test public void replacementDirectoryAtSamePathIsNotDeleted() throws Exception {
        File directory = directory("books");
        write(new File(directory, "book.txt"), "old");
        FileDeletionPlan plan = FileDeletionPlan.capture(directory);
        assertTrue(directory.renameTo(new File(workspace, "previous")));
        assertTrue(directory.mkdir());
        File replacement = write(new File(directory, "book.txt"), "new");
        assertEquals(FileDeletionPlan.Result.CHANGED, plan.delete(null, null));
        assertTrue(replacement.exists());
    }

    @Test public void cancellationKeepsConfirmedTree() throws Exception {
        File directory = directory("books");
        File file = write(new File(directory, "book.txt"), "book");
        FileDeletionPlan plan = FileDeletionPlan.capture(directory);
        FileOperationProgress progress = new FileOperationProgress("delete", null);
        progress.cancel();
        assertEquals(FileDeletionPlan.Result.CANCELLED, plan.delete(progress, null));
        assertTrue(file.exists());
    }

    @Test public void childAddedDuringRemovalIsNeverDeleted() throws Exception {
        File directory = directory("books");
        File first = write(new File(directory, "first.txt"), "first");
        File added = new File(directory, "added.txt");
        File injected = new File(first.getPath()) {
            @Override public boolean delete() {
                try { write(added, "new"); }
                catch (Exception failure) { throw new AssertionError(failure); }
                return super.delete();
            }
        };
        File root = new File(directory.getPath()) {
            @Override public File[] listFiles() { return new File[]{injected}; }
        };
        FileDeletionPlan plan = FileDeletionPlan.capture(root);
        assertEquals(FileDeletionPlan.Result.FAILED, plan.delete(null, null));
        assertTrue(added.exists());
        assertFalse(first.exists());
        assertTrue(directory.exists());
    }

    @Test public void progressCallbackReplacementIsCheckedBeforeRemoval() throws Exception {
        File file = write(new File(workspace, "book.txt"), "old");
        FileDeletionPlan plan = FileDeletionPlan.capture(file);
        boolean[] replaced = {false};
        FileOperationProgress progress = new FileOperationProgress("delete", snapshot -> {
            if (!replaced[0] && snapshot.detail.equals("book.txt")) {
                replaced[0] = true;
                assertTrue(file.renameTo(new File(workspace, "previous.txt")));
                try { write(file, "new"); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }
        });
        assertEquals(FileDeletionPlan.Result.CHANGED, plan.delete(progress, null));
        assertTrue(replaced[0]);
        assertTrue(file.exists());
    }

    @Test public void threadInterruptionMarksProgressCancelled() throws Exception {
        File file = write(new File(workspace, "book.txt"), "book");
        FileDeletionPlan plan = FileDeletionPlan.capture(file);
        FileOperationProgress progress = new FileOperationProgress("delete", null);
        Thread.currentThread().interrupt();
        try {
            assertEquals(FileDeletionPlan.Result.CANCELLED, plan.delete(progress, null));
            assertTrue(progress.isCancelled());
            assertTrue(file.exists());
        } finally { Thread.interrupted(); }
    }

    @Test public void finalProgressFailureDoesNotUndoCompletedDeletion() throws Exception {
        File file = write(new File(workspace, "book.txt"), "book");
        FileDeletionPlan plan = FileDeletionPlan.capture(file);
        FileOperationProgress progress = new FileOperationProgress("delete", snapshot -> {
            if (snapshot.doneBytes > 0) throw new IllegalStateException("progress listener");
        });
        assertEquals(FileDeletionPlan.Result.DELETED, plan.delete(progress, null));
        assertFalse(file.exists());
    }

    private File directory(String name) {
        File directory = new File(workspace, name);
        assertTrue(directory.mkdir());
        return directory;
    }

    private static File write(File file, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
}
