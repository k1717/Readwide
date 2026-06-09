package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Absolute output-range request for a future RAR3/RAR4 VM-filter output pipeline.
 *
 * <p>RAR's real VM filter path needs to delay already-decoded bytes until the filter's target
 * range is complete, then emit the transformed range and validate CRC over the filtered bytes.
 * The current source does not wire that pipeline into {@link RarLzWindow}; this immutable request object is a
 * small, testable building block for that future queue.</p>
 */
final class RarVmQueuedFilter {
    final long outputOffset;
    final int length;
    final RarVmFilter.StandardFilter standardFilter;
    final long fileOffset;

    RarVmQueuedFilter(long outputOffset,
                      int length,
                      @NonNull RarVmFilter.StandardFilter standardFilter,
                      long fileOffset) throws IOException {
        if (outputOffset < 0) {
            throw new IOException("Invalid RAR VM filter output offset");
        }
        if (length <= 0) {
            throw new IOException("Invalid RAR VM filter output length");
        }
        if (standardFilter == null) {
            throw new IOException("Missing RAR VM standard filter type");
        }
        if (fileOffset < 0) {
            throw new IOException("Invalid RAR VM filter file offset");
        }
        long end = outputOffset + (long) length;
        if (end < outputOffset) {
            throw new IOException("RAR VM filter output range overflows");
        }
        this.outputOffset = outputOffset;
        this.length = length;
        this.standardFilter = standardFilter;
        this.fileOffset = fileOffset;
    }

    long endOffsetExclusive() {
        return outputOffset + (long) length;
    }

    boolean hasStandalonePrimitive() {
        return RarStandardFilters.hasStandalonePrimitive(standardFilter);
    }

    @NonNull
    String diagnosticSummary() {
        return "outputOffset=" + outputOffset
                + ", length=" + length
                + ", endOffset=" + endOffsetExclusive()
                + ", standardFilter=" + standardFilter.displayName
                + ", standalonePrimitive=" + hasStandalonePrimitive()
                + ", fileOffset=" + fileOffset;
    }
}
