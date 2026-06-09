package com.textview.reader.archive;

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
}
