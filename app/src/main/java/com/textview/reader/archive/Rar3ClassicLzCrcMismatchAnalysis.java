package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Small diagnostic classifier for CRC mismatches after the solid dictionary looks sane.
 *
 * <p>This is intentionally not a decoder change. It narrows the next classic-LZ work item
 * without enabling compressed-solid extraction in the user-facing RAR path.</p>
 */
final class Rar3ClassicLzCrcMismatchAnalysis {
    enum Status {
        NOT_APPLICABLE,
        CARRYOVER_PROBLEM,
        SIZE_MISMATCH,
        CRC_MISMATCH_AFTER_SIZE_MATCH,
        CRC_MISMATCH_SINGLE_BLOCK,
        CRC_MISMATCH_MULTI_BLOCK,
        TABLE_OR_BITSTREAM_FAILURE,
        VM_OR_PPMD_GAP,
        UNKNOWN_FAILURE
    }

    enum NextStep {
        NONE,
        FIX_SOLID_CARRYOVER,
        FIX_CLASSIC_LZ_SIZE_STOP,
        TRACE_LENGTH_DISTANCE_STATE,
        TRACE_TABLE_REUSE_OR_BLOCK_BOUNDARIES,
        IMPLEMENT_VM_OR_PPMD_GAP,
        INVESTIGATE_BITSTREAM_OR_FIXTURE
    }

    final Status status;
    final NextStep nextStep;
    final String detail;

    private Rar3ClassicLzCrcMismatchAnalysis(@NonNull Status status,
                                             @NonNull NextStep nextStep,
                                             @NonNull String detail) {
        this.status = status;
        this.nextStep = nextStep;
        this.detail = detail;
    }

    @NonNull
    static Rar3ClassicLzCrcMismatchAnalysis analyze(
            @NonNull Rar3SolidSequenceTrace.EntryStatus entryStatus,
            @NonNull Rar3SolidCarryoverCheck carryover,
            long written,
            long outputSize,
            long actualCrc,
            long expectedCrc,
            int blocks,
            long bitsRead,
            @Nullable String detail) {
        String text = detail == null ? "" : detail;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("ppmd") || lower.contains("vm-filter") || lower.contains("vm filter")) {
            return of(Status.VM_OR_PPMD_GAP,
                    NextStep.IMPLEMENT_VM_OR_PPMD_GAP,
                    "not a classic-LZ CRC problem; first-party VM/PPMd gap reached first");
        }
        if (entryStatus == Rar3SolidSequenceTrace.EntryStatus.GAP) {
            return of(Status.TABLE_OR_BITSTREAM_FAILURE,
                    NextStep.INVESTIGATE_BITSTREAM_OR_FIXTURE,
                    "unsupported gap before CRC-stable classic-LZ output");
        }
        if (entryStatus == Rar3SolidSequenceTrace.EntryStatus.FAILED) {
            if (lower.contains("huffman") || lower.contains("table") || lower.contains("block")) {
                return of(Status.TABLE_OR_BITSTREAM_FAILURE,
                        NextStep.TRACE_TABLE_REUSE_OR_BLOCK_BOUNDARIES,
                        "table or compressed-block boundary failed before CRC comparison");
            }
            if (lower.contains("payload ended") || lower.contains("unexpected end") || lower.contains("remaining bits")) {
                return of(Status.TABLE_OR_BITSTREAM_FAILURE,
                        NextStep.INVESTIGATE_BITSTREAM_OR_FIXTURE,
                        "compressed payload ended before stable output comparison");
            }
            return of(Status.UNKNOWN_FAILURE,
                    NextStep.INVESTIGATE_BITSTREAM_OR_FIXTURE,
                    "first-party classic-LZ trace failed before CRC classification");
        }
        if (carryover.status == Rar3SolidCarryoverCheck.Status.TARGET_STARTED_WITHOUT_DICTIONARY
                || carryover.status == Rar3SolidCarryoverCheck.Status.POSITION_ADVANCE_MISMATCH
                || carryover.status == Rar3SolidCarryoverCheck.Status.WINDOW_NOT_INITIALIZED_AFTER_WRITE) {
            return of(Status.CARRYOVER_PROBLEM,
                    NextStep.FIX_SOLID_CARRYOVER,
                    carryover.compact());
        }
        if (entryStatus != Rar3SolidSequenceTrace.EntryStatus.CRC_MISMATCH) {
            return of(Status.NOT_APPLICABLE,
                    NextStep.NONE,
                    "entry did not produce a CRC mismatch");
        }
        if (written >= 0L && outputSize >= 0L && written != outputSize) {
            return of(Status.SIZE_MISMATCH,
                    NextStep.FIX_CLASSIC_LZ_SIZE_STOP,
                    "written byte count and output file size differ; written=" + written + "; outputSize=" + outputSize);
        }
        if (blocks > 1) {
            return of(Status.CRC_MISMATCH_MULTI_BLOCK,
                    NextStep.TRACE_TABLE_REUSE_OR_BLOCK_BOUNDARIES,
                    "CRC mismatch after valid carryover across " + blocks + " blocks; check table reuse/reset and block boundary handling");
        }
        if (blocks == 1) {
            return of(Status.CRC_MISMATCH_SINGLE_BLOCK,
                    NextStep.TRACE_LENGTH_DISTANCE_STATE,
                    "CRC mismatch after valid carryover in one block; check length/distance/old-distance state");
        }
        return of(Status.CRC_MISMATCH_AFTER_SIZE_MATCH,
                NextStep.TRACE_LENGTH_DISTANCE_STATE,
                "CRC mismatch after valid carryover; crc=" + hex(actualCrc) + "; expected=" + hex(expectedCrc) + "; bitsRead=" + bitsRead);
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
    private static Rar3ClassicLzCrcMismatchAnalysis of(@NonNull Status status,
                                                       @NonNull NextStep nextStep,
                                                       @NonNull String detail) {
        return new Rar3ClassicLzCrcMismatchAnalysis(status, nextStep, detail);
    }

    @NonNull
    private static String hex(long crc) {
        return crc < 0L ? "-" : String.format(Locale.US, "0x%08x", crc & 0xffffffffL);
    }
}
