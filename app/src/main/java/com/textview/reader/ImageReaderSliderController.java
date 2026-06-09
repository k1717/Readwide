package com.textview.reader;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.textview.reader.util.ImageSequenceNavigationMath;

import java.util.Locale;

final class ImageReaderSliderController {
    interface Listener {
        void onSliderTargetSelected(int targetIndex);
    }

    private final ImageReaderActivity activity;
    private final Listener listener;
    private LinearLayout sliderBar;
    private SeekBar imageSlider;
    private TextView imageSliderLabel;
    private int bottomInset;

    ImageReaderSliderController(@NonNull ImageReaderActivity activity, @NonNull Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    @NonNull
    View createView(int itemCount) {
        sliderBar = new LinearLayout(activity);
        sliderBar.setOrientation(LinearLayout.VERTICAL);
        sliderBar.setGravity(Gravity.CENTER);
        sliderBar.setBackgroundColor(Color.argb(210, 0, 0, 0));
        applyBottomInset(0);

        imageSliderLabel = new TextView(activity);
        imageSliderLabel.setTextColor(Color.WHITE);
        imageSliderLabel.setTextSize(13f);
        imageSliderLabel.setGravity(Gravity.CENTER);
        imageSliderLabel.setSingleLine(true);
        sliderBar.addView(imageSliderLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        imageSlider = new SeekBar(activity);
        imageSlider.setMax(Math.max(0, itemCount - 1));
        tintImageSlider(imageSlider);
        sliderBar.addView(imageSlider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(38)));
        imageSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) updateLabel(progress, imageSlider.getMax() + 1);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                listener.onSliderTargetSelected(seekBar.getProgress());
            }
        });
        update(0, itemCount, true);
        return sliderBar;
    }

    void applyBottomInset(int inset) {
        bottomInset = Math.max(0, inset);
        if (sliderBar == null) return;
        sliderBar.setPadding(
                activity.dpToPx(18),
                activity.dpToPx(8),
                activity.dpToPx(18),
                activity.dpToPx(10) + bottomInset);
    }

    void update(int currentIndex, int itemCount, boolean chromeVisible) {
        if (sliderBar == null || imageSlider == null || imageSliderLabel == null) return;
        boolean hasSequence = itemCount > 1;
        sliderBar.setVisibility(chromeVisible && hasSequence ? View.VISIBLE : View.GONE);
        imageSlider.setMax(Math.max(0, itemCount - 1));
        int safeIndex = ImageSequenceNavigationMath.clampIndex(currentIndex, itemCount);
        if (imageSlider.getProgress() != safeIndex) imageSlider.setProgress(safeIndex);
        updateLabel(safeIndex, itemCount);
    }

    int reservedViewportBottomInset(int itemCount) {
        if (itemCount <= 1) return bottomInset;
        return activity.dpToPx(82) + bottomInset;
    }

    private void updateLabel(int index, int itemCount) {
        if (imageSliderLabel == null) return;
        if (itemCount <= 1) {
            imageSliderLabel.setText("");
            return;
        }
        int safeIndex = ImageSequenceNavigationMath.clampIndex(index, itemCount);
        imageSliderLabel.setText(String.format(Locale.getDefault(), "%d / %d", safeIndex + 1, itemCount));
    }

    private void tintImageSlider(@NonNull SeekBar seekBar) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        int accent = Color.WHITE;
        int track = Color.argb(90, 255, 255, 255);
        seekBar.setProgressTintList(ColorStateList.valueOf(accent));
        seekBar.setThumbTintList(ColorStateList.valueOf(accent));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(track));
    }
}
