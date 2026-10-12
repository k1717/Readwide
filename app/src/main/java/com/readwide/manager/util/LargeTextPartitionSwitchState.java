package com.readwide.manager.util;

/**
 * Runtime state for an in-flight large-TXT partition switch.
 *
 * Rapid page taps can arrive while the next partition is still loading.
 * Page numbers are presentation hints; the destination is an absolute character
 * and remains valid when an estimated page model becomes exact.
 */
public final class LargeTextPartitionSwitchState {
    private boolean inProgress;
    private int pendingDisplayPage;
    private int pendingTotalPages;
    private int pendingTargetChar = -1;
    private int queuedTargetChar = -1;
    private boolean viewportHandoff;

    public boolean isInProgress() {
        return inProgress;
    }

    public int pendingDisplayPage() {
        return pendingDisplayPage;
    }

    public int pendingTotalPages() {
        return pendingTotalPages;
    }

    public boolean hasQueuedTarget() {
        return queuedTargetChar >= 0;
    }

    public int destinationChar() {
        return hasQueuedTarget() ? queuedTargetChar : pendingTargetChar;
    }

    /** Relocation of the disk-read target must not overwrite a newer page tap. */
    public void setPendingTargetChar(int position) {
        if (inProgress) pendingTargetChar = Math.max(0, position);
    }

    /** Automatic window replacement keeps the visible position, not a page-start jump. */
    public void markViewportHandoff() {
        if (inProgress) viewportHandoff = true;
    }

    public boolean shouldPreserveVisiblePosition() {
        return inProgress && viewportHandoff && !hasQueuedTarget();
    }

    public boolean isViewportHandoff() {
        return inProgress && viewportHandoff;
    }

    public void begin(int displayPage, int totalPages, int fallbackDisplayPage, int fallbackTotalPages) {
        // A new request replaces both the original read and any queued taps.
        clearQueuedTarget();
        pendingTargetChar = -1;
        viewportHandoff = false;
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
        pendingTargetChar = -1;
        viewportHandoff = false;
    }

    public void clearQueuedTarget() {
        queuedTargetChar = -1;
    }

    public void reset() {
        clearPending();
        clearQueuedTarget();
    }

    public void queueTarget(int charPosition, int page, int totalPages) {
        if (!inProgress || charPosition < 0) return;
        int total = Math.max(1, totalPages);
        queuedTargetChar = charPosition;
        pendingTotalPages = total;
        pendingDisplayPage = LargeTextPageModelMath.clampPage(page, total);
    }

    public int consumeQueuedTarget() {
        int target = queuedTargetChar;
        clearQueuedTarget();
        return target;
    }
}
