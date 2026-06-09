package com.textview.reader;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * Owns the TXT reader loading overlay. Keeping this out of ReaderActivity keeps
 * loading-window state separate from pagination/partition state while preserving
 * the same visual behavior.
 */
final class ReaderLoadingWindowController {
    private final ReaderActivity activity;

    ReaderLoadingWindowController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    private GradientDrawable loadingBoxBackground(int backgroundColor, int fgColor) {
        GradientDrawable drawable = new GradientDrawable();
        int panelColor = activity.dialogStyler().blendColors(backgroundColor, fgColor,
                activity.isDarkColor(backgroundColor) ? 0.08f : 0.05f);
        int borderColor = activity.dialogStyler().blendColors(backgroundColor, fgColor,
                activity.isDarkColor(backgroundColor) ? 0.24f : 0.18f);
        drawable.setColor(panelColor);
        drawable.setCornerRadius(activity.dpToPx(24));
        drawable.setStroke(activity.dpToPx(1), borderColor);
        return drawable;
    }

    void updateLoadingIndicatorColors(int backgroundColor) {
        int fg = UiColorUtils.readableTextColorForBackground(backgroundColor);

        if (activity.loadingBox != null) {
            activity.loadingBox.setBackground(loadingBoxBackground(backgroundColor, fg));
        }

        if (activity.progressText != null) {
            activity.progressText.setTextColor(fg);
            activity.progressText.setBackgroundColor(Color.TRANSPARENT);
        }

        if (activity.progressBar != null) {
            activity.progressBar.setBackgroundColor(Color.TRANSPARENT);
            activity.progressBar.setIndeterminateTintList(ColorStateList.valueOf(fg));
        }
    }

    void showLoadingWindow() {
        if (activity.readerRoot != null) {
            activity.readerRoot.setBackgroundColor(activity.currentReaderBackgroundColor);
        }
        if (activity.readerView != null) {
            activity.readerView.setBackgroundColor(activity.currentReaderBackgroundColor);
        }
        activity.getWindow().setStatusBarColor(activity.currentReaderBackgroundColor);
        updateLoadingIndicatorColors(activity.currentReaderBackgroundColor);
        if (activity.loadingBox != null) {
            activity.loadingBox.setVisibility(View.VISIBLE);
            activity.loadingBox.bringToFront();
        }
        if (activity.progressBar != null) {
            activity.progressBar.setVisibility(View.VISIBLE);
        }
        if (activity.progressText != null) {
            activity.progressText.setText(activity.getString(R.string.loading));
            activity.progressText.setVisibility(View.VISIBLE);
        }
    }

    void hideLoadingWindow() {
        activity.loadingWindowPartitionJumpGeneration = -1;
        if (activity.progressBar != null) {
            activity.progressBar.setVisibility(View.GONE);
        }
        if (activity.progressText != null) {
            activity.progressText.setVisibility(View.GONE);
        }
        if (activity.loadingBox != null) {
            activity.loadingBox.setVisibility(View.GONE);
        }
    }

    void showLoadingWindowForPartitionJump(int switchGeneration) {
        activity.loadingWindowPartitionJumpGeneration = switchGeneration;
        showLoadingWindow();
    }

    void hideLoadingWindowForPartitionJumpIfCurrent(boolean shouldHide, int switchGeneration) {
        if (!shouldHide) return;
        if (activity.loadingWindowPartitionJumpGeneration == switchGeneration) {
            hideLoadingWindow();
        }
    }
}
