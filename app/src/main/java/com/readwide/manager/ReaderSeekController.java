package com.readwide.manager;

import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;

import androidx.annotation.NonNull;

final class ReaderSeekController {
    private final ReaderActivity activity;

    private boolean suppressSeekCallback = false;
    private boolean toolbarSeekTracking = false;
    private boolean pendingToolbarSeekJump = false;
    private int pendingToolbarSeekPage = -1;
    private int pendingToolbarSeekTotalPages = 1;
    private final Runnable pendingToolbarSeekTimeoutRunnable = this::clearStalePendingToolbarSeekJump;

    ReaderSeekController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void setupSeekBar() {
        if (activity.seekBar == null) return;
        activity.seekBar.setMax(0);
        activity.seekBar.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_MOVE
                    || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                view.post(() -> suppressSeekBarPressedEffect(view));
            }
            return false;
        });
        activity.seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private int pendingPage = 1;

            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser || suppressSeekCallback || activity.fileContent.isEmpty()) return;
                int pages = Math.max(1, sb.getMax() + 1);
                pendingPage = Math.max(1, Math.min(pages, progress + 1));
                previewToolbarSeekPage(pendingPage, pages);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                if (activity.fileContent.isEmpty()) return;
                toolbarSeekTracking = true;
                pendingToolbarSeekJump = false;
                activity.handler.removeCallbacks(pendingToolbarSeekTimeoutRunnable);
                int pages = Math.max(1, sb.getMax() + 1);
                pendingPage = Math.max(1, Math.min(pages, activity.getStatusCurrentPageNumber()));
                previewToolbarSeekPage(pendingPage, pages);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                toolbarSeekTracking = false;
                if (activity.fileContent.isEmpty()) {
                    clearPendingToolbarSeekJump();
                    return;
                }

                int pages = activity.getDisplayedTotalPageCount();
                pendingPage = Math.max(1, Math.min(pages, pendingPage));
                previewToolbarSeekPage(pendingPage, pages);

                boolean accepted = activity.scrollToPageNumber(pendingPage, true, true);
                if (accepted) {
                    beginPendingToolbarSeekJump(pendingPage, pages);
                } else {
                    clearPendingToolbarSeekJump();
                    activity.updatePositionLabel();
                }
            }
        });
    }

    private void suppressSeekBarPressedEffect(View view) {
        if (view == null) return;
        view.setPressed(false);
        view.jumpDrawablesToCurrentState();
    }

    void previewToolbarSeekPage(int page, int totalPages) {
        setPageAndSeekBar(page, totalPages);
        pendingToolbarSeekPage = Math.max(1, Math.min(Math.max(1, totalPages), page));
        pendingToolbarSeekTotalPages = Math.max(1, totalPages);
    }

    void beginPendingToolbarSeekJump(int page, int totalPages) {
        pendingToolbarSeekJump = true;
        previewToolbarSeekPage(page, totalPages);
        activity.handler.removeCallbacks(pendingToolbarSeekTimeoutRunnable);
        activity.handler.postDelayed(pendingToolbarSeekTimeoutRunnable, 10000L);
    }

    void clearPendingToolbarSeekJump() {
        toolbarSeekTracking = false;
        pendingToolbarSeekJump = false;
        pendingToolbarSeekPage = -1;
        pendingToolbarSeekTotalPages = 1;
        activity.handler.removeCallbacks(pendingToolbarSeekTimeoutRunnable);
    }

    boolean showPendingPositionIfNeeded(int currentPage, int totalPages) {
        if ((!toolbarSeekTracking && !pendingToolbarSeekJump) || pendingToolbarSeekPage <= 0) return false;

        if (!toolbarSeekTracking && activity.largeTextEstimateActive) {
            // After release, the partition request owns the destination (and
            // rebases its label when exact anchors arrive). A second numeric
            // overlay would otherwise show the old estimate for up to 10 s.
            clearPendingToolbarSeekJump();
            return false;
        }

        // Keep the touched scale stable until release. Exact indexing may
        // finish mid-drag, but must not move the thumb under the user's finger.
        int pendingTotal = Math.max(1, toolbarSeekTracking
                ? pendingToolbarSeekTotalPages : Math.max(totalPages, pendingToolbarSeekTotalPages));
        int pendingPage = Math.max(1, Math.min(pendingTotal, pendingToolbarSeekPage));
        if (!toolbarSeekTracking && currentPage == pendingPage) {
            clearPendingToolbarSeekJump();
            return false;
        }

        setPageAndSeekBar(pendingPage, pendingTotal);
        return true;
    }

    void syncPosition(int currentPage, int totalPages) {
        setPageAndSeekBar(currentPage, totalPages);
    }

    private void clearStalePendingToolbarSeekJump() {
        if (!pendingToolbarSeekJump) return;
        pendingToolbarSeekJump = false;
        pendingToolbarSeekPage = -1;
        pendingToolbarSeekTotalPages = 1;
        activity.updatePositionLabel();
    }

    private void setPageAndSeekBar(int page, int totalPages) {
        totalPages = Math.max(1, totalPages);
        page = Math.max(1, Math.min(totalPages, page));
        activity.setPageLabels(page, totalPages);
        if (activity.seekBar == null) return;
        suppressSeekCallback = true;
        activity.seekBar.setMax(Math.max(0, totalPages - 1));
        activity.seekBar.setProgress(Math.max(0, Math.min(totalPages - 1, page - 1)));
        suppressSeekCallback = false;
    }
}
