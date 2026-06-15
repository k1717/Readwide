package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.Arrays;

/**
 * Bounded newest-first decoded-symbol history for the evolving RAR3/RAR4 PPMd model.
 *
 * <p>Earlier passes stored the last symbols as a raw array inside {@link RarPpmdModel}. That was
 * enough for order-1/order-2 diagnostics, but it made successor traversal easy to desynchronize
 * from the history used by SEE selection and update. This class is intentionally small: it keeps
 * byte symbols in newest-first order, validates test seeds, and exposes explicit newest/older
 * accessors for context lookup. The full live PPMd decoder will reuse the same history object when
 * fixture-driven decoding is enabled.</p>
 */
final class RarPpmdSymbolHistory {
    private static final int DEFAULT_CAPACITY = 16;

    @NonNull private final int[] symbols;
    private int count;

    RarPpmdSymbolHistory() {
        this(DEFAULT_CAPACITY);
    }

    RarPpmdSymbolHistory(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("PPMd symbol history capacity must be positive: " + capacity);
        }
        symbols = new int[capacity];
        Arrays.fill(symbols, -1);
    }

    void remember(int symbol) throws IOException {
        validateSymbol(symbol);
        int copy = Math.min(count, symbols.length - 1);
        if (copy > 0) {
            System.arraycopy(symbols, 0, symbols, 1, copy);
        }
        symbols[0] = symbol & 0xff;
        if (count < symbols.length) count++;
    }

    int count() {
        return count;
    }

    int newest() {
        return symbolAt(0);
    }

    int older() {
        return symbolAt(1);
    }

    int symbolAt(int index) {
        if (index < 0 || index >= count) return -1;
        return symbols[index];
    }

    boolean hasAtLeast(int requiredCount) {
        return count >= requiredCount;
    }

    @NonNull
    int[] snapshotForTest() {
        int[] copy = new int[count];
        System.arraycopy(symbols, 0, copy, 0, count);
        return copy;
    }

    @NonNull
    String diagnostic() {
        StringBuilder builder = new StringBuilder();
        builder.append("count=").append(count).append("; newestFirst=");
        builder.append('[');
        for (int i = 0; i < count; i++) {
            if (i > 0) builder.append(',');
            builder.append(symbols[i]);
        }
        builder.append(']');
        return builder.toString();
    }

    private static void validateSymbol(int symbol) throws IOException {
        if (symbol < 0 || symbol > 255) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd history symbol is out of byte range: " + symbol);
        }
    }
}
