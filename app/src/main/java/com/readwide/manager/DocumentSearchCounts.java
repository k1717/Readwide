package com.readwide.manager;

/** Immutable per-page counts and prefix sums; no document text is retained. */
final class DocumentSearchCounts {
    private final int[] counts;
    private final long[] prefix;
    DocumentSearchCounts(int[] counts) {
        this.counts = counts.clone();
        prefix = new long[counts.length + 1];
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] < 0) throw new IllegalArgumentException("Negative match count");
            prefix[i + 1] = prefix[i] + counts[i];
        }
    }
    int onPage(int page) { return page >= 0 && page < counts.length ? counts[page] : 0; }
    long before(int page) { return prefix[Math.max(0, Math.min(page, counts.length))]; }
    int total() { return (int) Math.min(Integer.MAX_VALUE, prefix[counts.length]); }
    int pageForOccurrence(long occurrence) {
        if (occurrence < 1 || occurrence > prefix[counts.length]) return -1;
        int low = 0, high = counts.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (prefix[mid + 1] < occurrence) low = mid + 1; else high = mid;
        }
        return low;
    }
}
