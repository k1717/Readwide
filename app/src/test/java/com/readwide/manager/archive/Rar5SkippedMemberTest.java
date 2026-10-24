package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Metadata-only compressed records isolate skipped-member history from codec behavior. */
public class Rar5SkippedMemberTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] PACKED = "synthetic metadata payload".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SAFE = "selected safe payload".getBytes(StandardCharsets.UTF_8);
    private static final int COMPRESSED = 0x80;
    private static final int SOLID = 0x40;

    @Test public void omittedCompressedHistoryCannotRelyOnOptionalChecksum() throws Exception {
        File archive = archive(member("prior.bin", PACKED, COMPRESSED, 0, 0),
                member("../omitted.bin", PACKED, COMPRESSED | SOLID, 0, 0),
                member("dependent.bin", PACKED, COMPRESSED | SOLID, 0, 0));
        assertRejected(archive);
    }

    @Test public void independentCompressedHeadResetsOmittedHistory() throws Exception {
        File archive = archive(member("../omitted.bin", PACKED, COMPRESSED, 0, 0),
                member("independent.bin", PACKED, COMPRESSED, 0, 0),
                member("dependent.bin", PACKED, COMPRESSED | SOLID, 0, 0));
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        assertEquals(2, entries.size());
        assertEquals("independent.bin", entries.get(0).path);
        assertEquals("dependent.bin", entries.get(1).path);
        assertEquals(-1L, entries.get(1).dataCrc); // Optional CRC remains optional.
    }

    @Test public void skippedStoredMemberDoesNotInvalidateHistory() throws Exception {
        File archive = archive(member("prior.bin", PACKED, COMPRESSED, 0, 0),
                member("../omitted.bin", PACKED, SOLID, 0, 0),
                member("dependent.bin", PACKED, COMPRESSED | SOLID, 0, 0));
        assertEquals(2, RarArchiveReader.readEntries(archive, null).size());
    }

    @Test public void skippedDirectoryDoesNotInvalidateHistory() throws Exception {
        File archive = archive(member("prior.bin", PACKED, COMPRESSED, 0, 0),
                member("./", new byte[0], COMPRESSED, 1, 0),
                member("dependent.bin", PACKED, COMPRESSED | SOLID, 0, 0));
        assertEquals(2, RarArchiveReader.readEntries(archive, null).size());
    }

    @Test public void splitContinuationCannotResetOmittedHistory() throws Exception {
        File archive = archive(member("../omitted.bin", PACKED, COMPRESSED, 0, 0),
                member("continuation.bin", PACKED, COMPRESSED, 0, 8));
        assertRejected(archive);
    }

    @Test public void omittedEmptyCompressedResetStillInvalidatesHistory() throws Exception {
        File archive = archive(member("prior.bin", PACKED, COMPRESSED, 0, 0),
                member("../empty.bin", new byte[0], COMPRESSED, 0, 0),
                member("dependent.bin", PACKED, COMPRESSED | SOLID, 0, 0));
        assertRejected(archive);
    }

    @Test public void omittedHistoryRemainsInvalidAcrossVolumes() throws Exception {
        File first = temporary.newFile("history.part1.rar");
        File second = temporary.newFile("history.part2.rar");
        writeArchive(first, 0, member("../omitted.bin", PACKED, COMPRESSED, 0, 0));
        writeArchive(second, 1, member("dependent.bin", PACKED, COMPRESSED | SOLID, 0, 0));
        assertRejected(second);
    }

    @Test public void independentResetAcrossVolumesRestoresUsableMetadata() throws Exception {
        File first = temporary.newFile("reset.part1.rar");
        File second = temporary.newFile("reset.part2.rar");
        writeArchive(first, 0, member("../omitted.bin", PACKED, COMPRESSED, 0, 0));
        writeArchive(second, 1, member("independent.bin", PACKED, COMPRESSED, 0, 0),
                member("dependent.bin", PACKED, COMPRESSED | SOLID, 0, 0));
        assertEquals(2, RarArchiveReader.readEntries(second, null).size());
    }

    @Test public void selectedSafePrefixRemainsRecoverableWithoutPublishingDependency() throws Exception {
        File archive = archive(member("safe.txt", SAFE, 0, 0, 0),
                member("../omitted.bin", PACKED, COMPRESSED, 0, 0),
                member("dependent.bin", PACKED, COMPRESSED | SOLID, 0, 0));
        RarArchiveReader.EntryScan scan = RarArchiveReader.scanEntries(archive, null);
        List<RarArchiveReader.RarEntry> prefix = scan.forSelectedEntry("safe.txt");
        assertEquals(1, prefix.size());
        assertEquals(archive, prefix.get(0).sourceArchive);
        File output = temporary.newFile();
        RarArchiveReader.extractStoredEntry(prefix.get(0), output, null, prefix, null);
        assertArrayEquals(SAFE, Files.readAllBytes(output.toPath()));
        try {
            scan.forSelectedEntry("dependent.bin");
            fail("Missing compressed history must not enter selected extraction");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("omitted unsafe-name member"));
        }
        assertRejected(archive);
    }

    @Test public void forwardReaderRejectsMissingHistoryBeforeCreatingSpools() throws Exception {
        File archive = archive(member("../omitted.bin", PACKED, COMPRESSED, 0, 0),
                member("dependent.jpg", PACKED, COMPRESSED | SOLID, 0, 0));
        File spool = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader ignored =
                     Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, false)) {
            fail("Forward reader must not start with omitted history");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("omitted unsafe-name member"));
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void omittedCompressedMemberDoesNotBlockIndependentStoredPayload() throws Exception {
        File archive = archive(member("../omitted.bin", PACKED, COMPRESSED, 0, 0),
                member("safe.txt", SAFE, 0, 0, 0));
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        assertEquals(1, entries.size());
        File output = temporary.newFile();
        RarArchiveReader.extractStoredEntry(entries.get(0), output, null, entries, null);
        assertArrayEquals(SAFE, Files.readAllBytes(output.toPath()));
    }

    private static void assertRejected(File archive) throws Exception {
        try {
            RarArchiveReader.readEntries(archive, null);
            fail("Expected incomplete compressed history to fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("omitted unsafe-name member"));
        }
    }

    private File archive(byte[]... members) throws Exception {
        File file = temporary.newFile();
        writeArchive(file, -1, members);
        return file;
    }

    private static void writeArchive(File file, int volumeNumber, byte[]... members) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0});
            ByteArrayOutputStream main = new ByteArrayOutputStream();
            vint(main, 1); vint(main, 0); vint(main, volumeNumber < 0 ? 4 : 7);
            if (volumeNumber >= 0) vint(main, volumeNumber);
            out.write(header(main.toByteArray()));
            for (byte[] member : members) out.write(member);
            out.write(header(new byte[]{5, 0, (byte) (volumeNumber == 0 ? 1 : 0)}));
        }
    }

    private static byte[] member(String name, byte[] payload, int compression,
                                 int fileFlags, int splitFlags) throws Exception {
        byte[] rawName = name.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        vint(body, 2); vint(body, 2 | splitFlags); vint(body, payload.length);
        vint(body, fileFlags); vint(body, payload.length); vint(body, 0);
        vint(body, compression); vint(body, 0); vint(body, rawName.length);
        body.write(rawName);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(header(body.toByteArray()));
        encoded.write(payload);
        return encoded.toByteArray();
    }

    private static byte[] header(byte[] body) throws Exception {
        ByteArrayOutputStream checked = new ByteArrayOutputStream();
        vint(checked, body.length);
        checked.write(body);
        CRC32 crc = new CRC32();
        crc.update(checked.toByteArray());
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (int i = 0; i < 4; i++) result.write((int) (crc.getValue() >>> (i * 8)) & 0xff);
        result.write(checked.toByteArray());
        return result.toByteArray();
    }

    private static void vint(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            out.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }
}
