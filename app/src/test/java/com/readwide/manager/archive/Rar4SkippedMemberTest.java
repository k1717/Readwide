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

/** Physical framing regressions; synthetic compressed records test metadata, not decoding. */
public class Rar4SkippedMemberTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final byte[] PACKED = "not another RAR header".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SAFE = "safe member remains readable".getBytes(StandardCharsets.UTF_8);
    private static final int SOLID = 0x0010;
    private static final int LARGE = 0x0100;

    @Test public void skippedStoredNamesDoNotHideLaterSafePayload() throws Exception {
        for (String name : new String[]{"../escape.bin", "/absolute.bin", "C:\\absolute.bin", "bad\0name", "./"}) {
            File archive = archive(member(name, PACKED, 0x30, 0, 0),
                    member("safe.txt", SAFE, 0x30, 0, 0));
            List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
            assertEquals(name, 1, entries.size());
            assertEquals("safe.txt", entries.get(0).path);
            File output = temporary.newFile();
            assertTrue(RarArchiveReader.extractSingleEntry(archive, "safe.txt", output, null));
            assertArrayEquals(SAFE, Files.readAllBytes(output.toPath()));
        }
    }

    @Test public void skippedLargeHeaderWithZeroHighWordKeepsPhysicalOffset() throws Exception {
        File archive = archive(member("../escape.bin", PACKED, 0x30, LARGE, 0),
                member("safe.txt", SAFE, 0x30, 0, 0));
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        assertEquals(1, entries.size());
        assertEquals("safe.txt", entries.get(0).path);
        File output = temporary.newFile();
        assertTrue(RarArchiveReader.extractSingleEntry(archive, "safe.txt", output, null));
        assertArrayEquals(SAFE, Files.readAllBytes(output.toPath()));
    }

    @Test public void skippedLargeHeaderCannotIgnoreItsNonzeroHighWord() throws Exception {
        File archive = archive(member("../escape.bin", PACKED, 0x30, LARGE, 1),
                member("safe.txt", SAFE, 0x30, 0, 0));
        assertRejected(archive, "data size");
    }

    @Test public void skippedLargeHeaderRejectsSignedSizeOverflow() throws Exception {
        File archive = archive(member("../escape.bin", PACKED, 0x30, LARGE, 0x80000000L));
        assertRejected(archive, "numeric range");
    }

    @Test public void truncatedLargeHeaderIsNotTreatedAsZeroLength() throws Exception {
        File archive = archive(header(0x74, 0x8000 | LARGE, new byte[25]));
        assertRejected(archive, "Truncated RAR4 file header");
    }

    @Test public void omittedCompressedHistoryRejectsLaterSolidDependency() throws Exception {
        File archive = archive(member("prior.bin", PACKED, 0x31, 0, 0),
                member("../omitted.bin", PACKED, 0x31, SOLID, 0),
                member("dependent.bin", PACKED, 0x31, SOLID, 0));
        assertRejected(archive, "omitted unsafe-name member");
    }

    @Test public void independentCompressedEntryResetsOmittedHistory() throws Exception {
        File archive = archive(member("../omitted.bin", PACKED, 0x31, 0, 0),
                member("independent.bin", PACKED, 0x31, 0, 0),
                member("dependent.bin", PACKED, 0x31, SOLID, 0));
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        assertEquals(2, entries.size());
        assertEquals("independent.bin", entries.get(0).path);
        assertEquals("dependent.bin", entries.get(1).path);
    }

    @Test public void omittedStoredEntryDoesNotInvalidateCompressedHistory() throws Exception {
        File archive = archive(member("prior.bin", PACKED, 0x31, 0, 0),
                member("../omitted.bin", PACKED, 0x30, SOLID, 0),
                member("dependent.bin", PACKED, 0x31, SOLID, 0));
        assertEquals(2, RarArchiveReader.readEntries(archive, null).size());
    }

    @Test public void omittedDirectoryDoesNotInvalidateCompressedHistory() throws Exception {
        File archive = archive(member("prior.bin", PACKED, 0x31, 0, 0),
                member("./", new byte[0], 0x31, 0x00e0, 0),
                member("dependent.bin", PACKED, 0x31, SOLID, 0));
        assertEquals(2, RarArchiveReader.readEntries(archive, null).size());
    }

    @Test public void omittedHistoryRemainsInvalidAcrossVolumeBoundary() throws Exception {
        File first = temporary.newFile("history.rar");
        File second = temporary.newFile("history.r00");
        writeArchive(first, 0x0101, 1, member("../omitted.bin", PACKED, 0x31, 0, 0));
        writeArchive(second, 1, 0, member("dependent.bin", PACKED, 0x31, SOLID, 0));
        assertRejected(second, "omitted unsafe-name member");
    }

    @Test public void omittedHistoryFailureRetainsEarlierSelectedPayload() throws Exception {
        File archive = archive(member("safe.txt", SAFE, 0x30, 0, 0),
                member("../omitted.bin", PACKED, 0x31, 0, 0),
                member("dependent.bin", PACKED, 0x31, SOLID, 0));
        RarArchiveReader.EntryScan scan = RarArchiveReader.scanEntries(archive, null);
        List<RarArchiveReader.RarEntry> prefix = scan.forSelectedEntry("safe.txt");
        assertEquals(1, prefix.size());
        assertEquals(archive, prefix.get(0).sourceArchive);
        File output = temporary.newFile();
        RarArchiveReader.extractStoredEntry(prefix.get(0), output, null, prefix, null);
        assertArrayEquals(SAFE, Files.readAllBytes(output.toPath()));
        try {
            scan.forSelectedEntry("dependent.bin");
            fail("An omitted-history dependency must not be selected from the prefix");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("omitted unsafe-name member"));
        }
        assertRejected(archive, "omitted unsafe-name member");
    }

    private static void assertRejected(File archive, String message) throws Exception {
        try {
            RarArchiveReader.readEntries(archive, null);
            fail("Expected malformed or incomplete RAR4 metadata to fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }

    private File archive(byte[]... members) throws Exception {
        File archive = temporary.newFile();
        writeArchive(archive, members);
        return archive;
    }

    private static void writeArchive(File file, byte[]... members) throws Exception {
        writeArchive(file, 0, 0, members);
    }

    private static void writeArchive(File file, int mainFlags, int endFlags,
                                     byte[]... members) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[]{0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0});
            output.write(header(0x73, mainFlags, new byte[6]));
            for (byte[] member : members) output.write(member);
            output.write(header(0x7b, endFlags, new byte[0]));
        }
    }

    private static byte[] member(String name, byte[] payload, int method, int flags,
                                 long highPacked) throws Exception {
        byte[] rawName = name.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        littleEndian(body, payload.length, 4);
        littleEndian(body, payload.length, 4);
        body.write(2); // Windows host.
        CRC32 crc = new CRC32();
        crc.update(payload);
        littleEndian(body, crc.getValue(), 4);
        littleEndian(body, 0, 4); // No timestamp.
        body.write(29);
        body.write(method);
        littleEndian(body, rawName.length, 2);
        littleEndian(body, 0, 4);
        if ((flags & LARGE) != 0) {
            littleEndian(body, highPacked, 4);
            littleEndian(body, 0, 4);
        }
        body.write(rawName);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(header(0x74, 0x8000 | flags, body.toByteArray()));
        encoded.write(payload);
        return encoded.toByteArray();
    }

    private static byte[] header(int type, int flags, byte[] body) throws Exception {
        ByteArrayOutputStream checked = new ByteArrayOutputStream();
        checked.write(type);
        littleEndian(checked, flags, 2);
        littleEndian(checked, 7 + body.length, 2);
        checked.write(body);
        CRC32 crc = new CRC32();
        crc.update(checked.toByteArray());
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        littleEndian(header, crc.getValue(), 2);
        header.write(checked.toByteArray());
        return header.toByteArray();
    }

    private static void littleEndian(ByteArrayOutputStream out, long value, int count) {
        for (int i = 0; i < count; i++) out.write((int) (value >>> (i * 8)) & 0xff);
    }
}
