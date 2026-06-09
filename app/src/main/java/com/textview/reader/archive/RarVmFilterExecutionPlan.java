package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.List;

/**
 * Immutable execution plan for a parsed RAR3/RAR4 VM standard-filter marker.
 *
 * <p>RAR's VM-filter marker does not by itself give the first-party decoder enough safe state to
 * wire live extraction: the real VM program/register state determines the filtered output length
 * and file offset, and the existing LZ window still writes directly to the final stream. This plan
 * object is therefore deliberately narrow. It validates the metadata needed before a parsed filter
 * may be converted into a delayed-output queue entry, while keeping normal compressed RAR on the
 * libarchive-primary path and keeping first-party VM wiring disabled until the output pipeline is
 * proven by tests.</p>
 */
final class RarVmFilterExecutionPlan {
    final long outputOffset;
    final int outputLength;
    final long fileOffset;
    final RarVmFilter.StandardFilter standardFilter;
    final int codeLength;
    final long codeCrc32;

    private RarVmFilterExecutionPlan(@NonNull RarVmFilter filter,
                                     int outputLength,
                                     long fileOffset) {
        this.outputOffset = filter.outputOffset;
        this.outputLength = outputLength;
        this.fileOffset = fileOffset;
        this.standardFilter = filter.standardFilter;
        this.codeLength = filter.codeLength();
        this.codeCrc32 = filter.codeCrc32;
    }

    @NonNull
    static Builder fromParsedFilter(@NonNull RarVmFilter filter) throws IOException {
        if (filter == null) {
            throw new IOException("Missing RAR VM filter metadata");
        }
        return new Builder(filter);
    }

    long endOffsetExclusive() {
        return outputOffset + (long) outputLength;
    }

    boolean hasStandalonePrimitive() {
        return RarStandardFilters.hasStandalonePrimitive(standardFilter);
    }

    void validateTargetNotEmitted(long emittedOffset) throws IOException {
        if (emittedOffset < 0) {
            throw new IOException("Invalid RAR VM emitted output offset");
        }
        if (outputOffset < emittedOffset) {
            throw new IOException("RAR VM filter execution plan targets already emitted output");
        }
    }

    @NonNull
    RarVmQueuedFilter toQueuedFilter() throws IOException {
        return new RarVmQueuedFilter(outputOffset, outputLength, standardFilter, fileOffset);
    }

    @NonNull
    static RarVmFilterQueue toQueue(@NonNull List<RarVmFilterExecutionPlan> plans)
            throws IOException {
        if (plans == null) {
            throw new IOException("Missing RAR VM filter execution plans");
        }
        RarVmFilterQueue queue = new RarVmFilterQueue();
        for (RarVmFilterExecutionPlan plan : plans) {
            if (plan == null) {
                throw new IOException("Missing RAR VM filter execution plan");
            }
            queue.add(plan.toQueuedFilter());
        }
        return queue;
    }

    @NonNull
    String diagnosticSummary() {
        return "outputOffset=" + outputOffset
                + ", outputLength=" + outputLength
                + ", endOffset=" + endOffsetExclusive()
                + ", fileOffset=" + fileOffset
                + ", standardFilter=" + standardFilter.displayName
                + ", standalonePrimitive=" + hasStandalonePrimitive()
                + ", codeLength=" + codeLength
                + ", codeCrc32=0x" + hexWord(codeCrc32);
    }

    static final class Builder {
        private final RarVmFilter filter;
        private Integer outputLength;
        private Long fileOffset;
        private int maxRangeBytes = RarFilteredOutputBuffer.DEFAULT_MAX_DELAYED_BYTES;

        private Builder(@NonNull RarVmFilter filter) {
            this.filter = filter;
        }

        @NonNull
        Builder outputLength(int outputLength) {
            this.outputLength = outputLength;
            return this;
        }

        @NonNull
        Builder fileOffset(long fileOffset) {
            this.fileOffset = fileOffset;
            return this;
        }

        @NonNull
        Builder maxRangeBytes(int maxRangeBytes) {
            this.maxRangeBytes = maxRangeBytes;
            return this;
        }

        @NonNull
        RarVmFilterExecutionPlan build() throws IOException {
            if (outputLength == null) {
                throw new IOException("Missing RAR VM filter output length from VM state");
            }
            if (fileOffset == null) {
                throw new IOException("Missing RAR VM filter file offset from VM state");
            }
            if (maxRangeBytes <= 0) {
                throw new IOException("Invalid RAR VM filter execution-plan memory limit");
            }
            if (filter.outputOffset < 0) {
                throw new IOException("Invalid RAR VM filter output offset");
            }
            int length = outputLength;
            if (length <= 0) {
                throw new IOException("Invalid RAR VM filter output length");
            }
            if (length > maxRangeBytes) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR VM filter range exceeds first-party execution-plan memory limit");
            }
            long end = filter.outputOffset + (long) length;
            if (end < filter.outputOffset) {
                throw new IOException("RAR VM filter execution-plan output range overflows");
            }
            long offset = fileOffset;
            if (offset < 0) {
                throw new IOException("Invalid RAR VM filter file offset");
            }
            if (!filter.hasStandaloneStandardFilterPrimitive()) {
                throw Rar3FirstPartyGap.vmFilter(filter);
            }
            return new RarVmFilterExecutionPlan(filter, length, offset);
        }
    }

    private static String hexWord(long value) {
        String text = Long.toHexString(value & 0xffff_ffffL);
        StringBuilder builder = new StringBuilder();
        for (int i = text.length(); i < 8; i++) builder.append('0');
        return builder.append(text).toString();
    }
}
