package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileOperationProgress;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps archive extraction progress counts tied to archive entries, not to the
 * outer pending-queue/archive count.  The UI then shows the current extracted
 * file as current/total files, while the folder line shows current/total
 * folders actually created from the archive entry tree.
 */
final class ArchiveExtractionProgressTracker {
    @NonNull private final FileOperationProgress progress;
    private final int totalFiles;
    private final int totalFolders;
    private final boolean preserveOuterFolderProgress;
    @NonNull private final Set<String> folders = new HashSet<>();
    @NonNull private final Set<String> seenFolders = new HashSet<>();
    @Nullable private String currentFolder;
    private int fileIndex = 0;

    @Nullable
    static ArchiveExtractionProgressTracker create(@Nullable FileOperationProgress progress,
                                                   @Nullable List<ArchiveSupport.EntryInfo> entries) throws IOException {
        if (progress == null || entries == null) return null;
        return new ArchiveExtractionProgressTracker(progress, entries);
    }

    private ArchiveExtractionProgressTracker(@NonNull FileOperationProgress progress,
                                             @NonNull List<ArchiveSupport.EntryInfo> entries) throws IOException {
        this.progress = progress;
        this.preserveOuterFolderProgress = progress.snapshot().folderTotal > 1;
        ArchiveCancellation.checkpoint(progress);
        int files = 0;
        for (ArchiveSupport.EntryInfo entry : entries) {
            ArchiveCancellation.checkpoint(progress);
            if (entry == null) continue;
            String path = normalizeEntryPath(entry.path);
            if (path == null) continue;
            if (entry.directory || path.endsWith("/")) {
                if (!preserveOuterFolderProgress) addFolderTree(toDirectoryPath(path));
            } else {
                files++;
                if (!preserveOuterFolderProgress) addFolderTree(parentFolder(path));
            }
        }
        this.totalFiles = files;
        this.totalFolders = folders.size();
        ArchiveCancellation.checkpoint(progress);
        progress.clearItemProgress();
        if (!preserveOuterFolderProgress) progress.clearFolderProgress();
    }

    void onDirectory(@Nullable String rawPath) {
        if (preserveOuterFolderProgress || totalFolders <= 0) return;
        String path = normalizeEntryPath(rawPath);
        if (path == null) return;
        noteFolderTree(toDirectoryPath(path));
    }

    void onFile(@Nullable String rawPath) {
        String path = normalizeEntryPath(rawPath);
        if (path == null || path.endsWith("/")) return;
        if (!preserveOuterFolderProgress) noteFolderTree(parentFolder(path));
        fileIndex++;
        progress.setDetail(displayFileName(path));
        if (totalFiles > 0) progress.setItemProgress(fileIndex, totalFiles);
    }

    private void addFolderTree(@NonNull String folder) throws IOException {
        // Every admitted folder includes its ancestors, including empty directory entries.
        // A previously admitted parent therefore ends the walk for repeated siblings.
        while (!folder.isEmpty()) {
            ArchiveCancellation.checkpoint(progress);
            if (!folders.add(folder)) break;
            folder = parentFolder(folder.substring(0, folder.length() - 1));
        }
    }

    private void noteFolderTree(@NonNull String folder) {
        int before = seenFolders.size();
        String parent = folder;
        while (!parent.isEmpty()) {
            if (folders.contains(parent) && !seenFolders.add(parent)) break;
            parent = parentFolder(parent.substring(0, parent.length() - 1));
        }
        // Publish only the deepest folder, not one transient notification per ancestor.
        // Root files clear a previous member's folder label without resetting the count.
        if (!folder.equals(currentFolder)) {
            currentFolder = folder;
            progress.setFolder(folder.isEmpty() ? "" : displayFolder(folder));
        }
        if (seenFolders.size() != before) progress.setFolderProgress(seenFolders.size(), totalFolders);
    }

    @NonNull
    private static String parentFolder(@NonNull String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    @NonNull
    private static String toDirectoryPath(@NonNull String path) {
        return path.endsWith("/") ? path : path + "/";
    }

    @NonNull
    private static String displayFolder(@NonNull String folderPath) {
        String p = folderPath.endsWith("/") ? folderPath.substring(0, folderPath.length() - 1) : folderPath;
        if (p.length() == 0) return "-";
        int slash = p.lastIndexOf('/');
        if (slash >= 0 && slash < p.length() - 1) return p.substring(slash + 1);
        return p;
    }

    @NonNull
    private static String displayFileName(@NonNull String filePath) {
        String p = filePath.endsWith("/") ? filePath.substring(0, filePath.length() - 1) : filePath;
        int slash = p.lastIndexOf('/');
        if (slash >= 0 && slash < p.length() - 1) return p.substring(slash + 1);
        return p.length() == 0 ? "-" : p;
    }

    @Nullable
    private static String normalizeEntryPath(@Nullable String rawPath) {
        return ArchiveSupport.sanitizeEntryPathForList(rawPath);
    }
}
