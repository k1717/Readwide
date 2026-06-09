package com.textview.reader;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

final class ReaderBookmarkHintsDialogController {
    private static final int TXT_BOOKMARK_HINT_POPUP_Y_DP = 112;

    private final ReaderActivity activity;

    ReaderBookmarkHintsDialogController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void show() {
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);
        final int sub = activity.dialogStyler().readerDialogSubTextColor(bg);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.setPadding(0, 0, 0, 0);

        TextView title = activity.dialogStyler().makeReaderDialogTitle(
                activity.getString(R.string.bookmark_hints_show), bg, fg);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView message = activity.dialogStyler().makeReaderDialogLabel(
                activity.getString(R.string.bookmark_folder_hint), sub, 13f);
        message.setGravity(Gravity.START);
        message.setSingleLine(false);
        message.setLineSpacing(0f, 1.12f);
        message.setPadding(activity.dpToPx(18), activity.dpToPx(2), activity.dpToPx(18), activity.dpToPx(14));
        panel.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setBackground(activity.dialogStyler().positionedActionPanelBackground(
                activity.dialogStyler().dialogActionPanelFillColor(bg),
                activity.dialogStyler().dialogActionPanelLineColor(bg)));
        actionRow.setPadding(activity.dpToPx(18), 0, activity.dpToPx(18), 0);

        TextView ok = activity.dialogStyler().makeReaderDialogActionText(activity.getString(R.string.ok), fg,
                Gravity.CENTER_VERTICAL | Gravity.END);
        actionRow.addView(ok, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        panel.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(52)));

        android.app.Dialog dialog = activity.dialogStyler().createNarrowPositionedReaderDialog(
                panel,
                bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                TXT_BOOKMARK_HINT_POPUP_Y_DP,
                0.74f,
                360,
                false);
        ok.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }
}
