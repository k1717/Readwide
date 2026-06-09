package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/** Stable diagnostic buckets for compressed-solid first-party probe results. */
final class RarSolidProbeFailure {
    enum Cause {
        NONE,
        NO_CANDIDATE,
        SKIPPED_TOO_LARGE,
        RETURNED_FALSE,
        PPMD_MODEL_GAP,
        VM_FILTER_GAP,
        VM_EXECUTION_STATE_GAP,
        CRC_MISMATCH,
        SIZE_MISMATCH,
        TABLE_DECODE_FAILURE,
        BITSTREAM_ENDED,
        MATCH_OR_DICTIONARY_FAILURE,
        BLOCK_SEQUENCE_FAILURE,
        REFERENCE_UNAVAILABLE,
        REFERENCE_FAILED,
        REFERENCE_MISMATCH,
        CANCELLED,
        IO_FAILURE,
        UNKNOWN_FAILURE
    }

    enum NextStep {
        NONE,
        KEEP_LIBARCHIVE_PRIMARY,
        IMPLEMENT_PPMD_MODEL,
        IMPLEMENT_VM_STATE_DECODING,
        FIX_CLASSIC_LZ_DECODER,
        FIX_SOLID_DICTIONARY_CONTINUATION,
        INVESTIGATE_REFERENCE_OUTPUT,
        SHRINK_OR_SKIP_ANDROID_UNSAFE_FIXTURE,
        INVESTIGATE_IO_OR_FIXTURE,
        ADD_SMALLER_FIXTURE
    }

    final Cause cause;
    final NextStep nextStep;
    final String detail;

    private RarSolidProbeFailure(@NonNull Cause cause,
                                 @NonNull NextStep nextStep,
                                 @NonNull String detail) {
        this.cause = cause;
        this.nextStep = nextStep;
        this.detail = detail;
    }

    @NonNull
    static RarSolidProbeFailure classify(@NonNull RarSolidFirstPartyProbe.Result result) {
        if (result.firstPartyStatus == RarSolidFirstPartyProbe.FirstPartyStatus.SUCCESS) {
            if (result.comparisonStatus == RarSolidFirstPartyProbe.ComparisonStatus.MISMATCH) {
                return of(Cause.REFERENCE_MISMATCH,
                        NextStep.INVESTIGATE_REFERENCE_OUTPUT,
                        "first-party output differs from reference size or CRC");
            }
            if (result.comparisonStatus == RarSolidFirstPartyProbe.ComparisonStatus.REFERENCE_FAILED) {
                return of(Cause.REFERENCE_FAILED,
                        NextStep.INVESTIGATE_REFERENCE_OUTPUT,
                        "first-party decoded but reference extraction failed");
            }
            if (result.comparisonStatus == RarSolidFirstPartyProbe.ComparisonStatus.REFERENCE_UNAVAILABLE) {
                return of(Cause.REFERENCE_UNAVAILABLE,
                        NextStep.KEEP_LIBARCHIVE_PRIMARY,
                        "first-party decoded but libarchive reference was unavailable");
            }
            return of(Cause.NONE, NextStep.NONE, "probe did not expose a first-party failure");
        }
        if (result.firstPartyStatus == RarSolidFirstPartyProbe.FirstPartyStatus.NO_CANDIDATE) {
            return of(Cause.NO_CANDIDATE,
                    NextStep.ADD_SMALLER_FIXTURE,
                    "no eligible RAR3/RAR4 classic-LZ solid target was found");
        }
        if (result.firstPartyStatus == RarSolidFirstPartyProbe.FirstPartyStatus.SKIPPED_TOO_LARGE) {
            return of(Cause.SKIPPED_TOO_LARGE,
                    NextStep.SHRINK_OR_SKIP_ANDROID_UNSAFE_FIXTURE,
                    "probe skipped by Android-safe size guard");
        }
        if (result.firstPartyStatus == RarSolidFirstPartyProbe.FirstPartyStatus.RETURNED_FALSE) {
            return of(Cause.RETURNED_FALSE,
                    NextStep.FIX_SOLID_DICTIONARY_CONTINUATION,
                    "first-party solid sequencing did not select or complete the requested target");
        }
        return classifyMessage(result.detail, result.firstPartyStatus == RarSolidFirstPartyProbe.FirstPartyStatus.GAP);
    }

    @NonNull
    static RarSolidProbeFailure classifyMessage(@Nullable String message, boolean unsupportedGap) {
        String text = message == null ? "" : message;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("ppmd statistical model") || lower.contains("ppmd model")) {
            return of(Cause.PPMD_MODEL_GAP,
                    NextStep.IMPLEMENT_PPMD_MODEL,
                    "RAR3/RAR4 PPMd statistical model is still a first-party gap");
        }
        if (lower.contains("register state") || lower.contains("execution-state")) {
            return of(Cause.VM_EXECUTION_STATE_GAP,
                    NextStep.IMPLEMENT_VM_STATE_DECODING,
                    "RAR VM register/state decoding is needed before filtered output can be planned");
        }
        if (lower.contains("vm filters") || lower.contains("vm-filter")) {
            return of(Cause.VM_FILTER_GAP,
                    NextStep.IMPLEMENT_VM_STATE_DECODING,
                    "RAR VM filter execution remains a first-party gap");
        }
        if (lower.contains("classiclz=crc_mismatch_single_block")
                || lower.contains("classiclz=crc_mismatch_after_size_match")) {
            return of(Cause.CRC_MISMATCH,
                    NextStep.FIX_CLASSIC_LZ_DECODER,
                    "single-block classic-LZ solid output has valid carryover but mismatched CRC; trace length/distance state");
        }
        if (lower.contains("classiclz=crc_mismatch_multi_block")) {
            return of(Cause.CRC_MISMATCH,
                    NextStep.FIX_CLASSIC_LZ_DECODER,
                    "multi-block classic-LZ solid output has valid carryover but mismatched CRC; trace table reuse/reset and block boundaries");
        }
        if (lower.contains("classiclz=size_mismatch")) {
            return of(Cause.SIZE_MISMATCH,
                    NextStep.FIX_CLASSIC_LZ_DECODER,
                    "classic-LZ solid output size accounting differs from decoded bytes");
        }
        if (lower.contains("target_started_without_dictionary")
                || lower.contains("position_advance_mismatch")
                || lower.contains("window_not_initialized_after_write")) {
            return of(Cause.MATCH_OR_DICTIONARY_FAILURE,
                    NextStep.FIX_SOLID_DICTIONARY_CONTINUATION,
                    "solid dictionary carryover did not advance as expected");
        }
        if (lower.contains("crc_mismatch_with_valid_carryover")) {
            return of(Cause.CRC_MISMATCH,
                    NextStep.FIX_CLASSIC_LZ_DECODER,
                    "CRC differs even though solid dictionary position carryover looks valid");
        }
        if (lower.contains("crc did not match") || lower.contains("crc mismatch")) {
            return of(Cause.CRC_MISMATCH,
                    NextStep.FIX_CLASSIC_LZ_DECODER,
                    "decoded bytes reached output but CRC differs from the archive header");
        }
        if (lower.contains("declared unpacked size") || lower.contains("did not reach")) {
            return of(Cause.SIZE_MISMATCH,
                    NextStep.FIX_CLASSIC_LZ_DECODER,
                    "first-party decoder stopped before or after the declared unpacked size");
        }
        if (lower.contains("payload ended") || lower.contains("remaining bits") || lower.contains("unexpected end")) {
            return of(Cause.BITSTREAM_ENDED,
                    NextStep.INVESTIGATE_IO_OR_FIXTURE,
                    "compressed payload ended before a complete first-party decode");
        }
        if (lower.contains("huffman") || lower.contains("table") || lower.contains("decode symbols")) {
            return of(Cause.TABLE_DECODE_FAILURE,
                    NextStep.FIX_CLASSIC_LZ_DECODER,
                    "RAR3/RAR4 table/Huffman decode failed before output could be trusted");
        }
        if (lower.contains("distance") || lower.contains("dictionary") || lower.contains("copy match") || lower.contains("window")) {
            return of(Cause.MATCH_OR_DICTIONARY_FAILURE,
                    NextStep.FIX_SOLID_DICTIONARY_CONTINUATION,
                    "LZ match or solid dictionary continuation failed");
        }
        if (lower.contains("block did not end") || lower.contains("block count")) {
            return of(Cause.BLOCK_SEQUENCE_FAILURE,
                    NextStep.FIX_CLASSIC_LZ_DECODER,
                    "compressed block sequencing did not end cleanly");
        }
        if (lower.contains("cancelled")) {
            return of(Cause.CANCELLED,
                    NextStep.NONE,
                    "probe was cancelled cooperatively");
        }
        if (!unsupportedGap && (lower.contains("ioexception") || lower.contains("source volume") || lower.contains("create") || lower.contains("read length"))) {
            return of(Cause.IO_FAILURE,
                    NextStep.INVESTIGATE_IO_OR_FIXTURE,
                    "I/O or fixture-source failure before reliable decoder diagnosis");
        }
        return of(Cause.UNKNOWN_FAILURE,
                unsupportedGap ? NextStep.KEEP_LIBARCHIVE_PRIMARY : NextStep.INVESTIGATE_IO_OR_FIXTURE,
                text.length() == 0 ? "unclassified first-party solid probe failure" : text);
    }

    @NonNull
    String markdownLabel() {
        return cause + " / " + nextStep;
    }

    @NonNull
    private static RarSolidProbeFailure of(@NonNull Cause cause,
                                           @NonNull NextStep nextStep,
                                           @NonNull String detail) {
        return new RarSolidProbeFailure(cause, nextStep, detail);
    }
}
