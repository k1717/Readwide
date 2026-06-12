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

    public static int currentPageIndex(int scrollYPx, int viewportHeightPx, int totalPages) {
        return currentPageIndex(scrollYPx, viewportHeightPx, totalPages, -1);
    }

    public static int currentPageIndex(int scrollYPx, int viewportHeightPx, int totalPages, int maxScrollY) {
        int viewport = Math.max(1, viewportHeightPx);
        int total = Math.max(1, totalPages);
        int scroll = Math.max(0, scrollYPx);
        int max = Math.max(0, maxScrollY);
        if (maxScrollY >= 0 && total > 1 && scroll >= Math.max(0, max - 2)) {
            return total - 1;
        }
        int page = scroll / viewport;
        return Math.max(0, Math.min(total - 1, page));
    }

    public static int targetScrollYForPage(int pageIndex, int viewportHeightPx, int maxScrollY) {
        int viewport = Math.max(1, viewportHeightPx);
        int max = Math.max(0, maxScrollY);
        int page = Math.max(0, pageIndex);
        long target = (long) page * (long) viewport;
        if (target > Integer.MAX_VALUE) target = Integer.MAX_VALUE;
        return Math.max(0, Math.min(max, (int) target));
    }
}
