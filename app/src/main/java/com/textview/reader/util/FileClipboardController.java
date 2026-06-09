package com.textview.reader.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * State and validation for main-screen cut/copy/paste file operations.
 *
 * This controller intentionally has no Android UI dependency. MainActivity owns
 * dialogs, toasts, navigation, bookmark rebinding, and visible-list refreshes.
 */
public final class FileClipboardController {

    private static final FileClipboardController SHARED = new FileClipboardController();

    @NonNull
    public static FileClipboardController getShared() {
        return SHARED;
    }

    public enum StartResult {
        STARTED,
        INVALID_SOURCE
    }

    public enum PasteStatus {
        NO_SOURCE,
        SOURCE_UNAVAILABLE,
        DESTINATION_UNAVAILABLE,
        CUT_SAME_FOLDER,
        DIRECTORY_INTO_SELF,
        CONFLICT,
        READY
    }

    public static final class PendingItem {
        private final long id;
        private final File source;
        private final boolean copy;
        private boolean inProgress;

        private PendingItem(long id, @NonNull File source, boolean copy) {
            this.id = id;
            this.source = source;
            this.copy = copy;
        }

        public long getId() {
            return id;
        }

        @NonNull
        public File getSource() {
            return source;
        }

        public boolean isCopy() {
            return copy;
        }

        public boolean isInProgress() {
            return inProgress;
        }
    }

    public static final class PastePlan {
        private final PasteStatus status;
        private final File source;
        private final File destinationDir;
        private final File destination;

        private PastePlan(@NonNull PasteStatus status,
                          @Nullable File source,
                          @Nullable File destinationDir,
                          @Nullable File destination) {
            this.status = status;
            this.source = source;
            this.destinationDir = destinationDir;
            this.destination = destination;
        }

        @NonNull
        public PasteStatus getStatus() {
            return status;
        }

        @Nullable
        public File getSource() {
            return source;
        }

        @Nullable
        public File getDestinationDir() {
            return destinationDir;
        }

        @Nullable
        public File getDestination() {
            return destination;
        }
    }

    private final List<PendingItem> pendingItems = new ArrayList<>();
    private PendingItem activeItem;
    private long nextId = 1L;

    @NonNull
    public StartResult start(@NonNull File source, boolean copy) {
        if (isInProgress() || !isValidSource(source)) return StartResult.INVALID_SOURCE;
        removeDuplicatePending(source, copy);
        PendingItem item = new PendingItem(nextId++, source, copy);
        pendingItems.add(0, item);
        activeItem = item;
        return StartResult.STARTED;
    }

    public void cancel() {
        pendingItems.clear();
        activeItem = null;
    }

    public void cancel(long id) {
        Iterator<PendingItem> it = pendingItems.iterator();
        while (it.hasNext()) {
            PendingItem item = it.next();
            if (item.id == id) {
                it.remove();
                if (activeItem == item) activeItem = pendingItems.isEmpty() ? null : pendingItems.get(0);
                return;
            }
        }
    }

    public void clearAfterSuccess() {
        if (activeItem != null) {
            cancel(activeItem.id);
        }
    }

    public void clearAfterSuccess(@NonNull PendingItem item) {
        cancel(item.id);
    }

    public boolean hasPending() {
        return !pendingItems.isEmpty();
    }

    @NonNull
    public List<PendingItem> getPendingItems() {
        return Collections.unmodifiableList(new ArrayList<>(pendingItems));
    }

    public boolean setActive(long id) {
        for (PendingItem item : pendingItems) {
            if (item.id == id) {
                activeItem = item;
                return true;
            }
        }
        return false;
    }

    public boolean isCopy() {
        PendingItem item = getActiveItem();
        return item != null && item.copy;
    }

    public boolean isInProgress() {
        for (PendingItem item : pendingItems) {
            if (item.inProgress) return true;
        }
        return false;
    }

    public void setInProgress(boolean inProgress) {
        PendingItem item = getActiveItem();
        if (item != null) item.inProgress = inProgress;
    }

    public void setItemsInProgress(@NonNull List<PendingItem> items, boolean inProgress) {
        for (PendingItem target : items) {
            if (target == null) continue;
            for (PendingItem item : pendingItems) {
                if (item.id == target.id) {
                    item.inProgress = inProgress;
                    break;
                }
            }
        }
    }

    @Nullable
    public File getSource() {
        PendingItem item = getActiveItem();
        return item != null ? item.source : null;
    }

    public boolean isSource(@NonNull File candidate) {
        PendingItem item = getActiveItem();
        return item != null && item.source.equals(candidate);
    }

    public boolean canOverwrite(@NonNull File target) {
        PendingItem item = getActiveItem();
        return item != null && !FileSystemOps.sameCanonicalFile(item.source, target);
    }

    @NonNull
    public PastePlan preparePaste(@Nullable File destinationDir) {
        PendingItem item = getActiveItem();
        return preparePaste(item, destinationDir);
    }

    @NonNull
    public PastePlan preparePaste(@Nullable PendingItem item, @Nullable File destinationDir) {
        if (item == null) {
            return new PastePlan(PasteStatus.NO_SOURCE, null, destinationDir, null);
        }
        File source = item.source;
        if (!isValidSource(source)) {
            return new PastePlan(PasteStatus.SOURCE_UNAVAILABLE, source, destinationDir, null);
        }
        if (destinationDir == null || !destinationDir.exists()
                || !destinationDir.isDirectory() || !destinationDir.canWrite()) {
            return new PastePlan(PasteStatus.DESTINATION_UNAVAILABLE, source, destinationDir, null);
        }

        File sourceParent = source.getParentFile();
        if (!item.copy && sourceParent != null && FileSystemOps.sameCanonicalFile(sourceParent, destinationDir)) {
            return new PastePlan(PasteStatus.CUT_SAME_FOLDER, source, destinationDir, null);
        }

        if (source.isDirectory() && FileSystemOps.isSameOrDescendant(source, destinationDir)) {
            return new PastePlan(PasteStatus.DIRECTORY_INTO_SELF, source, destinationDir, null);
        }

        File destination = new File(destinationDir, source.getName());
        if (destination.exists()) {
            return new PastePlan(PasteStatus.CONFLICT, source, destinationDir, destination);
        }
        return new PastePlan(PasteStatus.READY, source, destinationDir, destination);
    }

    public boolean performOperation(@NonNull File destination, boolean overwrite) {
        return performOperation(destination, overwrite, null);
    }

    public boolean performOperation(@NonNull File destination,
                                    boolean overwrite,
                                    @Nullable FileOperationProgress progress) {
        PendingItem item = getActiveItem();
        return performOperation(item, destination, overwrite, progress, true);
    }

    public boolean performOperation(@Nullable PendingItem item,
                                    @NonNull File destination,
                                    boolean overwrite,
                                    @Nullable FileOperationProgress progress,
                                    boolean assignTotalBytes) {
        return performOperation(item, destination, overwrite, progress, assignTotalBytes, null);
    }

    public boolean performOperation(@Nullable PendingItem item,
                                    @NonNull File destination,
                                    boolean overwrite,
                                    @Nullable FileOperationProgress progress,
                                    boolean assignTotalBytes,
                                    @Nullable FileTreeProgressTracker tracker) {
        if (item == null || !isValidSource(item.source)) return false;
        return item.copy
                ? FileSystemOps.copy(item.source, destination, overwrite, progress, assignTotalBytes, tracker)
                : FileSystemOps.move(item.source, destination, overwrite, progress, assignTotalBytes, tracker);
    }

    @Nullable
    public File buildCopyDestination(@NonNull File destinationDir, @NonNull File source) {
        String originalName = source.getName();
        String base = originalName;
        String ext = "";
        if (source.isFile()) {
            int dot = originalName.lastIndexOf('.');
            if (dot > 0 && dot < originalName.length() - 1) {
                base = originalName.substring(0, dot);
                ext = originalName.substring(dot);
            }
        }
        for (int i = 1; i < 10000; i++) {
            File candidate = new File(destinationDir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return null;
    }

    @Nullable
    private PendingItem getActiveItem() {
        if (activeItem != null && pendingItems.contains(activeItem)) return activeItem;
        activeItem = pendingItems.isEmpty() ? null : pendingItems.get(0);
        return activeItem;
    }

    private void removeDuplicatePending(@NonNull File source, boolean copy) {
        Iterator<PendingItem> it = pendingItems.iterator();
        while (it.hasNext()) {
            PendingItem item = it.next();
            if (item.copy == copy && FileSystemOps.sameCanonicalFile(item.source, source)) {
                if (activeItem == item) activeItem = null;
                it.remove();
            }
        }
    }

    private boolean isValidSource(@NonNull File file) {
        return file.exists() && (file.isFile() || file.isDirectory());
    }
}
