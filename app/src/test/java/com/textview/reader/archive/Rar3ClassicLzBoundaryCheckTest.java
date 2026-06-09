package com.textview.reader.archive;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class Rar3ClassicLzBoundaryCheckTest {
    @Test
    public void analyze_prioritizesLowDistanceRepeat() {
        Rar3ClassicLzCrcMismatchAnalysis classicLz = Rar3ClassicLzCrcMismatchAnalysis.analyze(
                Rar3SolidSequenceTrace.EntryStatus.CRC_MISMATCH,
                carryoverOk(),
                4,
                4,
                0x11111111L,
                0x22222222L,
                1,
                80,
                "decoded but CRC mismatch");
        Rar3ClassicLzStateTrace.Snapshot trace = snapshot(1, 1, 1, 0, 1, 0);

        Rar3ClassicLzBoundaryCheck result = Rar3ClassicLzBoundaryCheck.analyze(classicLz, trace);

        assertEquals(Rar3ClassicLzBoundaryCheck.Status.LOW_DISTANCE_REPEAT_PATH, result.status);
        assertEquals(Rar3ClassicLzBoundaryCheck.NextStep.TRACE_LOW_DISTANCE_DECODER, result.nextStep);
    }

    @Test
    public void analyze_multiBlockKeepOldTable() {
        Rar3ClassicLzCrcMismatchAnalysis classicLz = Rar3ClassicLzCrcMismatchAnalysis.analyze(
                Rar3SolidSequenceTrace.EntryStatus.CRC_MISMATCH,
                carryoverOk(),
                4,
                4,
                0x11111111L,
                0x22222222L,
                2,
                160,
                "decoded but CRC mismatch");
        Rar3ClassicLzStateTrace.Snapshot trace = snapshot(0, 0, 0, 2, 0, 0);

        Rar3ClassicLzBoundaryCheck result = Rar3ClassicLzBoundaryCheck.analyze(classicLz, trace);

        assertEquals(Rar3ClassicLzBoundaryCheck.Status.KEEP_OLD_TABLE_PATH, result.status);
        assertEquals(Rar3ClassicLzBoundaryCheck.NextStep.TRACE_TABLE_KEEP_OR_RESET, result.nextStep);
    }

    @Test
    public void analyze_transitionSuspectWinsBeforeBoundary() {
        Rar3ClassicLzCrcMismatchAnalysis classicLz = Rar3ClassicLzCrcMismatchAnalysis.analyze(
                Rar3SolidSequenceTrace.EntryStatus.CRC_MISMATCH,
                carryoverOk(),
                4,
                4,
                0x11111111L,
                0x22222222L,
                1,
                80,
                "decoded but CRC mismatch");
        Rar3ClassicLzStateTrace.Snapshot trace = snapshot(1, 1, 1, 0, 1, 2);

        Rar3ClassicLzBoundaryCheck result = Rar3ClassicLzBoundaryCheck.analyze(classicLz, trace);

        assertEquals(Rar3ClassicLzBoundaryCheck.Status.TRANSITION_SUSPECT, result.status);
        assertEquals(Rar3ClassicLzBoundaryCheck.NextStep.FIX_STATE_TRANSITION, result.nextStep);
    }

    private static Rar3SolidCarryoverCheck carryoverOk() {
        return Rar3SolidCarryoverCheck.analyze(
                true,
                true,
                Rar3SolidSequenceTrace.EntryStatus.CRC_MISMATCH,
                true,
                10,
                true,
                14,
                4);
    }

    private static Rar3ClassicLzStateTrace.Snapshot snapshot(int highDistance,
                                                            int lowDistance,
                                                            int lowRepeat,
                                                            int keepOld,
                                                            int reset,
                                                            int transitionSuspect) {
        return new Rar3ClassicLzStateTrace.Snapshot(
                Collections.<Rar3ClassicLzStateTrace.BlockTrace>emptyList(),
                Collections.<String>emptyList(),
                0,
                1,
                0,
                0,
                0,
                0,
                1,
                64,
                8,
                highDistance,
                lowDistance,
                lowRepeat,
                keepOld,
                reset,
                0,
                0,
                transitionSuspect,
                "");
    }
}
