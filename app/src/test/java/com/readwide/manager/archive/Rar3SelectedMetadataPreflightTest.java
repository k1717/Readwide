package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public class Rar3SelectedMetadataPreflightTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void changedContinuationMethodFailsBeforePrimerRead() throws Exception {
        assertBeforePrimer(entry(true, false, true, 0x33, 8, 0, null),
                entry(true, true, false, 0x34, 8, 0, null),
                "Inconsistent RAR3/RAR4 split payload");
    }

    @Test public void absentFinalChecksumFailsBeforePrimerRead() throws Exception {
        assertBeforePrimer(entry(true, false, true, 0x33, 8, 0, null),
                entry(true, true, false, 0x33, 8, -1, null),
                "RAR3/RAR4 payload is incomplete or lacks a valid checksum");
    }

    @Test public void missingSourceMetadataFailsBeforePrimerRead() throws Exception {
        RarArchiveReader.RarEntry last = entry(true, true, false, 0x33, 8, 0, null);
        last.sourceArchive = null;
        assertBeforePrimer(entry(true, false, true, 0x33, 8, 0, null), last,
                "Invalid RAR3/RAR4 source bounds");
    }

    @Test public void packedSizeOverflowFailsBeforePrimerRead() throws Exception {
        assertBeforePrimer(entry(true, false, true, 0x33, Long.MAX_VALUE, 0, null),
                entry(true, true, false, 0x33, 1, 0, null), "RAR packed size overflow");
    }

    @Test public void invalidEncryptedTotalFailsBeforePrimerReadOrPasswordPrompt() throws Exception {
        RarArchiveReader.EncryptionInfo encryption = RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[8]);
        assertBeforePrimer(entry(true, false, true, 0x33, 11, 0, encryption),
                entry(true, true, false, 0x33, 6, 0, encryption),
                "RAR3/RAR4 AES payload is not block aligned");
    }

    @Test public void alignedEncryptedTotalReachesPlainPrimerWithoutPasswordWork() throws Exception {
        RarArchiveReader.EncryptionInfo encryption = RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[8]);
        RarArchiveReader.RarEntry first = entry(true, false, true, 0x33, 11, 0, encryption);
        RarArchiveReader.RarEntry last = entry(true, true, false, 0x33, 5, 0, encryption);
        assertPrimerRead(Arrays.asList(primer(), first, last), first);
    }

    @Test public void unrelatedLaterMalformedEntryIsOutsideSelectedPreflight() throws Exception {
        RarArchiveReader.RarEntry target = entry(true, false, false, 0x33, 8, 0, null);
        RarArchiveReader.RarEntry later = entry(false, false, false, 0x33, -1, -1, null);
        later.sourceArchive = null;
        assertPrimerRead(Arrays.asList(primer(), target, later), target);
    }

    private void assertBeforePrimer(RarArchiveReader.RarEntry first,
            RarArchiveReader.RarEntry last, String message) throws Exception {
        File output = output();
        IOException failure = assertThrows(IOException.class,
                () -> Rar3PpmdSolidArchiveExtractor.tryExtractSolidPpmdEntry(first,
                        Arrays.asList(primer(), first, last), output, null, null));
        assertEquals(message, failure.getMessage());
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    private void assertPrimerRead(List<RarArchiveReader.RarEntry> entries,
            RarArchiveReader.RarEntry target) throws Exception {
        File output = output();
        assertThrows(FileNotFoundException.class,
                () -> Rar3PpmdSolidArchiveExtractor.tryExtractSolidPpmdEntry(
                        target, entries, output, null, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    private File output() throws Exception {
        File output = temporary.newFile();
        Files.write(output.toPath(), new byte[]{42});
        return output;
    }

    private RarArchiveReader.RarEntry primer() {
        return entry(false, false, false, 0x33, 8, 0, null);
    }

    private RarArchiveReader.RarEntry entry(boolean solid, boolean before, boolean after,
            int method, long packed, long crc, RarArchiveReader.EncryptionInfo encryption) {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("selected.bin", false,
                8, packed, 0, 4, method, solid, before, after, encryption, crc, 0, 0);
        entry.sourceArchive = new File(temporary.getRoot(), "absent-volume.rar");
        return entry;
    }
}
