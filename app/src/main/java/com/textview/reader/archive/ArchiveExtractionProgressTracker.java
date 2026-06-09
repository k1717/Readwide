package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps archive extraction progress counts tied to archive entries, not to the
 * outer pending-queue/archive count.  The UI then shows the current extracted
 * file as current/total files, while the folder line shows current/total
 * folders actually created from the archive entry tree.
 */
final class ArchiveExtractionProgressTracker {
    @Nullable private final FileOperationProgress progress;
    private final int totalFiles;
    private final int totalFolders;
    private final boolean preserveOuterFolderProgress;
    @NonNull private final Map<String, Integer> folderOrder;
    @NonNull private final Set<String> seenFolders = new LinkedHashSet<>();
    private int fileIndex = 0;

    @Nullable
    static ArchiveExtractionProgressTracker create(@Nullable FileOperationProgress progress,
                                                   @Nullable List<ArchiveSupport.EntryInfo> entries) {
        if (progress == null || entries == null) return null;
        return new ArchiveExtractionProgressTracker(progress, entries);
    }

    private ArchiveExtractionProgressTracker(@NonNull FileOperationProgress progress,
                                             @NonNull List<ArchiveSupport.EntryInfo> entries) {
        this.progress = progress;
        this.preserveOuterFolderProgress = progress.snapshot().folderTotal > 1;
        this.folderOrder = new LinkedHashMap<>();
        int files = 0;
        for (ArchiveSupport.EntryInfo entry : entries) {
            if (entry == null) continue;
            String path = normalizeEntryPath(entry.path);
            if (path == null) continue;
            if (entry.directory || path.endsWith("/")) {
                addFolderToOrder(toDirectoryPath(path));
            } else {
                files++;
                addParentFoldersToOrder(path);
            }
        }
        this.totalFiles = files;
        this.totalFolders = folderOrder.size();
        progress.clearItemProgress();
        if (!preserveOuterFolderProgress) progress.clearFolderProgress();
    }

    void onDirectory(@Nullable String rawPath) {
        if (progress == null || preserveOuterFolderProgress || totalFolders <= 0) return;
        String path = normalizeEntryPath(rawPath);
        if (path == null) return;
        noteFolder(toDirectoryPath(path));
    }

    void onFile(@Nullable String rawPath) {
        if (progress == null) return;
        String path = normalizeEntryPath(rawPath);
        if (path == null || path.endsWith("/")) return;
        if (!preserveOuterFolderProgress) noteParentFolders(path);
        fileIndex++;
        progress.setDetail(displayFileName(path));
        if (totalFiles > 0) progress.setItemProgress(fileIndex, totalFiles);
    }

    private void addParentFoldersToOrder(@NonNull String filePath) {
        int slash = filePath.indexOf('/');
        while (slash >= 0) {
            addFolderToOrder(filePath.substring(0, slash + 1));
            slash = filePath.indexOf('/', slash + 1);
        }
    }

    private void addFolderToOrder(@Nullable String folderPath) {
        if (folderPath == null || folderPath.length() == 0) return;
        String dir = toDirectoryPath(folderPath);
        if (!folderOrder.containsKey(dir)) folderOrder.put(dir, folderOrder.size() + 1);
    }

    private void noteParentFolders(@NonNull String filePath) {
        if (totalFolders <= 0) return;
        int slash = filePath.indexOf('/');
        while (slash >= 0) {
            noteFolder(filePath.substring(0, slash + 1));
            slash = filePath.indexOf('/', slash + 1);
        }
    }

    private void noteFolder(@Nullable String folderPath) {
        if (progress == null || totalFolders <= 0 || folderPath == null || folderPath.length() == 0) return;
        String dir = toDirectoryPath(folderPath);
        if (!seenFolders.add(dir)) {
            progress.setFolder(displayFolder(dir));
            Integer ordered = folderOrder.get(dir);
            if (ordered != null) progress.setFolderProgress(Math.min(seenFolders.size(), totalFolders), totalFolders);
            return;
        }
        progress.setFolder(displayFolder(dir));
        progress.setFolderProgress(Math.min(seenFolders.size(), totalFolders), totalFolders);
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
        if (rawPath == null) return null;
        String path = rawPath.trim().replace('\\', '/');
        while (path.startsWith("./")) path = path.substring(2);
        while (path.contains("//")) path = path.replace("//", "/");
        String lower = path.toLowerCase(Locale.ROOT);
        if (path.length() == 0
                || path.startsWith("/")
                || path.equals("..")
                || path.startsWith("../")
                || path.contains("/../")
                || path.endsWith("/..")
                || lower.matches("^[a-z]:.*")) {
            return null;
        }
        return path;
    }
}
