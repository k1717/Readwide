package com.textview.reader;

import android.graphics.Color;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.textview.reader.model.Bookmark;

final class ReaderBookmarkMemoDialogController {
    private final ReaderActivity activity;

    ReaderBookmarkMemoDialogController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void show(@NonNull Bookmark bookmark, Runnable afterSave) {
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);
        final int sub = activity.dialogStyler().readerDialogSubTextColor(bg);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);

        TextView title = activity.dialogStyler().makeReaderDialogTitle(
                activity.getString(R.string.edit_bookmark_memo), bg, fg);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.TRANSPARENT);
        box.setPadding(activity.dpToPx(22), activity.dpToPx(12), activity.dpToPx(22), activity.dpToPx(8));

        TextView message = new TextView(activity);
        message.setText(bookmark.getFileName() + "\n\n" + bookmark.getExcerpt());
        message.setTextColor(sub);
        message.setTextSize(13f);
        message.setLineSpacing(0f, 1.15f);
        message.setPadding(0, 0, 0, activity.dpToPx(10));
        box.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText input = activity.dialogStyler().makeReaderDialogEditText(
                activity.getString(R.string.optional_memo), bg, fg, sub);
        input.setText(bookmark.getLabel());
        input.setSelectAllOnFocus(true);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(52)));
        panel.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        actions.setPadding(activity.dpToPx(8), activity.dpToPx(4), activity.dpToPx(8), activity.dpToPx(4));
        TextView cancel = activity.dialogStyler().makeReaderDialogActionText(
                activity.getString(R.string.cancel), sub, Gravity.CENTER);
        TextView clear = activity.dialogStyler().makeReaderDialogActionText(
                activity.getString(R.string.clear_memo), sub, Gravity.CENTER);
        TextView save = activity.dialogStyler().makeReaderDialogActionText(
                activity.getString(R.string.save), fg, Gravity.CENTER);
        actions.addView(cancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, activity.dpToPx(46)));
        actions.addView(clear, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, activity.dpToPx(46)));
        actions.addView(save, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, activity.dpToPx(46)));
        panel.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        android.app.Dialog dialog = activity.dialogStyler().createPositionedReaderDialog(
                panel, bg, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 74, 14, 460, true);
        cancel.setOnClickListener(v -> dialog.dismiss());
        clear.setOnClickListener(v -> {
            bookmark.setLabel("");
            activity.bookmarkManager.updateBookmark(bookmark);
            if (afterSave != null) afterSave.run();
            dialog.dismiss();
        });
        save.setOnClickListener(v -> {
            bookmark.setLabel(input.getText().toString().trim());
            activity.bookmarkManager.updateBookmark(bookmark);
            if (afterSave != null) afterSave.run();
            dialog.dismiss();
        });
        dialog.show();
    }
}
