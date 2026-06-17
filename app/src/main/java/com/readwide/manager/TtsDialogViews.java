package com.readwide.manager;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * Stateless view-builder helpers for the TTS dialogs (rounded option panels and
 * percent sliders). Extracted from {@link ReaderTtsController} so the controller
 * keeps to playback logic. None of these touch TTS playback state — they only
 * build views from the activity's dialog styler.
 */
final class TtsDialogViews {
    private TtsDialogViews() {}

    interface PercentValueCallback {
        void onChanged(int value);
    }

    static LinearLayout makeOptionBox(@NonNull ReaderActivity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(activity.dpToPx(10), activity.dpToPx(10),
                activity.dpToPx(10), activity.dpToPx(2));
        box.setBackground(roundedPanelBackground(activity,
                activity.dialogStyler().readerDialogPanelColor(), 14));
        return box;
    }

    static void addPercentSlider(@NonNull ReaderActivity activity,
                                 LinearLayout parent,
                                 String title,
                                 int initialValue,
                                 @NonNull PercentValueCallback callback,
                                 int fg,
                                 int sub) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(activity.dpToPx(8), activity.dpToPx(8),
                activity.dpToPx(8), activity.dpToPx(6));
        box.setBackground(roundedPanelBackground(activity,
                activity.dialogStyler().readerDialogBgColor(), 10));

        TextView label = new TextView(activity);
        label.setText(percentLabel(title, initialValue));
        label.setTextColor(fg);
        label.setTextSize(13f);
        label.setGravity(Gravity.CENTER);
        label.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        label.setIncludeFontPadding(false);
        box.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(22)));

        SeekBar seek = new SeekBar(activity);
        seek.setMax(150);
        seek.setProgress(Math.max(50, Math.min(200, initialValue)) - 50);
        seek.setPadding(activity.dpToPx(16), 0, activity.dpToPx(16), 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = Math.max(50, Math.min(200, progress + 50));
                label.setText(percentLabel(title, value));
                if (fromUser) callback.onChanged(value);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                callback.onChanged(Math.max(50, Math.min(200, seekBar.getProgress() + 50)));
            }
        });
        box.addView(seek, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(34)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, activity.dpToPx(8));
        parent.addView(box, lp);
    }

    static String percentLabel(String title, int value) {
        return title + ": " + Math.max(50, Math.min(200, value)) + "%";
    }

    static GradientDrawable roundedPanelBackground(@NonNull ReaderActivity activity,
                                                   int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(activity.dpToPx(radiusDp));
        drawable.setStroke(0, Color.TRANSPARENT);
        return drawable;
    }
}
