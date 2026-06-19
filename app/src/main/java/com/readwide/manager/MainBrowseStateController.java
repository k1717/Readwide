package com.readwide.manager;

import android.os.Parcelable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.readwide.manager.model.FileListItem;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.PrefsManager;

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
    // Bound the total number of cached FileListItems across all folders, not just
    // the entry count: a handful of large folders (e.g. Downloads) would otherwise
    // let the cache grow without limit. Eldest (LRU) entries are evicted until both
    // bounds hold; the just-saved current folder is always kept.
    private static final int MAX_TOTAL_CACHED_ITEMS = 30000;

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
        // Reuse the signature captured when the folder finished loading rather than
        // running a synchronous per-file folder stat on the main thread every time a
        // file is opened (slow in large folders such as Downloads). If no matching
        // baseline is available, leave it null; shouldPreserveViewerStateOnResume then
        // falls back to a normal reload on return.
        if (currentFolderFullyLoaded
                && currentLoadedSignature != null
                && currentLoadedPath != null
                && currentLoadedPath.equals(preserveDirectoryPath)) {
            preserveFolderSignature = currentLoadedSignature;
        } else {
            preserveFolderSignature = null;
        }
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
        if (preserveFolderSignature == null) return false;
        // Keep the preserved folder view immediately and verify its signature in the
        // background, rather than running a synchronous per-file folder stat on the
        // main thread during the viewer-return resume. Deletion of the file that was
        // opened is already handled above by the cheap exists() check; any other
        // change made while the viewer was open is caught by validateOptimistic,
        // which refreshes the folder in place (keeping scroll).
        validateOptimistic(activity.currentDirectory, preserveFolderSignature);
        return true;
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
        // Without a known baseline signature there is nothing to validate against,
        // so fall back to a reload.
        if (currentLoadedSignature == null) return false;
        // Keep the already-visible folder immediately and re-save the current
        // snapshot (scroll position + items) under the known signature, then verify
        // that signature in the background. If the folder changed while the activity
        // was away, validateOptimistic refreshes it in place (keeping scroll), the
        // same as restore() and focus-regain. This removes the two synchronous
        // per-file folder stats this method used to run on the main thread on every
        // resume: the explicit one here and the one inside the no-signature save().
        save(activity.currentDirectory, currentLoadedSignature, activity.fileAdapter.getItemsSnapshot(), sortMode, captureRecyclerViewState());
        validateOptimistic(activity.currentDirectory, currentLoadedSignature);
        return true;
    }

    /**
     * If the visible browse folder's on-disk contents differ from what is loaded
     * (name/size/mtime/count signature), re-read it in place while keeping scroll
     * position. This is the fallback for changes the FileObserver missed — e.g. a
     * download written via MediaStore on FUSE storage — triggered when the window
     * regains focus or the user pulls to refresh. Returns true if a reload started.
     */
    void reloadVisibleFolderIfChanged() {
        if (activity.homeMode || activity.searchMode) return;
        File directory = activity.currentDirectory;
        if (directory == null || !directory.isDirectory() || !directory.canRead()) return;
        if (currentLoadedSignature == null) return;
        // Verify the signature in the background; validateOptimistic refreshes the
        // folder only if it actually changed. This keeps the heavy per-file stat
        // off the main thread on every focus regain in a large folder.
        validateOptimistic(directory, currentLoadedSignature);
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

    void markLoadComplete(@NonNull File directory, @NonNull List<FileListItem> items, int sortMode,
                          @Nullable String precomputedSignature) {
        currentFolderFullyLoaded = true;
        currentLoadedPath = directory.getAbsolutePath();
        // The signature is computed on the background load thread and passed in. Do
        // NOT fall back to a synchronous captureFolderSignature() here — that per-file
        // folder stat on the main thread is exactly the large-folder freeze this load
        // path exists to avoid. If the background scan produced no signature (e.g. the
        // directory became unreadable mid-load), skip caching; the next focus/resume
        // reloads from scratch.
        currentLoadedSignature = precomputedSignature;
        currentLoadedSortMode = sortMode;
        currentLoadedShowHidden = activity.prefs != null && activity.prefs.getShowHiddenFiles();
        if (precomputedSignature != null) {
            save(directory, precomputedSignature, items, sortMode, captureRecyclerViewState());
        }
    }

    void markLoadFailed(@Nullable File directory) {
        currentFolderFullyLoaded = false;
        currentLoadedPath = directory != null ? directory.getAbsolutePath() : null;
        currentLoadedSignature = null;
    }

    void saveCurrentFastIfComplete() {
        if (activity.homeMode || activity.searchMode || activity.currentDirectory == null || activity.fileAdapter == null) return;
        if (!currentFolderFullyLoaded) return;
        if (currentLoadedPath == null
                || currentLoadedSignature == null
                || !currentLoadedPath.equals(activity.currentDirectory.getAbsolutePath())) return;
        int sortMode = activity.prefs != null ? activity.prefs.getSortMode() : currentLoadedSortMode;
        save(activity.currentDirectory, currentLoadedSignature, activity.fileAdapter.getItemsSnapshot(), sortMode, captureRecyclerViewState());
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
        // Sort/hidden are cheap in-memory checks; mismatch means the cached
        // ordering is wrong, so drop it and force a full reload.
        if (snapshot.sortMode != sortMode || snapshot.showHidden != showHidden) {
            remove(directory);
            return false;
        }

        // Show the cached snapshot immediately, then verify the (now full/length-
        // inclusive) signature in the background. If the folder changed, the
        // validation pass quietly refreshes. This keeps the heavy per-file stat
        // off the main thread so folder transitions stay fast.
        apply(directory, snapshot, snapshot.signature, sortMode, showHidden);
        validateOptimistic(directory, snapshot.signature);
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
        boolean showHidden = activity.prefs != null && activity.prefs.getShowHiddenFiles();
        ArrayList<String> entries = new ArrayList<>();
        for (File child : children) {
            if (child == null) continue;
            // Match the folder-load filter so this describes the *visible* listing:
            // hidden (dot) files are excluded unless the user shows them. This makes
            // the signature comparable to captureSignatureFromItems (built from the
            // visible items), and means a change to a hidden file does not trigger a
            // spurious in-place refresh of a list that would not actually change.
            if (!showHidden && child.getName().startsWith(".")) continue;
            // Match the folder-load filter for files too: a name that normalizes to an
            // empty display string (e.g. "   ") is not listed, so exclude it here as
            // well, otherwise this signature would not match captureSignatureFromItems
            // (built from the already-filtered visible items) and validation would see
            // a phantom difference and refresh a list that never actually changed.
            if (!child.isDirectory() && !FileUtils.isVisibleInAllFilesFilter(child.getName())) continue;
            // Name + type + size + lastModified. Size is included so in-place content
            // edits that preserve mtime are still detected.
            StringBuilder builder = new StringBuilder();
            builder.append(child.getName()).append('|');
            builder.append(child.isDirectory() ? 'D' : 'F').append('|');
            builder.append(child.isDirectory() ? 0L : child.length()).append('|');
            builder.append(Math.max(0L, child.lastModified()));
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

    /**
     * Builds the same signature string as {@link #captureFolderSignature(File)} but
     * from the already-loaded (visible) FileListItems, so the folder-load path does
     * not re-scan and re-stat the directory after walking it. The per-entry fields,
     * the sort, and the format are identical to captureFolderSignature (which also
     * filters hidden files), so the background validation re-scan compares directly.
     */
    @NonNull
    String captureSignatureFromItems(@NonNull File directory, @NonNull List<FileListItem> items) {
        ArrayList<String> entries = new ArrayList<>(items.size());
        for (FileListItem item : items) {
            if (item == null) continue;
            StringBuilder builder = new StringBuilder();
            builder.append(item.getName()).append('|');
            builder.append(item.isDirectory() ? 'D' : 'F').append('|');
            builder.append(item.isDirectory() ? 0L : item.getSize()).append('|');
            builder.append(item.getLastModified());
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
        activity.syncVisibleFolderChangeObserver();
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
            activity.fileAdapter.setItemsFastPresorted(new ArrayList<>(snapshot.items));
            activity.fileAdapter.refreshReadingProgress();
        }
        if (activity.emptyText != null) {
            activity.emptyText.setVisibility(snapshot.items.isEmpty() ? View.VISIBLE : View.GONE);
        }
        activity.updateParentFolderButtonState();
        restoreRecyclerViewState(snapshot.recyclerState);
        activity.updateMainOverflowButtonVisibility();
        activity.invalidateOptionsMenu();
    }

    private void validateOptimistic(@NonNull File directory, @NonNull String expectedSignature) {
        final int generation = validationGeneration.incrementAndGet();
        final String directoryPath = directory.getAbsolutePath();
        activity.submitFolderValidationTask(() -> {
            // If a newer validation was requested before this task ran, skip the
            // expensive folder stat entirely. This debounces rapid focus-regains and
            // folder restores so a burst of them does not pile redundant full scans
            // onto the validation executor.
            if (generation != validationGeneration.get()) return;
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
                      @NonNull String signature,
                      @NonNull List<FileListItem> items,
                      int sortMode,
                      @Nullable Parcelable recyclerState) {
        boolean showHidden = activity.prefs != null && activity.prefs.getShowHiddenFiles();
        folderStateCache.put(directory.getAbsolutePath(), new BrowseFolderSnapshot(
                signature,
                new ArrayList<>(items),
                recyclerState,
                sortMode,
                showHidden));
        trim();
    }

    private void trim() {
        while (folderStateCache.size() > MAX_BROWSE_STATE_CACHE_ENTRIES) {
            evictEldestEntry();
        }
        // Then evict by total item count, but never drop the last (just-saved,
        // current) folder even if it alone exceeds the item budget.
        while (folderStateCache.size() > 1 && totalCachedItems() > MAX_TOTAL_CACHED_ITEMS) {
            evictEldestEntry();
        }
    }

    private void evictEldestEntry() {
        java.util.Iterator<String> it = folderStateCache.keySet().iterator();
        if (it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private int totalCachedItems() {
        int total = 0;
        for (BrowseFolderSnapshot snapshot : folderStateCache.values()) {
            total += snapshot.items.size();
        }
        return total;
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
        final ArrayList<FileListItem> items;
        @Nullable final Parcelable recyclerState;
        final int sortMode;
        final boolean showHidden;

        BrowseFolderSnapshot(@NonNull String signature,
                             @NonNull ArrayList<FileListItem> items,
                             @Nullable Parcelable recyclerState,
                             int sortMode,
                             boolean showHidden) {
            this.signature = signature;
            this.items = items;
            this.recyclerState = recyclerState;
            this.sortMode = sortMode;
            this.showHidden = showHidden;
        }
    }
}
