package com.readwide.manager;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Main-thread-owned demand window, not a FIFO of every row bound during a fling.
 * At most one token may be submitted to the renderer. Only its cancellation bit
 * is read by the worker, before opening a page and again after taking the lock.
 */
final class PdfContinuousRenderQueue {
    static final class Request {
        final int page;
        final int generation;
        volatile boolean cancelled;

        Request(int page, int generation) {
            this.page = page;
            this.generation = generation;
        }
    }

    private int first = -1;
    private int last = -1;
    private int center = -1;
    private int count;
    private int direction = 1;
    private int generation;
    private Request active;
    private final Set<Integer> attempted = new HashSet<>();

    void update(int first, int last, int center, int count, int direction, int generation) {
        int safeCount = Math.max(0, count);
        int safeFirst = safeCount == 0 ? -1 : Math.max(0, Math.min(safeCount - 1, first));
        int safeLast = safeCount == 0 ? -1 : Math.max(safeFirst, Math.min(safeCount - 1, last));
        int safeCenter = safeCount == 0 ? -1 : Math.max(safeFirst, Math.min(safeLast, center));
        int safeDirection = direction < 0 ? -1 : 1;
        if (this.first != safeFirst || this.last != safeLast || this.center != safeCenter
                || this.count != safeCount || this.direction != safeDirection
                || this.generation != generation) {
            attempted.clear();
        }
        this.first = safeFirst;
        this.last = safeLast;
        this.center = safeCenter;
        this.count = safeCount;
        this.direction = safeDirection;
        this.generation = generation;
        if (active != null && (active.generation != generation || !wants(active.page))) {
            active.cancelled = true;
        }
    }

    void cancelPending() {
        first = last = center = -1;
        attempted.clear();
        if (active != null) active.cancelled = true;
        // A queued/native render owns its token until completion even after a
        // mode/document change. Do not allow a second outstanding worker job.
    }

    boolean wants(int page) {
        return first >= 0 && page >= 0 && page < count
                && (long) page >= (long) first - 1L && (long) page <= (long) last + 1L;
    }

    Request next(IntPredicate ready) {
        if (first < 0) return null;
        int visible = nextVisible(ready);
        if (active != null) {
            // Visible demand may supersede a speculative token still waiting on
            // the executor/renderer. A native render already in progress finishes
            // normally; its unwanted bitmap is discarded by the main thread.
            if (visible >= 0 && (active.page < first || active.page > last)) {
                active.cancelled = true;
            }
            return null;
        }
        if (visible >= 0) return start(visible);
        long ahead = direction > 0 ? (long) last + 1L : (long) first - 1L;
        long behind = direction > 0 ? (long) first - 1L : (long) last + 1L;
        if (eligible(ahead, ready)) return start((int) ahead);
        if (eligible(behind, ready)) return start((int) behind);
        return null;
    }

    private int nextVisible(IntPredicate ready) {
        if (eligible(center, ready)) return center;
        long radius = Math.max((long) center - first, (long) last - center);
        for (long distance = 1; distance <= radius; distance++) {
            long ahead = center + distance * direction;
            long behind = center - distance * direction;
            if (ahead >= first && ahead <= last && eligible(ahead, ready)) return (int) ahead;
            if (behind >= first && behind <= last && eligible(behind, ready)) return (int) behind;
        }
        return -1;
    }

    private boolean eligible(long page, IntPredicate ready) {
        return page >= 0 && page < count && !attempted.contains((int) page)
                && !ready.test((int) page);
    }

    private Request start(int page) {
        active = new Request(page, generation);
        return active;
    }

    void finish(Request request, boolean renderedOrFailed) {
        if (active != request) return;
        if (renderedOrFailed && !request.cancelled && request.generation == generation
                && wants(request.page)) {
            // Bound retries and avoid an eviction/re-render loop when a page
            // cannot be cached. A changed viewport permits another attempt.
            attempted.add(request.page);
        }
        active = null;
    }

    static long pagePixelBudget(long cacheBytes, long perPageCap) {
        // Leave room for the visible page and a neighbor on either side. Bitmap
        // resolution may shrink, but the intended row/zoom dimensions must not.
        return Math.max(1L, Math.min(Math.max(1L, perPageCap), cacheBytes / (3L * 4L)));
    }
}
