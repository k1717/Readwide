package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

public class EggArchiveReaderTest {
    private static final int MAGIC_EGG = 0x41474745;
    private static final int MAGIC_FILE = 0x0a8590e3;
    private static final int MAGIC_BLOCK = 0x02b50c13;
    private static final int MAGIC_ENCRYPT = 0x08d1470f;
    private static final int MAGIC_FILENAME = 0x0a8591ac;
    private static final int MAGIC_END = 0x08e28222;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void listEntries_eggStoredArchive_returnsMetadata() throws Exception {
        File archive = buildEggArchive("book/page001.txt", "stored".getBytes(StandardCharsets.UTF_8), 0, false);

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);

        assertEquals(1, entries.size());
        assertEquals("book/page001.txt", entries.get(0).path);
        assertEquals(6L, entries.get(0).size);
    }

    @Test
    public void extractSingleEntry_eggStoredArchive_writesPayload() throws Exception {
        File archive = buildEggArchive("page001.txt", "stored payload".getBytes(StandardCharsets.UTF_8), 0, false);
        File out = tempFolder.newFile("egg-stored.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page001.txt", out, null));

        assertEquals("stored payload", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void extractSingleEntry_eggDeflateArchive_inflatesPayload() throws Exception {
        File archive = buildEggArchive("page001.txt", "deflate payload".getBytes(StandardCharsets.UTF_8), 1, false);
        File out = tempFolder.newFile("egg-deflate.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page001.txt", out, null));

        assertEquals("deflate payload", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
    }


    @Test
    public void extractSingleEntry_eggAzoFramedStoredBlock_decodesPayload() throws Exception {
        byte[] plain = "azo framed payload".getBytes(StandardCharsets.UTF_8);
        File archive = buildEggArchiveWithStoredPayload("page-azo.txt", plain, 3, buildAzoStoredStream(plain), false);
        File out = tempFolder.newFile("egg-azo.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page-azo.txt", out, null));

        assertEquals("azo framed payload", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void encryptedEgg_requestsPasswordThenReportsUnsupportedDecrypt() throws Exception {
        File archive = buildEggArchive("secret.txt", "secret payload".getBytes(StandardCharsets.UTF_8), 0, true);
        File out = tempFolder.newFile("egg-encrypted.txt");

        assertTrue(ArchiveSupport.requiresPasswordForExtraction(archive));

        ArchiveSupport.ExtractionResult withoutPassword = ArchiveSupport.extractSingleEntryDetailed(
                archive,
                "secret.txt",
                out,
                null);
        assertFalse(withoutPassword.success);
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, withoutPassword.failure);
        assertFalse(out.exists());

        ArchiveSupport.ExtractionResult withPassword = ArchiveSupport.extractSingleEntryDetailed(
                archive,
                "secret.txt",
                out,
                "pw".toCharArray());
        assertFalse(withPassword.success);
        assertEquals(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE, withPassword.failure);
        assertFalse(out.exists());
    }

    private File buildEggArchive(String entryName, byte[] plainPayload, int method, boolean encrypted) throws Exception {
        byte[] storedPayload = method == 1 ? rawDeflate(plainPayload) : plainPayload;
        return buildEggArchiveWithStoredPayload(entryName, plainPayload, method, storedPayload, encrypted);
    }

    private File buildEggArchiveWithStoredPayload(String entryName, byte[] plainPayload, int method,
                                                  byte[] storedPayload, boolean encrypted) throws Exception {
        File archive = tempFolder.newFile("fixture-" + System.nanoTime() + ".egg");
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(plainPayload);

        try (FileOutputStream out = new FileOutputStream(archive)) {
            writeIntLE(out, MAGIC_EGG);
            writeShortLE(out, 0);
            writeIntLE(out, 1);
            writeIntLE(out, 0);

            writeIntLE(out, MAGIC_FILE);
            writeIntLE(out, 1);
            writeLongLE(out, plainPayload.length);

            writeIntLE(out, MAGIC_FILENAME);
            out.write(0);
            writeShortLE(out, name.length);
            out.write(name);

            if (encrypted) {
                writeIntLE(out, MAGIC_ENCRYPT);
                out.write(0);
                writeShortLE(out, 0);
            }

            writeIntLE(out, MAGIC_BLOCK);
            out.write(method);
            out.write(0);
            writeIntLE(out, plainPayload.length);
            writeIntLE(out, storedPayload.length);
            writeIntLE(out, (int) crc.getValue());
            out.write(storedPayload);

            writeIntLE(out, MAGIC_END);
            writeIntLE(out, MAGIC_END);
        }
        return archive;
    }


    private byte[] buildAzoStoredStream(byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('1');
        out.write(0);
        writeIntBE(out, payload.length);
        writeIntBE(out, payload.length);
        writeIntBE(out, payload.length ^ payload.length);
        out.write(payload);
        writeIntBE(out, 0);
        writeIntBE(out, 0);
        writeIntBE(out, 0);
        return out.toByteArray();
    }

    private void writeIntBE(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
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

    private void writeLongLE(FileOutputStream out, long value) throws Exception {
        writeIntLE(out, (int) value);
        writeIntLE(out, (int) (value >>> 32));
    }
}
