package com.textview.reader;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.archive.ArchiveSupport;
import com.textview.reader.util.FileOperationProgress;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class MainArchiveExtractController {
    private final MainActivity activity;

    MainArchiveExtractController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    boolean isSupportedArchive(@NonNull File file) {
        return ArchiveSupport.isSupportedArchive(file);
    }

    void startArchiveExtraction(@NonNull File archive) {
        ArrayList<File> archives = new ArrayList<>();
        archives.add(archive);
        startArchiveExtractions(archives);
    }

    void startArchiveExtractions(@NonNull List<File> archives) {
        ArrayList<File> ready = MainArchiveExtractionPlanner.collectReadyArchives(archives);
        if (ready.isEmpty()) {
            ShortToast.show(activity, R.string.archive_extract_failed);
            return;
        }
        for (int i = ready.size() - 1; i >= 0; i--) {
            addPendingArchiveExtraction(ready.get(i));
        }
        activity.archiveExtractInProgress = false;
        activity.updateMainOverflowButtonVisibility();
        Toast.makeText(activity, R.string.archive_extract_select_destination, Toast.LENGTH_LONG).show();

        File parent = ready.get(0).getParentFile();
        if (parent != null && parent.exists() && parent.isDirectory() && parent.canRead()) {
            activity.resetMainBrowseFiltersAndShow(parent, null);
        }
    }

    private void addPendingArchiveExtraction(@NonNull File archive) {
        removePendingArchiveExtraction(archive);
        activity.pendingExtractArchives.add(0, archive);
        activity.pendingExtractArchive = archive;
    }

    boolean setActivePendingArchiveExtraction(@Nullable File archive) {
        if (archive == null) return false;
        for (File pending : activity.pendingExtractArchives) {
            if (pending.equals(archive)) {
                activity.pendingExtractArchive = pending;
                return true;
            }
        }
        return false;
    }

    private void removePendingArchiveExtraction(@Nullable File archive) {
        if (archive == null) return;
        for (int i = activity.pendingExtractArchives.size() - 1; i >= 0; i--) {
            File pending = activity.pendingExtractArchives.get(i);
            if (pending.equals(archive)) activity.pendingExtractArchives.remove(i);
        }
        if (activity.pendingExtractArchive != null && activity.pendingExtractArchive.equals(archive)) {
            activity.pendingExtractArchive = activity.pendingExtractArchives.isEmpty()
                    ? null
                    : activity.pendingExtractArchives.get(0);
        }
    }

    void cancelPendingArchiveExtraction(@Nullable File archive) {
        removePendingArchiveExtraction(archive);
        activity.archiveExtractInProgress = false;
        activity.updateMainOverflowButtonVisibility();
        ShortToast.show(activity, R.string.archive_extract_cancelled);
    }

    void confirmPendingArchiveExtractionToCurrentDirectory() {
        File archive = activity.pendingExtractArchive;
        if (archive == null) return;
        if (!archive.exists() || !archive.canRead() || !isSupportedArchive(archive)) {
            removePendingArchiveExtraction(archive);
            activity.updateMainOverflowButtonVisibility();
            ShortToast.show(activity, R.string.archive_extract_failed);
            return;
        }
        File destinationDir = activity.currentDirectory;
        if (destinationDir == null || !destinationDir.exists() || !destinationDir.isDirectory() || !destinationDir.canWrite()) {
            ShortToast.show(activity, R.string.file_move_destination_unavailable);
            return;
        }
        File destination = new File(destinationDir, getArchiveOutputBaseName(archive));
        showArchiveExtractConfirmDialog(archive, destinationDir, destination);
    }

    void confirmAllPendingArchiveExtractionsToCurrentDirectory() {
        if (activity.archiveExtractInProgress) return;
        ArrayList<File> archives = new ArrayList<>(activity.pendingExtractArchives);
        if (archives.isEmpty()) return;
        File destinationRoot = activity.currentDirectory;
        if (destinationRoot == null || !destinationRoot.exists()
                || !destinationRoot.isDirectory() || !destinationRoot.canWrite()) {
            ShortToast.show(activity, R.string.file_move_destination_unavailable);
            return;
        }
        activity.showSimpleConfirmDialog(
                activity.getString(R.string.extract_all_here_title),
                activity.getString(R.string.extract_all_here_message,
                        archives.size(),
                        destinationRoot.getName().length() > 0 ? destinationRoot.getName() : destinationRoot.getAbsolutePath()),
                activity.getString(R.string.extract_all_here),
                () -> continueAllArchiveExtractionsWithPasswordPreflight(archives, destinationRoot));
    }

    private String getArchiveOutputBaseName(@NonNull File archive) {
        return MainArchiveExtractionPlanner.archiveOutputBaseName(
                archive,
                activity.getString(R.string.extracted_folder_fallback));
    }

    private void showArchiveExtractConfirmDialog(@NonNull File archive,
                                                 @NonNull File destinationDir,
                                                 @NonNull File destination) {
        activity.showSimpleConfirmDialog(
                activity.getString(R.string.archive_extract_confirm_title),
                activity.getString(R.string.archive_extract_confirm_message,
                        archive.getName(),
                        destinationDir.getName().length() > 0 ? destinationDir.getName() : destinationDir.getAbsolutePath()),
                activity.getString(R.string.extract_archive),
                () -> {
                    if (activity.pendingExtractArchive == null || !activity.pendingExtractArchive.equals(archive)) return;
                    if (destination.exists()) showArchiveTargetExistsDialog(archive, destinationDir, destination);
                    else continueArchiveExtractionWithPasswordIfNeeded(archive, destinationDir, destination, false);
                });
    }

    private void showArchiveTargetExistsDialog(@NonNull File archive,
                                               @NonNull File parentDir,
                                               @NonNull File existingTarget) {
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
        title.setText(activity.getString(R.string.archive_extract_conflict_title));
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
        message.setText(activity.getString(R.string.archive_extract_conflict_message, existingTarget.getName()));
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
        activity.addFileOpsRow(box, activity.getString(R.string.overwrite), fg, panel, () -> {
            if (ref[0] != null) ref[0].dismiss();
            continueArchiveExtractionWithPasswordIfNeeded(archive, parentDir, existingTarget, true);
        });
        activity.addFileOpsRow(box, activity.getString(R.string.create_copy), fg, panel, () -> {
            if (ref[0] != null) ref[0].dismiss();
            File copyDestination = MainArchiveExtractionPlanner.numberedDirectoryDestination(parentDir, existingTarget.getName(), activity.getString(R.string.extracted_folder_fallback));
            if (copyDestination == null) {
                ShortToast.show(activity, R.string.archive_extract_failed);
                return;
            }
            continueArchiveExtractionWithPasswordIfNeeded(archive, parentDir, copyDestination, false);
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

    private interface ArchivePasswordCallback {
        void onPassword(@NonNull String password);
    }

    private void showArchivePasswordDialog(@NonNull ArchivePasswordCallback callback) {
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
        title.setText(R.string.archive_password_title);
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
        message.setText(R.string.archive_password_message);
        message.setTextColor(sub);
        message.setTextSize(14f);
        message.setGravity(Gravity.CENTER);
        message.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        message.setLineSpacing(activity.dpToPx(2), 1.0f);
        message.setPadding(activity.dpToPx(8), 0, activity.dpToPx(8), activity.dpToPx(12));
        box.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(R.string.archive_password_hint);
        input.setTextColor(fg);
        input.setHintTextColor(sub);
        box.addView(makePasswordInputRow(input, fg, panel), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dpToPx(52)));

        final android.app.Dialog[] ref = new android.app.Dialog[1];
        LinearLayout actions = makeArchiveDialogActionRow();
        TextView cancel = makeArchiveDialogActionButton(activity.getString(R.string.cancel), sub, panel);
        TextView ok = makeArchiveDialogActionButton(activity.getString(R.string.ok), fg, panel);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, activity.dpToPx(44), 1f));
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(0, activity.dpToPx(44), 1f);
        okLp.setMargins(activity.dpToPx(8), 0, 0, 0);
        actions.addView(ok, okLp);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsLp.setMargins(0, activity.dpToPx(12), 0, 0);
        box.addView(actions, actionsLp);

        android.app.Dialog dialog = activity.createStableBottomDialog(
                box,
                activity.mainFileTypeAlignedDialogYOffsetPx(),
                0.22f);
        ref[0] = dialog;
        cancel.setOnClickListener(v -> dialog.dismiss());
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            callback.onPassword(input.getText() == null ? "" : input.getText().toString());
        });
        dialog.setOnShowListener(d -> input.requestFocus());
        dialog.show();
    }

    private void continueArchiveExtractionWithPasswordIfNeeded(@NonNull File archive,
                                                               @NonNull File parentDir,
                                                               @NonNull File destinationDir,
                                                               boolean overwrite) {
        if (ArchiveSupport.requiresPasswordForExtraction(archive)) {
            showArchivePasswordDialog(password -> continueArchiveExtraction(archive, parentDir, destinationDir, overwrite, password.toCharArray()));
            return;
        }
        continueArchiveExtraction(archive, parentDir, destinationDir, overwrite, null);
    }

    private void continueArchiveExtraction(@NonNull File archive,
                                           @NonNull File parentDir,
                                           @NonNull File destinationDir,
                                           boolean overwrite,
                                           char[] password) {
        activity.archiveExtractInProgress = true;
        activity.updateMainOverflowButtonVisibility();
        FileOperationProgress progress = activity.showFileOperationProgress(
                activity.getString(R.string.archive_extracting),
                archive.getName());
        progress.setFolder(destinationDir.getName().length() > 0 ? destinationDir.getName() : destinationDir.getAbsolutePath());
        progress.clearItemProgress();
        progress.clearFolderProgress();
        activity.executeFolderBackgroundTask(() -> {
            ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                    archive,
                    destinationDir,
                    overwrite,
                    password,
                    progress);
            activity.fileSearchHandler.post(() -> {
                activity.archiveExtractInProgress = false;
                activity.finishFileOperationProgress(progress);
                activity.updateMainOverflowButtonVisibility();
                if (activity.activityDestroyed) return;
                if (progress.isCancelled()) {
                    ShortToast.show(activity, R.string.archive_extract_cancelled);
                    return;
                }
                if (!result.success) {
                    if (result.failure == ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED
                            && (password == null || password.length == 0)
                            && ArchiveSupport.canUsePassword(archive)) {
                        showArchivePasswordDialog(nextPassword -> continueArchiveExtraction(archive, parentDir, destinationDir, overwrite, nextPassword.toCharArray()));
                    } else {
                        showArchiveExtractionFailure(archive, result);
                    }
                    return;
                }
                removePendingArchiveExtraction(archive);
                activity.updateMainOverflowButtonVisibility();
                if (activity.prefs != null) activity.prefs.addRecentFolder(parentDir.getAbsolutePath());
                activity.resetMainBrowseFiltersAndShow(parentDir, destinationDir.getAbsolutePath());
                activity.rebuildDrawerStorageEntries();
                ShortToast.show(activity, R.string.archive_extracted);
            });
        });
    }

    private void continueAllArchiveExtractions(@NonNull ArrayList<File> archives,
                                               @NonNull File destinationRoot,
                                               @Nullable char[] batchPassword) {
        activity.archiveExtractInProgress = true;
        activity.updateMainOverflowButtonVisibility();
        FileOperationProgress progress = activity.showFileOperationProgress(
                activity.getString(R.string.archive_extracting),
                activity.getString(R.string.extract_all_here));
        progress.setFolder(destinationRoot.getName().length() > 0 ? destinationRoot.getName() : destinationRoot.getAbsolutePath());

        activity.executeFolderBackgroundTask(() -> {
            int successCount = 0;
            int failedCount = 0;
            int unsupportedCount = 0;
            int passwordFailedCount = 0;
            String firstUnsupportedArchiveName = null;
            String firstUnsupportedDetail = null;
            final int totalArchives = archives.size();
            int archiveIndex = 0;
            for (File archive : archives) {
                archiveIndex++;
                if (progress.isCancelled()) break;
                if (archive == null || !archive.exists() || !archive.canRead() || !isSupportedArchive(archive)) {
                    failedCount++;
                    continue;
                }
                String baseName = getArchiveOutputBaseName(archive);
                File destination = new File(destinationRoot, baseName);
                if (destination.exists()) {
                    destination = MainArchiveExtractionPlanner.numberedDirectoryDestination(destinationRoot, baseName, activity.getString(R.string.extracted_folder_fallback));
                }
                if (destination == null) {
                    failedCount++;
                    continue;
                }
                progress.setDetail(archive.getName());
                progress.clearItemProgress();
                progress.setFolder(destination.getName());
                if (totalArchives > 1) {
                    progress.setFolderProgress(archiveIndex, totalArchives);
                } else {
                    progress.clearFolderProgress();
                }
                boolean passwordRequired = ArchiveSupport.requiresPasswordForExtraction(archive);
                char[] password = passwordRequired ? batchPassword : null;
                ArchiveSupport.ExtractionResult result = password == null && passwordRequired
                        ? ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, null)
                        : ArchiveSupport.extractArchiveDetailed(archive, destination, false, password, progress);
                if (result.success) {
                    successCount++;
                    removePendingArchiveExtraction(archive);
                } else if (!progress.isCancelled()) {
                    failedCount++;
                    if (result.failure == ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE) {
                        unsupportedCount++;
                        if (firstUnsupportedDetail == null && result.detail != null && result.detail.length() > 0) {
                            firstUnsupportedArchiveName = archive.getName();
                            firstUnsupportedDetail = result.detail;
                        }
                    } else if (result.failure == ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED) passwordFailedCount++;
                }
            }

            final int finalSuccessCount = successCount;
            final int finalFailedCount = failedCount;
            final int finalUnsupportedCount = unsupportedCount;
            final int finalPasswordFailedCount = passwordFailedCount;
            final String finalFirstUnsupportedArchiveName = firstUnsupportedArchiveName;
            final String finalFirstUnsupportedDetail = firstUnsupportedDetail;
            final int totalCount = archives.size();
            activity.fileSearchHandler.post(() -> {
                activity.archiveExtractInProgress = false;
                activity.finishFileOperationProgress(progress);
                activity.updateMainOverflowButtonVisibility();
                if (activity.activityDestroyed) return;
                if (progress.isCancelled()) {
                    ShortToast.show(activity, R.string.archive_extract_cancelled);
                    return;
                }
                if (activity.prefs != null && finalSuccessCount > 0) {
                    activity.prefs.addRecentFolder(destinationRoot.getAbsolutePath());
                }
                activity.resetMainBrowseFiltersAndShow(destinationRoot, null);
                activity.rebuildDrawerStorageEntries();
                if (finalFailedCount > 0 || finalSuccessCount < totalCount) {
                    if (finalUnsupportedCount > 0) {
                        String message = activity.getString(
                                R.string.archive_extract_all_partial_unsupported,
                                finalSuccessCount,
                                totalCount,
                                finalUnsupportedCount);
                        if (hasFailureDetail(finalFirstUnsupportedDetail)) {
                            showArchiveExtractionFailureDetailDialog(
                                    message,
                                    finalFirstUnsupportedArchiveName,
                                    finalFirstUnsupportedDetail);
                        } else {
                            ShortToast.show(activity, message);
                        }
                    } else if (finalPasswordFailedCount > 0) {
                        ShortToast.show(activity, activity.getString(
                                R.string.archive_extract_all_partial_password,
                                finalSuccessCount,
                                totalCount,
                                finalPasswordFailedCount));
                    } else {
                        ShortToast.show(activity, activity.getString(
                                R.string.archive_extract_all_partial,
                                finalSuccessCount,
                                totalCount));
                    }
                } else {
                    ShortToast.show(activity, activity.getString(
                            R.string.archive_extract_all_done,
                            finalSuccessCount,
                            totalCount));
                }
            });
        });
    }

    private void showArchiveExtractionFailure(@NonNull File archive,
                                              @NonNull ArchiveSupport.ExtractionResult result) {
        String message = activity.getString(ArchiveFailureMessages.extractionFailureMessageRes(archive, result));
        if (result.failure == ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE
                && hasFailureDetail(result.detail)) {
            showArchiveExtractionFailureDetailDialog(message, archive.getName(), result.detail);
            return;
        }
        ShortToast.show(activity, ArchiveFailureMessages.extractionFailureMessageRes(archive, result));
    }

    private boolean hasFailureDetail(@Nullable String detail) {
        return detail != null && detail.trim().length() > 0;
    }

    private void showArchiveExtractionFailureDetailDialog(@NonNull String shortMessage,
                                                          @Nullable String archiveName,
                                                          @Nullable String detail) {
        if (!hasFailureDetail(detail)) {
            ShortToast.show(activity, shortMessage);
            return;
        }
        ShortToast.show(activity, shortMessage);
        if (activity.activityDestroyed) return;

        String fullDetail = formatFailureDetailForDialog(archiveName, detail);
        boolean dark = activity.prefs == null || activity.prefs.shouldUseDarkColors(activity);
        int bg = activity.prefs != null
                ? activity.prefs.getMainBgColor(activity)
                : (dark ? Color.rgb(33, 33, 33) : Color.rgb(255, 255, 255));
        int panel = activity.prefs != null
                ? activity.prefs.getMainPanelColor(activity)
                : (dark ? Color.rgb(48, 48, 48) : Color.rgb(245, 245, 245));
        int fg = activity.prefs != null
                ? activity.prefs.getMainTextColor(activity)
                : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        int sub = activity.prefs != null
                ? activity.prefs.getMainSubTextColor(activity)
                : (dark ? Color.rgb(190, 190, 190) : Color.rgb(95, 99, 104));
        int line = activity.prefs != null
                ? activity.prefs.getMainOutlineColor(activity)
                : (dark ? Color.rgb(92, 92, 92) : Color.rgb(210, 210, 210));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(activity.dpToPx(18), activity.dpToPx(16), activity.dpToPx(18), activity.dpToPx(12));
        GradientDrawable bgShape = new GradientDrawable();
        bgShape.setColor(bg);
        bgShape.setCornerRadius(activity.dpToPx(18));
        bgShape.setStroke(Math.max(1, activity.dpToPx(1)), line);
        box.setBackground(bgShape);

        TextView title = new TextView(activity);
        title.setText(R.string.archive_failure_detail_title);
        title.setTextColor(fg);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setPadding(activity.dpToPx(6), 0, activity.dpToPx(6), activity.dpToPx(8));
        box.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(activity);
        message.setText(R.string.archive_failure_detail_message);
        message.setTextColor(sub);
        message.setTextSize(14f);
        message.setGravity(Gravity.CENTER);
        message.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        message.setLineSpacing(activity.dpToPx(2), 1.0f);
        message.setPadding(activity.dpToPx(8), 0, activity.dpToPx(8), activity.dpToPx(10));
        box.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView detailView = new TextView(activity);
        detailView.setText(fullDetail);
        detailView.setTextColor(fg);
        detailView.setTextSize(13f);
        detailView.setTextIsSelectable(true);
        detailView.setLineSpacing(0f, 1.12f);
        int pad = activity.dpToPx(12);
        detailView.setPadding(pad, activity.dpToPx(12), pad, activity.dpToPx(12));

        ScrollView scroll = new ScrollView(activity);
        GradientDrawable detailBg = new GradientDrawable();
        detailBg.setColor(panel);
        detailBg.setCornerRadius(activity.dpToPx(12));
        detailBg.setStroke(Math.max(1, activity.dpToPx(1)), line);
        scroll.setBackground(detailBg);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(detailView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        box.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(260)));

        final android.app.Dialog[] ref = new android.app.Dialog[1];
        LinearLayout actions = makeArchiveDialogActionRow();
        TextView close = makeArchiveDialogActionButton(activity.getString(R.string.close), sub, panel);
        TextView copy = makeArchiveDialogActionButton(activity.getString(R.string.copy), fg, panel);
        actions.addView(close, new LinearLayout.LayoutParams(0, activity.dpToPx(44), 1f));
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, activity.dpToPx(44), 1f);
        copyLp.setMargins(activity.dpToPx(8), 0, 0, 0);
        actions.addView(copy, copyLp);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsLp.setMargins(0, activity.dpToPx(12), 0, 0);
        box.addView(actions, actionsLp);

        android.app.Dialog dialog = activity.createStableCenterDialog(box, 0, 0.24f);
        ref[0] = dialog;
        close.setOnClickListener(v -> dialog.dismiss());
        copy.setOnClickListener(v -> copyArchiveFailureDetail(fullDetail));
        dialog.show();
    }

    private LinearLayout makeArchiveDialogActionRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private TextView makeArchiveDialogActionButton(@NonNull String label, int textColor, int panelColor) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextColor(textColor);
        button.setTextSize(15f);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setIncludeFontPadding(false);
        button.setPadding(activity.dpToPx(10), 0, activity.dpToPx(10), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(panelColor);
        bg.setCornerRadius(activity.dpToPx(12));
        button.setBackground(bg);
        return button;
    }

    private LinearLayout makePasswordInputRow(@NonNull EditText input, int iconColor, int panelColor) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        row.addView(input, inputLp);

        ImageButton toggle = new ImageButton(activity);
        toggle.setImageResource(R.drawable.ic_visibility);
        toggle.setColorFilter(iconColor);
        toggle.setBackground(makePasswordToggleBackground(panelColor));
        toggle.setPadding(activity.dpToPx(12), activity.dpToPx(12), activity.dpToPx(12), activity.dpToPx(12));
        toggle.setContentDescription(activity.getString(R.string.archive_password_show));
        final boolean[] visible = {false};
        toggle.setOnClickListener(v -> {
            visible[0] = !visible[0];
            input.setTransformationMethod(visible[0]
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
            toggle.setImageResource(visible[0] ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
            toggle.setColorFilter(iconColor);
            toggle.setContentDescription(activity.getString(visible[0]
                    ? R.string.archive_password_hide
                    : R.string.archive_password_show));
            input.setSelection(input.length());
        });
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(activity.dpToPx(48), activity.dpToPx(48));
        toggleLp.setMargins(activity.dpToPx(8), 0, 0, 0);
        row.addView(toggle, toggleLp);
        return row;
    }

    private GradientDrawable makePasswordToggleBackground(int panelColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(panelColor);
        bg.setCornerRadius(activity.dpToPx(12));
        return bg;
    }

    @NonNull
    private String formatFailureDetailForDialog(@Nullable String archiveName, @Nullable String detail) {
        String normalized = detail == null ? "" : detail.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (archiveName == null || archiveName.length() == 0) return normalized;
        return archiveName + "\n\n" + normalized;
    }

    private void copyArchiveFailureDetail(@NonNull String detail) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    activity.getString(R.string.archive_failure_detail_title),
                    detail));
            ShortToast.show(activity, R.string.archive_failure_detail_copied);
        }
    }

    private void continueAllArchiveExtractionsWithPasswordPreflight(@NonNull ArrayList<File> archives,
                                                                    @NonNull File destinationRoot) {
        activity.executeFolderBackgroundTask(() -> {
            boolean needsPassword = MainArchiveExtractionPlanner.hasPasswordProtectedArchive(archives);
            activity.fileSearchHandler.post(() -> {
                if (activity.activityDestroyed) return;
                if (needsPassword) {
                    showArchivePasswordDialog(password -> continueAllArchiveExtractions(
                            archives,
                            destinationRoot,
                            password.toCharArray()));
                    return;
                }
                continueAllArchiveExtractions(archives, destinationRoot, null);
            });
        });
    }


}
