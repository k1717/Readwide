package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.readwide.manager.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
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
    public void listEntries_alzCp949Filename_autoDecodes() throws Exception {
        byte[] payload = "stored".getBytes(StandardCharsets.UTF_8);
        File archive = buildAlzArchiveWithNameBytes(
                "한글/page001.txt".getBytes(Charset.forName("MS949")),
                payload,
                0,
                false,
                null);

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);

        assertEquals("한글/", entries.get(0).path);
        assertEquals("한글/page001.txt", entries.get(1).path);
    }

    @Test
    public void extractSingleEntry_alzStoredArchive_writesPayload() throws Exception {
        byte[] payload = "stored payload".getBytes(StandardCharsets.UTF_8);
        File archive = buildAlzArchive("page001.txt", payload, 0, false, null);
        File out = tempFolder.newFile("alz-stored.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page001.txt", out, null));

        assertEquals("stored payload", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
    }

    /**
     * ALZ bzip2 bitstream fixtures. ALZip 4.x writes a trimmed bzip2 variant
     * (no "BZh"/block magics, no per-block CRC or randomised bit; block
     * framing is 'D','L','Z',0x01 and end-of-stream is 'D','L','Z',0x02).
     * These payloads were produced by bit-exact transformation of standard
     * bzip2 output into that variant and validated byte-identical against the
     * zlib-licensed unalz 0.65 reference decoder before being embedded here.
     * The single-block stream compresses {@link #alzBzipSingleBlockPlain()};
     * the second stream carries three bzip2 blocks (level-1 100k blocks) of
     * {@link #alzBzipMultiBlockPlain()}.
     */
    private static final String ALZ_BZ_SINGLE_BLOCK_B64 =
            "RExaAQABPz8AgAIgAEAIACB9W5BgQAEgUDTQyMmIFKpGhpk0PSaZCaidxNxOwmwmBPAmgnkTAmYmYToJsJ9EwJwJyJgTgTkT"
            + "8JyJoEyE2CaiZif0RMWgIA==";
    private static final String ALZ_BZ_MULTI_BLOCK_B64 =
            "RExaAQAEfT8Af/////////////8AgAUwAAA5hNAaA0aMI0GI0xMmJoMI0DIBkwOYTQGgNGjCNBiNMTJiaDCNAyAZMBNVVRiZ"
            + "NMjEMmI0aMQDE0aGBAyDRpkZNDmE0BoDRowjQYjTEyYmgwjQMgGTDoQKsrpwFWBAVYMBVhQFXUgKsOAqxICrqwFWLAVdaAqx"
            + "oCrHgKuvAVdiAqyICrJgKsqAqyUCrsoFXaQKu2gVZaBVmIFXcQKs1AqzkCrPQKu6gVd5Aq0ECrvoFXgQKtFAq8KBV4kCrSQK"
            + "tNAq1ECrxoFXkQKvKgVeZAq86BV6ECr0oFWqgVayBVroFXqQKthAq9aBV7ECrZQKvagVe5Aq96BVtIFW2gVbiBVuoFW8gVb6"
            + "BV8ECr4oFXyQKvmgVfRAq4ECrhQKvqgVfZAq+6BV+ECriQKvygVfpAq40Cr9oFXIgVcqBV/ECr+oFXMgVc6BV/kCroQKv+iJ"
            + "i0AgCqjn4A//////////////4BAApgAABzCaA0Bo0YRoMRpiZMTQYRoGQDJgcwmgNAaNGEaDEaYmTE0GEaBkAyYCaqqjEyaZ"
            + "GIZMRo0ZAGJo0MCBkGmmIyaHMJoDQGjRhGgxGmJkxNBhGgZAMmHQgVdJAq6aBVgIFWCgVYSBV1ECrDQKsRAq6qBVioFXWQKs"
            + "ZAqx0CrroFXYQKshAqyUCrKQKtLtQFXbgKsuAqzICruQFWbAVZ0BVnwFXdgKu9AVaEBV34CrwQFWjAVeGAq8UBVpQFWSgVaa"
            + "BV40CryIFXlQKvMgVedAq9CBV6UCr1IFWogVaqBVrIFXrQKtdAq9iBV7UCrYQKvcgVe9Aq+CBVsoFW0gVbaBVuIFW6gVbyBV"
            + "8UCr5IFXzQKvogVfVAq30CrgQKvsgVfdAq/CBV+UCrhQKv0gVcSBV+0CrjQKuRAq5UCr+IFX9QKuZAq50Cr/IFXQgVf9ETFo"
            + "BAGO6PwB//////////////wCABLgAAHMJoDQGjRhGgxGmJkxNBhGgZAMmBzCaA0Bo0YRoMRpiZMTQYRoGQDJgcwmgNAaNGEa"
            + "DEaYmTE0GEaBkAyYBSqqGTRpkMhkxGjTCZGmJo0MCBpkGQMmnQik6SKTpopOoikwEUmCik6qKTCRSdZFJ10UmGik7CKTERSY"
            + "qKTsopO0ik7aKTGRSY6KTuIpO6ik7yKTIRSZKKTvopMpFJlopMxFJ4EUnhRSZqKTxIpPGikzkUnkRSZ6KTQRSaulRSeWik06"
            + "KTUopPNRSeeik1aKTCRSehFJrIpNdFJsIpPSik2UUnqRSetFJtIpPYik9qKT3IpNtFJuIpN1FJvIpN9FJwIpPeik+CKT4opP"
            + "kik+aKThRSfRFJxIpONFJ9UUn2RSfdFJ+EUnIik5UUn5RSfpFJ+0Un8RSf1FJzIpOdFJ/kUnQik/6ImLQEA=";

    private static byte[] alzBzipSingleBlockPlain() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append("ALZ bzip2 single block payload. ");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] alzBzipMultiBlockPlain() {
        byte[] pattern = new byte[87];
        for (int i = 0; i < pattern.length; i++) pattern[i] = (byte) (33 + i);
        byte[] data = new byte[260000];
        for (int i = 0; i < data.length; i++) data[i] = pattern[i % pattern.length];
        return data;
    }

    @Test
    public void extractSingleEntry_alzBzip2SingleBlock_decodesVariantStream() throws Exception {
        byte[] plain = alzBzipSingleBlockPlain();
        byte[] stored = java.util.Base64.getDecoder().decode(ALZ_BZ_SINGLE_BLOCK_B64);
        File archive = buildAlzArchiveWithStoredPayload("page-bz.txt", plain, 1, stored);
        File out = tempFolder.newFile("alz-bzip2-single.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page-bz.txt", out, null));

        assertTrue(java.util.Arrays.equals(plain, Files.readAllBytes(out.toPath())));
    }

    @Test
    public void extractSingleEntry_alzBzip2MultiBlock_decodesAllBlocks() throws Exception {
        byte[] plain = alzBzipMultiBlockPlain();
        byte[] stored = java.util.Base64.getDecoder().decode(ALZ_BZ_MULTI_BLOCK_B64);
        File archive = buildAlzArchiveWithStoredPayload("multi.bin", plain, 1, stored);
        File out = tempFolder.newFile("alz-bzip2-multi.bin");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "multi.bin", out, null));

        assertTrue(java.util.Arrays.equals(plain, Files.readAllBytes(out.toPath())));
    }

    @Test
    public void extractSingleEntry_alzBzip2CorruptedStream_failsCleanly() throws Exception {
        byte[] plain = alzBzipSingleBlockPlain();
        byte[] stored = java.util.Base64.getDecoder().decode(ALZ_BZ_SINGLE_BLOCK_B64);
        stored[stored.length / 2] ^= 0x5a; // corrupt mid-stream
        File archive = buildAlzArchiveWithStoredPayload("page-bz.txt", plain, 1, stored);
        File out = new File(tempFolder.getRoot(), "alz-bzip2-corrupt.txt");

        // ArchiveSupport.extractSingleEntry reports failure via its return
        // value rather than an exception; if the corrupted stream happens to
        // decode structurally, the container CRC check must still reject it.
        assertFalse(ArchiveSupport.extractSingleEntry(archive, "page-bz.txt", out, null));
    }

    /**
     * Plain bzip2 flavor of ALZ method 1 (some real ALZip files carry a
     * standard {@code BZh} stream; see docs/ALZ_FORMAT_NOTES.md). The payload
     * is {@code "ALZ standard bzip2 payload. "} repeated 40 times, compressed
     * with standard bzip2 level 9.
     */
    private static final String ALZ_BZ_STANDARD_B64 =
            "QlpoOTFBWSZTWWPOxC0AAIufgEABEAAgBAAQNCXcMCAAkCmTEyDIwKVQj0gaabU0E8CbCYE1E4E7iYE3E4E/CYEwJgTkT2Jy"
            + "JkTImwnQnQm4mwmgmRNRMifxdyRThQkGPOxC0A==";

    @Test
    public void extractSingleEntry_alzBzip2StandardFlavor_stillDecodes() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append("ALZ standard bzip2 payload. ");
        byte[] plain = sb.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] stored = java.util.Base64.getDecoder().decode(ALZ_BZ_STANDARD_B64);
        File archive = buildAlzArchiveWithStoredPayload("page-std.txt", plain, 1, stored);
        File out = tempFolder.newFile("alz-bzip2-standard.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page-std.txt", out, null));

        assertTrue(java.util.Arrays.equals(plain, Files.readAllBytes(out.toPath())));
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
    public void alzSplit_twoSegments_extractsAcrossBoundary() throws Exception {
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) ('a' + (i % 23));
        writeSplitAlzFixture("splitfx", "data.txt", payload);
        File first = new File(tempFolder.getRoot(), "splitfx.alz");
        File out = tempFolder.newFile("split-out.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(first, "data.txt", out, null));

        byte[] extracted = Files.readAllBytes(out.toPath());
        assertEquals(payload.length, extracted.length);
        assertTrue(java.util.Arrays.equals(payload, extracted));
    }

    @Test
    public void alzSplit_openedFromContinuationSegment_resolvesFirstPart() throws Exception {
        byte[] payload = new byte[2048];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) ('A' + (i % 19));
        writeSplitAlzFixture("splitcont", "data.txt", payload);
        File continuation = new File(tempFolder.getRoot(), "splitcont.a00");
        File out = tempFolder.newFile("split-cont-out.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(continuation, "data.txt", out, null));

        assertTrue(java.util.Arrays.equals(payload, Files.readAllBytes(out.toPath())));
    }

    @Test
    public void alzSplit_missingSegment_failsWithoutPartialOutput() throws Exception {
        byte[] payload = new byte[2048];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) ('0' + (i % 10));
        writeSplitAlzFixture("splitmiss", "data.txt", payload);
        File first = new File(tempFolder.getRoot(), "splitmiss.alz");
        File a00 = new File(tempFolder.getRoot(), "splitmiss.a00");
        File a01 = new File(tempFolder.getRoot(), "splitmiss.a01");
        assertTrue(a00.renameTo(a01)); // leaves a gap at .a00
        File out = new File(tempFolder.getRoot(), "split-missing-out.txt");

        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                first, "data.txt", out, null);

        assertFalse(result.success);
        assertFalse(out.exists());
    }

    @Test
    public void listEntries_alzDirectoryEntryWithZeroSizeNibble_isTolerated() throws Exception {
        byte[] payload = "hello alz".getBytes(StandardCharsets.UTF_8);
        File archive = buildAlzArchiveWithDirectoryEntry("docs", "docs/readme.txt", payload);

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);

        boolean sawDirectory = false;
        boolean sawFile = false;
        for (ArchiveSupport.EntryInfo entry : entries) {
            if (entry.directory && entry.path.startsWith("docs")) sawDirectory = true;
            if (!entry.directory && entry.path.equals("docs/readme.txt")) sawFile = true;
        }
        assertTrue(sawDirectory);
        assertTrue(sawFile);

        File out = tempFolder.newFile("dir-entry-out.txt");
        assertTrue(ArchiveSupport.extractSingleEntry(archive, "docs/readme.txt", out, null));
        assertEquals("hello alz", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
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
        return buildAlzArchiveWithNameBytes(entryName.getBytes(StandardCharsets.UTF_8), plainPayload, method, encrypted, password);
    }

    /** Builds a one-entry ALZ archive around an already-encoded payload. */
    private File buildAlzArchiveWithStoredPayload(String entryName,
                                                  byte[] plainPayload,
                                                  int method,
                                                  byte[] storedPayload) throws Exception {
        File archive = tempFolder.newFile("fixture-" + System.nanoTime() + ".alz");
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(plainPayload);
        try (FileOutputStream out = new FileOutputStream(archive)) {
            writeIntLE(out, 0x015a4c41);
            writeIntLE(out, 0);
            writeIntLE(out, 0x015a4c42);
            writeShortLE(out, name.length);
            out.write(0x20);
            writeIntLE(out, 0);
            out.write(0x40);
            out.write(0);
            out.write(method);
            out.write(0);
            writeIntLE(out, (int) crc.getValue());
            writeIntLE(out, storedPayload.length);
            writeIntLE(out, plainPayload.length);
            out.write(name);
            out.write(storedPayload);
            writeIntLE(out, 0x025a4c43);
        }
        return archive;
    }

    private File buildAlzArchiveWithNameBytes(byte[] name,
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

    /**
     * Writes a two-segment split fixture with real ALZ split framing: the
     * logical stream (header + one deflate entry) is cut mid-data; the first
     * segment gets a CLZ trailer ending in CLZ\3 (more segments follow), the
     * continuation gets an 8-byte ALZ segment header and a CLZ\2 trailer.
     */
    private void writeSplitAlzFixture(String baseName, String entryName, byte[] plainPayload) throws Exception {
        byte[] stored = rawDeflate(plainPayload);
        CRC32 crc = new CRC32();
        crc.update(plainPayload);
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream logical = new ByteArrayOutputStream();
        writeIntLE(logical, 0x015a4c41);
        writeShortLE(logical, 0x000a); // version
        writeShortLE(logical, 0x0000); // segment id of first part
        writeIntLE(logical, 0x015a4c42);
        writeShortLE(logical, name.length);
        logical.write(0x20);
        writeIntLE(logical, 0);
        logical.write(0x40); // 4-byte sizes, not encrypted
        logical.write(0);
        logical.write(2); // deflate
        logical.write(0);
        writeIntLE(logical, (int) crc.getValue());
        writeIntLE(logical, stored.length);
        writeIntLE(logical, plainPayload.length);
        logical.write(name);
        logical.write(stored);
        byte[] bytes = logical.toByteArray();

        int cut = bytes.length - stored.length / 2; // mid-data
        ByteArrayOutputStream seg1 = new ByteArrayOutputStream();
        seg1.write(bytes, 0, cut);
        writeSegmentTrailer(seg1, 0x035a4c43); // CLZ\3: more segments follow
        ByteArrayOutputStream seg2 = new ByteArrayOutputStream();
        writeIntLE(seg2, 0x015a4c41);
        writeShortLE(seg2, 0x000a); // version
        writeShortLE(seg2, 0x0001); // segment id
        seg2.write(bytes, cut, bytes.length - cut);
        writeSegmentTrailer(seg2, 0x025a4c43); // CLZ\2: final segment

        writeBytes(new File(tempFolder.getRoot(), baseName + ".alz"), seg1.toByteArray());
        writeBytes(new File(tempFolder.getRoot(), baseName + ".a00"), seg2.toByteArray());
    }

    private void writeSegmentTrailer(ByteArrayOutputStream out, int endSignature) throws Exception {
        writeIntLE(out, 0x015a4c43); // CLZ\1
        writeIntLE(out, 0);
        writeIntLE(out, 0);
        writeIntLE(out, endSignature);
    }

    /** One zero-size-nibble directory entry (no method/CRC/size fields) followed by a stored file. */
    private File buildAlzArchiveWithDirectoryEntry(String dirName, String fileName, byte[] plainPayload) throws Exception {
        CRC32 crc = new CRC32();
        crc.update(plainPayload);
        byte[] dirBytes = dirName.getBytes(StandardCharsets.UTF_8);
        byte[] fileBytes = fileName.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeIntLE(out, 0x015a4c41);
        writeIntLE(out, 0);
        writeIntLE(out, 0x015a4c42);
        writeShortLE(out, dirBytes.length);
        out.write(0x10); // directory attribute
        writeIntLE(out, 0);
        out.write(0x00); // zero size nibble: method/CRC/sizes omitted
        out.write(0);
        out.write(dirBytes);
        writeIntLE(out, 0x015a4c42);
        writeShortLE(out, fileBytes.length);
        out.write(0x20);
        writeIntLE(out, 0);
        out.write(0x40);
        out.write(0);
        out.write(0); // stored
        out.write(0);
        writeIntLE(out, (int) crc.getValue());
        writeIntLE(out, plainPayload.length);
        writeIntLE(out, plainPayload.length);
        out.write(fileBytes);
        out.write(plainPayload);
        writeIntLE(out, 0x025a4c43);

        File archive = tempFolder.newFile("fixture-direntry.alz");
        writeBytes(archive, out.toByteArray());
        return archive;
    }

    private void writeBytes(File target, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
        }
    }

    private void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private void writeShortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
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
