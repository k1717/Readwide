package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class RarOutputFileGuardTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void closeWithoutCommitDeletesPartialOutput() throws Exception {
        File out = new File(tempFolder.getRoot(), "partial.bin");

        try (RarOutputFileGuard ignored = RarOutputFileGuard.forTarget(out)) {
            write(out, new byte[] {1, 2, 3});
            assertTrue(out.exists());
        }

        assertFalse(out.exists());
    }

    @Test
    public void commitKeepsFinishedOutput() throws Exception {
        File out = new File(tempFolder.getRoot(), "done.bin");

        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(out)) {
            write(out, new byte[] {4, 5, 6});
            guard.commit();
        }

        assertTrue(out.exists());
        assertArrayEquals(new byte[] {4, 5, 6}, read(out));
    }

    @Test
    public void guardCreatesParentDirectory() throws Exception {
        File out = new File(new File(tempFolder.getRoot(), "nested"), "file.bin");

        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(out)) {
            write(out, new byte[] {7});
            guard.commit();
        }

        assertTrue(out.exists());
    }

    @Test
    public void closeWithoutCommitRestoresExistingOutput() throws Exception {
        File out = new File(tempFolder.getRoot(), "existing.bin");
        write(out, new byte[] {9, 8, 7});

        try (RarOutputFileGuard ignored = RarOutputFileGuard.forTarget(out)) {
            write(out, new byte[] {1, 2, 3});
            assertArrayEquals(new byte[] {1, 2, 3}, read(out));
        }

        assertTrue(out.exists());
        assertArrayEquals(new byte[] {9, 8, 7}, read(out));
    }

    @Test
    public void commitReplacesExistingOutput() throws Exception {
        File out = new File(tempFolder.getRoot(), "replace.bin");
        write(out, new byte[] {0});

        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(out)) {
            write(out, new byte[] {5, 4});
            guard.commit();
        }

        assertTrue(out.exists());
        assertArrayEquals(new byte[] {5, 4}, read(out));
    }

    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    private static byte[] read(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
