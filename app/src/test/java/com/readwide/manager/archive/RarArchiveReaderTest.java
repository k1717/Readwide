package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.readwide.manager.util.FileUtils;

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

    @Test public void progressTotalUsesFinalSplitSizeNotUnknownOrPartialHead() {
        for (long headSize : new long[]{-1L, 3L, Long.MAX_VALUE}) {
            assertEquals(19L, RarArchiveReader.sumUnpackedBytes(java.util.Arrays.asList(
                    progressEntry(headSize, false, true), progressEntry(5, true, true),
                    progressEntry(12, true, false), progressEntry(7, false, false))));
        }
    }

    @Test public void progressTotalStaysUnknownWhenAnyFinalSizeIsUnknown() {
        RarArchiveReader.RarEntry known = progressEntry(7, false, false);
        RarArchiveReader.RarEntry unknown = progressEntry(-1, false, false);
        assertEquals(-1L, RarArchiveReader.sumUnpackedBytes(java.util.Arrays.asList(known, unknown)));
        assertEquals(-1L, RarArchiveReader.sumUnpackedBytes(java.util.Arrays.asList(unknown, known)));
        assertEquals(-1L, RarArchiveReader.sumUnpackedBytes(java.util.Arrays.asList(
                progressEntry(3, false, true), progressEntry(-1, true, false), known)));
        com.readwide.manager.util.FileOperationProgress progress =
                new com.readwide.manager.util.FileOperationProgress("RAR", null);
        progress.setTotalBytes(RarArchiveReader.sumUnpackedBytes(java.util.Arrays.asList(known, unknown)));
        progress.addDoneBytes(8);
        assertTrue(progress.snapshot().indeterminate);
        assertEquals(8L, progress.snapshot().doneBytes);
        assertFalse(progress.snapshot().complete);
    }

    @Test public void progressTotalTreatsOverflowAsUnknownNotAnExactMaximum() {
        assertEquals(Long.MAX_VALUE, RarArchiveReader.sumUnpackedBytes(java.util.Arrays.asList(
                progressEntry(Long.MAX_VALUE, false, false), progressEntry(0, false, false))));
        assertEquals(-1L, RarArchiveReader.sumUnpackedBytes(java.util.Arrays.asList(
                progressEntry(Long.MAX_VALUE, false, false), progressEntry(1, false, false))));
    }

    @Test public void progressTotalIgnoresDirectoriesAndKeepsEmptyTotals() {
        RarArchiveReader.RarEntry directory = new RarArchiveReader.RarEntry("dir/", true,
                -1, 0, 0, 5, 0, false, false, false, null, -1, 0);
        assertEquals(0L, RarArchiveReader.sumUnpackedBytes(java.util.Collections.emptyList()));
        assertEquals(0L, RarArchiveReader.sumUnpackedBytes(java.util.Arrays.asList(
                null, directory, progressEntry(0, false, false))));
    }

    private static RarArchiveReader.RarEntry progressEntry(long size, boolean before, boolean after) {
        return new RarArchiveReader.RarEntry("file.bin", false, size, 0, 0,
                5, 1, false, before, after, null, -1, 0);
    }

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

    @Test
    public void rar5UnixNanosecondsFollowAllWholeSecondTimes() throws Exception {
        byte[] record = bytes(vint(3), vint(0x1f),
                uint32(1_700_000_000L), uint32(1_600_000_000L), uint32(1_500_000_000L),
                uint32(123_456_789L), uint32(234_567_890L), uint32(345_678_901L));
        File archive = buildRar5ArchiveWithExtra(bytes(vint(record.length), record));
        List<RarArchiveReader.RarEntry> entries =
                RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, null);
        assertEquals(1_700_000_000_123L, entries.get(0).timeMillis);
    }

    @Test
    public void rar5ModificationOnlyNanosecondsKeepSecondsAndFractionSeparate() throws Exception {
        byte[] record = bytes(vint(3), vint(0x13),
                uint32(1_700_000_000L), uint32(999_999_999L));
        File archive = buildRar5ArchiveWithExtra(bytes(vint(record.length), record));
        assertEquals(1_700_000_000_999L,
                RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, null).get(0).timeMillis);
    }

    @Test(expected = java.io.IOException.class)
    public void truncatedTimeRecordCannotBorrowBytesFromNextRecord() throws Exception {
        byte[] shortTime = bytes(vint(3), vint(3)); // mtime required but missing
        byte[] nextRecord = bytes(vint(99), new byte[16]);
        File archive = buildRar5ArchiveWithExtra(bytes(
                vint(shortTime.length), shortTime, vint(nextRecord.length), nextRecord));
        RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, null);
    }

    @Test(expected = java.io.IOException.class)
    public void requiredPasswordCheckCannotBeSilentlyDropped() throws Exception {
        byte[] record = bytes(vint(1), vint(0), vint(1), new byte[] {8},
                new byte[16], new byte[16]); // flag 1 requires twelve more bytes
        File archive = buildRar5ArchiveWithExtra(bytes(vint(record.length), record));
        RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, null);
    }

    @Test(expected = java.io.IOException.class)
    public void rar5InvalidNanosecondFractionIsRejected() throws Exception {
        byte[] record = bytes(vint(3), vint(0x13), uint32(1_700_000_000L), uint32(1_000_000_000L));
        File archive = buildRar5ArchiveWithExtra(bytes(vint(record.length), record));
        RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, null);
    }

    @Test public void rar5BlakeExtraIsRetained() throws Exception {
        byte[] hash = RarBlake2spTest.sequence(32);
        byte[] record = bytes(vint(2),vint(0),hash);
        File archive = buildRar5ArchiveWithExtra(bytes(vint(record.length),record));
        assertArrayEquals(hash,RarArchiveReader.readEntries(archive,null).get(0).blake2sp);
    }

    @Test(expected = java.io.IOException.class)
    public void truncatedBlakeCannotBorrowFromFollowingRecord() throws Exception {
        byte[] hash = bytes(vint(2),vint(0),new byte[31]);
        byte[] next = bytes(vint(99),new byte[32]);
        File archive = buildRar5ArchiveWithExtra(bytes(vint(hash.length),hash,vint(next.length),next));
        RarArchiveReader.readEntries(archive,null);
    }

    @Test(expected = java.io.IOException.class)
    public void duplicateBlakeRecordIsRejected() throws Exception {
        byte[] hash = bytes(vint(2),vint(0),new byte[32]);
        File archive = buildRar5ArchiveWithExtra(bytes(vint(hash.length),hash,vint(hash.length),hash));
        RarArchiveReader.readEntries(archive,null);
    }

    @Test public void rar5RedirectionsAreNotListedAsEmptyFilesOrDirectories() throws Exception {
        for (int type = 1; type <= 5; type++) {
            for (int flags = 0; flags <= 1; flags++) {
                File archive = buildRar5RedirectionArchive(
                        redirectionExtra(type, flags, "../outside"), false, flags != 0, new byte[0]);
                List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
                assertEquals(2, entries.size());
                assertEquals("ordinary.txt", entries.get(0).path);
                assertEquals("empty.txt", entries.get(1).path);
                assertEquals(0L, entries.get(1).unpackedSize);
                List<ArchiveSupport.EntryInfo> visible = RarArchiveReader.listEntries(archive, null);
                assertEquals(2, visible.size());
                assertEquals("ordinary.txt", visible.get(0).path);
                assertEquals("empty.txt", visible.get(1).path);
            }
        }
    }

    @Test public void rar5RedirectionDoesNotShadowLaterRegularFileWithSameName() throws Exception {
        for (int type = 1; type <= 5; type++) {
            File archive = buildRar5RedirectionArchive(
                    redirectionExtra(type, 0, "ordinary.txt"), true, false, new byte[0]);
            File out = new File(tempFolder.newFolder(), "selected.txt");
            assertTrue(RarArchiveReader.extractSingleEntry(archive, "redirected", out, null));
            assertArrayEquals("ordinary payload".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(out.toPath()));
        }
    }

    @Test public void rar5BulkSkipsRedirectionsButExtractsRealAndEmptyFiles() throws Exception {
        for (int type = 1; type <= 5; type++) {
            File archive = buildRar5RedirectionArchive(
                    redirectionExtra(type, 1, "../outside"), false, true, new byte[0]);
            File out = tempFolder.newFolder();
            assertTrue(RarArchiveReader.extractArchiveIntoDirectory(archive, out, null, null));
            assertFalse(new File(out, "redirected").exists());
            assertArrayEquals("ordinary payload".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(new File(out, "ordinary.txt").toPath()));
            assertTrue(new File(out, "empty.txt").isFile());
            assertEquals(0L, new File(out, "empty.txt").length());
            assertEquals(2, out.list().length);
        }
    }

    @Test public void rar5SelectingOnlyRedirectionDoesNotReplaceExistingOutput() throws Exception {
        for (int type = 1; type <= 5; type++) {
            File archive = buildRar5RedirectionArchive(
                    redirectionExtra(type, 0, "ordinary.txt"), false, false, new byte[0]);
            File out = tempFolder.newFile();
            byte[] previous = "keep existing output".getBytes(StandardCharsets.UTF_8);
            Files.write(out.toPath(), previous);
            assertFalse(RarArchiveReader.extractSingleEntry(archive, "redirected", out, null));
            assertArrayEquals(previous, Files.readAllBytes(out.toPath()));
        }
    }

    @Test public void rar5ForwardViewerUsesFilteredScanEvenWithNativeAvailable() throws Exception {
        for (int type = 1; type <= 5; type++) {
            File archive = buildRar5RedirectionArchive(
                    redirectionExtra(type, 0, "ordinary.txt"), false, false, new byte[0]);
            File spool = tempFolder.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader =
                    Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, true)) {
                assertNotNull(reader);
                assertEquals("ordinary.txt", reader.nextEntry().path);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[7];
                for (int count; (count = reader.read(buffer)) != -1;) output.write(buffer, 0, count);
                assertArrayEquals("ordinary payload".getBytes(StandardCharsets.UTF_8), output.toByteArray());
                assertEquals("empty.txt", reader.nextEntry().path);
                assertEquals(-1, reader.read(buffer));
                assertEquals(null, reader.nextEntry());
            }
            assertEquals(0, spool.list().length);
        }
        File ordinary = buildRar5Archive("ordinary.txt", new byte[] {42}, 0);
        assertEquals(null, Rar5CompressedArchiveExtractor.openForwardReader(
                ordinary, null, tempFolder.newFolder(), true));
        File unsafeName = buildRar5RedirectionArchive(redirectionExtra(5, 0, "ordinary.txt"),
                false, false, new byte[0], "../redirected");
        try (ArchiveSupport.ForwardArchiveReader reader = Rar5CompressedArchiveExtractor.openForwardReader(
                unsafeName, null, tempFolder.newFolder(), true)) {
            assertNotNull(reader); // Even an excluded pathname must avoid native file-copy parsing.
            assertEquals("ordinary.txt", reader.nextEntry().path);
        }
    }

    @Test public void rar5RedirectionTargetCannotBorrowFollowingExtraOrHeaderBytes() throws Exception {
        byte[][] malformedRecords = {
                bytes(vint(5)), // missing type, flags and length
                bytes(vint(5), vint(1), vint(0)), // missing target length
                bytes(vint(5), vint(1), vint(0), vint(0)), // empty target
                bytes(vint(5), vint(1), vint(0), vint(4), new byte[] {'a'}),
                bytes(vint(5), vint(1), vint(0), vint(Long.MAX_VALUE))
        };
        byte[] nextRecord = bytes(vint(99), new byte[32]);
        for (byte[] record : malformedRecords) {
            assertRedirectionRejected(bytes(vint(record.length), record,
                    vint(nextRecord.length), nextRecord), new byte[0]);
        }
    }

    @Test public void rar5DuplicateAndUnknownRedirectionMetadataIsRejected() throws Exception {
        byte[] valid = redirectionExtra(1, 0, "ordinary.txt");
        assertRedirectionRejected(bytes(valid, valid), new byte[0]);
        assertRedirectionRejected(redirectionExtra(0, 0, "ordinary.txt"), new byte[0]);
        assertRedirectionRejected(redirectionExtra(6, 0, "ordinary.txt"), new byte[0]);
        assertRedirectionRejected(redirectionExtra(1, 2, "ordinary.txt"), new byte[0]);
    }

    @Test public void rar5RedirectionWithPayloadIsNotSilentlySkipped() throws Exception {
        assertRedirectionRejected(redirectionExtra(5, 0, "ordinary.txt"), new byte[] {1});
    }

    private void assertRedirectionRejected(byte[] extra, byte[] payload) throws Exception {
        File archive = buildRar5RedirectionArchive(extra, false, false, payload);
        try {
            RarArchiveReader.readEntries(archive, null);
            fail("Malformed/unsupported redirection must not become an ordinary entry");
        } catch (java.io.IOException expected) {
            // Exercise the bounded first-party metadata parser, not native fallback.
        }
    }

    private static byte[] redirectionExtra(int type, int flags, String target) throws Exception {
        byte[] targetBytes = target.getBytes(StandardCharsets.UTF_8);
        byte[] record = bytes(vint(5), vint(type), vint(flags), vint(targetBytes.length), targetBytes);
        return bytes(vint(record.length), record);
    }

    private File buildRar5RedirectionArchive(byte[] extra, boolean samePath,
                                            boolean directory, byte[] redirectedPayload) throws Exception {
        return buildRar5RedirectionArchive(extra, samePath, directory, redirectedPayload, "redirected");
    }

    private File buildRar5RedirectionArchive(byte[] extra, boolean samePath,
                                            boolean directory, byte[] redirectedPayload, String path) throws Exception {
        File archive = new File(tempFolder.newFolder(), "redirection.rar");
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00});
            out.write(header(bytes(vint(1), vint(0), vint(0))));
            writeRar5StoredRecord(out, path, redirectedPayload, extra, directory);
            writeRar5StoredRecord(out, samePath ? path : "ordinary.txt",
                    "ordinary payload".getBytes(StandardCharsets.UTF_8), new byte[0], false);
            writeRar5StoredRecord(out, "empty.txt", new byte[0], new byte[0], false);
            out.write(header(bytes(vint(5), vint(0), vint(0))));
        }
        return archive;
    }

    private static void writeRar5StoredRecord(FileOutputStream out, String path, byte[] payload,
                                             byte[] extra, boolean directory) throws Exception {
        byte[] name = path.getBytes(StandardCharsets.UTF_8);
        out.write(header(bytes(vint(2), vint(3), vint(extra.length), vint(payload.length),
                vint(directory ? 5 : 4), vint(payload.length), vint(directory ? 16877 : 33188), uint32(crc32(payload)),
                vint(0), vint(1), vint(name.length), name, extra)));
        out.write(payload);
    }

    private File buildRar5ArchiveWithExtra(byte[] extra) throws Exception {
        File archive = tempFolder.newFile("metadata.rar");
        byte[] name = "data.txt".getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00});
            out.write(header(bytes(vint(1), vint(0), vint(0))));
            out.write(header(bytes(vint(2), vint(3), vint(extra.length), vint(0),
                    vint(0), vint(0), vint(0), vint(0), vint(1), vint(name.length), name, extra)));
            out.write(header(bytes(vint(5), vint(0), vint(0))));
        }
        return archive;
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

    @Test public void unknownIntermediateSizesIgnorePlaceholderAndExtractStoredVolumes() throws Exception {
        for (long placeholder : new long[]{0, 17, Long.MAX_VALUE, -1L}) {
            checkUnknownStoredVolumes(placeholder, false);
        }
    }

    @Test public void unknownIntermediateSizesRetainAesStoredChecks() throws Exception {
        checkUnknownStoredVolumes(-1L, true);
    }

    private void checkUnknownStoredVolumes(long placeholder, boolean encrypted) throws Exception {
        checkUnknownStoredVolumes(placeholder, encrypted, false);
    }

    @Test public void unknownFinalPlainStoredSizesExtractAcrossVolumes() throws Exception {
        for (long placeholder : new long[]{0, 17, Long.MAX_VALUE, -1L}) {
            checkUnknownStoredVolumes(placeholder, false, true);
        }
    }

    private void checkUnknownStoredVolumes(long placeholder, boolean encrypted,
                                           boolean unknownFinalSize) throws Exception {
        byte[] plain = "streaming input across volumes".getBytes(StandardCharsets.UTF_8);
        byte[] packed = encrypted ? encryptRar5StoredPayload(plain, "pw".toCharArray()) : plain;
        byte[] extra = encrypted ? rar5EncryptionExtra() : new byte[0];
        File directory = tempFolder.newFolder();
        File[] volumes = new File[3];
        int[] offsets = {0, 3, 7, packed.length}; // Includes non-AES-aligned boundaries.
        for (int i = 0; i < 3; i++) {
            volumes[i] = new File(directory, "stream.part" + (i + 1) + ".rar");
            byte[] part = java.util.Arrays.copyOfRange(packed, offsets[i], offsets[i + 1]);
            writeRar5Volume(volumes[i], "stream.txt", part,
                    (i == 0 ? 0 : 8) | (i < 2 ? 16 : 0),
                    i < 2 ? crc32(part) : crc32(plain),
                    i < 2 || unknownFinalSize ? placeholder : plain.length,
                    extra, i < 2 || unknownFinalSize);
        }
        char[] password = encrypted ? "pw".toCharArray() : null;
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(volumes[1], password);
        assertEquals(3, entries.size());
        assertEquals(-1L, entries.get(0).unpackedSize);
        assertEquals(-1L, entries.get(1).unpackedSize);
        assertEquals(unknownFinalSize ? -1L : plain.length, entries.get(2).unpackedSize);
        File selected = tempFolder.newFile();
        assertTrue(RarArchiveReader.extractSingleEntry(volumes[1], "stream.txt", selected, password));
        assertArrayEquals(plain, Files.readAllBytes(selected.toPath()));
        File bulk = tempFolder.newFolder();
        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(volumes[0], bulk, password));
        assertArrayEquals(plain, Files.readAllBytes(new File(bulk, "stream.txt").toPath()));
        File spool = tempFolder.newFolder();
        // Even with native available, unknown intermediate headers need the first-party route.
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar5CompressedArchiveExtractor.openForwardReader(volumes[0], password, spool, true)) {
            assertNotNull(reader);
            assertEquals("stream.txt", reader.nextEntry().path);
            org.junit.Assert.assertThrows(java.io.IOException.class,
                    () -> reader.drainCurrentEntry(plain.length - 1));
            assertEquals(0, spool.list().length); // Reject the bound before making a spool.
            assertTrue(reader.drainCurrentEntry(plain.length));
            assertEquals(null, reader.nextEntry());
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void unknownFinalEncryptedStoredSizeUsesUniqueFinalChecksum() throws Exception {
        File archive = tempFolder.newFile();
        byte[] plain = new byte[]{42};
        writeRar5Volume(archive, "stream.txt", encryptRar5StoredPayload(plain, "pw".toCharArray()),
                0, crc32(plain), 0, rar5EncryptionExtra(), true);
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        assertEquals(-1L, entries.get(0).unpackedSize);
        assertTrue(entries.get(0).encrypted());
        File output = tempFolder.newFile();
        Files.write(output.toPath(), new byte[]{11, 22});
        RarArchiveReader.extractStoredEntry(entries.get(0), output, "pw".toCharArray(), entries, null);
        assertArrayEquals(plain, Files.readAllBytes(output.toPath()));
    }

    @Test public void unknownEncryptedStoredFinalSizeWorksAcrossVolumesAndForwardReads() throws Exception {
        byte[] plain = "unknown encrypted tail\0\0".getBytes(StandardCharsets.UTF_8);
        byte[] packed = encryptRar5StoredPayload(plain, "pw".toCharArray());
        File first = new File(tempFolder.getRoot(), "unknown-aes.part1.rar");
        File last = new File(tempFolder.getRoot(), "unknown-aes.part2.rar");
        byte[] head = java.util.Arrays.copyOf(packed, 7); // Split inside an AES block.
        writeRar5Volume(first, "stream.txt", head, 16, crc32(head), 0, rar5EncryptionExtra(), true);
        writeRar5Volume(last, "stream.txt", java.util.Arrays.copyOfRange(packed, 7, packed.length),
                8, crc32(plain), Long.MAX_VALUE, rar5EncryptionExtra(), true);
        File selected = tempFolder.newFile();
        assertTrue(RarArchiveReader.extractSingleEntry(last, "stream.txt", selected, "pw".toCharArray()));
        assertArrayEquals(plain, Files.readAllBytes(selected.toPath()));
        File bulk = tempFolder.newFolder();
        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(first, bulk, "pw".toCharArray()));
        assertArrayEquals(plain, Files.readAllBytes(new File(bulk, "stream.txt").toPath()));
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader =
                Rar5CompressedArchiveExtractor.openForwardReader(first, "pw".toCharArray(), spool, true)) {
            assertNotNull(reader); assertEquals("stream.txt", reader.nextEntry().path);
            java.io.ByteArrayOutputStream actual = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[3]; int count;
            while ((count = reader.read(buffer)) != -1) actual.write(buffer, 0, count);
            assertArrayEquals(plain, actual.toByteArray()); assertEquals(null, reader.nextEntry());
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void unknownEncryptedStoredChecksumAndPasswordFailuresRestoreOriginal() throws Exception {
        byte[] plain = "preserve original target".getBytes(StandardCharsets.UTF_8);
        for (boolean wrongPassword : new boolean[]{false, true}) {
            File archive = tempFolder.newFile();
            writeRar5Volume(archive, "stream.txt", encryptRar5StoredPayload(plain, "pw".toCharArray()),
                    0, crc32(plain) ^ (wrongPassword ? 0 : 1), 0, rar5EncryptionExtra(), true);
            List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
            File output = tempFolder.newFile(); byte[] original = {11,22}; Files.write(output.toPath(), original);
            org.junit.Assert.assertThrows(java.io.IOException.class, () -> RarArchiveReader.extractStoredEntry(
                    entries.get(0), output, (wrongPassword ? "wrong" : "pw").toCharArray(), entries, null));
            assertArrayEquals(original, Files.readAllBytes(output.toPath()));
        }
    }

    @Test public void unknownEncryptedStoredForwardDrainChecksResolvedLength() throws Exception {
        byte[] plain = "stored tail\0\0".getBytes(StandardCharsets.UTF_8);
        File archive = tempFolder.newFile();
        writeRar5Volume(archive, "stream.txt", encryptRar5StoredPayload(plain, "pw".toCharArray()),
                0, crc32(plain), 0, rar5EncryptionExtra(), true);
        for (boolean tooSmall : new boolean[]{true, false}) {
            File spool = tempFolder.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader =
                    Rar5CompressedArchiveExtractor.openForwardReader(archive, "pw".toCharArray(), spool, true)) {
                assertNotNull(reader); reader.nextEntry();
                if (tooSmall) {
                    org.junit.Assert.assertThrows(java.io.IOException.class, () -> reader.drainCurrentEntry(plain.length - 1));
                    org.junit.Assert.assertThrows(java.io.IOException.class, reader::nextEntry);
                } else {
                    assertTrue(reader.drainCurrentEntry(plain.length)); assertEquals(null, reader.nextEntry());
                }
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void unknownEncryptedStoredSplitStillChecksIntermediateCiphertext() throws Exception {
        byte[] plain = "verify each encrypted volume".getBytes(StandardCharsets.UTF_8);
        byte[] packed = encryptRar5StoredPayload(plain, "pw".toCharArray());
        File first = new File(tempFolder.getRoot(), "bad-unknown-aes.part1.rar");
        File last = new File(tempFolder.getRoot(), "bad-unknown-aes.part2.rar");
        byte[] head = java.util.Arrays.copyOf(packed, 7);
        writeRar5Volume(first, "stream.txt", head, 16, crc32(head) ^ 1, 0, rar5EncryptionExtra(), true);
        writeRar5Volume(last, "stream.txt", java.util.Arrays.copyOfRange(packed, 7, packed.length),
                8, crc32(plain), 0, rar5EncryptionExtra(), true);
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(first, null);
        File output = tempFolder.newFile(); byte[] original = {99}; Files.write(output.toPath(), original);
        org.junit.Assert.assertThrows(java.io.IOException.class, () -> RarArchiveReader.extractStoredEntry(
                entries.get(0), output, "pw".toCharArray(), entries, null));
        assertArrayEquals(original, Files.readAllBytes(output.toPath()));
    }

    @Test public void unknownPlainStoredSizeUsesPackedBoundsIncludingEmptyFiles() throws Exception {
        for (long placeholder : new long[]{0, 17, Long.MAX_VALUE, -1L}) {
            for (byte[] plain : new byte[][]{new byte[0], new byte[]{42},
                    "stored trailing zeros\0\0".getBytes(StandardCharsets.UTF_8)}) {
                File archive = tempFolder.newFile();
                writeRar5Volume(archive, "stream.txt", plain, 0, crc32(plain),
                        placeholder, new byte[0], true);
                List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
                assertEquals(-1L, entries.get(0).unpackedSize);
                File selected = tempFolder.newFile();
                assertTrue(RarArchiveReader.extractSingleEntry(archive, "stream.txt", selected, null));
                assertArrayEquals(plain, Files.readAllBytes(selected.toPath()));
                File bulk = tempFolder.newFolder();
                assertTrue(RarArchiveReader.extractArchiveIntoDirectory(archive, bulk, null));
                assertArrayEquals(plain, Files.readAllBytes(new File(bulk, "stream.txt").toPath()));
                File spool = tempFolder.newFolder();
                try (ArchiveSupport.ForwardArchiveReader reader =
                             Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, true)) {
                    assertNotNull(reader);
                    assertEquals("stream.txt", reader.nextEntry().path);
                    if (plain.length > 0) {
                        org.junit.Assert.assertThrows(java.io.IOException.class,
                                () -> reader.drainCurrentEntry(plain.length - 1));
                        assertEquals(0, spool.list().length);
                    }
                    java.io.ByteArrayOutputStream actual = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[3];
                    int count;
                    while ((count = reader.read(buffer)) != -1) actual.write(buffer, 0, count);
                    assertArrayEquals(plain, actual.toByteArray());
                    assertEquals(null, reader.nextEntry());
                }
                assertEquals(0, spool.list().length);
            }
        }
    }

    @Test public void unknownEncryptedStoredMemberDoesNotHideOtherStoredFiles() throws Exception {
        File archive = tempFolder.newFile();
        writeRar5Volume(archive, "unknown.txt", encryptRar5StoredPayload(new byte[]{42}, "pw".toCharArray()),
                0, crc32(new byte[]{42}), 0, rar5EncryptionExtra(), true);
        File second = tempFolder.newFile();
        byte[] plain = {11, 22, 33};
        writeRar5Volume(second, "readable.txt", plain, 0, crc32(plain), plain.length);
        byte[] firstBytes = Files.readAllBytes(archive.toPath()), secondBytes = Files.readAllBytes(second.toPath());
        int endSize = header(bytes(vint(5), vint(0), vint(0))).length;
        int startSize = 8 + header(bytes(vint(1), vint(0), vint(0x0001))).length;
        Files.write(archive.toPath(), bytes(java.util.Arrays.copyOf(firstBytes, firstBytes.length - endSize),
                java.util.Arrays.copyOfRange(secondBytes, startSize, secondBytes.length)));
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        assertEquals(2, entries.size());
        assertEquals(-1L, entries.get(0).unpackedSize);
        File output = tempFolder.newFile();
        RarArchiveReader.extractStoredEntry(entries.get(1), output, null, entries, null);
        assertArrayEquals(plain, Files.readAllBytes(output.toPath()));
    }

    @Test public void unknownPlainStoredChecksumFailureRestoresExistingFile() throws Exception {
        File archive = tempFolder.newFile();
        byte[] plain = new byte[]{42, 0};
        writeRar5Volume(archive, "stream.txt", plain, 0, crc32(plain) ^ 1,
                0, new byte[0], true);
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        File output = tempFolder.newFile();
        byte[] original = new byte[]{11, 22, 33};
        Files.write(output.toPath(), original);
        org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> RarArchiveReader.extractStoredEntry(entries.get(0), output, null, entries, null));
        assertArrayEquals(original, Files.readAllBytes(output.toPath()));
    }

    @Test public void unknownPlainStoredSplitChecksEveryPartAndRestoresExistingFile() throws Exception {
        byte[] plain = new byte[]{11, 22, 33, 44};
        for (int damaged = 0; damaged < 2; damaged++) {
            File directory = tempFolder.newFolder();
            File first = new File(directory, "stream.part1.rar");
            File last = new File(directory, "stream.part2.rar");
            byte[] firstBytes = java.util.Arrays.copyOfRange(plain, 0, 2);
            writeRar5Volume(first, "stream.txt", firstBytes, 16,
                    crc32(firstBytes) ^ (damaged == 0 ? 1 : 0), 0, new byte[0], true);
            writeRar5Volume(last, "stream.txt", java.util.Arrays.copyOfRange(plain, 2, 4), 8,
                    crc32(plain) ^ (damaged == 1 ? 1 : 0), 0, new byte[0], true);
            List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(first, null);
            File output = tempFolder.newFile();
            byte[] original = new byte[]{99};
            Files.write(output.toPath(), original);
            org.junit.Assert.assertThrows(java.io.IOException.class,
                    () -> RarArchiveReader.extractStoredEntry(entries.get(0), output, null, entries, null));
            assertArrayEquals(original, Files.readAllBytes(output.toPath()));
            File spool = tempFolder.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader =
                         Rar5CompressedArchiveExtractor.openForwardReader(first, null, spool, true)) {
                assertNotNull(reader);
                reader.nextEntry();
                org.junit.Assert.assertThrows(java.io.IOException.class, () -> reader.read(new byte[4]));
                assertEquals(0, spool.list().length);
                org.junit.Assert.assertThrows(java.io.IOException.class, reader::nextEntry);
            }
        }
    }

    @Test public void unknownPlainStoredTruncationRestoresExistingFile() throws Exception {
        File archive = tempFolder.newFile();
        byte[] plain = new byte[]{11, 22, 33, 44};
        writeRar5Volume(archive, "stream.txt", plain, 0, crc32(plain), 0, new byte[0], true);
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        try (java.io.RandomAccessFile source = new java.io.RandomAccessFile(archive, "rw")) {
            source.setLength(entries.get(0).dataOffset + plain.length - 1);
        }
        File output = tempFolder.newFile();
        byte[] original = new byte[]{99};
        Files.write(output.toPath(), original);
        org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> RarArchiveReader.extractStoredEntry(entries.get(0), output, null, entries, null));
        assertArrayEquals(original, Files.readAllBytes(output.toPath()));
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
        writeRar5Volume(file, entryName, payload, fileHeaderFlags, crc, unpackedSize, extra, false);
    }

    private static void writeRar5Volume(File file, String entryName, byte[] payload,
            long fileHeaderFlags, long crc, long unpackedSize, byte[] extra,
            boolean unknownUnpackedSize) throws Exception {
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
                    vint(0x0004 | (unknownUnpackedSize ? 0x0008 : 0)),
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
