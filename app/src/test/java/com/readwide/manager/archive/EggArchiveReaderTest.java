package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Fixtures follow the layout observed in real ALZip-created EGG files: the
 * 14-byte EGG header is followed by archive-level extra fields terminated by
 * an END field, each FILE's extra fields are terminated by an END field, and
 * every BLOCK header ends with an END field before its data. Split fixtures
 * are a byte-level cut of one logical archive with prev/next header-id links,
 * exactly as ALZip writes {@code .vol1.egg}/{@code .vol2.egg} volumes.
 */
public class EggArchiveReaderTest {
    private static final int MAGIC_EGG = 0x41474745;
    private static final int MAGIC_FILE = 0x0a8590e3;
    private static final int MAGIC_BLOCK = 0x02b50c13;
    private static final int MAGIC_ENCRYPT = 0x08d1470f;
    private static final int MAGIC_FILENAME = 0x0a8591ac;
    private static final int MAGIC_SPLIT = 0x24f5a262;
    private static final int MAGIC_SOLID = 0x24e5a060;
    private static final int MAGIC_END = 0x08e28222;

    private static final String TEST_PASSWORD = "1q2w3e4r!";

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
    public void listEntries_eggCp949FilenameWithLocale_autoDecodes() throws Exception {
        byte[] payload = "stored".getBytes(StandardCharsets.UTF_8);
        File archive = buildEggArchiveWithNameBytes(
                "한글/page001.txt".getBytes(Charset.forName("MS949")),
                949,
                payload,
                0,
                payload,
                false);

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);

        assertEquals(1, entries.size());
        assertEquals("한글/page001.txt", entries.get(0).path);
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
    public void encryptedEgg_zipCrypto_decryptsWithCorrectPassword() throws Exception {
        File archive = buildEggArchive("secret.txt", "secret payload".getBytes(StandardCharsets.UTF_8), 0, true);
        File out = tempFolder.newFile("egg-encrypted.txt");

        assertTrue(ArchiveSupport.requiresPasswordForExtraction(archive));

        ArchiveSupport.ExtractionResult withoutPassword = ArchiveSupport.extractSingleEntryDetailed(
                archive, "secret.txt", out, null);
        assertFalse(withoutPassword.success);
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, withoutPassword.failure);
        assertFalse(out.exists());

        ArchiveSupport.ExtractionResult wrongPassword = ArchiveSupport.extractSingleEntryDetailed(
                archive, "secret.txt", out, "wrong-pw".toCharArray());
        assertFalse(wrongPassword.success);
        assertEquals(ArchiveSupport.ExtractionFailure.BAD_PASSWORD, wrongPassword.failure);
        assertFalse(out.exists());

        ArchiveSupport.ExtractionResult correct = ArchiveSupport.extractSingleEntryDetailed(
                archive, "secret.txt", out, TEST_PASSWORD.toCharArray());
        assertTrue(correct.success);
        assertEquals("secret payload", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void encryptedEgg_zipCrypto_deflateEntryDecrypts() throws Exception {
        File archive = buildEggArchive("secret.txt", "deflate secret payload".getBytes(StandardCharsets.UTF_8), 1, true);
        File out = tempFolder.newFile("egg-encrypted-deflate.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "secret.txt", out, TEST_PASSWORD.toCharArray()));

        assertEquals("deflate secret payload", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void encryptedEgg_zipCrypto_keystreamContinuesAcrossBlocks() throws Exception {
        byte[] payload = buildRepeatingPayload(4000);
        File archive = buildTwoBlockEncryptedStoreArchive("multi.txt", payload);
        File out = tempFolder.newFile("egg-encrypted-multiblock.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "multi.txt", out, TEST_PASSWORD.toCharArray()));

        assertEquals(new String(payload, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
    }

    // ----- Split volumes -----

    @Test
    public void splitEgg_twoVolumes_extractsAcrossVolumeBoundary() throws Exception {
        byte[] payload = buildRepeatingPayload(6000); // large enough to cut mid-data
        writeSplitPair("split-a", payload);
        File vol1 = new File(tempFolder.getRoot(), "split-a.vol1.egg");
        File out = tempFolder.newFile("split-a-out.txt");

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(vol1, null);
        assertEquals(1, entries.size());
        assertEquals((long) payload.length, entries.get(0).size);

        assertTrue(ArchiveSupport.extractSingleEntry(vol1, "data.txt", out, null));
        assertEquals(new String(payload, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void splitEgg_openedFromSecondVolume_resolvesFirstVolume() throws Exception {
        byte[] payload = buildRepeatingPayload(6000);
        writeSplitPair("split-b", payload);
        File vol2 = new File(tempFolder.getRoot(), "split-b.vol2.egg");

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(vol2, null);

        assertEquals(1, entries.size());
        assertEquals("data.txt", entries.get(0).path);
    }

    @Test
    public void splitEgg_missingSecondVolume_failsWithoutPartialOutput() throws Exception {
        byte[] payload = buildRepeatingPayload(6000);
        writeSplitPair("split-c", payload);
        File vol1 = new File(tempFolder.getRoot(), "split-c.vol1.egg");
        File vol2 = new File(tempFolder.getRoot(), "split-c.vol2.egg");
        assertTrue(vol2.delete());
        File out = tempFolder.newFile("split-c-out.txt");
        assertTrue(out.delete());

        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                vol1, "data.txt", out, null);

        assertFalse(result.success);
        assertFalse(out.exists());
    }

    // ----- Solid archives -----

    @Test
    public void solidEgg_storeSingleBlock_extractsAllEntries() throws Exception {
        byte[][] payloads = {"a".getBytes(StandardCharsets.US_ASCII),
                "bc".getBytes(StandardCharsets.US_ASCII), buildRepeatingPayload(6000)};
        File archive = buildSolidEggArchive(new String[]{"a.txt", "b.txt", "c.bin"}, payloads, 0, 1);
        File target = tempFolder.newFolder("solid-store-out");

        assertTrue(ArchiveSupport.extractArchive(archive, target, true, null));

        assertEquals("a", new String(Files.readAllBytes(new File(target, "a.txt").toPath()), StandardCharsets.US_ASCII));
        assertEquals("bc", new String(Files.readAllBytes(new File(target, "b.txt").toPath()), StandardCharsets.US_ASCII));
        assertTrue(java.util.Arrays.equals(payloads[2], Files.readAllBytes(new File(target, "c.bin").toPath())));
    }

    @Test
    public void solidEgg_deflateTwoBlocks_boundaryInsideEntry_extracts() throws Exception {
        // The second block boundary falls inside c.bin, the realistic solid
        // layout (validated against the vendor unegg 0.5 decoder; see
        // docs/EGG_FORMAT_NOTES.md).
        byte[][] payloads = {"a".getBytes(StandardCharsets.US_ASCII),
                "bc".getBytes(StandardCharsets.US_ASCII), buildRepeatingPayload(6000)};
        File archive = buildSolidEggArchive(new String[]{"a.txt", "b.txt", "c.bin"}, payloads, 1, 2);
        File target = tempFolder.newFolder("solid-deflate-out");

        assertTrue(ArchiveSupport.extractArchive(archive, target, true, null));

        assertEquals("bc", new String(Files.readAllBytes(new File(target, "b.txt").toPath()), StandardCharsets.US_ASCII));
        assertTrue(java.util.Arrays.equals(payloads[2], Files.readAllBytes(new File(target, "c.bin").toPath())));
    }

    @Test
    public void solidEgg_singleEntry_extractsFromMiddleOfStream() throws Exception {
        byte[][] payloads = {"a".getBytes(StandardCharsets.US_ASCII),
                "bc".getBytes(StandardCharsets.US_ASCII), buildRepeatingPayload(6000)};
        File archive = buildSolidEggArchive(new String[]{"a.txt", "b.txt", "c.bin"}, payloads, 1, 2);
        File out = tempFolder.newFile("solid-single-b.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "b.txt", out, null));
        assertEquals("bc", new String(Files.readAllBytes(out.toPath()), StandardCharsets.US_ASCII));

        File outC = tempFolder.newFile("solid-single-c.bin");
        assertTrue(ArchiveSupport.extractSingleEntry(archive, "c.bin", outC, null));
        assertTrue(java.util.Arrays.equals(payloads[2], Files.readAllBytes(outC.toPath())));
    }

    @Test
    public void solidEgg_listEntries_returnsAllEntries() throws Exception {
        byte[][] payloads = {"a".getBytes(StandardCharsets.US_ASCII), "bc".getBytes(StandardCharsets.US_ASCII)};
        File archive = buildSolidEggArchive(new String[]{"a.txt", "b.txt"}, payloads, 0, 1);

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);

        assertEquals(2, entries.size());
        assertEquals("a.txt", entries.get(0).path);
        assertEquals(1L, entries.get(0).size);
        assertEquals("b.txt", entries.get(1).path);
        assertEquals(2L, entries.get(1).size);
    }

    @Test
    public void solidEgg_truncatedStream_failsExplicitly() throws Exception {
        // Declared entry sizes total 6 bytes but the solid stream carries 3.
        byte[] shortStream = buildSolidEggBytesWithStream(new String[]{"a.txt", "b.txt"},
                new long[]{1L, 5L}, "abc".getBytes(StandardCharsets.US_ASCII), 0);
        File archive = tempFolder.newFile("solid-truncated.egg");
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(shortStream);
        }

        File target = tempFolder.newFolder("solid-truncated-out");
        try {
            EggArchiveReader.extractArchiveIntoDirectory(archive, target, null);
            fail("Expected truncated solid stream to fail");
        } catch (IOException e) {
            assertTrue(e.getMessage() != null && e.getMessage().contains("Solid EGG stream"));
        }
    }

    // ----- AES-encrypted entries -----

    /**
     * Self-made AES fixtures: single-entry EGG archives whose Encrypt field
     * carries method(1) + salt(8/16) + 2-byte PBKDF2 verifier + 10-byte
     * HMAC-SHA1 footer, with AES-CTR ciphertext block data (WinZip AES
     * construction, PBKDF2-HMAC-SHA1/1000). Built by a first-party script
     * from the public EGG Specification and validated byte-identical through
     * ESTsoft's own unegg 0.5 decoder before embedding (no vendor code used;
     * see docs/EGG_FORMAT_NOTES.md). Password: "pw1717". Payloads are
     * {@link #aesStorePayload()} / {@link #aesDeflatePayload()}.
     */
    private static final String AES128_STORE_B64 =
            "RUdHQQABAQAAAAAAAAAiguII45CFCgAAAAC+AAAAAAAAAKyRhQoACgBzZWNyZXQudHh0C5WGLAAJACPJo09j+8cBAA9H0QgA"
            + "FQABAQIDBAUGBwh2Eky8MBmYFnVCA9YiguIIEwy1AgAAvgAAAL4AAACZDU3EIoLiCImweUKQpSKfqEHDJKqaVSjdAJhya8m6"
            + "B2MyZuYurP5mYYPIE37f88VDiFXAyUv4LTWq+ZSQkuspVTYryCyherGWa+6lRlp2FVrC8Uqm2t3Kdxp/61AlpXN7nj9bnRJy"
            + "bSxIRkqNd/j5G93WzHN2nILS6Jum2eQhc85D/IVEyLG55mhpVQtcauOF+W77feQr8dyJ6ihyiQq4fB3r3LR4SufY4nsdGTkS"
            + "tNfRhw+MSq1wLbmt8xSlDfSYTMs29xoiguII";
    private static final String AES256_STORE_B64 =
            "RUdHQQABAQAAAAAAAAAiguII45CFCgAAAAC+AAAAAAAAAKyRhQoACgBzZWNyZXQudHh0C5WGLAAJACPJo09j+8cBAA9H0QgA"
            + "HQACAQIDBAUGBwgJCgsMDQ4PEGaDGlyOzJ+HOQVcEiKC4ggTDLUCAAC+AAAAvgAAAJkNTcQiguIIkrfZyUdDmU0+pbwqtuix"
            + "bIK8F358044J7uhR7JcWGxHkmhdsGYg4WGBxgwAMA+4p/yGnzrJb5i2wcHGYYbB+M+c5ODefSuFgUazObgrlSvDZrn+p0GXU"
            + "dYxV9dUS6SjVbqvXOJb7NXTJimCK9YoPTPShTNYK0r+UyFpOayRBION4/S8E8yhtMX1C+mF7GvFMYOYg9LZgtOOx6D+63Qdb"
            + "4LZZSpD658u5BRVCiQ5gEyMD2anKhFPUatdPho4sbSKC4gg=";
    private static final String AES256_DEFLATE_B64 =
            "RUdHQQABAQAAAAAAAAAiguII45CFCgAAAAAOAQAAAAAAAKyRhQoACgBzZWNyZXQudHh0C5WGLAAJACPJo09j+8cBAA9H0QgA"
            + "HQACAQIDBAUGBwgJCgsMDQ4PEGaDu1YpuUMoqScm2SKC4ggTDLUCAQAOAQAAIAAAAMnhYlAiguIIuJ6HvxxotxER/M0SntwW"
            + "Ss+RYjdRDGKzxMdywf9POXAiguII";
    private static final String AES128_NOCRC_B64 =
            "RUdHQQABAQAAAAAAAAAiguII45CFCgAAAAC+AAAAAAAAAKyRhQoACgBzZWNyZXQudHh0C5WGLAAJACPJo09j+8cBAA9H0QgA"
            + "FQABAQIDBAUGBwh2Eky8MBmYFnVCA9YiguIIEwy1AgAAvgAAAL4AAAAAAAAAIoLiCImweUKQpSKfqEHDJKqaVSjdAJhya8m6"
            + "B2MyZuYurP5mYYPIE37f88VDiFXAyUv4LTWq+ZSQkuspVTYryCyherGWa+6lRlp2FVrC8Uqm2t3Kdxp/61AlpXN7nj9bnRJy"
            + "bSxIRkqNd/j5G93WzHN2nILS6Jum2eQhc85D/IVEyLG55mhpVQtcauOF+W77feQr8dyJ6ihyiQq4fB3r3LR4SufY4nsdGTkS"
            + "tNfRhw+MSq1wLbmt8xSlDfSYTMs29xoiguII";

    private static final String AES_PASSWORD = "pw1717";

    private static byte[] aesStorePayload() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append("aes secret payload ");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] aesDeflatePayload() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append("aes secret deflate payload ");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private File writeFixture(String name, String base64) throws Exception {
        File archive = tempFolder.newFile(name);
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(java.util.Base64.getDecoder().decode(base64));
        }
        return archive;
    }

    @Test
    public void aesEgg_aes128Store_extractsWithPassword() throws Exception {
        File archive = writeFixture("aes128-store.egg", AES128_STORE_B64);
        File out = tempFolder.newFile("aes128.txt");

        assertTrue(ArchiveSupport.requiresPasswordForExtraction(archive));
        assertTrue(ArchiveSupport.extractSingleEntry(archive, "secret.txt", out, AES_PASSWORD.toCharArray()));
        assertTrue(java.util.Arrays.equals(aesStorePayload(), Files.readAllBytes(out.toPath())));
    }

    @Test
    public void aesEgg_aes256Store_extractsWithPassword() throws Exception {
        File archive = writeFixture("aes256-store.egg", AES256_STORE_B64);
        File out = tempFolder.newFile("aes256.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "secret.txt", out, AES_PASSWORD.toCharArray()));
        assertTrue(java.util.Arrays.equals(aesStorePayload(), Files.readAllBytes(out.toPath())));
    }

    @Test
    public void aesEgg_aes256Deflate_decryptsThenInflates() throws Exception {
        File archive = writeFixture("aes256-deflate.egg", AES256_DEFLATE_B64);
        File out = tempFolder.newFile("aes256-deflate.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "secret.txt", out, AES_PASSWORD.toCharArray()));
        assertTrue(java.util.Arrays.equals(aesDeflatePayload(), Files.readAllBytes(out.toPath())));
    }

    @Test
    public void aesEgg_wrongOrMissingPassword_failsCleanly() throws Exception {
        File archive = writeFixture("aes128-store-pw.egg", AES128_STORE_B64);
        File out = new File(tempFolder.getRoot(), "aes128-wrong.txt");

        try {
            EggArchiveReader.extractSingleEntry(archive, "secret.txt", out, "wrong".toCharArray());
            fail("Expected wrong password to fail");
        } catch (IOException e) {
            assertTrue(String.valueOf(e.getMessage()).contains("Invalid password"));
        }
        try {
            EggArchiveReader.extractSingleEntry(archive, "secret.txt", out, null);
            fail("Expected missing password to fail");
        } catch (ArchiveSupport.PasswordRequiredException expected) {
        }
        assertFalse(out.exists());
    }

    @Test
    public void aesEgg_tamperedCiphertext_rejectedByHmacFooter() throws Exception {
        // Block CRC is zero in this fixture, so integrity rests on the
        // 10-byte HMAC-SHA1 footer alone.
        byte[] raw = java.util.Base64.getDecoder().decode(AES128_NOCRC_B64);
        raw[raw.length - 8] ^= 0x41;
        File archive = tempFolder.newFile("aes128-tampered.egg");
        try (FileOutputStream fos = new FileOutputStream(archive)) {
            fos.write(raw);
        }
        File out = new File(tempFolder.getRoot(), "aes128-tampered.txt");

        try {
            EggArchiveReader.extractSingleEntry(archive, "secret.txt", out, AES_PASSWORD.toCharArray());
            fail("Expected tampered ciphertext to fail authentication");
        } catch (IOException e) {
            assertTrue(String.valueOf(e.getMessage()).contains("authentication"));
        }
        assertFalse(out.exists());
    }

    // ----- Fixture builders (real ALZip layout) -----

    private File buildEggArchive(String entryName, byte[] plainPayload, int method, boolean encrypted) throws Exception {
        byte[] storedPayload = method == 1 ? rawDeflate(plainPayload) : plainPayload;
        return buildEggArchiveWithStoredPayload(entryName, plainPayload, method, storedPayload, encrypted);
    }

    private File buildEggArchiveWithStoredPayload(String entryName, byte[] plainPayload, int method,
                                                  byte[] storedPayload, boolean encrypted) throws Exception {
        return buildEggArchiveWithNameBytes(entryName.getBytes(StandardCharsets.UTF_8), 0, plainPayload, method, storedPayload, encrypted);
    }

    private File buildEggArchiveWithNameBytes(byte[] name, int localeCodePage, byte[] plainPayload, int method,
                                              byte[] storedPayload, boolean encrypted) throws Exception {
        File archive = tempFolder.newFile("fixture-" + System.nanoTime() + ".egg");
        byte[] bytes = buildEggBytes(name, localeCodePage, plainPayload, method, storedPayload, encrypted,
                0x11111111, 0L, 0L, false);
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(bytes);
        }
        return archive;
    }

    /**
     * One solid EGG archive: header with a Solid extra field, every file
     * header in order, then {@code blockCount} blocks whose decoded
     * concatenation is all payloads back to back (layout validated against
     * ESTsoft's unegg 0.5 decoder; see docs/EGG_FORMAT_NOTES.md).
     */
    private File buildSolidEggArchive(String[] names, byte[][] payloads, int method, int blockCount) throws Exception {
        File archive = tempFolder.newFile("solid-" + System.nanoTime() + ".egg");
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(buildSolidEggBytes(names, payloads, method, blockCount));
        }
        return archive;
    }

    private byte[] buildSolidEggBytes(String[] names, byte[][] payloads, int method, int blockCount) throws Exception {
        ByteArrayOutputStream concat = new ByteArrayOutputStream();
        long[] sizes = new long[payloads.length];
        for (int i = 0; i < payloads.length; i++) {
            sizes[i] = payloads[i].length;
            concat.write(payloads[i]);
        }
        return buildSolidEggBytesInBlocks(names, sizes, concat.toByteArray(), method, blockCount);
    }

    /** Solid archive whose single block carries exactly {@code stream}. */
    private byte[] buildSolidEggBytesWithStream(String[] names, long[] sizes, byte[] stream, int method) throws Exception {
        return buildSolidEggBytesInBlocks(names, sizes, stream, method, 1);
    }

    private byte[] buildSolidEggBytesInBlocks(String[] names, long[] sizes, byte[] concat,
                                              int method, int blockCount) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeIntLE(out, MAGIC_EGG);
        writeShortLE(out, 0x0100);
        writeIntLE(out, 0x22222222);
        writeIntLE(out, 0);
        writeIntLE(out, MAGIC_SOLID);
        out.write(0);
        writeShortLE(out, 0);
        writeIntLE(out, MAGIC_END); // prefix END

        for (int i = 0; i < names.length; i++) {
            byte[] name = names[i].getBytes(StandardCharsets.UTF_8);
            writeIntLE(out, MAGIC_FILE);
            writeIntLE(out, i);
            writeLongLE(out, sizes[i]);
            writeIntLE(out, MAGIC_FILENAME);
            out.write(0);
            writeShortLE(out, name.length);
            out.write(name);
            writeIntLE(out, MAGIC_END); // file extras END
        }

        int step = (concat.length + blockCount - 1) / blockCount;
        if (step <= 0) step = 1;
        for (int off = 0; off < concat.length || (off == 0 && concat.length == 0); off += step) {
            int len = Math.min(step, concat.length - off);
            byte[] part = new byte[len];
            System.arraycopy(concat, off, part, 0, len);
            byte[] stored = method == 1 ? rawDeflate(part) : part;
            CRC32 crc = new CRC32();
            crc.update(part);
            writeIntLE(out, MAGIC_BLOCK);
            out.write(method);
            out.write(0);
            writeIntLE(out, part.length);
            writeIntLE(out, stored.length);
            writeIntLE(out, (int) crc.getValue());
            writeIntLE(out, MAGIC_END); // block header END
            out.write(stored);
            if (concat.length == 0) break;
        }

        writeIntLE(out, MAGIC_END); // archive END
        return out.toByteArray();
    }

    /**
     * One logical EGG archive: header (with optional Split field) + prefix END
     * + FILE (extras + END) + BLOCK (header + END + data) + archive END.
     */
    private byte[] buildEggBytes(byte[] name, int localeCodePage, byte[] plainPayload, int method,
                                 byte[] storedPayload, boolean encrypted,
                                 int programId, long splitPrev, long splitNext, boolean split) throws Exception {
        CRC32 crc = new CRC32();
        crc.update(plainPayload);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        writeIntLE(out, MAGIC_EGG);
        writeShortLE(out, 0x0100);
        writeIntLE(out, programId);
        writeIntLE(out, 0);
        if (split) {
            writeIntLE(out, MAGIC_SPLIT);
            out.write(0);
            writeShortLE(out, 8);
            writeIntLE(out, (int) splitPrev);
            writeIntLE(out, (int) splitNext);
        }
        writeIntLE(out, MAGIC_END); // prefix END

        writeIntLE(out, MAGIC_FILE);
        writeIntLE(out, 0);
        writeLongLE(out, plainPayload.length);

        writeIntLE(out, MAGIC_FILENAME);
        boolean hasLocale = localeCodePage > 0;
        out.write(hasLocale ? (1 << 5) : 0);
        writeShortLE(out, name.length + (hasLocale ? 2 : 0));
        if (hasLocale) writeShortLE(out, localeCodePage);
        out.write(name);

        TestZipCrypto encryptor = null;
        if (encrypted) {
            encryptor = new TestZipCrypto(TEST_PASSWORD);
            byte[] plainVerify = new byte[12];
            for (int i = 0; i < 11; i++) plainVerify[i] = (byte) (0x30 + i);
            plainVerify[11] = (byte) (crc.getValue() >>> 24);
            byte[] encryptedVerify = encryptor.encrypt(plainVerify);
            writeIntLE(out, MAGIC_ENCRYPT);
            out.write(0);
            writeShortLE(out, 17);
            out.write(0); // EncryptMethod: ZipCrypto
            out.write(encryptedVerify);
            writeIntLE(out, (int) crc.getValue());
        }
        writeIntLE(out, MAGIC_END); // file extras END

        writeIntLE(out, MAGIC_BLOCK);
        out.write(method);
        out.write(0);
        writeIntLE(out, plainPayload.length);
        writeIntLE(out, storedPayload.length);
        writeIntLE(out, (int) crc.getValue());
        writeIntLE(out, MAGIC_END); // block header END
        out.write(encryptor == null ? storedPayload : encryptor.encrypt(storedPayload));

        writeIntLE(out, MAGIC_END); // archive END
        return out.toByteArray();
    }

    /**
     * Writes {@code base.vol1.egg} and {@code base.vol2.egg}: the logical
     * archive (whose own prefix carries Split prev=0/next=id2) cut inside the
     * stored block data; the second volume repeats the EGG header with Split
     * prev=id1 and carries the remaining bytes after its prefix.
     */
    private void writeSplitPair(String base, byte[] payload) throws Exception {
        int id1 = 0x1a2b3c4d;
        int id2 = 0x5e6f7a8b;
        byte[] logical = buildEggBytes("data.txt".getBytes(StandardCharsets.UTF_8), 0,
                payload, 0, payload, false, id1, 0L, id2 & 0xffffffffL, true);
        int cut = logical.length - payload.length / 2; // inside the stored data
        assertTrue(cut > 0 && cut < logical.length);

        try (FileOutputStream out = new FileOutputStream(new File(tempFolder.getRoot(), base + ".vol1.egg"))) {
            out.write(logical, 0, cut);
        }
        try (FileOutputStream out = new FileOutputStream(new File(tempFolder.getRoot(), base + ".vol2.egg"))) {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            writeIntLE(head, MAGIC_EGG);
            writeShortLE(head, 0x0100);
            writeIntLE(head, id2);
            writeIntLE(head, 0);
            writeIntLE(head, MAGIC_SPLIT);
            head.write(0);
            writeShortLE(head, 8);
            writeIntLE(head, id1);
            writeIntLE(head, 0);
            writeIntLE(head, MAGIC_END);
            out.write(head.toByteArray());
            out.write(logical, cut, logical.length - cut);
        }
    }

    private byte[] buildRepeatingPayload(int length) {
        StringBuilder sb = new StringBuilder(length);
        int i = 0;
        while (sb.length() < length) sb.append("line-").append(i++).append('\n');
        sb.setLength(length);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
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

    /**
     * One file stored as two blocks, both ZipCrypto-encrypted with a single
     * continuing keystream (check data first, then block 1, then block 2),
     * which is how ALZip encrypts multi-block files.
     */
    private File buildTwoBlockEncryptedStoreArchive(String entryName, byte[] payload) throws Exception {
        int half = payload.length / 2;
        byte[] part1 = java.util.Arrays.copyOfRange(payload, 0, half);
        byte[] part2 = java.util.Arrays.copyOfRange(payload, half, payload.length);
        CRC32 fullCrc = new CRC32();
        fullCrc.update(payload);
        TestZipCrypto encryptor = new TestZipCrypto(TEST_PASSWORD);
        byte[] plainVerify = new byte[12];
        plainVerify[11] = (byte) (fullCrc.getValue() >>> 24);
        byte[] encryptedVerify = encryptor.encrypt(plainVerify);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeIntLE(out, MAGIC_EGG);
        writeShortLE(out, 0x0100);
        writeIntLE(out, 0x22222222);
        writeIntLE(out, 0);
        writeIntLE(out, MAGIC_END); // prefix END

        writeIntLE(out, MAGIC_FILE);
        writeIntLE(out, 0);
        writeLongLE(out, payload.length);
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        writeIntLE(out, MAGIC_FILENAME);
        out.write(0);
        writeShortLE(out, name.length);
        out.write(name);
        writeIntLE(out, MAGIC_ENCRYPT);
        out.write(0);
        writeShortLE(out, 17);
        out.write(0);
        out.write(encryptedVerify);
        writeIntLE(out, (int) fullCrc.getValue());
        writeIntLE(out, MAGIC_END); // file extras END

        for (byte[] part : new byte[][] { part1, part2 }) {
            CRC32 blockCrc = new CRC32();
            blockCrc.update(part);
            writeIntLE(out, MAGIC_BLOCK);
            out.write(0); // store
            out.write(0);
            writeIntLE(out, part.length);
            writeIntLE(out, part.length);
            writeIntLE(out, (int) blockCrc.getValue());
            writeIntLE(out, MAGIC_END);
            out.write(encryptor.encrypt(part));
        }
        writeIntLE(out, MAGIC_END); // archive END

        File archive = tempFolder.newFile("fixture-encrypted-multiblock.egg");
        try (FileOutputStream fos = new FileOutputStream(archive)) {
            fos.write(out.toByteArray());
        }
        return archive;
    }

    /** Minimal ZipCrypto encryptor for building test fixtures. */
    private static final class TestZipCrypto {
        private final int[] keys = { 0x12345678, 0x23456789, 0x34567890 };

        TestZipCrypto(String password) {
            for (int i = 0; i < password.length(); i++) updateKeys((byte) password.charAt(i));
        }

        byte[] encrypt(byte[] plain) {
            byte[] cipher = new byte[plain.length];
            for (int i = 0; i < plain.length; i++) {
                cipher[i] = (byte) (plain[i] ^ keyByte());
                updateKeys(plain[i]);
            }
            return cipher;
        }

        private int keyByte() {
            int temp = (keys[2] & 0xffff) | 2;
            return ((temp * (temp ^ 1)) >>> 8) & 0xff;
        }

        private void updateKeys(byte plain) {
            keys[0] = crc32(keys[0], plain);
            keys[1] = (keys[1] + (keys[0] & 0xff)) * 134775813 + 1;
            keys[2] = crc32(keys[2], (byte) (keys[1] >>> 24));
        }

        private static int crc32(int value, byte b) {
            int c = (value ^ b) & 0xff;
            for (int bit = 0; bit < 8; bit++) c = (c & 1) != 0 ? (c >>> 1) ^ 0xedb88320 : c >>> 1;
            return c ^ (value >>> 8);
        }
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

    private void writeIntLE(OutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private void writeShortLE(OutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private void writeLongLE(OutputStream out, long value) throws IOException {
        writeIntLE(out, (int) value);
        writeIntLE(out, (int) (value >>> 32));
    }
}
