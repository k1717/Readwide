package com.textview.reader.util;

/**
 * Pure page-number arithmetic for large TXT display state.
 *
 * The reader has several navigation entry points: tap paging, toolbar slider,
 * go-to-page, partition handoff, and bookmark restore. Keeping the page/total
 * selection math here makes those paths share one final page model instead of
 * each re-deriving totals from transient partition-local state.
 */
public final class LargeTextPageModelMath {
    private LargeTextPageModelMath() {}

    public static final class OffsetState {
        public final int totalPages;
        public final int basePageOffset;

        public OffsetState(int totalPages, int basePageOffset) {
            this.totalPages = Math.max(1, totalPages);
            this.basePageOffset = Math.max(0, basePageOffset);
        }
    }

    public static int displayedTotalPages(boolean largeTextActive,
                                          boolean partitionSwitchInProgress,
                                          int pendingTotalPages,
                                          boolean exactIndexReady,
                                          int exactAnchorCount,
                                          int estimatedTotalPages,
                                          int localTotalPages) {
        if (largeTextActive) {
            if (partitionSwitchInProgress && pendingTotalPages > 0) {
                return Math.max(1, pendingTotalPages);
            }
            if (exactIndexReady && exactAnchorCount > 0) {
                return Math.max(1, exactAnchorCount);
            }
            if (estimatedTotalPages > 0) {
                return Math.max(1, estimatedTotalPages);
            }
        }
        return Math.max(1, localTotalPages);
    }

    public static int displayedCurrentPage(boolean largeTextActive,
                                           int localPage,
                                           int displayedTotalPages,
                                           boolean partitionSwitchInProgress,
                                           int pendingDisplayPage,
                                           boolean atDocumentEnd,
                                           boolean exactIndexReady,
                                           int exactPageForCurrentChar,
                                           int estimatedBasePageOffset) {
        int stableLocalPage = Math.max(1, localPage);
        if (!largeTextActive) return stableLocalPage;

        int total = Math.max(1, displayedTotalPages);
        if (partitionSwitchInProgress && pendingDisplayPage > 0) {
            return clampPage(pendingDisplayPage, total);
        }
        if (atDocumentEnd) {
            return total;
        }
        if (exactIndexReady && exactPageForCurrentChar > 0) {
            return clampPage(exactPageForCurrentChar, total);
        }
        return clampPage(estimatedBasePageOffset + stableLocalPage, total);
    }

    public static OffsetState preserveKnownTarget(int localPage,
                                                  int displayPage,
                                                  int totalPages,
                                                  int currentEstimatedTotalPages,
                                                  int currentDisplayedTotalPages,
                                                  int currentBasePageOffset) {
        int stableLocalPage = Math.max(1, localPage);
        int stableTotal = totalPages > 0
                ? totalPages
                : Math.max(currentEstimatedTotalPages, currentDisplayedTotalPages);
        stableTotal = Math.max(1, Math.max(stableTotal, displayPage));
        int stablePage = displayPage > 0
                ? clampPage(displayPage, stableTotal)
                : clampPage(currentBasePageOffset + stableLocalPage, stableTotal);
        int maxOffset = Math.max(0, stableTotal - stableLocalPage);
        return new OffsetState(stableTotal, clamp(stablePage - stableLocalPage, 0, maxOffset));
    }

    public static OffsetState recomputePartitionOffset(int currentEstimatedTotalPages,
                                                       int bodyPages,
                                                       int exactAnchorCount,
                                                       int totalLogicalLines,
                                                       int partitionLines,
                                                       int partitionStartLine) {
        int total = currentEstimatedTotalPages;
        if (exactAnchorCount > 0) {
            total = Math.max(1, exactAnchorCount);
        } else if (total <= 0) {
            float ratio = Math.max(1, totalLogicalLines) / (float) Math.max(1, partitionLines);
            total = Math.max(bodyPages, Math.round(Math.max(1, bodyPages) * ratio));
        }
        total = Math.max(1, total);

        int maxOffset = Math.max(0, total - 1);
        int offset = Math.round(((Math.max(1, partitionStartLine) - 1)
                / (float) Math.max(1, totalLogicalLines)) * total);
        return new OffsetState(total, clamp(offset, 0, maxOffset));
    }

    public static int clampPage(int page, int totalPages) {
        return clamp(page, 1, Math.max(1, totalPages));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
