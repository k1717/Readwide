package com.readwide.manager.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class FileDeleteOutcomeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void ordinaryFileDeletionRemovesTheSource() throws Exception {
        File file = temp.newFile("book.txt");
        Files.writeString(file.toPath(), "book");
        assertTrue(FileSystemOps.delete(file, new FileOperationProgress("delete", null)));
        assertFalse(file.exists());
    }

    @Test public void deniedDeleteReportsFailureAndRetainsTheSource() throws Exception {
        File file = temp.newFile("protected.txt");
        assertFalse(FileSystemOps.delete(denied(file), new FileOperationProgress("delete", null)));
        assertTrue(file.exists());
    }

    @Test public void cancellationDoesNotDeleteTheSource() throws Exception {
        File file = temp.newFile("book.txt");
        FileOperationProgress progress = new FileOperationProgress("delete", null);
        progress.cancel();
        assertFalse(FileSystemOps.delete(file, progress));
        assertTrue(file.exists());
    }

    @Test public void partiallyDeletedFolderRemainsAFailure() throws Exception {
        File directory = temp.newFolder("books");
        File first = new File(directory, "first.txt");
        File second = new File(directory, "protected.txt");
        assertTrue(first.createNewFile());
        assertTrue(second.createNewFile());
        File ordered = new File(directory.getPath()) {
            @Override public File[] listFiles() { return new File[]{first, denied(second)}; }
        };
        assertFalse(FileSystemOps.delete(ordered, new FileOperationProgress("delete", null)));
        assertFalse(first.exists());
        assertTrue(second.exists());
        assertTrue(directory.exists());
    }

    @Test public void failedDeleteDoesNotTouchAnotherSelection() throws Exception {
        File failed = temp.newFile("sd.txt");
        File other = temp.newFile("internal.txt");
        assertFalse(FileSystemOps.delete(denied(failed)));
        assertTrue(failed.exists());
        assertTrue(other.exists());
    }

    private static File denied(File file) {
        return new File(file.getPath()) {
            @Override public boolean delete() { return false; }
        };
    }
}
