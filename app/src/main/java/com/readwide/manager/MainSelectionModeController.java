package com.readwide.manager;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.readwide.manager.adapter.FileAdapter;
import com.readwide.manager.archive.ArchiveCancellation;
import com.readwide.manager.util.FileClipboardController;
import com.readwide.manager.util.FileDeletionPlan;
import com.readwide.manager.util.FileOperationProgress;
import com.readwide.manager.util.FileTreeProgressTracker;
import com.readwide.manager.util.TaskSubmission;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class MainSelectionModeController {
    private final MainActivity activity;
    private boolean preparingDelete;

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
        if (activity.activityDestroyed || preparingDelete) return;
        ArrayList<File> selected = getSelectedFilesSnapshot();
        if (selected.isEmpty()) {
            exitFileSelectionMode(true);
            return;
        }
        preparingDelete = true;
        try {
            TaskSubmission.submit(activity.folderValidationExecutor, () -> {
                Map<File, FileDeletionPlan> plans = new LinkedHashMap<>();
                try {
                    for (File file : selected) plans.put(file, FileDeletionPlan.capture(file));
                } catch (java.io.IOException | RuntimeException | OutOfMemoryError | StackOverflowError failure) {
                    ArchiveCancellation.restoreIfCancelled(failure, null);
                }
                activity.runOnUiThread(() -> {
                    preparingDelete = false;
                    if (activity.activityDestroyed || activity.isFinishing()) return;
                    if (plans.size() == selected.size()) showSelectedDeleteConfirm(selected, plans);
                    else if (!activity.offerSafDeleteAccess(selected)) {
                        ShortToast.show(activity, R.string.selected_files_delete_failed);
                    }
                });
            });
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError failure) {
            preparingDelete = false;
            ShortToast.show(activity, R.string.selected_files_delete_failed);
        }
    }

    private void showSelectedDeleteConfirm(ArrayList<File> selected, Map<File, FileDeletionPlan> plans) {
        String message = activity.getString(R.string.delete_selected_files_confirm, selected.size());
        int folderCount = countSelectedFolders(selected);
        if (folderCount > 0) {
            message = message + "\n\n"
                    + activity.getString(R.string.delete_selected_includes_folders_warning, folderCount);
        }
        activity.showSimpleConfirmDialog(
                activity.getString(R.string.delete),
                message,
                activity.getString(R.string.delete),
                () -> deleteSelectedFiles(selected, plans));
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

    private void deleteSelectedFiles(@NonNull ArrayList<File> selected,
                                     @NonNull Map<File, FileDeletionPlan> plans) {
        if (activity.activityDestroyed || selected.isEmpty()) return;
        // The delete worker uses a snapshot of the selected files, so the UI no
        // longer needs to remain in multi-select mode after the user confirms.
        // Leaving selection mode active hides the background operation-progress
        // toolbar button; if the user pauses the delete job and backgrounds the
        // dialog, there is no immediate way to reopen it until another folder
        // navigation refreshes the toolbar. Exit selection mode up front so the
        // normal toolbar can expose the active progress button immediately.
        exitFileSelectionMode(true);
        FileOperationProgress progress = activity.showFileOperationProgress(
                activity.getString(R.string.file_deleting),
                activity.getString(R.string.file_selection_count, selected.size()));
        if (activity.currentDirectory != null) {
            progress.setFolder(activity.currentDirectory.getName().length() > 0
                    ? activity.currentDirectory.getName()
                    : activity.currentDirectory.getAbsolutePath());
        }
        boolean accepted = activity.executeFolderBackgroundTask(() -> {
            int deletedCount = 0;
            boolean changed = false;
            ArrayList<File> failedItems = new ArrayList<>(selected);
            try {
                FileTreeProgressTracker treeProgress = FileTreeProgressTracker.create(progress, selected);
                progress.setTotalBytes(treeProgress.totalBytes());
                for (File file : selected) {
                    if (!treeProgress.isReady() || !progress.checkpoint()) break;
                    if (file == null) continue;
                    String path = file.getAbsolutePath();
                    FileDeletionPlan plan = plans.get(file);
                    FileDeletionPlan.Result result = plan == null ? FileDeletionPlan.Result.FAILED
                            : plan.delete(progress, treeProgress);
                    if (result == FileDeletionPlan.Result.DELETED) {
                        deletedCount++;
                        failedItems.remove(file);
                        // These are synchronized/persistent metadata updates, not
                        // view operations. Keep bulk writes off the UI thread.
                        if (activity.bookmarkManager != null) activity.bookmarkManager.deleteReadingState(path);
                        activity.cleanupNavigationStateAfterDelete(path, plan.isDirectory());
                    } else if (result == FileDeletionPlan.Result.CHANGED) {
                        changed = true;
                        failedItems.remove(file);
                    }
                    if (progress.isCancelled()) break;
                }
            } catch (RuntimeException failure) {
                ArchiveCancellation.restoreIfCancelled(failure, progress);
            } finally {
                final int finalDeletedCount = deletedCount;
                final boolean changedItems = changed;
                activity.runOnUiThread(() -> {
                    activity.finishFileOperationProgress(progress);
                    if (activity.activityDestroyed) return;
                    if (progress.isCancelled()) {
                        activity.refreshVisibleFileListAfterDelete();
                        ShortToast.show(activity, R.string.file_operation_cancelled);
                        return;
                    }
                    activity.refreshVisibleFileListAfterFileSystemChange();
                    ShortToast.show(activity, changedItems ? activity.getString(R.string.raw_delete_changed)
                                    : finalDeletedCount > 0
                                    ? activity.getString(R.string.selected_files_deleted, finalDeletedCount)
                                    : activity.getString(R.string.selected_files_delete_failed));
                    activity.offerSafDeleteAccess(failedItems);
                });
            }
        });
        if (!accepted) activity.finishFileOperationProgress(progress);
    }
}
