package com.readwide.manager;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows multi-file selection actions as a compact toolbar dropdown instead of
 * a large rounded bottom sheet.
 */
final class MainSelectionActionDropdownController {
    private final MainActivity activity;

    MainSelectionActionDropdownController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void show() {
        if (!activity.fileSelectionMode || activity.selectedFilePaths.isEmpty() || activity.mainOverflowButton == null) return;

        final MainDropdownStyle style = MainDropdownStyle.from(activity);
        final PopupWindow[] popupRef = new PopupWindow[1];
        List<Action> actions = buildActions(popupRef, style.fg, style.sub, style.danger);
        CharSequence titleText = activity.getString(R.string.file_selection_count, activity.selectedFilePaths.size());
        final int popupWidth = MainActionPopupSizing.selectionDropdownWidth(activity);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, activity.dpToPx(4), 0, activity.dpToPx(4));
        box.setBackground(style.makePanelBackground(activity, 8, false));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            box.setClipToOutline(true);
        }

        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextColor(style.sub);
        title.setTextSize(13f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        title.setSingleLine(true);
        title.setIncludeFontPadding(false);
        title.setPadding(activity.dpToPx(14), 0, activity.dpToPx(14), 0);
        box.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(32)));

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (Action action : actions) {
            addActionRow(rows, action, style.rowPanel);
        }
        box.addView(rows, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        PopupWindow popup = new PopupWindow(
                box,
                popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        popupRef[0] = popup;
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popup.setElevation(activity.dpToPx(3));
        }

        int xoff = activity.mainOverflowButton.getWidth() - popupWidth;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            popup.showAsDropDown(activity.mainOverflowButton, xoff, 0, Gravity.NO_GRAVITY);
        } else {
            popup.showAsDropDown(activity.mainOverflowButton, xoff, 0);
        }
    }

    private List<Action> buildActions(@NonNull PopupWindow[] popupRef, int fg, int sub, int danger) {
        ArrayList<Action> actions = new ArrayList<>();
        File single = activity.getSingleSelectedFile();
        if (single != null) {
            if (single.isFile() && activity.isSupportedArchive(single)) {
                actions.add(new Action(activity.getString(R.string.archive_folder_preview), fg, () -> {
                    dismiss(popupRef);
                    activity.exitFileSelectionMode(true);
                    activity.openArchiveFolderPreview(single);
                }));
            }
            actions.add(new Action(activity.getString(single.isDirectory() ? R.string.folder_info : R.string.file_info), fg, () -> {
                dismiss(popupRef);
                activity.showFileInfo(single);
            }));
            actions.add(new Action(activity.getString(R.string.rename), fg, () -> {
                dismiss(popupRef);
                activity.exitFileSelectionMode(true);
                activity.showRenameDialog(single);
            }));
        }
        actions.add(new Action(activity.getString(R.string.select_all), fg, () -> {
            dismiss(popupRef);
            activity.selectAllVisibleFiles();
        }));
        if (!activity.getSelectedShareableFilesSnapshot().isEmpty()) {
            actions.add(new Action(activity.getString(R.string.share), fg, () -> {
                dismiss(popupRef);
                activity.shareSelectedFiles();
            }));
        }
        if (!activity.getSelectedArchiveFilesSnapshot().isEmpty()) {
            actions.add(new Action(activity.getString(R.string.extract_archive), fg, () -> {
                dismiss(popupRef);
                activity.startSelectedArchiveExtraction();
            }));
        }
        if (activity.isRecentFileSelectionContext()) {
            actions.add(new Action(activity.getString(R.string.remove_selected_from_recent_list), fg, () -> {
                dismiss(popupRef);
                activity.removeSelectedFilesFromRecentList();
            }));
        }
        actions.add(new Action(activity.getString(R.string.archive_create), fg, () -> {
            dismiss(popupRef);
            activity.startSelectedArchiveCreation();
        }));
        actions.add(new Action(activity.getString(R.string.cut), fg, () -> {
            dismiss(popupRef);
            activity.startSelectedClipboardOperation(false);
        }));
        actions.add(new Action(activity.getString(R.string.copy), fg, () -> {
            dismiss(popupRef);
            activity.startSelectedClipboardOperation(true);
        }));
        actions.add(new Action(activity.getString(R.string.delete), danger, () -> {
            dismiss(popupRef);
            activity.showSelectedDeleteConfirm();
        }));
        actions.add(new Action(activity.getString(R.string.clear_selection), sub, () -> {
            dismiss(popupRef);
            activity.exitFileSelectionMode(true);
        }));
        return actions;
    }

    private void addActionRow(@NonNull LinearLayout box, @NonNull Action action, int rowBgColor) {
        TextView row = new TextView(activity);
        row.setText(action.label);
        row.setTextColor(action.textColor);
        row.setTextSize(15f);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        boolean allowTwoLines = shouldAllowTwoLineActionLabel(action.label);
        row.setSingleLine(!allowTwoLines);
        row.setMaxLines(allowTwoLines ? 2 : 1);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.setIncludeFontPadding(false);
        if (allowTwoLines) {
            row.setLineSpacing(0f, 0.96f);
        }
        row.setPadding(activity.dpToPx(14), 0, activity.dpToPx(14), 0);
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setColor(rowBgColor);
        rowBg.setCornerRadius(activity.dpToPx(7));
        row.setBackground(rowBg);
        row.setOnClickListener(v -> action.action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(allowTwoLines ? 48 : 38));
        lp.setMargins(activity.dpToPx(6), 0, activity.dpToPx(6), activity.dpToPx(4));
        box.addView(row, lp);
    }

    private static boolean shouldAllowTwoLineActionLabel(@NonNull String label) {
        return label.length() > 18 && label.indexOf(' ') >= 0;
    }

    private void dismiss(@NonNull PopupWindow[] popupRef) {
        if (popupRef[0] != null) popupRef[0].dismiss();
    }

    private static final class Action {
        final String label;
        final int textColor;
        final Runnable action;

        Action(@NonNull String label, int textColor, @NonNull Runnable action) {
            this.label = label;
            this.textColor = textColor;
            this.action = action;
        }
    }
}
