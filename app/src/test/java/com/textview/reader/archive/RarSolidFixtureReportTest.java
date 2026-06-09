package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class RarSolidFixtureReportTest {
    @Test
    public void rar3CompressedSolidIsFirstPartyGap() {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("solid.txt", 4, 0x33, true, 12L, 20L));

        RarSolidFixtureReport report = RarSolidFixtureReport.fromEntriesForTest(
                "testfile.rar3.solid.rar",
                1,
                entries,
                RarSolidFixtureReport.LibarchiveProbe.NOT_REQUESTED,
                "fixture");

        assertEquals(1, report.solidArchiveCount());
        assertEquals(1, report.compressedSolidArchiveCount());
        assertEquals(1, report.firstPartyGapCount());
        RarSolidFixtureReport.Row row = report.rows().get(0);
        assertEquals(RarSolidFixtureReport.SolidKind.RAR3_OR_4_COMPRESSED_SOLID, row.solidKind);
        assertEquals(RarSolidFixtureReport.FirstPartyBoundary.RAR3_OR_4_COMPRESSED_SOLID_GAP,
                row.firstPartyBoundary);
        assertEquals(1, row.backendRoutes.count(RarBackendRoute.Kind.CLEAN_UNSUPPORTED_SOLID));
        assertTrue(report.toMarkdown().contains("Backend route"));
        assertTrue(report.toMarkdown().contains("CLEAN_UNSUPPORTED_SOLID"));
        assertTrue(report.toMarkdown().contains("libarchive-first"));
    }

    @Test
    public void rar5SolidIsLibarchiveOwnedGap() {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("solid.txt", 5, 0x31, true, 12L, 20L));

        RarSolidFixtureReport report = RarSolidFixtureReport.fromEntriesForTest(
                "testfile.rar5.solid.rar",
                1,
                entries,
                RarSolidFixtureReport.LibarchiveProbe.UNAVAILABLE,
                "fixture");

        RarSolidFixtureReport.Row row = report.rows().get(0);
        assertEquals(RarSolidFixtureReport.SolidKind.RAR5_SOLID, row.solidKind);
        assertEquals(RarSolidFixtureReport.FirstPartyBoundary.RAR5_SOLID_GAP, row.firstPartyBoundary);
        assertEquals(1, row.backendRoutes.count(RarBackendRoute.Kind.TRY_LIBARCHIVE));
        assertEquals(RarSolidFixtureReport.LibarchiveProbe.UNAVAILABLE, row.libarchiveProbe);
        assertEquals(1, report.firstPartyGapCount());
    }

    @Test
    public void nonSolidArchiveIsNotGap() {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("normal.txt", 4, 0x33, false, 12L, 20L));

        RarSolidFixtureReport report = RarSolidFixtureReport.fromEntriesForTest(
                "testfile.rar3.rar",
                1,
                entries,
                RarSolidFixtureReport.LibarchiveProbe.NOT_SOLID,
                "fixture");

        RarSolidFixtureReport.Row row = report.rows().get(0);
        assertEquals(RarSolidFixtureReport.SolidKind.NONE, row.solidKind);
        assertEquals(RarSolidFixtureReport.FirstPartyBoundary.NOT_SOLID, row.firstPartyBoundary);
        assertEquals(1, row.backendRoutes.count(RarBackendRoute.Kind.TRY_FIRST_PARTY_CLASSIC_LZ_NON_SOLID));
        assertEquals(0, report.firstPartyGapCount());
        assertEquals(0, report.compressedSolidArchiveCount());
    }

    @Test
    public void storedSolidIsStoredOnlyBoundary() {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("stored.bin", 4, 0x30, true, 20L, 20L));

        RarSolidFixtureReport report = RarSolidFixtureReport.fromEntriesForTest(
                "stored-solid.rar",
                1,
                entries,
                RarSolidFixtureReport.LibarchiveProbe.NOT_REQUESTED,
                "fixture");

        RarSolidFixtureReport.Row row = report.rows().get(0);
        assertEquals(RarSolidFixtureReport.SolidKind.RAR3_OR_4_STORED_SOLID, row.solidKind);
        assertEquals(RarSolidFixtureReport.FirstPartyBoundary.STORED_ONLY, row.firstPartyBoundary);
        assertEquals(1, row.backendRoutes.count(RarBackendRoute.Kind.TRY_FIRST_PARTY_STORED));
        assertEquals(0, report.firstPartyGapCount());
    }

    private static RarArchiveReader.RarEntry entry(String path,
                                                   int rarVersion,
                                                   int method,
                                                   boolean solid,
                                                   long packedSize,
                                                   long unpackedSize) {
        return new RarArchiveReader.RarEntry(
                path,
                false,
                unpackedSize,
                packedSize,
                0L,
                rarVersion,
                method,
                solid,
                false,
                false,
                null,
                0L,
                0L);
    }
}
