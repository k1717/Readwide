package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class RarSplitStoredPlanTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void fromChain_acceptsRar4Method30PlainStoredSplit() throws Exception {
        RarArchiveReader.RarEntry first = entry("file.bin", 4, 0x30, false, true, null, 7L);
        RarArchiveReader.RarEntry last = entry("file.bin", 4, 0x30, true, false, null, 5L);
        assignSources(first, last);

        RarSplitStoredPlan plan = RarSplitStoredPlan.fromChain(Arrays.asList(first, last));

        assertEquals(RarSplitStoredPlan.Kind.PLAIN_STORED, plan.kind());
        assertFalse(plan.encrypted());
        assertEquals(12L, plan.totalPackedSize());
        assertEquals(last.unpackedSize, plan.unpackedSize());
        assertEquals(last, plan.crcEntry());
        assertEquals(2, plan.payloadSegments().size());
    }

    @Test
    public void fromChain_acceptsRar4AesStoredSplitWithMatchingSalt() throws Exception {
        RarArchiveReader.EncryptionInfo encryption =
                RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[] {1,2,3,4,5,6,7,8});
        RarArchiveReader.RarEntry first = entry("file.bin", 4, 0x30, false, true, encryption, 16L);
        RarArchiveReader.RarEntry last = entry("file.bin", 4, 0x30, true, false, encryption, 16L);
        assignSources(first, last);

        RarSplitStoredPlan plan = RarSplitStoredPlan.fromChain(Arrays.asList(first, last));

        assertEquals(RarSplitStoredPlan.Kind.RAR4_AES_STORED, plan.kind());
        assertTrue(plan.encrypted());
        assertEquals(32L, plan.totalPackedSize());
    }

    @Test
    public void fromChain_acceptsRar5AesStoredSplitWithMatchingParameters() throws Exception {
        RarArchiveReader.EncryptionInfo encryption = new RarArchiveReader.EncryptionInfo(
                0L,
                0L,
                5,
                new byte[] {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15},
                new byte[] {16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31},
                new byte[] {1,2,3,4,5,6,7,8,9,10,11,12});
        RarArchiveReader.RarEntry first = entry("file.bin", 5, 0, false, true, encryption, 16L);
        RarArchiveReader.RarEntry last = entry("file.bin", 5, 0, true, false, encryption, 16L);
        assignSources(first, last);

        RarSplitStoredPlan plan = RarSplitStoredPlan.fromChain(Arrays.asList(first, last));

        assertEquals(RarSplitStoredPlan.Kind.RAR5_AES_STORED, plan.kind());
        assertTrue(plan.encrypted());
    }

    @Test
    public void fromChain_rejectsCompressedSplitInStoredPath() throws Exception {
        RarArchiveReader.RarEntry first = entry("file.bin", 4, 0x33, false, true, null, 7L);
        RarArchiveReader.RarEntry last = entry("file.bin", 4, 0x33, true, false, null, 5L);
        assignSources(first, last);

        try {
            RarSplitStoredPlan.fromChain(Arrays.asList(first, last));
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("compressed split"));
            return;
        }
        throw new AssertionError("compressed split must not enter stored split path");
    }

    @Test
    public void fromChain_rejectsMixedEncryption() throws Exception {
        RarArchiveReader.EncryptionInfo encryption =
                RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[] {1,2,3,4,5,6,7,8});
        RarArchiveReader.RarEntry first = entry("file.bin", 4, 0x30, false, true, encryption, 16L);
        RarArchiveReader.RarEntry last = entry("file.bin", 4, 0x30, true, false, null, 16L);
        assignSources(first, last);

        try {
            RarSplitStoredPlan.fromChain(Arrays.asList(first, last));
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("Mixed encrypted"));
            return;
        }
        throw new AssertionError("mixed encryption must be rejected");
    }

    @Test
    public void fromChain_rejectsMissingSourceArchiveBeforeCopy() throws Exception {
        RarArchiveReader.RarEntry first = entry("file.bin", 4, 0x30, false, true, null, 7L);
        RarArchiveReader.RarEntry last = entry("file.bin", 4, 0x30, true, false, null, 5L);
        first.sourceArchive = tempFolder.newFile("first.rar");

        try {
            RarSplitStoredPlan.fromChain(Arrays.asList(first, last));
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("source volume"));
            return;
        }
        throw new AssertionError("missing source archive must be rejected");
    }

    @Test
    public void fromFirstEntry_buildsAndValidatesCompleteStoredChain() throws Exception {
        RarArchiveReader.RarEntry first = entry("file.bin", 4, 0x30, false, true, null, 7L);
        RarArchiveReader.RarEntry last = entry("file.bin", 4, 0x30, true, false, null, 5L);
        RarArchiveReader.RarEntry other = entry("other.bin", 4, 0x30, false, false, null, 3L);
        assignSources(first, last, other);
        List<RarArchiveReader.RarEntry> all = Arrays.asList(first, other, last);

        RarSplitStoredPlan plan = RarSplitStoredPlan.fromFirstEntry(first, all);

        assertEquals(2, plan.chain().size());
        assertEquals(12L, plan.totalPackedSize());
    }

    private void assignSources(RarArchiveReader.RarEntry... entries) throws Exception {
        for (int i = 0; i < entries.length; i++) {
            entries[i].sourceArchive = tempFolder.newFile("vol" + i + ".rar");
        }
    }

    private static RarArchiveReader.RarEntry entry(String path,
                                                   int rarVersion,
                                                   int method,
                                                   boolean splitBefore,
                                                   boolean splitAfter,
                                                   RarArchiveReader.EncryptionInfo encryption,
                                                   long packedSize) {
        return new RarArchiveReader.RarEntry(
                path,
                false,
                12L,
                packedSize,
                64L,
                rarVersion,
                method,
                false,
                splitBefore,
                splitAfter,
                encryption,
                0x12345678L,
                0L);
    }
}
