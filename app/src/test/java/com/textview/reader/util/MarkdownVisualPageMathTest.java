package com.textview.reader.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the Markdown visual paging arithmetic: normal pages use full viewport
 * buckets, the final page lands exactly at the bottom of the scroll range, and
 * the slider remains monotonic and bounded as the document is scrolled. These
 * guard both the last-page/chrome jitter regression and the partial-final-page
 * regression where page anchors were compressed across the whole document.
 */
public class MarkdownVisualPageMathTest {

    private static final int VIEWPORT = 2000;

    @Test
    public void totalPagesUsesCeilingOverViewport() {
        assertEquals(1, MarkdownVisualPageMath.totalPages(1500, VIEWPORT));
        assertEquals(1, MarkdownVisualPageMath.totalPages(2000, VIEWPORT));
        assertEquals(2, MarkdownVisualPageMath.totalPages(2001, VIEWPORT));
        assertEquals(4, MarkdownVisualPageMath.totalPages(7300, VIEWPORT));
    }

    @Test
    public void pageTargetAndIndexRoundTrip() {
        int content = 7300;
        int max = content - VIEWPORT;
        int total = MarkdownVisualPageMath.totalPages(content, VIEWPORT);
        for (int page = 0; page < total; page++) {
            int y = MarkdownVisualPageMath.targetScrollYForPage(page, VIEWPORT, max, total);
            int back = MarkdownVisualPageMath.currentPageIndex(y, VIEWPORT, total, max);
            assertEquals("page " + page + " did not round-trip (y=" + y + ")", page, back);
        }
    }

    @Test
    public void lastPageLandsExactlyAtBottom() {
        int content = 7300;
        int max = content - VIEWPORT;
        int total = MarkdownVisualPageMath.totalPages(content, VIEWPORT);
        assertEquals(max, MarkdownVisualPageMath.targetScrollYForPage(total - 1, VIEWPORT, max, total));
        assertEquals(total - 1, MarkdownVisualPageMath.currentPageIndex(max, VIEWPORT, total, max));
    }

    @Test
    public void bottomToleranceTreatsNearBottomAsLastPage() {
        int content = 7300;
        int max = content - VIEWPORT;
        int total = MarkdownVisualPageMath.totalPages(content, VIEWPORT);
        // A few pixels short of the true bottom (chrome show/hide jitter) must
        // still be reported as the last page, not the page before it.
        assertEquals(total - 1, MarkdownVisualPageMath.currentPageIndex(max - 1, VIEWPORT, total, max));
        assertEquals(total - 1, MarkdownVisualPageMath.currentPageIndex(max - (VIEWPORT / 80), VIEWPORT, total, max));
    }


    @Test
    public void finalPageUsesMidpointToPreviousAnchor() {
        int content = 7300;
        int max = content - VIEWPORT;
        int total = MarkdownVisualPageMath.totalPages(content, VIEWPORT);
        int previous = MarkdownVisualPageMath.targetScrollYForPage(total - 2, VIEWPORT, max, total);
        int boundary = previous + (max - previous + 1) / 2;
        assertEquals(total - 2, MarkdownVisualPageMath.currentPageIndex(boundary - 1, VIEWPORT, total, max));
        assertEquals(total - 1, MarkdownVisualPageMath.currentPageIndex(boundary, VIEWPORT, total, max));
    }

    @Test
    public void topIsAlwaysPageZero() {
        int total = MarkdownVisualPageMath.totalPages(7300, VIEWPORT);
        assertEquals(0, MarkdownVisualPageMath.currentPageIndex(0, VIEWPORT, total, 7300 - VIEWPORT));
    }

    @Test
    public void singlePageDocumentStaysOnPageZero() {
        int total = MarkdownVisualPageMath.totalPages(1500, VIEWPORT);
        assertEquals(1, total);
        assertEquals(0, MarkdownVisualPageMath.currentPageIndex(0, VIEWPORT, total, 0));
        assertEquals(0, MarkdownVisualPageMath.currentPageIndex(500, VIEWPORT, total, 0));
    }

    @Test
    public void sliderIsMonotonicBoundedAndSmoothAcrossScrollRange() {
        int content = 7300;
        int max = content - VIEWPORT;
        int total = MarkdownVisualPageMath.totalPages(content, VIEWPORT);
        int prev = MarkdownVisualPageMath.currentPageIndex(0, VIEWPORT, total, max);
        for (int y = 0; y <= max; y += 50) {
            int idx = MarkdownVisualPageMath.currentPageIndex(y, VIEWPORT, total, max);
            assertTrue("index below 0 at y=" + y, idx >= 0);
            assertTrue("index above last at y=" + y, idx <= total - 1);
            assertTrue("index decreased at y=" + y, idx >= prev);
            assertTrue("index jumped more than one page at y=" + y, idx - prev <= 1);
            prev = idx;
        }
    }

    @Test
    public void partialFinalPageDoesNotCompressInteriorPageTargets() {
        int content = 7300;
        int max = content - VIEWPORT;
        int total = MarkdownVisualPageMath.totalPages(content, VIEWPORT);
        assertEquals(4, total);
        assertEquals(0, MarkdownVisualPageMath.targetScrollYForPage(0, VIEWPORT, max, total));
        assertEquals(VIEWPORT, MarkdownVisualPageMath.targetScrollYForPage(1, VIEWPORT, max, total));
        assertEquals(2 * VIEWPORT, MarkdownVisualPageMath.targetScrollYForPage(2, VIEWPORT, max, total));
        assertEquals(max, MarkdownVisualPageMath.targetScrollYForPage(3, VIEWPORT, max, total));
        assertEquals(0, MarkdownVisualPageMath.currentPageIndex(VIEWPORT - 1, VIEWPORT, total, max));
        assertEquals(1, MarkdownVisualPageMath.currentPageIndex(VIEWPORT, VIEWPORT, total, max));
    }

    @Test
    public void pageOverlapReducesStrideAndRoundTrips() {
        int content = 7300;
        int max = content - VIEWPORT;
        int overlap = 200;
        int step = VIEWPORT - overlap;
        int total = MarkdownVisualPageMath.totalPages(content, VIEWPORT, overlap);

        assertEquals(4, total);
        assertEquals(0, MarkdownVisualPageMath.targetScrollYForPage(0, VIEWPORT, max, total, overlap));
        assertEquals(step, MarkdownVisualPageMath.targetScrollYForPage(1, VIEWPORT, max, total, overlap));
        assertEquals(2 * step, MarkdownVisualPageMath.targetScrollYForPage(2, VIEWPORT, max, total, overlap));
        assertEquals(max, MarkdownVisualPageMath.targetScrollYForPage(3, VIEWPORT, max, total, overlap));

        for (int page = 0; page < total; page++) {
            int y = MarkdownVisualPageMath.targetScrollYForPage(page, VIEWPORT, max, total, overlap);
            assertEquals(page, MarkdownVisualPageMath.currentPageIndex(y, VIEWPORT, total, max, overlap));
        }
    }

    @Test
    public void lastOverlappedPageDoesNotDropOnTinyUpwardScroll() {
        int content = 7300;
        int max = content - VIEWPORT;
        int overlap = 200;
        int total = MarkdownVisualPageMath.totalPages(content, VIEWPORT, overlap);
        assertEquals(total - 1, MarkdownVisualPageMath.currentPageIndex(max, VIEWPORT, total, max, overlap));
        assertEquals(total - 1, MarkdownVisualPageMath.currentPageIndex(max - 20, VIEWPORT, total, max, overlap));
    }

    @Test
    public void overlappedFinalPageDoesNotSwallowPreviousTarget() {
        int content = 7300;
        int max = content - VIEWPORT;
        int overlap = 200;
        int total = MarkdownVisualPageMath.totalPages(content, VIEWPORT, overlap);
        int previous = MarkdownVisualPageMath.targetScrollYForPage(total - 2, VIEWPORT, max, total, overlap);
        assertEquals(total - 2, MarkdownVisualPageMath.currentPageIndex(previous, VIEWPORT, total, max, overlap));
    }

    @Test
    public void unknownTotalFallsBackToViewportMultiple() {
        // Legacy path: totalPages unknown (<= 0) uses viewport-multiple target.
        int max = 5300;
        assertEquals(0, MarkdownVisualPageMath.targetScrollYForPage(0, VIEWPORT, max));
        assertEquals(VIEWPORT, MarkdownVisualPageMath.targetScrollYForPage(1, VIEWPORT, max));
        assertEquals(2 * VIEWPORT, MarkdownVisualPageMath.targetScrollYForPage(2, VIEWPORT, max));
        // clamped at max
        assertEquals(max, MarkdownVisualPageMath.targetScrollYForPage(10, VIEWPORT, max));
    }
}
