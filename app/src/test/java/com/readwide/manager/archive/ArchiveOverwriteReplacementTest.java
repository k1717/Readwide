package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.Assume;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
        assertEquals("original", ArchiveTestTextFiles.readString(new File(destination, "old.txt").toPath()));
        assertFalse(backup.exists());
    }

    @Test public void backupRestoresByCopyWhenRenameFails() throws Exception {
        File backup = originalBackup();
        File destination = new File(tempFolder.getRoot(), "restored");
        restore(new File(backup.getAbsolutePath()) {
            @Override public boolean renameTo(File target) { return false; }
        }, destination);
        assertEquals("original", ArchiveTestTextFiles.readString(new File(destination, "old.txt").toPath()));
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
        assertEquals("original", ArchiveTestTextFiles.readString(new File(backup, "old.txt").toPath()));
        assertFalse(destination.exists());
    }

    @Test public void preflightFailureDoesNotDeleteConcurrentDestination() throws Exception {
        File archive = simpleZip();
        File destination = new File(tempFolder.getRoot(), "concurrent");
        FileOperationProgress progress = new FileOperationProgress("extract", snapshot -> {
            if (snapshot.totalBytes > 0) {
                assertTrue(destination.mkdir());
                try { ArchiveTestTextFiles.writeString(new File(destination, "unrelated.txt").toPath(), "keep"); }
                catch (IOException failure) { throw new AssertionError(failure); }
                throw new SecurityException("Injected preflight failure");
            }
        });
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                archive, destination, false, null, progress);
        assertFalse(result.success);
        assertEquals("keep", ArchiveTestTextFiles.readString(new File(destination, "unrelated.txt").toPath()));
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

    @Test public void uncheckedCallbackFailureCleansPartialOutputAndRetainsOriginalFailure() throws Exception {
        assertUncheckedCallbackCleanup(new IllegalStateException("Injected callback failure"));
    }

    @Test public void callbackErrorCleansPartialOutputAndRetainsOriginalFailure() throws Exception {
        assertUncheckedCallbackCleanup(new AssertionError("Injected callback error"));
    }

    private void assertUncheckedCallbackCleanup(Throwable failure) throws Exception {
        File archive = simpleZip();
        for (boolean overwrite : new boolean[]{false, true}) {
            File destination = overwrite ? originalBackup() : new File(tempFolder.getRoot(), "partial");
            boolean[] reachedOutput = {false};
            FileOperationProgress progress = new FileOperationProgress("extract", snapshot -> {
                if (snapshot.doneBytes > 0) {
                    reachedOutput[0] = true;
                    if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                    throw (Error) failure;
                }
            });
            try {
                ArchiveSupport.extractArchiveDetailed(archive, destination, overwrite, null, progress);
                fail("Expected original callback failure");
            } catch (RuntimeException | Error actual) {
                org.junit.Assert.assertSame(failure, actual);
            }
            assertTrue("Failure must occur after output progress", reachedOutput[0]);
            if (overwrite) {
                assertEquals("original", ArchiveTestTextFiles.readString(new File(destination, "old.txt").toPath()));
                assertFalse(new File(destination, "page.txt").exists());
            } else assertFalse(destination.exists());
            assertNoExtractionScratch();
        }
    }

    @Test public void cancellationDuringFinalSourceCheckPreventsReplacement() throws Exception {
        assertFinalSourceCheckCancellation(false);
    }

    @Test public void interruptionDuringFinalSourceCheckPreventsReplacement() throws Exception {
        assertFinalSourceCheckCancellation(true);
    }

    private void assertFinalSourceCheckCancellation(boolean interrupt) throws Exception {
        File archive = simpleZip();
        File original = originalBackup();
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        int[] canonicalChecks = {0};
        File destination = new File(original.getPath()) {
            @Override public File getCanonicalFile() throws IOException {
                File resolved = super.getCanonicalFile();
                // One preflight check, then the final check after successful decode.
                if (++canonicalChecks[0] == 2) {
                    if (interrupt) Thread.currentThread().interrupt();
                    else progress.cancel();
                }
                return resolved;
            }
        };
        try {
            ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                    archive, destination, true, null, progress);
            assertEquals(2, canonicalChecks[0]);
            assertFalse(result.success);
            assertTrue(result.detail, result.detail != null && result.detail.contains("cancelled"));
            if (interrupt) assertTrue(Thread.currentThread().isInterrupted());
            else assertTrue(progress.isCancelled());
            assertEquals("original", ArchiveTestTextFiles.readString(new File(original, "old.txt").toPath()));
            assertFalse(new File(original, "page.txt").exists());
            assertNoExtractionScratch();
        } finally {
            if (interrupt) Thread.interrupted();
        }
    }

    @Test public void malformedArchivePreflightPreservesExistingOverwriteTarget() throws Exception {
        File archive = tempFolder.newFile("bad.tar");
        Files.write(archive.toPath(), new byte[17]);
        File destination = originalBackup();
        assertFalse(ArchiveSupport.extractArchiveDetailed(archive, destination, true, null, null).success);
        assertEquals("original", ArchiveTestTextFiles.readString(new File(destination, "old.txt").toPath()));
    }

    @Test public void overwriteCannotReplaceTreeContainingSelectedArchive() throws Exception {
        File destination = originalBackup();
        File nested = new File(destination, "nested"); assertTrue(nested.mkdir());
        File archive = new File(nested, "input.zip");
        Files.copy(simpleZip().toPath(), archive.toPath());
        byte[] original = Files.readAllBytes(archive.toPath());
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                archive, destination, true, null, null);
        assertFalse(result.success);
        assertTrue(result.detail.contains("contains an archive source volume"));
        org.junit.Assert.assertArrayEquals(original, Files.readAllBytes(archive.toPath()));
        assertEquals("original", ArchiveTestTextFiles.readString(new File(destination, "old.txt").toPath()));
        assertNoExtractionScratch();
    }

    @Test public void overwriteCannotReplaceTheArchiveFileItself() throws Exception {
        File archive = simpleZip();
        byte[] original = Files.readAllBytes(archive.toPath());
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                archive, archive, true, null, null);
        assertFalse(result.success);
        assertTrue(result.detail.contains("contains an archive source volume"));
        org.junit.Assert.assertArrayEquals(original, Files.readAllBytes(archive.toPath()));
        assertNoExtractionScratch();
    }

    @Test public void continuationSelectionCannotReplaceItsSourceDirectory() throws Exception {
        for (String[] names : new String[][]{{"comic.lha", "comic.l01"},
                {"comic.7z.001", "comic.7z.002"}, {"comic.part1.rar", "comic.part2.rar"}}) {
            File directory = tempFolder.newFolder();
            File first = new File(directory, names[0]), second = new File(directory, names[1]);
            Files.write(first.toPath(), new byte[]{1}); Files.write(second.toPath(), new byte[]{2});
            ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                    second, directory, true, null, null);
            assertFalse(result.success);
            assertTrue(result.detail, result.detail.contains("contains an archive source volume"));
            org.junit.Assert.assertArrayEquals(new byte[]{1}, Files.readAllBytes(first.toPath()));
            org.junit.Assert.assertArrayEquals(new byte[]{2}, Files.readAllBytes(second.toPath()));
        }
        assertNoExtractionScratch();
    }

    @Test public void unresolvedVolumeSetCannotAuthorizeDestructiveReplacement() throws Exception {
        File archive = tempFolder.newFile("comic.7z.002");
        Files.write(archive.toPath(), new byte[]{2});
        File destination = originalBackup();
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                archive, destination, true, null, null);
        assertFalse(result.success);
        assertTrue(result.detail.contains("Cannot resolve archive sources"));
        assertEquals("original", ArchiveTestTextFiles.readString(new File(destination, "old.txt").toPath()));
        assertNoExtractionScratch();
    }

    @Test public void changedSourceStampPreventsFinalReplacementAndCleansStaging() throws Exception {
        File archive = simpleZip();
        File destination = originalBackup();
        boolean[] changed = {false};
        FileOperationProgress progress = new FileOperationProgress("extract", snapshot -> {
            if (!changed[0] && snapshot.totalBytes > 0) {
                changed[0] = true;
                assertTrue(archive.setLastModified(archive.lastModified() + 10000));
            }
        });
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                archive, destination, true, null, progress);
        assertTrue(changed[0]);
        assertFalse(result.success);
        // Either an earlier decoder identity check or the final public check may reject it.
        assertEquals("original", ArchiveTestTextFiles.readString(new File(destination, "old.txt").toPath()));
        assertNoExtractionScratch();
    }

    @Test public void replacementProtectsOtherVolumeBehindSiblingLink() throws Exception {
        File selected = tempFolder.newFile("comic.7z.001"); Files.write(selected.toPath(), new byte[]{1});
        File destination = originalBackup();
        File second = new File(destination, "second-part"); Files.write(second.toPath(), new byte[]{2});
        createLink(new File(tempFolder.getRoot(), "comic.7z.002"), second);
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                selected, destination, true, null, null);
        assertFalse(result.success);
        assertTrue(result.detail, result.detail.contains("contains an archive source volume"));
        org.junit.Assert.assertArrayEquals(new byte[]{2}, Files.readAllBytes(second.toPath()));
        assertEquals("original", ArchiveTestTextFiles.readString(new File(destination, "old.txt").toPath()));
        assertNoExtractionScratch();
    }

    @Test public void changedDestinationAliasIsRecheckedBeforeReplacement() throws Exception {
        File archive = simpleZip();
        File destination = originalBackup();
        File alias = new File(tempFolder.getRoot(), "destination-alias"); createLink(alias, destination);
        boolean[] changed = {false};
        FileOperationProgress progress = new FileOperationProgress("extract", snapshot -> {
            if (!changed[0] && snapshot.totalBytes > 0) {
                changed[0] = true;
                try {
                    Files.delete(alias.toPath());
                    Files.createSymbolicLink(alias.toPath(), archive.getParentFile().toPath());
                } catch (IOException error) { throw new AssertionError(error); }
            }
        });
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                archive, alias, true, null, progress);
        assertTrue(changed[0]);
        assertFalse(result.success);
        assertTrue(result.detail, result.detail.contains("contains an archive source volume"));
        assertTrue(archive.isFile());
        assertEquals("original", ArchiveTestTextFiles.readString(new File(destination, "old.txt").toPath()));
        assertNoExtractionScratch();
    }

    private void assertNoExtractionScratch() {
        File[] scratch = tempFolder.getRoot().listFiles((directory, name) -> name.startsWith(".textview_extract_"));
        assertTrue(scratch != null && scratch.length == 0);
    }

    private File originalBackup() throws Exception {
        File backup = tempFolder.newFolder("backup");
        ArchiveTestTextFiles.writeString(new File(backup, "old.txt").toPath(), "original");
        return backup;
    }

    @Test public void overwriteCleanupDoesNotFollowOldDirectoryLinks() throws Exception {
        File outside = tempFolder.newFolder("outside");
        File precious = new File(outside, "keep.txt");
        ArchiveTestTextFiles.writeString(precious.toPath(), "outside data");
        File destination = originalBackup();
        createLink(new File(destination, "link"), outside);
        assertTrue(ArchiveSupport.extractArchiveDetailed(simpleZip(), destination, true, null, null).success);
        assertEquals("outside data", ArchiveTestTextFiles.readString(precious.toPath()));
        assertTrue(new File(destination, "page.txt").isFile());
        assertFalse(Files.exists(new File(destination, "link").toPath(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test public void cleanupRemovesDanglingAndCyclicLinksWithoutFollowingThem() throws Exception {
        File root = tempFolder.newFolder("links");
        createLink(new File(root, "cycle"), root);
        createLink(new File(root, "dangling"), new File(tempFolder.getRoot(), "absent"));
        assertTrue(invokeBoolean("deleteFileSystemItem", root));
        assertFalse(root.exists());
    }

    @Test public void fallbackClearRefusesRootLinkAndPreservesRealRoot() throws Exception {
        File outside = tempFolder.newFolder("outside");
        File precious = new File(outside, "keep.txt");
        ArchiveTestTextFiles.writeString(precious.toPath(), "keep");
        File rootLink = new File(tempFolder.getRoot(), "root-link");
        createLink(rootLink, outside);
        assertFalse(invokeBoolean("clearDirectoryContents", rootLink));
        assertEquals("keep", ArchiveTestTextFiles.readString(precious.toPath()));
        assertTrue(Files.isSymbolicLink(rootLink.toPath()));
        File realRoot = tempFolder.newFolder("work");
        createLink(new File(realRoot, "nested-link"), outside);
        assertTrue(invokeBoolean("clearDirectoryContents", realRoot));
        assertTrue(realRoot.isDirectory());
        assertEquals(0, realRoot.list().length);
        assertEquals("keep", ArchiveTestTextFiles.readString(precious.toPath()));
    }

    @Test public void cleanupRejectsDirectoryReplacedDuringListing() throws Exception {
        File root = tempFolder.newFolder("root");
        File outside = tempFolder.newFolder("outside");
        File precious = new File(outside, "keep");
        ArchiveTestTextFiles.writeString(precious.toPath(), "outside");
        File probe = new File(tempFolder.getRoot(), "probe");
        createLink(probe, outside);
        Files.delete(probe.toPath());
        File changingRoot = new File(root.getPath()) {
            @Override public File[] listFiles() {
                try {
                    Files.delete(root.toPath());
                    Files.createSymbolicLink(root.toPath(), outside.toPath());
                } catch (IOException e) { throw new AssertionError(e); }
                return new File[]{new File(root, "keep")};
            }
        };
        try {
            assertFalse(invokeBoolean("deleteFileSystemItem", changingRoot));
            assertEquals("outside", ArchiveTestTextFiles.readString(precious.toPath()));
        } finally { Files.deleteIfExists(root.toPath()); }
    }

    @Test public void backupCopyRejectsLinksAndRetainsOriginalTree() throws Exception {
        File outside = tempFolder.newFile("outside");
        ArchiveTestTextFiles.writeString(outside.toPath(), "outside");
        File backup = originalBackup();
        createLink(new File(backup, "reference"), outside);
        File destination = new File(tempFolder.getRoot(), "restored");
        assertRestoreFailsAndRetainsBackup(backup, destination);
        assertFalse(destination.exists());
        assertTrue(Files.isSymbolicLink(new File(backup, "reference").toPath()));
        assertEquals("outside", ArchiveTestTextFiles.readString(outside.toPath()));
    }

    @Test public void backupCopyCannotMergeIntoConcurrentDestination() throws Exception {
        File backup = originalBackup();
        File destination = tempFolder.newFolder("restored");
        ArchiveTestTextFiles.writeString(new File(destination, "old.txt").toPath(), "concurrent");
        assertRestoreFailsAndRetainsBackup(backup, destination);
        assertEquals("concurrent", ArchiveTestTextFiles.readString(new File(destination, "old.txt").toPath()));
        File dangling = new File(tempFolder.getRoot(), "dangling-destination");
        createLink(dangling, new File(tempFolder.getRoot(), "absent"));
        try {
            restore(backup, dangling); // Exercise rename as well as the copy fallback.
            fail("Existing dangling destination must not be replaced");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains(backup.getAbsolutePath()));
        }
        assertTrue(Files.isSymbolicLink(dangling.toPath()));
        assertEquals("original", ArchiveTestTextFiles.readString(new File(backup, "old.txt").toPath()));
    }

    @Test public void backupCopyUsesSharedArchiveWriteBudget() throws Exception {
        File backup = originalBackup();
        File destination = new File(tempFolder.getRoot(), "restored");
        try (ArchiveExtractionByteBudget.Scope ignored = ArchiveExtractionByteBudget.begin(3)) {
            assertRestoreFailsAndRetainsBackup(backup, destination);
        }
        assertFalse(destination.exists());
        try (ArchiveExtractionByteBudget.Scope scope = ArchiveExtractionByteBudget.begin(8)) {
            restore(withoutRename(backup), destination);
            assertEquals(8L, scope.budgetForTest().totalBytesForTest());
        }
        assertEquals("original", ArchiveTestTextFiles.readString(new File(destination, "old.txt").toPath()));
        assertFalse(backup.exists());
    }

    @Test public void failedInstallationDoesNotDeleteConcurrentDestination() throws Exception {
        File destination = originalBackup();
        File replacement = tempFolder.newFolder("replacement");
        ArchiveTestTextFiles.writeString(new File(replacement, "new.txt").toPath(), "new");
        File contested = new File(replacement.getPath()) {
            @Override public boolean renameTo(File target) {
                assertTrue(target.mkdir());
                try { ArchiveTestTextFiles.writeString(new File(target, "concurrent.txt").toPath(), "keep"); }
                catch (IOException failure) { throw new AssertionError(failure); }
                return false;
            }
        };
        Method method = ArchiveSupport.class.getDeclaredMethod(
                "replaceExistingDirectoryWithTemp", File.class, File.class);
        method.setAccessible(true);
        try {
            method.invoke(null, destination, contested);
            fail("Expected retained-backup error");
        } catch (java.lang.reflect.InvocationTargetException failure) {
            assertTrue(failure.getCause() instanceof IOException);
        }
        assertEquals("keep", ArchiveTestTextFiles.readString(new File(destination, "concurrent.txt").toPath()));
        File[] backups = tempFolder.getRoot().listFiles((dir, name) -> name.startsWith(".textview_extract_backup_backup_"));
        assertEquals(1, backups.length);
        assertEquals("original", ArchiveTestTextFiles.readString(new File(backups[0], "old.txt").toPath()));
        assertFalse(replacement.exists());
    }

    @Test public void cancelledCopyRetainsBackupButCleanupStillRuns() throws Exception {
        File backup = originalBackup();
        File destination = new File(tempFolder.getRoot(), "restored");
        File scratch = tempFolder.newFolder("scratch");
        ArchiveTestTextFiles.writeString(new File(scratch, "partial").toPath(), "partial");
        Thread.currentThread().interrupt();
        try {
            try {
                restore(withoutRename(backup), destination);
                fail("Expected preserved-backup failure");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains(backup.getAbsolutePath()));
            }
            assertFalse(destination.exists());
            assertTrue(invokeBoolean("deleteFileSystemItem", scratch));
            assertFalse(scratch.exists());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals("original", ArchiveTestTextFiles.readString(new File(backup, "old.txt").toPath()));
    }

    @Test public void unresolvedOutputRootFailsClosed() throws Exception {
        File root = tempFolder.newFolder();
        File failedCanonical = new File(root.getPath()) {
            @Override public File getCanonicalFile() throws IOException { throw new IOException("unresolved"); }
        };
        File deniedCanonical = new File(root.getPath()) {
            @Override public File getCanonicalFile() { throw new SecurityException("denied"); }
        };
        assertEquals(null, ArchiveSupport.resolveArchiveEntryOutput(failedCanonical, "page.txt"));
        assertEquals(null, ArchiveSupport.resolveArchiveEntryOutput(deniedCanonical, "page.txt"));
        assertEquals(0, root.list().length);
    }

    private static File withoutRename(File file) {
        return new File(file.getPath()) {
            @Override public boolean renameTo(File target) { return false; }
        };
    }

    private static void assertRestoreFailsAndRetainsBackup(File backup, File destination) throws Exception {
        try {
            restore(withoutRename(backup), destination);
            fail("Expected preserved-backup failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains(backup.getAbsolutePath()));
        }
        assertEquals("original", ArchiveTestTextFiles.readString(new File(backup, "old.txt").toPath()));
    }

    private static boolean invokeBoolean(String name, File file) throws Exception {
        Method method = ArchiveSupport.class.getDeclaredMethod(name, File.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, file);
    }

    private static void createLink(File link, File target) throws IOException {
        try { Files.createSymbolicLink(link.toPath(), target.toPath()); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Host cannot create symbolic links", unavailable);
        }
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
