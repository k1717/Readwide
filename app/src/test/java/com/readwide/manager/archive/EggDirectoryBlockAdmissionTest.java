package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Synthetic metadata boundaries; these fixtures do not establish producer interoperability. */
public class EggDirectoryBlockAdmissionTest {
    private static final int FILE = 0x0a8590e3;
    private static final int NAME = 0x0a8591ac;
    private static final int BLOCK = 0x02b50c13;
    private static final int SOLID = 0x24e5a060;
    private static final int ENCRYPT = 0x08d1470f;
    private static final byte[] CONTENT = "shared block\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY = new byte[0];
    private static final String REJECTION = "Non-solid EGG directory has block data";

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void trailingSlashDirectoryRejectsNonemptyBlockBeforeOutput() throws Exception {
        reject(archive(false, directory("folder/", EMPTY, CONTENT, 0, false)), null);
    }

    @Test public void windowsDirectoryAttributeRejectsNonemptyBlockBeforeOutput() throws Exception {
        reject(archive(false, directory("folder", windowsDirectory(), CONTENT, 0, false)), null);
    }

    @Test public void posixDirectoryModeRejectsNonemptyBlockBeforeOutput() throws Exception {
        reject(archive(false, directory("folder", posixDirectory(), CONTENT, 0, false)), null);
    }

    @Test public void encryptedSplitDirectoryRejectsBeforePasswordOrOutput() throws Exception {
        File whole = archive(false, directory("folder/", EMPTY, CONTENT, 1, true));
        List<File> parts = Round4Fixtures.splitEgg(temp.newFolder(), "directory",
                Files.readAllBytes(whole.toPath()), 3);
        reject(parts.get(0), null);
        reject(parts.get(parts.size() - 1), Round4Fixtures.PASSWORD);
    }

    @Test public void emptyDirectoryFormsRemainAccepted() throws Exception {
        String[] names = {"folder/", "folder", "folder"};
        byte[][] metadata = {EMPTY, windowsDirectory(), posixDirectory()};
        for (int kind = 0; kind < names.length; kind++) {
            for (int method : new int[]{-1, 0, 1}) {
                byte[] record = method < 0
                        ? header(0, names[kind], 0, metadata[kind])
                        : directory(names[kind], metadata[kind], EMPTY, method, false);
                File archive = archive(false, record);
                List<ArchiveSupport.EntryInfo> entries = EggArchiveReader.listEntries(archive, null);
                assertEquals(1, entries.size());
                assertTrue(entries.get(0).directory);
                assertEquals(0, entries.get(0).size);
                File output = temp.newFolder();
                assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive, output, null));
                assertTrue(new File(output, "folder").isDirectory());
                assertEquals(0, new File(output, "folder").list().length);
            }
        }
    }

    @Test public void ordinaryEncryptedSplitFileStillExtractsExactly() throws Exception {
        File whole = archive(false, file("data.bin", CONTENT, 1, true));
        List<File> parts = Round4Fixtures.splitEgg(temp.newFolder(), "regular",
                Files.readAllBytes(whole.toPath()), 3);
        assertFile(parts.get(parts.size() - 1), Round4Fixtures.PASSWORD, false);
    }

    @Test public void solidDirectoryOwnerKeepsSharedBlocksForFiles() throws Exception {
        for (boolean encrypted : new boolean[]{false, true}) {
            File archive = archive(!encrypted, header(0, "data.bin", CONTENT.length,
                    encrypted ? extra(SOLID, EMPTY) : EMPTY),
                    directory("folder/", EMPTY, CONTENT, 1, encrypted));
            if (encrypted) {
                List<File> parts = Round4Fixtures.splitEgg(temp.newFolder(), "solid",
                        Files.readAllBytes(archive.toPath()), 3);
                archive = parts.get(parts.size() - 1);
            }
            assertFile(archive, encrypted ? Round4Fixtures.PASSWORD : null, true);
        }
    }

    @Test public void laterFileSolidFlagAppliesToEarlierDirectoryBlockOwner() throws Exception {
        File archive = archive(false, directory("folder/", EMPTY, CONTENT, 0, false),
                header(2, "data.bin", CONTENT.length, extra(SOLID, EMPTY)));
        assertFile(archive, null, true);
    }

    private void reject(File archive, char[] password) throws Exception {
        IOException listing = assertThrows(IOException.class,
                () -> EggArchiveReader.listEntries(archive, password));
        assertEquals(REJECTION, listing.getMessage());

        File selected = temp.newFile();
        byte[] original = {42};
        Files.write(selected.toPath(), original);
        IOException selection = assertThrows(IOException.class,
                () -> EggArchiveReader.extractSingleEntry(archive, "data.bin", selected, password));
        assertEquals(REJECTION, selection.getMessage());
        assertArrayEquals(original, Files.readAllBytes(selected.toPath()));

        File output = temp.newFolder();
        File existing = new File(output, "keep.bin");
        Files.write(existing.toPath(), original);
        IOException bulk = assertThrows(IOException.class,
                () -> EggArchiveReader.extractArchiveIntoDirectory(archive, output, password));
        assertEquals(REJECTION, bulk.getMessage());
        assertArrayEquals(original, Files.readAllBytes(existing.toPath()));
        assertEquals(1, output.list().length);
    }

    private void assertFile(File archive, char[] password, boolean hasDirectory) throws Exception {
        List<ArchiveSupport.EntryInfo> entries = EggArchiveReader.listEntries(archive, password);
        assertEquals(hasDirectory ? 2 : 1, entries.size());
        File selected = temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(archive, "data.bin", selected, password));
        assertArrayEquals(CONTENT, Files.readAllBytes(selected.toPath()));
        File output = temp.newFolder();
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive, output, password));
        assertArrayEquals(CONTENT, Files.readAllBytes(new File(output, "data.bin").toPath()));
        assertEquals(hasDirectory ? 2 : 1, output.list().length);
        if (hasDirectory) assertTrue(new File(output, "folder").isDirectory());
    }

    private File archive(boolean solid, byte[]... records) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Round4Fixtures.le(out, Round4Fixtures.EGG, 4);
        Round4Fixtures.le(out, 0x100, 2);
        Round4Fixtures.le(out, 17, 4);
        Round4Fixtures.le(out, 0, 4);
        if (solid) out.write(extra(SOLID, EMPTY));
        Round4Fixtures.le(out, Round4Fixtures.END, 4);
        for (byte[] record : records) out.write(record);
        Round4Fixtures.le(out, Round4Fixtures.END, 4);
        return Round4Fixtures.write(temp.newFolder(), "fixture.egg", out.toByteArray());
    }

    private static byte[] directory(String name, byte[] metadata, byte[] plain,
                                    int method, boolean encrypted) throws IOException {
        return entry(1, name, 0, metadata, plain, method, encrypted);
    }

    private static byte[] file(String name, byte[] plain, int method, boolean encrypted)
            throws IOException {
        return entry(0, name, plain.length, EMPTY, plain, method, encrypted);
    }

    private static byte[] entry(int id, String name, long fileSize, byte[] metadata,
                                byte[] plain, int method, boolean encrypted) throws IOException {
        byte[] packed = method == 1 ? Round4Fixtures.deflate(plain) : plain;
        ByteArrayOutputStream extras = new ByteArrayOutputStream();
        extras.write(metadata);
        if (encrypted) {
            Round4Fixtures.Crypto cipher = new Round4Fixtures.Crypto(Round4Fixtures.PASSWORD);
            byte[] verifier = new byte[12];
            verifier[11] = (byte) (Round4Fixtures.crc(plain) >>> 24);
            ByteArrayOutputStream encryption = new ByteArrayOutputStream();
            encryption.write(0);
            encryption.write(cipher.encrypt(verifier));
            Round4Fixtures.le(encryption, Round4Fixtures.crc(plain), 4);
            extras.write(extra(ENCRYPT, encryption.toByteArray()));
            packed = cipher.encrypt(packed);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header(id, name, fileSize, extras.toByteArray()));
        Round4Fixtures.le(out, BLOCK, 4);
        out.write(method);
        out.write(0);
        Round4Fixtures.le(out, plain.length, 4);
        Round4Fixtures.le(out, packed.length, 4);
        Round4Fixtures.le(out, Round4Fixtures.crc(plain), 4);
        Round4Fixtures.le(out, Round4Fixtures.END, 4);
        out.write(packed);
        return out.toByteArray();
    }

    private static byte[] header(int id, String name, long size, byte[] metadata)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Round4Fixtures.le(out, FILE, 4);
        Round4Fixtures.le(out, id, 4);
        Round4Fixtures.le(out, size, 8);
        out.write(extra(NAME, name.getBytes(StandardCharsets.UTF_8)));
        out.write(metadata);
        Round4Fixtures.le(out, Round4Fixtures.END, 4);
        return out.toByteArray();
    }

    private static byte[] windowsDirectory() throws IOException {
        byte[] attributes = new byte[9];
        attributes[8] = (byte) 0x80;
        return extra(0x2c86950b, attributes);
    }

    private static byte[] posixDirectory() throws IOException {
        byte[] attributes = new byte[20];
        attributes[1] = 0x40;
        return extra(0x1ee922e5, attributes);
    }

    private static byte[] extra(int signature, byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Round4Fixtures.le(out, signature, 4);
        out.write(0);
        Round4Fixtures.le(out, payload.length, 2);
        out.write(payload);
        return out.toByteArray();
    }
}
