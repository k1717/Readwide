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

    static LinearLayout makeOptionBox(@NonNull TtsHost host) {
        androidx.appcompat.app.AppCompatActivity activity = host.ttsHostActivity();
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(host.ttsHostDpToPx(10), host.ttsHostDpToPx(10),
                host.ttsHostDpToPx(10), host.ttsHostDpToPx(2));
        box.setBackground(roundedPanelBackground(host,
                host.ttsHostDialogStyler().readerDialogPanelColor(), 14));
        return box;
    }

    static void addPercentSlider(@NonNull TtsHost host,
                                 LinearLayout parent,
                                 String title,
                                 int initialValue,
                                 @NonNull PercentValueCallback callback,
                                 int fg,
                                 int sub) {
        androidx.appcompat.app.AppCompatActivity activity = host.ttsHostActivity();
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(host.ttsHostDpToPx(8), host.ttsHostDpToPx(8),
                host.ttsHostDpToPx(8), host.ttsHostDpToPx(6));
        box.setBackground(roundedPanelBackground(host,
                host.ttsHostDialogStyler().readerDialogBgColor(), 10));

        TextView label = new TextView(activity);
        label.setText(percentLabel(title, initialValue));
        label.setTextColor(fg);
        label.setTextSize(13f);
        label.setGravity(Gravity.CENTER);
        label.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        label.setIncludeFontPadding(false);
        box.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                host.ttsHostDpToPx(22)));

        SeekBar seek = new SeekBar(activity);
        seek.setMax(150);
        seek.setProgress(Math.max(50, Math.min(200, initialValue)) - 50);
        seek.setPadding(host.ttsHostDpToPx(16), 0, host.ttsHostDpToPx(16), 0);
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
                host.ttsHostDpToPx(34)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, host.ttsHostDpToPx(8));
        parent.addView(box, lp);
    }

    static String percentLabel(String title, int value) {
        return title + ": " + Math.max(50, Math.min(200, value)) + "%";
    }

    static GradientDrawable roundedPanelBackground(@NonNull TtsHost host,
                                                   int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(host.ttsHostDpToPx(radiusDp));
        drawable.setStroke(0, Color.TRANSPARENT);
        return drawable;
    }
}
