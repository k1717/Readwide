package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Small adapter from validated VM-filter execution plans to the delayed filtered-output buffer.
 *
 * <p>The parser can identify standard-filter bytecode signatures, but the real RAR VM
 * program/register state still decides the target length and file offset. This helper keeps live
 * extraction disabled and requires an immutable {@link RarVmFilterExecutionPlan} before anything
 * is queued into the delayed-output scaffold.</p>
 */
final class RarVmFilterPipeline {
    private final RarFilteredOutputBuffer output;

    RarVmFilterPipeline(@NonNull RarFilteredOutputBuffer output) {
        if (output == null) {
            throw new IllegalArgumentException("Missing RAR VM filtered output buffer");
        }
        this.output = output;
    }

    void queueStandaloneFilter(@NonNull RarVmFilter filter,
                               int outputLength,
                               long fileOffset) throws IOException {
        queueExecutionPlan(RarVmFilterExecutionPlan.fromParsedFilter(filter)
                .outputLength(outputLength)
                .fileOffset(fileOffset)
                .build());
    }

    void queueExecutionPlan(@NonNull RarVmFilterExecutionPlan plan) throws IOException {
        if (plan == null) {
            throw new IOException("Missing RAR VM filter execution plan");
        }
        plan.validateTargetNotEmitted(output.emittedOffset());
        output.queueFilter(plan.toQueuedFilter());
    }
}
