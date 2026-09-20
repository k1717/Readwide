package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class PdfContinuousRenderQueueTest {
    @Test
    public void visibleCenterAndRowsPrecedeDirectionalNeighbors() {
        PdfContinuousRenderQueue queue = window(4, 6, 5, 100, 1, 1);
        assertPages(queue, 5, 6, 4, 7, 3);
        assertNull(queue.next(page -> false));
    }

    @Test
    public void reverseScrollPrefersEarlierRowAndNeighbor() {
        PdfContinuousRenderQueue queue = window(4, 6, 5, 100, -1, 1);
        assertPages(queue, 5, 4, 6, 3, 7);
    }

    @Test
    public void cachedOrDisplayedRowsAreSkipped() {
        PdfContinuousRenderQueue queue = window(4, 6, 5, 100, 1, 1);
        assertEquals(4, queue.next(page -> page == 5 || page == 6).page);
    }

    @Test
    public void onlyOneOutstandingTokenEvenAfterLargeJump() {
        PdfContinuousRenderQueue queue = window(1, 2, 1, 100, 1, 1);
        PdfContinuousRenderQueue.Request old = queue.next(page -> false);
        queue.update(80, 81, 80, 100, 1, 1);
        assertTrue(old.cancelled);
        assertNull(queue.next(page -> false));
        queue.finish(old, false);
        assertEquals(80, queue.next(page -> false).page);
    }

    @Test
    public void visibleDemandSupersedesQueuedOffscreenPrefetch() {
        PdfContinuousRenderQueue queue = window(5, 5, 5, 100, 1, 1);
        PdfContinuousRenderQueue.Request speculative = queue.next(page -> page == 5);
        assertEquals(6, speculative.page);
        assertNull(queue.next(page -> false));
        assertTrue(speculative.cancelled);
        queue.finish(speculative, false);
        assertEquals(5, queue.next(page -> false).page);
    }

    @Test
    public void prefetchBecomingVisibleIsReused() {
        PdfContinuousRenderQueue queue = window(5, 5, 5, 100, 1, 1);
        PdfContinuousRenderQueue.Request speculative = queue.next(page -> page == 5);
        queue.update(6, 6, 6, 100, 1, 1);
        assertNull(queue.next(page -> false));
        assertFalse(speculative.cancelled);
        queue.finish(speculative, true);
        assertEquals(7, queue.next(page -> page == 6).page);
    }

    @Test
    public void geometryInvalidationRetainsOwnershipUntilOldWorkerFinishes() {
        PdfContinuousRenderQueue queue = window(5, 5, 5, 100, 1, 1);
        PdfContinuousRenderQueue.Request old = queue.next(page -> false);
        queue.update(5, 5, 5, 100, 1, 2);
        assertTrue(old.cancelled);
        assertNull(queue.next(page -> false));
        queue.finish(old, true);
        PdfContinuousRenderQueue.Request next = queue.next(page -> false);
        assertEquals(5, next.page);
        assertEquals(2, next.generation);
    }

    @Test
    public void cancelledPageReturningBeforeCompletionGetsFreshToken() {
        PdfContinuousRenderQueue queue = window(5, 5, 5, 100, 1, 1);
        PdfContinuousRenderQueue.Request old = queue.next(page -> false);
        queue.update(80, 80, 80, 100, 1, 1);
        queue.update(5, 5, 5, 100, -1, 1);
        assertTrue(old.cancelled);
        queue.finish(old, true);
        assertEquals(5, queue.next(page -> false).page);
    }

    @Test
    public void stopAndResumeDoesNotCreateTwoJobs() {
        PdfContinuousRenderQueue queue = window(5, 5, 5, 100, 1, 1);
        PdfContinuousRenderQueue.Request old = queue.next(page -> false);
        queue.cancelPending();
        assertTrue(old.cancelled);
        assertFalse(queue.wants(5));
        assertNull(queue.next(page -> false));
        queue.update(5, 5, 5, 100, 1, 1);
        assertNull(queue.next(page -> false));
        queue.finish(old, false);
        assertEquals(5, queue.next(page -> false).page);
    }

    @Test
    public void staleCompletionCannotReleaseNewJob() {
        PdfContinuousRenderQueue queue = window(5, 5, 5, 100, 1, 1);
        PdfContinuousRenderQueue.Request old = queue.next(page -> false);
        queue.finish(old, true);
        PdfContinuousRenderQueue.Request current = queue.next(page -> false);
        queue.finish(old, true);
        assertNull(queue.next(page -> page == 5));
        queue.finish(current, true);
        assertEquals(4, queue.next(page -> false).page);
    }

    @Test
    public void failureOrUncacheablePageIsNotRetriedForever() {
        PdfContinuousRenderQueue queue = window(5, 5, 5, 100, 1, 1);
        assertPages(queue, 5, 6, 4);
        queue.update(5, 5, 5, 100, 1, 1);
        assertNull(queue.next(page -> false));
        queue.update(6, 6, 6, 100, 1, 1);
        assertEquals(6, queue.next(page -> false).page);
    }

    @Test
    public void skippedWorkCanBeRequestedAgain() {
        PdfContinuousRenderQueue queue = window(5, 5, 5, 100, 1, 1);
        PdfContinuousRenderQueue.Request old = queue.next(page -> false);
        queue.finish(old, false);
        assertEquals(5, queue.next(page -> false).page);
    }

    @Test
    public void boundaryAndEmptyDocumentsHaveNoInvalidCandidates() {
        PdfContinuousRenderQueue queue = window(0, 0, 0, 1, -1, 1);
        assertPages(queue, 0);
        assertNull(queue.next(page -> false));
        queue.update(0, 0, 0, 0, 1, 2);
        assertNull(queue.next(page -> false));
        assertFalse(queue.wants(0));
    }

    @Test
    public void lastPageArithmeticDoesNotWrapToNegative() {
        int last = Integer.MAX_VALUE - 1;
        PdfContinuousRenderQueue queue = window(last, last, last, Integer.MAX_VALUE, 1, 1);
        assertPages(queue, last, last - 1);
        assertNull(queue.next(page -> false));
        assertFalse(queue.wants(-1));
    }

    @Test
    public void cacheBudgetLeavesSpaceForThreeArgbPages() {
        long cacheBytes = 24L * 1024L * 1024L;
        long pixels = PdfContinuousRenderQueue.pagePixelBudget(cacheBytes, 18_000_000L);
        assertEquals(2_097_152L, pixels);
        assertTrue(pixels * 4L * 3L <= cacheBytes);
        assertEquals(12_000_000L,
                PdfContinuousRenderQueue.pagePixelBudget(Long.MAX_VALUE, 12_000_000L));
        assertEquals(1L, PdfContinuousRenderQueue.pagePixelBudget(0L, 0L));
    }

    private static PdfContinuousRenderQueue window(int first, int last, int center,
                                                   int count, int direction, int generation) {
        PdfContinuousRenderQueue queue = new PdfContinuousRenderQueue();
        queue.update(first, last, center, count, direction, generation);
        return queue;
    }

    private static void assertPages(PdfContinuousRenderQueue queue, int... expected) {
        Set<Integer> ready = new HashSet<>();
        for (int page : expected) {
            PdfContinuousRenderQueue.Request request = queue.next(ready::contains);
            assertNotNull(request);
            assertEquals(page, request.page);
            ready.add(page);
            queue.finish(request, true);
        }
    }
}
