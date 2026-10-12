package com.readwide.manager.archive;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;

public class RarOutputFileGuardTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test public void directoryEntriesRejectFilesButAcceptExistingAndNewDirectories() throws Exception {
        File occupied = tempFolder.newFile();
        write(occupied, new byte[]{42});
        org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> RarOutputFileGuard.ensureDirectory(occupied));
        assertArrayEquals(new byte[]{42}, read(occupied));
        File directory = new File(tempFolder.getRoot(), "nested/empty");
        RarOutputFileGuard.ensureDirectory(directory);
        RarOutputFileGuard.ensureDirectory(directory);
        assertTrue(directory.isDirectory());
    }

    @Test public void directoryCreatedDuringMkdirRaceIsAccepted() throws Exception {
        File directory = new File(tempFolder.getRoot(), "race") {
            private boolean created;
            @Override public boolean isDirectory() { return created; }
            @Override public boolean mkdirs() { created = true; return false; }
        };
        RarOutputFileGuard.ensureDirectory(directory);
    }

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

    @Test public void existingTargetLinkIsRejectedWithoutChangingItsReferent() throws Exception {
        File outside = tempFolder.newFile("outside");
        write(outside, new byte[]{9});
        File link = new File(tempFolder.getRoot(), "output-link");
        createLink(link, outside);
        org.junit.Assert.assertThrows(IOException.class, () -> RarOutputFileGuard.forTarget(link));
        assertTrue(Files.isSymbolicLink(link.toPath()));
        assertArrayEquals(new byte[]{9}, read(outside));
    }

    @Test public void danglingTargetLinkCannotRedirectExtraction() throws Exception {
        File outside = new File(tempFolder.getRoot(), "missing-outside");
        File link = new File(tempFolder.getRoot(), "dangling-output");
        createLink(link, outside);
        org.junit.Assert.assertThrows(IOException.class, () -> RarOutputFileGuard.forTarget(link));
        assertTrue(Files.isSymbolicLink(link.toPath()));
        assertFalse(outside.exists());
    }

    @Test public void partialCleanupRemovesDanglingLinkWithoutFollowingIt() throws Exception {
        File outside = new File(tempFolder.getRoot(), "missing-outside");
        File link = new File(tempFolder.getRoot(), "dangling-partial");
        createLink(link, outside);
        RarOutputFileGuard.deletePartial(link);
        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS));
        assertFalse(outside.exists());
    }

    @Test public void substitutedBackupLinkCannotAuthorizeRollbackDeletion() throws Exception {
        File outside = tempFolder.newFile("outside"); write(outside, new byte[]{9});
        File out = tempFolder.newFile("output"); write(out, new byte[]{7});
        // Check host support before entering the guarded operation.
        File probe = new File(tempFolder.getRoot(), "probe"); createLink(probe, outside); Files.delete(probe.toPath());
        RarOutputFileGuard guard = RarOutputFileGuard.forTarget(out);
        java.lang.reflect.Field field = RarOutputFileGuard.class.getDeclaredField("backupFile");
        field.setAccessible(true);
        File backup = (File) field.get(guard);
        try {
            Files.delete(backup.toPath());
            Files.createSymbolicLink(backup.toPath(), outside.toPath());
            write(out, new byte[]{3});
            org.junit.Assert.assertThrows(IOException.class, guard::close);
            assertArrayEquals(new byte[]{3}, read(out));
            assertArrayEquals(new byte[]{9}, read(outside));
        } finally {
            Files.deleteIfExists(backup.toPath());
            write(backup, new byte[]{7});
            guard.close();
        }
        assertArrayEquals(new byte[]{7}, read(out));
    }

    private static void createLink(File link, File target) throws IOException {
        try { Files.createSymbolicLink(link.toPath(), target.toPath()); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.Assume.assumeNoException("Host cannot create symbolic links", unavailable);
        }
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
