package com.textview.reader.util;

/**
 * Small arithmetic helper for Markdown document WebView visual paging.
 *
 * Markdown documents are rendered/reflowed HTML pages, so their page count is
 * based on the rendered content height and the visible WebView viewport height
 * rather than the TXT reader's exact source-offset page anchors.
 */
public final class MarkdownVisualPageMath {
    private MarkdownVisualPageMath() {}

    public static int totalPages(int contentHeightPx, int viewportHeightPx) {
        int viewport = Math.max(1, viewportHeightPx);
        int content = Math.max(viewport, contentHeightPx);
        return Math.max(1, (int) Math.ceil(content / (double) viewport));
    }

    /**
     * Total page count for overlapped Markdown visual paging.
     *
     * The overlap must reduce the page stride, not merely subtract a fixed top
     * offset from every target.  Otherwise consecutive pages remain exactly one
     * viewport apart and the next page still starts in the middle of the same
     * cut line/block.  With stride = viewport - overlap, each next page repeats
     * the previous page's lower overlap band and gives enough context for a
     * sentence or code line that was clipped at the bottom edge.
     */
    public static int totalPages(int contentHeightPx, int viewportHeightPx, int pageOverlapPx) {
        int viewport = Math.max(1, viewportHeightPx);
        int content = Math.max(viewport, contentHeightPx);
        int overlap = normalizedOverlap(viewport, pageOverlapPx);
        int max = Math.max(0, content - viewport);
        if (max <= 0) return 1;
        int step = Math.max(1, viewport - overlap);
        return Math.max(1, (int) Math.ceil(max / (double) step) + 1);
    }

    public static int currentPageIndex(int scrollYPx, int viewportHeightPx, int totalPages) {
        return currentPageIndex(scrollYPx, viewportHeightPx, totalPages, -1);
    }

    public static int currentPageIndex(int scrollYPx, int viewportHeightPx, int totalPages, int maxScrollY) {
        return currentPageIndex(scrollYPx, viewportHeightPx, totalPages, maxScrollY, 0);
    }

    public static int currentPageIndex(int scrollYPx, int viewportHeightPx, int totalPages, int maxScrollY, int pageOverlapPx) {
        int viewport = Math.max(1, viewportHeightPx);
        int total = Math.max(1, totalPages);
        int scroll = Math.max(0, scrollYPx);
        int overlap = normalizedOverlap(viewport, pageOverlapPx);
        if (total <= 1) return 0;

        int max = Math.max(0, maxScrollY);
        int step = Math.max(1, viewport - overlap);
        if (maxScrollY >= 0) {
            scroll = Math.min(scroll, max);
            // The final page anchor is clamped to maxScrollY and may be much closer
            // to the previous anchor than a full page step.  Using step/2 as the
            // final-page tolerance makes the final page swallow the previous page,
            // so tapping/scrolling back from the bottom can get stuck.  Use the
            // real midpoint between the previous target and the final target.
            int finalStart = Math.max(0, targetScrollYForPage(total - 1, viewport, max, total, overlap));
            int previousStart = total >= 2
                    ? Math.max(0, targetScrollYForPage(total - 2, viewport, max, total, overlap))
                    : 0;
            if (finalStart > previousStart) {
                int finalBoundary = previousStart + Math.max(1, (finalStart - previousStart + 1) / 2);
                if (scroll >= finalBoundary) return total - 1;
                total = Math.max(1, total - 1);
            }
        }

        int page = (int) Math.floor((scroll + (step / 2.0d)) / (double) step);
        return Math.max(0, Math.min(total - 1, page));
    }

    public static int targetScrollYForPage(int pageIndex, int viewportHeightPx, int maxScrollY) {
        int max = Math.max(0, maxScrollY);
        int page = Math.max(0, pageIndex);
        if (page == 0) return 0;
        return targetScrollYForPage(pageIndex, viewportHeightPx, maxScrollY, -1);
    }

    /**
     * Scroll target for a page that round-trips with {@link #currentPageIndex}.
     * Markdown visual pages use viewport-sized buckets. The final page is still
     * clamped to {@code maxScrollY}, so the unavoidable overlap is limited to
     * the last viewport instead of being distributed through the whole document.
     * When {@code totalPages} is unknown ({@code <= 0}) this is the same legacy
     * viewport-multiple target.
     */
    public static int targetScrollYForPage(int pageIndex, int viewportHeightPx, int maxScrollY, int totalPages) {
        return targetScrollYForPage(pageIndex, viewportHeightPx, maxScrollY, totalPages, 0);
    }

    public static int targetScrollYForPage(int pageIndex, int viewportHeightPx, int maxScrollY, int totalPages, int pageOverlapPx) {
        int viewport = Math.max(1, viewportHeightPx);
        int max = Math.max(0, maxScrollY);
        int page = Math.max(0, pageIndex);
        int overlap = normalizedOverlap(viewport, pageOverlapPx);
        if (page == 0) return 0;
        if (totalPages <= 0) {
            long legacyTarget = (long) page * (long) viewport;
            if (legacyTarget > Integer.MAX_VALUE) legacyTarget = Integer.MAX_VALUE;
            return Math.max(0, Math.min(max, (int) legacyTarget));
        }
        int total = Math.max(1, totalPages);
        if (page >= total - 1 && max > 0) return max;
        int step = Math.max(1, viewport - overlap);
        long target = (long) page * (long) step;
        if (target > Integer.MAX_VALUE) target = Integer.MAX_VALUE;
        return Math.max(0, Math.min(max, (int) target));
    }

    private static int normalizedOverlap(int viewportHeightPx, int pageOverlapPx) {
        int viewport = Math.max(1, viewportHeightPx);
        return Math.max(0, Math.min(viewport - 1, pageOverlapPx));
    }
}
