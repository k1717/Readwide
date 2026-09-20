package com.readwide.manager.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Counts and reports real recursive file/folder progress for file operations.
 *
 * The main progress dialog uses itemIndex/itemTotal for the current regular
 * file/link leaf out of all such leaves in the operation, and folderIndex/folderTotal
 * for the current folder out of all actual folders in the operation.  This
 * keeps the progress UI from showing only top-level selected items when the
 * worker is actually processing many nested files.
 */
public final class FileTreeProgressTracker {
    @Nullable private final FileOperationProgress progress;
    @NonNull private final Map<String, FolderInfo> folderOrder = new LinkedHashMap<>();
    @NonNull private final Set<String> seenFolders = new LinkedHashSet<>();
    private final boolean preserveOuterFolderProgress;
    private int totalFiles;
    private final int totalFolders;
    private long totalBytes;
    private boolean ready = true;
    private final Map<String, Totals> rootTotals = new LinkedHashMap<>();
    private int fileIndex = 0;

    @Nullable
    public static FileTreeProgressTracker create(@Nullable FileOperationProgress progress,
                                                 @Nullable File root) {
        if (progress == null || root == null) return null;
        return new FileTreeProgressTracker(progress, root);
    }

    @Nullable
    public static FileTreeProgressTracker create(@Nullable FileOperationProgress progress,
                                                 @Nullable List<File> roots) {
        if (progress == null || roots == null) return null;
        return new FileTreeProgressTracker(progress, roots);
    }

    private FileTreeProgressTracker(@NonNull FileOperationProgress progress,
                                    @NonNull File root) {
        this(progress, Collections.singletonList(root));
    }

    private FileTreeProgressTracker(@NonNull FileOperationProgress progress,
                                    @NonNull List<File> roots) {
        this.progress = progress;
        this.preserveOuterFolderProgress = progress.snapshot().folderTotal > 1;
        for (File root : roots) {
            if (root != null && !scan(root)) { ready = false; break; }
        }
        this.totalFolders = folderOrder.size();
        resetVisibleCounters();
    }

    public int totalFiles() {
        return totalFiles;
    }

    public int totalFolders() {
        return totalFolders;
    }

    public long totalBytes() { return totalBytes; }

    /** A cancelled/unreadable inventory must not start a partial operation. */
    public boolean isReady() { return ready; }

    /** Account for a committed rename without pretending bytes were copied. */
    public void onMoved(@NonNull File root) {
        if (progress == null) return;
        Totals totals = rootTotals.get(root.getAbsolutePath());
        if (totals == null) return;
        fileIndex = (int) Math.min(Integer.MAX_VALUE, (long) fileIndex + totals.files);
        progress.setDetail(root.getName());
        if (totalFiles > 0) progress.setItemProgress(Math.min(fileIndex, totalFiles), totalFiles);
        if (!preserveOuterFolderProgress && totalFolders > 0) {
            seenFolders.addAll(totals.folders);
            progress.setFolder(root.getName());
            progress.setFolderProgress(Math.min(seenFolders.size(), totalFolders), totalFolders);
        }
        progress.addDoneBytes(totals.bytes);
    }

    public void onDirectory(@Nullable File directory) {
        if (progress == null || preserveOuterFolderProgress || directory == null || totalFolders <= 0) return;
        String key = folderKey(directory);
        FolderInfo info = folderOrder.get(key);
        if (info == null) return;
        if (seenFolders.add(key)) {
            progress.setFolder(info.displayName);
            progress.setFolderProgress(Math.min(seenFolders.size(), totalFolders), totalFolders);
        } else {
            progress.setFolder(info.displayName);
            progress.setFolderProgress(Math.min(seenFolders.size(), totalFolders), totalFolders);
        }
    }

    public void onFile(@Nullable File file) {
        if (progress == null || file == null) return;
        if (!preserveOuterFolderProgress) noteParentFolder(file);
        if (fileIndex < Integer.MAX_VALUE) fileIndex++;
        progress.setDetail(file.getName());
        if (totalFiles > 0) {
            progress.setItemProgress(Math.min(fileIndex, totalFiles), totalFiles);
        }
    }

    private void resetVisibleCounters() {
        if (progress == null) return;
        progress.clearItemProgress();
        if (!preserveOuterFolderProgress) progress.clearFolderProgress();
    }

    private boolean scan(@NonNull File root) {
        Totals totals = new Totals();
        try {
            boolean complete = FileTreeWalk.walk(root, progress::checkpoint, entry -> {
                if (entry.kind == FileTreeWalk.Kind.FILE || entry.kind == FileTreeWalk.Kind.LINK) {
                    if (totalFiles < Integer.MAX_VALUE) totalFiles++;
                    if (totals.files < Integer.MAX_VALUE) totals.files++;
                    totals.bytes = addBytes(totals.bytes, entry.bytes);
                    totalBytes = addBytes(totalBytes, entry.bytes);
                    File parent = entry.file.getParentFile();
                    if (parent != null) noteScannedFolder(parent, totals);
                } else if (entry.kind == FileTreeWalk.Kind.DIRECTORY) {
                    noteScannedFolder(entry.file, totals);
                }
                return true;
            });
            if (complete) rootTotals.put(root.getAbsolutePath(), totals);
            return complete;
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }

    private void noteScannedFolder(File folder, Totals totals) {
        String key = folderKey(folder);
        if (!folderOrder.containsKey(key)) {
            folderOrder.put(key, new FolderInfo(displayFolder(folder)));
            totals.folders.add(key);
        }
    }

    private static long addBytes(long first, long next) {
        return next > Long.MAX_VALUE - first ? Long.MAX_VALUE : first + next;
    }

    private static final class Totals {
        long bytes;
        int files;
        final List<String> folders = new ArrayList<>();
    }

    private void noteParentFolder(@NonNull File file) {
        if (progress == null) return;
        File parent = file.getParentFile();
        if (parent == null) return;
        if (totalFolders > 0 && folderOrder.containsKey(folderKey(parent))) {
            onDirectory(parent);
        } else {
            progress.setFolder(displayFolder(parent));
        }
    }

    @NonNull
    private static String folderKey(@NonNull File folder) {
        try {
            return folder.getCanonicalPath();
        } catch (Exception ignored) {
            return folder.getAbsolutePath();
        }
    }

    @NonNull
    private static String displayFolder(@NonNull File folder) {
        String name = folder.getName();
        return name == null || name.length() == 0 ? folder.getAbsolutePath() : name;
    }

    private static final class FolderInfo {
        @NonNull final String displayName;

        FolderInfo(@NonNull String displayName) {
            this.displayName = displayName;
        }
    }
}
