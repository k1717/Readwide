package com.readwide.manager;

import static org.junit.Assert.*;

import org.junit.Test;

public class PdfPrefetchQueueTest {
    @Test public void forwardReadingReplenishesThreeAheadBeforeOneBehind() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(5, 20, 1, 0);
        assertOrder(queue, 6, 7, 8, 4);
    }

    @Test public void reverseReadingReplenishesThreeBehindBeforeOneAhead() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(5, 20, -1, 0);
        assertOrder(queue, 4, 3, 2, 6);
    }

    @Test public void initialDirectionBalancesBothSides() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(5, 20, 0, 0);
        assertOrder(queue, 6, 4, 7, 3);
    }

    @Test public void onlyOneNativeJobMayBeActive() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(5, 20, 1, 7);
        PdfPrefetchQueue.Request request = queue.next(page -> false);
        assertTrue(queue.isActive(6, 7));
        assertFalse(queue.isActive(6, 8));
        assertNull(queue.next(page -> false));
        queue.finish(request);
        assertFalse(queue.isActive(6, 7));
    }

    @Test public void rapidAdvanceReplacesOldPrioritiesAtCompletion() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(5, 30, 1, 0);
        PdfPrefetchQueue.Request old = queue.next(page -> false);
        queue.update(12, 30, 1, 0);
        assertFalse(queue.wants(old.page));
        assertNull(queue.next(page -> false));
        queue.finish(old);
        assertEquals(13, queue.next(page -> false).page);
    }

    @Test public void reversalPrioritizesNearestNewDirection() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(5, 20, 1, 0);
        PdfPrefetchQueue.Request old = queue.next(page -> false);
        queue.update(5, 20, -1, 0);
        queue.finish(old);
        assertEquals(4, queue.next(page -> false).page);
    }

    @Test public void pauseForExactPageRetainsActiveTokenForPromotionButStopsRefill() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(5, 20, 1, 0);
        PdfPrefetchQueue.Request old = queue.next(page -> false);
        queue.pauseForPage(6, 0);
        assertTrue(queue.isActive(6, 0));
        assertFalse(queue.wants(6));
        queue.finish(old);
        assertNull(queue.next(page -> false));
    }

    @Test public void oldGeometryCompletionCannotClearNewActiveToken() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(5, 20, 1, 0);
        PdfPrefetchQueue.Request old = queue.next(page -> false);
        queue.cancelPending();
        queue.update(5, 20, 1, 1);
        assertFalse(queue.isActive(6, 1));
        assertNull(queue.next(page -> false));
        queue.finish(old);
        PdfPrefetchQueue.Request fresh = queue.next(page -> false);
        assertEquals(6, fresh.page);
        queue.finish(old);
        assertTrue(queue.isActive(6, 1));
    }

    @Test public void cachedPagesAndFailedAttemptsDoNotCauseRefillLoops() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(0, 3, 1, 0);
        PdfPrefetchQueue.Request request = queue.next(page -> page == 1);
        assertEquals(2, request.page);
        queue.finish(request); // No bitmap was cached, as on a failed render.
        queue.update(0, 3, 1, 0);
        assertNull(queue.next(page -> page == 1));
    }

    @Test public void documentEdgesAndLargeIndicesDoNotWrap() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(0, 1, 1, 0);
        assertNull(queue.next(page -> false));
        queue.update(Integer.MAX_VALUE - 1, Integer.MAX_VALUE, 1, 0);
        assertOrder(queue, Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 3,
                Integer.MAX_VALUE - 4, Integer.MAX_VALUE - 5);
    }

    @Test public void openingAtFirstPageFillsAllFourSlotsAhead() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(0, 20, 0, 0);
        assertOrder(queue, 1, 2, 3, 4);
    }

    @Test public void readingForwardNearEndUsesSpareSlotsBehind() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(18, 20, 1, 0);
        assertOrder(queue, 19, 17, 16, 15);
    }

    @Test public void readingBackwardNearStartUsesSpareSlotsAhead() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(1, 20, -1, 0);
        assertOrder(queue, 0, 2, 3, 4);
    }

    @Test public void shortDocumentsNeverDuplicatePagesOrIncludeCenter() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(1, 4, 0, 0);
        assertOrder(queue, 2, 0, 3);
        queue.update(0, 0, 1, 0);
        assertNull(queue.next(page -> false));
    }

    @Test public void hardCancellationInvalidatesPromotionButRetainsOwnershipUntilFinish() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(5, 20, 1, 0);
        PdfPrefetchQueue.Request old = queue.next(page -> false);
        queue.cancelPending();
        assertTrue(old.cancelled);
        assertFalse(queue.isActive(6, 0));
        queue.update(5, 20, 1, 0);
        assertNull(queue.next(page -> false));
        queue.finish(old);
        assertEquals(6, queue.next(page -> false).page);
    }

    @Test public void pausingForDifferentPageCancelsUnneededNativeWork() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(5, 20, 1, 0);
        PdfPrefetchQueue.Request old = queue.next(page -> false);
        queue.pauseForPage(12, 0);
        assertTrue(old.cancelled);
        assertFalse(queue.isActive(6, 0));
        queue.finish(old);
        assertNull(queue.next(page -> false));
    }

    @Test public void samePageAtNewGeometryCannotPromoteOldRender() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(5, 20, 1, 0);
        PdfPrefetchQueue.Request old = queue.next(page -> false);
        queue.pauseForPage(6, 1);
        assertTrue(old.cancelled);
        assertFalse(queue.isActive(6, 0));
    }

    @Test public void returningToOldWindowCannotReviveCancelledWork() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(5, 30, 1, 0);
        PdfPrefetchQueue.Request old = queue.next(page -> false);
        queue.update(20, 30, 1, 0);
        assertTrue(old.cancelled);
        queue.update(5, 30, 1, 0);
        queue.pauseForPage(6, 0);
        assertTrue(old.cancelled);
        assertFalse(queue.isActive(6, 0));
    }

    @Test public void activePageStillInNewWindowRemainsUseful() {
        PdfPrefetchQueue queue = new PdfPrefetchQueue();
        queue.update(5, 30, 1, 0);
        PdfPrefetchQueue.Request active = queue.next(page -> false);
        queue.update(6, 30, 1, 0);
        assertFalse(active.cancelled);
        assertTrue(queue.isActive(6, 0));
        queue.finish(active);
        assertOrder(queue, 7, 8, 9, 5);
    }

    @Test public void fitBudgetLeavesRoomForVisiblePageAndFourNeighbors() {
        for (int bytes : new int[]{24 * 1024 * 1024, 48 * 1024 * 1024, 96 * 1024 * 1024}) {
            long pixels = PdfPrefetchQueue.fitPagePixelBudget(bytes);
            assertTrue(pixels * 4 * 5 <= bytes);
            assertTrue(pixels <= 6_000_000L);
            PdfPageRenderPlan.Plan plan = PdfPageRenderPlan.create(
                    600, 800, 3000, 4000, 1f, 1.4f, 24, 16, true, pixels);
            assertTrue((long) plan.bitmapWidthPx * plan.bitmapHeightPx <= pixels);
            assertEquals(2976, plan.intendedDisplayWidthPx);
        }
    }

    private static void assertOrder(PdfPrefetchQueue queue, int... pages) {
        for (int page : pages) {
            PdfPrefetchQueue.Request request = queue.next(candidate -> false);
            assertNotNull(request);
            assertEquals(page, request.page);
            queue.finish(request);
        }
        assertNull(queue.next(page -> false));
    }
}
