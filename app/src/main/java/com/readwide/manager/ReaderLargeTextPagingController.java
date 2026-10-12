package com.readwide.manager;

import androidx.annotation.NonNull;

import com.readwide.manager.util.LargeTextContinuityMath;
import com.readwide.manager.util.LargeTextExactPageIndexState;

final class ReaderLargeTextPagingController {
    private final ReaderActivity activity;

    ReaderLargeTextPagingController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void pageBy(int direction, boolean fromAutoPageTurn) {
        if (!fromAutoPageTurn) {
            activity.stopAutoPageTurnForManualNavigation();
        }
        if (activity.readerView == null || direction == 0) return;

        // A tap owns a destination; an automatic buffer replacement does not.
        // Supersede it before normal tap handling instead of queueing taps that
        // could be discarded if the automatic read loses to the next gesture.
        if (activity.largeTextPartitionSwitchState.isViewportHandoff()) {
            activity.cancelLargeTextPartitionSwitchForLocalJump();
        }
        if (activity.largeTextEstimateActive && activity.largeTextPartitionSwitchState.isInProgress()) {
            queueLargeTextPageDeltaWhileSwitching(direction);
            activity.updatePositionLabel();
            return;
        }

        if (activity.largeTextEstimateActive && activity.isLargeTextExactPageIndexReady()) {
            activity.recordLargeTextPageDirection(direction);
            if (pageLargeTextByExactAnchorDelta(direction, false)) {
                return;
            }
        }

        if (activity.largeTextEstimateActive) {
            activity.recordLargeTextPageDirection(direction);
            if (pageLargeTextSequentiallyWithoutSkipping(direction)) {
                return;
            }
        }

        pageCurrentReaderView(direction);
    }

    void processQueuedLargeTextPageDeltaAfterPartitionApply() {
        if (!activity.largeTextEstimateActive
                || !activity.largeTextPartitionSwitchState.hasQueuedTarget()
                || activity.largeTextPartitionSwitchState.isInProgress()) return;
        int target = activity.largeTextPartitionSwitchState.consumeQueuedTarget();
        int total = activity.getLargeTextExactPageCountIfReady();
        int page = total > 0 ? activity.findExactLargeTextPageForChar(target) : 0;
        // The queued tap already selected a character. Do not replay numeric
        // deltas from a different landing (or a newly rebuilt page model).
        activity.jumpToAbsoluteCharPosition(target, page, total, null, null, false);
    }

    private void queueLargeTextPageDeltaWhileSwitching(int direction) {
        if (!activity.largeTextEstimateActive
                || !activity.isLargeTextExactPageIndexReady()
                || activity.filePath == null
                || direction == 0) return;
        int origin = activity.largeTextPartitionSwitchState.destinationChar();
        if (origin < 0) return;
        String signature = activity.buildCurrentLargeTextExactPageIndexSignature(activity.filePath);
        LargeTextExactPageIndexState.TapTarget target = activity.largeTextExactPageIndexState
                .tapTargetIfUsable(signature, origin, direction);
        if (target != null && target.anchor != null) {
            activity.largeTextPartitionSwitchState.queueTarget(
                    target.anchor.charPosition, target.page, target.total);
        }
    }

    private boolean pageLargeTextByExactAnchorDelta(int direction,
                                                    boolean showLoadingForAsyncPartitionJump) {
        if (!activity.largeTextEstimateActive
                || activity.readerView == null
                || activity.filePath == null
                || direction == 0) return false;

        String currentSignature = activity.buildCurrentLargeTextExactPageIndexSignature(activity.filePath);
        int currentAbs = Math.max(0, activity.getCurrentCharPosition());
        LargeTextExactPageIndexState.TapTarget target = activity.largeTextExactPageIndexState
                .tapTargetIfUsable(currentSignature, currentAbs, direction);
        if (target == null) return false;
        if (target.anchor == null) {
            activity.updatePositionLabel();
            return true;
        }
        int total = target.total;
        int targetPage = target.page;
        if (direction > 0 && targetPage >= total && total > 1) {
            return activity.jumpToFinalLargeTextPage(total, showLoadingForAsyncPartitionJump);
        }

        activity.jumpToAbsoluteCharPosition(target.anchor.charPosition, targetPage, total,
                null, null, showLoadingForAsyncPartitionJump);
        return true;
    }

    private boolean pageLargeTextSequentiallyWithoutSkipping(int direction) {
        if (!activity.largeTextEstimateActive || activity.readerView == null || direction == 0) {
            return false;
        }

        int displayedTotal = Math.max(1, activity.getDisplayedTotalPageCount());
        int displayedCurrent = Math.max(1,
                Math.min(displayedTotal, activity.getDisplayedCurrentPageNumber()));
        // Until exact anchors are ready these numbers are only display hints.
        // A clamped estimate is not proof of a physical document boundary.
        int displayedTarget = (int) Math.max(1L,
                Math.min(displayedTotal, (long) displayedCurrent + direction));

        if (direction > 0) {
            return pageLargeTextForwardSequentially(displayedTarget, displayedTotal);
        }
        return pageLargeTextBackwardSequentially(displayedTarget, displayedTotal);
    }

    private boolean pageLargeTextForwardSequentially(int displayedTarget, int displayedTotal) {
        // EOF can already be visible in lookahead, before the final canonical
        // body is loaded. Do not reload that tail or snap the viewport backward.
        if (activity.readerView.isDocumentEndFullyVisible()) {
            activity.updatePositionLabel();
            return true;
        }
        int contentLength = activity.fileContent != null ? activity.fileContent.length() : 0;
        int bodyEnd = activity.largeTextPartitionBodyCharCount > 0
                ? Math.min(contentLength, activity.largeTextPartitionBodyCharCount)
                : contentLength;
        int nextPageStartLocal = Math.max(0, Math.min(contentLength,
                activity.readerView.getCharPositionForNextPageStartRespectingOverlap()));
        boolean finalPartition = !activity.hasNextLargeTextPartition()
                && activity.largeTextPartitionEndLine >= activity.largeTextTotalLogicalLines;

        if (nextPageStartLocal < bodyEnd || finalPartition) {
            pageCurrentReaderView(+1);
            return true;
        }

        if (activity.hasNextLargeTextPartition()) {
            int targetAbs = LargeTextContinuityMath.forwardHandoffTargetAbs(
                    activity.largeTextPreviewBaseCharOffset,
                    nextPageStartLocal,
                    bodyEnd);
            activity.reloadLargeTextPreviewAround(
                    targetAbs, displayedTarget, displayedTotal, null, null, -1, false);
            return true;
        }

        pageCurrentReaderView(+1);
        return true;
    }

    private boolean pageLargeTextBackwardSequentially(int displayedTarget, int displayedTotal) {
        int localPage = Math.max(1, activity.readerView.getCurrentPageNumber());
        if (activity.readerView.isCurrentLinePastCurrentPageAnchor()) {
            pageCurrentReaderView(-1);
            return true;
        }

        if (localPage <= 1 && activity.hasPreviousLargeTextPartition()) {
            int previousStart = activity.getLargeTextPartitionStartLineForLine(
                    activity.largeTextPartitionStartLine - activity.getLargeTextPartitionLines());
            activity.reloadLargeTextPartitionByStartLine(previousStart, displayedTarget, displayedTotal);
            return true;
        }

        pageCurrentReaderView(-1);
        return true;
    }

    private void pageCurrentReaderView(int direction) {
        if (direction > 0) {
            activity.readerView.pageForwardWithoutSkippingContent();
        } else {
            activity.readerView.pageBackwardWithoutSkippingContent();
        }
        activity.readerView.post(() -> {
            activity.updatePositionLabel();
            if (activity.largeTextEstimateActive) {
                activity.prefetchNeighborLargeTextPartitions();
            }
        });
    }
}
