package com.textview.reader.util;

/**
 * Runtime state for an in-flight large-TXT partition switch.
 *
 * Rapid page taps can arrive while the next partition is still loading. This
 * object keeps the visible pending page and the queued page delta together so
 * the reader does not derive them from separate Activity fields.
 */
public final class LargeTextPartitionSwitchState {
    private boolean inProgress;
    private int pendingDisplayPage;
    private int pendingTotalPages;
    private int queuedPageDelta;

    public boolean isInProgress() {
        return inProgress;
    }

    public int pendingDisplayPage() {
        return pendingDisplayPage;
    }

    public int pendingTotalPages() {
        return pendingTotalPages;
    }

    public boolean hasQueuedDelta() {
        return queuedPageDelta != 0;
    }

    public void begin(int displayPage, int totalPages, int fallbackDisplayPage, int fallbackTotalPages) {
        int stableTotal = totalPages > 0 ? totalPages : fallbackTotalPages;
        stableTotal = Math.max(1, stableTotal);
        int stablePage = displayPage > 0 ? displayPage : fallbackDisplayPage;
        inProgress = true;
        pendingTotalPages = stableTotal;
        pendingDisplayPage = LargeTextPageModelMath.clampPage(stablePage, stableTotal);
    }

    public void clearPending() {
        inProgress = false;
        pendingDisplayPage = 0;
        pendingTotalPages = 0;
    }

    public void clearQueuedDelta() {
        queuedPageDelta = 0;
    }

    public void reset() {
        clearPending();
        clearQueuedDelta();
    }

    public void queuePageDelta(int direction, int totalPages, int fallbackCurrentPage) {
        if (direction == 0) return;
        int total = Math.max(1, totalPages);
        int pendingPage = pendingDisplayPage > 0 ? pendingDisplayPage : fallbackCurrentPage;
        pendingPage = LargeTextPageModelMath.clampPage(pendingPage, total);
        int target = LargeTextPageModelMath.clampPage(pendingPage + direction, total);
        queuedPageDelta += target - pendingPage;
        pendingTotalPages = total;
        pendingDisplayPage = target;
    }

    public int consumeQueuedDelta() {
        int delta = queuedPageDelta;
        queuedPageDelta = 0;
        return delta;
    }
}
