package com.textview.reader;

import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

final class ReaderBookmarkFolderDeleteDialogController {
    private final ReaderActivity activity;

    ReaderBookmarkFolderDeleteDialogController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void show(String folderFilePath, String folderName, int bookmarkCount, Runnable afterDelete) {
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);
        final int sub = activity.dialogStyler().readerDialogSubTextColor(bg);
        final int danger = activity.isLightColor(bg) ? Color.rgb(95, 35, 35) : Color.rgb(255, 170, 170);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);

        TextView title = activity.dialogStyler().makeReaderDialogTitle(
                activity.getString(R.string.delete_bookmark_folder), bg, fg);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.TRANSPARENT);
        box.setPadding(activity.dpToPx(22), activity.dpToPx(12), activity.dpToPx(22), activity.dpToPx(8));

        TextView message = new TextView(activity);
        String displayName = folderName != null && !folderName.trim().isEmpty()
                ? folderName.trim()
                : activity.getString(R.string.bookmark);
        message.setText(displayName + "\n\n"
                + activity.getString(R.string.delete_bookmark_folder_message, bookmarkCount)
                + "\n" + activity.getString(R.string.delete_bookmark_folder_note));
        message.setTextColor(fg);
        message.setTextSize(14f);
        message.setLineSpacing(0f, 1.15f);
        message.setPadding(0, activity.dpToPx(4), 0, activity.dpToPx(8));
        box.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        actions.setBackground(activity.dialogStyler().actionPanelBackground(
                activity.dialogStyler().dialogActionPanelFillColor(bg),
                activity.dialogStyler().dialogActionPanelLineColor(bg)));
        actions.setPadding(activity.dpToPx(8), activity.dpToPx(4), activity.dpToPx(8), activity.dpToPx(4));
        TextView cancel = activity.dialogStyler().makeReaderDialogActionText(
                activity.getString(R.string.cancel), sub, Gravity.CENTER);
        TextView delete = activity.dialogStyler().makeReaderDialogActionText(
                activity.getString(R.string.delete), danger, Gravity.CENTER);
        actions.addView(cancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, activity.dpToPx(46)));
        actions.addView(delete, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, activity.dpToPx(46)));
        panel.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        android.app.Dialog dialog = activity.dialogStyler().createPositionedReaderDialog(
                panel, bg, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 74, 14, 460, false);
        cancel.setOnClickListener(v -> dialog.dismiss());
        delete.setOnClickListener(v -> {
            activity.bookmarkManager.deleteBookmarksForFile(folderFilePath);
            if (afterDelete != null) afterDelete.run();
            dialog.dismiss();
        });
        dialog.show();
    }
}
