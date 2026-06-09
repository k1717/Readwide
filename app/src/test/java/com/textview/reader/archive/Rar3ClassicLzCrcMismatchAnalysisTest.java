package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Rar3ClassicLzCrcMismatchAnalysisTest {
    @Test
    public void separatesCarryoverProblemsFromClassicLzCrcWork() {
        Rar3SolidCarryoverCheck carryover = Rar3SolidCarryoverCheck.analyze(
                true,
                true,
                Rar3SolidSequenceTrace.EntryStatus.CRC_MISMATCH,
                false,
                0,
                true,
                10,
                10);

        Rar3ClassicLzCrcMismatchAnalysis analysis = Rar3ClassicLzCrcMismatchAnalysis.analyze(
                Rar3SolidSequenceTrace.EntryStatus.CRC_MISMATCH,
                carryover,
                10,
                10,
                0x11111111L,
                0x22222222L,
                1,
                128,
                "decoded but CRC mismatch");

        assertEquals(Rar3ClassicLzCrcMismatchAnalysis.Status.CARRYOVER_PROBLEM, analysis.status);
        assertEquals(Rar3ClassicLzCrcMismatchAnalysis.NextStep.FIX_SOLID_CARRYOVER, analysis.nextStep);
    }

    @Test
    public void singleBlockCrcMismatchPointsToLengthDistanceState() {
        Rar3SolidCarryoverCheck carryover = Rar3SolidCarryoverCheck.analyze(
                true,
                true,
                Rar3SolidSequenceTrace.EntryStatus.CRC_MISMATCH,
                true,
                4,
                true,
                9,
                5);

        Rar3ClassicLzCrcMismatchAnalysis analysis = Rar3ClassicLzCrcMismatchAnalysis.analyze(
                Rar3SolidSequenceTrace.EntryStatus.CRC_MISMATCH,
                carryover,
                5,
                5,
                0x11111111L,
                0x22222222L,
                1,
                256,
                "decoded but CRC mismatch");

        assertEquals(Rar3ClassicLzCrcMismatchAnalysis.Status.CRC_MISMATCH_SINGLE_BLOCK, analysis.status);
        assertEquals(Rar3ClassicLzCrcMismatchAnalysis.NextStep.TRACE_LENGTH_DISTANCE_STATE, analysis.nextStep);
        assertTrue(analysis.compact().contains("length/distance"));
    }

    @Test
    public void multiBlockCrcMismatchPointsToTableReuseOrBlockBoundaries() {
        Rar3SolidCarryoverCheck carryover = Rar3SolidCarryoverCheck.analyze(
                true,
                true,
                Rar3SolidSequenceTrace.EntryStatus.CRC_MISMATCH,
                true,
                4,
                true,
                12,
                8);

        Rar3ClassicLzCrcMismatchAnalysis analysis = Rar3ClassicLzCrcMismatchAnalysis.analyze(
                Rar3SolidSequenceTrace.EntryStatus.CRC_MISMATCH,
                carryover,
                8,
                8,
                0x11111111L,
                0x22222222L,
                3,
                1024,
                "decoded but CRC mismatch");

        assertEquals(Rar3ClassicLzCrcMismatchAnalysis.Status.CRC_MISMATCH_MULTI_BLOCK, analysis.status);
        assertEquals(Rar3ClassicLzCrcMismatchAnalysis.NextStep.TRACE_TABLE_REUSE_OR_BLOCK_BOUNDARIES, analysis.nextStep);
    }

    @Test
    public void ppmdOrVmGapIsNotTreatedAsClassicLzCrcMismatch() {
        Rar3SolidCarryoverCheck carryover = Rar3SolidCarryoverCheck.analyze(
                true,
                true,
                Rar3SolidSequenceTrace.EntryStatus.GAP,
                true,
                4,
                true,
                4,
                -1);

        Rar3ClassicLzCrcMismatchAnalysis analysis = Rar3ClassicLzCrcMismatchAnalysis.analyze(
                Rar3SolidSequenceTrace.EntryStatus.GAP,
                carryover,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                "RAR VM-filter execution-state gap");

        assertEquals(Rar3ClassicLzCrcMismatchAnalysis.Status.VM_OR_PPMD_GAP, analysis.status);
        assertEquals(Rar3ClassicLzCrcMismatchAnalysis.NextStep.IMPLEMENT_VM_OR_PPMD_GAP, analysis.nextStep);
    }
}
