package com.textview.reader;

import android.content.Intent;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.textview.reader.controller.ReaderToolbarController;

final class ReaderBottomControlsController {
    private static final int TXT_TOOLBAR_POPUP_Y_DP = 74;

    private final ReaderActivity activity;

    ReaderBottomControlsController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void setupBottomControls() {
        activity.readerToolbarController = new ReaderToolbarController(activity, activity.bottomBar);
        activity.readerToolbarController.prepareToolbarContainer();
        activity.readerToolbarController.setupScrollableActionStrip(
                R.id.toolbar_action_scroll,
                R.id.toolbar_scroll_actions,
                5,
                2);
        ButtonOrderManager.applyOrder(activity, activity.prefs, ButtonOrderManager.GROUP_TXT_READER);

        activity.readerToolbarController.bindScrollableButton(R.id.btn_home, activity::openHomeFromViewer);
        activity.readerToolbarController.bindScrollableButton(R.id.btn_open_file, activity::openFileBrowserFromViewer);
        activity.readerToolbarController.bindScrollableButton(R.id.btn_find, activity::showTextSearch);
        activity.readerToolbarController.bindScrollableButton(R.id.btn_page_move, this::showPageMoveBubble);
        activity.readerToolbarController.bindScrollableButton(R.id.btn_bookmark, activity::showBookmarksForFile);
        activity.readerToolbarController.bindScrollableButton(R.id.btn_auto_page, activity::showAutoPageTurnDialog);
        activity.readerToolbarController.bindScrollableButton(R.id.btn_tts, activity::showTtsDialog);
        activity.readerToolbarController.bindScrollableButton(R.id.btn_settings, () -> {
            Intent settingsIntent = new Intent(activity, SettingsActivity.class);
            if (activity.filePath != null) settingsIntent.putExtra("txt_file_path", activity.filePath);
            activity.startActivity(settingsIntent);
        });
        activity.readerToolbarController.bindScrollableButton(R.id.btn_font, activity::showFontDialog);
        activity.readerToolbarController.bindScrollableButton(R.id.btn_rule_add, () -> activity.showQuickTextDisplayRuleDialog("", true));
        activity.readerToolbarController.bindScrollableButton(R.id.btn_rule_manage, activity::showReaderTextDisplayRulesManagerDialog);
        activity.readerToolbarController.bindScrollableButton(R.id.btn_text_encoding, activity::showTextEncodingDialog);
        activity.readerToolbarController.bindFixedButton(R.id.btn_more, activity::showMoreDialog);
    }

    void showPageMoveBubble() {
        if (activity.fileContent == null || activity.fileContent.isEmpty()) {
            ShortToast.show(activity, activity.getString(R.string.file_not_loaded));
            return;
        }

        final int bubbleBg = activity.dialogStyler().readerDialogBgColor();
        final int bubbleFg = activity.dialogStyler().readerDialogTextColor(bubbleBg);
        final int bubbleSub = activity.dialogStyler().readerDialogSubTextColor(bubbleBg);
        int totalPages = activity.getDisplayedTotalPageCount();
        int currentPage = activity.getDisplayedCurrentPageNumber();

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.setClipChildren(true);
        panel.setClipToPadding(true);

        TextView title = activity.dialogStyler().makeReaderDialogTitle(activity.getString(R.string.page_move), bubbleBg, bubbleFg);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = activity.dpToPx(20);
        box.setPadding(pad, activity.dpToPx(8), pad, activity.dpToPx(12));
        box.setBackgroundColor(Color.TRANSPARENT);

        TextView label = new TextView(activity);
        label.setGravity(Gravity.CENTER);
        label.setTextSize(17f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(bubbleFg);
        label.setText(formatPageMoveLabel(currentPage, totalPages,
                activity.getLineOffsetWithinDisplayedPage(currentPage, totalPages)));
        box.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        SeekBar dialogSeek = new SeekBar(activity);
        dialogSeek.setMax(Math.max(0, totalPages - 1));
        dialogSeek.setProgress(Math.max(0, currentPage - 1));
        int trackColor = activity.dialogStyler().blendColors(bubbleBg, bubbleFg, 0.52f);
        int progressColor = activity.dialogStyler().blendColors(bubbleBg, bubbleFg, 0.82f);
        int thumbColor = activity.dialogStyler().blendColors(bubbleBg, bubbleFg, 0.94f);
        dialogSeek.setThumbTintList(ColorStateList.valueOf(thumbColor));
        dialogSeek.setProgressTintList(ColorStateList.valueOf(progressColor));
        dialogSeek.setProgressBackgroundTintList(ColorStateList.valueOf(trackColor));
        dialogSeek.setPadding(activity.dpToPx(18), 0, activity.dpToPx(18), 0);
        box.addView(dialogSeek, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(42)));

        TextView pageHint = new TextView(activity);
        pageHint.setText(activity.getString(R.string.exact_page_number));
        pageHint.setTextSize(13f);
        pageHint.setTextColor(activity.dialogStyler().blendColors(bubbleBg, bubbleFg, 0.78f));
        pageHint.setGravity(Gravity.CENTER);
        pageHint.setPadding(0, activity.dpToPx(3), 0, 0);
        box.addView(pageHint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText pageInput = activity.dialogStyler().makeReaderDialogEditText("1 - " + totalPages, bubbleBg, bubbleFg, bubbleSub);
        pageInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pageInput.setGravity(Gravity.CENTER);
        pageInput.setText(String.valueOf(currentPage));
        pageInput.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams pageInputLp = new LinearLayout.LayoutParams(
                activity.dpToPx(132),
                activity.dpToPx(52));
        pageInputLp.gravity = Gravity.CENTER_HORIZONTAL;
        pageInputLp.setMargins(0, activity.dpToPx(8), 0, 0);
        box.addView(pageInput, pageInputLp);

        panel.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setBackground(activity.dialogStyler().positionedActionPanelBackground(
                activity.dialogStyler().dialogActionPanelFillColor(bubbleBg),
                activity.dialogStyler().dialogActionPanelLineColor(bubbleBg)));
        actionRow.setPadding(activity.dpToPx(30), 0, activity.dpToPx(30), 0);

        TextView closeButton = activity.dialogStyler().makeReaderDialogActionText(activity.getString(R.string.go), bubbleFg,
                Gravity.CENTER);
        // Nudge only the TXT Page Move Go button upward slightly. Keep the
        // original action-row height/layout so this does not reintroduce the
        // previous compact-row alignment change.
        closeButton.setTranslationY(-activity.dpToPx(4));
        actionRow.addView(closeButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        panel.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(54)));

        android.app.Dialog dialog = activity.dialogStyler().createNarrowPositionedReaderDialog(
                panel,
                bubbleBg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                TXT_TOOLBAR_POPUP_Y_DP,
                0.85f,
                460,
                true);

        final int[] pendingPage = new int[]{currentPage};
        dialogSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                int pages = activity.getDisplayedTotalPageCount();
                int page = Math.max(1, Math.min(pages, progress + 1));
                pendingPage[0] = page;
                label.setText(formatPageMoveLabel(page, pages,
                        activity.getLineOffsetWithinDisplayedPage(page, pages)));
                pageInput.setText(String.valueOf(page));
                pageInput.setSelection(pageInput.getText().length());
            }

            @Override public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                int pages = activity.getDisplayedTotalPageCount();
                int page = Math.max(1, Math.min(pages, pendingPage[0]));
                activity.previewToolbarSeekPage(page, pages);
                boolean accepted = activity.scrollToPageNumber(page, true, true);
                if (accepted) {
                    activity.beginPendingToolbarSeekJump(page, pages);
                } else {
                    activity.clearPendingToolbarSeekJump();
                    activity.updatePositionLabel();
                }
            }
        });

        closeButton.setOnClickListener(v -> {
            String raw = pageInput.getText().toString().trim();
            if (raw.isEmpty()) {
                dialog.dismiss();
                return;
            }
            try {
                int page = Integer.parseInt(raw);
                if (page < 1 || page > activity.getDisplayedTotalPageCount()) {
                    ShortToast.show(activity, activity.getString(R.string.page_range_error, activity.getDisplayedTotalPageCount()));
                    return;
                }
                int pages = activity.getDisplayedTotalPageCount();
                page = Math.max(1, Math.min(pages, page));
                activity.previewToolbarSeekPage(page, pages);
                boolean accepted = activity.scrollToPageNumber(page, true, true);
                if (accepted) {
                    activity.beginPendingToolbarSeekJump(page, pages);
                } else {
                    activity.clearPendingToolbarSeekJump();
                    activity.updatePositionLabel();
                }
                dialog.dismiss();
            } catch (NumberFormatException ex) {
                ShortToast.show(activity, activity.getString(R.string.invalid_page_number));
            }
        });

        dialog.show();
    }

    String formatPageMoveLabel(int page, int totalPages, int lineNumber) {
        return formatPageMoveLabel(page, String.valueOf(Math.max(1, totalPages)), lineNumber);
    }

    String formatPageMoveLabel(int page, String totalPagesText, int lineNumber) {
        int safeLine = Math.max(1, lineNumber);
        String safeTotal = (totalPagesText == null || totalPagesText.trim().isEmpty())
                ? "1"
                : totalPagesText;
        if (safeLine <= 1) {
            return page + " / " + safeTotal;
        }
        return activity.getString(R.string.page_move_label_with_line,
                page,
                safeTotal,
                safeLine);
    }

}
