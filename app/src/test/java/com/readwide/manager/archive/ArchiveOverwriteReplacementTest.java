package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.readwide.manager.util.FileOperationProgress;

public class ArchiveOverwriteReplacementTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test public void backupRestoresByRename() throws Exception {
        File backup = originalBackup();
        File destination = new File(tempFolder.getRoot(), "restored");
        restore(backup, destination);
        assertEquals("original", Files.readString(new File(destination, "old.txt").toPath()));
        assertFalse(backup.exists());
    }

    @Test public void backupRestoresByCopyWhenRenameFails() throws Exception {
        File backup = originalBackup();
        File destination = new File(tempFolder.getRoot(), "restored");
        restore(new File(backup.getAbsolutePath()) {
            @Override public boolean renameTo(File target) { return false; }
        }, destination);
        assertEquals("original", Files.readString(new File(destination, "old.txt").toPath()));
        assertFalse(backup.exists());
    }

    @Test public void failedRenameAndPartialCopyRetainBackupAndReportRecoveryPath() throws Exception {
        File backup = originalBackup();
        File destination = new File(tempFolder.getRoot(), "restored");
        try {
            restore(new File(backup.getAbsolutePath()) {
                @Override public boolean renameTo(File target) { return false; }
                @Override public File[] listFiles() {
                    return new File[]{new File(backup, "old.txt"), new File(backup, "unreadable.txt")};
                }
            }, destination);
            fail("Expected retained-backup error");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains(backup.getAbsolutePath()));
        }
        assertEquals("original", Files.readString(new File(backup, "old.txt").toPath()));
        assertFalse(destination.exists());
    }

    @Test public void preflightFailureDoesNotDeleteConcurrentDestination() throws Exception {
        File archive = simpleZip();
        File destination = new File(tempFolder.getRoot(), "concurrent");
        FileOperationProgress progress = new FileOperationProgress("extract", snapshot -> {
            if (snapshot.totalBytes > 0) {
                assertTrue(destination.mkdir());
                try { Files.writeString(new File(destination, "unrelated.txt").toPath(), "keep"); }
                catch (IOException failure) { throw new AssertionError(failure); }
                throw new SecurityException("Injected preflight failure");
            }
        });
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                archive, destination, false, null, progress);
        assertFalse(result.success);
        assertEquals("keep", Files.readString(new File(destination, "unrelated.txt").toPath()));
    }

    @Test public void failureAfterWorkDirectoryCreationStillCleansOwnedDirectory() throws Exception {
        File archive = simpleZip();
        File destination = new File(tempFolder.getRoot(), "owned");
        FileOperationProgress progress = new FileOperationProgress("extract", snapshot -> {
            if (destination.exists()) throw new SecurityException("Injected extraction failure");
        });
        assertFalse(ArchiveSupport.extractArchiveDetailed(archive, destination, false, null, progress).success);
        assertFalse(destination.exists());
    }

    @Test public void malformedArchivePreflightPreservesExistingOverwriteTarget() throws Exception {
        File archive = tempFolder.newFile("bad.tar");
        Files.write(archive.toPath(), new byte[17]);
        File destination = originalBackup();
        assertFalse(ArchiveSupport.extractArchiveDetailed(archive, destination, true, null, null).success);
        assertEquals("original", Files.readString(new File(destination, "old.txt").toPath()));
    }

    private File originalBackup() throws Exception {
        File backup = tempFolder.newFolder("backup");
        Files.writeString(new File(backup, "old.txt").toPath(), "original");
        return backup;
    }

    private File simpleZip() throws Exception {
        File archive = tempFolder.newFile("pages.zip");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("page.txt"));
            output.write(new byte[]{1, 2, 3});
            output.closeEntry();
        }
        return archive;
    }

    private static void restore(File backup, File destination) throws Exception {
        Method method = ArchiveSupport.class.getDeclaredMethod("restoreDirectoryBackup", File.class, File.class);
        method.setAccessible(true);
        try { method.invoke(null, backup, destination); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw failure;
        }
    }

    @Test
    public void replaceExistingDirectoryWithTempInstallsNewTreeAndRemovesBackup() throws Exception {
        File parent = tempFolder.getRoot();
        File destination = new File(parent, "book");
        File oldFile = new File(destination, "old.txt");
        File temp = new File(parent, ".book_extract_test");
        File newFile = new File(temp, "new.txt");

        assertTrue(destination.mkdirs());
        Files.write(oldFile.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        assertTrue(temp.mkdirs());
        Files.write(newFile.toPath(), "new".getBytes(StandardCharsets.UTF_8));

        Method method = ArchiveSupport.class.getDeclaredMethod(
                "replaceExistingDirectoryWithTemp", File.class, File.class);
        method.setAccessible(true);
        boolean ok = (Boolean) method.invoke(null, destination, temp);

        assertTrue(ok);
        assertFalse(temp.exists());
        assertFalse(oldFile.exists());
        File installed = new File(destination, "new.txt");
        assertTrue(installed.isFile());
        assertEquals("new", new String(Files.readAllBytes(installed.toPath()), StandardCharsets.UTF_8));
        File[] backups = parent.listFiles(file -> file.getName().contains("backup"));
        assertTrue(backups == null || backups.length == 0);
    }
}
