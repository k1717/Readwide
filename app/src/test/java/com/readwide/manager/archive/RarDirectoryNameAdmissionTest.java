package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Synthetic metadata checks for Java fallback admission, not compressed codec fixtures. */
public class RarDirectoryNameAdmissionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] PACKED = {1, 2, 3};
    private static final byte[] KEEP = {7, 8};

    @Test public void rar4CompressedDirectoryShapedNamesCannotOmitSolidHistory() throws Exception {
        for (String name : new String[]{"folder/", "folder\\", "./folder//"}) {
            File archive = archive(4,
                    member(4, "prior.bin", PACKED, false, true, false),
                    member(4, name, PACKED, false, true, true),
                    member(4, "dependent.bin", PACKED, false, true, true));
            assertRejectedBeforeOutput(archive);
        }
    }

    @Test public void rar5NoChecksumSolidMembersCannotFollowSkippedRegularPayload() throws Exception {
        for (String name : new String[]{"folder/", "folder\\", "./folder//"}) {
            File archive = archive(5,
                    member(5, "prior.bin", PACKED, false, true, false),
                    member(5, name, PACKED, false, true, true),
                    member(5, "dependent.bin", PACKED, false, true, true));
            assertRejectedBeforeOutput(archive);
        }
    }

    @Test public void storedRegularPayloadIsNotSilentlyReclassifiedAsDirectory() throws Exception {
        for (int version : new int[]{4, 5}) {
            File archive = archive(version,
                    member(version, "folder/", PACKED, false, false, false),
                    member(version, "dependent.bin", PACKED, false, false, false));
            assertRejectedBeforeOutput(archive);
        }
    }

    @Test public void explicitDirectoryFlagsStillAllowNamesWithOrWithoutSlash() throws Exception {
        for (int version : new int[]{4, 5}) {
            File archive = archive(version,
                    member(version, "folder", new byte[0], true, false, false),
                    member(version, "other/", new byte[0], true, false, false),
                    member(version, "folder/data.bin", KEEP, false, false, false));
            List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
            assertEquals(3, entries.size());
            assertTrue(entries.get(0).directory); assertEquals("folder/", entries.get(0).path);
            assertTrue(entries.get(1).directory); assertEquals("other/", entries.get(1).path);
            assertFalse(entries.get(2).directory);
            File output = temporary.newFile();
            assertTrue(RarArchiveReader.extractSingleEntry(archive, "folder/data.bin", output, null));
            assertArrayEquals(KEEP, Files.readAllBytes(output.toPath()));
            File root = temporary.newFolder();
            assertTrue(RarArchiveReader.extractArchiveIntoDirectory(archive, root, null));
            assertTrue(new File(root, "folder").isDirectory());
            assertTrue(new File(root, "other").isDirectory());
            assertArrayEquals(KEEP, Files.readAllBytes(new File(root, "folder/data.bin").toPath()));
        }
    }

    @Test public void realDirectoryDoesNotChangeCompressedSolidHistory() throws Exception {
        for (int version : new int[]{4, 5}) {
            File archive = archive(version,
                    member(version, "prior.bin", PACKED, false, true, false),
                    member(version, "folder/", new byte[0], true, true, false),
                    member(version, "dependent.bin", PACKED, false, true, true));
            List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
            assertEquals(3, entries.size());
            assertTrue(entries.get(1).directory); assertTrue(entries.get(2).solid);
            if (version == 5) assertEquals(-1L, entries.get(2).dataCrc);
        }
    }

    @Test public void malformedLaterNameDoesNotDiscardEarlierSelectedRecovery() throws Exception {
        for (int version : new int[]{4, 5}) {
            File archive = archive(version,
                    member(version, "safe.bin", KEEP, false, false, false),
                    member(version, "folder/", PACKED, false, true, false),
                    member(version, "dependent.bin", PACKED, false, true, true));
            RarArchiveReader.EntryScan scan = RarArchiveReader.scanEntries(archive, null);
            List<RarArchiveReader.RarEntry> prefix = scan.forSelectedEntry("safe.bin");
            assertEquals(1, prefix.size()); assertEquals(archive, prefix.get(0).sourceArchive);
            File output = temporary.newFile();
            assertTrue(RarArchiveReader.extractSingleEntry(archive, "safe.bin", output, null));
            assertArrayEquals(KEEP, Files.readAllBytes(output.toPath()));
            assertDirectoryNameFailure(assertThrows(IOException.class, scan::requireComplete));
            assertDirectoryNameFailure(assertThrows(IOException.class,
                    () -> scan.forSelectedEntry("dependent.bin")));
        }
    }

    private void assertRejectedBeforeOutput(File archive) throws Exception {
        assertDirectoryNameFailure(assertThrows(IOException.class,
                () -> RarArchiveReader.readEntries(archive, null)));
        RarArchiveReader.EntryScan scan = RarArchiveReader.scanEntries(archive, null);
        assertDirectoryNameFailure(assertThrows(IOException.class,
                () -> scan.forSelectedEntry("dependent.bin")));
        File selected = temporary.newFile(); Files.write(selected.toPath(), KEEP);
        assertDirectoryNameFailure(assertThrows(IOException.class,
                () -> RarArchiveReader.extractSingleEntry(archive, "dependent.bin", selected, null)));
        assertArrayEquals(KEEP, Files.readAllBytes(selected.toPath()));
        File root = temporary.newFolder();
        File old = new File(root, "dependent.bin"); Files.write(old.toPath(), KEEP);
        assertDirectoryNameFailure(assertThrows(IOException.class,
                () -> RarArchiveReader.extractArchiveIntoDirectory(archive, root, null)));
        assertArrayEquals(KEEP, Files.readAllBytes(old.toPath()));
        assertArrayEquals(new String[]{"dependent.bin"}, root.list());
    }

    private static void assertDirectoryNameFailure(IOException failure) {
        assertTrue(failure.getMessage(), failure.getMessage().contains("directory-shaped name"));
    }

    private File archive(int version, byte[]... members) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[]{'R', 'a', 'r', '!', 0x1a, 7, (byte) (version == 5 ? 1 : 0)});
        if (version == 5) {
            output.write(0);
            output.write(rar5Header(new byte[]{1, 0, 4})); // Main, solid archive.
        } else output.write(rar4Header(0x73, 8, new byte[6]));
        for (byte[] member : members) output.write(member);
        output.write(version == 5 ? rar5Header(new byte[]{5, 0, 0})
                : rar4Header(0x7b, 0, new byte[0]));
        File file = new File(temporary.newFolder(), "names.rar");
        Files.write(file.toPath(), output.toByteArray());
        return file;
    }

    private static byte[] member(int version, String name, byte[] payload,
                                 boolean directory, boolean compressed, boolean solid) throws Exception {
        byte[] encodedName = name.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        if (version == 5) {
            vint(body, 2); vint(body, 2); vint(body, payload.length);
            vint(body, directory ? 1 : 0); // No optional CRC or hash.
            vint(body, payload.length); vint(body, 0);
            vint(body, (compressed ? 0x80 : 0) | (solid ? 0x40 : 0));
            vint(body, 0); vint(body, encodedName.length); body.write(encodedName);
            result.write(rar5Header(body.toByteArray()));
        } else {
            little(body, payload.length, 4); little(body, payload.length, 4);
            body.write(2); little(body, crc(payload), 4); little(body, 0, 4);
            body.write(29); body.write(compressed ? 0x31 : 0x30);
            little(body, encodedName.length, 2); little(body, directory ? 0x10 : 0x20, 4);
            body.write(encodedName);
            result.write(rar4Header(0x74, 0x8000 | (directory ? 0xe0 : 0) | (solid ? 0x10 : 0),
                    body.toByteArray()));
        }
        result.write(payload);
        return result.toByteArray();
    }

    private static byte[] rar4Header(int type, int flags, byte[] body) throws IOException {
        ByteArrayOutputStream checked = new ByteArrayOutputStream();
        checked.write(type); little(checked, flags, 2); little(checked, 7 + body.length, 2); checked.write(body);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        little(result, crc(checked.toByteArray()), 2); result.write(checked.toByteArray());
        return result.toByteArray();
    }

    private static byte[] rar5Header(byte[] body) throws IOException {
        ByteArrayOutputStream checked = new ByteArrayOutputStream();
        vint(checked, body.length); checked.write(body);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        little(result, crc(checked.toByteArray()), 4); result.write(checked.toByteArray());
        return result.toByteArray();
    }

    private static long crc(byte[] bytes) {
        CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue();
    }

    private static void little(ByteArrayOutputStream output, long value, int width) {
        for (int i = 0; i < width; i++) output.write((int) (value >>> (8 * i)));
    }

    private static void vint(ByteArrayOutputStream output, long value) {
        do {
            int next = (int) value & 0x7f; value >>>= 7;
            output.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }
}
