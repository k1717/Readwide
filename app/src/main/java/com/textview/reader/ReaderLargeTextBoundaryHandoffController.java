package com.textview.reader;

import androidx.annotation.NonNull;

import com.textview.reader.model.LargeTextLinePartitionResult;
import com.textview.reader.util.LargeTextContinuityMath;

final class ReaderLargeTextBoundaryHandoffController {
    private static final long HANDOFF_DELAY_MS = 140L;

    private final ReaderActivity activity;
    private boolean overscrollSwitchQueued = false;

    ReaderLargeTextBoundaryHandoffController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void scheduleManualScrollBoundaryHandoff() {
        if (!activity.largeTextEstimateActive
                || activity.readerView == null
                || activity.largeTextPartitionSwitchState.isInProgress()) {
            activity.handler.removeCallbacks(activity.largeTextManualScrollBoundaryHandoffRunnable);
            return;
        }
        activity.handler.removeCallbacks(activity.largeTextManualScrollBoundaryHandoffRunnable);
        activity.handler.postDelayed(activity.largeTextManualScrollBoundaryHandoffRunnable, HANDOFF_DELAY_MS);
    }

    void handleManualScrollBoundaryHandoff() {
        if (!canHandleBoundaryHandoff()) {
            return;
        }

        if (activity.readerView.isUserDraggingOrFlinging()) {
            scheduleManualScrollBoundaryHandoff();
            return;
        }

        int contentLength = activity.fileContent.length();
        int bodyEnd = activity.largeTextPartitionBodyCharCount > 0
                ? Math.min(contentLength, activity.largeTextPartitionBodyCharCount)
                : contentLength;
        if (contentLength <= 0
                || bodyEnd <= 0
                || bodyEnd >= contentLength
                || !activity.hasNextLargeTextPartition()) {
            return;
        }

        int currentLocalStart = Math.max(0, Math.min(contentLength,
                activity.readerView.getCurrentPageStartCharPositionForCoverage()));
        int visibleEndLocal = Math.max(currentLocalStart, Math.min(contentLength,
                activity.readerView.getCharPositionAfterCurrentVisibleContent()));

        int nextStartLine = activity.getLargeTextPartitionStartLineForLine(
                activity.largeTextPartitionEndLine + 1);
        LargeTextLinePartitionResult nextCached =
                activity.getCachedLargeTextManualHandoffPartitionByStartLine(nextStartLine);

        if (currentLocalStart < bodyEnd && visibleEndLocal > bodyEnd) {
            handleEarlyLookaheadHandoff(currentLocalStart, nextStartLine, nextCached);
            return;
        }

        if (currentLocalStart >= bodyEnd) {
            handoffToNextPartition(currentLocalStart, bodyEnd, nextStartLine);
        }
    }

    void handleManualOverscroll(int direction) {
        if (!activity.largeTextEstimateActive
                || activity.readerView == null
                || activity.filePath == null
                || activity.largeTextPartitionSwitchState.isInProgress()
                || activity.activityDestroyed
                || direction >= 0
                || !activity.hasPreviousLargeTextPartition()
                || overscrollSwitchQueued) {
            return;
        }

        overscrollSwitchQueued = true;
        activity.handler.post(() -> {
            overscrollSwitchQueued = false;
            if (!activity.largeTextEstimateActive
                    || activity.readerView == null
                    || activity.filePath == null
                    || activity.largeTextPartitionSwitchState.isInProgress()
                    || activity.activityDestroyed
                    || !activity.hasPreviousLargeTextPartition()) {
                return;
            }
            int total = Math.max(1, activity.getDisplayedTotalPageCount());
            int displayedTarget = Math.max(1,
                    Math.min(total, activity.getDisplayedCurrentPageNumber() - 1));
            int previousStart = activity.getLargeTextPartitionStartLineForLine(
                    activity.largeTextPartitionStartLine - activity.getLargeTextPartitionLines());
            activity.recordLargeTextPageDirection(-1);
            activity.reloadLargeTextPartitionByStartLine(previousStart, displayedTarget, total);
        });
    }

    private boolean canHandleBoundaryHandoff() {
        return activity.largeTextEstimateActive
                && activity.readerView != null
                && activity.filePath != null
                && activity.fileContent != null
                && !activity.largeTextPartitionSwitchState.isInProgress()
                && !activity.activityDestroyed;
    }

    private void handleEarlyLookaheadHandoff(int currentLocalStart,
                                             int nextStartLine,
                                             LargeTextLinePartitionResult nextCached) {
        if (nextCached == null) {
            activity.prefetchLargeTextManualHandoffPartitionByStartLine(nextStartLine);
            return;
        }
        int currentAbsTop = activity.largeTextPreviewBaseCharOffset + currentLocalStart;
        int nextWindowStart = nextCached.baseCharOffset;
        int nextWindowEnd = nextWindowStart + Math.max(0, nextCached.content.length());
        if (currentAbsTop >= nextWindowStart && currentAbsTop < nextWindowEnd) {
            int total = Math.max(1, activity.getDisplayedTotalPageCount());
            int displayPage = activity.isLargeTextExactPageIndexReady()
                    ? Math.max(1, Math.min(total, activity.findExactLargeTextPageForChar(currentAbsTop)))
                    : Math.max(1, Math.min(total, activity.getDisplayedCurrentPageNumber()));
            activity.recordLargeTextPageDirection(+1);
            activity.switchLargeTextPartitionInPlace(
                    currentAbsTop, displayPage, total, null, null, nextStartLine, false, true);
        }
    }

    private void handoffToNextPartition(int currentLocalStart, int bodyEnd, int nextStartLine) {
        int targetAbs = LargeTextContinuityMath.clampAbsolutePositionToKnownText(
                LargeTextContinuityMath.forwardHandoffTargetAbs(
                        activity.largeTextPreviewBaseCharOffset,
                        currentLocalStart,
                        bodyEnd),
                activity.largeTextEstimatedTotalChars);

        int total = Math.max(1, activity.getDisplayedTotalPageCount());
        int displayPage = activity.isLargeTextExactPageIndexReady()
                ? Math.max(1, Math.min(total, activity.findExactLargeTextPageForChar(targetAbs)))
                : Math.max(1, Math.min(total, activity.getDisplayedCurrentPageNumber()));
        activity.recordLargeTextPageDirection(+1);
        activity.switchLargeTextPartitionInPlace(
                targetAbs, displayPage, total, null, null, nextStartLine, false, false);
    }
}
