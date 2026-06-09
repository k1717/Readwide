package com.textview.reader;

import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.textview.reader.util.FileSortUtils;
import com.textview.reader.util.FileUtils;
import com.textview.reader.util.PrefsManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class MainFolderLoadController {
    private static final int FOLDER_LOAD_INITIAL_PROGRESS_BATCH = 32;
    private static final int FOLDER_LOAD_PROGRESS_BATCH = 192;
    private static final long FOLDER_LOAD_INITIAL_PROGRESS_MIN_INTERVAL_MS = 80L;
    private static final long FOLDER_LOAD_PROGRESS_MIN_INTERVAL_MS = 280L;

    private final MainActivity activity;
    private final Object executorLock = new Object();
    private final AtomicInteger generation = new AtomicInteger(0);
    private ThreadPoolExecutor executor = createExecutor();
    private String pendingRevealFilePath;

    MainFolderLoadController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void cancelPendingFolderLoad() {
        generation.incrementAndGet();
        synchronized (executorLock) {
            executor.getQueue().clear();
        }
    }

    void loadDirectory(File dir) {
        if (dir == null) return;
        activity.exitFileSelectionMode(false);

        final int loadGeneration = generation.incrementAndGet();
        final File targetDir = dir;
        final boolean showHidden = activity.prefs != null && activity.prefs.getShowHiddenFiles();
        final int sortMode = currentSortMode();

        prepareBrowseTarget(targetDir, sortMode, true);

        submitPriorityFolderLoad(() -> {
            List<File> folderList = new ArrayList<>();
            List<File> visibleFiles = new ArrayList<>();
            FolderLoadProgress progress = new FolderLoadProgress(targetDir, loadGeneration, sortMode, true);
            try {
                File[] fileArray = targetDir.listFiles();
                if (fileArray != null) {
                    for (File f : fileArray) {
                        if (isCancelled(loadGeneration)) return;
                        if (!showHidden && f.getName().startsWith(".")) continue;
                        if (f.isDirectory() || FileUtils.isVisibleInAllFilesFilter(f.getName())) {
                            if (f.isDirectory()) folderList.add(f);
                            else visibleFiles.add(f);
                            maybePublishFolderLoadProgress(progress, folderList, visibleFiles);
                        }
                    }
                }
                List<File> fileList = new ArrayList<>(folderList.size() + visibleFiles.size());
                fileList.addAll(folderList);
                fileList.addAll(visibleFiles);
                final int finalSortMode = currentSortMode();
                sortFiles(fileList, finalSortMode);

                activity.fileSearchHandler.post(() -> applyFinalDirectoryLoad(targetDir, fileList, finalSortMode, loadGeneration));
            } catch (SecurityException ignored) {
                activity.fileSearchHandler.post(() -> applyDirectoryAccessDenied(targetDir, sortMode, loadGeneration));
            }
        });
    }

    void refreshCurrentDirectoryWithoutClearing(File dir) {
        if (dir == null) return;

        final int loadGeneration = generation.incrementAndGet();
        final File targetDir = dir;
        final boolean showHidden = activity.prefs != null && activity.prefs.getShowHiddenFiles();
        final int sortMode = currentSortMode();

        prepareBrowseTarget(targetDir, sortMode, false);

        submitPriorityFolderLoad(() -> {
            List<File> fileList = new ArrayList<>();
            int appliedSortMode = currentSortMode();
            try {
                File[] fileArray = targetDir.listFiles();
                if (fileArray != null) {
                    for (File f : fileArray) {
                        if (isCancelled(loadGeneration)) return;
                        if (!showHidden && f.getName().startsWith(".")) continue;
                        if (f.isDirectory() || FileUtils.isVisibleInAllFilesFilter(f.getName())) {
                            fileList.add(f);
                        }
                    }
                }
                appliedSortMode = currentSortMode();
                sortFiles(fileList, appliedSortMode);
            } catch (SecurityException ignored) {
                fileList.clear();
                appliedSortMode = currentSortMode();
            }

            final int finalSortMode = appliedSortMode;
            activity.fileSearchHandler.post(() -> applyRefreshDirectory(targetDir, fileList, finalSortMode, loadGeneration));
        });
    }

    void resortVisibleFileListAsync(int sortMode) {
        if (activity.fileAdapter == null) return;
        final int searchGeneration = activity.fileSearchGeneration.incrementAndGet();
        final boolean showPath = activity.searchMode;
        final ArrayList<File> snapshot = activity.fileAdapter.getFilesSnapshot();
        if (activity.fileSearchProgress != null) activity.fileSearchProgress.setVisibility(View.VISIBLE);
        activity.fileAdapter.setSortModeSilently(sortMode);

        activity.fileSearchExecutor.execute(() -> {
            ArrayList<File> sorted = new ArrayList<>(snapshot);
            sortFiles(sorted, sortMode);
            activity.fileSearchHandler.post(() -> {
                if (activity.activityDestroyed || searchGeneration != activity.fileSearchGeneration.get()) return;
                if (activity.fileAdapter != null) {
                    activity.fileAdapter.setShowFilePath(showPath);
                    activity.fileAdapter.setSortModeSilently(sortMode);
                    activity.fileAdapter.setFilesFastPresorted(sorted);
                }
                if (activity.fileSearchProgress != null) activity.fileSearchProgress.setVisibility(View.GONE);
                activity.scrollListToTop(activity.fileRecyclerView);
            });
        });
    }

    void setPendingRevealPath(String revealPath) {
        pendingRevealFilePath = revealPath;
    }

    void executeBackgroundTask(@NonNull Runnable task) {
        synchronized (executorLock) {
            executor.execute(task);
        }
    }

    void shutdownNow() {
        generation.incrementAndGet();
        synchronized (executorLock) {
            executor.shutdownNow();
        }
    }

    private void prepareBrowseTarget(@NonNull File targetDir, int sortMode, boolean clearList) {
        activity.currentDirectory = targetDir;
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
            executor.getQueue().clear();
            if (executor.getActiveCount() > 0) {
                executor.shutdownNow();
                executor = createExecutor();
            }
            executor.execute(task);
        }
    }

    private void maybePublishFolderLoadProgress(@NonNull FolderLoadProgress progress,
                                                @NonNull List<File> folders,
                                                @NonNull List<File> files) {
        int size = folders.size() + files.size();
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
        ArrayList<File> snapshot = new ArrayList<>(size);
        snapshot.addAll(folders);
        snapshot.addAll(files);
        activity.fileSearchHandler.post(() -> publishFolderLoadSnapshot(progress, snapshot));
    }

    private void publishFolderLoadSnapshot(@NonNull FolderLoadProgress progress, @NonNull List<File> snapshot) {
        if (isCancelled(progress.generation)) return;
        if (!progress.targetDir.equals(activity.currentDirectory)) return;
        if (activity.fileAdapter != null) {
            activity.fileAdapter.setShowFilePath(false);
            activity.fileAdapter.setSortModeSilently(progress.sortMode);
            activity.fileAdapter.setFilesFastPresorted(snapshot);
        }
        if (progress.scrollTop) {
            progress.scrollTop = false;
            activity.scrollListToTop(activity.fileRecyclerView);
        }
        if (activity.emptyText != null) activity.emptyText.setVisibility(View.GONE);
    }

    private void applyFinalDirectoryLoad(@NonNull File targetDir,
                                         @NonNull List<File> fileList,
                                         int sortMode,
                                         int loadGeneration) {
        if (isCancelled(loadGeneration)) return;
        if (!targetDir.equals(activity.currentDirectory)) return;

        if (activity.fileAdapter != null) {
            activity.fileAdapter.setShowFilePath(false);
            activity.fileAdapter.setSortModeSilently(sortMode);
            activity.fileAdapter.setFilesFastPresorted(fileList);
        }
        if (!scrollToPendingRevealFile(targetDir, fileList)) {
            activity.scrollListToTop(activity.fileRecyclerView);
        }
        updateEmptyState(fileList.isEmpty());
        activity.markCurrentBrowseFolderLoadComplete(targetDir, fileList, sortMode);
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
        activity.markCurrentBrowseFolderLoadFailed(targetDir);
        if (activity.fileSearchProgress != null) activity.fileSearchProgress.setVisibility(View.GONE);
    }

    private void applyRefreshDirectory(@NonNull File targetDir,
                                       @NonNull List<File> fileList,
                                       int sortMode,
                                       int loadGeneration) {
        if (isCancelled(loadGeneration)) return;
        if (!targetDir.equals(activity.currentDirectory)) return;

        if (activity.fileAdapter != null) {
            activity.fileAdapter.setShowFilePath(false);
            activity.fileAdapter.setSortModeSilently(sortMode);
            activity.fileAdapter.setFiles(fileList);
        }
        updateEmptyState(fileList.isEmpty());
        activity.markCurrentBrowseFolderLoadComplete(targetDir, fileList, sortMode);
        if (activity.fileSearchProgress != null) activity.fileSearchProgress.setVisibility(View.GONE);
    }

    private void updateEmptyState(boolean empty) {
        if (activity.emptyText == null) return;
        activity.emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) activity.emptyText.setText(activity.getString(R.string.no_text_files_in_directory));
    }

    private boolean scrollToPendingRevealFile(@NonNull File targetDir, @NonNull List<File> fileList) {
        if (pendingRevealFilePath == null || pendingRevealFilePath.trim().isEmpty()) return false;

        File pendingFile = new File(pendingRevealFilePath);
        File parent = pendingFile.getParentFile();
        if (parent == null || !targetDir.equals(parent)) return false;

        String targetPath = pendingFile.getAbsolutePath();
        int targetIndex = -1;
        for (int i = 0; i < fileList.size(); i++) {
            if (targetPath.equals(fileList.get(i).getAbsolutePath())) {
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

    private void sortFiles(@NonNull List<File> target, int sortMode) {
        FileSortUtils.sortMainFiles(activity, target, sortMode);
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

        FolderLoadProgress(@NonNull File targetDir, int generation, int sortMode, boolean scrollTop) {
            this.targetDir = targetDir;
            this.generation = generation;
            this.sortMode = sortMode;
            this.scrollTop = scrollTop;
        }
    }
}
