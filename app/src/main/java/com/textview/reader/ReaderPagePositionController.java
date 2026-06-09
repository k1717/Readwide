package com.textview.reader;

import androidx.annotation.NonNull;

import com.textview.reader.util.LargeTextPageModelMath;


final class ReaderPagePositionController {
    private final ReaderActivity activity;

    ReaderPagePositionController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void recomputeLargeTextDisplayPageOffset(int displayPage, int totalPages) {
        if (activity.readerView == null || !activity.largeTextEstimateActive) return;

        int localPage = Math.max(1, activity.readerView.getCurrentPageNumber());
        if (displayPage > 0 || totalPages > 0) {
            LargeTextPageModelMath.OffsetState state = LargeTextPageModelMath.preserveKnownTarget(
                    localPage,
                    displayPage,
                    totalPages,
                    activity.largeTextEstimatedTotalPages,
                    getDisplayedTotalPageCount(),
                    activity.largeTextEstimatedBasePageOffset);
            activity.largeTextEstimatedTotalPages = state.totalPages;
            activity.largeTextEstimatedBasePageOffset = state.basePageOffset;
            return;
        }

        int bodyPages = activity.getLastLocalPageStartingInsideLargeTextPartition();
        int exactTotal = activity.getLargeTextExactPageCountIfReady();
        LargeTextPageModelMath.OffsetState state = LargeTextPageModelMath.recomputePartitionOffset(
                activity.largeTextEstimatedTotalPages,
                bodyPages,
                exactTotal,
                activity.largeTextTotalLogicalLines,
                activity.getLargeTextPartitionLines(),
                activity.largeTextPartitionStartLine);
        activity.largeTextEstimatedTotalPages = state.totalPages;
        activity.largeTextEstimatedBasePageOffset = state.basePageOffset;
    }

    int getTotalPageCount() {
        return activity.readerView != null ? activity.readerView.getTotalPageCount() : 1;
    }

    int getDisplayedTotalPageCount() {
        int exactPageCount = activity.largeTextEstimateActive
                ? activity.getLargeTextExactPageCountIfReady()
                : 0;
        return LargeTextPageModelMath.displayedTotalPages(
                activity.largeTextEstimateActive,
                activity.largeTextPartitionSwitchState.isInProgress(),
                activity.largeTextPartitionSwitchState.pendingTotalPages(),
                exactPageCount > 0,
                exactPageCount,
                activity.largeTextEstimatedTotalPages,
                getTotalPageCount());
    }

    int getCurrentPageNumber() {
        return activity.readerView != null ? activity.readerView.getCurrentPageNumber() : 1;
    }

    int getDisplayedCurrentPageNumber() {
        int localPage = getCurrentPageNumber();
        int total = getDisplayedTotalPageCount();
        boolean exactReady = activity.largeTextEstimateActive && activity.isLargeTextExactPageIndexReady();
        int exactPage = exactReady ? activity.findExactLargeTextPageForChar(activity.getCurrentCharPosition()) : 0;
        return LargeTextPageModelMath.displayedCurrentPage(
                activity.largeTextEstimateActive,
                localPage,
                total,
                activity.largeTextPartitionSwitchState.isInProgress(),
                activity.largeTextPartitionSwitchState.pendingDisplayPage(),
                isAtEndOfLargeTextDocument(),
                exactReady,
                exactPage,
                activity.largeTextEstimatedBasePageOffset);
    }

    void updatePositionLabel() {
        if (activity.readerView == null) return;
        int totalPages = getDisplayedTotalPageCount();
        int currentPage = getDisplayedCurrentPageNumber();

        if (activity.readerSeekController != null
                && activity.readerSeekController.showPendingPositionIfNeeded(currentPage, totalPages)) {
            return;
        }
        activity.readerSeek().syncPosition(currentPage, totalPages);
    }

    void setPageLabels(int currentPage, int totalPages) {
        totalPages = Math.max(1, totalPages);
        currentPage = Math.max(1, Math.min(totalPages, currentPage));
        String totalText = (activity.largeTextEstimateActive
                && !activity.isLargeTextExactPageIndexReady()
                && activity.largeTextEstimatedTotalPages > 0)
                ? "~" + totalPages
                : String.valueOf(totalPages);
        int lineOffset = activity.getLineOffsetWithinDisplayedPage(currentPage, totalPages);
        String text = activity.formatPageMoveLabel(
                currentPage,
                totalText,
                Math.max(1, lineOffset));
        if (activity.positionLabel != null) activity.positionLabel.setText(text);
        if (activity.readerPageStatus != null) activity.readerPageStatus.setText(text);
    }

    void scrollToPercent(float percent) {
        if (activity.readerView != null) activity.readerView.scrollToPercent(percent);
    }

    void scrollToCharPosition(int charPosition) {
        if (activity.readerView != null) {
            activity.readerView.scrollToCharPosition(toLocalCharPosition(charPosition));
            activity.readerView.post(activity::updatePositionLabel);
        }
    }

    void scrollToSearchResultPosition(int charPosition) {
        if (activity.readerView != null) {
            activity.readerView.scrollToSearchResultPosition(toLocalCharPosition(charPosition));
            activity.readerView.post(activity::updatePositionLabel);
        }
    }

    boolean isActiveSearchTarget(int absolutePosition) {
        if (activity.activeSearchQuery == null
                || activity.activeSearchQuery.isEmpty()
                || activity.activeSearchIndex < 0) {
            return false;
        }
        int tolerance = Math.max(1, activity.activeSearchQuery.length());
        return Math.abs(activity.activeSearchIndex - absolutePosition) <= tolerance;
    }

    private boolean isAtEndOfLargeTextDocument() {
        return activity.largeTextEstimateActive
                && activity.largeTextTotalLogicalLines > 0
                && activity.largeTextPartitionEndLine >= activity.largeTextTotalLogicalLines
                && activity.readerView != null
                && activity.readerView.isAtVisualEndOfText();
    }

    private int toLocalCharPosition(int charPosition) {
        int localPosition = activity.largeTextEstimateActive
                ? charPosition - activity.largeTextPreviewBaseCharOffset
                : charPosition;
        return Math.max(0, Math.min(
                activity.fileContent != null ? activity.fileContent.length() : 0,
                localPosition));
    }
}
