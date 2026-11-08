package com.readwide.manager.util;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileTransferParentTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void parentRedirectedIntoSourceBeforeStagingCannotCreateRecursiveCopy() throws Exception {
        File source = temp.newFolder("source");
        File parent = temp.newFolder("target");
        File retired = new File(temp.getRoot(), "retired");
        File destination = new File(parent, "copy");
        try {
            assertFalse(FileSystemOps.copyWithSourceCheck(source, destination, false,
                    null, true, null, () -> redirect(parent, retired, source)));
            assertTrue(source.isDirectory());
            assertArrayEquals(new String[0], source.list());
            assertTrue(retired.isDirectory());
        } finally {
            removeLink(parent);
        }
    }

    @Test public void parentRedirectedBeforeFastMoveCannotMoveSourceIntoItself() throws Exception {
        File source = temp.newFolder("source");
        File parent = temp.newFolder("target");
        File retired = new File(temp.getRoot(), "retired");
        try {
            assertEquals(FileSystemOps.TransferResult.FAILED, FileSystemOps.moveWithResult(
                    source, new File(parent, "moved"), false, null, true, null,
                    () -> redirect(parent, retired, source)));
            assertTrue(source.isDirectory());
            assertArrayEquals(new String[0], source.list());
        } finally {
            removeLink(parent);
        }
    }

    @Test public void changedParentDoesNotSendCleanupIntoAnotherTree() throws Exception {
        File original = temp.newFolder("source");
        File parent = temp.newFolder("target");
        File retired = new File(temp.getRoot(), "retired");
        File other = temp.newFolder("other");
        File[] foreignMarker = {null};
        File source = new File(original.getPath()) {
            @Override public File[] listFiles() {
                File[] transactions = parent.listFiles(file -> file.getName().startsWith(".rwtransfer_"));
                assertNotNull(transactions);
                assertEquals(1, transactions.length);
                try {
                    redirect(parent, retired, other);
                    File foreignStaged = new File(other, transactions[0].getName() + "/replacement");
                    assertTrue(foreignStaged.mkdirs());
                    foreignMarker[0] = new File(foreignStaged, "foreign");
                    assertTrue(foreignMarker[0].mkdir());
                } catch (IOException failure) { throw new AssertionError(failure); }
                return new File[0];
            }
        };
        try {
            assertFalse(FileSystemOps.copy(source, new File(parent, "copy"), false));
            assertNotNull(foreignMarker[0]);
            assertTrue(foreignMarker[0].isDirectory());
            assertTrue(retired.isDirectory());
        } finally {
            removeLink(parent);
        }
    }

    @Test public void unchangedSymbolicParentStillAllowsCopy() throws Exception {
        File source = temp.newFolder("source");
        File actualParent = temp.newFolder("target");
        File linkedParent = new File(temp.getRoot(), "linked");
        try {
            link(linkedParent, actualParent);
            assertTrue(FileSystemOps.copy(source, new File(linkedParent, "copy"), false));
            assertTrue(new File(actualParent, "copy").isDirectory());
        } finally {
            removeLink(linkedParent);
        }
    }

    private static void redirect(File parent, File retired, File target) throws IOException {
        assertTrue(parent.renameTo(retired));
        link(parent, target);
    }

    private static void link(File link, File target) throws IOException {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath());
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Host must allow directory symbolic links", unavailable);
        }
    }

    private static void removeLink(File link) throws IOException {
        if (Files.isSymbolicLink(link.toPath())) Files.delete(link.toPath());
    }
}
