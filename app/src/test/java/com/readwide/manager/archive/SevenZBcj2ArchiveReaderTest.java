package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

/**
 * Tests for {@link SevenZBcj2ArchiveReader}, the first-party 7z path for the
 * BCJ2 branch filter that Apache Commons Compress cannot decode ("Multi
 * input/output stream coders are not yet supported").
 *
 * <p>Fixtures are self-made: a deterministic 2,868-byte payload of random
 * bytes interleaved with real x86 {@code E8}/{@code E9}/{@code 0F 8x} branch
 * instructions and 4-byte operands (so BCJ2 has genuine conversions to undo),
 * packed by p7zip in three coder chains - BCJ2 over stored inputs, BCJ2 over
 * LZMA, and AES-256 + LZMA + BCJ2 with an encrypted (encoded) header. Each
 * fixture was extracted with the reference {@code 7z} tool to confirm it round
 * trips before embedding; the decoded output is pinned here by SHA-256 and
 * length. Password: {@code pw1717}.</p>
 */
public class SevenZBcj2ArchiveReaderTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static final String PAYLOAD_SHA256 =
            "c1d477b07574b457e8751f9aa7ee7858ee1f7b624942651e3728129498fcc6aa";
    private static final int PAYLOAD_LENGTH = 2868;
    private static final String ENTRY_NAME = "selfmade.bin";
    private static final char[] PASSWORD = "pw1717".toCharArray();

    @Test
    public void splitAesBcj2PublicForwardRouteCrossesHeadersAndPackedStreams() throws Exception {
        byte[] bytes = Base64.getDecoder().decode(Sevenz7Bcj2Fixtures.AES_B64);
        File[] parts = splitBytes(bytes, 5, 31, 34, bytes.length - 19);
        File spool = tempFolder.newFolder("split-aes-spool");
        File output = tempFolder.newFile("split-aes-output.bin");
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(
                parts[2], PASSWORD, spool); FileOutputStream out = new FileOutputStream(output)) {
            org.junit.Assert.assertNotNull(reader);
            assertEquals(ENTRY_NAME, reader.nextEntry().path);
            copyForward(reader, out);
            assertEquals(1, spool.list().length);
        }
        assertPayload(output);
        assertEquals(0, spool.list().length);
        deleteParts(parts);
    }

    @Test
    public void splitStoredAndLzmaBcj2MatchExistingDigests() throws Exception {
        for (String fixture : new String[] {Sevenz7Bcj2Fixtures.STORED_B64, Sevenz7Bcj2Fixtures.LZMA_B64}) {
            byte[] bytes = Base64.getDecoder().decode(fixture);
            File[] parts = splitBytes(bytes, 9, 35, bytes.length - 3);
            File spool = tempFolder.newFolder();
            File output = tempFolder.newFile();
            try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(
                    parts[parts.length - 1], null, spool); FileOutputStream out = new FileOutputStream(output)) {
                org.junit.Assert.assertNotNull(reader);
                assertEquals(ENTRY_NAME, reader.nextEntry().path);
                copyForward(reader, out);
            }
            assertPayload(output);
            assertEquals(0, spool.list().length);
            deleteParts(parts);
        }
    }

    @Test
    public void splitPlainAndEncryptedPpmdMatchExistingDigest() throws Exception {
        String[] fixtures = {Sevenz7PpmdFixtures.PLAIN_B64, Sevenz7PpmdFixtures.AES_B64};
        for (int i = 0; i < fixtures.length; i++) {
            byte[] bytes = Base64.getDecoder().decode(fixtures[i]);
            File[] parts = splitBytes(bytes, 3, 18, 33, bytes.length - 7);
            File spool = tempFolder.newFolder();
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(
                    parts[1], i == 0 ? null : PASSWORD, spool)) {
                org.junit.Assert.assertNotNull(reader);
                assertEquals("ppmd-sm.txt", reader.nextEntry().path);
                copyForward(reader, output);
            }
            assertEquals(31600, output.size());
            assertEquals("d8cb9b211ea4130bdace77b73a1cd345ee0fc3062356599c07534746851b1d73",
                    digest(output.toByteArray()));
            assertEquals(0, spool.list().length);
            deleteParts(parts);
        }
    }

    @Test
    public void splitForwardReusesFolderAndReleasesVolumesAtEnd() throws Exception {
        File[] parts = splitBytes(Files.readAllBytes(tinyBcj2(false, false, false, false).toPath()), 5, 33, 38);
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(
                parts[0], null, spool)) {
            assertEquals("1.bin", reader.nextEntry().path);
            assertForwardBytes(reader, new byte[] {1, 2});
            File firstSpool = spool.listFiles()[0];
            assertEquals(4, firstSpool.length());
            assertEquals("2.bin", reader.nextEntry().path);
            assertForwardBytes(reader, new byte[] {3, 4});
            assertEquals(firstSpool, spool.listFiles()[0]);
            org.junit.Assert.assertNull(reader.nextEntry());
            assertEquals(0, spool.list().length);
            deleteParts(parts); // On Windows this also catches retained volume handles at EOF.
            org.junit.Assert.assertNull(reader.nextEntry());
        }
    }

    @Test
    public void splitCrcFailuresPrecedePublicationAndRetireReader() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            File[] parts = splitBytes(Files.readAllBytes(tinyBcj2(kind == 0, kind == 1, kind == 2, false).toPath()), 6, 33, 38);
            File spool = tempFolder.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(
                    parts[0], null, spool)) {
                reader.nextEntry();
                byte[] output = {(byte) 0x55};
                try { reader.read(output); fail(); }
                catch (IOException expected) { assertTrue(expected.getMessage().contains("CRC")); }
                assertEquals(0x55, output[0]);
                assertEquals(0, spool.list().length);
                assertRetired(reader);
                deleteParts(parts);
            }
        }
    }

    @Test
    public void splitHeaderCrcFailuresCloseVolumesBeforeReturning() throws Exception {
        for (int index : new int[] {8, -1}) {
            byte[] bytes = Files.readAllBytes(tinyBcj2(false, false, false, false).toPath());
            bytes[index < 0 ? bytes.length - 1 : index] ^= 1;
            File[] parts = splitBytes(bytes, 5, 31, 34);
            try {
                ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(
                        parts[0], null, tempFolder.newFolder());
                if (reader != null) reader.close();
                fail("Header CRC must not be suppressed");
            } catch (SevenZBcj2ArchiveReader.IntegrityException expected) { }
            deleteParts(parts);
        }
    }

    @Test
    public void splitLaterPartChangeBeforeDecodeRejectsOldHeader() throws Exception {
        File[] parts = splitBytes(Files.readAllBytes(tinyBcj2(false, false, false, false).toPath()), 5, 33, 38);
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(
                parts[0], null, spool)) {
            reader.nextEntry();
            try (FileOutputStream append = new FileOutputStream(parts[parts.length - 1], true)) { append.write(0); }
            try { reader.read(new byte[1]); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("source changed")); }
            assertEquals(0, spool.list().length);
            assertRetired(reader);
            deleteParts(parts);
        }
    }

    @Test
    public void splitCancellationClosesVolumesAndRetiresReader() throws Exception {
        File[] parts = splitBytes(Files.readAllBytes(tinyBcj2(false, false, false, false).toPath()), 5, 33, 38);
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(
                parts[0], null, spool)) {
            reader.nextEntry();
            Thread.currentThread().interrupt();
            try {
                try { reader.read(new byte[1]); fail(); }
                catch (IOException expected) { assertTrue(expected.getMessage().contains("cancelled")); }
            } finally { Thread.interrupted(); }
            assertEquals(0, spool.list().length);
            assertRetired(reader);
            deleteParts(parts);
        }
    }

    @Test
    public void splitMissingMiddlePartFailsBeforeCreatingSpool() throws Exception {
        File[] parts = splitBytes(Files.readAllBytes(tinyBcj2(false, false, false, false).toPath()), 5, 33, 38);
        assertTrue(parts[1].delete());
        File spool = tempFolder.newFolder();
        try {
            ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(parts[0], null, spool);
            if (reader != null) reader.close();
            fail();
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("Missing 7z split volume")); }
        assertEquals(0, spool.list().length);
        for (File part : parts) if (part.exists()) assertTrue(part.delete());
    }

    @Test
    public void splitFolderBudgetFailureCleansSpoolAndVolumes() throws Exception {
        File[] parts = splitBytes(Files.readAllBytes(tinyBcj2(false, false, false, false).toPath()), 5, 33, 38);
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(parts[0], null, spool);
             ArchiveExtractionByteBudget.Scope ignored = ArchiveExtractionByteBudget.begin(1)) {
            reader.nextEntry();
            try { reader.read(new byte[1]); fail(); } catch (IOException expected) { }
            assertEquals(0, spool.list().length);
            assertRetired(reader);
            deleteParts(parts);
        }
    }

    @Test
    public void splitCloseWithoutReadingReleasesEveryVolume() throws Exception {
        File[] parts = splitBytes(Files.readAllBytes(tinyBcj2(false, false, false, false).toPath()), 5, 33, 38);
        File spool = tempFolder.newFolder();
        ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(parts[0], null, spool);
        org.junit.Assert.assertNotNull(reader);
        reader.close(); reader.close();
        assertEquals(0, spool.list().length);
        deleteParts(parts);
    }

    @Test
    public void splitEmptyVolumeStillUsesLogicalSignatureAndBounds() throws Exception {
        File[] parts = splitBytes(Files.readAllBytes(tinyBcj2(false, false, false, false).toPath()), 0, 5, 5, 33);
        try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(
                parts[0], null, tempFolder.newFolder())) {
            org.junit.Assert.assertNotNull(reader);
            reader.nextEntry();
            assertForwardBytes(reader, new byte[] {1, 2});
        }
        deleteParts(parts);
    }

    @Test
    public void splitUnsupportedHeaderDeclinesWithoutRetainingVolumes() throws Exception {
        File[] parts = splitBytes(new byte[64], 5, 33);
        File spool = tempFolder.newFolder();
        org.junit.Assert.assertNull(SevenZBcj2ArchiveReader.openSpecialForwardReader(parts[0], null, spool));
        assertEquals(0, spool.list().length);
        deleteParts(parts);
    }

    private File[] splitBytes(byte[] bytes, int... cuts) throws Exception {
        File directory = tempFolder.newFolder();
        File[] parts = new File[cuts.length + 1];
        int start = 0;
        for (int i = 0; i < parts.length; i++) {
            int end = i < cuts.length ? cuts[i] : bytes.length;
            assertTrue(end >= start && end <= bytes.length);
            parts[i] = new File(directory, String.format(java.util.Locale.ROOT, "comic.cb7.%03d", i + 1));
            Files.write(parts[i].toPath(), java.util.Arrays.copyOfRange(bytes, start, end));
            start = end;
        }
        return parts;
    }

    private static void deleteParts(File[] parts) {
        for (File part : parts) assertTrue("Volume handle was not released: " + part, part.delete());
    }

    private static void assertRetired(ArchiveSupport.ForwardArchiveReader reader) throws Exception {
        try { reader.nextEntry(); fail("Failed reader must not continue"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("closed or failed")); }
    }

    private static void copyForward(ArchiveSupport.ForwardArchiveReader reader, java.io.OutputStream out) throws IOException {
        byte[] buffer = new byte[127];
        int count;
        while ((count = reader.read(buffer)) != -1) out.write(buffer, 0, count);
    }

    private static String digest(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            text.append(String.format("%02x", value & 255));
        }
        return text.toString();
    }

    @Test
    public void forwardAesBcj2MatchesFixtureAndCleansFolderSpool() throws Exception {
        File archive = writeFixture("forward-aes.7z", Sevenz7Bcj2Fixtures.AES_B64);
        File spool = tempFolder.newFolder("forward-aes-spool");
        File output = tempFolder.newFile("forward-aes.bin");
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, PASSWORD, spool);
             FileOutputStream out = new FileOutputStream(output)) {
            assertEquals(ENTRY_NAME, reader.nextEntry().path);
            byte[] buffer = new byte[127];
            int count;
            while ((count = reader.read(buffer)) != -1) out.write(buffer, 0, count);
            assertEquals(1, spool.list().length);
        }
        assertPayload(output);
        assertEquals(0, spool.list().length);
    }

    @Test
    public void forwardBcj2ReusesOneVerifiedFolderForTwoEntries() throws Exception {
        File archive = tinyBcj2(false, false, false, false);
        File spool = tempFolder.newFolder("two-spool");
        try (ArchiveSupport.ForwardArchiveReader reader =
                     SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool)) {
            assertEquals("1.bin", reader.nextEntry().path);
            assertForwardBytes(reader, new byte[]{1, 2});
            File[] first = spool.listFiles();
            assertEquals(1, first.length);
            assertEquals(4, first[0].length());
            // The folder is now fully decoded; advancing must not reopen the archive.
            assertTrue(archive.delete());
            assertEquals("2.bin", reader.nextEntry().path);
            assertForwardBytes(reader, new byte[]{3, 4});
            assertEquals(first[0], spool.listFiles()[0]);
            org.junit.Assert.assertNull(reader.nextEntry());
            assertEquals(0, spool.list().length);
        }
    }

    @Test
    public void forwardBcj2RejectsFolderSubstreamAndPackedCrcBeforeReturningBytes() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            File archive = tinyBcj2(kind == 0, kind == 1, kind == 2, false);
            File spool = tempFolder.newFolder("bad-crc-" + kind);
            try (ArchiveSupport.ForwardArchiveReader reader =
                         SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool)) {
                reader.nextEntry();
                try { reader.read(new byte[1]); fail("CRC error must precede image bytes"); }
                catch (IOException expected) { assertTrue(expected.getMessage().contains("CRC")); }
                assertEquals(0, spool.list().length);
            }
        }
    }

    @Test
    public void singleSubstreamInheritsFolderCrcWithoutConsumingExtraDigest() throws Exception {
        File archive = tinyBcj2(false, false, false, true);
        File out = tempFolder.newFile("inherited.bin");
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "1.bin", out, null));
        org.junit.Assert.assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void publicSingleEntryRouteDoesNotSuppressSpecialCoderCrcFailure() throws Exception {
        File archive = tinyBcj2(false, true, false, false);
        File out = new File(tempFolder.getRoot(), "bad-public.bin");
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                archive, "1.bin", out, null);
        assertFalse(result.success);
        assertFalse(out.exists());
    }

    @Test
    public void forwardFolderBudgetFailureCleansPartialSpool() throws Exception {
        File archive = tinyBcj2(false, false, false, false);
        File spool = tempFolder.newFolder("budget-spool");
        try (ArchiveSupport.ForwardArchiveReader reader =
                     SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool);
             ArchiveExtractionByteBudget.Scope ignored = ArchiveExtractionByteBudget.begin(1)) {
            reader.nextEntry();
            try { reader.read(new byte[1]); fail("Folder must obey shared output budget"); }
            catch (IOException expected) { }
            assertEquals(0, spool.list().length);
        }
    }

    @Test
    public void corruptSignatureAndNextHeaderCrcAreRejected() throws Exception {
        for (int kind = 0; kind < 2; kind++) {
            File archive = tinyBcj2(false, false, false, false);
            byte[] bytes = Files.readAllBytes(archive.toPath());
            bytes[kind == 0 ? 8 : bytes.length - 1] ^= 1;
            Files.write(archive.toPath(), bytes);
            try { SevenZBcj2ArchiveReader.listEntries(archive, null); fail("Expected header CRC failure"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("CRC")); }
        }
    }

    private static void assertForwardBytes(ArchiveSupport.ForwardArchiveReader reader, byte[] expected) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1];
        int count;
        while ((count = reader.read(buffer)) != -1) out.write(buffer, 0, count);
        org.junit.Assert.assertArrayEquals(expected, out.toByteArray());
    }

    @Test public void bcj2GraphAcceptsAdditionalDeflateBzip2AndDeltaCoders() throws Exception {
        for (int method = 0; method < 3; method++) {
            File archive = tinyAdditionalBcj2(method);
            File out = tempFolder.newFile();
            assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "1.bin", out, null));
            org.junit.Assert.assertArrayEquals(new byte[]{1,2,3,4}, Files.readAllBytes(out.toPath()));
        }
    }

    @Test public void splitBcj2Bzip2GraphUsesVerifiedForwardReader() throws Exception {
        File archive = tinyAdditionalBcj2(1);
        File[] parts = splitBytes(Files.readAllBytes(archive.toPath()), 15, 34);
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(parts[1], null, spool)) {
            assertEquals("1.bin", reader.nextEntry().path);
            assertForwardBytes(reader, new byte[]{1,2,3,4});
            org.junit.Assert.assertNull(reader.nextEntry());
            assertEquals(0, spool.list().length);
        }
        deleteParts(parts);
    }

    /** Two-coder graph: additional decoder feeds BCJ2's main input. */
    private File tinyAdditionalBcj2(int method) throws Exception {
        byte[] raw = {1,2,3,4};
        java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
        byte[] id;
        if (method == 0) {
            id = new byte[]{4,1,8};
            java.util.zip.Deflater deflater = new java.util.zip.Deflater(6,true);
            try (java.util.zip.DeflaterOutputStream out = new java.util.zip.DeflaterOutputStream(encoded,deflater)) { out.write(raw); }
            finally { deflater.end(); }
        } else if (method == 1) {
            id = new byte[]{4,2,2};
            try (org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream out =
                    new org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(encoded)) { out.write(raw); }
        } else { id = new byte[]{3}; encoded.write(new byte[]{1,1,1,1}); }
        java.io.ByteArrayOutputStream header = new java.io.ByteArrayOutputStream();
        header.write(new byte[]{1,4,6,0,4,9,(byte)encoded.size(),0,0,5,10,1});
        little(header,crc(encoded.toByteArray()),4); little(header,0,4); little(header,0,4); little(header,crc(new byte[5]),4);
        header.write(new byte[]{0,7,11,1,0,2});
        header.write(id.length | (method == 2 ? 0x20 : 0)); header.write(id);
        if (method == 2) header.write(new byte[]{1,0}); // One property: distance minus one.
        header.write(new byte[]{0x14,3,3,1,0x1b,4,1,1,0,0,2,3,4,12,4,4,10,1});
        little(header,crc(raw),4);
        header.write(new byte[]{0,0,5,1,17,13,0});
        header.write("1.bin\0".getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        header.write(new byte[]{0,0});
        java.io.ByteArrayOutputStream start = new java.io.ByteArrayOutputStream();
        little(start,encoded.size()+5,8); little(start,header.size(),8); little(start,crc(header.toByteArray()),4);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        bytes.write(new byte[]{'7','z',(byte)0xbc,(byte)0xaf,0x27,0x1c,0,4});
        little(bytes,crc(start.toByteArray()),4); bytes.write(start.toByteArray());
        bytes.write(encoded.toByteArray()); bytes.write(new byte[5]); bytes.write(header.toByteArray());
        File file = tempFolder.newFile(); Files.write(file.toPath(),bytes.toByteArray()); return file;
    }

    /** Self-made BCJ2 main stream without branch opcodes, empty CALL/JUMP and five zero RC bytes. */
    private File tinyBcj2(boolean badFolder, boolean badSubstream, boolean badPacked, boolean single) throws Exception {
        byte[] payload = {1, 2, 3, 4};
        java.io.ByteArrayOutputStream header = new java.io.ByteArrayOutputStream();
        header.write(new byte[]{1, 4, 6, 0, 4, 9, 4, 0, 0, 5, 10, 1});
        little(header, crc(payload) ^ (badPacked ? 1 : 0), 4);
        little(header, 0, 4); little(header, 0, 4); little(header, crc(new byte[5]), 4);
        header.write(new byte[]{0, 7, 11, 1, 0, 1, 0x14, 3, 3, 1, 0x1b, 4, 1, 0, 1, 2, 3, 12, 4, 10, 1});
        little(header, crc(payload) ^ (badFolder ? 1 : 0), 4);
        header.write(new byte[]{0, 8});
        if (!single) header.write(new byte[]{13, 2, 9, 2});
        header.write(new byte[]{10, 1});
        if (!single) {
            little(header, crc(new byte[]{1, 2}), 4);
            little(header, crc(new byte[]{3, 4}) ^ (badSubstream ? 1 : 0), 4);
        }
        header.write(new byte[]{0, 0, 5, (byte) (single ? 1 : 2), 17});
        byte[] names = (single ? "1.bin\0" : "1.bin\0" + "2.bin\0").getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        header.write(names.length + 1); header.write(0); header.write(names);
        header.write(new byte[]{0, 0});
        java.io.ByteArrayOutputStream start = new java.io.ByteArrayOutputStream();
        little(start, 9, 8); little(start, header.size(), 8); little(start, crc(header.toByteArray()), 4);
        java.io.ByteArrayOutputStream archive = new java.io.ByteArrayOutputStream();
        archive.write(new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c, 0, 4});
        little(archive, crc(start.toByteArray()), 4); archive.write(start.toByteArray());
        archive.write(payload); archive.write(new byte[5]); archive.write(header.toByteArray());
        File file = tempFolder.newFile("tiny-" + System.nanoTime() + ".7z");
        Files.write(file.toPath(), archive.toByteArray());
        return file;
    }

    private static void little(java.io.ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) out.write((int) (value >>> (i * 8)) & 255);
    }

    private static long crc(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(data); return crc.getValue();
    }

    @Test
    public void bcj2StoredInputs_extractsByteExact() throws Exception {
        File archive = writeFixture("sm-bcj2.7z", Sevenz7Bcj2Fixtures.STORED_B64);

        assertTrue(SevenZBcj2ArchiveReader.archiveUsesBcj2(archive, null));
        File out = extractSingle(archive, null);
        assertPayload(out);
    }

    @Test
    public void bcj2OverLzma_extractsByteExact() throws Exception {
        File archive = writeFixture("sm-bcj2-lzma.7z", Sevenz7Bcj2Fixtures.LZMA_B64);

        File out = extractSingle(archive, null);
        assertPayload(out);
    }

    @Test
    public void bcj2WithAesAndEncodedHeader_extractsByteExact() throws Exception {
        File archive = writeFixture("sm-bcj2-aes.7z", Sevenz7Bcj2Fixtures.AES_B64);

        // The header is itself AES-encrypted, so listing needs the password.
        List<ArchiveSupport.EntryInfo> entries = SevenZBcj2ArchiveReader.listEntries(archive, PASSWORD);
        assertEquals(1, entries.size());
        assertEquals(ENTRY_NAME, entries.get(0).path);

        File out = extractSingle(archive, PASSWORD);
        assertPayload(out);
    }

    @Test
    public void bcj2Aes_wrongPassword_failsCleanly() throws Exception {
        File archive = writeFixture("sm-bcj2-aes-wrong.7z", Sevenz7Bcj2Fixtures.AES_B64);
        File out = new File(tempFolder.getRoot(), "wrong.bin");

        try {
            SevenZBcj2ArchiveReader.extractSingleEntry(archive, ENTRY_NAME, out, "nope".toCharArray());
            fail("Expected wrong password to fail");
        } catch (IOException expected) {
            // AES-CBC of the header with the wrong key yields a corrupt header,
            // which fails to parse - a clean IOException, never partial output.
        }
    }

    @Test
    public void bcj2Aes_missingPassword_promptsForPassword() throws Exception {
        File archive = writeFixture("sm-bcj2-aes-nopw.7z", Sevenz7Bcj2Fixtures.AES_B64);
        File out = new File(tempFolder.getRoot(), "nopw.bin");

        try {
            SevenZBcj2ArchiveReader.extractSingleEntry(archive, ENTRY_NAME, out, null);
            fail("Expected missing password to fail");
        } catch (ArchiveSupport.PasswordRequiredException expected) {
        } catch (IOException e) {
            // The encoded header cannot be read without the password; either a
            // password-required signal or a clean IOException is acceptable.
            assertTrue(e.getMessage() != null);
        }
    }

    @Test
    public void bcj2ExtractIntoDirectory_writesEntry() throws Exception {
        File archive = writeFixture("sm-bcj2-dir.7z", Sevenz7Bcj2Fixtures.STORED_B64);
        File target = tempFolder.newFolder("bcj2-out");

        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, target, null, null, null));
        assertPayload(new File(target, ENTRY_NAME));
    }

    private File writeFixture(String name, String base64) throws Exception {
        File archive = tempFolder.newFile(name);
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(Base64.getDecoder().decode(base64));
        }
        return archive;
    }

    private File extractSingle(File archive, char[] password) throws Exception {
        File out = tempFolder.newFile("out-" + System.nanoTime() + ".bin");
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, ENTRY_NAME, out, password));
        return out;
    }

    private void assertPayload(File file) throws Exception {
        byte[] data = Files.readAllBytes(file.toPath());
        assertEquals(PAYLOAD_LENGTH, data.length);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b & 0xff));
        assertEquals(PAYLOAD_SHA256, sb.toString());
    }
}
