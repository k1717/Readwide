package com.readwide.manager;

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
    private static final long PARTITION_JUMP_LOADING_DELAY_MS = 150L;

    private final ReaderActivity activity;
    private Runnable pendingPartitionJumpShow;

    ReaderLoadingWindowController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    private GradientDrawable loadingBoxBackground(int backgroundColor, int fgColor) {
        return LoadingWindowTheme.boxDrawable(activity, LoadingWindowTheme.reader(backgroundColor, fgColor));
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
        // Defer the overlay briefly so fast or cached partition switches - the
        // common case when stepping through search results across partitions -
        // finish without flashing a full-screen loading window. Only genuinely
        // slow loads keep the switch pending past the delay and show it.
        if (pendingPartitionJumpShow != null) {
            activity.handler.removeCallbacks(pendingPartitionJumpShow);
        }
        pendingPartitionJumpShow = () -> {
            if (!activity.activityDestroyed
                    && activity.loadingWindowPartitionJumpGeneration == switchGeneration) {
                showLoadingWindow();
            }
        };
        activity.handler.postDelayed(pendingPartitionJumpShow, PARTITION_JUMP_LOADING_DELAY_MS);
    }

    void hideLoadingWindowForPartitionJumpIfCurrent(boolean shouldHide, int switchGeneration) {
        if (!shouldHide) return;
        if (activity.loadingWindowPartitionJumpGeneration == switchGeneration) {
            if (pendingPartitionJumpShow != null) {
                activity.handler.removeCallbacks(pendingPartitionJumpShow);
                pendingPartitionJumpShow = null;
            }
            hideLoadingWindow();
        }
    }
}
