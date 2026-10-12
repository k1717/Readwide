package com.readwide.manager;

import androidx.annotation.NonNull;

import com.readwide.manager.model.LargeTextLinePartitionResult;
import com.readwide.manager.util.LargeTextPageModelMath;
import com.readwide.manager.view.CustomReaderView;


final class ReaderPagePositionController {
    private final ReaderActivity activity;
    private PartitionRowOrigin partitionRowOrigin;

    /** One measured row origin for the exact page crossing the current window start. */
    private static final class PartitionRowOrigin {
        final String content, signature;
        final int windowBase, pageAnchor, rowAtWindowStart;

        PartitionRowOrigin(String content, String signature, int windowBase,
                           int pageAnchor, int rowAtWindowStart) {
            this.content = content;
            this.signature = signature;
            this.windowBase = windowBase;
            this.pageAnchor = pageAnchor;
            this.rowAtWindowStart = rowAtWindowStart;
        }
    }

    ReaderPagePositionController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void clearPartitionRowOrigin() {
        partitionRowOrigin = null;
    }

    /** Capture before replacing StaticLayout; never rebuild an off-screen layout for a label. */
    void preparePartitionRowOrigin(@NonNull LargeTextLinePartitionResult next) {
        PartitionRowOrigin measured = null;
        if (activity.largeTextEstimateActive && activity.readerView != null
                && activity.filePath != null && !next.content.isEmpty()
                // Markdown spans can depend on context outside the shared window.
                && !activity.readerView.isMarkdownHighlightingEnabledForIndex()) {
            String signature = activity.buildCurrentLargeTextExactPageIndexSignature(activity.filePath);
            int[] page = activity.largeTextExactPageIndexState.pageForCharIfUsable(
                    signature, next.baseCharOffset);
            CustomReaderView.PageTextAnchor anchor = page[0] > 0
                    ? activity.getExactLargeTextAnchorForPage(page[0]) : null;
            int localBase = next.baseCharOffset - activity.largeTextPreviewBaseCharOffset;
            String current = activity.fileContent;
            boolean sharedLineStart = current != null && localBase >= 0 && localBase < current.length()
                    && (localBase == 0 || current.charAt(localBase - 1) == '\n');
            if (sharedLineStart && anchor != null && anchor.charPosition < next.baseCharOffset) {
                int row = exactRowInLoadedWindow(anchor.charPosition, next.baseCharOffset, signature);
                if (row > 0) {
                    measured = new PartitionRowOrigin(next.content, signature, next.baseCharOffset,
                            anchor.charPosition, row);
                }
            }
        }
        partitionRowOrigin = measured;
    }

    int getLineOffsetWithinDisplayedPage(int page, int totalPages) {
        if (activity.readerView == null) return 1;
        int total = Math.max(1, totalPages);
        int target = Math.max(1, Math.min(total, page));
        if (target != Math.max(1, Math.min(total, getStatusCurrentPageNumber()))) return 1;
        int displayChar = activity.readerView.getDisplayTopCharPosition();
        if (activity.largeTextEstimateActive) {
            if (activity.largeTextPartitionSwitchState.isInProgress()) return 1;
            if (activity.isLargeTextExactPageIndexReady()) {
                CustomReaderView.PageTextAnchor anchor = activity.getExactLargeTextAnchorForPage(target);
                if (anchor == null) return 1;
                int localAnchor = anchor.charPosition - activity.largeTextPreviewBaseCharOffset;
                if (localAnchor >= 0) {
                    return Math.max(1, activity.readerView.getLineOffsetBetweenCharPositions(localAnchor, displayChar));
                }
                if (partitionRowOrigin == null || activity.filePath == null) return 1;
                // The carried seam origin and page label use the same display row.
                return Math.max(1, exactRowInLoadedWindow(anchor.charPosition, absoluteDisplayCharPosition(),
                        activity.buildCurrentLargeTextExactPageIndexSignature(activity.filePath)));
            }
        }
        int localPage = activity.readerView.getPageNumberForCharPosition(displayChar);
        return Math.max(1, activity.readerView.getLineOffsetBetweenCharPositions(
                activity.readerView.getPageStartCharPosition(localPage), displayChar));
    }

    private int exactRowInLoadedWindow(int anchor, int position, String signature) {
        String content = activity.fileContent;
        int base = activity.largeTextPreviewBaseCharOffset;
        int local = position - base;
        if (content == null || content.isEmpty() || local < 0 || local > content.length()) return 0;
        int localAnchor = anchor - base;
        if (localAnchor >= 0) {
            return activity.readerView.getLineOffsetBetweenCharPositions(localAnchor, local);
        }
        PartitionRowOrigin origin = partitionRowOrigin;
        if (origin == null || origin.content != content || origin.windowBase != base
                || origin.pageAnchor != anchor || !origin.signature.equals(signature)) return 0;
        int rows = activity.readerView.getLineOffsetBetweenCharPositions(0, local);
        return rows > 0 ? (int) Math.min(Integer.MAX_VALUE,
                (long) origin.rowAtWindowStart + rows - 1L) : 0;
    }

    void recomputeLargeTextDisplayPageOffset(int displayPage, int totalPages) {
        if (activity.readerView == null || !activity.largeTextEstimateActive) return;

        int localPage = Math.max(1, activity.readerView.getCurrentPageNumber());
        // A request may have been issued against the estimated model. Once
        // exact anchors exist, old page hints must not overwrite that model.
        int exactTotal = activity.getLargeTextExactPageCountIfReady();
        if (exactTotal > 0) {
            int exactPage = activity.findExactLargeTextPageForChar(activity.getCurrentCharPosition());
            activity.largeTextEstimatedTotalPages = exactTotal;
            activity.largeTextEstimatedBasePageOffset = Math.max(0, exactPage - localPage);
            return;
        }
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
        return currentPageNumber(false);
    }

    /** Shared status/new-save row choice; page navigation keeps its raw top-edge coordinates. */
    int getStatusCurrentPageNumber() {
        return currentPageNumber(true);
    }

    private int absoluteDisplayCharPosition() {
        int local = activity.readerView != null ? activity.readerView.getDisplayTopCharPosition() : 0;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L,
                (long) (activity.largeTextEstimateActive ? activity.largeTextPreviewBaseCharOffset : 0) + local));
    }

    private int currentPageNumber(boolean status) {
        int localPage = status && activity.readerView != null
                ? activity.readerView.getPageNumberForCharPosition(activity.readerView.getDisplayTopCharPosition())
                : getCurrentPageNumber();
        int total = getDisplayedTotalPageCount();
        boolean exactReady = activity.largeTextEstimateActive && activity.isLargeTextExactPageIndexReady();
        int exactPage = exactReady ? activity.findExactLargeTextPageForChar(
                status ? absoluteDisplayCharPosition() : activity.getCurrentCharPosition()) : 0;
        int pendingPage = activity.largeTextPartitionSwitchState.pendingDisplayPage();
        int destination = activity.largeTextPartitionSwitchState.destinationChar();
        if (exactReady && activity.largeTextPartitionSwitchState.isInProgress() && destination >= 0) {
            // Rebase labels, not the destination. An estimated target can lie
            // inside a newly indexed page and must keep that exact character.
            pendingPage = activity.findExactLargeTextPageForChar(destination);
        }
        if (status && activity.largeTextPartitionSwitchState.shouldPreserveVisiblePosition()) {
            // A background window replacement has no new reading destination.
            // Explicit queued taps revoke this flag and keep their target label.
            pendingPage = 0;
        }
        return LargeTextPageModelMath.displayedCurrentPage(
                activity.largeTextEstimateActive,
                localPage,
                total,
                activity.largeTextPartitionSwitchState.isInProgress(),
                pendingPage,
                isAtEndOfLargeTextDocument(),
                exactReady,
                exactPage,
                activity.largeTextEstimatedBasePageOffset);
    }

    void updatePositionLabel() {
        if (activity.readerView == null) return;
        int totalPages = getDisplayedTotalPageCount();
        int currentPage = getStatusCurrentPageNumber();

        if (activity.readerSeekController != null
                && activity.readerSeekController.showPendingPositionIfNeeded(currentPage, totalPages)) {
            return;
        }
        activity.readerSeek().syncPosition(currentPage, totalPages);
    }

    void setPageLabels(int currentPage, int totalPages) {
        totalPages = Math.max(1, totalPages);
        currentPage = Math.max(1, Math.min(totalPages, currentPage));
        boolean exactReady = activity.isLargeTextExactPageIndexReady();
        boolean checkingCache = activity.largeTextExactPageIndexState.isCheckingCache();
        boolean hasEstimate = activity.largeTextEstimatedTotalPages > 0;
        String totalText = LargeTextPageModelMath.pageNumberLabel(totalPages,
                activity.largeTextEstimateActive, exactReady, checkingCache, hasEstimate);
        String currentText = LargeTextPageModelMath.pageNumberLabel(currentPage,
                activity.largeTextEstimateActive, exactReady, checkingCache, hasEstimate);
        int lineOffset = activity.getLineOffsetWithinDisplayedPage(currentPage, totalPages);
        String text = activity.formatPageMoveLabel(
                currentText,
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
