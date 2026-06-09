package com.textview.reader.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class Rar3PpmdBlockProbeTest {
    @Test
    public void detectsPpmdControlBitFromVisiblePayload() throws Exception {
        File archive = writePayload((byte) 0x80, (byte) 0x00, (byte) 0x12);
        RarArchiveReader.RarEntry entry = compressedEntry(archive, false, false, false);

        Rar3PpmdBlockProbe.Result result = Rar3PpmdBlockProbe.probe(entry);

        assertTrue(result.isPpmd());
        assertFalse(result.keepOldTable);
        assertEquals(0x8000, result.rawFlags);
        assertTrue(result.diagnostic().contains("ppmd"));
    }

    @Test
    public void detectsClassicLzAndKeepOldTableBit() throws Exception {
        File archive = writePayload((byte) 0x40, (byte) 0x00, (byte) 0x12);
        RarArchiveReader.RarEntry entry = compressedEntry(archive, false, false, false);

        Rar3PpmdBlockProbe.Result result = Rar3PpmdBlockProbe.probe(entry);

        assertTrue(result.isClassicLz());
        assertTrue(result.keepOldTable);
        assertEquals(0x4000, result.rawFlags);
        assertTrue(result.diagnostic().contains("classic-lz"));
    }

    @Test
    public void encryptedPayloadIsNotClassifiedBeforeDecrypt() throws Exception {
        File archive = writePayload((byte) 0x80, (byte) 0x00, (byte) 0x12);
        RarArchiveReader.RarEntry entry = compressedEntry(archive, true, false, false);

        Rar3PpmdBlockProbe.Result result = Rar3PpmdBlockProbe.probe(entry);

        assertFalse(result.isPpmd());
        assertTrue(result.diagnostic().contains("encrypted payload"));
    }

    @Test
    public void featureClassifierIncludesPpmdProbeDiagnostic() throws Exception {
        File archive = writePayload((byte) 0x80, (byte) 0x00, (byte) 0x12);
        RarArchiveReader.RarEntry entry = compressedEntry(archive, false, false, false);

        RarArchiveReader.UnsupportedRarFeatureException ex =
                RarFeatureClassifier.firstPartyRar3Or4Gap(entry, null);

        assertTrue(ex.getMessage().contains("PPMd compressed payload is detected"));
        assertTrue(ex.getMessage().contains("ppmd probe:"));
        assertTrue(ex.getMessage().contains("rawFlags=0x8000"));
    }


    @Test
    public void libarchivePrimaryFailurePrioritizesSolidPpmdBoundary() throws Exception {
        File archive = writePayload((byte) 0x80, (byte) 0x00, (byte) 0x12);
        RarArchiveReader.RarEntry entry = compressedEntry(archive, false, false, false, true);

        RarArchiveReader.UnsupportedRarFeatureException ex =
                RarFeatureClassifier.libarchivePrimaryRarFailure(
                        entry,
                        new IOException("Could not extract archive with libarchive: RAR solid archive support unavailable."));

        assertTrue(ex.getMessage().contains("solid PPMd payload"));
        assertTrue(ex.getMessage().contains("known old-format RAR3/RAR4 solid gap"));
        assertTrue(ex.getMessage().contains("RAR solid archive support unavailable"));
        assertTrue(ex.getMessage().contains("ppmd probe:"));
    }

    private static File writePayload(byte... bytes) throws Exception {
        File file = File.createTempFile("rar3-ppmd-probe", ".bin");
        file.deleteOnExit();
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static RarArchiveReader.RarEntry compressedEntry(File archive,
                                                             boolean encrypted,
                                                             boolean splitBefore,
                                                             boolean splitAfter) {
        return compressedEntry(archive, encrypted, splitBefore, splitAfter, false);
    }

    private static RarArchiveReader.RarEntry compressedEntry(File archive,
                                                             boolean encrypted,
                                                             boolean splitBefore,
                                                             boolean splitAfter,
                                                             boolean solid) {
        RarArchiveReader.EncryptionInfo encryption = encrypted
                ? RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[8])
                : null;
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                "file.bin",
                false,
                10,
                3,
                0,
                4,
                0x33,
                solid,
                splitBefore,
                splitAfter,
                encryption,
                0,
                0);
        entry.sourceArchive = archive;
        return entry;
    }
}
