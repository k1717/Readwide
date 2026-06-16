package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class RarNonSolidCompatibilityReportTest {
    @Test
    public void reportsStoredSplitAndClassicLzFallbackAsNonSolidCompatiblePaths() {
        RarArchiveReader.RarEntry storedSplit = entry("stored.bin", 4, 0x30, false, false, true, null);
        RarArchiveReader.RarEntry classicLz = entry("classic.txt", 4, 0x33, false, false, false, null);
        classicLz.sourceArchive = classicLzProbePayload();

        RarNonSolidCompatibilityReport report = RarNonSolidCompatibilityReport.fromEntries(
                Arrays.asList(storedSplit, classicLz));

        assertEquals(2, report.nonSolidPayloadCount());
        assertEquals(1, report.firstPartyDirectCount());
        assertEquals(1, report.limitedFallbackCount());
        assertTrue(report.toMarkdown().contains("FIRST_PARTY_STORED_SPLIT"));
        assertTrue(report.toMarkdown().contains("LIMITED_CLASSIC_LZ_FALLBACK"));
    }

    @Test
    public void reportsCompressedSplitRewriteAsLibarchiveDependentNonSolidPath() {
        RarArchiveReader.RarEntry split = entry("split.txt", 4, 0x33, false, false, true, null);

        RarNonSolidCompatibilityReport report = RarNonSolidCompatibilityReport.fromEntries(
                Arrays.asList(split));

        assertEquals(1, report.nonSolidPayloadCount());
        assertEquals(1, report.libarchiveDependentCount());
        assertTrue(report.toMarkdown().contains("TRY_FIRST_PARTY_RAR4_COMPRESSED_SPLIT_REWRITE"));
        assertTrue(report.toMarkdown().contains("REWRITE_THEN_LIBARCHIVE"));
    }

    @Test
    public void reportsEncryptedCompressedSplitRewriteAsLibarchiveDependentNonSolidPath() {
        RarArchiveReader.EncryptionInfo encryption = RarArchiveReader.EncryptionInfo.rar4Unsupported(
                new byte[] {1,2,3,4,5,6,7,8});
        RarArchiveReader.RarEntry split = entry("split.txt", 4, 0x33, false, false, true, encryption);

        RarNonSolidCompatibilityReport report = RarNonSolidCompatibilityReport.fromEntries(
                Arrays.asList(split));

        assertEquals(1, report.libarchiveDependentCount());
        assertTrue(report.toMarkdown().contains("TRY_FIRST_PARTY_RAR4_ENCRYPTED_COMPRESSED_SPLIT_REWRITE"));
        assertTrue(report.toMarkdown().contains("REWRITE_THEN_LIBARCHIVE"));
    }

    @Test
    public void reportsRar5CompressedAsLibarchivePrimaryWithoutFirstPartyClaim() {
        RarArchiveReader.RarEntry rar5 = entry("rar5.bin", 5, 1, false, false, false, null);

        RarNonSolidCompatibilityReport report = RarNonSolidCompatibilityReport.fromEntries(
                Arrays.asList(rar5));

        assertEquals(1, report.nonSolidPayloadCount());
        assertEquals(1, report.libarchiveDependentCount());
        assertEquals(0, report.limitedFallbackCount());
        assertEquals(0, report.rar5StoredFirstPartyCount());
        assertEquals(1, report.rar5CompressedLibarchiveCount());
        assertTrue(report.oneLineSummary().contains("rar5CompressedLibarchive=1"));
        assertTrue(report.toMarkdown().contains("TRY_LIBARCHIVE"));
        assertTrue(report.toMarkdown().contains("RAR5 compressed non-solid"));
    }


    @Test
    public void reportsRar5StoredAsFirstPartyLimitedWithoutCompressedClaim() {
        RarArchiveReader.RarEntry rar5Stored = entry("rar5-stored.bin", 5, 0, false, false, false, null);

        RarNonSolidCompatibilityReport report = RarNonSolidCompatibilityReport.fromEntries(
                Arrays.asList(rar5Stored));

        assertEquals(1, report.nonSolidPayloadCount());
        assertEquals(1, report.firstPartyDirectCount());
        assertEquals(1, report.rar5StoredFirstPartyCount());
        assertEquals(0, report.rar5CompressedLibarchiveCount());
        assertTrue(report.oneLineSummary().contains("rar5StoredFirstParty=1"));
        assertTrue(report.toMarkdown().contains("RAR5 stored method-0 path"));
    }

    @Test
    public void excludesCompressedSolidFromNonSolidAudit() {
        RarArchiveReader.RarEntry solid = entry("solid.bin", 4, 0x33, true, false, false, null);

        RarNonSolidCompatibilityReport report = RarNonSolidCompatibilityReport.fromEntries(
                Arrays.asList(solid));

        assertEquals(0, report.nonSolidPayloadCount());
        assertEquals(1, report.excludedSolidCount());
        assertTrue(report.toMarkdown().contains("EXCLUDED_SOLID"));
    }

    private static RarArchiveReader.RarEntry entry(String path,
                                                   int version,
                                                   int method,
                                                   boolean solid,
                                                   boolean splitBefore,
                                                   boolean splitAfter,
                                                   RarArchiveReader.EncryptionInfo encryption) {
        return new RarArchiveReader.RarEntry(
                path,
                false,
                32L,
                encryption == null ? 32L : 48L,
                0L,
                version,
                method,
                solid,
                splitBefore,
                splitAfter,
                encryption,
                0x12345678L,
                0L);
    }

    private static java.io.File classicLzProbePayload() {
        try {
            java.io.File payload = java.io.File.createTempFile("classiclz-probe", ".rar");
            payload.deleteOnExit();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(payload)) {
                out.write(new byte[]{0x00, 0x00, 0x00, 0x00}); // first bit clear = classic LZ block
            }
            return payload;
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
