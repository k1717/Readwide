package com.readwide.manager.util;

/**
 * Pure decision for which char position and anchors {@code saveReadingState}
 * should persist for a large-TXT reader.
 *
 * <p>The problem this solves: during an in-flight partition switch,
 * {@code getDisplayedCurrentPageNumber()} already returns the <em>pending</em>
 * page (see {@link LargeTextPageModelMath#displayedCurrentPage}). If the save
 * path persisted the current readerView char position and its anchors while
 * the page number reflected the pending page, a pause mid-switch would store a
 * page and a position that disagree, and a later restore could land on the
 * wrong page. When the exact page index is ready, the pending page's exact
 * anchor gives a char position and before/after text that are consistent with
 * the saved page number, so we prefer it.</p>
 *
 * <p>This class only decides <em>whether</em> to use the pending anchor; the
 * controller supplies the actual anchor values. Keeping the predicate here
 * makes it unit-testable without an Activity.</p>
 */
public final class ReaderSaveAnchorMath {
    private ReaderSaveAnchorMath() {}

    /**
     * @param largeTextActive          whether the large-TXT estimate path is active
     * @param partitionSwitchInProgress whether a partition switch is in flight
     * @param exactPageIndexReady       whether the exact page index can resolve anchors
     * @param pendingDisplayPage        the pending page (1-based; {@code <= 0} means none)
     * @param pendingAnchorAvailable    whether an exact anchor exists for that page
     * @return true if the save path should override the current char position and
     *     anchors with the pending page's exact anchor
     */
    public static boolean shouldUsePendingPageAnchor(boolean largeTextActive,
                                                     boolean partitionSwitchInProgress,
                                                     boolean exactPageIndexReady,
                                                     int pendingDisplayPage,
                                                     boolean pendingAnchorAvailable) {
        return largeTextActive
                && partitionSwitchInProgress
                && exactPageIndexReady
                && pendingDisplayPage > 0
                && pendingAnchorAvailable;
    }
}
