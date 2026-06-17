package com.readwide.manager;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The TTS sleep-timer chooser UI (preset list, custom-minutes entry, and the
 * finish-sentence toggle). Extracted from {@link ReaderTtsController} so the
 * controller keeps to playback logic. Reads/writes timer prefs and tells the
 * controller to re-read them mid-playback via {@link ReaderTtsController#refreshSleepTimerFromPrefs()}.
 */
final class TtsSleepTimerDialog {
    private final ReaderActivity activity;
    private final ReaderTtsController controller;

    TtsSleepTimerDialog(@NonNull ReaderActivity activity, @NonNull ReaderTtsController controller) {
        this.activity = activity;
        this.controller = controller;
    }

    /** Label for the row that opens this dialog (shows the current setting). */
    String rowLabel() {
        int minutes = activity.prefs != null ? activity.prefs.getTtsSleepTimerMinutes() : 0;
        if (minutes <= 0) {
            return activity.getString(R.string.tts_sleep_timer) + ": "
                    + activity.getString(R.string.tts_sleep_timer_off);
        }
        return activity.getString(R.string.tts_sleep_timer_set, minutes);
    }

    void show(@Nullable Runnable onChanged) {
        if (activity.prefs == null) return;
        activity.dialogStyler().syncReaderDialogThemeSnapshot();
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);

        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Color.TRANSPARENT);

        TextView title = activity.dialogStyler().makeReaderDialogTitle(
                activity.getString(R.string.tts_sleep_timer), bg, fg);
        outer.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = activity.dpToPx(14);
        list.setPadding(pad, activity.dpToPx(4), pad, activity.dpToPx(8));

        final android.app.Dialog[] ref = new android.app.Dialog[1];

        int[] presets = {0, 15, 30, 45, 60};
        int[] labels = {
                R.string.tts_sleep_timer_off,
                R.string.tts_sleep_timer_15,
                R.string.tts_sleep_timer_30,
                R.string.tts_sleep_timer_45,
                R.string.tts_sleep_timer_60
        };
        for (int i = 0; i < presets.length; i++) {
            final int minutes = presets[i];
            TextView row = activity.dialogStyler().makeReaderActionRow(
                    activity.getString(labels[i]), fg);
            row.setGravity(Gravity.CENTER);
            row.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
            row.setOnClickListener(v -> {
                activity.prefs.setTtsSleepTimerMinutes(minutes);
                controller.refreshSleepTimerFromPrefs();
                if (onChanged != null) onChanged.run();
                if (ref[0] != null) ref[0].dismiss();
            });
            list.addView(row);
        }

        TextView customRow = activity.dialogStyler().makeReaderActionRow(
                activity.getString(R.string.tts_sleep_timer_custom), fg);
        customRow.setGravity(Gravity.CENTER);
        customRow.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        customRow.setOnClickListener(v -> showCustom(() -> {
            if (onChanged != null) onChanged.run();
            if (ref[0] != null) ref[0].dismiss();
        }));
        list.addView(customRow);

        TextView finishToggle = activity.dialogStyler().makeReaderActionRow(
                finishSentenceRowLabel(), fg);
        finishToggle.setGravity(Gravity.CENTER);
        finishToggle.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        finishToggle.setOnClickListener(v -> {
            boolean next = !activity.prefs.getTtsSleepTimerFinishSentence();
            activity.prefs.setTtsSleepTimerFinishSentence(next);
            controller.refreshSleepTimerFromPrefs();
            finishToggle.setText(finishSentenceRowLabel());
        });
        list.addView(finishToggle);

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        activity.dialogStyler().constrainDialogScrollArea(scroll, list);
        scroll.addView(list);
        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        android.app.Dialog dialog = activity.dialogStyler().createNarrowPositionedReaderDialog(
                outer,
                bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                74,
                0.78f,
                420,
                true);
        ref[0] = dialog;
        dialog.show();
    }

    private String finishSentenceRowLabel() {
        boolean on = activity.prefs != null && activity.prefs.getTtsSleepTimerFinishSentence();
        return activity.getString(R.string.tts_sleep_timer_finish_sentence)
                + ": " + (on ? "ON" : "OFF");
    }

    private void showCustom(@Nullable Runnable onChanged) {
        if (activity.prefs == null) return;
        activity.dialogStyler().syncReaderDialogThemeSnapshot();
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);
        final int sub = activity.dialogStyler().readerDialogSubTextColor(bg);

        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Color.TRANSPARENT);

        TextView title = activity.dialogStyler().makeReaderDialogTitle(
                activity.getString(R.string.tts_sleep_timer_custom_minutes), bg, fg);
        outer.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = activity.dpToPx(16);
        body.setPadding(pad, activity.dpToPx(6), pad, activity.dpToPx(6));

        final android.widget.EditText input = new android.widget.EditText(activity);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setTextColor(fg);
        input.setHintTextColor(sub);
        input.setHint(R.string.tts_sleep_timer_custom_minutes);
        input.setGravity(Gravity.CENTER);
        input.setPadding(activity.dpToPx(14), activity.dpToPx(10),
                activity.dpToPx(14), activity.dpToPx(10));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setCornerRadius(activity.dpToPx(12));
        inputBg.setColor(activity.dialogStyler().readerDialogPanelColor());
        inputBg.setStroke(activity.dpToPx(1), sub);
        input.setBackground(inputBg);
        int current = activity.prefs.getTtsSleepTimerMinutes();
        if (current > 0) input.setText(String.valueOf(current));
        body.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final android.app.Dialog[] ref = new android.app.Dialog[1];

        TextView okRow = activity.dialogStyler().makeReaderActionRow(
                activity.getString(android.R.string.ok), fg);
        okRow.setGravity(Gravity.CENTER);
        okRow.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        okRow.setOnClickListener(v -> {
            int minutes = 0;
            try {
                minutes = Integer.parseInt(input.getText().toString().trim());
            } catch (NumberFormatException ignored) { }
            minutes = Math.max(0, Math.min(600, minutes));
            activity.prefs.setTtsSleepTimerMinutes(minutes);
            controller.refreshSleepTimerFromPrefs();
            if (onChanged != null) onChanged.run();
            if (ref[0] != null) ref[0].dismiss();
        });
        body.addView(okRow);

        TextView cancelRow = activity.dialogStyler().makeReaderActionRow(
                activity.getString(android.R.string.cancel), fg);
        cancelRow.setGravity(Gravity.CENTER);
        cancelRow.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        cancelRow.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
        });
        body.addView(cancelRow);

        outer.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        android.app.Dialog dialog = activity.dialogStyler().createNarrowPositionedReaderDialog(
                outer,
                bg,
                Gravity.CENTER,
                0,
                0.78f,
                360,
                true);
        ref[0] = dialog;
        dialog.show();
        input.requestFocus();
    }
}
