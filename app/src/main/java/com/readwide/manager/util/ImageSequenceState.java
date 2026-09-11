package com.readwide.manager.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ImageSequenceState {
    private ImageSequenceState() {}

    /** Immutable index mapping shared by workers; excludes passwords and live reader state. */
    public static final class Snapshot {
        public final int generation;
        public final List<String> paths;
        public final List<String> entryPaths;

        private Snapshot(int generation, List<String> paths, List<String> entryPaths) {
            this.generation = generation;
            this.paths = Collections.unmodifiableList(new ArrayList<>(paths));
            this.entryPaths = Collections.unmodifiableList(new ArrayList<>(entryPaths));
        }
    }

    /** Owner-thread cache. Every index/path mutation must invalidate or advance generation. */
    public static final class SnapshotCache {
        private Snapshot current;

        public Snapshot capture(int generation, List<String> paths, List<String> entryPaths) {
            if (current == null || current.generation != generation) {
                current = new Snapshot(generation, paths, entryPaths);
            }
            return current;
        }

        public void clear() { current = null; }
    }

    public static void normalizeMetadataLists(@NonNull List<String> imagePaths,
                                              @NonNull List<String> displayNames,
                                              @NonNull List<String> entryPaths) {
        while (displayNames.size() < imagePaths.size()) {
            displayNames.add(defaultDisplayName(imagePaths.get(displayNames.size())));
        }
        while (entryPaths.size() < imagePaths.size()) {
            entryPaths.add("");
        }
        while (displayNames.size() > imagePaths.size()) displayNames.remove(displayNames.size() - 1);
        while (entryPaths.size() > imagePaths.size()) entryPaths.remove(entryPaths.size() - 1);
    }

    public static int applyRename(@NonNull List<String> imagePaths,
                                  @NonNull List<String> displayNames,
                                  @Nullable String oldPath,
                                  @NonNull String newPath,
                                  @NonNull String newDisplayName) {
        int index = imagePaths.indexOf(oldPath);
        if (index < 0) return -1;
        imagePaths.set(index, newPath);
        if (index < displayNames.size()) displayNames.set(index, newDisplayName);
        return index;
    }

    @Nullable
    public static String entryPathAt(@NonNull List<String> entryPaths, int currentIndex) {
        if (currentIndex < 0 || currentIndex >= entryPaths.size()) return null;
        return entryPaths.get(currentIndex);
    }

    @NonNull
    public static RemoveResult removePath(@NonNull List<String> imagePaths,
                                          @NonNull List<String> displayNames,
                                          @NonNull List<String> entryPaths,
                                          @Nullable String removedPath,
                                          int currentIndex) {
        int removedIndex = imagePaths.indexOf(removedPath);
        if (removedIndex >= 0) {
            imagePaths.remove(removedIndex);
            if (removedIndex < displayNames.size()) displayNames.remove(removedIndex);
            if (removedIndex < entryPaths.size()) entryPaths.remove(removedIndex);
        }
        normalizeMetadataLists(imagePaths, displayNames, entryPaths);
        if (imagePaths.isEmpty()) {
            return new RemoveResult(removedIndex, 0, null, true);
        }
        int nextIndex = ImageSequenceNavigationMath.clampIndex(currentIndex, imagePaths.size());
        return new RemoveResult(removedIndex, nextIndex, imagePaths.get(nextIndex), false);
    }

    @NonNull
    private static String defaultDisplayName(@Nullable String path) {
        if (path == null || path.trim().isEmpty()) return "";
        return FileUtils.normalizeDisplayFileName(new File(path).getName());
    }

    public static final class RemoveResult {
        public final int removedIndex;
        public final int currentIndex;
        @Nullable public final String currentPath;
        public final boolean empty;

        private RemoveResult(int removedIndex, int currentIndex, @Nullable String currentPath, boolean empty) {
            this.removedIndex = removedIndex;
            this.currentIndex = currentIndex;
            this.currentPath = currentPath;
            this.empty = empty;
        }
    }
}
