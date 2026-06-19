package com.readwide.manager;

import androidx.annotation.NonNull;

import com.readwide.manager.util.FileTypeFilter;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class MainFileSearchWalker {
    // No result cap: a File entry is light (a path string), so even tens of
    // thousands of matches are a few MB. Results are streamed to the adapter
    // incrementally rather than held twice. VISIT_LIMIT is only a runaway guard
    // (e.g. a pathological tree) and is set far above any real device's file
    // count.
    private static final int VISIT_LIMIT = 1_000_000;
    private static final int PROGRESS_BATCH = 96;
    private static final long PROGRESS_MIN_INTERVAL_MS = 220L;

    interface ProgressCallback {
        // Receives only the items found since the previous callback (a delta),
        // so a growing result set is never re-copied or re-bound wholesale.
        void onProgress(@NonNull String query, int generation, @NonNull List<File> delta);
    }

    private final MainActivity activity;
    private final ProgressCallback progressCallback;

    MainFileSearchWalker(@NonNull MainActivity activity, @NonNull ProgressCallback progressCallback) {
        this.activity = activity;
        this.progressCallback = progressCallback;
    }

    void search(@NonNull String query,
                @NonNull List<File> roots,
                int filter,
                boolean showHidden,
                int generation,
                @NonNull List<File> results) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> visitedDirs = new java.util.HashSet<>();
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        int[] visited = new int[]{0};
        SearchProgress progress = new SearchProgress(query, generation);

        for (File root : roots) {
            if (isCancelled(generation)) return;
            if (root == null || !root.exists() || !root.canRead()) continue;
            searchFilesRecursive(root, needle, filter, showHidden, seen, results, visited, generation, 0, progress,
                    visitedDirs);
            if (isCancelled(generation) || visited[0] >= VISIT_LIMIT) return;
        }
    }

    private void searchFilesRecursive(@NonNull File dir,
                                      @NonNull String needle,
                                      int filter,
                                      boolean showHidden,
                                      @NonNull Set<String> seen,
                                      @NonNull List<File> results,
                                      @NonNull int[] visited,
                                      int generation,
                                      int depth,
                                      @NonNull SearchProgress progress,
                                      @NonNull Set<String> visitedDirs) {
        if (isCancelled(generation)
                || depth > 16
                || visited[0] >= VISIT_LIMIT) return;
        // Guard against symlink cycles: skip a directory whose canonical path was
        // already walked, so a link pointing back up the tree cannot re-scan it.
        String canonical;
        try {
            canonical = dir.getCanonicalPath();
        } catch (java.io.IOException e) {
            canonical = dir.getAbsolutePath();
        }
        if (!visitedDirs.add(canonical)) return;
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (isCancelled(generation)) return;
            if (child == null) continue;
            visited[0]++;
            if (visited[0] >= VISIT_LIMIT) return;
            String name = child.getName();
            if (!showHidden && name.startsWith(".")) continue;
            String path = child.getAbsolutePath();
            if (path.contains("/Android/data/") || path.contains("/Android/obb/")) continue;

            // Stat the directory flag once per entry instead of up to three times
            // (filesystem access adds up on Downloads/FUSE/external storage).
            boolean isDir = child.isDirectory();
            boolean nameMatch = needle.isEmpty() || name.toLowerCase(java.util.Locale.ROOT).contains(needle);
            boolean fileMatch = !isDir && FileTypeFilter.matches(name, filter);
            boolean directoryMatch = isDir && !needle.isEmpty()
                    && filter == MainActivity.FILTER_ALL
                    && nameMatch;
            if ((fileMatch || directoryMatch) && nameMatch && seen.add(path)) {
                results.add(child);
                progress.pendingDelta.add(child);
                maybePublishSearchProgress(progress);
            }
            if (isDir && child.canRead()) {
                searchFilesRecursive(child, needle, filter, showHidden, seen, results, visited, generation, depth + 1,
                        progress, visitedDirs);
            }
        }
    }

    private boolean isCancelled(int generation) {
        return activity.activityDestroyed || generation != activity.fileSearchGeneration.get();
    }

    // Publishes the items accumulated since the last callback (a delta), throttled
    // by batch size and time so the UI thread is not flooded on fast filesystems.
    private void maybePublishSearchProgress(@NonNull SearchProgress progress) {
        long now = android.os.SystemClock.uptimeMillis();
        if (progress.pendingDelta.size() < PROGRESS_BATCH
                && now - progress.lastPublishedAt < PROGRESS_MIN_INTERVAL_MS) {
            return;
        }
        flushSearchProgress(progress, now);
    }

    private void flushSearchProgress(@NonNull SearchProgress progress, long now) {
        if (progress.pendingDelta.isEmpty()) return;
        progress.lastPublishedAt = now;
        List<File> delta = progress.pendingDelta;
        progress.pendingDelta = new ArrayList<>();
        progressCallback.onProgress(progress.query, progress.generation, delta);
    }

    private static final class SearchProgress {
        final String query;
        final int generation;
        long lastPublishedAt;
        List<File> pendingDelta = new ArrayList<>();

        SearchProgress(@NonNull String query, int generation) {
            this.query = query;
            this.generation = generation;
        }
    }
}
