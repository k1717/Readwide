package com.readwide.manager.util;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class FileTreeWalkTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void deleteDirectoryLinkDoesNotVisitItsTarget() throws Exception {
        File outside = temporary.newFolder("outside");
        File precious = new File(outside, "keep");
        Files.write(precious.toPath(), new byte[]{1, 2, 3});
        File root = temporary.newFolder("root");
        link(new File(root, "link"), outside);
        assertEquals(0L, FileSystemOps.measureBytes(root));
        FileOperationProgress progress = progress();
        FileTreeProgressTracker tracker = FileTreeProgressTracker.create(progress, root);
        assertTrue(tracker.isReady());
        assertEquals(1, tracker.totalFiles());
        assertEquals(1, tracker.totalFolders());
        assertTrue(FileSystemOps.delete(root, progress, true, tracker));
        assertFalse(root.exists());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(precious.toPath()));
    }

    @Test public void deletingARootLinkRemovesOnlyTheLink() throws Exception {
        File target = temporary.newFolder("target");
        File path = new File(temporary.getRoot(), "link");
        link(path, target);
        assertTrue(FileSystemOps.delete(path));
        assertTrue(target.exists());
        assertFalse(Files.exists(path.toPath(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test public void danglingLinkIsDeletedRatherThanIgnoredAsMissing() throws Exception {
        File path = new File(temporary.getRoot(), "dangling");
        link(path, new File(temporary.getRoot(), "missing"));
        assertFalse(path.exists());
        assertTrue(FileSystemOps.deleteAll(Arrays.asList(path), progress()));
        assertFalse(Files.exists(path.toPath(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test public void linkCycleIsALeafAndCannotRecurseForever() throws Exception {
        File root = temporary.newFolder("root");
        link(new File(root, "self"), root);
        assertEquals(0L, FileSystemOps.measureBytes(root));
        assertTrue(FileSystemOps.delete(root));
        assertFalse(root.exists());
    }

    @Test public void copyRejectsContainedLinksAndKeepsExistingDestination() throws Exception {
        File root = temporary.newFolder("source");
        File outside = temporary.newFile("outside");
        File destination = temporary.newFile("destination");
        Files.write(destination.toPath(), new byte[]{7});
        link(new File(root, "link"), outside);
        assertFalse(FileSystemOps.copy(root, destination, true, progress()));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(destination.toPath()));
        assertTrue(outside.exists());
        assertEquals(0, temporary.getRoot().listFiles((dir, name) -> name.startsWith(".rwtransfer_")).length);
    }

    @Test public void danglingDestinationLinkCannotBeOverwritten() throws Exception {
        File source = temporary.newFile("source");
        File destination = new File(temporary.getRoot(), "destination");
        link(destination, new File(temporary.getRoot(), "absent"));
        assertFalse(FileSystemOps.copy(source, destination, true));
        assertFalse(FileSystemOps.move(source, destination, true, progress()));
        assertTrue(Files.isSymbolicLink(destination.toPath()));
        assertTrue(source.exists());
    }

    @Test public void preCancelledInventoryDoesNotListTheRoot() throws Exception {
        AtomicInteger listings = new AtomicInteger();
        File root = counted(temporary.newFolder(), listings);
        FileOperationProgress progress = progress();
        progress.cancel();
        assertFalse(FileTreeProgressTracker.create(progress, root).isReady());
        assertEquals(0, listings.get());
    }

    @Test public void inventoryCancellationStopsBeforeProcessingChildren() throws Exception {
        File actual = temporary.newFolder();
        Files.write(new File(actual, "keep").toPath(), new byte[]{1});
        FileOperationProgress progress = progress();
        File root = new File(actual.getPath()) {
            @Override public File[] listFiles() { progress.cancel(); return super.listFiles(); }
        };
        assertFalse(FileTreeProgressTracker.create(progress, root).isReady());
        assertTrue(new File(actual, "keep").exists());
    }

    @Test public void oneInventoryComputesBytesFilesAndFolders() throws Exception {
        AtomicInteger listings = new AtomicInteger();
        File root = counted(temporary.newFolder(), listings);
        Files.write(new File(root, "a").toPath(), new byte[7]);
        File sub = new File(root, "sub");
        assertTrue(sub.mkdir());
        Files.write(new File(sub, "b").toPath(), new byte[9]);
        FileTreeProgressTracker tracker = FileTreeProgressTracker.create(progress(), root);
        assertTrue(tracker.isReady());
        assertEquals(16L, tracker.totalBytes());
        assertEquals(2, tracker.totalFiles());
        assertEquals(2, tracker.totalFolders());
        assertEquals(1, listings.get());
    }

    @Test public void unreadableInventoryCannotStartAMove() throws Exception {
        File actual = temporary.newFolder();
        File source = new File(actual.getPath()) {
            @Override public File[] listFiles() { return null; }
        };
        File destination = new File(temporary.getRoot(), "moved");
        assertFalse(FileSystemOps.move(source, destination, false, progress()));
        assertTrue(source.exists());
        assertFalse(destination.exists());
    }

    @Test public void directoryReplacedDuringVisitIsRejected() throws Exception {
        File root = temporary.newFolder("root");
        File outside = temporary.newFolder("outside");
        // Verify link support before mutating the walked directory.
        File probe = new File(temporary.getRoot(), "probe");
        link(probe, outside);
        Files.delete(probe.toPath());
        try {
            FileTreeWalk.walk(root, () -> true, entry -> {
                Files.delete(root.toPath());
                Files.createSymbolicLink(root.toPath(), outside.toPath());
                return true;
            });
            fail("Replacement directory was followed");
        } catch (IOException expected) {
            assertTrue(outside.exists());
        } finally { Files.deleteIfExists(root.toPath()); }
    }

    @Test public void iterativeWalkVisitsChildrenBeforeLeavingDirectory() throws Exception {
        File root = temporary.newFolder();
        Files.write(new File(root, "a").toPath(), new byte[1]);
        StringBuilder order = new StringBuilder();
        assertTrue(FileTreeWalk.walk(root, () -> true, new FileTreeWalk.Visitor() {
            @Override public boolean enter(FileTreeWalk.Entry entry) {
                order.append(entry.kind == FileTreeWalk.Kind.DIRECTORY ? "D" : "F"); return true;
            }
            @Override public boolean leave(FileTreeWalk.Entry directory) { order.append("E"); return true; }
        }));
        assertEquals("DFE", order.toString());
    }

    @Test public void unpausedProgressObservesThreadInterruption() {
        FileOperationProgress progress = progress();
        Thread.currentThread().interrupt();
        try {
            assertFalse(progress.checkpoint());
            assertTrue(progress.isCancelled());
            assertTrue(progress.isComplete());
        } finally { Thread.interrupted(); }
    }

    @Test public void progressBytesSaturateInsteadOfWrappingNegative() {
        FileOperationProgress progress = progress();
        progress.addDoneBytes(Long.MAX_VALUE - 1);
        progress.addDoneBytes(9);
        assertEquals(Long.MAX_VALUE, progress.snapshot().doneBytes);
    }

    private FileOperationProgress progress() { return new FileOperationProgress("operation", null); }
    private static File counted(File root, AtomicInteger listings) {
        return new File(root.getPath()) {
            @Override public File[] listFiles() { listings.incrementAndGet(); return super.listFiles(); }
        };
    }
    private static void link(File path, File target) throws IOException {
        try { Files.createSymbolicLink(path.toPath(), target.toPath()); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Host cannot create symbolic links", unavailable);
        }
    }
}
