package com.textview.reader;

import androidx.annotation.NonNull;

import com.textview.reader.util.LargeTextAnchorMath;
import com.textview.reader.util.LargeTextContinuityMath;
import com.textview.reader.view.CustomReaderView;

import java.util.ArrayList;

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

        if (activity.largeTextEstimateActive && tryLargeTextPartitionBoundaryFallback(direction)) {
            return;
        }

        pageCurrentReaderView(direction);
    }

    void processQueuedLargeTextPageDeltaAfterPartitionApply() {
        if (!activity.largeTextEstimateActive
                || !activity.largeTextPartitionSwitchState.hasQueuedDelta()
                || activity.largeTextPartitionSwitchState.isInProgress()) return;
        if (!activity.isLargeTextExactPageIndexReady()) {
            activity.clearLargeTextQueuedPageDelta();
            return;
        }
        int delta = activity.largeTextPartitionSwitchState.consumeQueuedDelta();
        if (!pageLargeTextByExactAnchorDelta(delta, false)) {
            int total = Math.max(1, activity.getDisplayedTotalPageCount());
            int current = Math.max(1, Math.min(total, activity.getDisplayedCurrentPageNumber()));
            int target = Math.max(1, Math.min(total, current + delta));
            if (target != current) {
                activity.scrollToPageNumber(target, false, false);
            }
        }
    }

    private void queueLargeTextPageDeltaWhileSwitching(int direction) {
        if (!activity.largeTextEstimateActive
                || !activity.isLargeTextExactPageIndexReady()
                || direction == 0) return;
        int total = Math.max(1, activity.getDisplayedTotalPageCount());
        activity.largeTextPartitionSwitchState.queuePageDelta(
                direction, total, activity.getDisplayedCurrentPageNumber());
    }

    private boolean pageLargeTextByExactAnchorDelta(int direction,
                                                    boolean showLoadingForAsyncPartitionJump) {
        if (!activity.largeTextEstimateActive
                || activity.readerView == null
                || activity.filePath == null
                || direction == 0) return false;

        String currentSignature = activity.buildCurrentLargeTextExactPageIndexSignature(activity.filePath);
        ArrayList<CustomReaderView.PageTextAnchor> anchors =
                activity.largeTextExactPageIndexState.copyAnchorsIfUsable(currentSignature);
        if (anchors == null || anchors.isEmpty()) return false;

        int total = Math.max(1, anchors.size());
        int currentAbs = Math.max(0, activity.getCurrentCharPosition());
        int targetIndex = LargeTextAnchorMath.findTapTargetAnchorIndex(anchors, currentAbs, direction);
        if (targetIndex < 0) {
            activity.updatePositionLabel();
            return true;
        }
        if (Math.abs(direction) > 1) {
            int extraSteps = Math.abs(direction) - 1;
            targetIndex += direction > 0 ? extraSteps : -extraSteps;
            targetIndex = Math.max(0, Math.min(anchors.size() - 1, targetIndex));
        }

        int targetPage = Math.max(1, Math.min(total, targetIndex + 1));
        if (direction > 0 && targetPage >= total && total > 1) {
            return activity.jumpToFinalLargeTextPage(total, showLoadingForAsyncPartitionJump);
        }

        CustomReaderView.PageTextAnchor targetAnchor = anchors.get(targetPage - 1);
        activity.jumpToAbsoluteCharPosition(targetAnchor.charPosition, targetPage, total,
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
        int displayedTarget = Math.max(1, Math.min(displayedTotal, displayedCurrent + direction));
        if (displayedTarget == displayedCurrent) {
            activity.updatePositionLabel();
            return true;
        }

        if (direction > 0) {
            return pageLargeTextForwardSequentially(displayedTarget, displayedTotal);
        }
        return pageLargeTextBackwardSequentially(displayedTarget, displayedTotal);
    }

    private boolean pageLargeTextForwardSequentially(int displayedTarget, int displayedTotal) {
        int contentLength = activity.fileContent != null ? activity.fileContent.length() : 0;
        int bodyEnd = activity.largeTextPartitionBodyCharCount > 0
                ? Math.min(contentLength, activity.largeTextPartitionBodyCharCount)
                : contentLength;
        int nextPageStartLocal = Math.max(0, Math.min(contentLength,
                activity.readerView.getCharPositionForNextPageStartRespectingOverlap()));
        boolean finalPartition = !activity.hasNextLargeTextPartition()
                && activity.largeTextPartitionEndLine >= activity.largeTextTotalLogicalLines;

        if (finalPartition && displayedTarget >= displayedTotal) {
            return activity.jumpToFinalLargeTextPage(displayedTotal, false);
        }

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

    private boolean tryLargeTextPartitionBoundaryFallback(int direction) {
        int localPage = Math.max(1, activity.readerView.getCurrentPageNumber());
        int displayedTotal = activity.getDisplayedTotalPageCount();
        int displayedCurrent = activity.getDisplayedCurrentPageNumber();
        if (direction > 0 && activity.hasNextLargeTextPartition()) {
            int contentLength = activity.fileContent != null ? activity.fileContent.length() : 0;
            int bodyEnd = activity.largeTextPartitionBodyCharCount > 0
                    ? Math.min(contentLength, activity.largeTextPartitionBodyCharCount)
                    : contentLength;
            int nextPageStartLocal = Math.max(0, Math.min(contentLength,
                    activity.readerView.getCharPositionForNextPageStartRespectingOverlap()));
            if (nextPageStartLocal >= bodyEnd) {
                int targetAbs = LargeTextContinuityMath.forwardHandoffTargetAbs(
                        activity.largeTextPreviewBaseCharOffset,
                        nextPageStartLocal,
                        bodyEnd);
                int displayedTarget = Math.max(1, Math.min(displayedTotal, displayedCurrent + 1));
                activity.reloadLargeTextPreviewAround(
                        targetAbs, displayedTarget, displayedTotal, null, null, -1, false);
                return true;
            }
        }
        if (direction < 0 && localPage <= 1 && activity.hasPreviousLargeTextPartition()) {
            int previousStart = activity.getLargeTextPartitionStartLineForLine(
                    activity.largeTextPartitionStartLine - activity.getLargeTextPartitionLines());
            int displayedTarget = Math.max(1, Math.min(displayedTotal, displayedCurrent - 1));
            activity.reloadLargeTextPartitionByStartLine(previousStart, displayedTarget, displayedTotal);
            return true;
        }

        if (direction > 0
                && !activity.hasNextLargeTextPartition()
                && activity.largeTextPartitionEndLine >= activity.largeTextTotalLogicalLines
                && displayedCurrent < displayedTotal) {
            return activity.jumpToFinalLargeTextPage(displayedTotal, false);
        }
        return false;
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
