package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class RarVolumeChainTest {
    @Test
    public void build_collectsContiguousPartsByPath() throws Exception {
        RarArchiveReader.RarEntry first = entry("file.bin", false, true, null);
        RarArchiveReader.RarEntry middle = entry("file.bin", true, true, null);
        RarArchiveReader.RarEntry last = entry("file.bin", true, false, null);
        RarArchiveReader.RarEntry other = entry("other.bin", true, false, null);

        List<RarArchiveReader.RarEntry> chain = RarVolumeChain.build(
                first,
                Arrays.asList(first, other, middle, last));

        assertEquals(3, chain.size());
        assertTrue(RarVolumeChain.isComplete(chain));
        assertEquals(last, RarVolumeChain.last(chain));
    }

    @Test
    public void payloadSegments_preserveSourceOffsetAndSize() throws Exception {
        RarArchiveReader.RarEntry first = entry("file.bin", false, true, null);
        RarArchiveReader.RarEntry last = entry("file.bin", true, false, null);
        first.sourceArchive = new File("first.rar");
        last.sourceArchive = new File("first.r00");

        List<RarCryptoStreams.EncryptedSegment> segments = RarVolumeChain.payloadSegments(
                Arrays.asList(first, last));

        assertEquals(2, segments.size());
        assertEquals(new File("first.rar"), segments.get(0).archive);
        assertEquals(64L, segments.get(0).offset);
        assertEquals(144L, segments.get(0).encryptedSize);
        assertEquals(new File("first.r00"), segments.get(1).archive);
    }

    @Test
    public void sameEncryption_requiresMatchingRar4Salt() {
        RarArchiveReader.EncryptionInfo a =
                RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[] {1,2,3,4,5,6,7,8});
        RarArchiveReader.EncryptionInfo b =
                RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[] {1,2,3,4,5,6,7,8});
        RarArchiveReader.EncryptionInfo c =
                RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[] {8,7,6,5,4,3,2,1});

        assertTrue(RarVolumeChain.sameRar4Encryption(a, b));
        assertFalse(RarVolumeChain.sameRar4Encryption(a, c));
    }

    @Test
    public void sameEncryption_allowsRar5ContinuationFlagsToDiffer() {
        RarArchiveReader.EncryptionInfo first = rar5Encryption(1L, new byte[] {10, 11, 12});
        RarArchiveReader.EncryptionInfo continuation = rar5Encryption(3L, new byte[] {10, 11, 12});
        RarArchiveReader.EncryptionInfo differentIv = rar5Encryption(3L, new byte[] {12, 11, 10});

        assertTrue(RarVolumeChain.sameRar5Encryption(first, continuation));
        assertFalse(RarVolumeChain.sameRar5Encryption(first, differentIv));
    }

    @Test
    public void validateStoredPart_acceptsRar4Method30StoredEntry() throws Exception {
        RarArchiveReader.RarEntry stored30 = new RarArchiveReader.RarEntry(
                "file.bin",
                false,
                128L,
                128L,
                64L,
                4,
                0x30,
                false,
                false,
                true,
                null,
                0x12345678L,
                0L);

        RarVolumeChain.validateStoredPart(stored30, false);
    }

    private static RarArchiveReader.RarEntry entry(String path,
                                                   boolean splitBefore,
                                                   boolean splitAfter,
                                                   RarArchiveReader.EncryptionInfo encryption) {
        return new RarArchiveReader.RarEntry(
                path,
                false,
                128L,
                144L,
                64L,
                4,
                0,
                false,
                splitBefore,
                splitAfter,
                encryption,
                0x12345678L,
                0L);
    }

    private static RarArchiveReader.EncryptionInfo rar5Encryption(long flags, byte[] ivSeed) {
        byte[] salt = new byte[16];
        byte[] iv = new byte[16];
        byte[] check = new byte[12];
        for (int i = 0; i < salt.length; i++) salt[i] = (byte) i;
        for (int i = 0; i < iv.length; i++) iv[i] = ivSeed[i % ivSeed.length];
        for (int i = 0; i < check.length; i++) check[i] = (byte) (20 + i);
        return new RarArchiveReader.EncryptionInfo(0L, flags, 15, salt, iv, check);
    }
}
