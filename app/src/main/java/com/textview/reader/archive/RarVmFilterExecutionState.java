package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Small immutable holder for the VM-derived state needed to execute a parsed RAR3/RAR4
 * standard filter.
 *
 * <p>The parsed VM bytecode signature alone is not enough to safely wire filtered extraction. A
 * real RAR VM run also provides the target output length and file-relative offset via register
 * state. The first-party decoder still does not implement that VM register/state decoding; this
 * object only models the already-decoded values so the later live path has a narrow validation
 * boundary. Normal compressed RAR remains libarchive-primary.</p>
 */
final class RarVmFilterExecutionState {
    final int outputLength;
    final long fileOffset;
    final int maxRangeBytes;

    private RarVmFilterExecutionState(int outputLength,
                                      long fileOffset,
                                      int maxRangeBytes) throws IOException {
        if (outputLength <= 0) {
            throw new IOException("Invalid RAR VM filter output length from VM state");
        }
        if (fileOffset < 0) {
            throw new IOException("Invalid RAR VM filter file offset from VM state");
        }
        if (maxRangeBytes <= 0) {
            throw new IOException("Invalid RAR VM filter execution-state memory limit");
        }
        this.outputLength = outputLength;
        this.fileOffset = fileOffset;
        this.maxRangeBytes = maxRangeBytes;
    }

    @NonNull
    static RarVmFilterExecutionState knownStandardFilterRange(int outputLength,
                                                              long fileOffset)
            throws IOException {
        return knownStandardFilterRange(
                outputLength,
                fileOffset,
                RarFilteredOutputBuffer.DEFAULT_MAX_DELAYED_BYTES);
    }

    @NonNull
    static RarVmFilterExecutionState knownStandardFilterRange(int outputLength,
                                                              long fileOffset,
                                                              int maxRangeBytes)
            throws IOException {
        return new RarVmFilterExecutionState(outputLength, fileOffset, maxRangeBytes);
    }

    @NonNull
    RarVmFilterExecutionPlan toPlan(@NonNull RarVmFilter filter) throws IOException {
        return RarVmFilterExecutionPlan.fromParsedFilter(filter)
                .outputLength(outputLength)
                .fileOffset(fileOffset)
                .maxRangeBytes(maxRangeBytes)
                .build();
    }
}