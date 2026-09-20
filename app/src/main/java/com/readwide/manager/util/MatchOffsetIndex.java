package com.readwide.manager.util;

import java.util.Arrays;
import java.util.concurrent.CancellationException;

/** Adaptive start-offset storage: sparse integers or a complete ranked bitmap. */
final class MatchOffsetIndex {
    private static final int WORDS_PER_BLOCK = 8;
    private final int textLength, count;
    private final int[] starts;
    private final long[] words;
    private final int[] ranks;

    private MatchOffsetIndex(int textLength, int count, int[] starts, long[] words, int[] ranks) {
        this.textLength = textLength; this.count = count; this.starts = starts;
        this.words = words; this.ranks = ranks;
    }

    int count() { return count; }
    boolean isComplete() { return words != null || count == starts.length; }
    long cachedBytes() { return 4L * starts.length + (words == null ? 0L : 8L * words.length + 4L * ranks.length); }

    /** Number of hits strictly before the given original-text boundary. */
    int rank(long boundary) {
        if (!isComplete()) throw new IllegalStateException("Partial offset index");
        if (boundary <= 0L) return 0;
        if (boundary >= textLength) return count;
        int position = (int) boundary;
        if (words == null) {
            int low = 0, high = count;
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (starts[mid] < position) low = mid + 1;
                else high = mid;
            }
            return low;
        }
        int word = position >>> 6, block = word / WORDS_PER_BLOCK;
        int total = ranks[block];
        for (int i = block * WORDS_PER_BLOCK; i < word; i++) total += Long.bitCount(words[i]);
        int bits = position & 63;
        if (bits != 0) total += Long.bitCount(words[word] & (-1L >>> (64 - bits)));
        return total;
    }

    /** Start of a cached 1-based occurrence, or -1 for an uncached/invalid ordinal. */
    int occurrence(int ordinal) {
        if (ordinal < 1 || ordinal > count) return -1;
        if (words == null) return ordinal <= starts.length ? starts[ordinal - 1] : -1;
        int low = 0, high = ranks.length - 2;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (ranks[mid + 1] < ordinal) low = mid + 1;
            else high = mid;
        }
        int remaining = ordinal - ranks[low];
        int end = Math.min(words.length, (low + 1) * WORDS_PER_BLOCK);
        for (int i = low * WORDS_PER_BLOCK; i < end; i++) {
            long bits = words[i];
            int hits = Long.bitCount(bits);
            if (remaining > hits) { remaining -= hits; continue; }
            // At most 63 bit removals, independent of document/match count.
            while (--remaining > 0) bits &= bits - 1;
            return (i << 6) + Long.numberOfTrailingZeros(bits);
        }
        throw new IllegalStateException("Invalid rank table");
    }

    static final class Builder {
        private final int textLength, limit, wordCount;
        private final long bitmapBytes;
        private final boolean bitmapFits;
        private int count, previous = -1;
        private int[] starts;
        private long[] words;
        private boolean built;

        Builder(int textLength, int maxCachedStarts) {
            if (textLength < 0 || maxCachedStarts < 0) throw new IllegalArgumentException();
            this.textLength = textLength; limit = maxCachedStarts;
            wordCount = (int) ((textLength + 63L) >>> 6);
            long blocks = (wordCount + WORDS_PER_BLOCK - 1L) / WORDS_PER_BLOCK;
            bitmapBytes = 8L * wordCount + 4L * (blocks + 1L);
            bitmapFits = bitmapBytes <= 4L * limit;
            starts = new int[Math.min(256, limit)];
        }

        void add(int start) {
            if (built) throw new IllegalStateException("Builder already consumed");
            if (start < 0 || start >= textLength || start <= previous) throw new IllegalArgumentException("Unordered match start");
            previous = start;
            if (words == null && bitmapFits && 4L * (count + 1L) >= bitmapBytes) {
                words = new long[wordCount];
                for (int i = 0; i < count; i++) set(starts[i]);
                starts = new int[0];
            }
            if (words != null) set(start);
            else if (count < limit) {
                if (count == starts.length) starts = Arrays.copyOf(starts,
                        (int) Math.min(limit, Math.max(1L, 2L * starts.length)));
                starts[count] = start;
            }
            count++;
        }

        private void set(int start) { words[start >>> 6] |= 1L << (start & 63); }

        MatchOffsetIndex build() {
            if (built) throw new IllegalStateException("Builder already consumed");
            built = true;
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (words == null) return new MatchOffsetIndex(textLength, count,
                    Arrays.copyOf(starts, Math.min(count, limit)), null, null);
            int blocks = (words.length + WORDS_PER_BLOCK - 1) / WORDS_PER_BLOCK;
            int[] ranks = new int[blocks + 1];
            int total = 0;
            for (int block = 0; block < blocks; block++) {
                if ((block & 255) == 0 && Thread.currentThread().isInterrupted()) throw new CancellationException();
                ranks[block] = total;
                int end = Math.min(words.length, (block + 1) * WORDS_PER_BLOCK);
                for (int i = block * WORDS_PER_BLOCK; i < end; i++) total += Long.bitCount(words[i]);
            }
            ranks[blocks] = total;
            if (total != count) throw new IllegalStateException("Invalid match count");
            return new MatchOffsetIndex(textLength, count, new int[0], words, ranks);
        }
    }
}
