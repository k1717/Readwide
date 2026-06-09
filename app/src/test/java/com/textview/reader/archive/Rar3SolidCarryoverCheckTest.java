package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Rar3SolidCarryoverCheckTest {
    @Test
    public void successfulEntryAdvancesExpectedWritePosition() {
        Rar3SolidCarryoverCheck check = Rar3SolidCarryoverCheck.analyze(
                false,
                false,
                Rar3SolidSequenceTrace.EntryStatus.SUCCESS,
                false,
                0,
                true,
                4,
                4);

        assertEquals(Rar3SolidCarryoverCheck.Status.OK, check.status);
        assertEquals(4, check.expectedPositionAfter);
    }

    @Test
    public void solidTargetCannotStartWithoutInitializedDictionary() {
        Rar3SolidCarryoverCheck check = Rar3SolidCarryoverCheck.analyze(
                true,
                true,
                Rar3SolidSequenceTrace.EntryStatus.SUCCESS,
                false,
                0,
                true,
                3,
                3);

        assertEquals(Rar3SolidCarryoverCheck.Status.TARGET_STARTED_WITHOUT_DICTIONARY, check.status);
        assertTrue(check.compact().contains("TARGET_STARTED_WITHOUT_DICTIONARY"));
    }

    @Test
    public void detectsPositionAdvanceMismatch() {
        Rar3SolidCarryoverCheck check = Rar3SolidCarryoverCheck.analyze(
                false,
                true,
                Rar3SolidSequenceTrace.EntryStatus.SUCCESS,
                true,
                9,
                true,
                10,
                5);

        assertEquals(Rar3SolidCarryoverCheck.Status.POSITION_ADVANCE_MISMATCH, check.status);
        assertEquals(14, check.expectedPositionAfter);
    }

    @Test
    public void separatesCrcMismatchAfterValidCarryover() {
        Rar3SolidCarryoverCheck check = Rar3SolidCarryoverCheck.analyze(
                true,
                true,
                Rar3SolidSequenceTrace.EntryStatus.CRC_MISMATCH,
                true,
                4,
                true,
                7,
                3);

        assertEquals(Rar3SolidCarryoverCheck.Status.CRC_MISMATCH_WITH_VALID_CARRYOVER, check.status);
        assertEquals(7, check.expectedPositionAfter);
    }

    @Test
    public void gapEntriesAreNotValidated() {
        Rar3SolidCarryoverCheck check = Rar3SolidCarryoverCheck.analyze(
                true,
                true,
                Rar3SolidSequenceTrace.EntryStatus.GAP,
                true,
                4,
                true,
                4,
                -1);

        assertEquals(Rar3SolidCarryoverCheck.Status.NOT_VALIDATED, check.status);
    }
}
