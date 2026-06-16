package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.util.List;

public class Rar3PpmdLiveDiagnosticProbeTest {

    // Numeric assertions against the retired pre-rewrite diagnostic
    // PPMd skeleton were removed. The live PPMd var.H engine is CRC-verified in
    // Rar3PpmdEngineFixtureProbeTest; the probe class itself is not referenced by production code.



    @Test
    public void directTargetFixtureLiveProbeRecordsBothEntriesWhenProvided() throws Exception {
        File fixture = directFixture();
        List<Rar3PpmdLiveDiagnosticProbe.Row> rows =
                Rar3PpmdLiveDiagnosticProbe.probeArchive(fixture, 32);

        assertEquals(2, rows.size());
        assertEquals("testfile.png", rows.get(0).path);
        assertEquals("testfile.jpg", rows.get(1).path);
        assertTrue(rows.get(0).header.isPpmd());
        assertTrue(rows.get(1).header.isPpmd());
        assertFalse(rows.get(0).header.keepOldTable());
        assertTrue(rows.get(1).header.keepOldTable());
        assertTrue(rows.get(0).decodedSymbols >= 0);
        assertTrue(rows.get(1).decodedSymbols >= 0);
        assertTrue(rows.get(0).diagnostic().contains("boundaryType"));
        assertTrue(rows.get(1).diagnostic().contains("boundaryType"));
        assertFalse(rows.get(0).traceRows.isEmpty());
        assertFalse(rows.get(1).traceRows.isEmpty());
        assertTrue(rows.get(0).lastTraceDiagnostic().contains("rangeAfter"));
        assertTrue(rows.get(1).lastTraceDiagnostic().contains("decodeTraceAfter"));
    }

    @Test
    public void directTargetFixtureVariantProbeComparesRootAndSeeWhenProvided() throws Exception {
        File fixture = directFixture();
        List<Rar3PpmdLiveDiagnosticProbe.VariantReport> reports =
                Rar3PpmdLiveDiagnosticProbe.probeArchiveVariants(fixture, 32);

        assertEquals(21, reports.size());
        boolean sawOrder0 = false;
        boolean sawPrimaryRoot = false;
        boolean sawPrimaryRootOnly = false;
        boolean sawFixedSee = false;
        boolean sawPrimaryPolicyVariant = false;
        boolean sawCandidateSee = false;
        boolean sawCursorLoop = false;
        boolean sawCursorOrderFall = false;
        boolean sawCursorLoopTerminal = false;
        boolean sawPendingSuccessor = false;
        boolean sawCreateSuccessors = false;
        boolean sawCreateSuccessorsPendingSeed = false;
        boolean sawCreateSuccessorsHistorySeed = false;
        for (Rar3PpmdLiveDiagnosticProbe.VariantReport report : reports) {
            assertEquals(2, report.rows.size());
            assertTrue(report.diagnostic().contains("variant="));
            if ("order0-only".equals(report.variantName)) sawOrder0 = true;
            if ("rar-primary-root".equals(report.variantName)) sawPrimaryRoot = true;
            if ("rar-primary-root-only".equals(report.variantName)) sawPrimaryRootOnly = true;
            if ("fixed-see-1".equals(report.variantName)) sawFixedSee = true;
            if ("rar-primary-root-light-update".equals(report.variantName)) sawPrimaryPolicyVariant = true;
            if ("candidate-see-early-low".equals(report.variantName)) sawCandidateSee = true;
            if ("rar-primary-root-cursor-loop".equals(report.variantName)) sawCursorLoop = true;
            if ("rar-primary-root-cursor-orderfall".equals(report.variantName)) sawCursorOrderFall = true;
            if ("rar-primary-root-cursor-loop-terminal".equals(report.variantName)) sawCursorLoopTerminal = true;
            if ("rar-primary-root-pending-successor".equals(report.variantName)) sawPendingSuccessor = true;
            if ("rar-primary-root-create-successors".equals(report.variantName)) sawCreateSuccessors = true;
            if ("rar-primary-root-create-successors-pending-seed".equals(report.variantName)) sawCreateSuccessorsPendingSeed = true;
            if ("rar-primary-root-create-successors-history-seed".equals(report.variantName)) sawCreateSuccessorsHistorySeed = true;
            assertTrue(report.diagnostic().contains("unpackedProgress"));
        }
        assertTrue(sawOrder0);
        assertTrue(sawPrimaryRoot);
        assertTrue(sawPrimaryRootOnly);
        assertTrue(sawFixedSee);
        assertTrue(sawPrimaryPolicyVariant);
        assertTrue(sawCandidateSee);
        assertTrue(sawCursorLoop);
        assertTrue(sawCursorOrderFall);
        assertTrue(sawCursorLoopTerminal);
        assertTrue(sawPendingSuccessor);
        assertTrue(sawCreateSuccessors);
        assertTrue(sawCreateSuccessorsPendingSeed);
        assertTrue(sawCreateSuccessorsHistorySeed);
    }

    private static File directFixture() {
        String path = System.getProperty("textview.rar3SolidCbrFixture");
        if (path == null || path.trim().length() == 0) {
            path = System.getenv("TEXTVIEW_RAR3_SOLID_CBR_FIXTURE");
        }
        assumeTrue("Direct RAR3 solid CBR fixture not provided",
                path != null && path.trim().length() > 0);
        File fixture = new File(path);
        assumeTrue("Direct RAR3 solid CBR fixture missing: " + fixture.getAbsolutePath(), fixture.isFile());
        return fixture;
    }
}
