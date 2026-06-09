package com.textview.reader.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class RarFeatureClassifierTest {
    @Test
    public void encryptedStoredSplit_isFirstPartySpecialCaseNotGenericUnsupported() {
        RarArchiveReader.RarEntry entry = rar4Entry(
                "file.bin",
                0x30,
                false,
                false,
                true,
                true,
                RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[] {1,2,3,4,5,6,7,8}));

        assertFalse(RarFeatureClassifier.isUnsupportedRar3Or4Payload(entry));
    }

    @Test
    public void encryptedCompressedSplitCandidate_requiresConsistentChain() {
        RarArchiveReader.EncryptionInfo encryption =
                RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[] {1,2,3,4,5,6,7,8});
        RarArchiveReader.RarEntry first = rar4Entry("file.bin", 0x33, false, false, true, true, encryption);
        RarArchiveReader.RarEntry last = rar4Entry("file.bin", 0x33, false, true, false, true, encryption);

        assertTrue(RarFeatureClassifier.isRar3Or4EncryptedCompressedSplitRewriteCandidate(first));
        assertTrue(RarFeatureClassifier.isRar3Or4EncryptedCompressedSplitRewriteCandidate(
                Arrays.asList(first, last)));
    }

    @Test
    public void plainCompressedSplitCandidate_requiresConsistentChain() {
        RarArchiveReader.RarEntry first = rar4Entry("file.bin", 0x33, false, false, true, false, null);
        RarArchiveReader.RarEntry last = rar4Entry("file.bin", 0x33, false, true, false, false, null);

        assertTrue(RarFeatureClassifier.isRar3Or4CompressedSplitRewriteCandidate(first));
        assertTrue(RarFeatureClassifier.isRar3Or4CompressedSplitRewriteCandidate(
                Arrays.asList(first, last)));
    }

    @Test
    public void solidCompressedEntry_staysGenericUnsupportedUntilSolidDecoderExists() {
        RarArchiveReader.RarEntry solid = rar4Entry("file.bin", 0x35, true, false, false, false, null);

        assertTrue(RarFeatureClassifier.isUnsupportedRar3Or4Payload(solid));
    }

    @Test
    public void solidStoredEntry_isNotUnsupportedBecauseNoDictionaryContinuationIsNeeded() {
        RarArchiveReader.RarEntry solidStored = rar4Entry("file.bin", 0, true, false, false, false, null);

        assertFalse(RarFeatureClassifier.isUnsupportedRar3Or4Payload(solidStored));
    }

    @Test
    public void encryptedSolidStoredEntry_isFirstPartyStoredCryptoPath() {
        RarArchiveReader.RarEntry solidStored = rar4Entry(
                "file.bin",
                0,
                true,
                false,
                false,
                true,
                RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[] {1,2,3,4,5,6,7,8}));

        assertFalse(RarFeatureClassifier.isUnsupportedRar3Or4Payload(solidStored));
        assertTrue(RarFeatureClassifier.isFirstPartyRar3Or4EncryptedStoredEntry(solidStored));
    }

    private static RarArchiveReader.RarEntry rar4Entry(String path,
                                                       int method,
                                                       boolean solid,
                                                       boolean splitBefore,
                                                       boolean splitAfter,
                                                       boolean encrypted,
                                                       RarArchiveReader.EncryptionInfo encryption) {
        return new RarArchiveReader.RarEntry(
                path,
                false,
                128L,
                encrypted ? 144L : 128L,
                64L,
                4,
                method,
                solid,
                splitBefore,
                splitAfter,
                encryption,
                0x12345678L,
                0L);
    }
}
