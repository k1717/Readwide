package com.textview.reader;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.textview.reader.adapter.FileAdapter;
import com.textview.reader.util.FileClipboardController;
import com.textview.reader.util.FileOperationProgress;
import com.textview.reader.util.FileSystemOps;
import com.textview.reader.util.FileTreeProgressTracker;

import java.io.File;
import java.util.ArrayList;

final class MainSelectionModeController {
    private final MainActivity activity;

    MainSelectionModeController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void enterFileSelectionMode(@NonNull File firstFile) {
        if (activity.fileSelectionMode) {
            activity.selectedFilePaths.add(firstFile.getAbsolutePath());
            updateFileSelectionUi();
            return;
        }
        activity.fileSelectionMode = true;
        activity.selectedFilePaths.clear();
        activity.selectedFilePaths.add(firstFile.getAbsolutePath());
        updateFileSelectionUi();
    }

    void toggleFileSelection(@NonNull File file) {
        String path = file.getAbsolutePath();
        if (activity.selectedFilePaths.contains(path)) {
            activity.selectedFilePaths.remove(path);
        } else {
            activity.selectedFilePaths.add(path);
        }
        if (activity.selectedFilePaths.isEmpty()) {
            exitFileSelectionMode(true);
        } else {
            updateFileSelectionUi();
        }
    }

    void exitFileSelectionMode(boolean restoreToolbar) {
        boolean wasSelectionMode = activity.fileSelectionMode || !activity.selectedFilePaths.isEmpty();
        if (!wasSelectionMode) return;
        activity.fileSelectionMode = false;
        activity.selectedFilePaths.clear();
        updateFileSelectionUi();
        if (restoreToolbar) restoreMainToolbarAfterSelection();
        else activity.installToolbarMenuButton(activity.mainToolbar);
    }

    private void updateFileSelectionUi() {
        if (activity.fileAdapter != null) activity.fileAdapter.setSelectionState(activity.fileSelectionMode, activity.selectedFilePaths);
        if (activity.recentAdapter != null) activity.recentAdapter.setSelectionState(activity.fileSelectionMode, activity.selectedFilePaths);
        if (activity.fileSelectionMode) applyFileSelectionToolbar();
        else activity.updateMainOverflowButtonVisibility();
    }

    void applyFileSelectionToolbar() {
        Toolbar toolbar = activity.mainToolbar;
        if (toolbar == null) return;
        if (activity.drawerToggle != null) activity.drawerToggle.setDrawerIndicatorEnabled(false);
        Drawable nav = ContextCompat.getDrawable(activity, R.drawable.ic_bottom_arrow_left);
        if (nav != null) {
            Drawable wrapped = DrawableCompat.wrap(nav.mutate());
            DrawableCompat.setTint(wrapped, Color.WHITE);
            toolbar.setNavigationIcon(wrapped);
        }
        toolbar.setNavigationContentDescription(R.string.clear_selection);
        toolbar.setNavigationOnClickListener(v -> exitFileSelectionMode(true));
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle(activity.getString(R.string.file_selection_count, activity.selectedFilePaths.size()));
        } else {
            toolbar.setTitle(activity.getString(R.string.file_selection_count, activity.selectedFilePaths.size()));
        }
        activity.updateMainOverflowButtonVisibility();
    }

    private void restoreMainToolbarAfterSelection() {
        activity.installToolbarMenuButton(activity.mainToolbar);
        if (activity.getSupportActionBar() == null) return;
        if (activity.searchMode) {
            activity.getSupportActionBar().setTitle(R.string.file_search);
        } else if (activity.homeMode) {
            activity.getSupportActionBar().setTitle(R.string.app_name);
        } else if (activity.currentDirectory != null) {
            activity.getSupportActionBar().setTitle(activity.currentDirectory.getName().isEmpty() ? "/" : activity.currentDirectory.getName());
        } else {
            activity.getSupportActionBar().setTitle(R.string.app_name);
        }
    }

    @NonNull
    ArrayList<File> getSelectedFilesSnapshot() {
        ArrayList<File> result = new ArrayList<>();
        for (String path : new ArrayList<>(activity.selectedFilePaths)) {
            if (path == null || path.trim().isEmpty()) continue;
            result.add(new File(path));
        }
        return result;
    }

    @NonNull
    ArrayList<File> getSelectedShareableFilesSnapshot() {
        ArrayList<File> result = new ArrayList<>();
        for (File file : getSelectedFilesSnapshot()) {
            if (file != null && file.exists() && file.isFile() && file.canRead()) {
                result.add(file);
            }
        }
        return result;
    }

    @NonNull
    ArrayList<File> getSelectedArchiveFilesSnapshot() {
        ArrayList<File> selected = getSelectedFilesSnapshot();
        ArrayList<File> archives = new ArrayList<>();
        if (selected.isEmpty()) return archives;
        for (File file : selected) {
            if (file == null
                    || !file.exists()
                    || !file.isFile()
                    || !file.canRead()
                    || !activity.isSupportedArchive(file)) {
                archives.clear();
                return archives;
            }
            archives.add(file);
        }
        return archives;
    }

    @Nullable
    File getSingleSelectedFile() {
        ArrayList<File> selected = getSelectedFilesSnapshot();
        return selected.size() == 1 ? selected.get(0) : null;
    }

    void selectAllVisibleFiles() {
        FileAdapter visibleAdapter = activity.homeMode && !activity.searchMode ? activity.recentAdapter : activity.fileAdapter;
        if (visibleAdapter == null) return;
        for (File file : visibleAdapter.getFilesSnapshot()) {
            if (file != null) activity.selectedFilePaths.add(file.getAbsolutePath());
        }
        if (!activity.selectedFilePaths.isEmpty()) {
            activity.fileSelectionMode = true;
            updateFileSelectionUi();
        }
    }

    void startSelectedClipboardOperation(boolean copy) {
        ArrayList<File> selected = getSelectedFilesSnapshot();
        int started = 0;
        for (File file : selected) {
            if (file == null || !file.exists() || !(file.isFile() || file.isDirectory())) continue;
            FileClipboardController.StartResult result = activity.fileClipboardController.start(file, copy);
            if (result == FileClipboardController.StartResult.STARTED) started++;
        }
        if (started <= 0) {
            ShortToast.show(activity, R.string.file_operation_source_unavailable);
            exitFileSelectionMode(true);
            return;
        }
        activity.archiveExtractInProgress = false;
        exitFileSelectionMode(true);
        activity.updateMainOverflowButtonVisibility();
        Toast.makeText(activity,
                activity.getString(copy ? R.string.selected_files_copy_started : R.string.selected_files_move_started, started),
                Toast.LENGTH_LONG).show();
    }

    void startSelectedArchiveExtraction() {
        ArrayList<File> archives = getSelectedArchiveFilesSnapshot();
        if (archives.isEmpty()) {
            ShortToast.show(activity, R.string.archive_extract_failed);
            exitFileSelectionMode(true);
            return;
        }
        exitFileSelectionMode(true);
        activity.startArchiveExtractions(archives);
    }

    boolean isRecentFileSelectionContext() {
        return activity.homeMode && !activity.searchMode;
    }

    void removeSelectedFilesFromRecentList() {
        ArrayList<File> selected = getSelectedFilesSnapshot();
        if (selected.isEmpty() || activity.bookmarkManager == null) {
            exitFileSelectionMode(true);
            return;
        }
        int removed = 0;
        for (File file : selected) {
            if (file == null) continue;
            activity.bookmarkManager.deleteReadingState(file.getAbsolutePath());
            removed++;
        }
        exitFileSelectionMode(true);
        activity.loadRecentFiles();
        ShortToast.show(activity, removed > 0
                ? activity.getString(R.string.selected_recent_files_cleared, removed)
                : activity.getString(R.string.no_recent_files_to_clear));
    }

    void showSelectedDeleteConfirm() {
        ArrayList<File> selected = getSelectedFilesSnapshot();
        if (selected.isEmpty()) {
            exitFileSelectionMode(true);
            return;
        }
        activity.showSimpleConfirmDialog(
                activity.getString(R.string.delete),
                activity.getString(R.string.delete_selected_files_confirm, selected.size()),
                activity.getString(R.string.delete),
                () -> deleteSelectedFiles(selected));
    }

    private int countSelectedFolders(@NonNull ArrayList<File> selected) {
        int count = 0;
        for (File file : selected) {
            if (file != null && file.isDirectory()) count++;
        }
        return count;
    }

    private int countExistingSelectedItems(@NonNull ArrayList<File> selected) {
        int count = 0;
        for (File file : selected) {
            if (file != null && file.exists()) count++;
        }
        return count;
    }

    private void deleteSelectedFiles(@NonNull ArrayList<File> selected) {
        if (selected.isEmpty()) return;
        // The delete worker uses a snapshot of the selected files, so the UI no
        // longer needs to remain in multi-select mode after the user confirms.
        // Leaving selection mode active hides the background operation-progress
        // toolbar button; if the user pauses the delete job and backgrounds the
        // dialog, there is no immediate way to reopen it until another folder
        // navigation refreshes the toolbar. Exit selection mode up front so the
        // normal toolbar can expose the active progress button immediately.
        exitFileSelectionMode(true);
        ArrayList<String> deletedPaths = new ArrayList<>();
        FileOperationProgress progress = activity.showFileOperationProgress(
                activity.getString(R.string.file_deleting),
                activity.getString(R.string.file_selection_count, selected.size()));
        if (activity.currentDirectory != null) {
            progress.setFolder(activity.currentDirectory.getName().length() > 0
                    ? activity.currentDirectory.getName()
                    : activity.currentDirectory.getAbsolutePath());
        }
        activity.executeFolderBackgroundTask(() -> {
            long totalBytes = 0L;
            for (File file : selected) {
                if (file == null) continue;
                totalBytes += FileSystemOps.measureBytes(file);
                if (totalBytes < 0L) {
                    totalBytes = Long.MAX_VALUE;
                    break;
                }
            }
            progress.setTotalBytes(totalBytes);
            FileTreeProgressTracker treeProgress = FileTreeProgressTracker.create(progress, selected);
            int deletedCount = 0;
            for (File file : selected) {
                if (file == null || !file.exists()) continue;
                String path = file.getAbsolutePath();
                boolean wasDirectory = file.isDirectory();
                if (FileSystemOps.delete(file, progress, false, treeProgress)) {
                    deletedCount++;
                    deletedPaths.add(path);
                    if (activity.bookmarkManager != null) activity.bookmarkManager.deleteReadingState(path);
                    activity.cleanupNavigationStateAfterDelete(path, wasDirectory);
                }
                if (progress.isCancelled()) break;
            }
            final int finalDeletedCount = deletedCount;
            activity.fileSearchHandler.post(() -> {
                activity.finishFileOperationProgress(progress);
                if (progress.isCancelled()) {
                    activity.refreshVisibleFileListAfterDelete();
                    ShortToast.show(activity, R.string.file_operation_cancelled);
                    return;
                }
                if (activity.currentDirectory != null && activity.currentDirectory.exists() && activity.currentDirectory.isDirectory() && !activity.homeMode) {
                    activity.loadDirectory(activity.currentDirectory);
                } else {
                    activity.loadRecentFiles();
                }
                activity.rebuildDrawerStorageEntries();
                ShortToast.show(activity, finalDeletedCount > 0
                                ? activity.getString(R.string.selected_files_deleted, finalDeletedCount)
                                : activity.getString(R.string.selected_files_delete_failed));
            });
        });
    }
}
