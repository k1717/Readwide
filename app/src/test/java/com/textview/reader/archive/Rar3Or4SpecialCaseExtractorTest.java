package com.textview.reader.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class Rar3Or4SpecialCaseExtractorTest {
    @Test
    public void archiveWidePlan_allowsMixedStoredCompressedSplitAndClassicLz() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("stored.txt", 0x30, false, false, false, false, null, 4));
        entries.add(entry("split.bin", 0x33, false, false, false, true, null, 4));
        entries.add(entry("split.bin", 0x33, false, false, true, false, null, 4));
        entries.add(entry("classic.txt", 0x33, false, false, false, false, null, 4));

        assertTrue(Rar3Or4SpecialCaseExtractor.isArchiveWideNonSolidSpecialCaseAllowed(entries, null));
    }

    @Test
    public void archiveWidePlan_rejectsCompressedSolid() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("solid.bin", 0x33, true, false, false, false, null, 4));

        assertFalse(Rar3Or4SpecialCaseExtractor.isArchiveWideNonSolidSpecialCaseAllowed(entries, null));
    }

    @Test
    public void archiveWidePlan_rejectsRar5Compressed() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("rar5.bin", 1, false, false, false, false, null, 5));

        assertFalse(Rar3Or4SpecialCaseExtractor.isArchiveWideNonSolidSpecialCaseAllowed(entries, null));
    }

    @Test
    public void archiveWidePlan_requiresPasswordForEncryptedCompressedSplitRewrite() throws Exception {
        RarArchiveReader.EncryptionInfo enc = RarArchiveReader.EncryptionInfo.rar4Unsupported(
                new byte[] {1,2,3,4,5,6,7,8});
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("secret.bin", 0x33, false, false, false, true, enc, 4));
        entries.add(entry("secret.bin", 0x33, false, false, true, false, enc, 4));

        assertFalse(Rar3Or4SpecialCaseExtractor.isArchiveWideNonSolidSpecialCaseAllowed(entries, null));
        assertTrue(Rar3Or4SpecialCaseExtractor.isArchiveWideNonSolidSpecialCaseAllowed(entries, "pw".toCharArray()));
    }

    @Test
    public void archiveWidePlan_rejectsNormalUnsupportedCompressedMethod() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("unsupported.bin", 0x7f, false, false, false, false, null, 4));

        assertFalse(Rar3Or4SpecialCaseExtractor.isArchiveWideNonSolidSpecialCaseAllowed(entries, null));
    }

    private static RarArchiveReader.RarEntry entry(String path,
                                                   int method,
                                                   boolean solid,
                                                   boolean directory,
                                                   boolean splitBefore,
                                                   boolean splitAfter,
                                                   RarArchiveReader.EncryptionInfo encryption,
                                                   int rarVersion) {
        return new RarArchiveReader.RarEntry(
                path,
                directory,
                directory ? 0 : 4,
                directory ? 0 : 4,
                0,
                rarVersion,
                method,
                solid,
                splitBefore,
                splitAfter,
                encryption,
                0,
                0);
    }
}
