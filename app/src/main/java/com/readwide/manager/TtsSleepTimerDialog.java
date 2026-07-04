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
    private final TtsHost host;
    private final androidx.appcompat.app.AppCompatActivity activity;
    private final ReaderTtsController controller;

    TtsSleepTimerDialog(@NonNull TtsHost host, @NonNull ReaderTtsController controller) {
        this.host = host;
        this.activity = host.ttsHostActivity();
        this.controller = controller;
    }

    private com.readwide.manager.util.PrefsManager prefs() {
        return host.ttsHostPrefs();
    }

    /** Label for the row that opens this dialog (shows the current setting). */
    String rowLabel() {
        int minutes = prefs() != null ? prefs().getTtsSleepTimerMinutes() : 0;
        if (minutes <= 0) {
            return activity.getString(R.string.tts_sleep_timer) + ": "
                    + activity.getString(R.string.tts_sleep_timer_off);
        }
        return activity.getString(R.string.tts_sleep_timer_set, minutes);
    }

    void show(@Nullable Runnable onChanged) {
        if (prefs() == null) return;
        host.ttsHostDialogStyler().syncReaderDialogThemeSnapshot();
        final int bg = host.ttsHostDialogStyler().readerDialogBgColor();
        final int fg = host.ttsHostDialogStyler().readerDialogTextColor(bg);

        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Color.TRANSPARENT);

        TextView title = host.ttsHostDialogStyler().makeReaderDialogTitle(
                activity.getString(R.string.tts_sleep_timer), bg, fg);
        outer.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = host.ttsHostDpToPx(14);
        list.setPadding(pad, host.ttsHostDpToPx(4), pad, host.ttsHostDpToPx(8));

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
            TextView row = host.ttsHostDialogStyler().makeReaderActionRow(
                    activity.getString(labels[i]), fg);
            row.setGravity(Gravity.CENTER);
            row.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
            row.setOnClickListener(v -> {
                prefs().setTtsSleepTimerMinutes(minutes);
                controller.refreshSleepTimerFromPrefs();
                if (onChanged != null) onChanged.run();
                if (ref[0] != null) ref[0].dismiss();
            });
            list.addView(row);
        }

        TextView customRow = host.ttsHostDialogStyler().makeReaderActionRow(
                activity.getString(R.string.tts_sleep_timer_custom), fg);
        customRow.setGravity(Gravity.CENTER);
        customRow.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        customRow.setOnClickListener(v -> showCustom(() -> {
            if (onChanged != null) onChanged.run();
            if (ref[0] != null) ref[0].dismiss();
        }));
        list.addView(customRow);

        TextView finishToggle = host.ttsHostDialogStyler().makeReaderActionRow(
                finishSentenceRowLabel(), fg);
        finishToggle.setGravity(Gravity.CENTER);
        finishToggle.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        finishToggle.setOnClickListener(v -> {
            boolean next = !prefs().getTtsSleepTimerFinishSentence();
            prefs().setTtsSleepTimerFinishSentence(next);
            controller.refreshSleepTimerFromPrefs();
            finishToggle.setText(finishSentenceRowLabel());
        });
        list.addView(finishToggle);

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        host.ttsHostDialogStyler().constrainDialogScrollArea(scroll, list);
        scroll.addView(list);
        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        android.app.Dialog dialog = host.ttsHostDialogStyler().createNarrowPositionedReaderDialog(
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
        boolean on = prefs() != null && prefs().getTtsSleepTimerFinishSentence();
        return activity.getString(R.string.tts_sleep_timer_finish_sentence)
                + ": " + (on ? "ON" : "OFF");
    }

    private void showCustom(@Nullable Runnable onChanged) {
        if (prefs() == null) return;
        host.ttsHostDialogStyler().syncReaderDialogThemeSnapshot();
        final int bg = host.ttsHostDialogStyler().readerDialogBgColor();
        final int fg = host.ttsHostDialogStyler().readerDialogTextColor(bg);
        final int sub = host.ttsHostDialogStyler().readerDialogSubTextColor(bg);

        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Color.TRANSPARENT);

        TextView title = host.ttsHostDialogStyler().makeReaderDialogTitle(
                activity.getString(R.string.tts_sleep_timer_custom_minutes), bg, fg);
        outer.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = host.ttsHostDpToPx(16);
        body.setPadding(pad, host.ttsHostDpToPx(6), pad, host.ttsHostDpToPx(6));

        final android.widget.EditText input = new android.widget.EditText(activity);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setTextColor(fg);
        input.setHintTextColor(sub);
        input.setHint(R.string.tts_sleep_timer_custom_minutes);
        input.setGravity(Gravity.CENTER);
        input.setPadding(host.ttsHostDpToPx(14), host.ttsHostDpToPx(10),
                host.ttsHostDpToPx(14), host.ttsHostDpToPx(10));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setCornerRadius(host.ttsHostDpToPx(12));
        inputBg.setColor(host.ttsHostDialogStyler().readerDialogPanelColor());
        inputBg.setStroke(host.ttsHostDpToPx(1), sub);
        input.setBackground(inputBg);
        int current = prefs().getTtsSleepTimerMinutes();
        if (current > 0) input.setText(String.valueOf(current));
        body.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final android.app.Dialog[] ref = new android.app.Dialog[1];

        TextView okRow = host.ttsHostDialogStyler().makeReaderActionRow(
                activity.getString(android.R.string.ok), fg);
        okRow.setGravity(Gravity.CENTER);
        okRow.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        okRow.setOnClickListener(v -> {
            int minutes = 0;
            try {
                minutes = Integer.parseInt(input.getText().toString().trim());
            } catch (NumberFormatException ignored) { }
            minutes = Math.max(0, Math.min(600, minutes));
            prefs().setTtsSleepTimerMinutes(minutes);
            controller.refreshSleepTimerFromPrefs();
            if (onChanged != null) onChanged.run();
            if (ref[0] != null) ref[0].dismiss();
        });
        body.addView(okRow);

        TextView cancelRow = host.ttsHostDialogStyler().makeReaderActionRow(
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

        android.app.Dialog dialog = host.ttsHostDialogStyler().createNarrowPositionedReaderDialog(
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
