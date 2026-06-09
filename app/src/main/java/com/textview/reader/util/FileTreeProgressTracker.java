package com.textview.reader.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Counts and reports real recursive file/folder progress for file operations.
 *
 * The main progress dialog uses itemIndex/itemTotal for the current regular
 * file out of all regular files in the operation, and folderIndex/folderTotal
 * for the current folder out of all actual folders in the operation.  This
 * keeps the progress UI from showing only top-level selected items when the
 * worker is actually processing many nested files.
 */
public final class FileTreeProgressTracker {
    @Nullable private final FileOperationProgress progress;
    @NonNull private final Map<String, FolderInfo> folderOrder = new LinkedHashMap<>();
    @NonNull private final Set<String> seenFolders = new LinkedHashSet<>();
    private final boolean preserveOuterFolderProgress;
    private final int totalFiles;
    private final int totalFolders;
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
        this.progress = progress;
        this.preserveOuterFolderProgress = progress.snapshot().folderTotal > 1;
        int[] counts = new int[1];
        scan(root, counts);
        this.totalFiles = counts[0];
        this.totalFolders = folderOrder.size();
        resetVisibleCounters();
    }

    private FileTreeProgressTracker(@NonNull FileOperationProgress progress,
                                    @NonNull List<File> roots) {
        this.progress = progress;
        this.preserveOuterFolderProgress = progress.snapshot().folderTotal > 1;
        int[] counts = new int[1];
        for (File root : roots) {
            if (root != null) scan(root, counts);
        }
        this.totalFiles = counts[0];
        this.totalFolders = folderOrder.size();
        resetVisibleCounters();
    }

    public int totalFiles() {
        return totalFiles;
    }

    public int totalFolders() {
        return totalFolders;
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
        fileIndex++;
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

    private void scan(@NonNull File file, @NonNull int[] fileCount) {
        if (!file.exists()) return;
        if (file.isFile()) {
            fileCount[0]++;
            File parent = file.getParentFile();
            if (parent != null) addFolder(parent);
            return;
        }
        if (!file.isDirectory()) return;
        addFolder(file);
        File[] children;
        try {
            children = file.listFiles();
        } catch (SecurityException ignored) {
            return;
        }
        if (children == null) return;
        for (File child : children) {
            if (child != null) scan(child, fileCount);
        }
    }

    private void addFolder(@NonNull File folder) {
        String key = folderKey(folder);
        if (!folderOrder.containsKey(key)) {
            folderOrder.put(key, new FolderInfo(displayFolder(folder)));
        }
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
