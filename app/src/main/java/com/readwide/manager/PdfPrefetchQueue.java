package com.readwide.manager;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

/** Main-thread-owned rolling plan. Only one speculative native render may be active. */
final class PdfPrefetchQueue {
    static final class Request {
        final int page;
        final int geometry;
        volatile boolean cancelled;

        Request(int page, int geometry) {
            this.page = page;
            this.geometry = geometry;
        }
    }

    private int center = -1;
    private int count;
    private int direction;
    private int geometry;
    private Request active;
    private final Set<Integer> attempted = new HashSet<>();
    private static final int[] FORWARD = {1, 2, 3, -1, 4, -2, -3, -4};
    private static final int[] BACKWARD = {-1, -2, -3, 1, -4, 2, 3, 4};
    private static final int[] BALANCED = {1, -1, 2, -2, 3, -3, 4, -4};
    private final int[] neighbors = new int[4];
    private int neighborCount;

    void update(int center, int count, int direction, int geometry) {
        count = Math.max(0, count);
        center = center < 0 || count == 0 ? -1 : Math.min(center, count - 1);
        int normalizedDirection = Integer.signum(direction);
        if (this.center != center || this.count != count
                || this.direction != normalizedDirection || this.geometry != geometry) {
            attempted.clear();
        }
        this.center = center;
        this.count = count;
        this.direction = normalizedDirection;
        this.geometry = geometry;
        neighborCount = 0;
        if (center >= 0) {
            int[] offsets = this.direction > 0 ? FORWARD : this.direction < 0 ? BACKWARD : BALANCED;
            for (int offset : offsets) {
                long candidate = (long) center + offset;
                if (candidate < 0 || candidate >= count) continue;
                neighbors[neighborCount++] = (int) candidate;
                if (neighborCount == neighbors.length) break;
            }
        }
        if (active != null && (active.geometry != geometry || !wants(active.page))) {
            active.cancelled = true;
        }
    }

    void cancelPending() {
        center = -1;
        neighborCount = 0;
        attempted.clear();
        if (active != null) active.cancelled = true;
        // Native rendering cannot be interrupted safely. Its completion retains
        // ownership of this token even across document/geometry invalidation.
    }

    /** Pause refill for a cache miss, retaining only the exact requested token. */
    void pauseForPage(int page, int geometry) {
        center = -1;
        neighborCount = 0;
        attempted.clear();
        if (active != null && (active.page != page || active.geometry != geometry)) {
            active.cancelled = true;
        }
    }

    boolean isActive(int page, int geometry) {
        return active != null && !active.cancelled
                && active.page == page && active.geometry == geometry;
    }

    boolean wants(int page) {
        if (center < 0 || page < 0 || page >= count) return false;
        if (page == center) return true;
        for (int i = 0; i < neighborCount; i++) {
            if (neighbors[i] == page) return true;
        }
        return false;
    }

    Request next(IntPredicate cached) {
        if (active != null || center < 0) return null;
        for (int i = 0; i < neighborCount; i++) {
            int page = neighbors[i];
            if (attempted.contains(page) || cached.test(page)) continue;
            attempted.add(page);
            active = new Request(page, geometry);
            return active;
        }
        return null;
    }

    void finish(Request request) {
        if (active != request) return;
        if (!request.cancelled && request.geometry == geometry) attempted.add(request.page);
        else attempted.remove(request.page);
        active = null;
    }

    static long fitPagePixelBudget(int cacheBytes) {
        // Four neighbors plus the visible page. Keep the existing prefetch cap;
        // zoomed detail is still rendered separately by the sharpen-patch path.
        return Math.max(1L, Math.min(6_000_000L, cacheBytes / (5L * 4L)));
    }
}
