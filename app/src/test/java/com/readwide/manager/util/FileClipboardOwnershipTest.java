package com.readwide.manager.util;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Queue identity regressions; no Activity or Android runtime is required. */
public class FileClipboardOwnershipTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void confirmedCopyDoesNotBecomeCutWhenActiveItemChanges() throws Exception {
        File source = temp.newFile("source.txt");
        Files.write(source.toPath(), "payload".getBytes(StandardCharsets.UTF_8));
        File destination = new File(temp.getRoot(), "copy.txt");
        FileClipboardController clipboard = new FileClipboardController();
        clipboard.start(source, true);
        FileClipboardController.PendingItem copy = clipboard.getActiveItem();
        clipboard.start(source, false);
        FileClipboardController.PendingItem cut = clipboard.getActiveItem();
        assertTrue(clipboard.performOperation(copy, destination, false, null, true));
        assertTrue(source.isFile());
        assertArrayEquals(Files.readAllBytes(source.toPath()), Files.readAllBytes(destination.toPath()));
        clipboard.clearAfterSuccess(copy);
        assertSame(cut, clipboard.getActiveItem());
        assertTrue(clipboard.isPending(cut));
        assertFalse(clipboard.isPending(copy));
    }

    @Test public void completingCapturedItemDoesNotRemoveNewActiveItem() throws Exception {
        FileClipboardController clipboard = new FileClipboardController();
        clipboard.start(temp.newFile("first"), true);
        FileClipboardController.PendingItem first = clipboard.getActiveItem();
        clipboard.start(temp.newFile("second"), true);
        FileClipboardController.PendingItem second = clipboard.getActiveItem();
        clipboard.setItemsInProgress(Collections.singletonList(first), true);
        clipboard.clearAfterSuccess(first);
        assertSame(second, clipboard.getActiveItem());
        assertEquals(Collections.singletonList(second), clipboard.getPendingItems());
        assertFalse(clipboard.isInProgress());
    }

    @Test public void clearingQueuePreservesClaimedItemsUntilWorkerReleasesThem() throws Exception {
        FileClipboardController clipboard = new FileClipboardController();
        clipboard.start(temp.newFile("claimed"), true);
        FileClipboardController.PendingItem claimed = clipboard.getActiveItem();
        clipboard.start(temp.newFile("idle"), false);
        clipboard.setItemsInProgress(Collections.singletonList(claimed), true);
        clipboard.cancel();
        assertEquals(Collections.singletonList(claimed), clipboard.getPendingItems());
        assertSame(claimed, clipboard.getActiveItem());
        assertTrue(clipboard.isInProgress());
        assertEquals(FileClipboardController.StartResult.INVALID_SOURCE,
                clipboard.start(temp.newFile("third"), true));
        clipboard.setItemsInProgress(Collections.singletonList(claimed), false);
        clipboard.cancel();
        assertFalse(clipboard.hasPending());
        assertNull(clipboard.getActiveItem());
    }

    @Test public void staleCancelCannotDropRunningItemOrItsBusyFlag() throws Exception {
        FileClipboardController clipboard = new FileClipboardController();
        clipboard.start(temp.newFile("running"), true);
        FileClipboardController.PendingItem item = clipboard.getActiveItem();
        clipboard.setItemsInProgress(Collections.singletonList(item), true);
        clipboard.cancel(item.getId());
        assertTrue(clipboard.isPending(item));
        assertTrue(clipboard.isInProgress());
        clipboard.setItemsInProgress(Collections.singletonList(item), false);
        clipboard.cancel(item.getId());
        assertFalse(clipboard.hasPending());
    }

    @Test public void replacedSameSourceItemCannotValidateOldConfirmation() throws Exception {
        FileClipboardController clipboard = new FileClipboardController();
        File source = temp.newFile("source");
        clipboard.start(source, true);
        FileClipboardController.PendingItem old = clipboard.getActiveItem();
        clipboard.start(source, true);
        FileClipboardController.PendingItem replacement = clipboard.getActiveItem();
        assertNotSame(old, replacement);
        assertFalse(clipboard.isPending(old));
        assertTrue(clipboard.isPending(replacement));
        clipboard.clearAfterSuccess(old);
        assertSame(replacement, clipboard.getActiveItem());
    }

    @Test public void delayedBatchOnlyKeepsStillPendingIdentities() throws Exception {
        FileClipboardController clipboard = new FileClipboardController();
        clipboard.start(temp.newFile("first"), true);
        FileClipboardController.PendingItem first = clipboard.getActiveItem();
        clipboard.start(temp.newFile("second"), true);
        List<FileClipboardController.PendingItem> confirmed = new ArrayList<>(clipboard.getPendingItems());
        clipboard.cancel(first.getId());
        clipboard.start(temp.newFile("new"), true);
        FileClipboardController.PendingItem later = clipboard.getActiveItem();
        confirmed.retainAll(clipboard.getPendingItems());
        assertEquals(1, confirmed.size());
        assertFalse(confirmed.contains(first));
        assertFalse(confirmed.contains(later));
        clipboard.setItemsInProgress(confirmed, true);
        assertFalse(later.isInProgress());
    }

    @Test public void overwriteCheckUsesConfirmedSourceNotCurrentSelection() throws Exception {
        FileClipboardController clipboard = new FileClipboardController();
        File source = temp.newFile("source");
        File target = temp.newFile("target");
        clipboard.start(source, true);
        FileClipboardController.PendingItem confirmed = clipboard.getActiveItem();
        clipboard.start(target, false);
        assertFalse(clipboard.canOverwrite(target));
        assertTrue(clipboard.canOverwrite(confirmed, target));
    }

    @Test public void releasingOldClaimCannotUnlockAnotherClaimedItem() throws Exception {
        FileClipboardController clipboard = new FileClipboardController();
        clipboard.start(temp.newFile("first"), true);
        FileClipboardController.PendingItem first = clipboard.getActiveItem();
        clipboard.start(temp.newFile("second"), true);
        FileClipboardController.PendingItem second = clipboard.getActiveItem();
        clipboard.setItemsInProgress(Collections.singletonList(first), true);
        clipboard.setItemsInProgress(Collections.singletonList(second), true);
        clipboard.setItemsInProgress(Collections.singletonList(first), false);
        assertTrue(second.isInProgress());
        assertTrue(clipboard.isInProgress());
        clipboard.clearAfterSuccess(first);
        assertTrue(clipboard.isInProgress());
    }

    @Test public void foreignItemsWithSameNumericIdCannotClaimLocalQueue() throws Exception {
        FileClipboardController local = new FileClipboardController();
        FileClipboardController other = new FileClipboardController();
        local.start(temp.newFile("local"), true);
        other.start(temp.newFile("foreign"), true);
        assertEquals(local.getActiveItem().getId(), other.getActiveItem().getId());
        local.setItemsInProgress(Collections.singletonList(other.getActiveItem()), true);
        assertFalse(local.isInProgress());
        local.clearAfterSuccess(other.getActiveItem());
        assertTrue(local.hasPending());
    }

    @Test public void duplicateAndNullBatchTargetsOnlyChangeTheirPendingItem() throws Exception {
        FileClipboardController clipboard = new FileClipboardController();
        clipboard.start(temp.newFile("one"), true);
        FileClipboardController.PendingItem one = clipboard.getActiveItem();
        clipboard.start(temp.newFile("two"), true);
        FileClipboardController.PendingItem two = clipboard.getActiveItem();
        List<FileClipboardController.PendingItem> targets = java.util.Arrays.asList(one, null, one);
        clipboard.setItemsInProgress(targets, true);
        assertTrue(one.isInProgress());
        assertFalse(two.isInProgress());
        clipboard.setItemsInProgress(targets, false);
        assertFalse(clipboard.isInProgress());
    }
}
