package com.readwide.manager.util;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FileRenamePlanInstrumentedTest {
    private File workspace;

    @Before public void prepare() {
        workspace = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "rename-plan-" + UUID.randomUUID());
        assertTrue(workspace.mkdir());
    }

    @After public void cleanUp() { FileSystemOps.delete(workspace); }

    @Test public void unchangedFileRenames() throws Exception {
        File source = write("book.txt", "book");
        FileRenamePlan plan = FileRenamePlan.capture(source);
        assertTrue(plan.rename("renamed.txt"));
        assertFalse(source.exists());
        assertTrue(new File(workspace, "renamed.txt").isFile());
    }

    @Test public void replacementFileKeepsItsName() throws Exception {
        File source = write("book.txt", "old");
        FileRenamePlan plan = FileRenamePlan.capture(source);
        assertTrue(source.renameTo(new File(workspace, "previous.txt")));
        write("book.txt", "new");
        assertFalse(plan.rename("renamed.txt"));
        assertTrue(source.isFile());
        assertFalse(new File(workspace, "renamed.txt").exists());
    }

    @Test public void editedFileKeepsItsName() throws Exception {
        File source = write("book.txt", "old");
        FileRenamePlan plan = FileRenamePlan.capture(source);
        write("book.txt", "new contents");
        assertFalse(plan.isUnchanged());
        assertFalse(plan.rename("renamed.txt"));
        assertTrue(source.exists());
    }

    @Test public void replacementDirectoryKeepsItsName() throws Exception {
        File source = new File(workspace, "books");
        assertTrue(source.mkdir());
        FileRenamePlan plan = FileRenamePlan.capture(source);
        assertTrue(source.renameTo(new File(workspace, "previous")));
        assertTrue(source.mkdir());
        assertFalse(plan.rename("renamed"));
        assertTrue(source.isDirectory());
    }

    @Test public void directoryCaptureDoesNotEnumerateChildren() throws Exception {
        File source = new File(workspace, "books") {
            @Override public File[] listFiles() { throw new AssertionError("Unexpected inventory"); }
        };
        assertTrue(source.mkdir());
        FileRenamePlan plan = FileRenamePlan.capture(source);
        assertTrue(plan.isDirectory());
        assertTrue(plan.rename("renamed"));
    }

    @Test public void caseOnlyRenameStillWorks() throws Exception {
        File source = write("book.txt", "book");
        FileRenamePlan plan = FileRenamePlan.capture(source);
        assertTrue(plan.rename("Book.txt"));
        assertTrue(java.util.Arrays.asList(workspace.list()).contains("Book.txt"));
    }

    @Test public void missingSourceCannotBecomeAPlan() throws Exception {
        try {
            FileRenamePlan.capture(new File(workspace, "missing.txt"));
            fail("Missing source accepted");
        } catch (IOException expected) { }
    }

    @Test public void interruptionStopsRename() throws Exception {
        File source = write("book.txt", "book");
        FileRenamePlan plan = FileRenamePlan.capture(source);
        Thread.currentThread().interrupt();
        try {
            assertFalse(plan.rename("renamed.txt"));
            assertTrue(source.exists());
        } finally { Thread.interrupted(); }
    }

    private File write(String name, String text) throws IOException {
        File file = new File(workspace, name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
}
