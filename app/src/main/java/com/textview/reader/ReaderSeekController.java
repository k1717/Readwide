package com.textview.reader;

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
        activity.seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private int pendingPage = 1;

            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser || suppressSeekCallback || activity.fileContent.isEmpty()) return;
                int pages = activity.getDisplayedTotalPageCount();
                pendingPage = Math.max(1, Math.min(pages, progress + 1));
                previewToolbarSeekPage(pendingPage, pages);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                if (activity.fileContent.isEmpty()) return;
                toolbarSeekTracking = true;
                pendingToolbarSeekJump = false;
                activity.handler.removeCallbacks(pendingToolbarSeekTimeoutRunnable);
                int pages = activity.getDisplayedTotalPageCount();
                pendingPage = Math.max(1, Math.min(pages, activity.getDisplayedCurrentPageNumber()));
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

        int pendingTotal = Math.max(1, Math.max(totalPages, pendingToolbarSeekTotalPages));
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
