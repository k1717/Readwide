package com.readwide.manager.util;

import static org.junit.Assert.*;

import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FileSingleDeletionPlanInstrumentedTest {
    private File workspace;

    @Before public void prepare() {
        workspace = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "single-delete-plan-" + UUID.randomUUID());
        assertTrue(workspace.mkdir());
    }

    @After public void cleanUp() { FileSystemOps.delete(workspace); }

    @Test public void unchangedRegularFileIsDeleted() throws Exception {
        File file = write(new File(workspace, "image.png"), "original");
        FileDeletionPlan plan = FileDeletionPlan.captureSingleFile(file);
        assertFalse(plan.isDirectory());
        assertEquals(8, plan.totalBytes());
        assertEquals(FileDeletionPlan.Result.DELETED, plan.delete(null, null));
        assertFalse(file.exists());
    }

    @Test public void replacementAtConfirmedImagePathIsPreserved() throws Exception {
        File file = write(new File(workspace, "image.png"), "original");
        FileDeletionPlan plan = FileDeletionPlan.captureSingleFile(file);
        assertTrue(file.renameTo(new File(workspace, "original.png")));
        write(file, "replacement");
        assertEquals(FileDeletionPlan.Result.CHANGED, plan.delete(null, null));
        assertTrue(file.exists());
    }

    @Test public void modifiedImageIsPreserved() throws Exception {
        File file = write(new File(workspace, "image.png"), "original");
        FileDeletionPlan plan = FileDeletionPlan.captureSingleFile(file);
        write(file, "modified image contents");
        assertEquals(FileDeletionPlan.Result.CHANGED, plan.delete(null, null));
        assertTrue(file.exists());
    }

    @Test public void imageReplacedByFolderDoesNotDeleteItsChildren() throws Exception {
        File file = write(new File(workspace, "image.png"), "original");
        FileDeletionPlan plan = FileDeletionPlan.captureSingleFile(file);
        assertTrue(file.renameTo(new File(workspace, "original.png")));
        assertTrue(file.mkdir());
        File child = write(new File(file, "new.txt"), "new");
        assertEquals(FileDeletionPlan.Result.CHANGED, plan.delete(null, null));
        assertTrue(child.exists());
    }

    @Test public void folderCaptureIsRejectedBeforeChildTraversal() throws Exception {
        File directory = new File(workspace, "folder");
        assertTrue(directory.mkdir());
        File child = write(new File(directory, "new.txt"), "new");
        File observed = new File(directory.getPath()) {
            @Override public File[] listFiles() { throw new AssertionError("Unexpected directory walk"); }
            @Override public String[] list() { throw new AssertionError("Unexpected child inventory"); }
        };
        try {
            FileDeletionPlan.captureSingleFile(observed);
            fail("Directory accepted as an image");
        } catch (IOException expected) {
            assertTrue(child.exists());
        }
    }

    @Test public void symlinkCaptureDoesNotFollowItsTarget() throws Exception {
        File target = write(new File(workspace, "original.png"), "original");
        File link = new File(workspace, "image.png");
        Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
        try {
            FileDeletionPlan.captureSingleFile(link);
            fail("Link accepted as a regular image");
        } catch (IOException expected) {
            assertTrue(target.exists());
            assertTrue(link.exists());
        }
    }

    private static File write(File file, String contents) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(contents.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
}
