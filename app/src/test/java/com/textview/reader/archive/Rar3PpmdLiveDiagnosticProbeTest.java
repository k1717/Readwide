package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.util.List;

public class Rar3PpmdLiveDiagnosticProbeTest {
    @Test
    public void syntheticPackedPayloadProbeRecordsSymbolsWithoutSuccessState() throws Exception {
        Rar3PpmdState state = new Rar3PpmdState();
        byte[] packed = new byte[] {
                (byte) 0x80, 0x02, // PPMd, reset table, escape 2.
                0, 0, 0, 0,       // range decoder seed.
                0, 0, 0, 0
        };

        Rar3PpmdLiveDiagnosticProbe.Row row =
                Rar3PpmdLiveDiagnosticProbe.probePackedPayloadForTest(
                        "synthetic.bin", packed.length, 1, false, packed, state, 1);

        assertEquals("synthetic.bin", row.path);
        assertTrue(row.header.isPpmd());
        assertEquals(1, row.decodedSymbols);
        assertTrue(row.symbolLimitReached);
        assertEquals("none", row.boundaryType);
        assertFalse(row.fatalStateBoundary);
        assertEquals(1, row.firstSymbols.size());
        assertEquals(1, row.traceRows.size());
        assertFalse(row.traceRows.get(0).boundary);
        assertTrue(row.traceRows.get(0).diagnostic().contains("rangeBefore"));
        assertTrue(row.traceRows.get(0).diagnostic().contains("decodeTraceAfter"));
        assertTrue(row.diagnostic().contains("firstSymbolsHex"));
        assertTrue(row.diagnostic().contains("lastTrace"));
    }

    @Test
    public void syntheticPackedPayloadVariantProbeSeparatesOrder0AndSeeModes() throws Exception {
        byte[] packed = new byte[] {
                (byte) 0x80, 0x02, // PPMd, reset table, escape 2.
                0, 0, 0, 0,
                0, 0, 0, 0
        };

        Rar3PpmdLiveDiagnosticProbe.Row standard =
                Rar3PpmdLiveDiagnosticProbe.probePackedPayloadWithOptionsForTest(
                        "synthetic.bin", packed.length, 1, false, packed, new Rar3PpmdState(), 1,
                        RarPpmdDiagnosticOptions.standard());
        Rar3PpmdLiveDiagnosticProbe.Row order0Only =
                Rar3PpmdLiveDiagnosticProbe.probePackedPayloadWithOptionsForTest(
                        "synthetic.bin", packed.length, 1, false, packed, new Rar3PpmdState(), 1,
                        RarPpmdDiagnosticOptions.order0Only());
        Rar3PpmdLiveDiagnosticProbe.Row primaryRoot =
                Rar3PpmdLiveDiagnosticProbe.probePackedPayloadWithOptionsForTest(
                        "synthetic.bin", packed.length, 1, false, packed, new Rar3PpmdState(), 1,
                        RarPpmdDiagnosticOptions.rarPrimaryRoot());
        Rar3PpmdLiveDiagnosticProbe.Row fixedSee =
                Rar3PpmdLiveDiagnosticProbe.probePackedPayloadWithOptionsForTest(
                        "synthetic.bin", packed.length, 1, false, packed, new Rar3PpmdState(), 1,
                        RarPpmdDiagnosticOptions.fixedEscapeScale(1));
        Rar3PpmdLiveDiagnosticProbe.Row candidateSee =
                Rar3PpmdLiveDiagnosticProbe.probePackedPayloadWithOptionsForTest(
                        "synthetic.bin", packed.length, 1, false, packed, new Rar3PpmdState(), 1,
                        RarPpmdDiagnosticOptions.candidateEarlyLowSee());

        assertEquals("standard", standard.variantName);
        assertEquals("order0-only", order0Only.variantName);
        assertEquals("rar-primary-root", primaryRoot.variantName);
        assertEquals("fixed-see-1", fixedSee.variantName);
        assertEquals("candidate-see-early-low", candidateSee.variantName);
        assertTrue(order0Only.lastTraceDiagnostic().contains("contextFallback=false"));
        assertTrue(primaryRoot.lastTraceDiagnostic().contains("primaryContext=true"));
        assertTrue(fixedSee.lastTraceDiagnostic().contains("fixedEscapeScale=1"));
        assertTrue(candidateSee.lastTraceDiagnostic().contains("seeMode="));
        assertEquals(1, standard.decodedSymbols);
        assertEquals(1, order0Only.decodedSymbols);
        assertEquals(1, primaryRoot.decodedSymbols);
        assertEquals(1, fixedSee.decodedSymbols);
        assertEquals(1, candidateSee.decodedSymbols);
        assertTrue(candidateSee.diagnostic().contains("unpackedProgress"));
        assertTrue(primaryRoot.diagnostic().contains("expectedCommonPrefixMatched"));
    }

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
