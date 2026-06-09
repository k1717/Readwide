package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class RarSolidProbeSummaryTest {
    @Test
    public void aggregatesFailureCausesAndNextSteps() {
        RarSolidProbeFailure ppmd = RarSolidProbeFailure.classifyMessage(
                "RAR3/RAR4 PPMd statistical model decoding is not implemented", true);
        RarSolidProbeFailure vm = RarSolidProbeFailure.classifyMessage(
                "RAR VM execution-state register state is missing", true);
        RarSolidProbeFailure crc = RarSolidProbeFailure.classifyMessage(
                "CRC mismatch after first-party decode", false);

        RarSolidProbeSummary summary = RarSolidProbeSummary.fromFailuresForTest(Arrays.asList(ppmd, vm, ppmd, crc));

        assertEquals(4, summary.total);
        assertEquals(4, summary.firstPartyFailure);
        assertEquals(2, summary.count(RarSolidProbeFailure.Cause.PPMD_MODEL_GAP));
        assertEquals(1, summary.count(RarSolidProbeFailure.Cause.VM_EXECUTION_STATE_GAP));
        assertEquals(RarSolidProbeFailure.Cause.PPMD_MODEL_GAP, summary.dominantCause);
        assertEquals(RarSolidProbeFailure.NextStep.IMPLEMENT_PPMD_MODEL, summary.dominantNextStep);
    }

    @Test
    public void markdownKeepsLibarchivePrimaryWarning() {
        RarSolidProbeFailure failure = RarSolidProbeFailure.classifyMessage(
                "copy match distance exceeds dictionary window", false);

        String markdown = RarSolidProbeSummary.fromFailuresForTest(Arrays.asList(failure)).toMarkdown();

        assertTrue(markdown.contains("libarchive remains the primary backend"));
        assertTrue(markdown.contains("MATCH_OR_DICTIONARY_FAILURE"));
        assertTrue(markdown.contains("FIX_SOLID_DICTIONARY_CONTINUATION"));
    }
}
