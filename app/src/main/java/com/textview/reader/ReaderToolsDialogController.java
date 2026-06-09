package com.textview.reader;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.textview.reader.util.FileUtils;

import java.io.File;
import java.util.Locale;

/**
 * Owns reader utility dialogs (More, encoding, auto page turn, go-to and find).
 * These popups are UI shells around ReaderActivity's existing navigation/search
 * operations; TXT page continuity state remains in ReaderActivity.
 */
final class ReaderToolsDialogController {
    private static final int TXT_TOOLBAR_POPUP_Y_DP = 74;

    private final ReaderActivity activity;

    ReaderToolsDialogController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void showMoreDialog() {
        activity.dialogStyler().syncReaderDialogThemeSnapshot();
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int panel = activity.dialogStyler().readerDialogPanelColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);

        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Color.TRANSPARENT);

        TextView title = activity.dialogStyler().makeReaderDialogTitle(activity.getString(R.string.more), bg, fg);
        outer.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(Color.TRANSPARENT);
        int pad = activity.dpToPx(14);
        list.setPadding(pad, activity.dpToPx(10), pad, activity.dpToPx(10));

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        activity.dialogStyler().constrainDialogScrollArea(scroll, list);
        scroll.addView(list);

        final android.app.Dialog[] ref = new android.app.Dialog[1];

        addMoreActionRow(list, activity.getString(R.string.brightness), fg, panel, activity::showBrightnessDialog, ref);
        addMoreActionRow(list, activity.getString(R.string.font), fg, panel, activity::showFontDialog, ref);
        addMoreActionRow(list, activity.getString(R.string.increase_font), fg, panel, () -> activity.changeFontSize(2f), ref);
        addMoreActionRow(list, activity.getString(R.string.decrease_font), fg, panel, () -> activity.changeFontSize(-2f), ref);
        addMoreActionRow(list, activity.getString(R.string.reset_font_size), fg, panel, activity::resetFontSize, ref);
        addMoreActionRow(list, activity.getString(R.string.tts_title), fg, panel, activity::showTtsDialog, ref);
        addMoreActionRow(list, activity.getString(R.string.txt_display_rule_quick_add), fg, panel, () -> activity.showQuickTextDisplayRuleDialog("", true), ref);
        addMoreActionRow(list, activity.getString(R.string.txt_display_rule_manage), fg, panel, activity::showReaderTextDisplayRulesManagerDialog, ref);
        addMoreActionRow(list, activity.getString(R.string.text_encoding), fg, panel, this::showTextEncodingDialog, ref);
        addMoreActionRow(list, activity.getString(R.string.file_info), fg, panel, activity::showFileInfoDialog, ref);

        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setBackground(activity.dialogStyler().positionedActionPanelBackground(
                activity.dialogStyler().dialogActionPanelFillColor(bg),
                activity.dialogStyler().dialogActionPanelLineColor(bg)));
        actionRow.setPadding(activity.dpToPx(14), 0, activity.dpToPx(14), 0);

        TextView openFile = activity.dialogStyler().makeReaderDialogActionText(activity.getString(R.string.action_open_file), fg,
                Gravity.CENTER_VERTICAL | Gravity.START);
        TextView close = activity.dialogStyler().makeReaderDialogActionText(activity.getString(R.string.close), fg,
                Gravity.CENTER_VERTICAL | Gravity.END);

        actionRow.addView(openFile, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f));
        actionRow.addView(close, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f));

        outer.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(54)));

        android.app.Dialog dialog = activity.dialogStyler().createNarrowPositionedReaderDialog(
                outer,
                bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                TXT_TOOLBAR_POPUP_Y_DP,
                0.85f,
                460,
                false);
        ref[0] = dialog;

        openFile.setOnClickListener(v -> {
            dialog.dismiss();
            activity.openFileBrowserFromViewer();
        });
        close.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    void showTextEncodingDialog() {
        if (activity.filePath == null || activity.filePath.isEmpty()) {
            ShortToast.show(activity, R.string.no_file_selected);
            return;
        }

        File file = new File(activity.filePath);
        if (!FileUtils.isTextFile(file.getName())) {
            ShortToast.show(activity, R.string.text_encoding_txt_only);
            return;
        }

        activity.dialogStyler().syncReaderDialogThemeSnapshot();
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int panel = activity.dialogStyler().readerDialogPanelColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);
        final int sub = activity.dialogStyler().readerDialogSubTextColor(bg);

        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Color.TRANSPARENT);

        TextView title = new TextView(activity);
        title.setText(activity.getString(R.string.text_encoding));
        title.setTextColor(fg);
        title.setTextSize(19f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setIncludeFontPadding(false);
        title.setPadding(activity.dpToPx(14), activity.dpToPx(10), activity.dpToPx(14), activity.dpToPx(8));
        title.setBackgroundColor(Color.TRANSPARENT);
        outer.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(48)));

        String manual = activity.prefs != null ? activity.prefs.getManualTextEncodingForFile(file) : null;
        String cachedLabel = activity.prefs != null ? activity.prefs.getCachedAutoTextEncodingLabelForFile(file) : null;
        String autoLabel = activity.currentTextEncodingLabel;
        if (autoLabel == null || autoLabel.trim().isEmpty() || activity.currentTextEncodingManual) {
            autoLabel = cachedLabel != null && !cachedLabel.trim().isEmpty() ? cachedLabel : "auto";
        }
        String status = manual != null
                ? activity.getString(R.string.text_encoding_status_manual, manual, autoLabel)
                : activity.getString(R.string.text_encoding_status_auto, autoLabel);
        TextView statusView = new TextView(activity);
        statusView.setText(status);
        statusView.setTextColor(sub);
        statusView.setTextSize(12f);
        statusView.setMaxLines(2);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        statusView.setIncludeFontPadding(false);
        statusView.setPadding(activity.dpToPx(14), 0, activity.dpToPx(14), activity.dpToPx(4));
        statusView.setBackgroundColor(Color.TRANSPARENT);
        outer.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(42)));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = activity.dpToPx(10);
        list.setPadding(pad, activity.dpToPx(6), pad, activity.dpToPx(8));

        final android.app.Dialog[] ref = new android.app.Dialog[1];
        addEncodingOptionGrid(list, FileUtils.getManualTextEncodingOptions(), fg, panel, file, ref);

        ScrollView scroll = new ScrollView(activity);
        activity.dialogStyler().constrainDialogScrollArea(scroll, list);
        scroll.addView(list);

        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(268)));

        TextView closeButton = new TextView(activity);
        closeButton.setText(activity.getString(R.string.close));
        closeButton.setTextColor(fg);
        closeButton.setTextSize(14f);
        closeButton.setTypeface(Typeface.DEFAULT_BOLD);
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        closeButton.setIncludeFontPadding(false);
        closeButton.setBackgroundColor(Color.TRANSPARENT);
        closeButton.setPadding(activity.dpToPx(12), 0, activity.dpToPx(12), 0);
        closeButton.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(38));
        closeLp.setMargins(activity.dpToPx(12), activity.dpToPx(4), activity.dpToPx(12), activity.dpToPx(8));
        outer.addView(closeButton, closeLp);

        android.app.Dialog dialog = activity.dialogStyler().createNarrowPositionedReaderDialog(
                outer,
                bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                TXT_TOOLBAR_POPUP_Y_DP,
                0.84f,
                500,
                false);
        ref[0] = dialog;
        dialog.show();
    }

    void showAutoPageTurnDialog() {
        activity.dialogStyler().syncReaderDialogThemeSnapshot();
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);
        final int sub = activity.dialogStyler().readerDialogSubTextColor(bg);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.setClipChildren(true);
        panel.setClipToPadding(true);

        TextView title = activity.dialogStyler().makeReaderDialogTitle(activity.getString(R.string.auto_page_turn), bg, fg);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = activity.dpToPx(18);
        box.setPadding(pad, activity.dpToPx(4), pad, activity.dpToPx(12));
        box.setBackgroundColor(Color.TRANSPARENT);

        TextView desc = new TextView(activity);
        desc.setText(R.string.auto_page_turn_description);
        desc.setTextColor(sub);
        desc.setTextSize(13f);
        desc.setLineSpacing(0, 1.08f);
        desc.setPadding(0, 0, 0, activity.dpToPx(10));
        box.addView(desc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText secondsInput = activity.dialogStyler().makeReaderDialogEditText(activity.getString(R.string.auto_page_turn_interval_hint), bg, fg, sub);
        secondsInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        secondsInput.setSingleLine(true);
        secondsInput.setGravity(Gravity.CENTER);
        secondsInput.setText(String.valueOf(activity.prefs != null ? activity.prefs.getAutoPageTurnIntervalSeconds() : 8));
        secondsInput.setSelectAllOnFocus(true);

        LinearLayout intervalRow = new LinearLayout(activity);
        intervalRow.setOrientation(LinearLayout.HORIZONTAL);
        intervalRow.setGravity(Gravity.CENTER);
        intervalRow.setPadding(0, 0, 0, 0);
        intervalRow.addView(new Space(activity), new LinearLayout.LayoutParams(0, 1, 0.30f));
        intervalRow.addView(secondsInput, new LinearLayout.LayoutParams(0, activity.dpToPx(52), 0.40f));
        intervalRow.addView(new Space(activity), new LinearLayout.LayoutParams(0, 1, 0.30f));
        box.addView(intervalRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activity.dpToPx(52)));

        panel.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setBackground(activity.dialogStyler().positionedActionPanelBackground(
                activity.dialogStyler().dialogActionPanelFillColor(bg),
                activity.dialogStyler().dialogActionPanelLineColor(bg)));
        actionRow.setPadding(activity.dpToPx(8), 0, activity.dpToPx(8), 0);

        TextView stopButton = activity.dialogStyler().makeReaderDialogActionText(activity.getString(R.string.auto_page_turn_stop), sub, Gravity.CENTER);
        TextView cancelButton = activity.dialogStyler().makeReaderDialogActionText(activity.getString(R.string.cancel), sub, Gravity.CENTER);
        TextView startButton = activity.dialogStyler().makeReaderDialogActionText(activity.getString(R.string.auto_page_turn_start), fg, Gravity.CENTER);
        actionRow.addView(stopButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        actionRow.addView(cancelButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        actionRow.addView(startButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        panel.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(54)));

        android.app.Dialog dialog = activity.dialogStyler().createNarrowPositionedReaderDialog(
                panel,
                bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                TXT_TOOLBAR_POPUP_Y_DP,
                0.70f,
                420,
                true);

        startButton.setOnClickListener(v -> {
            int seconds = parseAutoPageTurnSeconds(secondsInput.getText() != null ? secondsInput.getText().toString() : "");
            if (activity.prefs != null) activity.prefs.setAutoPageTurnIntervalSeconds(seconds);
            activity.startAutoPageTurn();
            dialog.dismiss();
        });
        stopButton.setOnClickListener(v -> {
            activity.stopAutoPageTurn(true);
            dialog.dismiss();
        });
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    void showGoToDialog() {
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);
        final int sub = activity.dialogStyler().readerDialogSubTextColor(bg);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.TRANSPARENT);
        box.setPadding(activity.dpToPx(24), activity.dpToPx(12), activity.dpToPx(24), activity.dpToPx(8));

        TextView percentLabel = activity.dialogStyler().makeReaderDialogLabel(activity.getString(R.string.go_to_percentage), fg, 14f);
        box.addView(percentLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText inputPercent = activity.dialogStyler().makeReaderDialogEditText(activity.getString(R.string.example_50), bg, fg, sub);
        inputPercent.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(inputPercent, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(52)));

        TextView lineLabel = activity.dialogStyler().makeReaderDialogLabel(activity.getString(R.string.or_go_to_line_number), fg, 14f);
        lineLabel.setPadding(0, activity.dpToPx(6), 0, 0);
        box.addView(lineLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText inputLine = activity.dialogStyler().makeReaderDialogEditText(activity.getString(R.string.example_1000), bg, fg, sub);
        inputLine.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(inputLine, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(52)));

        TextView title = activity.dialogStyler().makeReaderDialogTitle(activity.getString(R.string.go_to_position), bg, fg);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setCustomTitle(title)
                .setView(box)
                .setPositiveButton(activity.getString(R.string.go), (d, w) -> {
                    String pStr = inputPercent.getText().toString().trim();
                    String lStr = inputLine.getText().toString().trim();
                    if (!pStr.isEmpty()) {
                        try { activity.scrollToPercent(Float.parseFloat(pStr) / 100f); }
                        catch (NumberFormatException ignored) {}
                    } else if (!lStr.isEmpty()) {
                        try {
                            int targetLine = Math.max(1, Integer.parseInt(lStr));
                            activity.scrollToCharPosition(activity.findCharForLine(targetLine));
                        } catch (NumberFormatException ignored) {}
                    }
                    activity.updatePositionLabel();
                })
                .setNegativeButton(activity.getString(R.string.cancel), null)
                .create();

        activity.dialogStyler().prepareReaderAlertDialogWindowNoJump(dialog, true);
        dialog.setOnShowListener(d -> {
            activity.dialogStyler().styleReaderDialogWindow(dialog, bg, fg, sub);
            activity.dialogStyler().prepareReaderAlertDialogWindowNoJump(dialog, false);
            if (dialog.getWindow() != null) {
                View decor = dialog.getWindow().getDecorView();
                decor.post(() -> decor.setAlpha(1f));
            }
        });
        dialog.show();
        activity.dialogStyler().prepareReaderAlertDialogWindowNoJump(dialog, false);
    }

    void showTextSearch() {
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);
        final int sub = activity.dialogStyler().readerDialogSubTextColor(bg);

        FrameLayout titleBox = new FrameLayout(activity);
        titleBox.setPadding(activity.dpToPx(22), activity.dpToPx(18), activity.dpToPx(22), activity.dpToPx(8));
        titleBox.setBackgroundColor(Color.TRANSPARENT);

        TextView title = new TextView(activity);
        title.setText(activity.getString(R.string.find_in_text));
        title.setTextColor(fg);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        title.setIncludeFontPadding(false);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.START);
        titleLp.setMarginEnd(activity.dpToPx(116));
        titleBox.addView(title, titleLp);

        TextView matchStatus = new TextView(activity);
        matchStatus.setText("0 / 0");
        matchStatus.setTextColor(sub);
        matchStatus.setTextSize(12f);
        matchStatus.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        matchStatus.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        matchStatus.setIncludeFontPadding(false);
        matchStatus.setMinWidth(activity.dpToPx(100));
        titleBox.addView(matchStatus, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.END));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.TRANSPARENT);
        box.setPadding(activity.dpToPx(24), activity.dpToPx(12), activity.dpToPx(24), activity.dpToPx(8));

        EditText input = activity.dialogStyler().makeReaderDialogEditText(activity.getString(R.string.search_text_hint), bg, fg, sub);
        String rememberedQuery = activity.activeSearchQuery;
        if ((rememberedQuery == null || rememberedQuery.isEmpty()) && activity.prefs != null) {
            rememberedQuery = activity.prefs.getLastReaderSearchQuery();
        }
        if (rememberedQuery == null) rememberedQuery = "";
        input.setText(rememberedQuery);
        if (!rememberedQuery.isEmpty()) {
            input.setSelection(input.getText().length());
            if (activity.largeTextEstimateActive) {
                int knownTotal = activity.getCachedLargeTextSearchTotal(rememberedQuery);
                int ordinal = activity.activeSearchIndex >= 0 ? Math.max(1, activity.activeSearchOrdinal) : 0;
                activity.updateLargeTextSearchStatus(matchStatus, ordinal, knownTotal);
            } else {
                int total = activity.countTextMatches(rememberedQuery);
                int ordinal = activity.activeSearchIndex >= 0 ? activity.matchIndexForPosition(rememberedQuery, activity.activeSearchIndex) : 0;
                activity.activeSearchOrdinal = ordinal;
                matchStatus.setText(String.format(Locale.getDefault(), "%d / %d", ordinal, total));
            }
        }
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(52)));

        EditText occurrenceInput = activity.dialogStyler().makeReaderDialogEditText(activity.getString(R.string.search_occurrence_hint), bg, fg, sub);
        occurrenceInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams occurrenceLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(48));
        occurrenceLp.setMargins(0, activity.dpToPx(8), 0, 0);
        box.addView(occurrenceInput, occurrenceLp);

        TextView hint = activity.dialogStyler().makeReaderDialogLabel(activity.getString(R.string.search_hint_multiple), sub, 12f);
        hint.setPadding(0, activity.dpToPx(6), 0, activity.dpToPx(8));
        box.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, activity.dpToPx(8), 0, 0);

        TextView prevButton = makeSearchDialogButton(activity.getString(R.string.find_previous), bg, fg, sub);
        TextView nthButton = makeSearchDialogButton(activity.getString(R.string.find_nth), bg, fg, sub);
        TextView closeButton = makeSearchDialogButton(activity.getString(R.string.close), bg, fg, sub);
        TextView nextButton = makeSearchDialogButton(activity.getString(R.string.find_next), bg, fg, sub);

        buttons.addView(prevButton, new LinearLayout.LayoutParams(0, activity.dpToPx(44), 1f));
        buttons.addView(nthButton, new LinearLayout.LayoutParams(0, activity.dpToPx(44), 1f));
        buttons.addView(closeButton, new LinearLayout.LayoutParams(0, activity.dpToPx(44), 1f));
        buttons.addView(nextButton, new LinearLayout.LayoutParams(0, activity.dpToPx(44), 1f));
        box.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.addView(titleBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        android.app.Dialog dialog = activity.dialogStyler().createNarrowPositionedReaderDialog(
                panel,
                bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                TXT_TOOLBAR_POPUP_Y_DP,
                0.85f,
                460,
                true);

        activity.dialogStyler().updatePositionedReaderDialogYOffset(dialog, TXT_TOOLBAR_POPUP_Y_DP);

        prevButton.setOnClickListener(v -> activity.performTextSearchMove(
                input.getText().toString(), false, matchStatus));
        nthButton.setOnClickListener(v -> {
            int occurrence = parseSearchOccurrenceTarget(occurrenceInput);
            if (occurrence > 0) {
                activity.performTextSearchMove(input.getText().toString(), true, matchStatus, occurrence);
            }
        });
        nextButton.setOnClickListener(v -> activity.performTextSearchMove(
                input.getText().toString(), true, matchStatus));
        closeButton.setOnClickListener(v -> dialog.dismiss());

        input.setOnEditorActionListener((v, actionId, event) -> {
            activity.performTextSearchMove(input.getText().toString(), true, matchStatus);
            return true;
        });
        occurrenceInput.setOnEditorActionListener((v, actionId, event) -> {
            int occurrence = parseSearchOccurrenceTarget(occurrenceInput);
            if (occurrence > 0) {
                activity.performTextSearchMove(input.getText().toString(), true, matchStatus, occurrence);
            }
            return true;
        });

        dialog.setOnDismissListener(d -> {
            if (activity.prefs != null) {
                activity.prefs.setLastReaderSearchQuery(input.getText() != null ? input.getText().toString() : "");
            }
            activity.resetActiveSearchState();
        });
        dialog.show();
    }

    private void addEncodingOptionGrid(@NonNull LinearLayout list,
                                       @NonNull String[] options,
                                       int fg,
                                       int panel,
                                       @NonNull File file,
                                       @NonNull android.app.Dialog[] dialogRef) {
        LinearLayout row = null;
        for (int i = 0; i < options.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER);
                row.setBackgroundColor(Color.TRANSPARENT);
                list.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        activity.dpToPx(44)));
            }

            String option = options[i];
            TextView cell = makeEncodingOptionCell(option, fg, panel);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0,
                    activity.dpToPx(40),
                    1f);
            lp.setMargins(i % 2 == 0 ? 0 : activity.dpToPx(4), activity.dpToPx(2), i % 2 == 0 ? activity.dpToPx(4) : 0, activity.dpToPx(4));
            cell.setOnClickListener(v -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                activity.applyTextEncodingSelection(file, option);
            });
            if (row != null) row.addView(cell, lp);
        }
    }

    private TextView makeEncodingOptionCell(@NonNull String label, int fg, int panel) {
        TextView cell = new TextView(activity);
        cell.setText(label);
        cell.setTextColor(fg);
        cell.setTextSize(13f);
        cell.setGravity(Gravity.CENTER);
        cell.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        cell.setIncludeFontPadding(false);
        cell.setSingleLine(true);
        cell.setEllipsize(TextUtils.TruncateAt.END);
        cell.setPadding(activity.dpToPx(8), 0, activity.dpToPx(8), 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(panel);
        bg.setCornerRadius(activity.dpToPx(10));
        bg.setStroke(0, Color.TRANSPARENT);
        cell.setBackground(bg);
        return cell;
    }

    private int parseAutoPageTurnSeconds(String raw) {
        try {
            return Math.max(2, Math.min(120, Integer.parseInt(raw.trim())));
        } catch (Exception ignored) {
            return activity.prefs != null ? activity.prefs.getAutoPageTurnIntervalSeconds() : 8;
        }
    }

    private void addMoreActionRow(
            LinearLayout list,
            String label,
            int fg,
            int panel,
            Runnable action,
            android.app.Dialog[] dialogRef
    ) {
        TextView row = activity.dialogStyler().makeReaderActionRow(label, fg);
        row.setGravity(Gravity.CENTER);
        row.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        row.setPadding(0, 0, 0, 0);
        row.setOnClickListener(v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            action.run();
        });
        list.addView(row);
    }

    private int parseSearchOccurrenceTarget(EditText occurrenceInput) {
        if (occurrenceInput == null || occurrenceInput.getText() == null) return -1;
        String raw = occurrenceInput.getText().toString().trim();
        if (raw.isEmpty()) return -1;
        try {
            int occurrence = Integer.parseInt(raw);
            return occurrence > 0 ? occurrence : -1;
        } catch (NumberFormatException ignored) {
            ShortToast.show(activity, activity.getString(R.string.search_occurrence_invalid));
            return -1;
        }
    }

    private TextView makeSearchDialogButton(String label, int bg, int fg, int sub) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextColor(fg);
        button.setTextSize(14f);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setIncludeFontPadding(false);
        button.setPadding(activity.dpToPx(4), 0, activity.dpToPx(4), 0);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }
}
