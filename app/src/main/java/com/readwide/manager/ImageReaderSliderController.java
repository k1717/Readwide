package com.readwide.manager;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.readwide.manager.util.ImageSequenceNavigationMath;
import com.readwide.manager.util.PrefsManager;

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
    private int leftInset;
    private int rightInset;
    private int bottomInset;
    private int sliderDirection = PrefsManager.IMAGE_SLIDER_DIRECTION_LTR;

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
        // Consume taps that land on the bar (outside the seek thumb) so they do not
        // fall through to the image view behind it and page the sequence. The image
        // view now extends under this bar (its bottom strip is padding, not a
        // margin); when the bar is hidden it is GONE and those taps reach the image.
        sliderBar.setClickable(true);
        applySystemInsets(0, 0, 0);

        imageSliderLabel = new TextView(activity);
        imageSliderLabel.setTextColor(Color.WHITE);
        imageSliderLabel.setTextSize(13f);
        imageSliderLabel.setGravity(Gravity.CENTER);
        imageSliderLabel.setSingleLine(true);
        imageSliderLabel.setTranslationY(activity.dpToPx(2));
        sliderBar.addView(imageSliderLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        imageSlider = new SeekBar(activity);
        imageSlider.setMax(Math.max(0, itemCount - 1));
        tintImageSlider(imageSlider);
        applySliderLayoutDirection();
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(38));
        // Pull the slider up toward the page-count label so the gap between them is
        // tighter (the seek bar reserves empty vertical space above its thumb).
        sliderLp.topMargin = -activity.dpToPx(6);
        sliderBar.addView(imageSlider, sliderLp);
        imageSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) updateLabel(progressToIndex(progress, imageSlider.getMax() + 1), imageSlider.getMax() + 1);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                listener.onSliderTargetSelected(progressToIndex(seekBar.getProgress(), seekBar.getMax() + 1));
            }
        });
        update(0, itemCount, true);
        return sliderBar;
    }

    void applySystemInsets(int left, int right, int bottom) {
        leftInset = Math.max(0, left);
        rightInset = Math.max(0, right);
        bottomInset = Math.max(0, bottom);
        if (sliderBar == null) return;
        sliderBar.setPadding(
                activity.dpToPx(24) + leftInset,
                activity.dpToPx(8),
                activity.dpToPx(24) + rightInset,
                activity.dpToPx(10) + bottomInset);
    }

    void setSliderDirection(int direction) {
        sliderDirection = direction == PrefsManager.IMAGE_SLIDER_DIRECTION_RTL
                ? PrefsManager.IMAGE_SLIDER_DIRECTION_RTL
                : PrefsManager.IMAGE_SLIDER_DIRECTION_LTR;
        applySliderLayoutDirection();
    }

    void update(int currentIndex, int itemCount, boolean chromeVisible) {
        if (sliderBar == null || imageSlider == null || imageSliderLabel == null) return;
        boolean hasSequence = itemCount > 1;
        sliderBar.setVisibility(chromeVisible && hasSequence ? View.VISIBLE : View.GONE);
        imageSlider.setMax(Math.max(0, itemCount - 1));
        int safeIndex = ImageSequenceNavigationMath.clampIndex(currentIndex, itemCount);
        int visualProgress = indexToProgress(safeIndex, itemCount);
        if (imageSlider.getProgress() != visualProgress) imageSlider.setProgress(visualProgress);
        updateLabel(safeIndex, itemCount);
    }

    private int progressToIndex(int progress, int itemCount) {
        return ImageSequenceNavigationMath.clampIndex(progress, itemCount);
    }

    private int indexToProgress(int index, int itemCount) {
        return ImageSequenceNavigationMath.clampIndex(index, itemCount);
    }

    private void applySliderLayoutDirection() {
        if (imageSlider == null) return;
        imageSlider.setLayoutDirection(sliderDirection == PrefsManager.IMAGE_SLIDER_DIRECTION_RTL
                ? View.LAYOUT_DIRECTION_RTL
                : View.LAYOUT_DIRECTION_LTR);
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
