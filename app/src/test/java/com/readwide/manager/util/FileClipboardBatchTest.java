package com.readwide.manager.util;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileClipboardBatchTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final FileClipboardController clipboard = new FileClipboardController();

    @Test public void sameNamedFilesGetDistinctDestinationsInAnEmptyFolder() throws Exception {
        File first = source("a", "page.txt", "first"), second = source("b", "page.txt", "second");
        File destination = temp.newFolder("out");
        FileClipboardBatch batch = batch(destination, progress(), item(first, true), item(second, true));
        batch.run();
        assertEquals(2, batch.completedTransfers().size());
        assertText(new File(destination, "page.txt"), "first");
        assertText(new File(destination, "page (1).txt"), "second");
        assertTrue(first.exists()); assertTrue(second.exists());
    }

    @Test public void existingNamesAndNumberedNamesAreNeverOverwritten() throws Exception {
        File destination = temp.newFolder("out");
        write(new File(destination, "page.txt"), "old");
        write(new File(destination, "page (1).txt"), "old numbered");
        FileClipboardBatch batch = batch(destination, progress(),
                item(source("a", "page.txt", "a"), true), item(source("b", "page.txt", "b"), true));
        batch.run();
        assertEquals(2, batch.completedTransfers().size());
        assertText(new File(destination, "page.txt"), "old");
        assertText(new File(destination, "page (1).txt"), "old numbered");
        assertText(new File(destination, "page (2).txt"), "a");
        assertText(new File(destination, "page (3).txt"), "b");
    }

    @Test public void sameNamedDottedDirectoriesKeepTheirWholeNames() throws Exception {
        File first = temp.newFolder("a", "chapter.v1"), second = temp.newFolder("b", "chapter.v1");
        write(new File(first, "1.txt"), "a"); write(new File(second, "1.txt"), "b");
        File destination = temp.newFolder("out");
        FileClipboardBatch batch = batch(destination, progress(), item(first, true), item(second, true));
        batch.run();
        assertEquals(2, batch.completedTransfers().size());
        assertText(new File(destination, "chapter.v1/1.txt"), "a");
        assertText(new File(destination, "chapter.v1 (1)/1.txt"), "b");
    }

    @Test public void extensionlessFilesAreNumberedWithoutInventingAnExtension() throws Exception {
        File destination = temp.newFolder("out");
        FileClipboardBatch batch = batch(destination, progress(),
                item(source("a", "page", "a"), true), item(source("b", "page", "b"), true));
        batch.run();
        assertText(new File(destination, "page"), "a");
        assertText(new File(destination, "page (1)"), "b");
    }

    @Test public void copyAndMoveKeepTheirOwnModesAndCompletionPaths() throws Exception {
        File copy = source("a", "page.txt", "copy"), move = source("b", "page.txt", "move");
        File destination = temp.newFolder("out");
        FileClipboardBatch batch = batch(destination, progress(), item(copy, true), item(move, false));
        batch.run();
        assertTrue(copy.exists()); assertFalse(move.exists());
        assertText(new File(destination, "page.txt"), "copy");
        assertText(new File(destination, "page (1).txt"), "move");
        FileClipboardBatch.Transfer moved = batch.completedTransfers().get(1);
        assertEquals(move, moved.source); assertFalse(moved.item.isCopy());
        assertEquals(new File(destination, "page (1).txt"), moved.destination);
    }

    @Test public void actualCaseInsensitiveAliasesReceiveDifferentNames() throws Exception {
        File first = source("a", "Page.txt", "upper"), second = source("b", "page.txt", "lower");
        Assume.assumeTrue(new File(first.getParentFile(), "page.txt").exists());
        File destination = temp.newFolder("out");
        FileClipboardBatch batch = batch(destination, progress(), item(first, true), item(second, true));
        batch.run();
        assertEquals(2, batch.completedTransfers().size());
        assertText(new File(destination, "Page.txt"), "upper");
        assertText(new File(destination, "page (1).txt"), "lower");
    }

    @Test public void distinctCaseSensitiveNamesAreNotArtificiallyMerged() throws Exception {
        File first = source("a", "Page.txt", "upper"), second = source("b", "page.txt", "lower");
        Assume.assumeFalse(new File(first.getParentFile(), "page.txt").exists());
        File destination = temp.newFolder("out");
        FileClipboardBatch batch = batch(destination, progress(), item(first, true), item(second, true));
        batch.run();
        assertEquals(2, batch.completedTransfers().size());
        assertText(new File(destination, "Page.txt"), "upper");
        assertText(new File(destination, "page.txt"), "lower");
    }

    @Test public void cancelledBeforeStartCreatesNothingAndRetainsQueueItems() throws Exception {
        File source = source("a", "page.txt", "keep"), destination = temp.newFolder("out");
        FileClipboardController.PendingItem item = item(source, false);
        FileOperationProgress progress = progress(); progress.cancel();
        FileClipboardBatch batch = batch(destination, progress, item);
        batch.run();
        assertTrue(batch.completedTransfers().isEmpty());
        assertTrue(clipboard.isPending(item)); assertTrue(source.exists());
        assertEquals(0, destination.list().length);
    }

    @Test public void cancellationBeforeSecondTransferRetainsFirstCommittedResult() throws Exception {
        File destination = temp.newFolder("out");
        FileClipboardController.PendingItem first = item(source("a", "page.txt", "a"), true);
        FileClipboardController.PendingItem second = item(source("b", "page.txt", "b"), true);
        FileOperationProgress progress = progress();
        progress.setListener(snapshot -> { if (snapshot.folderIndex == 2 && !snapshot.cancelled) progress.cancel(); });
        FileClipboardBatch batch = batch(destination, progress, first, second);
        batch.run();
        assertTrue(progress.isCancelled()); assertEquals(1, batch.completedTransfers().size());
        assertSame(first, batch.completedTransfers().get(0).item);
        assertFalse(new File(destination, "page (1).txt").exists());
        retireCompleted(batch);
        assertFalse(clipboard.isPending(first)); assertTrue(clipboard.isPending(second));
    }

    @Test public void nameCreatedAfterInventoryIsRespectedBeforeTransfer() throws Exception {
        File destination = temp.newFolder("out"); boolean[] inserted = {false};
        FileOperationProgress progress = progress();
        progress.setListener(snapshot -> {
            if (snapshot.totalBytes > 0 && !inserted[0]) {
                inserted[0] = true; writeUnchecked(new File(destination, "page.txt"), "external");
            }
        });
        FileClipboardBatch batch = batch(destination, progress,
                item(source("a", "page.txt", "a"), true), item(source("b", "page.txt", "b"), true));
        batch.run();
        assertEquals(2, batch.completedTransfers().size());
        assertText(new File(destination, "page.txt"), "external");
        assertText(new File(destination, "page (1).txt"), "a");
        assertText(new File(destination, "page (2).txt"), "b");
    }

    @Test public void racingDestinationPreservesExternalFileAndLaterTransferCanContinue() throws Exception {
        File destination = temp.newFolder("out"); boolean[] inserted = {false};
        FileClipboardController.PendingItem first = item(source("a", "page.txt", "a"), true);
        FileClipboardController.PendingItem second = item(source("b", "page.txt", "b"), true);
        FileOperationProgress progress = progress();
        progress.setListener(snapshot -> {
            if (snapshot.doneBytes > 0 && !inserted[0]) {
                inserted[0] = true; writeUnchecked(new File(destination, "page.txt"), "external");
            }
        });
        FileClipboardBatch batch = batch(destination, progress, first, second);
        batch.run();
        assertEquals(1, batch.completedTransfers().size());
        assertSame(second, batch.completedTransfers().get(0).item);
        assertText(new File(destination, "page.txt"), "external");
        assertText(new File(destination, "page (1).txt"), "b");
        retireCompleted(batch);
        assertTrue(clipboard.isPending(first)); assertFalse(clipboard.isPending(second));
        assertEquals(2, destination.list().length); // No owned staging folder is left behind.
    }

    @Test public void blockedDestinationDoesNotSilentlyRemoveQueuedFolder() throws Exception {
        File tree = temp.newFolder("tree"), destination = new File(tree, "inside");
        assertTrue(destination.mkdir());
        FileClipboardController.PendingItem blocked = item(tree, true);
        FileClipboardController.PendingItem good = item(source("a", "page.txt", "good"), true);
        FileClipboardBatch batch = batch(destination, progress(), blocked, good);
        batch.run(); retireCompleted(batch);
        assertEquals(2, batch.totalCount()); assertEquals(1, batch.completedTransfers().size());
        assertTrue(clipboard.isPending(blocked)); assertFalse(clipboard.isPending(good));
        assertText(new File(destination, "page.txt"), "good");
    }

    @Test public void vanishedSourceStaysQueuedWhileOtherItemsCanComplete() throws Exception {
        File missing = source("a", "missing.txt", "missing");
        FileClipboardController.PendingItem retry = item(missing, true);
        FileClipboardController.PendingItem good = item(source("b", "good.txt", "good"), true);
        assertTrue(missing.delete());
        FileClipboardBatch batch = batch(temp.newFolder("out"), progress(), retry, good);
        batch.run(); retireCompleted(batch);
        assertTrue(clipboard.isPending(retry)); assertFalse(clipboard.isPending(good));
    }

    @Test public void nonfatalCallbackFailureKeepsCommittedResultsAndStopsSafely() throws Exception {
        File destination = temp.newFolder("out");
        FileOperationProgress progress = progress();
        progress.setListener(snapshot -> { if (snapshot.folderIndex == 2) throw new IllegalStateException("callback"); });
        FileClipboardBatch batch = batch(destination, progress,
                item(source("a", "page.txt", "a"), true), item(source("b", "page.txt", "b"), true));
        batch.run();
        assertEquals(1, batch.completedTransfers().size());
        assertText(new File(destination, "page.txt"), "a");
        assertFalse(new File(destination, "page (1).txt").exists());
    }

    @Test public void fatalCallbackFailurePropagatesWithoutLosingCommittedResults() throws Exception {
        File destination = temp.newFolder("out"); AssertionError failure = new AssertionError("fatal");
        FileOperationProgress progress = progress();
        progress.setListener(snapshot -> { if (snapshot.folderIndex == 2) throw failure; });
        FileClipboardBatch batch = batch(destination, progress,
                item(source("a", "page.txt", "a"), true), item(source("b", "page.txt", "b"), true));
        assertSame(failure, assertThrows(AssertionError.class, batch::run));
        assertEquals(1, batch.completedTransfers().size());
        assertFalse(new File(destination, "page (1).txt").exists());
    }

    @Test public void wrappedInterruptionUpdatesTokenAndPreservesEarlierCompletion() throws Exception {
        FileOperationProgress progress = progress();
        progress.setListener(snapshot -> {
            if (snapshot.folderIndex == 2) throw new UncheckedIOException(new InterruptedIOException("cancel"));
        });
        FileClipboardBatch batch = batch(temp.newFolder("out"), progress,
                item(source("a", "page.txt", "a"), true), item(source("b", "page.txt", "b"), true));
        try {
            batch.run();
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(progress.isCancelled());
            assertEquals(1, batch.completedTransfers().size());
        } finally { Thread.interrupted(); }
    }

    @Test public void inputSnapshotAndCompletionViewCannotBeMutatedByCaller() throws Exception {
        List<FileClipboardController.PendingItem> items = new ArrayList<>();
        items.add(item(source("a", "page.txt", "a"), true));
        FileClipboardBatch batch = new FileClipboardBatch(clipboard, items, temp.newFolder("out"), progress());
        items.clear(); batch.run();
        assertEquals(1, batch.totalCount()); assertEquals(1, batch.completedTransfers().size());
        assertThrows(UnsupportedOperationException.class, () -> batch.completedTransfers().clear());
    }

    @Test public void danglingOriginalAndNumberedLinksAreOccupiedNames() throws Exception {
        File destination = temp.newFolder("out"), source = source("a", "page.txt", "a");
        File original = new File(destination, "page.txt"), numbered = new File(destination, "page (1).txt");
        try {
            Files.createSymbolicLink(original.toPath(), new File(destination, "missing-one").toPath());
            Files.createSymbolicLink(numbered.toPath(), new File(destination, "missing-two").toPath());
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException(unavailable);
        }
        FileClipboardController.PendingItem item = item(source, true);
        assertEquals(FileClipboardController.PasteStatus.CONFLICT, clipboard.preparePaste(item, destination).getStatus());
        FileClipboardBatch batch = batch(destination, progress(), item);
        batch.run();
        assertEquals(1, batch.completedTransfers().size());
        assertTrue(Files.isSymbolicLink(original.toPath())); assertTrue(Files.isSymbolicLink(numbered.toPath()));
        assertText(new File(destination, "page (2).txt"), "a");
    }

    private FileClipboardBatch batch(File destination, FileOperationProgress progress,
                                     FileClipboardController.PendingItem... items) {
        return new FileClipboardBatch(clipboard, Arrays.asList(items), destination, progress);
    }

    private FileClipboardController.PendingItem item(File source, boolean copy) {
        assertEquals(FileClipboardController.StartResult.STARTED, clipboard.start(source, copy));
        return clipboard.getActiveItem();
    }

    private void retireCompleted(FileClipboardBatch batch) {
        List<FileClipboardController.PendingItem> items = new ArrayList<>();
        for (FileClipboardBatch.Transfer transfer : batch.completedTransfers()) items.add(transfer.item);
        clipboard.clearAfterSuccess(items);
    }

    private static FileOperationProgress progress() { return new FileOperationProgress("paste", null); }
    private File source(String directory, String name, String text) throws IOException {
        File file = new File(temp.newFolder(directory), name); write(file, text); return file;
    }
    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private static void writeUnchecked(File file, String text) {
        try { write(file, text); } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private static void assertText(File file, String text) throws IOException {
        assertEquals(text, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
}
