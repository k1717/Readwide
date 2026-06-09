package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Classifies classic-LZ CRC mismatches after match-state transitions look sane.
 *
 * <p>The check is diagnostic-only. It does not enable first-party compressed-solid extraction;
 * it points the next narrow fix toward low-distance decoding, table reuse/reset, or the generic
 * length/distance path.</p>
 */
final class Rar3ClassicLzBoundaryCheck {
    enum Status {
        NOT_APPLICABLE,
        TRANSITION_SUSPECT,
        LOW_DISTANCE_PATH,
        LOW_DISTANCE_REPEAT_PATH,
        KEEP_OLD_TABLE_PATH,
        TABLE_RESET_PATH,
        MIXED_TABLE_BOUNDARY_PATH,
        LENGTH_DISTANCE_PATH,
        VM_OR_PPMD_GAP
    }

    enum NextStep {
        NONE,
        FIX_STATE_TRANSITION,
        TRACE_LOW_DISTANCE_DECODER,
        TRACE_TABLE_KEEP_OR_RESET,
        TRACE_LENGTH_DISTANCE_DECODER,
        IMPLEMENT_VM_OR_PPMD_GAP
    }

    @NonNull final Status status;
    @NonNull final NextStep nextStep;
    @NonNull final String detail;

    private Rar3ClassicLzBoundaryCheck(@NonNull Status status,
                                       @NonNull NextStep nextStep,
                                       @NonNull String detail) {
        this.status = status;
        this.nextStep = nextStep;
        this.detail = detail;
    }

    @NonNull
    static Rar3ClassicLzBoundaryCheck analyze(
            @NonNull Rar3ClassicLzCrcMismatchAnalysis classicLz,
            @Nullable Rar3ClassicLzStateTrace.Snapshot trace) {
        if (classicLz.status == Rar3ClassicLzCrcMismatchAnalysis.Status.VM_OR_PPMD_GAP) {
            return of(Status.VM_OR_PPMD_GAP,
                    NextStep.IMPLEMENT_VM_OR_PPMD_GAP,
                    "VM/PPMd gap reached before a stable classic-LZ boundary diagnosis");
        }
        if (trace == null
                || (classicLz.status != Rar3ClassicLzCrcMismatchAnalysis.Status.CRC_MISMATCH_SINGLE_BLOCK
                && classicLz.status != Rar3ClassicLzCrcMismatchAnalysis.Status.CRC_MISMATCH_MULTI_BLOCK
                && classicLz.status != Rar3ClassicLzCrcMismatchAnalysis.Status.CRC_MISMATCH_AFTER_SIZE_MATCH)) {
            return of(Status.NOT_APPLICABLE, NextStep.NONE, "no CRC-stable classic-LZ mismatch trace");
        }
        if (trace.suspiciousTransitionCount > 0) {
            return of(Status.TRANSITION_SUSPECT,
                    NextStep.FIX_STATE_TRANSITION,
                    "old-distance/repeat-last transition suspect count=" + trace.suspiciousTransitionCount);
        }
        if (trace.lowDistanceRepeatUseCount > 0) {
            return of(Status.LOW_DISTANCE_REPEAT_PATH,
                    NextStep.TRACE_LOW_DISTANCE_DECODER,
                    "low-distance repeat was used " + trace.lowDistanceRepeatUseCount + " time(s)");
        }
        if (trace.lowDistanceDecodeCount > 0 || trace.highDistanceSlotCount > 0) {
            return of(Status.LOW_DISTANCE_PATH,
                    NextStep.TRACE_LOW_DISTANCE_DECODER,
                    "high distance slots=" + trace.highDistanceSlotCount
                            + "; low-distance nibbles=" + trace.lowDistanceDecodeCount);
        }
        if (classicLz.status == Rar3ClassicLzCrcMismatchAnalysis.Status.CRC_MISMATCH_MULTI_BLOCK) {
            if (trace.keepOldTableBlocks > 0 && trace.resetTableBlocks > 0) {
                return of(Status.MIXED_TABLE_BOUNDARY_PATH,
                        NextStep.TRACE_TABLE_KEEP_OR_RESET,
                        "multi-block mismatch crosses keep-old and reset table blocks");
            }
            if (trace.keepOldTableBlocks > 0) {
                return of(Status.KEEP_OLD_TABLE_PATH,
                        NextStep.TRACE_TABLE_KEEP_OR_RESET,
                        "multi-block mismatch used keep-old tables=" + trace.keepOldTableBlocks);
            }
            return of(Status.TABLE_RESET_PATH,
                    NextStep.TRACE_TABLE_KEEP_OR_RESET,
                    "multi-block mismatch used reset table blocks=" + trace.resetTableBlocks);
        }
        return of(Status.LENGTH_DISTANCE_PATH,
                NextStep.TRACE_LENGTH_DISTANCE_DECODER,
                "single-block mismatch without low-distance or transition suspects");
    }

    @NonNull
    String compact() {
        return status + " / " + nextStep + ": " + detail;
    }

    @NonNull
    String markdownStatus() {
        return status + " / " + nextStep;
    }

    @NonNull
    private static Rar3ClassicLzBoundaryCheck of(@NonNull Status status,
                                                 @NonNull NextStep nextStep,
                                                 @NonNull String detail) {
        return new Rar3ClassicLzBoundaryCheck(status, nextStep, detail);
    }
}
