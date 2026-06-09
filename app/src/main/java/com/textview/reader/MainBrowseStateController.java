package com.textview.reader;

import android.os.Parcelable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.textview.reader.util.PrefsManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns browse-folder state preservation for MainActivity.
 *
 * This controller keeps MainActivity from accumulating ad-hoc cache, signature,
 * viewer-return, drawer shortcut, filter-return, and onResume state logic.
 */
final class MainBrowseStateController {
    private static final int MAX_BROWSE_STATE_CACHE_ENTRIES = 24;

    private final MainActivity activity;
    private final LinkedHashMap<String, BrowseFolderSnapshot> folderStateCache =
            new LinkedHashMap<String, BrowseFolderSnapshot>(16, 0.75f, true);
    private final AtomicInteger validationGeneration = new AtomicInteger(0);

    private boolean preserveOnNextResume = false;
    @Nullable private String preserveDirectoryPath;
    @Nullable private String preserveOpenedFilePath;
    @Nullable private String preserveFolderSignature;

    private boolean currentFolderFullyLoaded = false;
    @Nullable private String currentLoadedPath;
    @Nullable private String currentLoadedSignature;
    private int currentLoadedSortMode = PrefsManager.SORT_NAME_ASC;
    private boolean currentLoadedShowHidden = false;

    MainBrowseStateController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void markPreserveForViewerReturn(@Nullable File openedFile) {
        if (activity.homeMode || activity.searchMode || activity.currentDirectory == null || activity.fileAdapter == null) {
            return;
        }
        preserveOnNextResume = true;
        preserveDirectoryPath = activity.currentDirectory.getAbsolutePath();
        preserveOpenedFilePath = openedFile != null ? openedFile.getAbsolutePath() : null;
        preserveFolderSignature = captureFolderSignature(activity.currentDirectory);
    }

    boolean shouldPreserveViewerStateOnResume() {
        if (!preserveOnNextResume) return false;
        if (activity.homeMode || activity.searchMode || activity.currentDirectory == null || activity.fileAdapter == null) {
            return false;
        }
        if (preserveDirectoryPath == null
                || !preserveDirectoryPath.equals(activity.currentDirectory.getAbsolutePath())) {
            return false;
        }
        if (preserveOpenedFilePath != null
                && preserveOpenedFilePath.trim().length() > 0
                && !new File(preserveOpenedFilePath).exists()) {
            return false;
        }
        String currentSignature = captureFolderSignature(activity.currentDirectory);
        return preserveFolderSignature != null
                && currentSignature != null
                && preserveFolderSignature.equals(currentSignature);
    }

    boolean shouldKeepCurrentStateOnResume() {
        if (activity.homeMode || activity.searchMode || activity.currentDirectory == null || activity.fileAdapter == null) {
            return false;
        }
        if (!currentFolderFullyLoaded) return false;
        if (currentLoadedPath == null || !currentLoadedPath.equals(activity.currentDirectory.getAbsolutePath())) {
            return false;
        }
        int sortMode = activity.prefs != null ? activity.prefs.getSortMode() : PrefsManager.SORT_NAME_ASC;
        boolean showHidden = activity.prefs != null && activity.prefs.getShowHiddenFiles();
        if (currentLoadedSortMode != sortMode || currentLoadedShowHidden != showHidden) {
            return false;
        }
        String currentSignature = captureFolderSignature(activity.currentDirectory);
        if (currentLoadedSignature == null
                || currentSignature == null
                || !currentLoadedSignature.equals(currentSignature)) {
            remove(activity.currentDirectory);
            currentFolderFullyLoaded = false;
            currentLoadedSignature = currentSignature;
            return false;
        }
        save(activity.currentDirectory, activity.fileAdapter.getFilesSnapshot(), sortMode, captureRecyclerViewState());
        return true;
    }

    void refreshVisibleStateWithoutReload() {
        File directory = activity.currentDirectory;
        if (directory == null) return;
        if (activity.pathText != null) activity.pathText.setText(directory.getAbsolutePath());
        activity.updateParentFolderButtonState();
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle(directory.getName().isEmpty() ? "/" : directory.getName());
        }
        if (activity.fileSearchProgress != null) activity.fileSearchProgress.setVisibility(View.GONE);
        if (activity.fileAdapter != null) {
            activity.fileAdapter.setShowFilePath(false);
            activity.fileAdapter.refreshReadingProgress();
        }
        if (activity.emptyText != null && activity.fileAdapter != null) {
            activity.emptyText.setVisibility(activity.fileAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        }
        activity.updateParentFolderButtonState();
    }

    void clearPreservedResumeState() {
        preserveOnNextResume = false;
        preserveDirectoryPath = null;
        preserveOpenedFilePath = null;
        preserveFolderSignature = null;
    }

    void markLoadStarted(@Nullable File directory) {
        currentFolderFullyLoaded = false;
        currentLoadedPath = directory != null ? directory.getAbsolutePath() : null;
        currentLoadedSignature = null;
    }

    void markLoadComplete(@NonNull File directory, @NonNull List<File> files, int sortMode) {
        currentFolderFullyLoaded = true;
        currentLoadedPath = directory.getAbsolutePath();
        currentLoadedSignature = captureFolderSignature(directory);
        currentLoadedSortMode = sortMode;
        currentLoadedShowHidden = activity.prefs != null && activity.prefs.getShowHiddenFiles();
        save(directory, files, sortMode, captureRecyclerViewState());
    }

    void markLoadFailed(@Nullable File directory) {
        currentFolderFullyLoaded = false;
        currentLoadedPath = directory != null ? directory.getAbsolutePath() : null;
        currentLoadedSignature = null;
    }

    void saveCurrentIfComplete() {
        if (activity.homeMode || activity.searchMode || activity.currentDirectory == null || activity.fileAdapter == null) return;
        if (!currentFolderFullyLoaded) return;
        if (currentLoadedPath == null || !currentLoadedPath.equals(activity.currentDirectory.getAbsolutePath())) return;

        String currentSignature = captureFolderSignature(activity.currentDirectory);
        if (currentSignature == null
                || currentLoadedSignature == null
                || !currentLoadedSignature.equals(currentSignature)) {
            remove(activity.currentDirectory);
            currentFolderFullyLoaded = false;
            currentLoadedSignature = currentSignature;
            return;
        }
        int sortMode = activity.prefs != null ? activity.prefs.getSortMode() : currentLoadedSortMode;
        save(activity.currentDirectory, activity.fileAdapter.getFilesSnapshot(), sortMode, captureRecyclerViewState());
    }

    void saveCurrentFastIfComplete() {
        if (activity.homeMode || activity.searchMode || activity.currentDirectory == null || activity.fileAdapter == null) return;
        if (!currentFolderFullyLoaded) return;
        if (currentLoadedPath == null
                || currentLoadedSignature == null
                || !currentLoadedPath.equals(activity.currentDirectory.getAbsolutePath())) return;
        int sortMode = activity.prefs != null ? activity.prefs.getSortMode() : currentLoadedSortMode;
        save(activity.currentDirectory, currentLoadedSignature, activity.fileAdapter.getFilesSnapshot(), sortMode, captureRecyclerViewState());
    }

    boolean restoreForFilterReturn(@NonNull File directory) {
        return restore(directory, true);
    }

    boolean restore(@NonNull File directory) {
        return restore(directory, false);
    }

    boolean restore(@NonNull File directory, boolean allowFromSearchMode) {
        if (activity.searchMode && !allowFromSearchMode) return false;
        BrowseFolderSnapshot snapshot = folderStateCache.get(directory.getAbsolutePath());
        if (snapshot == null) return false;

        int sortMode = activity.prefs != null ? activity.prefs.getSortMode() : PrefsManager.SORT_NAME_ASC;
        boolean showHidden = activity.prefs != null && activity.prefs.getShowHiddenFiles();
        String currentSignature = captureFolderSignature(directory);
        if (currentSignature == null
                || !snapshot.signature.equals(currentSignature)
                || snapshot.sortMode != sortMode
                || snapshot.showHidden != showHidden) {
            remove(directory);
            return false;
        }

        apply(directory, snapshot, currentSignature, sortMode, showHidden);
        return true;
    }

    boolean restoreOptimisticForDrawer(@NonNull File directory) {
        BrowseFolderSnapshot snapshot = folderStateCache.get(directory.getAbsolutePath());
        if (snapshot == null) return false;

        int sortMode = activity.prefs != null ? activity.prefs.getSortMode() : PrefsManager.SORT_NAME_ASC;
        boolean showHidden = activity.prefs != null && activity.prefs.getShowHiddenFiles();
        if (snapshot.sortMode != sortMode || snapshot.showHidden != showHidden) {
            remove(directory);
            return false;
        }

        apply(directory, snapshot, snapshot.signature, sortMode, showHidden);
        validateOptimistic(directory, snapshot.signature);
        return true;
    }

    void remove(@Nullable File directory) {
        if (directory != null) folderStateCache.remove(directory.getAbsolutePath());
    }

    boolean sameDirectory(@Nullable File left, @Nullable File right) {
        if (left == null || right == null) return false;
        return left.getAbsolutePath().equals(right.getAbsolutePath());
    }

    @Nullable
    String captureFolderSignature(@Nullable File directory) {
        if (directory == null || !directory.isDirectory()) return null;
        File[] children = directory.listFiles();
        if (children == null) return null;
        ArrayList<String> entries = new ArrayList<>();
        for (File child : children) {
            if (child == null) continue;
            StringBuilder builder = new StringBuilder();
            builder.append(child.getName()).append('|');
            builder.append(child.isDirectory() ? 'D' : 'F').append('|');
            builder.append(child.isDirectory() ? 0L : child.length()).append('|');
            builder.append(child.lastModified());
            entries.add(builder.toString());
        }
        Collections.sort(entries);
        StringBuilder signature = new StringBuilder();
        signature.append(directory.getAbsolutePath()).append('|').append(entries.size()).append('\n');
        for (String entry : entries) {
            signature.append(entry).append('\n');
        }
        return signature.toString();
    }

    private void apply(@NonNull File directory,
                       @NonNull BrowseFolderSnapshot snapshot,
                       @NonNull String signature,
                       int sortMode,
                       boolean showHidden) {
        activity.cancelPendingFolderLoad();
        activity.exitFileSelectionMode(false);
        activity.searchMode = false;
        activity.searchReturnToHome = false;
        activity.searchReturnDirectory = directory;
        activity.homeMode = false;
        activity.currentDirectory = directory;
        currentFolderFullyLoaded = true;
        currentLoadedPath = directory.getAbsolutePath();
        currentLoadedSignature = signature;
        currentLoadedSortMode = sortMode;
        currentLoadedShowHidden = showHidden;

        if (activity.prefs != null) {
            activity.prefs.setLastDirectory(directory.getAbsolutePath());
            activity.prefs.addRecentFolder(directory.getAbsolutePath());
        }
        activity.recentSection.setVisibility(View.GONE);
        activity.browserSection.setVisibility(View.VISIBLE);
        activity.setPathBarVisible(true);
        activity.updateFileTypeChips();
        activity.updateFileSearchClearButtonVisibility();
        if (activity.pathText != null) activity.pathText.setText(directory.getAbsolutePath());
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle(directory.getName().isEmpty() ? "/" : directory.getName());
        }
        if (activity.fileSearchProgress != null) activity.fileSearchProgress.setVisibility(View.GONE);
        if (activity.fileAdapter != null) {
            activity.fileAdapter.setShowFilePath(false);
            activity.fileAdapter.setSortModeSilently(sortMode);
            activity.fileAdapter.setFilesFastPresorted(new ArrayList<>(snapshot.files));
            activity.fileAdapter.refreshReadingProgress();
        }
        if (activity.emptyText != null) {
            activity.emptyText.setVisibility(snapshot.files.isEmpty() ? View.VISIBLE : View.GONE);
        }
        activity.updateParentFolderButtonState();
        restoreRecyclerViewState(snapshot.recyclerState);
        activity.updateMainOverflowButtonVisibility();
        activity.invalidateOptionsMenu();
    }

    private void validateOptimistic(@NonNull File directory, @NonNull String expectedSignature) {
        final int generation = validationGeneration.incrementAndGet();
        final String directoryPath = directory.getAbsolutePath();
        activity.fileSearchExecutor.execute(() -> {
            String actualSignature = captureFolderSignature(directory);
            activity.fileSearchHandler.post(() -> {
                if (activity.activityDestroyed || generation != validationGeneration.get()) return;
                if (actualSignature != null && actualSignature.equals(expectedSignature)) return;
                remove(directory);
                if (activity.currentDirectory == null || !directoryPath.equals(activity.currentDirectory.getAbsolutePath())) return;
                if (activity.homeMode || activity.searchMode) return;
                activity.refreshCurrentDirectoryWithoutClearing(directory);
            });
        });
    }

    private void save(@NonNull File directory,
                      @NonNull List<File> files,
                      int sortMode,
                      @Nullable Parcelable recyclerState) {
        String signature = captureFolderSignature(directory);
        if (signature == null) return;
        save(directory, signature, files, sortMode, recyclerState);
    }

    private void save(@NonNull File directory,
                      @NonNull String signature,
                      @NonNull List<File> files,
                      int sortMode,
                      @Nullable Parcelable recyclerState) {
        boolean showHidden = activity.prefs != null && activity.prefs.getShowHiddenFiles();
        folderStateCache.put(directory.getAbsolutePath(), new BrowseFolderSnapshot(
                signature,
                new ArrayList<>(files),
                recyclerState,
                sortMode,
                showHidden));
        trim();
    }

    private void trim() {
        while (folderStateCache.size() > MAX_BROWSE_STATE_CACHE_ENTRIES) {
            String firstKey = folderStateCache.keySet().iterator().next();
            folderStateCache.remove(firstKey);
        }
    }

    @Nullable
    private Parcelable captureRecyclerViewState() {
        if (activity.fileRecyclerView == null) return null;
        RecyclerView.LayoutManager manager = activity.fileRecyclerView.getLayoutManager();
        return manager != null ? manager.onSaveInstanceState() : null;
    }

    private void restoreRecyclerViewState(@Nullable Parcelable state) {
        if (state == null || activity.fileRecyclerView == null) return;
        activity.fileRecyclerView.post(() -> {
            if (activity.activityDestroyed || activity.fileRecyclerView == null) return;
            RecyclerView.LayoutManager manager = activity.fileRecyclerView.getLayoutManager();
            if (manager != null) manager.onRestoreInstanceState(state);
        });
    }

    private static final class BrowseFolderSnapshot {
        final String signature;
        final ArrayList<File> files;
        @Nullable final Parcelable recyclerState;
        final int sortMode;
        final boolean showHidden;

        BrowseFolderSnapshot(@NonNull String signature,
                             @NonNull ArrayList<File> files,
                             @Nullable Parcelable recyclerState,
                             int sortMode,
                             boolean showHidden) {
            this.signature = signature;
            this.files = files;
            this.recyclerState = recyclerState;
            this.sortMode = sortMode;
            this.showHidden = showHidden;
        }
    }
}
