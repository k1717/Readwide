package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Synthetic stored members exercise verification without a decoded spool. */
public class Rar3StoredDiscardTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void plainAndSplitSkipsDoNotNeedASpoolDirectory() throws Exception {
        for (boolean split : new boolean[]{false, true}) {
            File blocked = temporary.newFile();
            try (ArchiveSupport.ForwardArchiveReader reader = open(fixture(split), blocked)) {
                assertNotNull(reader.nextEntry());
                assertTrue(reader.drainCurrentEntry(200_000));
                assertNull(reader.nextEntry());
            }
            assertEquals(0, blocked.length());
        }
    }

    @Test public void emptyStoredSkipDoesNotCreateDirectory() throws Exception {
        File absent = new File(temporary.getRoot(), "absent");
        List<RarArchiveReader.RarEntry> entries = Arrays.asList(part(new byte[0], 0, false, false, 0));
        try (ArchiveSupport.ForwardArchiveReader reader = open(entries, absent)) {
            reader.nextEntry();
            assertNull(reader.nextEntry());
        }
        assertFalse(absent.exists());
    }

    @Test public void finalCrcFailureRetiresReaderWithoutSpooling() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(true);
        RarArchiveReader.RarEntry last = entries.get(1);
        byte[] bytes = Files.readAllBytes(last.sourceArchive.toPath());
        bytes[0] ^= 1;
        Files.write(last.sourceArchive.toPath(), bytes);
        fails(entries, "checksum");
    }

    @Test public void intermediateCrcFailureStillRejectsCorrectFinalBytes() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(true);
        RarArchiveReader.RarEntry first = entries.get(0);
        entries.set(0, part(Files.readAllBytes(first.sourceArchive.toPath()),
                first.unpackedSize, false, true, first.dataCrc ^ 1));
        fails(entries, "packed-volume checksum");
    }

    @Test public void absentIntermediateCrcSentinelStillAllowsFinalVerification() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(true);
        RarArchiveReader.RarEntry first = entries.get(0);
        entries.set(0, part(Files.readAllBytes(first.sourceArchive.toPath()),
                first.unpackedSize, false, true, 0xffffffffL));
        try (ArchiveSupport.ForwardArchiveReader reader = open(entries, temporary.newFile())) {
            reader.nextEntry();
            assertNull(reader.nextEntry());
        }
    }

    @Test public void truncatedVolumeRetiresReader() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(true);
        Files.write(entries.get(1).sourceArchive.toPath(), new byte[0]);
        fails(entries, "past its volume");
    }

    @Test public void drainBoundDoesNotConsumeOrRetireEntry() throws Exception {
        try (ArchiveSupport.ForwardArchiveReader reader = open(fixture(false), temporary.newFile())) {
            reader.nextEntry();
            assertThrows(IOException.class, () -> reader.drainCurrentEntry(1));
            assertTrue(reader.drainCurrentEntry(200_000));
            assertNull(reader.nextEntry());
        }
    }

    @Test public void cancellationDuringDiscardRetiresDecoder() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(false);
        Rar3LogicalSolidArchiveExtractor.Step step = new Rar3LogicalSolidArchiveExtractor.Step(entries.get(0), entries, true);
        Rar3LogicalSolidArchiveExtractor.Decoder decoder = new Rar3LogicalSolidArchiveExtractor.Decoder(
                new Rar3LogicalSolidArchiveExtractor.Plan(Arrays.asList(step), 200_000), null);
        FileOperationProgress progress = new FileOperationProgress("discard", null);
        progress.setListener(snapshot -> { if (snapshot.doneBytes > 0 && !snapshot.cancelled) progress.cancel(); });
        try {
            assertThrows(InterruptedIOException.class, () -> decoder.extractNext(null, progress));
            assertThrows(IOException.class, () -> decoder.extractNext(null, null));
        } finally { decoder.retire(); }
    }

    @Test public void readStillUsesVerifiedSpoolAndChecksBeforePublishing() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(false);
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = open(entries, directory)) {
            reader.nextEntry();
            byte[] bytes = new byte[7];
            assertEquals(7, reader.read(bytes));
            assertArrayEquals(Arrays.copyOf(data(), 7), bytes);
            assertEquals(1, directory.list().length);
            assertTrue(reader.drainCurrentEntry(0));
            assertEquals(0, directory.list().length);
        }
        Files.write(entries.get(0).sourceArchive.toPath(), new byte[200_000]);
        try (ArchiveSupport.ForwardArchiveReader reader = open(entries, directory)) {
            reader.nextEntry();
            byte[] bytes = {42};
            assertThrows(IOException.class, () -> reader.read(bytes));
            assertArrayEquals(new byte[]{42}, bytes);
        }
        assertEquals(0, directory.list().length);
    }

    private void fails(List<RarArchiveReader.RarEntry> entries, String message) throws Exception {
        File absent = new File(temporary.getRoot(), "unused");
        try (ArchiveSupport.ForwardArchiveReader reader = open(entries, absent)) {
            reader.nextEntry();
            IOException failure = assertThrows(IOException.class, () -> reader.drainCurrentEntry(Long.MAX_VALUE));
            assertTrue(failure.toString(), failure.getMessage().contains(message));
            assertTrue(failure instanceof RarSplitStoredFailure.ExtractionException);
            assertEquals(message.contains("checksum") ? RarSplitStoredFailure.Code.CRC_MISMATCH
                            : RarSplitStoredFailure.Code.IO_FAILURE,
                    ((RarSplitStoredFailure.ExtractionException) failure).failure().code);
            assertThrows(IOException.class, reader::nextEntry);
            assertThrows(IOException.class, () -> reader.read(new byte[1]));
        }
        assertFalse(absent.exists());
    }

    private ArchiveSupport.ForwardArchiveReader open(List<RarArchiveReader.RarEntry> entries, File spool) throws Exception {
        ArchiveSupport.ForwardArchiveReader reader = Rar3LogicalSolidArchiveExtractor.openForwardReader(entries, null, spool);
        assertNotNull(reader);
        return reader;
    }

    private List<RarArchiveReader.RarEntry> fixture(boolean split) throws Exception {
        byte[] bytes = data();
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        if (split) {
            byte[] first = Arrays.copyOf(bytes, 70_001);
            entries.add(part(first, bytes.length, false, true, crc(first)));
            entries.add(part(Arrays.copyOfRange(bytes, first.length, bytes.length), bytes.length, true, false, crc(bytes)));
        } else entries.add(part(bytes, bytes.length, false, false, crc(bytes)));
        return entries;
    }

    private RarArchiveReader.RarEntry part(byte[] bytes, long size, boolean before, boolean after, long crc) throws Exception {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("stored.bin", false,
                size, bytes.length, 0, 4, 0x30, true, before, after, null, crc, 0);
        entry.sourceArchive = temporary.newFile();
        Files.write(entry.sourceArchive.toPath(), bytes);
        return entry;
    }

    private static byte[] data() {
        byte[] bytes = new byte[200_000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31);
        return bytes;
    }
    private static long crc(byte[] bytes) { CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue(); }
}
