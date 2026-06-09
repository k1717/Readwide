package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarSolidProbeFailureTest {
    @Test
    public void classifiesPpmdModelGap() {
        RarSolidProbeFailure failure = RarSolidProbeFailure.classifyMessage(
                "RAR3/RAR4 PPMd statistical model decoding is not implemented", true);

        assertEquals(RarSolidProbeFailure.Cause.PPMD_MODEL_GAP, failure.cause);
        assertEquals(RarSolidProbeFailure.NextStep.IMPLEMENT_PPMD_MODEL, failure.nextStep);
    }

    @Test
    public void classifiesVmExecutionStateGap() {
        RarSolidProbeFailure failure = RarSolidProbeFailure.classifyMessage(
                "RAR VM execution-state register state is missing", true);

        assertEquals(RarSolidProbeFailure.Cause.VM_EXECUTION_STATE_GAP, failure.cause);
        assertEquals(RarSolidProbeFailure.NextStep.IMPLEMENT_VM_STATE_DECODING, failure.nextStep);
    }

    @Test
    public void classifiesCrcMismatchAsClassicLzDecoderWork() {
        RarSolidProbeFailure failure = RarSolidProbeFailure.classifyMessage(
                "first-party unpacker decoded the payload but CRC did not match", true);

        assertEquals(RarSolidProbeFailure.Cause.CRC_MISMATCH, failure.cause);
        assertEquals(RarSolidProbeFailure.NextStep.FIX_CLASSIC_LZ_DECODER, failure.nextStep);
    }

    @Test
    public void classifiesDictionaryContinuationFailures() {
        RarSolidProbeFailure failure = RarSolidProbeFailure.classifyMessage(
                "RAR LZ window copy match distance exceeds current dictionary", false);

        assertEquals(RarSolidProbeFailure.Cause.MATCH_OR_DICTIONARY_FAILURE, failure.cause);
        assertEquals(RarSolidProbeFailure.NextStep.FIX_SOLID_DICTIONARY_CONTINUATION, failure.nextStep);
    }


    @Test
    public void classifiesCarryoverMismatchAsDictionaryWork() {
        RarSolidProbeFailure failure = RarSolidProbeFailure.classifyMessage(
                "solidTrace entries=2; carryover=POSITION_ADVANCE_MISMATCH", false);

        assertEquals(RarSolidProbeFailure.Cause.MATCH_OR_DICTIONARY_FAILURE, failure.cause);
        assertEquals(RarSolidProbeFailure.NextStep.FIX_SOLID_DICTIONARY_CONTINUATION, failure.nextStep);
    }

    @Test
    public void classifiesCrcMismatchWithValidCarryoverAsClassicLzWork() {
        RarSolidProbeFailure failure = RarSolidProbeFailure.classifyMessage(
                "solidTrace entries=2; carryover=CRC_MISMATCH_WITH_VALID_CARRYOVER", true);

        assertEquals(RarSolidProbeFailure.Cause.CRC_MISMATCH, failure.cause);
        assertEquals(RarSolidProbeFailure.NextStep.FIX_CLASSIC_LZ_DECODER, failure.nextStep);
    }

    @Test
    public void classifiesSingleBlockClassicLzTraceAsDecoderWork() {
        RarSolidProbeFailure failure = RarSolidProbeFailure.classifyMessage(
                "solidTrace entries=2; carryover=CRC_MISMATCH_WITH_VALID_CARRYOVER; classicLz=CRC_MISMATCH_SINGLE_BLOCK",
                true);

        assertEquals(RarSolidProbeFailure.Cause.CRC_MISMATCH, failure.cause);
        assertEquals(RarSolidProbeFailure.NextStep.FIX_CLASSIC_LZ_DECODER, failure.nextStep);
    }

    @Test
    public void classifiesMultiBlockClassicLzTraceAsDecoderWork() {
        RarSolidProbeFailure failure = RarSolidProbeFailure.classifyMessage(
                "solidTrace entries=2; carryover=CRC_MISMATCH_WITH_VALID_CARRYOVER; classicLz=CRC_MISMATCH_MULTI_BLOCK",
                true);

        assertEquals(RarSolidProbeFailure.Cause.CRC_MISMATCH, failure.cause);
        assertEquals(RarSolidProbeFailure.NextStep.FIX_CLASSIC_LZ_DECODER, failure.nextStep);
    }

    @Test
    public void markdownLabelContainsCauseAndNextStep() {
        RarSolidProbeFailure failure = RarSolidProbeFailure.classifyMessage(
                "compressed payload ended before the next block table", true);

        assertTrue(failure.markdownLabel().contains("BITSTREAM_ENDED"));
        assertTrue(failure.markdownLabel().contains("INVESTIGATE_IO_OR_FIXTURE"));
    }
}
