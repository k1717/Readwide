package com.readwide.manager;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.readwide.manager.util.FileSortUtils;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.PrefsManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class MainFolderLoadController {
    private static final String STORAGE_LOG_TAG = "ReadwideStorage";
    private static final int FOLDER_LOAD_INITIAL_PROGRESS_BATCH = 32;
    private static final int FOLDER_LOAD_PROGRESS_BATCH = 192;
    private static final long FOLDER_LOAD_INITIAL_PROGRESS_MIN_INTERVAL_MS = 80L;
    private static final long FOLDER_LOAD_PROGRESS_MIN_INTERVAL_MS = 280L;

    private final MainActivity activity;
    private final Object executorLock = new Object();
    private final AtomicInteger generation = new AtomicInteger(0);
    private ThreadPoolExecutor executor = createExecutor();
    @Nullable private Future<?> currentFolderLoadFuture;
    private String pendingRevealFilePath;

    MainFolderLoadController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void cancelPendingFolderLoad() {
        generation.incrementAndGet();
        synchronized (executorLock) {
            executor.getQueue().clear();
            if (currentFolderLoadFuture != null) {
                // Interrupt an in-flight load so its sort (which checks the thread
                // interrupt flag every 1024 keys) bails immediately instead of
                // finishing a stale full sort of a large folder.
                currentFolderLoadFuture.cancel(true);
                currentFolderLoadFuture = null;
            }
        }
    }

    void loadDirectory(File dir) {
        if (dir == null) return;
        activity.exitFileSelectionMode(false);

        final int loadGeneration = generation.incrementAndGet();
        // A folder load supersedes any in-flight search, re-sort, or date-refine
        // (all guarded by fileSearchGeneration); bump it so a stale one cannot
        // post its result over this freshly loaded list.
        activity.fileSearchGeneration.incrementAndGet();
        final File targetDir = dir;
        final boolean showHidden = activity.prefs != null && activity.prefs.getShowHiddenFiles();
        final int sortMode = currentSortMode();

        prepareBrowseTarget(targetDir, sortMode, true);

        submitPriorityFolderLoad(() -> {
            // Clear any interrupt inherited from a just-cancelled previous load so this
            // load's own sort cancellation check (isInterrupted) starts from a clean
            // state; this load is cancelled later via its own Future, not this flag.
            Thread.interrupted();
            // Accumulate row items (with metadata) as we walk so progress batches
            // reuse them instead of re-stat-ing the whole growing list on the UI
            // thread on every publish. Folders and files are kept separate so the
            // progress snapshot stays in "folders then files" discovery order
            // (the final load re-sorts).
            List<com.readwide.manager.model.FileListItem> folderItems = new ArrayList<>();
            List<com.readwide.manager.model.FileListItem> fileItems = new ArrayList<>();
            FolderLoadProgress progress = new FolderLoadProgress(targetDir, loadGeneration, sortMode, true);
            try {
                File[] fileArray = targetDir.listFiles();
                if (fileArray == null) {
                    Log.w(STORAGE_LOG_TAG, "Directory listing returned null; "
                            + activity.storageAccessDiagnosticSummary(targetDir));
                    activity.fileSearchHandler.post(
                            () -> applyDirectoryAccessDenied(targetDir, sortMode, loadGeneration));
                    return;
                }
                for (File f : fileArray) {
                    if (isCancelled(loadGeneration)) return;
                    String name = f.getName();
                    if (!showHidden && name.startsWith(".")) continue;
                    // isDirectory() is a filesystem stat; call it once per entry
                    // and reuse it when building the item.
                    boolean isDir = f.isDirectory();
                    if (isDir || FileUtils.isVisibleInAllFilesFilter(name)) {
                        com.readwide.manager.model.FileListItem item =
                                com.readwide.manager.model.FileListItem.fromKnownDirectory(f, isDir);
                        if (isDir) folderItems.add(item);
                        else fileItems.add(item);
                        maybePublishFolderLoadProgress(progress, folderItems, fileItems);
                    }
                }
                // Combine the items walked above (folders first, then files — the
                // progressive-publish order) into the canonical list, sort it using
                // the metadata already computed on each item (no per-file re-stat),
                // and derive the signature from those same items (no directory
                // re-scan). The adapter, cache, and validation all run on these items.
                final int finalSortMode = currentSortMode();
                final java.util.List<com.readwide.manager.model.FileListItem> items =
                        new ArrayList<>(folderItems.size() + fileItems.size());
                items.addAll(folderItems);
                items.addAll(fileItems);
                if (!FileSortUtils.sortMainItems(items, finalSortMode)) return;
                if (isCancelled(loadGeneration)) return;
                final String precomputedSignature =
                        activity.captureBrowseFolderSignatureFromItems(targetDir, items);
                activity.fileSearchHandler.post(() -> applyFinalDirectoryLoad(targetDir, items, finalSortMode, loadGeneration, precomputedSignature));
            } catch (SecurityException denied) {
                Log.w(STORAGE_LOG_TAG, "Directory listing threw SecurityException; "
                        + activity.storageAccessDiagnosticSummary(targetDir));
                activity.fileSearchHandler.post(() -> applyDirectoryAccessDenied(targetDir, sortMode, loadGeneration));
            }
        });
    }

    void refreshCurrentDirectoryWithoutClearing(File dir) {
        if (dir == null) return;

        final int loadGeneration = generation.incrementAndGet();
        // Same as loadDirectory: a refresh supersedes any in-flight search,
        // re-sort, or date-refine guarded by fileSearchGeneration.
        activity.fileSearchGeneration.incrementAndGet();
        final File targetDir = dir;
        final boolean showHidden = activity.prefs != null && activity.prefs.getShowHiddenFiles();
        final int sortMode = currentSortMode();

        prepareBrowseTarget(targetDir, sortMode, false);

        submitPriorityFolderLoad(() -> {
            // Clear any interrupt inherited from a just-cancelled previous load.
            Thread.interrupted();
            final java.util.List<com.readwide.manager.model.FileListItem> items = new ArrayList<>();
            int appliedSortMode = currentSortMode();
            boolean readOk = true;
            try {
                File[] fileArray = targetDir.listFiles();
                if (fileArray == null) {
                    Log.w(STORAGE_LOG_TAG, "Directory refresh returned null; "
                            + activity.storageAccessDiagnosticSummary(targetDir));
                    readOk = false;
                } else {
                    for (File f : fileArray) {
                        if (isCancelled(loadGeneration)) return;
                        String name = f.getName();
                        if (!showHidden && name.startsWith(".")) continue;
                        boolean isDir = f.isDirectory();
                        if (isDir || FileUtils.isVisibleInAllFilesFilter(name)) {
                            items.add(com.readwide.manager.model.FileListItem.fromKnownDirectory(f, isDir));
                        }
                    }
                }
                appliedSortMode = currentSortMode();
                if (!FileSortUtils.sortMainItems(items, appliedSortMode)) return;
            } catch (SecurityException denied) {
                Log.w(STORAGE_LOG_TAG, "Directory refresh threw SecurityException; "
                        + activity.storageAccessDiagnosticSummary(targetDir));
                items.clear();
                appliedSortMode = currentSortMode();
                readOk = false;
            }

            final int finalSortMode = appliedSortMode;
            if (isCancelled(loadGeneration)) return;
            // On a read failure leave the signature null (do not cache an empty-folder
            // signature) so validation does not keep re-refreshing an unreadable dir.
            final String precomputedSignature =
                    readOk ? activity.captureBrowseFolderSignatureFromItems(targetDir, items) : null;
            final boolean finalReadOk = readOk;
            activity.fileSearchHandler.post(() -> {
                if (finalReadOk) {
                    applyRefreshDirectory(targetDir, items, finalSortMode, loadGeneration, precomputedSignature);
                } else {
                    applyDirectoryAccessDenied(targetDir, finalSortMode, loadGeneration);
                }
            });
        });
    }

    void resortVisibleFileListAsync(int sortMode) {
        if (activity.fileAdapter == null) return;
        final int searchGeneration = activity.fileSearchGeneration.incrementAndGet();
        final boolean showPath = activity.searchMode;
        final ArrayList<com.readwide.manager.model.FileListItem> snapshot = activity.fileAdapter.getItemsSnapshot();
        if (activity.fileSearchProgress != null) activity.fileSearchProgress.setVisibility(View.VISIBLE);
        activity.fileAdapter.setSortModeSilently(sortMode);

        activity.submitFileSearchTask(() -> {
            // Re-sort the already-loaded items using their cached metadata (no per-file
            // re-stat) and keep each item's precomputed search-result location label,
            // instead of unwrapping to Files and rebuilding items.
            final ArrayList<com.readwide.manager.model.FileListItem> sortedItems = new ArrayList<>(snapshot);
            if (!FileSortUtils.sortMainItems(sortedItems, sortMode)) return;
            activity.fileSearchHandler.post(() -> {
                if (activity.activityDestroyed || searchGeneration != activity.fileSearchGeneration.get()) return;
                if (activity.fileAdapter != null) {
                    activity.fileAdapter.setShowFilePath(showPath);
                    activity.fileAdapter.setSortModeSilently(sortMode);
                    activity.fileAdapter.setItemsFastPresorted(sortedItems);
                }
                if (activity.fileSearchProgress != null) activity.fileSearchProgress.setVisibility(View.GONE);
                activity.scrollListToTop(activity.fileRecyclerView);
            });
            // The MediaStore date refinement reuses these same items (preserving each
            // item's precomputed search-result location label) in the refined order.
            refineDateOrderInBackground(sortedItems, sortMode, searchGeneration, showPath);
        });
    }

    // Hybrid date sort: the list above is already shown using fast filesystem
    // timestamps. Here we batch-query MediaStore DATE_ADDED and, only if it actually
    // differs for some files, re-sort the same items with those values and update the
    // list quietly. Every stage re-checks interrupt/generation, so a superseded refine
    // (navigation, new search, new sort) bails promptly instead of running the
    // MediaStore query + sort to completion behind the newer work.
    private void refineDateOrderInBackground(@NonNull java.util.List<com.readwide.manager.model.FileListItem> currentItems,
                                             int sortMode,
                                             int generationAtStart,
                                             boolean showPath) {
        if (sortMode != com.readwide.manager.util.PrefsManager.SORT_DATE_NEW
                && sortMode != com.readwide.manager.util.PrefsManager.SORT_DATE_OLD) return;
        if (isRefineSuperseded(generationAtStart)) return;
        ArrayList<File> current = new ArrayList<>(currentItems.size());
        for (com.readwide.manager.model.FileListItem it : currentItems) current.add(it.getFile());
        java.util.Map<String, Long> added =
                com.readwide.manager.util.FileSortUtils.batchMediaStoreDateAdded(activity, current);
        // The batch can run long on a large list; bail if it was superseded meanwhile
        // (or interrupted, in which case the map may be partial — discard it).
        if (isRefineSuperseded(generationAtStart)) return;
        if (added.isEmpty()) return;
        // Re-sort the same item objects with the DATE_ADDED overrides: no re-stat, and
        // each item's precomputed location label is preserved. Cancellable — bail if a
        // newer task interrupts mid-sort.
        final java.util.List<com.readwide.manager.model.FileListItem> refinedItems =
                new ArrayList<>(currentItems);
        if (!com.readwide.manager.util.FileSortUtils.sortMainItemsWithDateOverrides(refinedItems, sortMode, added)) return;
        if (refinedItems.equals(currentItems)) return; // MediaStore agreed with filesystem order
        activity.fileSearchHandler.post(() -> {
            if (activity.activityDestroyed || generationAtStart != activity.fileSearchGeneration.get()) return;
            if (activity.fileAdapter == null) return;
            activity.fileAdapter.setShowFilePath(showPath);
            activity.fileAdapter.setSortModeSilently(sortMode);
            activity.fileAdapter.setItemsFastPresorted(refinedItems);
        });
    }

    // True if this refine has been superseded — the activity is gone, a newer
    // search/sort/load bumped the generation, or Future.cancel(true) interrupted us.
    private boolean isRefineSuperseded(int generationAtStart) {
        return Thread.currentThread().isInterrupted()
                || activity.activityDestroyed
                || generationAtStart != activity.fileSearchGeneration.get();
    }

    void setPendingRevealPath(String revealPath) {
        pendingRevealFilePath = revealPath;
    }

    void shutdownNow() {
        generation.incrementAndGet();
        synchronized (executorLock) {
            executor.shutdownNow();
        }
    }

    private void prepareBrowseTarget(@NonNull File targetDir, int sortMode, boolean clearList) {
        activity.currentDirectory = targetDir;
        activity.syncVisibleFolderChangeObserver();
        activity.markCurrentBrowseFolderLoadStarted(targetDir);
        if (activity.prefs != null) {
            activity.prefs.setLastDirectory(targetDir.getAbsolutePath());
            activity.prefs.addRecentFolder(targetDir.getAbsolutePath());
        }
        if (activity.pathText != null) activity.pathText.setText(targetDir.getAbsolutePath());
        activity.updateParentFolderButtonState();
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle(targetDir.getName().isEmpty() ? "/" : targetDir.getName());
        }

        if (activity.fileSearchProgress != null) activity.fileSearchProgress.setVisibility(View.VISIBLE);
        if (activity.fileAdapter != null) {
            activity.fileAdapter.setShowFilePath(false);
            activity.fileAdapter.setSortModeSilently(sortMode);
            if (clearList) activity.fileAdapter.setFilesFastPresorted(new ArrayList<>());
        }
        if (activity.emptyText != null) activity.emptyText.setVisibility(View.GONE);
        if (clearList) activity.scrollListToTop(activity.fileRecyclerView);
    }

    private void submitPriorityFolderLoad(@NonNull Runnable task) {
        synchronized (executorLock) {
            // Coalesce onto the single I/O worker without creating a new executor
            // (which would let an interrupted-but-uninterruptible listFiles()/stat keep
            // running in parallel with the new scan). Clear any queued load and
            // interrupt the in-flight one via Future.cancel(true): its directory walk
            // bails on the bumped generation (checked every entry) and its sort bails
            // on the thread interrupt flag (checked every 1024 keys), so the stale load
            // unwinds promptly and the new load runs next on the same thread. The new
            // task clears any inherited interrupt at its start (see loadDirectory /
            // refreshCurrentDirectoryWithoutClearing), so it does not depend on the
            // executor's between-task interrupt handling.
            executor.getQueue().clear();
            if (currentFolderLoadFuture != null) {
                currentFolderLoadFuture.cancel(true);
            }
            currentFolderLoadFuture = executor.submit(task);
        }
    }

    private void maybePublishFolderLoadProgress(@NonNull FolderLoadProgress progress,
                                                @NonNull List<com.readwide.manager.model.FileListItem> folderItems,
                                                @NonNull List<com.readwide.manager.model.FileListItem> fileItems) {
        int size = folderItems.size() + fileItems.size();
        long now = SystemClock.uptimeMillis();
        int batchThreshold = progress.lastPublishedCount <= 0
                ? FOLDER_LOAD_INITIAL_PROGRESS_BATCH
                : FOLDER_LOAD_PROGRESS_BATCH;
        long intervalThreshold = progress.lastPublishedCount <= 0
                ? FOLDER_LOAD_INITIAL_PROGRESS_MIN_INTERVAL_MS
                : FOLDER_LOAD_PROGRESS_MIN_INTERVAL_MS;
        if (size - progress.lastPublishedCount < batchThreshold
                && now - progress.lastPublishedAt < intervalThreshold) {
            return;
        }
        progress.lastPublishedCount = size;
        progress.lastPublishedAt = now;
        // Publish only the items discovered since the last batch instead of
        // replacing the whole growing list every time (that was O(N^2) total
        // copy across all batches). New files append at the end; new folders
        // insert at the folder/file boundary so the partial list stays in
        // "folders then files" discovery order. Copy the deltas because this
        // walk thread keeps mutating folderItems/fileItems after we post.
        final int folderInsertIndex = progress.publishedFolderCount;
        final ArrayList<com.readwide.manager.model.FileListItem> newFolders =
                progress.publishedFolderCount < folderItems.size()
                        ? new ArrayList<>(folderItems.subList(progress.publishedFolderCount, folderItems.size()))
                        : null;
        final ArrayList<com.readwide.manager.model.FileListItem> newFiles =
                progress.publishedFileCount < fileItems.size()
                        ? new ArrayList<>(fileItems.subList(progress.publishedFileCount, fileItems.size()))
                        : null;
        progress.publishedFolderCount = folderItems.size();
        progress.publishedFileCount = fileItems.size();
        activity.fileSearchHandler.post(() ->
                publishFolderLoadIncrement(progress, folderInsertIndex, newFolders, newFiles));
    }

    private void publishFolderLoadIncrement(@NonNull FolderLoadProgress progress,
                                            int folderInsertIndex,
                                            @Nullable List<com.readwide.manager.model.FileListItem> newFolders,
                                            @Nullable List<com.readwide.manager.model.FileListItem> newFiles) {
        if (isCancelled(progress.generation)) return;
        if (!progress.targetDir.equals(activity.currentDirectory)) return;
        if (activity.fileAdapter != null) {
            activity.fileAdapter.setShowFilePath(false);
            activity.fileAdapter.setSortModeSilently(progress.sortMode);
            // Insert new folders at the boundary first so the index stays valid,
            // then append new files at the end. Posts run in order on a single
            // handler, so the adapter already holds exactly folderInsertIndex
            // folders (plus the files after them) when this batch applies.
            if (newFolders != null && !newFolders.isEmpty()) {
                activity.fileAdapter.insertItemsPresorted(folderInsertIndex, newFolders);
            }
            if (newFiles != null && !newFiles.isEmpty()) {
                activity.fileAdapter.appendItemsPresorted(newFiles);
            }
        }
        if (progress.scrollTop) {
            progress.scrollTop = false;
            activity.scrollListToTop(activity.fileRecyclerView);
        }
        if (activity.emptyText != null) activity.emptyText.setVisibility(View.GONE);
    }

    private void applyFinalDirectoryLoad(@NonNull File targetDir,
                                         @NonNull java.util.List<com.readwide.manager.model.FileListItem> items,
                                         int sortMode,
                                         int loadGeneration,
                                         @Nullable String precomputedSignature) {
        if (isCancelled(loadGeneration)) return;
        if (!targetDir.equals(activity.currentDirectory)) return;

        if (activity.fileAdapter != null) {
            activity.fileAdapter.setShowFilePath(false);
            activity.fileAdapter.setSortModeSilently(sortMode);
            activity.fileAdapter.setItemsFastPresorted(items);
        }
        if (!scrollToPendingRevealFile(targetDir, items)) {
            activity.scrollListToTop(activity.fileRecyclerView);
        }
        updateEmptyState(items.isEmpty());
        activity.markCurrentBrowseFolderLoadComplete(targetDir, items, sortMode, precomputedSignature);
        activity.syncVisibleFolderChangeObserver();
        if (activity.fileSearchProgress != null) activity.fileSearchProgress.setVisibility(View.GONE);
        activity.rebuildDrawerStorageEntries();
    }

    private void applyDirectoryAccessDenied(@NonNull File targetDir, int sortMode, int loadGeneration) {
        if (isCancelled(loadGeneration)) return;
        if (!targetDir.equals(activity.currentDirectory)) return;
        if (activity.fileAdapter != null) {
            activity.fileAdapter.setShowFilePath(false);
            activity.fileAdapter.setSortModeSilently(sortMode);
            activity.fileAdapter.setFilesFastPresorted(new ArrayList<>());
        }
        updateEmptyState(true);
        if (activity.emptyText != null) {
            activity.emptyText.setText(activity.getString(R.string.containing_folder_unavailable));
        }
        activity.markCurrentBrowseFolderLoadFailed(targetDir);
        if (activity.fileSearchProgress != null) activity.fileSearchProgress.setVisibility(View.GONE);
        activity.offerSafFolderFallback();
    }

    private void applyRefreshDirectory(@NonNull File targetDir,
                                       @NonNull java.util.List<com.readwide.manager.model.FileListItem> items,
                                       int sortMode,
                                       int loadGeneration,
                                       @Nullable String precomputedSignature) {
        if (isCancelled(loadGeneration)) return;
        if (!targetDir.equals(activity.currentDirectory)) return;

        if (activity.fileAdapter != null) {
            activity.fileAdapter.setShowFilePath(false);
            activity.fileAdapter.setSortModeSilently(sortMode);
            activity.fileAdapter.setItemsFastPresorted(items);
        }
        updateEmptyState(items.isEmpty());
        activity.markCurrentBrowseFolderLoadComplete(targetDir, items, sortMode, precomputedSignature);
        activity.syncVisibleFolderChangeObserver();
        if (activity.fileSearchProgress != null) activity.fileSearchProgress.setVisibility(View.GONE);
    }

    private void updateEmptyState(boolean empty) {
        if (activity.emptyText == null) return;
        activity.emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) activity.emptyText.setText(activity.getString(R.string.no_text_files_in_directory));
    }

    private boolean scrollToPendingRevealFile(@NonNull File targetDir,
                                              @NonNull java.util.List<com.readwide.manager.model.FileListItem> items) {
        if (pendingRevealFilePath == null || pendingRevealFilePath.trim().isEmpty()) return false;

        File pendingFile = new File(pendingRevealFilePath);
        File parent = pendingFile.getParentFile();
        if (parent == null || !targetDir.equals(parent)) return false;

        String targetPath = pendingFile.getAbsolutePath();
        int targetIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            if (targetPath.equals(items.get(i).getAbsolutePath())) {
                targetIndex = i;
                break;
            }
        }

        pendingRevealFilePath = null;
        if (targetIndex < 0 || activity.fileRecyclerView == null) return false;

        final int index = targetIndex;
        activity.fileRecyclerView.stopScroll();
        activity.fileRecyclerView.post(() -> {
            if (activity.activityDestroyed || activity.fileRecyclerView == null) return;
            RecyclerView.LayoutManager lm = activity.fileRecyclerView.getLayoutManager();
            if (lm instanceof LinearLayoutManager) {
                ((LinearLayoutManager) lm).scrollToPositionWithOffset(index, activity.dpToPx(12));
            } else {
                activity.fileRecyclerView.scrollToPosition(index);
            }
        });
        return true;
    }

    private boolean isCancelled(int loadGeneration) {
        return activity.activityDestroyed || loadGeneration != generation.get();
    }

    private int currentSortMode() {
        return activity.prefs != null ? activity.prefs.getSortMode() : PrefsManager.SORT_NAME_ASC;
    }

    private static ThreadPoolExecutor createExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
    }

    private static final class FolderLoadProgress {
        final File targetDir;
        final int generation;
        final int sortMode;
        boolean scrollTop;
        int lastPublishedCount;
        long lastPublishedAt;
        // How many folders/files have already been pushed to the adapter, so each
        // progress batch can append only the newly discovered delta instead of
        // replacing the whole list.
        int publishedFolderCount;
        int publishedFileCount;

        FolderLoadProgress(@NonNull File targetDir, int generation, int sortMode, boolean scrollTop) {
            this.targetDir = targetDir;
            this.generation = generation;
            this.sortMode = sortMode;
            this.scrollTop = scrollTop;
        }
    }
}
