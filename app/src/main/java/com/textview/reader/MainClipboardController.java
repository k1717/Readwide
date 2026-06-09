package com.textview.reader;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.textview.reader.util.FileClipboardController;
import com.textview.reader.util.FileOperationProgress;
import com.textview.reader.util.FileSystemOps;
import com.textview.reader.util.FileTreeProgressTracker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class MainClipboardController {
    private final MainActivity activity;

    MainClipboardController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void startFileClipboardOperation(@NonNull File source, boolean copy) {
        activity.archiveExtractInProgress = false;
        activity.archiveCreateInProgress = false;
        FileClipboardController.StartResult result = activity.fileClipboardController.start(source, copy);
        if (result != FileClipboardController.StartResult.STARTED) {
            ShortToast.show(activity, R.string.file_operation_source_unavailable);
            return;
        }

        activity.updateMainOverflowButtonVisibility();
        Toast.makeText(activity,
                copy ? R.string.file_copy_started : R.string.file_move_started,
                Toast.LENGTH_LONG).show();

        File parent = source.getParentFile();
        if (parent != null && parent.exists() && parent.isDirectory() && parent.canRead()) {
            activity.resetMainBrowseFiltersAndShow(parent, source.getAbsolutePath());
        } else {
            activity.updateMainOverflowButtonVisibility();
        }
    }

    void clearPendingActionQueue() {
        activity.fileClipboardController.cancel();
        activity.pendingExtractArchives.clear();
        activity.pendingExtractArchive = null;
        activity.pendingArchiveCreations.clear();
        activity.pendingArchiveCreation = null;
        activity.archiveExtractInProgress = false;
        activity.archiveCreateInProgress = false;
        activity.updateMainOverflowButtonVisibility();
    }

    void cancelPendingClipboardOperation(long pendingId) {
        activity.fileClipboardController.cancel(pendingId);
        activity.updateMainOverflowButtonVisibility();
        ShortToast.show(activity, R.string.file_operation_cancelled);
    }

    void pastePendingClipboardItemToCurrentDirectory() {
        FileClipboardController.PastePlan plan = activity.fileClipboardController.preparePaste(activity.currentDirectory);
        File source = plan.getSource();
        switch (plan.getStatus()) {
            case NO_SOURCE:
                return;
            case SOURCE_UNAVAILABLE:
                activity.fileClipboardController.clearAfterSuccess();
                activity.updateMainOverflowButtonVisibility();
                ShortToast.show(activity, R.string.file_operation_source_unavailable);
                refreshVisibleFileListAfterClipboardOperation(null);
                return;
            case DESTINATION_UNAVAILABLE:
                ShortToast.show(activity, R.string.file_move_destination_unavailable);
                return;
            case CUT_SAME_FOLDER:
                ShortToast.show(activity, source != null && source.isDirectory() ? R.string.folder_move_same_folder : R.string.file_move_same_folder);
                return;
            case DIRECTORY_INTO_SELF:
                ShortToast.show(activity, activity.fileClipboardController.isCopy() ? R.string.folder_copy_into_self : R.string.folder_move_into_self);
                return;
            case CONFLICT:
                if (source != null && plan.getDestinationDir() != null && plan.getDestination() != null) {
                    showPasteConfirmDialog(source, plan.getDestinationDir(), plan.getDestination(), true);
                }
                return;
            case READY:
                if (source != null && plan.getDestinationDir() != null && plan.getDestination() != null) {
                    showPasteConfirmDialog(source, plan.getDestinationDir(), plan.getDestination(), false);
                }
                return;
        }
    }

    void pasteAllPendingClipboardItemsToCurrentDirectory() {
        if (activity.fileClipboardController.isInProgress()) return;
        File destinationDir = activity.currentDirectory;
        if (destinationDir == null || !destinationDir.exists()
                || !destinationDir.isDirectory() || !destinationDir.canWrite()) {
            ShortToast.show(activity, R.string.file_move_destination_unavailable);
            return;
        }
        ArrayList<FileClipboardController.PendingItem> items =
                new ArrayList<>(activity.fileClipboardController.getPendingItems());
        if (items.isEmpty()) return;
        activity.showSimpleConfirmDialog(
                activity.getString(R.string.paste_all_here_title),
                activity.getString(R.string.paste_all_here_message,
                        items.size(),
                        destinationDir.getName().length() > 0 ? destinationDir.getName() : destinationDir.getAbsolutePath()),
                activity.getString(R.string.run_all),
                () -> continueAllPendingClipboardOperations(destinationDir, items));
    }

    private void showPasteConfirmDialog(@NonNull File source,
                                        @NonNull File destinationDir,
                                        @NonNull File destination,
                                        boolean hasConflict) {
        if (!activity.fileClipboardController.isSource(source)) return;
        final boolean copy = activity.fileClipboardController.isCopy();
        final String titleText = activity.getString(copy ? R.string.confirm_copy_here_title : R.string.confirm_move_here_title);
        final String messageText = activity.getString(copy ? R.string.confirm_copy_here_message : R.string.confirm_move_here_message,
                source.getName(), destinationDir.getName().length() > 0 ? destinationDir.getName() : destinationDir.getAbsolutePath());
        activity.showSimpleConfirmDialog(titleText, messageText, activity.getString(copy ? R.string.copy : R.string.move), () -> {
            if (!activity.fileClipboardController.isSource(source)) return;
            if (hasConflict) showClipboardTargetExistsDialog(source, destinationDir, destination);
            else continuePendingClipboardOperation(source, destinationDir, destination, false);
        });
    }

    private void showClipboardTargetExistsDialog(@NonNull File source,
                                                @NonNull File destinationDir,
                                                @NonNull File existingTarget) {
        if (!activity.fileClipboardController.isSource(source)) return;

        final boolean dark = activity.prefs == null || activity.prefs.shouldUseDarkColors(activity);
        final int bg = activity.prefs != null ? activity.prefs.getMainBgColor(activity) : (dark ? Color.rgb(33, 33, 33) : Color.rgb(255, 255, 255));
        final int panel = activity.prefs != null ? activity.prefs.getMainPanelColor(activity) : (dark ? Color.rgb(48, 48, 48) : Color.rgb(245, 245, 245));
        final int fg = activity.prefs != null ? activity.prefs.getMainTextColor(activity) : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        final int sub = activity.prefs != null ? activity.prefs.getMainSubTextColor(activity) : (dark ? Color.rgb(190, 190, 190) : Color.rgb(95, 99, 104));
        final int line = activity.prefs != null ? activity.prefs.getMainOutlineColor(activity) : (dark ? Color.rgb(92, 92, 92) : Color.rgb(210, 210, 210));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(activity.dpToPx(18), activity.dpToPx(16), activity.dpToPx(18), activity.dpToPx(10));
        GradientDrawable bgShape = new GradientDrawable();
        bgShape.setColor(bg);
        bgShape.setCornerRadius(activity.dpToPx(18));
        bgShape.setStroke(Math.max(1, activity.dpToPx(1)), line);
        box.setBackground(bgShape);

        TextView title = new TextView(activity);
        title.setText(activity.getString(source.isDirectory()
                ? R.string.folder_move_conflict_title
                : R.string.file_move_conflict_title));
        title.setTextColor(fg);
        title.setTextSize(21f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setPadding(activity.dpToPx(6), 0, activity.dpToPx(6), activity.dpToPx(8));
        box.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(activity);
        message.setText(activity.getString(source.isDirectory()
                        ? R.string.folder_move_conflict_message
                        : R.string.file_move_conflict_message,
                source.getName()));
        message.setTextColor(sub);
        message.setTextSize(14f);
        message.setGravity(Gravity.CENTER);
        message.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        message.setLineSpacing(activity.dpToPx(2), 1.0f);
        message.setPadding(activity.dpToPx(8), 0, activity.dpToPx(8), activity.dpToPx(14));
        box.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final android.app.Dialog[] ref = new android.app.Dialog[1];
        if (activity.fileClipboardController.canOverwrite(existingTarget)) {
            activity.addFileOpsRow(box, activity.getString(R.string.overwrite), fg, panel, () -> {
                if (ref[0] != null) ref[0].dismiss();
                continuePendingClipboardOperation(source, destinationDir, existingTarget, true);
            });
        }
        activity.addFileOpsRow(box, activity.getString(R.string.create_copy), fg, panel, () -> {
            if (ref[0] != null) ref[0].dismiss();
            File copyDestination = activity.fileClipboardController.buildCopyDestination(destinationDir, source);
            if (copyDestination == null) {
                ShortToast.show(activity, R.string.file_operation_failed);
                return;
            }
            continuePendingClipboardOperation(source, destinationDir, copyDestination, false);
        });

        TextView cancel = new TextView(activity);
        cancel.setText(activity.getString(R.string.cancel));
        cancel.setTextColor(sub);
        cancel.setTextSize(16f);
        cancel.setGravity(Gravity.CENTER);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        cancel.setPadding(activity.dpToPx(12), 0, activity.dpToPx(12), 0);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dpToPx(50));
        cancelLp.setMargins(0, activity.dpToPx(4), 0, 0);
        box.addView(cancel, cancelLp);

        android.app.Dialog dialog = activity.createStableCenterDialog(
                box,
                0,
                0.22f);
        ref[0] = dialog;
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void continuePendingClipboardOperation(@NonNull File source,
                                                   @NonNull File destinationDir,
                                                   @NonNull File destination,
                                                   boolean overwrite) {
        if (!activity.fileClipboardController.isSource(source)) return;
        if (activity.fileClipboardController.isInProgress()) return;
        final boolean copy = activity.fileClipboardController.isCopy();
        activity.fileClipboardController.setInProgress(true);
        activity.updateMainOverflowButtonVisibility();
        FileOperationProgress progress = activity.showFileOperationProgress(
                activity.getString(copy ? R.string.file_copying : R.string.file_moving),
                source.getName());
        progress.setFolder(destinationDir.getName().length() > 0 ? destinationDir.getName() : destinationDir.getAbsolutePath());

        String oldPath = source.getAbsolutePath();
        String newPath = destination.getAbsolutePath();
        boolean sourceWasDirectory = source.isDirectory();
        activity.executeFolderBackgroundTask(() -> {
            boolean done = activity.fileClipboardController.performOperation(destination, overwrite, progress);
            activity.fileSearchHandler.post(() -> {
                activity.fileClipboardController.setInProgress(false);
                activity.finishFileOperationProgress(progress);
                if (activity.activityDestroyed) return;
                if (progress.isCancelled()) {
                    activity.updateMainOverflowButtonVisibility();
                    ShortToast.show(activity, R.string.file_operation_cancelled);
                    return;
                }
                if (!done) {
                    activity.updateMainOverflowButtonVisibility();
                    ShortToast.show(activity, copy ? R.string.file_copy_failed : R.string.file_move_failed);
                    return;
                }

                activity.fileClipboardController.clearAfterSuccess();
                if (!copy && activity.bookmarkManager != null) {
                    if (sourceWasDirectory) activity.bookmarkManager.movePathPrefixReferences(oldPath, newPath);
                    else activity.bookmarkManager.moveFileReferences(oldPath, newPath);
                }
                if (activity.prefs != null) {
                    activity.prefs.addRecentFolder(destinationDir.getAbsolutePath());
                }
                refreshVisibleFileListAfterClipboardOperation(destinationDir);
                activity.updateMainOverflowButtonVisibility();
                ShortToast.show(activity, copy ? R.string.file_copied : R.string.file_moved);
            });
        });
    }

    private void continueAllPendingClipboardOperations(@NonNull File destinationDir,
                                                       @NonNull List<FileClipboardController.PendingItem> items) {
        if (activity.fileClipboardController.isInProgress()) return;
        activity.fileClipboardController.setItemsInProgress(items, true);
        activity.updateMainOverflowButtonVisibility();
        FileOperationProgress progress = activity.showFileOperationProgress(
                activity.getString(R.string.file_batch_operating),
                destinationDir.getAbsolutePath());
        progress.setFolder(destinationDir.getName().length() > 0 ? destinationDir.getName() : destinationDir.getAbsolutePath());

        activity.executeFolderBackgroundTask(() -> {
            ArrayList<BatchClipboardOperation> operations = new ArrayList<>();
            ArrayList<FileClipboardController.PendingItem> handledWithoutCopy = new ArrayList<>();
            long totalBytes = 0L;
            for (FileClipboardController.PendingItem item : items) {
                FileClipboardController.PastePlan plan = activity.fileClipboardController.preparePaste(item, destinationDir);
                File source = plan.getSource();
                File destination = plan.getDestination();
                switch (plan.getStatus()) {
                    case READY:
                        if (source != null && destination != null) {
                            operations.add(new BatchClipboardOperation(item, source, destination));
                            totalBytes = safeAddBytes(totalBytes, FileSystemOps.measureBytes(source));
                        }
                        break;
                    case CONFLICT:
                        if (source != null) {
                            File copyDestination = activity.fileClipboardController.buildCopyDestination(destinationDir, source);
                            if (copyDestination != null) {
                                operations.add(new BatchClipboardOperation(item, source, copyDestination));
                                totalBytes = safeAddBytes(totalBytes, FileSystemOps.measureBytes(source));
                            } else {
                                handledWithoutCopy.add(item);
                            }
                        }
                        break;
                    case SOURCE_UNAVAILABLE:
                    case CUT_SAME_FOLDER:
                    case DIRECTORY_INTO_SELF:
                        handledWithoutCopy.add(item);
                        break;
                    case NO_SOURCE:
                    case DESTINATION_UNAVAILABLE:
                        break;
                }
                if (progress.isCancelled()) break;
            }
            progress.setTotalBytes(totalBytes);
            int totalOperations = operations.size();
            if (totalOperations > 1) {
                progress.setFolder(displayName(destinationDir));
                progress.setFolderProgress(1, totalOperations);
            }
            FileTreeProgressTracker treeProgress = FileTreeProgressTracker.create(progress, sourcesForOperations(operations));

            ArrayList<BatchClipboardOperation> succeeded = new ArrayList<>();
            int failedCount = 0;
            int operationIndex = 0;
            for (BatchClipboardOperation op : operations) {
                if (progress.isCancelled()) break;
                operationIndex++;
                if (totalOperations > 1) {
                    progress.setFolder(operationFolderDisplayName(op, destinationDir));
                    progress.setFolderProgress(operationIndex, totalOperations);
                }
                boolean done = activity.fileClipboardController.performOperation(op.item, op.destination, false, progress, false, treeProgress);
                if (done) {
                    succeeded.add(op);
                } else if (!progress.isCancelled()) {
                    failedCount++;
                }
            }

            final int totalCount = items.size();
            final int finalFailedCount = failedCount;
            activity.fileSearchHandler.post(() -> {
                activity.fileClipboardController.setItemsInProgress(items, false);
                activity.finishFileOperationProgress(progress);
                if (activity.activityDestroyed) return;
                if (progress.isCancelled()) {
                    activity.updateMainOverflowButtonVisibility();
                    ShortToast.show(activity, R.string.file_operation_cancelled);
                    return;
                }

                for (BatchClipboardOperation op : succeeded) {
                    activity.fileClipboardController.clearAfterSuccess(op.item);
                    if (!op.item.isCopy() && activity.bookmarkManager != null) {
                        if (op.sourceWasDirectory) {
                            activity.bookmarkManager.movePathPrefixReferences(op.oldPath, op.newPath);
                        } else {
                            activity.bookmarkManager.moveFileReferences(op.oldPath, op.newPath);
                        }
                    }
                }
                for (FileClipboardController.PendingItem item : handledWithoutCopy) {
                    activity.fileClipboardController.clearAfterSuccess(item);
                }
                if (activity.prefs != null && !succeeded.isEmpty()) {
                    activity.prefs.addRecentFolder(destinationDir.getAbsolutePath());
                }
                refreshVisibleFileListAfterClipboardOperation(destinationDir);
                activity.updateMainOverflowButtonVisibility();
                ShortToast.show(activity, activity.getString(
                        finalFailedCount > 0 || succeeded.size() < totalCount
                                ? R.string.file_batch_operation_partial
                                : R.string.file_batch_operation_done,
                        succeeded.size(),
                        totalCount));
            });
        });
    }

    @NonNull
    private ArrayList<File> sourcesForOperations(@NonNull List<BatchClipboardOperation> operations) {
        ArrayList<File> sources = new ArrayList<>();
        for (BatchClipboardOperation operation : operations) {
            if (operation != null && operation.source != null) sources.add(operation.source);
        }
        return sources;
    }

    @NonNull
    private String operationFolderDisplayName(@NonNull BatchClipboardOperation op, @NonNull File destinationDir) {
        if (op.sourceWasDirectory && op.destination != null) return displayName(op.destination);
        return displayName(destinationDir);
    }

    @NonNull
    private String displayName(@NonNull File file) {
        String name = file.getName();
        return name == null || name.length() == 0 ? file.getAbsolutePath() : name;
    }

    private long safeAddBytes(long total, long next) {
        long result = total + Math.max(0L, next);
        return result < 0L ? Long.MAX_VALUE : result;
    }

    private static final class BatchClipboardOperation {
        final FileClipboardController.PendingItem item;
        final File source;
        final File destination;
        final String oldPath;
        final String newPath;
        final boolean sourceWasDirectory;

        BatchClipboardOperation(@NonNull FileClipboardController.PendingItem item,
                                @NonNull File source,
                                @NonNull File destination) {
            this.item = item;
            this.source = source;
            this.destination = destination;
            this.oldPath = source.getAbsolutePath();
            this.newPath = destination.getAbsolutePath();
            this.sourceWasDirectory = source.isDirectory();
        }
    }

    private void refreshVisibleFileListAfterClipboardOperation(File destinationDir) {
        if (destinationDir != null && !activity.homeMode && !activity.searchMode) {
            activity.loadDirectory(destinationDir);
        } else if (activity.homeMode) {
            activity.loadRecentFiles();
        } else if (activity.searchMode) {
            activity.runLiveFileSearchNow();
        } else if (activity.currentDirectory != null) {
            activity.loadDirectory(activity.currentDirectory);
        }
    }
}
