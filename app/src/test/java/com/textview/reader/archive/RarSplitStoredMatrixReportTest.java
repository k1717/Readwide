package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class RarSplitStoredMatrixReportTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void analyze_reportsSupportedPlainAndEncryptedStoredSplitRows() throws Exception {
        RarArchiveReader.RarEntry plainFirst = entry("plain.bin", 4, 0x30, false, true, null, 4L, false);
        RarArchiveReader.RarEntry plainLast = entry("plain.bin", 4, 0x30, true, false, null, 6L, false);
        RarArchiveReader.EncryptionInfo rar5 = rar5Encryption();
        RarArchiveReader.RarEntry encFirst = entry("secret.bin", 5, 0, false, true, rar5, 16L, false);
        RarArchiveReader.RarEntry encLast = entry("secret.bin", 5, 0, true, false, rar5, 16L, false);
        assignSources(plainFirst, plainLast, encFirst, encLast);

        RarSplitStoredMatrixReport report = RarSplitStoredMatrixReport.analyze(
                Arrays.asList(plainFirst, plainLast, encFirst, encLast));

        assertEquals(2, report.rows().size());
        assertEquals(2, report.supportedCount());
        assertEquals(RarSplitStoredMatrixReport.Category.PLAIN_STORED_SPLIT, report.rows().get(0).category);
        assertEquals(RarSplitStoredMatrixReport.Category.RAR5_AES_STORED_SPLIT, report.rows().get(1).category);
        assertTrue(report.toMarkdownTable().contains("plain.bin"));
        assertTrue(report.toMarkdownTable().contains("RAR5_AES_STORED_SPLIT"));
    }

    @Test
    public void analyze_reportsCompressedSplitAsUnsupportedNotSupported() throws Exception {
        RarArchiveReader.RarEntry first = entry("compressed.bin", 4, 0x33, false, true, null, 9L, false);
        RarArchiveReader.RarEntry last = entry("compressed.bin", 4, 0x33, true, false, null, 7L, false);
        assignSources(first, last);

        RarSplitStoredMatrixReport report = RarSplitStoredMatrixReport.analyze(Arrays.asList(first, last));

        assertEquals(0, report.supportedCount());
        assertEquals(1, report.unsupportedCount());
        assertEquals(RarSplitStoredMatrixReport.Category.COMPRESSED_SPLIT, report.rows().get(0).category);
        assertEquals(RarSplitStoredMatrixReport.Status.UNSUPPORTED, report.rows().get(0).status);
    }

    @Test
    public void analyze_reportsSolidSplitAsUnsupported() throws Exception {
        RarArchiveReader.RarEntry first = entry("solid.bin", 4, 0x30, false, true, null, 9L, true);
        RarArchiveReader.RarEntry last = entry("solid.bin", 4, 0x30, true, false, null, 7L, true);
        assignSources(first, last);

        RarSplitStoredMatrixReport report = RarSplitStoredMatrixReport.analyze(Arrays.asList(first, last));

        assertEquals(RarSplitStoredMatrixReport.Category.SOLID_SPLIT, report.rows().get(0).category);
        assertEquals(RarSplitStoredMatrixReport.Status.UNSUPPORTED, report.rows().get(0).status);
    }

    @Test
    public void analyze_reportsIncompleteChainAndDanglingContinuationAsInvalid() throws Exception {
        RarArchiveReader.RarEntry first = entry("missing.bin", 4, 0x30, false, true, null, 5L, false);
        RarArchiveReader.RarEntry orphan = entry("orphan.bin", 4, 0x30, true, false, null, 3L, false);
        assignSources(first, orphan);

        RarSplitStoredMatrixReport report = RarSplitStoredMatrixReport.analyze(Arrays.asList(first, orphan));

        assertEquals(2, report.rows().size());
        assertEquals(2, report.invalidCount());
        assertEquals(RarSplitStoredMatrixReport.Category.INCOMPLETE_CHAIN, report.rows().get(0).category);
        assertEquals(RarSplitStoredMatrixReport.Category.DANGLING_CONTINUATION, report.rows().get(1).category);
    }

    @Test
    public void analyzeIncludingNonSplit_marksNormalEntriesIgnored() throws Exception {
        RarArchiveReader.RarEntry normal = entry("normal.bin", 4, 0x30, false, false, null, 8L, false);
        assignSources(normal);

        RarSplitStoredMatrixReport report = RarSplitStoredMatrixReport.analyzeIncludingNonSplit(Arrays.asList(normal));

        assertEquals(1, report.rows().size());
        assertEquals(RarSplitStoredMatrixReport.Status.IGNORED, report.rows().get(0).status);
        assertEquals(RarSplitStoredMatrixReport.Category.NON_SPLIT_ENTRY, report.rows().get(0).category);
    }

    private void assignSources(RarArchiveReader.RarEntry... entries) throws Exception {
        for (int i = 0; i < entries.length; i++) {
            File source = tempFolder.newFile("matrix-vol" + i + ".rar");
            entries[i].sourceArchive = source;
        }
    }

    private static RarArchiveReader.RarEntry entry(String path,
                                                   int rarVersion,
                                                   int method,
                                                   boolean splitBefore,
                                                   boolean splitAfter,
                                                   RarArchiveReader.EncryptionInfo encryption,
                                                   long packedSize,
                                                   boolean solid) {
        return new RarArchiveReader.RarEntry(
                path,
                false,
                32L,
                packedSize,
                64L,
                rarVersion,
                method,
                solid,
                splitBefore,
                splitAfter,
                encryption,
                0x2468ace0L,
                0L);
    }

    private static RarArchiveReader.EncryptionInfo rar5Encryption() {
        return new RarArchiveReader.EncryptionInfo(
                0L,
                0L,
                5,
                new byte[] {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15},
                new byte[] {16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31},
                new byte[] {1,2,3,4,5,6,7,8,9,10,11,12});
    }
}
