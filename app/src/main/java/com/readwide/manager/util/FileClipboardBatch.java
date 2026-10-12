package com.readwide.manager.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.archive.ArchiveCancellation;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One worker's paste batch. Owns source inventory and just-in-time destination
 * selection, but never changes clipboard selection, queue membership or Android UI.
 * Run once through the serial file-operation executor; consume results after it ends.
 */
public final class FileClipboardBatch {
    private final FileClipboardController clipboard;
    private final List<FileClipboardController.PendingItem> items;
    private final File destinationDirectory;
    private final FileOperationProgress progress;
    private final List<Transfer> completed = new ArrayList<>();

    public FileClipboardBatch(@NonNull FileClipboardController clipboard,
                              @NonNull List<FileClipboardController.PendingItem> items,
                              @NonNull File destinationDirectory,
                              @NonNull FileOperationProgress progress) {
        this.clipboard = clipboard;
        this.items = new ArrayList<>(items);
        this.destinationDirectory = destinationDirectory;
        this.progress = progress;
    }

    public void run() {
        try {
            List<FileClipboardController.PendingItem> eligible = collectEligibleItems();
            if (!progress.checkpoint() || eligible.isEmpty()) return;
            if (eligible.size() > 1) {
                progress.setFolder(displayName(destinationDirectory));
                progress.setFolderProgress(1, eligible.size());
            }
            List<File> sources = new ArrayList<>(eligible.size());
            for (FileClipboardController.PendingItem item : eligible) sources.add(item.getSource());
            FileTreeProgressTracker tracker = FileTreeProgressTracker.create(progress, sources);
            progress.setTotalBytes(tracker.totalBytes());
            if (!tracker.isReady()) return;

            for (int index = 0; index < eligible.size(); index++) {
                if (!progress.checkpoint()) break;
                FileClipboardController.PendingItem item = eligible.get(index);
                // Earlier transfers may have occupied a name. Resolve from the
                // live directory, not from a list of precomputed destinations.
                File destination = resolveDestination(item);
                if (destination == null) continue;
                Transfer transfer = new Transfer(item, destination);
                if (eligible.size() > 1) {
                    progress.setFolder(displayName(transfer.sourceWasDirectory ? destination : destinationDirectory));
                    progress.setFolderProgress(index + 1, eligible.size());
                }
                if (clipboard.performOperation(item, destination, false, progress, false, tracker)) {
                    completed.add(transfer);
                }
            }
        } catch (RuntimeException failure) {
            // FileSystemOps owns staging/rollback. Keep earlier committed results;
            // cancellation is not an ordinary failure, and fatal Errors propagate.
            ArchiveCancellation.restoreIfCancelled(failure, progress);
        }
    }

    private List<FileClipboardController.PendingItem> collectEligibleItems() {
        List<FileClipboardController.PendingItem> eligible = new ArrayList<>();
        for (FileClipboardController.PendingItem item : items) {
            if (!progress.checkpoint()) break;
            FileClipboardController.PasteStatus status = clipboard.preparePaste(item, destinationDirectory).getStatus();
            if (status == FileClipboardController.PasteStatus.READY
                    || status == FileClipboardController.PasteStatus.CONFLICT) eligible.add(item);
        }
        return eligible;
    }

    @Nullable
    private File resolveDestination(FileClipboardController.PendingItem item) {
        FileClipboardController.PastePlan plan = clipboard.preparePaste(item, destinationDirectory);
        switch (plan.getStatus()) {
            case READY: return plan.getDestination();
            case CONFLICT: return clipboard.buildCopyDestination(destinationDirectory, item.getSource());
            default: return null; // Blocked/failed items stay queued for an explicit retry or cancel.
        }
    }

    /** Read only after the worker ends; no mutable result list escapes. */
    @NonNull
    public List<Transfer> completedTransfers() {
        return Collections.unmodifiableList(new ArrayList<>(completed));
    }

    public int totalCount() { return items.size(); }

    private static String displayName(File file) {
        String name = file.getName();
        return name.isEmpty() ? file.getAbsolutePath() : name;
    }

    /** Captures the metadata needed after a successful copy/move. */
    public static final class Transfer {
        public final FileClipboardController.PendingItem item;
        public final File source;
        public final File destination;
        public final boolean sourceWasDirectory;

        public Transfer(@NonNull FileClipboardController.PendingItem item, @NonNull File destination) {
            this.item = item;
            this.source = item.getSource();
            this.destination = destination;
            this.sourceWasDirectory = source.isDirectory();
        }
    }
}
