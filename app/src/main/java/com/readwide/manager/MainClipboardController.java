package com.readwide.manager;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.readwide.manager.archive.ArchiveCancellation;
import com.readwide.manager.util.FileClipboardBatch;
import com.readwide.manager.util.FileClipboardController;
import com.readwide.manager.util.FileOperationProgress;
import com.readwide.manager.util.FileSystemOps;
import com.readwide.manager.util.TaskSubmission;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

final class MainClipboardController {
    private final MainActivity activity;
    private boolean preparingOverwrite;

    MainClipboardController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void startFileClipboardOperation(@NonNull File source, boolean copy) {
        if (activity.activityDestroyed) return;
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
        if (activity.activityDestroyed || activity.fileClipboardController.isInProgress()
                || activity.archiveExtractInProgress || activity.archiveCreateInProgress) return;
        activity.fileClipboardController.cancel();
        activity.pendingExtractArchives.clear();
        activity.pendingExtractArchive = null;
        activity.pendingArchiveCreations.clear();
        activity.pendingArchiveCreation = null;
        activity.updateMainOverflowButtonVisibility();
    }

    void cancelPendingClipboardOperation(long pendingId) {
        if (activity.activityDestroyed || activity.fileClipboardController.isInProgress()) return;
        activity.fileClipboardController.cancel(pendingId);
        activity.updateMainOverflowButtonVisibility();
        ShortToast.show(activity, R.string.file_operation_cancelled);
    }

    void pastePendingClipboardItemToCurrentDirectory() {
        if (activity.activityDestroyed || activity.fileClipboardController.isInProgress()) return;
        FileClipboardController.PendingItem item = activity.fileClipboardController.getActiveItem();
        FileClipboardController.PastePlan plan = activity.fileClipboardController.preparePaste(item, activity.currentDirectory);
        File source = plan.getSource();
        switch (plan.getStatus()) {
            case NO_SOURCE:
                return;
            case SOURCE_UNAVAILABLE:
                if (item != null) activity.fileClipboardController.clearAfterSuccess(item);
                activity.updateMainOverflowButtonVisibility();
                ShortToast.show(activity, R.string.file_operation_source_unavailable);
                activity.refreshVisibleFileListAfterFileSystemChange();
                return;
            case DESTINATION_UNAVAILABLE:
                if (!activity.offerSafFileOperationAccess(Collections.singletonList(plan.getDestinationDir()))) {
                    ShortToast.show(activity, R.string.file_move_destination_unavailable);
                }
                return;
            case CUT_SAME_FOLDER:
                ShortToast.show(activity, source != null && source.isDirectory() ? R.string.folder_move_same_folder : R.string.file_move_same_folder);
                return;
            case DIRECTORY_INTO_SELF:
                ShortToast.show(activity, activity.fileClipboardController.isCopy() ? R.string.folder_copy_into_self : R.string.folder_move_into_self);
                return;
            case CONFLICT:
                if (source != null && plan.getDestinationDir() != null && plan.getDestination() != null) {
                    showPasteConfirmDialog(item, plan.getDestinationDir(), plan.getDestination(), true);
                }
                return;
            case READY:
                if (source != null && plan.getDestinationDir() != null && plan.getDestination() != null) {
                    showPasteConfirmDialog(item, plan.getDestinationDir(), plan.getDestination(), false);
                }
                return;
        }
    }

    void pasteAllPendingClipboardItemsToCurrentDirectory() {
        if (activity.activityDestroyed || activity.fileClipboardController.isInProgress()) return;
        File destinationDir = activity.currentDirectory;
        if (destinationDir == null || !destinationDir.exists()
                || !destinationDir.isDirectory() || !destinationDir.canWrite()) {
            if (!activity.offerSafFileOperationAccess(Collections.singletonList(destinationDir))) {
                ShortToast.show(activity, R.string.file_move_destination_unavailable);
            }
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

    private void showPasteConfirmDialog(@NonNull FileClipboardController.PendingItem item,
                                        @NonNull File destinationDir,
                                        @NonNull File destination,
                                        boolean hasConflict) {
        if (!canStart(item)) return;
        File source = item.getSource();
        final boolean copy = item.isCopy();
        final String titleText = activity.getString(copy ? R.string.confirm_copy_here_title : R.string.confirm_move_here_title);
        final String messageText = activity.getString(copy ? R.string.confirm_copy_here_message : R.string.confirm_move_here_message,
                source.getName(), destinationDir.getName().length() > 0 ? destinationDir.getName() : destinationDir.getAbsolutePath());
        activity.showSimpleConfirmDialog(titleText, messageText, activity.getString(copy ? R.string.copy : R.string.move), () -> {
            if (!canStart(item)) return;
            if (hasConflict) showClipboardTargetExistsDialog(item, destinationDir, destination);
            else continuePendingClipboardOperation(item, destinationDir, destination, false);
        });
    }

    private void showClipboardTargetExistsDialog(@NonNull FileClipboardController.PendingItem item,
                                                @NonNull File destinationDir,
                                                @NonNull File existingTarget) {
        if (!canStart(item) || preparingOverwrite) return;
        if (!activity.fileClipboardController.canOverwrite(item, existingTarget)) {
            showClipboardTargetExistsDialog(item, destinationDir, existingTarget, null);
            return;
        }
        preparingOverwrite = true;
        try {
            TaskSubmission.submit(activity.folderValidationExecutor, () -> {
                FileSystemOps.OverwriteTarget captured = null;
                try {
                    captured = FileSystemOps.captureOverwriteTarget(existingTarget);
                } catch (java.io.IOException | RuntimeException | OutOfMemoryError | StackOverflowError failure) {
                    ArchiveCancellation.restoreIfCancelled(failure, null);
                }
                final FileSystemOps.OverwriteTarget approval = captured;
                activity.runOnUiThread(() -> {
                    preparingOverwrite = false;
                    if (!canStart(item) || activity.isFinishing() || activity.isDestroyed()) return;
                    showClipboardTargetExistsDialog(item, destinationDir, existingTarget, approval);
                });
            });
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError failure) {
            preparingOverwrite = false;
            ShortToast.show(activity, R.string.file_operation_failed);
        }
    }

    private void showClipboardTargetExistsDialog(@NonNull FileClipboardController.PendingItem item,
                                                @NonNull File destinationDir,
                                                @NonNull File existingTarget,
                                                FileSystemOps.OverwriteTarget approval) {
        if (!canStart(item)) return;
        File source = item.getSource();

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
        if (approval != null && activity.fileClipboardController.canOverwrite(item, existingTarget)) {
            activity.addFileOpsRow(box, activity.getString(R.string.overwrite), fg, panel, () -> {
                if (ref[0] != null) ref[0].dismiss();
                continuePendingClipboardOperation(item, destinationDir, existingTarget, true, approval);
            });
        }
        activity.addFileOpsRow(box, activity.getString(R.string.create_copy), fg, panel, () -> {
            if (ref[0] != null) ref[0].dismiss();
            if (!canStart(item)) return;
            File copyDestination = activity.fileClipboardController.buildCopyDestination(destinationDir, source);
            if (copyDestination == null) {
                ShortToast.show(activity, R.string.file_operation_failed);
                return;
            }
            continuePendingClipboardOperation(item, destinationDir, copyDestination, false);
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

    private boolean canStart(FileClipboardController.PendingItem item) {
        return !activity.activityDestroyed && !activity.fileClipboardController.isInProgress()
                && activity.fileClipboardController.isPending(item);
    }

    private void continuePendingClipboardOperation(@NonNull FileClipboardController.PendingItem item,
                                                   @NonNull File destinationDir,
                                                   @NonNull File destination,
                                                   boolean overwrite) {
        continuePendingClipboardOperation(item, destinationDir, destination, overwrite, null);
    }

    private void continuePendingClipboardOperation(@NonNull FileClipboardController.PendingItem item,
                                                   @NonNull File destinationDir,
                                                   @NonNull File destination,
                                                   boolean overwrite,
                                                   FileSystemOps.OverwriteTarget approval) {
        if (!canStart(item)) return;
        FileClipboardBatch.Transfer transfer = new FileClipboardBatch.Transfer(item, destination);
        List<FileClipboardController.PendingItem> claimed = Collections.singletonList(item);
        activity.fileClipboardController.setItemsInProgress(claimed, true);
        activity.updateMainOverflowButtonVisibility();
        FileOperationProgress progress = activity.showFileOperationProgress(
                activity.getString(item.isCopy() ? R.string.file_copying : R.string.file_moving),
                transfer.source.getName());
        progress.setFolder(destinationDir.getName().isEmpty() ? destinationDir.getAbsolutePath() : destinationDir.getName());

        boolean accepted = activity.executeFolderBackgroundTask(() -> {
            FileSystemOps.TransferResult result = FileSystemOps.TransferResult.FAILED;
            try {
                result = activity.fileClipboardController.performOperationWithResult(
                        item, destination, overwrite, progress, true, null, approval);
            } catch (RuntimeException failure) {
                ArchiveCancellation.restoreIfCancelled(failure, progress);
            } finally {
                final FileSystemOps.TransferResult completed = result;
                // Independent of search-handler teardown: shared claims and
                // committed metadata must still be settled after screen closure.
                activity.runOnUiThread(() -> finishSinglePaste(transfer, destinationDir, progress, completed));
            }
        }, () -> activity.fileClipboardController.setItemsInProgress(claimed, false));
        if (!accepted) finishClipboardClaim(claimed, progress);
    }

    private void finishSinglePaste(FileClipboardBatch.Transfer transfer, File destinationDir,
                                   FileOperationProgress progress, FileSystemOps.TransferResult result) {
        boolean completed = result == FileSystemOps.TransferResult.COMPLETED;
        boolean sourceRemaining = result == FileSystemOps.TransferResult.COPIED_SOURCE_REMAINS;
        finishClipboardClaim(Collections.singletonList(transfer.item), progress);
        if (completed || sourceRemaining) {
            activity.fileClipboardController.clearAfterSuccess(transfer.item);
            if (completed) updateMovedReferences(transfer);
            if (activity.prefs != null) activity.prefs.addRecentFolder(destinationDir.getAbsolutePath());
        }
        if (activity.activityDestroyed) return;
        activity.updateMainOverflowButtonVisibility();
        // A failed move can still leave a committed copy or partially retained
        // source, so the visible listing must reflect both completion outcomes.
        activity.refreshVisibleFileListAfterFileSystemChange();
        if (sourceRemaining) {
            showSourceRemainingWarning(activity.getString(R.string.file_move_source_remaining_message,
                    transfer.destination.getAbsolutePath()));
            return;
        }
        if (!completed) {
            ShortToast.show(activity, progress.isCancelled() ? R.string.file_operation_cancelled
                    : transfer.item.isCopy() ? R.string.file_copy_failed : R.string.file_move_failed);
            if (!progress.isCancelled()) {
                activity.offerSafFileOperationAccess(java.util.Arrays.asList(transfer.source, transfer.destination));
            }
            return;
        }
        ShortToast.show(activity, transfer.item.isCopy() ? R.string.file_copied : R.string.file_moved);
    }

    private void continueAllPendingClipboardOperations(@NonNull File destinationDir,
                                                       @NonNull List<FileClipboardController.PendingItem> items) {
        if (activity.activityDestroyed || activity.fileClipboardController.isInProgress()) return;
        List<FileClipboardController.PendingItem> claimed = new ArrayList<>(items);
        claimed.retainAll(new HashSet<>(activity.fileClipboardController.getPendingItems()));
        if (claimed.isEmpty()) return;
        activity.fileClipboardController.setItemsInProgress(claimed, true);
        activity.updateMainOverflowButtonVisibility();
        FileOperationProgress progress = activity.showFileOperationProgress(
                activity.getString(R.string.file_batch_operating), destinationDir.getAbsolutePath());
        progress.setFolder(destinationDir.getName().isEmpty() ? destinationDir.getAbsolutePath() : destinationDir.getName());
        FileClipboardBatch batch = new FileClipboardBatch(activity.fileClipboardController, claimed, destinationDir, progress);

        boolean accepted = activity.executeFolderBackgroundTask(() -> {
            try {
                batch.run();
            } finally {
                activity.runOnUiThread(() -> finishBatchPaste(batch, claimed, destinationDir, progress));
            }
        }, () -> activity.fileClipboardController.setItemsInProgress(claimed, false));
        if (!accepted) finishClipboardClaim(claimed, progress);
    }

    private void finishBatchPaste(FileClipboardBatch batch, List<FileClipboardController.PendingItem> claimed,
                                  File destinationDir, FileOperationProgress progress) {
        finishClipboardClaim(claimed, progress);
        List<FileClipboardBatch.Transfer> completed = batch.completedTransfers();
        List<FileClipboardBatch.Transfer> sourceRemaining = batch.sourceRemainingTransfers();
        List<FileClipboardController.PendingItem> completedItems = new ArrayList<>(
                completed.size() + sourceRemaining.size());
        for (FileClipboardBatch.Transfer transfer : completed) completedItems.add(transfer.item);
        for (FileClipboardBatch.Transfer transfer : sourceRemaining) completedItems.add(transfer.item);
        // A partial move must not retry from a depleted source and replace its
        // complete destination. Only failures before commit remain retryable.
        activity.fileClipboardController.clearAfterSuccess(completedItems);
        for (FileClipboardBatch.Transfer transfer : completed) updateMovedReferences(transfer);
        if (activity.prefs != null && (!completed.isEmpty() || !sourceRemaining.isEmpty())) {
            activity.prefs.addRecentFolder(destinationDir.getAbsolutePath());
        }
        if (activity.activityDestroyed) return;
        activity.refreshVisibleFileListAfterFileSystemChange();
        activity.updateMainOverflowButtonVisibility();
        if (!sourceRemaining.isEmpty()) {
            showSourceRemainingWarning(activity.getString(R.string.file_move_source_remaining_batch,
                    sourceRemaining.size()));
        }
        if (progress.isCancelled()) {
            ShortToast.show(activity, R.string.file_operation_cancelled);
            return;
        }
        ShortToast.show(activity, activity.getString(
                completed.size() == batch.totalCount() ? R.string.file_batch_operation_done : R.string.file_batch_operation_partial,
                completed.size(), batch.totalCount()));
        if (sourceRemaining.isEmpty() && completed.size() < batch.totalCount()) {
            HashSet<FileClipboardController.PendingItem> settled = new HashSet<>(completedItems);
            List<File> failedPaths = new ArrayList<>();
            for (FileClipboardController.PendingItem item : claimed) {
                if (!settled.contains(item)) failedPaths.add(item.getSource());
            }
            if (!failedPaths.isEmpty()) {
                failedPaths.add(destinationDir);
                activity.offerSafFileOperationAccess(failedPaths);
            }
        }
    }

    private void showSourceRemainingWarning(String message) {
        activity.showSimpleConfirmDialog(activity.getString(R.string.file_move_source_remaining_title),
                message, activity.getString(android.R.string.ok), () -> { });
    }

    private void finishClipboardClaim(List<FileClipboardController.PendingItem> items, FileOperationProgress progress) {
        activity.fileClipboardController.setItemsInProgress(items, false);
        activity.finishFileOperationProgress(progress);
        if (!activity.activityDestroyed) activity.updateMainOverflowButtonVisibility();
    }

    private void updateMovedReferences(FileClipboardBatch.Transfer transfer) {
        if (transfer.item.isCopy()) return;
        String oldPath = transfer.source.getAbsolutePath();
        String newPath = transfer.destination.getAbsolutePath();
        if (activity.bookmarkManager != null) {
            activity.bookmarkManager.moveReferences(oldPath, newPath, transfer.sourceWasDirectory);
        }
        if (transfer.sourceWasDirectory && activity.prefs != null) {
            activity.prefs.moveFolderReferences(oldPath, newPath);
        }
    }
}
