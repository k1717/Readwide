package com.textview.reader;

import com.textview.reader.util.FileUtils;
import android.widget.Toast;
import android.widget.ScrollView;
import android.widget.EditText;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.textview.reader.util.FileClipboardController;
import com.textview.reader.util.FileOperationProgress;
import com.textview.reader.util.FileSystemOps;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps MainActivity focused on navigation and state changes while preserving
 * the existing main-file action popup behavior.
 */
final class MainFileActionDialogController {
    private final MainActivity activity;

    MainFileActionDialogController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void showPendingActionQueueDialog() {
        new MainPendingActionDropdownController(activity).show();
    }

    void showSelectedFileActionsDialog() {
        new MainSelectionActionDropdownController(activity).show();
    }

    // ---------------------------------------------------------------------
    // Single-file dialogs and folder creation extracted from MainActivity.
    // ---------------------------------------------------------------------

    void showFileOpsDialog(File file) {
        final MainVerticalActionSheet sheet = new MainVerticalActionSheet(activity);
        final MainVerticalActionSheet.Colors colors = sheet.readColors();
        final android.app.Dialog[] ref = new android.app.Dialog[1];
        final boolean isBuiltInFolder = file.isDirectory() && activity.isBuiltInDrawerPath(file.getAbsolutePath());
        List<MainVerticalActionSheet.Action> actions = new ArrayList<>();

        if (!file.isDirectory()) {
            actions.add(new MainVerticalActionSheet.Action(activity.getString(R.string.open), colors.fg, () -> {
                sheet.dismiss(ref);
                activity.openFile(file);
            }));
            actions.add(new MainVerticalActionSheet.Action(activity.getString(R.string.go_to_containing_folder), colors.fg, () -> {
                sheet.dismiss(ref);
                activity.navigateToContainingFolder(file);
            }));
            if (file.canRead()) {
                actions.add(new MainVerticalActionSheet.Action(activity.getString(R.string.share), colors.fg, () -> {
                    sheet.dismiss(ref);
                    activity.shareFile(file);
                }));
            }
            if (activity.isSupportedArchive(file)) {
                actions.add(new MainVerticalActionSheet.Action(activity.getString(R.string.archive_folder_preview), colors.fg, () -> {
                    sheet.dismiss(ref);
                    activity.openArchiveFolderPreview(file);
                }));
                actions.add(new MainVerticalActionSheet.Action(activity.getString(R.string.extract_archive), colors.fg, () -> {
                    sheet.dismiss(ref);
                    activity.startArchiveExtraction(file);
                }));
            }
            if (activity.homeMode && activity.bookmarkManager != null && activity.bookmarkManager.getReadingState(file.getAbsolutePath()) != null) {
                actions.add(new MainVerticalActionSheet.Action(activity.getString(R.string.clear_from_recently_viewed), colors.fg, () -> {
                    sheet.dismiss(ref);
                    activity.bookmarkManager.deleteReadingState(file.getAbsolutePath());
                    activity.loadRecentFiles();
                    activity.rebuildDrawerStorageEntries();
                    ShortToast.show(activity, activity.getString(R.string.recent_file_cleared));
                }));
            }
        } else if (!isBuiltInFolder) {
            boolean shortcut = activity.prefs != null && activity.prefs.isFolderShortcut(file.getAbsolutePath());
            actions.add(new MainVerticalActionSheet.Action(
                    activity.getString(shortcut ? R.string.remove_shortcut : R.string.add_shortcut),
                    colors.fg,
                    () -> {
                        sheet.dismiss(ref);
                        if (shortcut) activity.removeFolderShortcut(file);
                        else activity.addFolderShortcut(file);
                    }));
        }

        actions.add(new MainVerticalActionSheet.Action(activity.getString(R.string.rename), colors.fg, () -> {
            sheet.dismiss(ref);
            activity.showRenameDialog(file);
        }));
        if (!isBuiltInFolder) {
            actions.add(new MainVerticalActionSheet.Action(activity.getString(R.string.archive_create), colors.fg, () -> {
                sheet.dismiss(ref);
                activity.startArchiveCreation(file);
            }));
        }
        if (!isBuiltInFolder) {
            actions.add(new MainVerticalActionSheet.Action(activity.getString(R.string.cut), colors.fg, () -> {
                sheet.dismiss(ref);
                activity.startFileClipboardOperation(file, false);
            }));
            actions.add(new MainVerticalActionSheet.Action(activity.getString(R.string.copy), colors.fg, () -> {
                sheet.dismiss(ref);
                activity.startFileClipboardOperation(file, true);
            }));
        }
        actions.add(new MainVerticalActionSheet.Action(activity.getString(R.string.delete), colors.danger, () -> {
            sheet.dismiss(ref);
            activity.showDeleteConfirm(file);
        }));
        actions.add(new MainVerticalActionSheet.Action(activity.getString(file.isDirectory() ? R.string.folder_info : R.string.file_info), colors.fg, () -> {
            sheet.dismiss(ref);
            activity.showFileInfo(file);
        }));

        LinearLayout box = sheet.makeBox(colors);
        String titleText = file.getName();
        sheet.addTitle(box, titleText, colors.fg, true);
        sheet.addScrollableActions(box, actions, colors.panel, false);
        TextView cancel = sheet.addCancel(box, colors.sub);

        android.app.Dialog dialog = activity.createStableBottomDialog(box, activity.mainFileTypeAlignedDialogYOffsetPx(), 0.22f);
        ref[0] = dialog;
        activity.overrideDialogWidth(dialog, sheet.compactDialogWidthPx());
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    void showRenameDialog(File file) {
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
        title.setText(activity.getString(R.string.rename));
        title.setTextColor(fg);
        title.setTextSize(21f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setPadding(activity.dpToPx(6), 0, activity.dpToPx(6), activity.dpToPx(6));
        box.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(activity);
        hint.setText(file.getName());
        hint.setTextColor(sub);
        hint.setTextSize(13f);
        hint.setSingleLine(true);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        hint.setPadding(activity.dpToPx(6), 0, activity.dpToPx(6), activity.dpToPx(12));
        box.addView(hint, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText input = new EditText(activity);
        input.setText(file.getName());
        input.selectAll();
        input.setSingleLine(true);
        input.setTextColor(fg);
        input.setHintTextColor(sub);
        input.setTextSize(16f);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setPadding(activity.dpToPx(14), 0, activity.dpToPx(14), 0);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(panel);
        inputBg.setCornerRadius(activity.dpToPx(12));
        inputBg.setStroke(Math.max(1, activity.dpToPx(1)), line);
        input.setBackground(inputBg);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dpToPx(52));
        inputLp.setMargins(0, 0, 0, activity.dpToPx(12));
        box.addView(input, inputLp);

        final android.app.Dialog[] ref = new android.app.Dialog[1];
        activity.addFileOpsRow(box, activity.getString(R.string.rename), fg, panel, () -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty()) return;
            File parent = file.getParentFile();
            if (parent == null) return;
            File newFile = new File(parent, newName);
            if (file.renameTo(newFile)) {
                if (ref[0] != null) ref[0].dismiss();
                activity.loadDirectory(activity.currentDirectory);
                ShortToast.show(activity, activity.getString(R.string.renamed));
            } else {
                ShortToast.show(activity, activity.getString(R.string.rename_failed));
            }
        });

        TextView cancel = new TextView(activity);
        cancel.setText(activity.getString(R.string.cancel));
        cancel.setTextColor(sub);
        cancel.setTextSize(16f);
        cancel.setGravity(Gravity.CENTER);
        cancel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(cancel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dpToPx(48)));

        android.app.Dialog dialog = activity.createStableBottomDialog(box, activity.dpToPx(112), 0.22f);
        ref[0] = dialog;
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            input.requestFocus();
            input.postDelayed(() -> {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }, 120);
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    void showDeleteConfirm(File file) {
        final boolean dark = activity.prefs == null || activity.prefs.shouldUseDarkColors(activity);
        final int bg = activity.prefs != null ? activity.prefs.getMainBgColor(activity) : (dark ? Color.rgb(33, 33, 33) : Color.rgb(255, 255, 255));
        final int panel = activity.prefs != null ? activity.prefs.getMainPanelColor(activity) : (dark ? Color.rgb(48, 48, 48) : Color.rgb(245, 245, 245));
        final int fg = activity.prefs != null ? activity.prefs.getMainTextColor(activity) : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        final int sub = activity.prefs != null ? activity.prefs.getMainSubTextColor(activity) : (dark ? Color.rgb(190, 190, 190) : Color.rgb(95, 99, 104));
        final int danger = dark ? Color.rgb(255, 170, 170) : Color.rgb(176, 0, 32);
        final int dangerBg = dark ? Color.rgb(96, 42, 42) : Color.rgb(255, 235, 238);
        final int line = activity.prefs != null ? activity.prefs.getMainOutlineColor(activity) : (dark ? Color.rgb(92, 92, 92) : Color.rgb(210, 210, 210));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(activity.dpToPx(18), activity.dpToPx(16), activity.dpToPx(18), activity.dpToPx(12));
        GradientDrawable bgShape = new GradientDrawable();
        bgShape.setColor(bg);
        bgShape.setCornerRadius(activity.dpToPx(20));
        bgShape.setStroke(Math.max(1, activity.dpToPx(1)), line);
        box.setBackground(bgShape);

        TextView title = new TextView(activity);
        title.setText(activity.getString(R.string.delete));
        title.setTextColor(danger);
        title.setTextSize(21f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setPadding(activity.dpToPx(6), 0, activity.dpToPx(6), activity.dpToPx(8));
        box.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(activity);
        message.setText(activity.getString(R.string.delete_file_confirm, file.getName()));
        message.setTextColor(fg);
        message.setTextSize(16f);
        message.setGravity(Gravity.CENTER);
        message.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        message.setLineSpacing(activity.dpToPx(2), 1.0f);
        message.setPadding(activity.dpToPx(8), 0, activity.dpToPx(8), activity.dpToPx(6));
        box.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView detail = new TextView(activity);
        detail.setText(activity.getString(R.string.delete_file_confirm_detail));
        detail.setTextColor(sub);
        detail.setTextSize(13f);
        detail.setGravity(Gravity.CENTER);
        detail.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        detail.setLineSpacing(activity.dpToPx(2), 1.0f);
        detail.setPadding(activity.dpToPx(8), 0, activity.dpToPx(8), activity.dpToPx(14));
        box.addView(detail, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, 0, 0, 0);
        box.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(104)));

        TextView delete = new TextView(activity);
        delete.setText(activity.getString(R.string.delete));
        delete.setTextColor(danger);
        delete.setTextSize(16f);
        delete.setGravity(Gravity.CENTER);
        delete.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        GradientDrawable deleteBg = new GradientDrawable();
        deleteBg.setColor(dangerBg);
        deleteBg.setCornerRadius(activity.dpToPx(14));
        deleteBg.setStroke(Math.max(1, activity.dpToPx(1)), danger);
        delete.setBackground(deleteBg);
        LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(48));
        deleteLp.setMargins(0, 0, 0, activity.dpToPx(8));
        actions.addView(delete, deleteLp);

        TextView cancel = new TextView(activity);
        cancel.setText(activity.getString(R.string.cancel));
        cancel.setTextColor(sub);
        cancel.setTextSize(16f);
        cancel.setGravity(Gravity.CENTER);
        cancel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(panel);
        cancelBg.setCornerRadius(activity.dpToPx(14));
        cancelBg.setStroke(Math.max(1, activity.dpToPx(1)), line);
        cancel.setBackground(cancelBg);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(48));
        cancelLp.setMargins(0, 0, 0, 0);
        actions.addView(cancel, cancelLp);

        android.app.Dialog dialog = activity.createStableCenterDialog(box, 0, 0.22f);
        activity.overrideDialogWidth(dialog, activity.compactDeleteConfirmDialogWidthPx());
        cancel.setOnClickListener(v -> dialog.dismiss());
        delete.setOnClickListener(v -> {
            String deletedPath = file.getAbsolutePath();
            boolean deletedDirectory = file.isDirectory();
            dialog.dismiss();
            FileOperationProgress progress = activity.showFileOperationProgress(
                    activity.getString(R.string.file_deleting),
                    file.getName());
            File parent = file.getParentFile();
            if (parent != null) progress.setFolder(parent.getName().length() > 0 ? parent.getName() : parent.getAbsolutePath());
            activity.executeFolderBackgroundTask(() -> {
                boolean deleted = FileSystemOps.delete(file, progress);
                activity.fileSearchHandler.post(() -> {
                    activity.finishFileOperationProgress(progress);
                    if (activity.activityDestroyed) return;
                    if (progress.isCancelled()) {
                        activity.refreshVisibleFileListAfterDelete();
                        ShortToast.show(activity, R.string.file_operation_cancelled);
                        return;
                    }
                    if (deleted) {
                        if (activity.bookmarkManager != null) {
                            activity.bookmarkManager.deleteReadingState(deletedPath);
                        }
                        activity.cleanupNavigationStateAfterDelete(deletedPath, deletedDirectory);
                        activity.refreshVisibleFileListAfterDelete();
                        ShortToast.show(activity, activity.getString(R.string.deleted));
                    } else {
                        ShortToast.show(activity, activity.getString(R.string.delete_failed));
                    }
                });
            });
        });
        dialog.show();
    }

    void showFileInfo(File file) {
        final boolean dark = activity.prefs == null || activity.prefs.shouldUseDarkColors(activity);
        final int bg = activity.prefs != null ? activity.prefs.getMainBgColor(activity) : (dark ? Color.rgb(33, 33, 33) : Color.rgb(255, 255, 255));
        final int panel = activity.prefs != null ? activity.prefs.getMainPanelColor(activity) : (dark ? Color.rgb(48, 48, 48) : Color.rgb(245, 245, 245));
        final int fg = activity.prefs != null ? activity.prefs.getMainTextColor(activity) : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        final int sub = dark ? Color.rgb(205, 205, 205) : Color.rgb(78, 84, 92);
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
        title.setText(activity.getString(file.isDirectory() ? R.string.folder_info : R.string.file_info));
        title.setTextColor(fg);
        title.setTextSize(21f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setPadding(activity.dpToPx(6), 0, activity.dpToPx(6), activity.dpToPx(12));
        box.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout infoList = new LinearLayout(activity);
        infoList.setOrientation(LinearLayout.VERTICAL);
        infoList.setPadding(0, 0, 0, activity.dpToPx(4));
        addFileInfoRow(infoList, activity.getString(R.string.file_info_name), file.getName(), fg, sub, panel);
        addFileInfoRow(infoList, activity.getString(R.string.file_info_path), file.getAbsolutePath(), fg, sub, panel);
        final TextView sizeValueView = addFileInfoRow(infoList,
                activity.getString(R.string.file_info_size),
                file.isDirectory()
                        ? activity.getString(R.string.file_info_size_calculating)
                        : FileUtils.formatFileSize(file.length()),
                fg, sub, panel);
        addFileInfoRow(infoList, activity.getString(R.string.file_info_modified),
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(new java.util.Date(file.lastModified())), fg, sub, panel);
        addFileInfoRow(infoList, activity.getString(R.string.file_info_readable), String.valueOf(file.canRead()), fg, sub, panel);
        addFileInfoRow(infoList, activity.getString(R.string.file_info_writable), String.valueOf(file.canWrite()), fg, sub, panel);
        if (!file.isDirectory()) {
            addFileInfoRow(infoList, activity.getString(R.string.file_info_type), FileUtils.getReadableFileType(file.getName()), fg, sub, panel);
            if (FileUtils.isTextFile(file.getName())) {
                addFileInfoRow(infoList, activity.getString(R.string.file_info_encoding), FileUtils.detectEncoding(file), fg, sub, panel);
            }
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.addView(infoList);
        box.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(activity.dpToPx(430), activity.getResources().getDisplayMetrics().heightPixels - activity.dpToPx(240))));

        TextView close = new TextView(activity);
        close.setText(activity.getString(R.string.ok));
        close.setTextColor(fg);
        close.setTextSize(16f);
        close.setGravity(Gravity.CENTER);
        close.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        close.setPadding(activity.dpToPx(12), 0, activity.dpToPx(12), 0);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dpToPx(50));
        closeLp.setMargins(0, activity.dpToPx(4), 0, 0);
        box.addView(close, closeLp);

        android.app.Dialog dialog = activity.createStableBottomDialog(box, activity.mainFileTypeAlignedDialogYOffsetPx(), 0.22f);
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        if (file.isDirectory()) {
            activity.executeFolderBackgroundTask(() -> {
                long folderSize = FileSystemOps.measureBytes(file);
                activity.fileSearchHandler.post(() -> {
                    if (!activity.activityDestroyed && dialog.isShowing()) {
                        sizeValueView.setText(FileUtils.formatFileSize(folderSize));
                    }
                });
            });
        }
    }

    @SuppressLint("WrongConstant")
    private TextView addFileInfoRow(@NonNull LinearLayout box, @NonNull String label, String value, int fg, int sub, int panelColor) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(activity.dpToPx(14), activity.dpToPx(9), activity.dpToPx(14), activity.dpToPx(10));
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setColor(panelColor);
        rowBg.setCornerRadius(activity.dpToPx(12));
        row.setBackground(rowBg);

        TextView labelView = new TextView(activity);
        labelView.setText(label);
        labelView.setTextColor(sub);
        labelView.setTextSize(12f);
        labelView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.addView(labelView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView valueView = new TextView(activity);
        valueView.setText(value != null ? value : "");
        valueView.setTextColor(fg);
        valueView.setTextSize(15f);
        valueView.setPadding(0, activity.dpToPx(3), 0, 0);
        valueView.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        row.addView(valueView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, activity.dpToPx(8));
        box.addView(row, lp);
        return valueView;
    }

    void showNewFolderDialog() {
        if (activity.currentDirectory == null) return;
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
        title.setText(activity.getString(R.string.new_folder));
        title.setTextColor(fg);
        title.setTextSize(21f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setPadding(activity.dpToPx(6), 0, activity.dpToPx(6), activity.dpToPx(12));
        box.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText input = new EditText(activity);
        input.setHint(activity.getString(R.string.folder_name));
        input.setSingleLine(true);
        input.setTextColor(fg);
        input.setHintTextColor(sub);
        input.setTextSize(16f);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setPadding(activity.dpToPx(14), 0, activity.dpToPx(14), 0);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(panel);
        inputBg.setCornerRadius(activity.dpToPx(12));
        inputBg.setStroke(Math.max(1, activity.dpToPx(1)), line);
        input.setBackground(inputBg);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dpToPx(52));
        inputLp.setMargins(0, 0, 0, activity.dpToPx(12));
        box.addView(input, inputLp);

        final android.app.Dialog[] ref = new android.app.Dialog[1];
        activity.addFileOpsRow(box, activity.getString(R.string.create), fg, panel, () -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) return;
            File newDir = new File(activity.currentDirectory, name);
            if (newDir.mkdirs()) {
                if (ref[0] != null) ref[0].dismiss();
                activity.loadDirectory(activity.currentDirectory);
                ShortToast.show(activity, activity.getString(R.string.folder_created));
            } else {
                ShortToast.show(activity, activity.getString(R.string.folder_create_failed));
            }
        });

        TextView cancel = new TextView(activity);
        cancel.setText(activity.getString(R.string.cancel));
        cancel.setTextColor(sub);
        cancel.setTextSize(16f);
        cancel.setGravity(Gravity.CENTER);
        cancel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(cancel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dpToPx(48)));

        android.app.Dialog dialog = activity.createStableBottomDialog(box, activity.mainFileTypeAlignedDialogYOffsetPx(), 0.22f);
        ref[0] = dialog;
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        });
        dialog.show();
    }

}
