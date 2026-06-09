package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.textview.reader.util.FileUtils;

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

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class RarArchiveReaderTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void archiveSupport_detectsRarAndCbrNames() {
        assertEquals(ArchiveSupport.Type.RAR, ArchiveSupport.getSupportedArchiveType("sample.rar"));
        assertEquals(ArchiveSupport.Type.RAR, ArchiveSupport.getSupportedArchiveType("sample.cbr"));
        assertEquals(ArchiveSupport.Type.RAR, ArchiveSupport.getSupportedArchiveType("sample.part1.rar"));
        assertEquals(ArchiveSupport.Type.RAR, ArchiveSupport.getSupportedArchiveType("sample.r00"));
        assertTrue(FileUtils.isArchiveFile("sample.rar"));
        assertTrue(FileUtils.isArchiveFile("sample.cbr"));
        assertTrue(FileUtils.isArchiveFile("sample.part1.rar"));
        assertTrue(FileUtils.isArchiveFile("sample.r00"));
    }

    @Test
    public void archiveSupport_detectsEmbeddedRarSfxBySignatureOnly() throws Exception {
        File sfx = tempFolder.newFile("reader-test.sfx.exe");
        try (FileOutputStream out = new FileOutputStream(sfx)) {
            out.write("MZ-stub".getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00});
        }
        File plainExe = tempFolder.newFile("plain.exe");
        try (FileOutputStream out = new FileOutputStream(plainExe)) {
            out.write("MZ-no-rar-signature".getBytes(StandardCharsets.US_ASCII));
        }

        assertEquals(ArchiveSupport.Type.RAR, ArchiveSupport.getSupportedArchiveType(sfx));
        assertTrue(ArchiveSupport.isSupportedArchive(sfx));
        assertEquals(null, ArchiveSupport.getSupportedArchiveType(plainExe));
        assertFalse(ArchiveSupport.isSupportedArchive(plainExe));
        assertEquals(null, ArchiveSupport.getSupportedArchiveType("reader-test.sfx.exe"));
    }

    @Test
    public void listEntries_rar5StoredArchive_returnsFileMetadata() throws Exception {
        File archive = buildRar5Archive("comic/page001.txt", "hello rar".getBytes(StandardCharsets.UTF_8), 0);

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);

        assertEquals(2, entries.size());
        assertEquals("comic/", entries.get(0).path);
        assertTrue(entries.get(0).directory);
        assertEquals("comic/page001.txt", entries.get(1).path);
        assertFalse(entries.get(1).directory);
        assertEquals(9L, entries.get(1).size);
    }

    @Test
    public void extractSingleEntry_rar5StoredArchive_writesPayload() throws Exception {
        byte[] payload = "stored image bytes".getBytes(StandardCharsets.UTF_8);
        File archive = buildRar5Archive("page001.jpg", payload, 0);
        File out = tempFolder.newFile("page001.jpg");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page001.jpg", out, null));

        assertArrayEquals(payload, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void extractSingleEntry_rar5StoredCrcMismatchDeletesPartialOutput() throws Exception {
        byte[] payload = "stored image bytes".getBytes(StandardCharsets.UTF_8);
        File archive = buildRar5Archive("page001.jpg", payload, 0, false, 0x12345678L);
        File out = tempFolder.newFile("crc-mismatch.jpg");

        assertFalse(ArchiveSupport.extractSingleEntry(archive, "page001.jpg", out, null));
        assertFalse(out.exists());
    }

    @Test
    public void extractSingleEntry_compressedRar5Entry_failsExplicitly() throws Exception {
        File archive = buildRar5Archive("page001.jpg", "payload".getBytes(StandardCharsets.UTF_8), 1);
        File out = tempFolder.newFile("page001.jpg");

        assertFalse(ArchiveSupport.extractSingleEntry(archive, "page001.jpg", out, null));
    }

    @Test
    public void listEntries_encryptedRar5FileData_stillListsVisibleNames() throws Exception {
        File archive = buildRar5Archive("private/page001.txt", "secret".getBytes(StandardCharsets.UTF_8), 0, true);

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);

        assertEquals("private/", entries.get(0).path);
        assertEquals("private/page001.txt", entries.get(1).path);
    }

    @Test
    public void extractSingleEntry_encryptedRar5FileData_failsWithoutCorruptOutput() throws Exception {
        File archive = buildRar5Archive("page001.txt", "secret".getBytes(StandardCharsets.UTF_8), 0, true);
        File out = tempFolder.newFile("encrypted.txt");

        assertFalse(ArchiveSupport.extractSingleEntry(archive, "page001.txt", out, null));
    }

    @Test
    public void extractSingleEntry_encryptedRar5StoredData_attemptsPasswordDecrypt() throws Exception {
        byte[] payload = patternedBytes(256 * 1024 + 37);
        File archive = buildRar5Archive("page001.txt", payload, 0, true);
        File out = tempFolder.newFile("decrypted.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page001.txt", out, "pw".toCharArray()));
        assertArrayEquals(payload, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void extractSingleEntry_encryptedRar5StoredData_wrongPasswordDeletesPartialOutput() throws Exception {
        File archive = buildRar5Archive("page001.txt", "secret".getBytes(StandardCharsets.UTF_8), 0, true);
        File out = tempFolder.newFile("wrong-password.txt");

        assertFalse(ArchiveSupport.extractSingleEntry(archive, "page001.txt", out, "bad".toCharArray()));
        assertFalse(out.exists());
    }

    @Test(expected = ArchiveSupport.PasswordRequiredException.class)
    public void listEntries_rar5EncryptedHeaders_requestsPassword() throws Exception {
        ArchiveSupport.listEntries(buildRar5EncryptedHeaderArchive(), null);
    }

    @Test
    public void listEntries_rar5UnsafePath_skipsEntry() throws Exception {
        File archive = buildRar5Archive("../evil.txt", "evil".getBytes(StandardCharsets.UTF_8), 0);

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);

        assertTrue(entries.isEmpty());
    }

    @Test
    public void extractArchive_rar5UnsafePathFailsWithoutWritingOutsideTarget() throws Exception {
        File archive = buildRar5Archive("../evil.txt", "evil".getBytes(StandardCharsets.UTF_8), 0);
        File target = new File(tempFolder.getRoot(), "rar-out");
        File outside = new File(tempFolder.getRoot(), "evil.txt");

        assertFalse(ArchiveSupport.extractArchive(archive, target, true, null));
        assertFalse(target.exists());
        assertFalse(outside.exists());
    }

    @Test
    public void listEntries_newStyleSplitRar_resolvesSelectedLaterPartToFirstVolume() throws Exception {
        File archive = buildRar5Archive("comic/page001.txt", "split payload".getBytes(StandardCharsets.UTF_8), 0);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        File part1 = new File(archive.getParentFile(), "comic.part1.rar");
        File part2 = new File(archive.getParentFile(), "comic.part2.rar");
        Files.write(part1.toPath(), bytes);
        Files.write(part2.toPath(), new byte[] {0x01, 0x02});

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(part2, null);

        assertNotNull(entries);
        assertEquals("comic/page001.txt", entries.get(1).path);
    }

    @Test
    public void listEntries_oldStyleSplitRar_resolvesSelectedR00ToFirstVolume() throws Exception {
        File archive = buildRar5Archive("comic/page001.txt", "old split payload".getBytes(StandardCharsets.UTF_8), 0);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        File first = new File(archive.getParentFile(), "comic.rar");
        File later = new File(archive.getParentFile(), "comic.r00");
        Files.write(first.toPath(), bytes);
        Files.write(later.toPath(), new byte[] {0x01, 0x02});

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(later, null);

        assertNotNull(entries);
        assertEquals("comic/page001.txt", entries.get(1).path);
    }

    @Test
    public void listEntries_zeroPaddedNewStyleSplitRar_resolvesSelectedLaterPartToFirstVolume() throws Exception {
        File archive = buildRar5Archive("comic/page001.txt", "zero padded split payload".getBytes(StandardCharsets.UTF_8), 0);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        File part1 = new File(archive.getParentFile(), "comic.part001.rar");
        File part2 = new File(archive.getParentFile(), "comic.part002.rar");
        Files.write(part1.toPath(), bytes);
        Files.write(part2.toPath(), new byte[] {0x01, 0x02});

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(part2, null);

        assertNotNull(entries);
        assertEquals("comic/page001.txt", entries.get(1).path);
    }

    @Test
    public void listEntries_threeDigitOldStyleSplitRar_resolvesSelectedR000ToFirstVolume() throws Exception {
        File archive = buildRar5Archive("comic/page001.txt", "old three digit split payload".getBytes(StandardCharsets.UTF_8), 0);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        File first = new File(archive.getParentFile(), "comic.rar");
        File later = new File(archive.getParentFile(), "comic.r000");
        Files.write(first.toPath(), bytes);
        Files.write(later.toPath(), new byte[] {0x01, 0x02});

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(later, null);

        assertNotNull(entries);
        assertEquals("comic/page001.txt", entries.get(1).path);
    }


    @Test
    public void collectVolumeChainForBackend_newStyleSelectedLaterPartPassesOrderedVolumes() throws Exception {
        File part1 = tempFolder.newFile("book.part001.rar");
        File part2 = tempFolder.newFile("book.part002.rar");
        File part3 = tempFolder.newFile("book.part003.rar");

        List<File> volumes = RarArchiveReader.collectVolumeChainForBackend(part2);

        assertEquals(3, volumes.size());
        assertEquals(part1.getCanonicalFile(), volumes.get(0).getCanonicalFile());
        assertEquals(part2.getCanonicalFile(), volumes.get(1).getCanonicalFile());
        assertEquals(part3.getCanonicalFile(), volumes.get(2).getCanonicalFile());
    }

    @Test
    public void collectVolumeChainForBackend_oldStyleSelectedR001PassesOrderedVolumes() throws Exception {
        File first = tempFolder.newFile("book.rar");
        File r000 = tempFolder.newFile("book.r000");
        File r001 = tempFolder.newFile("book.r001");

        List<File> volumes = RarArchiveReader.collectVolumeChainForBackend(r001);

        assertEquals(3, volumes.size());
        assertEquals(first.getCanonicalFile(), volumes.get(0).getCanonicalFile());
        assertEquals(r000.getCanonicalFile(), volumes.get(1).getCanonicalFile());
        assertEquals(r001.getCanonicalFile(), volumes.get(2).getCanonicalFile());
    }

    @Test
    public void extractSingleEntry_newStyleSplitRarStoredPayload_writesCombinedData() throws Exception {
        byte[] payload = "split stored payload".getBytes(StandardCharsets.UTF_8);
        File part2 = buildRar5SplitStoredArchive("book", "comic/page001.txt", payload);
        File out = tempFolder.newFile("split-output.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(part2, "comic/page001.txt", out, null));
        assertArrayEquals(payload, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void extractSingleEntry_newStyleSplitRarStoredPayload_missingContinuationDeletesOutput() throws Exception {
        byte[] payload = "split stored payload with missing continuation".getBytes(StandardCharsets.UTF_8);
        File part2 = buildRar5SplitStoredArchive("missing-book", "comic/page001.txt", payload);
        File part1 = new File(part2.getParentFile(), "missing-book.part1.rar");
        assertTrue(part2.delete());
        File out = tempFolder.newFile("missing-split-output.txt");

        assertFalse(ArchiveSupport.extractSingleEntry(part1, "comic/page001.txt", out, null));
        assertFalse(out.exists());
    }

    @Test
    public void extractSingleEntry_encryptedNewStyleSplitRar5StoredPayload_decryptsCombinedData() throws Exception {
        byte[] payload = patternedBytes(192 * 1024 + 11);
        File part2 = buildRar5EncryptedSplitStoredArchive("secret-book", "comic/page001.txt", payload);
        File out = tempFolder.newFile("encrypted-split-output.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(part2, "comic/page001.txt", out, "pw".toCharArray()));
        assertArrayEquals(payload, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void extractSingleEntry_encryptedNewStyleSplitRar5StoredPayload_requiresPassword() throws Exception {
        byte[] payload = "encrypted split stored payload".getBytes(StandardCharsets.UTF_8);
        File part2 = buildRar5EncryptedSplitStoredArchive("secret-book-required", "comic/page001.txt", payload);
        File out = tempFolder.newFile("encrypted-split-required.txt");

        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                part2,
                "comic/page001.txt",
                out,
                null);

        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, result.failure);
        assertFalse(out.exists());
    }

    @Test
    public void listEntries_rar4StoredArchive_returnsFileMetadata() throws Exception {
        File archive = buildRar4Archive("comic/page001.txt", "hello rar4".getBytes(StandardCharsets.UTF_8), 0x30);

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);

        assertEquals(2, entries.size());
        assertEquals("comic/", entries.get(0).path);
        assertTrue(entries.get(0).directory);
        assertEquals("comic/page001.txt", entries.get(1).path);
        assertFalse(entries.get(1).directory);
        assertEquals(10L, entries.get(1).size);
    }

    @Test
    public void extractSingleEntry_rar4StoredArchive_writesPayload() throws Exception {
        byte[] payload = "rar4 stored image bytes".getBytes(StandardCharsets.UTF_8);
        File archive = buildRar4Archive("page001.jpg", payload, 0x30);
        File out = tempFolder.newFile("page001-rar4.jpg");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page001.jpg", out, null));

        assertArrayEquals(payload, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void extractSingleEntry_oldStyleSplitRar4StoredPayload_writesCombinedData() throws Exception {
        byte[] payload = "rar4 split stored image bytes".getBytes(StandardCharsets.UTF_8);
        File later = buildRar4SplitStoredArchive("rar4-book", "comic/page001.jpg", payload);
        File out = tempFolder.newFile("rar4-split-output.jpg");

        assertTrue(ArchiveSupport.extractSingleEntry(later, "comic/page001.jpg", out, null));

        assertArrayEquals(payload, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void extractSingleEntry_oldStyleSplitRar4StoredPayload_missingContinuationDeletesOutput() throws Exception {
        byte[] payload = "rar4 split stored payload with missing continuation".getBytes(StandardCharsets.UTF_8);
        File later = buildRar4SplitStoredArchive("rar4-missing-book", "comic/page001.jpg", payload);
        File first = new File(later.getParentFile(), "rar4-missing-book.rar");
        assertTrue(later.delete());
        File out = tempFolder.newFile("rar4-missing-split-output.jpg");

        assertFalse(ArchiveSupport.extractSingleEntry(first, "comic/page001.jpg", out, null));
        assertFalse(out.exists());
    }

    @Test
    public void extractSingleEntry_rar4EncryptedFileData_requestsPasswordBeforeFallback() throws Exception {
        byte[] payload = "rar4 encrypted placeholder".getBytes(StandardCharsets.UTF_8);
        File archive = buildRar4ArchiveWithRawName("private/page001.txt".getBytes(StandardCharsets.UTF_8), payload, 0x30, RAR4_FILE_PASSWORD);
        File out = tempFolder.newFile("rar4-password-required.txt");

        assertTrue(ArchiveSupport.requiresPasswordForExtraction(archive));
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                archive,
                "private/page001.txt",
                out,
                null);

        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, result.failure);
        assertFalse(out.exists());
    }


    @Test
    public void extractSingleEntry_rar4EncryptedStoredArchive_decryptsPayload() throws Exception {
        byte[] payload = "rar4 encrypted stored image bytes".getBytes(StandardCharsets.UTF_8);
        File archive = buildRar4EncryptedStoredArchive("private/page001.txt", payload, "pw".toCharArray());
        File out = tempFolder.newFile("rar4-encrypted-stored.txt");

        assertTrue(ArchiveSupport.requiresPasswordForExtraction(archive));
        assertTrue(ArchiveSupport.extractSingleEntry(archive, "private/page001.txt", out, "pw".toCharArray()));

        assertArrayEquals(payload, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void extractSingleEntry_rar4EncryptedStoredArchive_wrongPasswordDeletesOutput() throws Exception {
        byte[] payload = "rar4 encrypted stored secret".getBytes(StandardCharsets.UTF_8);
        File archive = buildRar4EncryptedStoredArchive("private/page001.txt", payload, "pw".toCharArray());
        File out = tempFolder.newFile("rar4-encrypted-stored-wrong.txt");

        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                archive,
                "private/page001.txt",
                out,
                "bad".toCharArray());

        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, result.failure);
        assertFalse(out.exists());
    }

    @Test
    public void extractSingleEntry_rar4EncryptedSplitStoredArchive_decryptsAcrossVolumes() throws Exception {
        byte[] payload = patternedBytes(96 * 1024 + 29);
        File later = buildRar4EncryptedSplitStoredArchive("rar4-secret-book", "comic/page001.jpg", payload, "pw".toCharArray());
        File out = tempFolder.newFile("rar4-encrypted-split-output.jpg");

        assertTrue(ArchiveSupport.extractSingleEntry(later, "comic/page001.jpg", out, "pw".toCharArray()));

        assertArrayEquals(payload, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void extractSingleEntry_rar4EncryptedSplitStoredArchive_wrongPasswordDeletesOutput() throws Exception {
        byte[] payload = patternedBytes(64 * 1024 + 13);
        File later = buildRar4EncryptedSplitStoredArchive("rar4-secret-book-wrong", "comic/page001.jpg", payload, "pw".toCharArray());
        File out = tempFolder.newFile("rar4-encrypted-split-wrong.jpg");

        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                later,
                "comic/page001.jpg",
                out,
                "bad".toCharArray());

        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, result.failure);
        assertFalse(out.exists());
    }

    @Test
    public void extractSingleEntry_compressedRar4SyntheticPayload_failsCleanlyThroughFallback() throws Exception {
        File archive = buildRar4Archive("page001.jpg", "payload".getBytes(StandardCharsets.UTF_8), 0x31);
        File out = tempFolder.newFile("compressed-rar4.jpg");

        assertFalse(ArchiveSupport.extractSingleEntry(archive, "page001.jpg", out, null));
    }

    @Test
    public void listEntries_rar4UnicodeName_decodesEncodedName() throws Exception {
        byte[] payload = "unicode-name".getBytes(StandardCharsets.UTF_8);
        String unicodeName = "한글/페이지.jpg";
        File archive = buildRar4ArchiveWithRawName(rar4UnicodeRawName("fallback/page.jpg", unicodeName), payload, 0x30, 0x0200);

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);

        assertEquals("한글/", entries.get(0).path);
        assertEquals(unicodeName, entries.get(1).path);
    }

    @Test
    public void extractSingleEntry_rar4UnicodeName_usesDecodedPath() throws Exception {
        byte[] payload = "unicode extract".getBytes(StandardCharsets.UTF_8);
        String unicodeName = "한글/페이지.jpg";
        File archive = buildRar4ArchiveWithRawName(rar4UnicodeRawName("fallback/page.jpg", unicodeName), payload, 0x30, 0x0200);
        File out = tempFolder.newFile("unicode-rar4.jpg");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, unicodeName, out, null));

        assertArrayEquals(payload, Files.readAllBytes(out.toPath()));
    }

    private static byte[] patternedBytes(int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((i * 31 + 17) & 0xff);
        }
        return out;
    }

    private File buildRar5Archive(String entryName, byte[] payload, int method) throws Exception {
        return buildRar5Archive(entryName, payload, method, false);
    }

    private File buildRar5Archive(String entryName, byte[] payload, int method, boolean encryptedFileData) throws Exception {
        return buildRar5Archive(entryName, payload, method, encryptedFileData, -1L);
    }

    private File buildRar5Archive(String entryName, byte[] payload, int method, boolean encryptedFileData, long crcOverride) throws Exception {
        File archive = tempFolder.newFile("sample.rar");
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00});
            out.write(header(bytes(vint(1), vint(0), vint(0))));

            byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
            CRC32 payloadCrc = new CRC32();
            payloadCrc.update(payload);
            long crc = crcOverride >= 0L ? crcOverride : payloadCrc.getValue();
            long compressionInfo = ((long) method) << 7;
            byte[] storedPayload = encryptedFileData ? encryptRar5StoredPayload(payload, "pw".toCharArray()) : payload;
            byte[] extra = encryptedFileData ? rar5EncryptionExtra() : new byte[0];
            out.write(header(bytes(
                    vint(2),
                    vint(encryptedFileData ? 0x0003 : 0x0002),
                    encryptedFileData ? vint(extra.length) : new byte[0],
                    vint(storedPayload.length),
                    vint(0x0004),
                    vint(payload.length),
                    vint(0),
                    uint32(crc),
                    vint(compressionInfo),
                    vint(1),
                    vint(name.length),
                    name,
                    extra)));
            out.write(storedPayload);
            out.write(header(bytes(vint(5), vint(0), vint(0))));
        }
        return archive;
    }

    private static byte[] rar5EncryptionExtra() throws Exception {
        byte[] record = bytes(
                vint(1),
                vint(0),
                vint(0),
                new byte[] {8},
                rar5TestSalt(),
                rar5TestIv());
        return bytes(vint(record.length), record);
    }

    private static byte[] encryptRar5StoredPayload(byte[] payload, char[] password) throws Exception {
        byte[] padded = new byte[((payload.length + 15) / 16) * 16];
        System.arraycopy(payload, 0, padded, 0, payload.length);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(password, rar5TestSalt(), 1 << 8, 256))
                .getEncoded();
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(rar5TestIv()));
        return cipher.doFinal(padded);
    }

    private static byte[] rar5TestSalt() {
        return new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    }

    private static byte[] rar5TestIv() {
        return new byte[] {16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
    }

    private File buildRar5EncryptedHeaderArchive() throws Exception {
        File archive = tempFolder.newFile("encrypted-headers.rar");
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00});
            out.write(header(bytes(vint(4), vint(0))));
        }
        return archive;
    }

    private File buildRar5SplitStoredArchive(String baseName, String entryName, byte[] payload) throws Exception {
        int cut = Math.max(1, payload.length / 2);
        byte[] firstPayload = java.util.Arrays.copyOfRange(payload, 0, cut);
        byte[] secondPayload = java.util.Arrays.copyOfRange(payload, cut, payload.length);
        File part1 = new File(tempFolder.getRoot(), baseName + ".part1.rar");
        File part2 = new File(tempFolder.getRoot(), baseName + ".part2.rar");
        writeRar5Volume(part1, entryName, firstPayload, 0x0010L, crc32(firstPayload), firstPayload.length);
        writeRar5Volume(part2, entryName, secondPayload, 0x0008L, crc32(payload), payload.length);
        return part2;
    }

    private File buildRar5EncryptedSplitStoredArchive(String baseName, String entryName, byte[] payload) throws Exception {
        byte[] encryptedPayload = encryptRar5StoredPayload(payload, "pw".toCharArray());
        int cut = Math.max(1, encryptedPayload.length / 2);
        byte[] firstPayload = java.util.Arrays.copyOfRange(encryptedPayload, 0, cut);
        byte[] secondPayload = java.util.Arrays.copyOfRange(encryptedPayload, cut, encryptedPayload.length);
        byte[] extra = rar5EncryptionExtra();
        File part1 = new File(tempFolder.getRoot(), baseName + ".part1.rar");
        File part2 = new File(tempFolder.getRoot(), baseName + ".part2.rar");
        writeRar5Volume(part1, entryName, firstPayload, 0x0010L, crc32(firstPayload), firstPayload.length, extra);
        writeRar5Volume(part2, entryName, secondPayload, 0x0008L, crc32(payload), payload.length, extra);
        return part2;
    }

    private static void writeRar5Volume(File file,
                                        String entryName,
                                        byte[] payload,
                                        long fileHeaderFlags,
                                        long crc,
                                        long unpackedSize) throws Exception {
        writeRar5Volume(file, entryName, payload, fileHeaderFlags, crc, unpackedSize, new byte[0]);
    }

    private static void writeRar5Volume(File file,
                                        String entryName,
                                        byte[] payload,
                                        long fileHeaderFlags,
                                        long crc,
                                        long unpackedSize,
                                        byte[] extra) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00});
            out.write(header(bytes(vint(1), vint(0), vint(0x0001))));
            byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
            long flags = 0x0002 | fileHeaderFlags | (extra.length > 0 ? 0x0001 : 0);
            out.write(header(bytes(
                    vint(2),
                    vint(flags),
                    extra.length > 0 ? vint(extra.length) : new byte[0],
                    vint(payload.length),
                    vint(0x0004),
                    vint(unpackedSize),
                    vint(0),
                    uint32(crc),
                    vint(0),
                    vint(1),
                    vint(name.length),
                    name,
                    extra)));
            out.write(payload);
            out.write(header(bytes(vint(5), vint(0), vint(0))));
        }
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private File buildRar4Archive(String entryName, byte[] payload, int method) throws Exception {
        return buildRar4ArchiveWithRawName(entryName.getBytes(StandardCharsets.UTF_8), payload, method, 0);
    }

    private File buildRar4ArchiveWithRawName(byte[] rawName, byte[] payload, int method, int extraFlags) throws Exception {
        File archive = tempFolder.newFile("sample-rar4.rar");
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00});
            out.write(rar4Header(0x73, 0, new byte[] {0, 0, 0, 0, 0, 0}));

            CRC32 payloadCrc = new CRC32();
            payloadCrc.update(payload);
            byte[] body = bytes(
                    uint32(payload.length),
                    uint32(payload.length),
                    new byte[] {1},
                    uint32(payloadCrc.getValue()),
                    uint32(0),
                    new byte[] {29},
                    new byte[] {(byte) method},
                    uint16(rawName.length),
                    uint32(0),
                    rawName);
            out.write(rar4Header(0x74, 0x8000 | extraFlags, body));
            out.write(payload);
            out.write(rar4Header(0x7b, 0, new byte[0]));
        }
        return archive;
    }


    private File buildRar4EncryptedStoredArchive(String entryName, byte[] payload, char[] password) throws Exception {
        byte[] rawName = entryName.getBytes(StandardCharsets.UTF_8);
        byte[] salt = new byte[] {0x37, (byte) 0x94, 0x75, (byte) 0xb0, 0x6e, 0x30, 0x39, 0x55};
        byte[] padded = java.util.Arrays.copyOf(payload, ((payload.length + 15) / 16) * 16);
        Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(password, salt);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(parameters.key, "AES"),
                new IvParameterSpec(parameters.iv));
        byte[] encrypted = cipher.doFinal(padded);

        File archive = tempFolder.newFile("sample-rar4-encrypted.rar");
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00});
            out.write(rar4Header(0x73, 0, new byte[] {0, 0, 0, 0, 0, 0}));

            byte[] body = bytes(
                    uint32(encrypted.length),
                    uint32(payload.length),
                    new byte[] {1},
                    uint32(crc32(payload)),
                    uint32(0),
                    new byte[] {29},
                    new byte[] {0x30},
                    uint16(rawName.length),
                    uint32(0),
                    rawName,
                    salt);
            out.write(rar4Header(0x74, 0x8000 | RAR4_FILE_PASSWORD | RAR4_FILE_SALT, body));
            out.write(encrypted);
            out.write(rar4Header(0x7b, 0, new byte[0]));
        }
        return archive;
    }

    private File buildRar4SplitStoredArchive(String baseName, String entryName, byte[] payload) throws Exception {
        int cut = Math.max(1, payload.length / 2);
        byte[] firstPayload = java.util.Arrays.copyOfRange(payload, 0, cut);
        byte[] secondPayload = java.util.Arrays.copyOfRange(payload, cut, payload.length);
        File first = new File(tempFolder.getRoot(), baseName + ".rar");
        File later = new File(tempFolder.getRoot(), baseName + ".r00");
        writeRar4Volume(first, entryName, firstPayload, RAR4_FILE_SPLIT_AFTER, crc32(firstPayload), firstPayload.length);
        writeRar4Volume(later, entryName, secondPayload, RAR4_FILE_SPLIT_BEFORE, crc32(payload), payload.length);
        return later;
    }

    private File buildRar4EncryptedSplitStoredArchive(String baseName,
                                                      String entryName,
                                                      byte[] payload,
                                                      char[] password) throws Exception {
        byte[] salt = new byte[] {0x52, 0x11, 0x6d, 0x22, 0x07, (byte) 0x89, 0x44, 0x0f};
        byte[] padded = java.util.Arrays.copyOf(payload, ((payload.length + 15) / 16) * 16);
        Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(password, salt);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(parameters.key, "AES"),
                new IvParameterSpec(parameters.iv));
        byte[] encrypted = cipher.doFinal(padded);

        int cut = Math.min(encrypted.length - 1, Math.max(1, encrypted.length / 2 + 7));
        byte[] firstEncrypted = java.util.Arrays.copyOfRange(encrypted, 0, cut);
        byte[] secondEncrypted = java.util.Arrays.copyOfRange(encrypted, cut, encrypted.length);
        File first = new File(tempFolder.getRoot(), baseName + ".rar");
        File later = new File(tempFolder.getRoot(), baseName + ".r00");
        writeRar4EncryptedVolume(
                first,
                entryName,
                firstEncrypted,
                RAR4_FILE_SPLIT_AFTER | RAR4_FILE_PASSWORD | RAR4_FILE_SALT,
                crc32(java.util.Arrays.copyOfRange(payload, 0, Math.min(payload.length, cut))),
                Math.min(payload.length, cut),
                salt);
        writeRar4EncryptedVolume(
                later,
                entryName,
                secondEncrypted,
                RAR4_FILE_SPLIT_BEFORE | RAR4_FILE_PASSWORD | RAR4_FILE_SALT,
                crc32(payload),
                payload.length,
                salt);
        return later;
    }

    private static final int RAR4_FILE_SPLIT_BEFORE = 0x0001;
    private static final int RAR4_FILE_SPLIT_AFTER = 0x0002;
    private static final int RAR4_FILE_PASSWORD = 0x0004;
    private static final int RAR4_FILE_SALT = 0x0400;

    private static void writeRar4Volume(File file,
                                        String entryName,
                                        byte[] payload,
                                        int fileFlags,
                                        long crc,
                                        long unpackedSize) throws Exception {
        byte[] rawName = entryName.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00});
            out.write(rar4Header(0x73, 0, new byte[] {0, 0, 0, 0, 0, 0}));
            byte[] body = bytes(
                    uint32(payload.length),
                    uint32(unpackedSize),
                    new byte[] {1},
                    uint32(crc),
                    uint32(0),
                    new byte[] {29},
                    new byte[] {0x30},
                    uint16(rawName.length),
                    uint32(0),
                    rawName);
            out.write(rar4Header(0x74, 0x8000 | fileFlags, body));
            out.write(payload);
            out.write(rar4Header(0x7b, 0, new byte[0]));
        }
    }

    private static void writeRar4EncryptedVolume(File file,
                                                 String entryName,
                                                 byte[] encryptedPayload,
                                                 int fileFlags,
                                                 long crc,
                                                 long unpackedSize,
                                                 byte[] salt) throws Exception {
        byte[] rawName = entryName.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00});
            out.write(rar4Header(0x73, 0, new byte[] {0, 0, 0, 0, 0, 0}));
            byte[] body = bytes(
                    uint32(encryptedPayload.length),
                    uint32(unpackedSize),
                    new byte[] {1},
                    uint32(crc),
                    uint32(0),
                    new byte[] {29},
                    new byte[] {0x30},
                    uint16(rawName.length),
                    uint32(0),
                    rawName,
                    salt);
            out.write(rar4Header(0x74, 0x8000 | fileFlags, body));
            out.write(encryptedPayload);
            out.write(rar4Header(0x7b, 0, new byte[0]));
        }
    }

    private static byte[] rar4UnicodeRawName(String fallbackName, String unicodeName) throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(fallbackName.getBytes(StandardCharsets.US_ASCII));
        encoded.write(0);
        encoded.write(0);

        int[] codeUnits = unicodeName.chars().toArray();
        int index = 0;
        while (index < codeUnits.length) {
            int group = Math.min(4, codeUnits.length - index);
            int flags = 0;
            for (int i = 0; i < group; i++) {
                flags |= 0x02 << (6 - (i * 2));
            }
            encoded.write(flags);
            for (int i = 0; i < group; i++) {
                int ch = codeUnits[index++];
                encoded.write(ch & 0xff);
                encoded.write((ch >>> 8) & 0xff);
            }
        }
        return encoded.toByteArray();
    }

    private static byte[] rar4Header(int type, int flags, byte[] body) throws Exception {
        int size = 7 + body.length;
        return bytes(
                uint16(0),
                new byte[] {(byte) type},
                uint16(flags),
                uint16(size),
                body);
    }

    private static byte[] header(byte[] headerData) throws Exception {
        byte[] headerSize = vint(headerData.length);
        CRC32 crc32 = new CRC32();
        crc32.update(headerSize);
        crc32.update(headerData);
        return bytes(uint32(crc32.getValue()), headerSize, headerData);
    }

    private static byte[] vint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long remaining = value;
        do {
            int b = (int) (remaining & 0x7f);
            remaining >>>= 7;
            if (remaining != 0) b |= 0x80;
            out.write(b);
        } while (remaining != 0);
        return out.toByteArray();
    }

    private static byte[] uint32(long value) {
        return new byte[] {
                (byte) (value & 0xff),
                (byte) ((value >>> 8) & 0xff),
                (byte) ((value >>> 16) & 0xff),
                (byte) ((value >>> 24) & 0xff)
        };
    }

    private static byte[] uint16(int value) {
        return new byte[] {
                (byte) (value & 0xff),
                (byte) ((value >>> 8) & 0xff)
        };
    }

    private static byte[] bytes(byte[]... parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part);
        return out.toByteArray();
    }
}
