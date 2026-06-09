package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.textview.reader.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

public class AlzipArchiveReaderTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void archiveSupport_detectsAlzAndEggNames() {
        assertEquals(ArchiveSupport.Type.ALZ, ArchiveSupport.getSupportedArchiveType("sample.alz"));
        assertEquals(ArchiveSupport.Type.EGG, ArchiveSupport.getSupportedArchiveType("sample.egg"));
        assertEquals(ArchiveSupport.Type.EGG, ArchiveSupport.getSupportedArchiveType("sample.vol1.egg"));
        assertEquals(ArchiveSupport.Type.SEVEN_Z, ArchiveSupport.getSupportedArchiveType("sample.cb7"));
        assertEquals(ArchiveSupport.Type.TAR, ArchiveSupport.getSupportedArchiveType("sample.cbt"));
        assertTrue(FileUtils.isArchiveFile("sample.alz"));
        assertTrue(FileUtils.isArchiveFile("sample.egg"));
        assertTrue(FileUtils.isArchiveFile("sample.vol1.egg"));
        assertTrue(FileUtils.isArchiveFile("sample.cb7"));
        assertTrue(FileUtils.isArchiveFile("sample.cbt"));
    }

    @Test
    public void archiveSupport_recognizesAlzipSplitPartNames() throws Exception {
        writeStubArchive("comic.vol1.egg", "EGGA");
        File eggPart2 = writeStubArchive("comic.vol2.egg", "PART");
        writeStubArchive("legacy.alz", "ALZ\1");
        File alzPart = writeStubArchive("legacy.a00", "PART");

        assertEquals(ArchiveSupport.Type.EGG, ArchiveSupport.getSupportedArchiveType(eggPart2));
        assertEquals(ArchiveSupport.Type.ALZ, ArchiveSupport.getSupportedArchiveType(alzPart));
        assertTrue(ArchiveSupport.requiresPasswordForExtraction(alzPart));
    }

    @Test
    public void extraction_alzipFormatsFailExplicitlyUntilDecoderIsAvailable() throws Exception {
        File archive = tempFolder.newFile("sample.egg");
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write("EGGA".getBytes(StandardCharsets.US_ASCII));
        }

        File target = tempFolder.newFolder("out");
        assertFalse(ArchiveSupport.extractArchive(archive, target, true, null));
    }

    @Test
    public void listEntries_truncatedAlzFailsExplicitly() throws Exception {
        File archive = writeStubArchive("sample.alz", "ALZ\1");

        try {
            ArchiveSupport.listEntries(archive, "pass".toCharArray());
            fail("Expected truncated ALZ listing to fail explicitly");
        } catch (IOException e) {
            assertTrue(e.getMessage() != null && e.getMessage().length() > 0);
        }
    }

    @Test
    public void listEntries_alzStoredArchive_returnsMetadata() throws Exception {
        File archive = buildAlzArchive("book/page001.txt", "stored".getBytes(StandardCharsets.UTF_8), 0, false, null);

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);

        assertEquals(2, entries.size());
        assertEquals("book/", entries.get(0).path);
        assertEquals("book/page001.txt", entries.get(1).path);
        assertEquals(6L, entries.get(1).size);
    }

    @Test
    public void extractSingleEntry_alzStoredArchive_writesPayload() throws Exception {
        byte[] payload = "stored payload".getBytes(StandardCharsets.UTF_8);
        File archive = buildAlzArchive("page001.txt", payload, 0, false, null);
        File out = tempFolder.newFile("alz-stored.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page001.txt", out, null));

        assertEquals("stored payload", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void extractSingleEntry_alzEncryptedStoredArchive_requiresAndUsesPassword() throws Exception {
        byte[] payload = "encrypted stored payload".getBytes(StandardCharsets.UTF_8);
        File archive = buildAlzArchive("page001.txt", payload, 0, true, "pw".toCharArray());
        File out = tempFolder.newFile("alz-encrypted-stored.txt");

        assertTrue(ArchiveSupport.requiresPasswordForExtraction(archive));
        assertFalse(ArchiveSupport.extractSingleEntry(archive, "page001.txt", out, null));
        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page001.txt", out, "pw".toCharArray()));
        assertEquals("encrypted stored payload", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void extractSingleEntry_alzEncryptedDeflateArchive_usesPasswordAndInflates() throws Exception {
        byte[] payload = "encrypted deflate payload".getBytes(StandardCharsets.UTF_8);
        File archive = buildAlzArchive("page001.txt", payload, 2, true, "pw".toCharArray());
        File out = tempFolder.newFile("alz-encrypted-deflate.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page001.txt", out, "pw".toCharArray()));

        assertEquals("encrypted deflate payload", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void listEntries_malformedEggFailsExplicitly() throws Exception {
        try {
            ArchiveSupport.listEntries(writeStubArchive("sample.egg", "EGGA"), null);
            fail("Expected malformed EGG listing to fail explicitly");
        } catch (IOException e) {
            assertTrue(e.getMessage() != null && e.getMessage().length() > 0);
        }
    }

    @Test
    public void alzipReader_detectsContainerFamilyFromSignature() throws Exception {
        assertEquals(AlzipArchiveReader.Family.ALZ,
                AlzipArchiveReader.detectFamily(writeStubArchive("sample.alz", "ALZ\1")));
        assertEquals(AlzipArchiveReader.Family.EGG,
                AlzipArchiveReader.detectFamily(writeStubArchive("sample.egg", "EGGA")));
        assertEquals(AlzipArchiveReader.Family.UNKNOWN,
                AlzipArchiveReader.detectFamily(writeStubArchive("sample-invalid.egg", "NOPE")));
    }

    @Test
    public void extractSingleEntry_alzipFormatsFailWithoutLeavingPreviewFile() throws Exception {
        File archive = writeStubArchive("sample.egg", "EGGA");
        File out = tempFolder.newFile("preview.bin");
        Files.write(out.toPath(), "stale".getBytes(StandardCharsets.UTF_8));

        assertFalse(ArchiveSupport.extractSingleEntry(archive, "page001.jpg", out, null));
        assertFalse(out.exists());
    }

    @Test
    public void archiveOutputBaseName_handlesAlzipExtensions() {
        assertEquals("sample", ArchiveSupport.getArchiveOutputBaseName(new File("sample.alz"), "fallback"));
        assertEquals("sample", ArchiveSupport.getArchiveOutputBaseName(new File("sample.egg"), "fallback"));
    }

    private File writeStubArchive(String name, String signature) throws Exception {
        File archive = tempFolder.newFile(name);
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(signature.getBytes(StandardCharsets.ISO_8859_1));
        }
        return archive;
    }

    private File buildAlzArchive(String entryName,
                                 byte[] plainPayload,
                                 int method,
                                 boolean encrypted,
                                 char[] password) throws Exception {
        File archive = tempFolder.newFile("fixture-" + System.nanoTime() + ".alz");
        byte[] storedPayload = method == 2 ? rawDeflate(plainPayload) : plainPayload;
        CRC32 crc = new CRC32();
        crc.update(plainPayload);
        byte[] encryptedHeader = null;
        if (encrypted) {
            Encryptor encryptor = new Encryptor(password);
            byte[] header = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, (byte) ((crc.getValue() >>> 24) & 0xff)};
            encryptedHeader = encryptor.encrypt(header);
            storedPayload = encryptor.encrypt(storedPayload);
        }
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(archive)) {
            writeIntLE(out, 0x015a4c41);
            writeIntLE(out, 0);
            writeIntLE(out, 0x015a4c42);
            writeShortLE(out, name.length);
            out.write(0x20);
            writeIntLE(out, 0);
            out.write(0x40 | (encrypted ? 0x01 : 0));
            out.write(0);
            out.write(method);
            out.write(0);
            writeIntLE(out, (int) crc.getValue());
            writeIntLE(out, storedPayload.length);
            writeIntLE(out, plainPayload.length);
            out.write(name);
            if (encryptedHeader != null) out.write(encryptedHeader);
            out.write(storedPayload);
            writeIntLE(out, 0x025a4c43);
        }
        return archive;
    }

    private byte[] rawDeflate(byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out, new Deflater(Deflater.DEFAULT_COMPRESSION, true))) {
            deflater.write(payload);
        }
        return out.toByteArray();
    }

    private void writeIntLE(FileOutputStream out, int value) throws Exception {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private void writeShortLE(FileOutputStream out, int value) throws Exception {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static final class Encryptor {
        private static final int[] CRC_TABLE = buildCrcTable();
        private final int[] keys = new int[3];

        Encryptor(char[] password) {
            keys[0] = 305419896;
            keys[1] = 591751049;
            keys[2] = 878082192;
            for (char ch : password) updateKeys((byte) ch);
        }

        byte[] encrypt(byte[] plain) {
            byte[] out = java.util.Arrays.copyOf(plain, plain.length);
            for (int i = 0; i < out.length; i++) {
                byte value = out[i];
                out[i] = (byte) (value ^ decryptKeyByte());
                updateKeys(value);
            }
            return out;
        }

        private int decryptKeyByte() {
            int temp = (keys[2] & 0xffff) | 2;
            return ((temp * (temp ^ 1)) >>> 8) & 0xff;
        }

        private void updateKeys(byte plain) {
            keys[0] = CRC_TABLE[(keys[0] ^ plain) & 0xff] ^ (keys[0] >>> 8);
            keys[1] = keys[1] + (keys[0] & 0xff);
            keys[1] = keys[1] * 134775813 + 1;
            keys[2] = CRC_TABLE[(keys[2] ^ (byte) (keys[1] >>> 24)) & 0xff] ^ (keys[2] >>> 8);
        }

        private static int[] buildCrcTable() {
            int[] table = new int[256];
            for (int i = 0; i < table.length; i++) {
                int value = i;
                for (int bit = 0; bit < 8; bit++) {
                    value = (value & 1) != 0 ? (value >>> 1) ^ 0xedb88320 : value >>> 1;
                }
                table[i] = value;
            }
            return table;
        }
    }
}
