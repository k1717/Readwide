package com.textview.reader.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

public class FileSystemOpsTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

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
