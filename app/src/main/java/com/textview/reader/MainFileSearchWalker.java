package com.textview.reader;

import androidx.annotation.NonNull;

import com.textview.reader.util.FileTypeFilter;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class MainFileSearchWalker {
    private static final int RESULT_LIMIT = Integer.MAX_VALUE;
    private static final int VISIT_LIMIT = Integer.MAX_VALUE;
    private static final int PROGRESS_BATCH = 96;
    private static final long PROGRESS_MIN_INTERVAL_MS = 220L;

    interface ProgressCallback {
        void onProgress(@NonNull String query, int generation, @NonNull List<File> snapshot);
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
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        int[] visited = new int[]{0};
        SearchProgress progress = new SearchProgress(query, generation);

        for (File root : roots) {
            if (isCancelled(generation)) return;
            if (root == null || !root.exists() || !root.canRead()) continue;
            searchFilesRecursive(root, needle, filter, showHidden, seen, results, visited, generation, 0, progress);
            if (isCancelled(generation)
                    || results.size() >= RESULT_LIMIT
                    || visited[0] >= VISIT_LIMIT) return;
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
                                      @NonNull SearchProgress progress) {
        if (isCancelled(generation)
                || depth > 16
                || results.size() >= RESULT_LIMIT
                || visited[0] >= VISIT_LIMIT) return;
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (isCancelled(generation)) return;
            if (child == null) continue;
            visited[0]++;
            if (visited[0] >= VISIT_LIMIT || results.size() >= RESULT_LIMIT) return;
            String name = child.getName();
            if (!showHidden && name.startsWith(".")) continue;
            String path = child.getAbsolutePath();
            if (path.contains("/Android/data/") || path.contains("/Android/obb/")) continue;

            boolean nameMatch = needle.isEmpty() || name.toLowerCase(java.util.Locale.ROOT).contains(needle);
            boolean fileMatch = !child.isDirectory() && FileTypeFilter.matches(name, filter);
            boolean directoryMatch = child.isDirectory() && !needle.isEmpty()
                    && filter == MainActivity.FILTER_ALL
                    && nameMatch;
            if ((fileMatch || directoryMatch) && nameMatch && seen.add(path)) {
                results.add(child);
                maybePublishSearchProgress(progress, results);
                if (results.size() >= RESULT_LIMIT) return;
            }
            if (child.isDirectory() && child.canRead()) {
                searchFilesRecursive(child, needle, filter, showHidden, seen, results, visited, generation, depth + 1,
                        progress);
            }
        }
    }

    private boolean isCancelled(int generation) {
        return activity.activityDestroyed || generation != activity.fileSearchGeneration.get();
    }

    private void maybePublishSearchProgress(@NonNull SearchProgress progress, @NonNull List<File> results) {
        int size = results.size();
        long now = android.os.SystemClock.uptimeMillis();
        if (size - progress.lastPublishedCount < PROGRESS_BATCH
                && now - progress.lastPublishedAt < PROGRESS_MIN_INTERVAL_MS) {
            return;
        }
        progress.lastPublishedCount = size;
        progress.lastPublishedAt = now;
        progressCallback.onProgress(progress.query, progress.generation, new ArrayList<>(results));
    }

    private static final class SearchProgress {
        final String query;
        final int generation;
        int lastPublishedCount;
        long lastPublishedAt;

        SearchProgress(@NonNull String query, int generation) {
            this.query = query;
            this.generation = generation;
        }
    }
}
