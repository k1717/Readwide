package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Small delayed-output queue scaffold for first-party RAR3/RAR4 VM filters.
 *
 * <p>This class intentionally works on an already-buffered byte array. It is not connected to the
 * streaming archive extractor yet because the existing first-party LZ window writes bytes directly
 * to the final output stream. Wiring VM filters correctly requires a larger buffered-output layer
 * that can hold back ranges, apply filters at the exact RAR-defined timing, emit transformed bytes,
 * and validate the final filtered CRC.</p>
 */
final class RarVmFilterQueue {
    private final List<RarVmQueuedFilter> filters = new ArrayList<>();
    private long highestEndOffset;

    void add(@NonNull RarVmQueuedFilter filter) throws IOException {
        if (filter == null) {
            throw new IOException("Missing RAR VM filter queue entry");
        }
        if (!filters.isEmpty() && filter.outputOffset < highestEndOffset) {
            throw new IOException("Overlapping or out-of-order RAR VM filter range");
        }
        filters.add(filter);
        highestEndOffset = filter.endOffsetExclusive();
    }

    int size() {
        return filters.size();
    }

    boolean isEmpty() {
        return filters.isEmpty();
    }

    long highestEndOffset() {
        return highestEndOffset;
    }

    @NonNull
    List<RarVmQueuedFilter> snapshot() {
        return new ArrayList<>(filters);
    }

    /**
     * Applies queued standalone standard-filter primitives to a copy of {@code bufferedOutput}.
     *
     * @param bufferedOutput contiguous unfiltered bytes starting at {@code bufferBaseOffset}
     * @param bufferBaseOffset absolute output offset represented by {@code bufferedOutput[0]}
     */
    @NonNull
    byte[] applyStandalonePrimitivesToCopy(@NonNull byte[] bufferedOutput,
                                           long bufferBaseOffset) throws IOException {
        if (bufferBaseOffset < 0) {
            throw new IOException("Invalid RAR VM filter buffer base offset");
        }
        byte[] copy = Arrays.copyOf(bufferedOutput, bufferedOutput.length);
        applyStandalonePrimitivesInPlace(copy, bufferBaseOffset);
        return copy;
    }

    void applyStandalonePrimitivesInPlace(@NonNull byte[] bufferedOutput,
                                          long bufferBaseOffset) throws IOException {
        if (bufferedOutput == null) {
            throw new IOException("Missing RAR VM filter output buffer");
        }
        if (bufferBaseOffset < 0) {
            throw new IOException("Invalid RAR VM filter buffer base offset");
        }
        long bufferEnd = bufferBaseOffset + (long) bufferedOutput.length;
        if (bufferEnd < bufferBaseOffset) {
            throw new IOException("RAR VM filter output buffer range overflows");
        }
        for (RarVmQueuedFilter filter : filters) {
            if (filter.outputOffset < bufferBaseOffset || filter.endOffsetExclusive() > bufferEnd) {
                throw new IOException("RAR VM filter range is not fully available in delayed buffer");
            }
            int relativeOffset = checkedRelativeOffset(filter.outputOffset - bufferBaseOffset);
            RarStandardFilterApplier.applyInPlace(
                    bufferedOutput,
                    relativeOffset,
                    filter.length,
                    filter.standardFilter,
                    filter.fileOffset);
        }
    }

    @NonNull
    String diagnosticSummary() {
        if (filters.isEmpty()) {
            return "queuedFilters=0, highestEndOffset=0";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("queuedFilters=").append(filters.size())
                .append(", highestEndOffset=").append(highestEndOffset);
        for (int i = 0; i < filters.size(); i++) {
            builder.append(", filter[").append(i).append("]={")
                    .append(filters.get(i).diagnosticSummary())
                    .append('}');
        }
        return builder.toString();
    }

    private static int checkedRelativeOffset(long offset) throws IOException {
        if (offset < 0 || offset > Integer.MAX_VALUE) {
            throw new IOException("RAR VM filter range is too large for in-memory delayed buffer");
        }
        return (int) offset;
    }
}
