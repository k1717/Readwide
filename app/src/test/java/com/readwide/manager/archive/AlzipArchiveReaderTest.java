package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
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
    @Test public void compressedPasswordCollisionsResolveAcrossDeflateAndBzipFlavors() throws Exception {
        byte[] plain = alzBzipSingleBlockPlain();
        ByteArrayOutputStream bzip = new ByteArrayOutputStream();
        try (java.io.OutputStream out = new org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(bzip)) {
            out.write(plain);
        }
        byte[][] packed = {rawDeflate(plain), bzip.toByteArray(),
                java.util.Arrays.copyOfRange(bzip.toByteArray(), 2, bzip.size()),
                java.util.Base64.getDecoder().decode(ALZ_BZ_SINGLE_BLOCK_B64)};
        for (String encoding : new String[]{"UTF-8", "MS949"}) {
            for (int flavor = 0; flavor < packed.length; flavor++) {
                File archive = ambiguousEncodedAlz(plain, flavor == 0 ? 2 : 1, packed[flavor], encoding);
                char[] password = "비밀번호".toCharArray();
                File output = tempFolder.newFile();
                assertTrue(ArchiveSupport.extractSingleEntry(archive, "data.bin", output, password));
                assertArrayEquals(plain, Files.readAllBytes(output.toPath()));
                File directory = tempFolder.newFolder();
                assertTrue(AlzipArchiveReader.extractArchiveIntoDirectory(archive, directory, password));
                assertArrayEquals(plain, Files.readAllBytes(new File(directory, "data.bin").toPath()));
            }
        }
    }

    @Test public void compressedPasswordProbeSupportsSplitHeaderAndPayload() throws Exception {
        byte[] plain = new byte[70003];
        new java.util.Random(206).nextBytes(plain);
        byte[] packed = rawDeflate(plain);
        File archive = ambiguousEncodedAlz(plain, 2, packed, "UTF-8");
        byte[] bytes = Files.readAllBytes(archive.toPath());
        int payloadOffset = bytes.length - packed.length - 4;
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        framed.write(bytes, 0, bytes.length - 4); writeSegmentTrailer(framed, 0x025a4c43);
        for (int cut : new int[]{payloadOffset - 6, payloadOffset + packed.length / 2}) {
            File selected = splitWithFinalTail(framed.toByteArray(), cut);
            File output = tempFolder.newFile();
            assertTrue(AlzipArchiveReader.extractSingleEntry(selected, "data.bin", output, "비밀번호".toCharArray()));
            assertArrayEquals(plain, Files.readAllBytes(output.toPath()));
        }
    }

    @Test public void rejectedCompressedPasswordCandidatesPreserveOutputs() throws Exception {
        byte[] plain = "compressed candidate must match size and CRC".getBytes(StandardCharsets.UTF_8);
        byte[] wrong = "different valid decoded content".getBytes(StandardCharsets.UTF_8);
        File archive = ambiguousEncodedAlz(plain, 2, rawDeflate(wrong), "MS949");
        char[] password = "비밀번호".toCharArray();
        File output = tempFolder.newFile(); Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> AlzipArchiveReader.extractSingleEntry(archive, "data.bin", output, password));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
        File directory = tempFolder.newFolder(), existing = new File(directory, "data.bin");
        Files.write(existing.toPath(), new byte[]{43});
        assertThrows(IOException.class, () -> AlzipArchiveReader.extractArchiveIntoDirectory(archive, directory, password));
        assertArrayEquals(new byte[]{43}, Files.readAllBytes(existing.toPath()));
        assertEquals(1, directory.list().length);
    }

    @Test public void storedPasswordHeaderCollisionsResolveForSelectedAndBulkReads() throws Exception {
        byte[] plain = new byte[150003];
        new java.util.Random(203).nextBytes(plain);
        for (String encoding : new String[]{"UTF-8", "MS949"}) {
            File archive = ambiguousStoredAlz(plain, encoding);
            char[] password = "비밀번호".toCharArray();
            File output = tempFolder.newFile();
            Files.write(output.toPath(), new byte[]{42});
            assertTrue(ArchiveSupport.extractSingleEntry(archive, "data.bin", output, password));
            assertArrayEquals(plain, Files.readAllBytes(output.toPath()));
            File directory = tempFolder.newFolder();
            com.readwide.manager.util.FileOperationProgress progress =
                    new com.readwide.manager.util.FileOperationProgress("test", null);
            long[] lastProgress = {0};
            progress.setListener(snapshot -> lastProgress[0] = snapshot.doneBytes);
            assertTrue(AlzipArchiveReader.extractArchiveIntoDirectory(archive, directory, password, progress));
            assertArrayEquals(plain, Files.readAllBytes(new File(directory, "data.bin").toPath()));
            assertEquals(plain.length, lastProgress[0]);
        }
    }

    @Test public void storedPasswordProbeTraversesSplitHeadersAndPayloads() throws Exception {
        byte[] plain = new byte[4097];
        new java.util.Random(204).nextBytes(plain);
        File archive = ambiguousStoredAlz(plain, "MS949");
        byte[] bytes = Files.readAllBytes(archive.toPath());
        int payloadOffset = bytes.length - plain.length - 4;
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        framed.write(bytes, 0, bytes.length - 4);
        writeSegmentTrailer(framed, 0x025a4c43);
        bytes = framed.toByteArray();
        for (int cut : new int[]{payloadOffset - 6, payloadOffset + 2048}) {
            File selected = splitWithFinalTail(bytes, cut);
            File output = tempFolder.newFile();
            assertTrue(AlzipArchiveReader.extractSingleEntry(selected, "data.bin", output, "비밀번호".toCharArray()));
            assertArrayEquals(plain, Files.readAllBytes(output.toPath()));
        }
    }

    @Test public void damagedAmbiguousStoredPasswordPreservesExistingOutputs() throws Exception {
        byte[] plain = new byte[4097];
        new java.util.Random(205).nextBytes(plain);
        File archive = ambiguousStoredAlz(plain, "UTF-8");
        byte[] bytes = Files.readAllBytes(archive.toPath());
        bytes[bytes.length - 5] ^= 1;
        Files.write(archive.toPath(), bytes);
        char[] password = "비밀번호".toCharArray();
        File output = tempFolder.newFile();
        Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> AlzipArchiveReader.extractSingleEntry(archive, "data.bin", output, password));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
        File directory = tempFolder.newFolder(), existing = new File(directory, "data.bin");
        Files.write(existing.toPath(), new byte[]{43});
        assertThrows(IOException.class, () -> AlzipArchiveReader.extractArchiveIntoDirectory(archive, directory, password));
        assertArrayEquals(new byte[]{43}, Files.readAllBytes(existing.toPath()));
        assertEquals(1, directory.list().length);
    }

    private File ambiguousStoredAlz(byte[] plain, String encoding) throws Exception {
        return ambiguousEncodedAlz(plain, 0, plain, encoding);
    }

    private File ambiguousEncodedAlz(byte[] plain, int method, byte[] packed, String encoding) throws Exception {
        File archive = buildAlzArchiveWithStoredPayload("data.bin", plain, method, packed);
        CRC32 crc = new CRC32(); crc.update(plain);
        byte[][] encrypted = AlzipZipCryptoTest.collidingPasswordFixture(packed, encoding, (int)(crc.getValue() >>> 24));
        byte[] bytes = Files.readAllBytes(archive.toPath());
        int payloadOffset = bytes.length - packed.length - 4;
        bytes[19] |= 1; // Encryption bit of the local descriptor.
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(bytes, 0, payloadOffset);
        result.write(encrypted[0]); result.write(encrypted[1]);
        result.write(bytes, bytes.length - 4, 4);
        Files.write(archive.toPath(), result.toByteArray());
        return archive;
    }

    @Test public void alzTimestampPreservesLocalDosTime() throws Exception {
        File archive = alzWithDosTime(2024, 2, 29, 14, 35, 58);
        java.util.Calendar expected = new java.util.GregorianCalendar();
        expected.clear(); expected.set(2024, 1, 29, 14, 35, 58);
        assertEquals(expected.getTimeInMillis(), AlzipArchiveReader.listEntries(archive, null).get(0).timeMillis);
    }

    @Test public void alzInvalidDosDatesRemainUnknown() throws Exception {
        for (int[] date : new int[][]{{2023, 2, 29, 0, 0, 0}, {2024, 0, 1, 0, 0, 0},
                {2024, 1, 0, 0, 0, 0}, {2024, 1, 1, 24, 0, 0},
                {2024, 1, 1, 0, 60, 0}, {2024, 1, 1, 0, 0, 62}}) {
            File archive = alzWithDosTime(date[0], date[1], date[2], date[3], date[4], date[5]);
            assertEquals(0L, AlzipArchiveReader.listEntries(archive, null).get(0).timeMillis);
        }
    }

    @Test public void malformedAlzDoesNotInventPasswordRequirement() throws Exception {
        File archive = tempFolder.newFile("malformed.alz");
        Files.write(archive.toPath(), new byte[]{'A', 'L', 'Z', 1});
        assertFalse(AlzipArchiveReader.requiresPasswordForExtraction(archive));
        assertThrows(IOException.class, () -> AlzipArchiveReader.listEntries(archive, null));
    }

    private File alzWithDosTime(int year, int month, int day, int hour, int minute, int second) throws Exception {
        File archive = buildAlzArchive("data.bin", new byte[]{1}, 0, false, null);
        long packed = ((long) (year - 1980) << 25) | ((long) month << 21) | ((long) day << 16)
                | (hour << 11) | (minute << 5) | (second / 2);
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(archive, "rw")) {
            file.seek(15);
            for (int i = 0; i < 4; i++) file.write((int) (packed >>> (8 * i)) & 255);
        }
        return archive;
    }

    @Test public void canonicalEmptyAlzListsAndExtractsSuccessfully() throws Exception {
        for (boolean fullTail : new boolean[]{false, true}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            writeIntLE(bytes, 0x015a4c41); writeIntLE(bytes, 0);
            if (fullTail) writeSegmentTrailer(bytes, 0x025a4c43);
            else writeIntLE(bytes, 0x025a4c43);
            File archive = tempFolder.newFile(); Files.write(archive.toPath(), bytes.toByteArray());
            assertTrue(AlzipArchiveReader.listEntries(archive, null).isEmpty());
            assertFalse(AlzipArchiveReader.requiresPasswordForExtraction(archive));
            File directory = tempFolder.newFolder(), existing = new File(directory, "keep");
            Files.write(existing.toPath(), new byte[]{42});
            assertTrue(AlzipArchiveReader.extractArchiveIntoDirectory(archive, directory, null));
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
            assertFalse(AlzipArchiveReader.extractSingleEntry(archive, "missing", existing, null));
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
            if (fullTail) {
                File selected = splitWithFinalTail(bytes.toByteArray(), 8);
                assertTrue(AlzipArchiveReader.listEntries(selected, null).isEmpty());
                assertTrue(AlzipArchiveReader.extractArchiveIntoDirectory(selected, tempFolder.newFolder(), null));
            }
        }
    }

    @Test public void bareAlzHeaderIsNotAnEmptyArchive() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeIntLE(bytes, 0x015a4c41); writeIntLE(bytes, 0);
        File archive = tempFolder.newFile(); Files.write(archive.toPath(), bytes.toByteArray());
        assertMalformedIndexPreservesOutputs(archive);
    }

    @Test public void emptyCommentArchiveWorksThroughPublicAlzRouting() throws Exception {
        File archive = alzWithComments(null, commentRecords());
        assertTrue(ArchiveSupport.listEntries(archive, null).isEmpty());
        File destination = new File(tempFolder.newFolder(), "empty-output");
        assertTrue(ArchiveSupport.extractArchive(archive, destination, true, null));
        assertTrue(destination.isDirectory());
        assertEquals(0, destination.list().length);
    }

    @Test public void alzCommentsPreserveEncryptedDeflateAndSelectedReads() throws Exception {
        byte[] plain = "comment-bearing payload".getBytes(StandardCharsets.UTF_8);
        for (boolean encrypted : new boolean[]{false, true}) {
            char[] password = encrypted ? "pw".toCharArray() : null;
            File archive = alzWithComments(buildAlzArchive("data.bin", plain, 2, encrypted, password), commentRecords());
            assertEquals(1, AlzipArchiveReader.listEntries(archive, password).size());
            File output = tempFolder.newFile();
            assertTrue(AlzipArchiveReader.extractSingleEntry(archive, "data.bin", output, password));
            assertArrayEquals(plain, Files.readAllBytes(output.toPath()));
            File directory = tempFolder.newFolder();
            assertTrue(AlzipArchiveReader.extractArchiveIntoDirectory(archive, directory, password));
            assertArrayEquals(plain, Files.readAllBytes(new File(directory, "data.bin").toPath()));
        }
    }

    @Test public void splitAlzCommentsAndEmptyArchiveRetainFinalTail() throws Exception {
        byte[] plain = "payload across volumes".getBytes(StandardCharsets.UTF_8);
        for (boolean empty : new boolean[]{false, true}) {
            File archive = alzWithComments(empty ? null : buildAlzArchive("data.bin", plain, 2, true, "pw".toCharArray()), commentRecords());
            byte[] bytes = Files.readAllBytes(archive.toPath());
            for (int cut : new int[]{8, bytes.length - 21, bytes.length - 17}) {
                File selected = splitWithFinalTail(bytes, cut);
                assertEquals(empty ? 0 : 1, AlzipArchiveReader.listEntries(selected, null).size());
                File directory = tempFolder.newFolder();
                assertTrue(AlzipArchiveReader.extractArchiveIntoDirectory(selected, directory, "pw".toCharArray()));
                if (empty) assertEquals(0, directory.list().length);
                else {
                    assertArrayEquals(plain, Files.readAllBytes(new File(directory, "data.bin").toPath()));
                    File output = tempFolder.newFile();
                    assertTrue(AlzipArchiveReader.extractSingleEntry(selected, "data.bin", output, "pw".toCharArray()));
                    assertArrayEquals(plain, Files.readAllBytes(output.toPath()));
                }
            }
        }
    }

    @Test public void alzUnknownRecordCannotPublishPartialIndexOrOutput() throws Exception {
        File archive = buildAlzArchive("data.bin", new byte[]{1}, 0, false, null);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        bytes[bytes.length - 4] = 99;
        Files.write(archive.toPath(), bytes);
        assertMalformedIndexPreservesOutputs(archive);
    }

    @Test public void alzPartialSignaturesAfterPayloadFailBeforeOutput() throws Exception {
        for (int remaining = 1; remaining <= 3; remaining++) {
            File archive = buildAlzArchive("data.bin", new byte[]{1}, 0, false, null);
            byte[] bytes = Files.readAllBytes(archive.toPath());
            Files.write(archive.toPath(), java.util.Arrays.copyOf(bytes, bytes.length - 4 + remaining));
            assertMalformedIndexPreservesOutputs(archive);
        }
    }

    @Test public void alzTruncatedCentralRecordAndWrongEndMarkerFail() throws Exception {
        File complete = buildAlzArchive("data.bin", new byte[]{1}, 0, false, null);
        byte[] original = Files.readAllBytes(complete.toPath());
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        central.write(original, 0, original.length - 4); writeSegmentTrailer(central, 0x025a4c43);
        Files.write(complete.toPath(), central.toByteArray());
        byte[] bytes = Files.readAllBytes(complete.toPath());
        for (int retained = 4; retained < 16; retained++) {
            File archive = tempFolder.newFile();
            Files.write(archive.toPath(), java.util.Arrays.copyOf(bytes, bytes.length - 16 + retained));
            assertMalformedIndexPreservesOutputs(archive);
        }
        bytes[bytes.length - 1] = 99;
        Files.write(complete.toPath(), bytes);
        assertMalformedIndexPreservesOutputs(complete);
    }

    @Test public void alzLegacyCentralSplitMarkerBeforeSeparateEndIsReadable() throws Exception {
        File archive = buildAlzArchive("data.bin", new byte[]{1}, 0, false, null);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(bytes, 0, bytes.length - 4); writeSegmentTrailer(out, 0x035a4c43);
        writeIntLE(out, 0x025a4c43);
        Files.write(archive.toPath(), out.toByteArray());
        File output = tempFolder.newFile();
        assertTrue(AlzipArchiveReader.extractSingleEntry(archive, "data.bin", output, null));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(output.toPath()));
    }

    @Test public void alzSplitPayloadCannotConsumeRetainedTrailer() throws Exception {
        File archive = buildAlzArchive("data.bin", new byte[]{1}, 0, false, null);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(bytes, 0, bytes.length - 4); writeSegmentTrailer(out, 0x025a4c43);
        bytes = out.toByteArray();
        // A forged packed length would otherwise swallow the newly visible trailer.
        bytes[27] = 17;
        assertMalformedIndexPreservesOutputs(splitWithFinalTail(bytes, 36));
    }

    @Test public void alzCommentExtentCannotHideTruncationOrEntryData() throws Exception {
        for (int declared : new int[]{0, 3, 4, 5, Integer.MAX_VALUE, -1}) {
            File archive = alzWithComments(buildAlzArchive("data.bin", new byte[]{1}, 0, false, null), commentRecords());
            byte[] bytes = Files.readAllBytes(archive.toPath());
            for (int i = 0; i < 4; i++) bytes[bytes.length - 12 + i] = (byte)(declared >>> (i * 8));
            Files.write(archive.toPath(), bytes);
            assertMalformedIndexPreservesOutputs(archive);
        }
    }

    @Test public void alzTruncatedCommentRecordFailsWithoutOutput() throws Exception {
        for (byte[] malformed : new byte[][]{new byte[]{1}, new byte[]{0, 0, 0, 0, 2, 0, 1}}) {
            File archive = alzWithComments(buildAlzArchive("data.bin", new byte[]{1}, 0, false, null), malformed);
            assertMalformedIndexPreservesOutputs(archive);
        }
    }

    @Test public void alzLegacyNonemptyExactEofStillWorks() throws Exception {
        File archive = buildAlzArchive("data.bin", new byte[]{1, 2}, 0, false, null);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        Files.write(archive.toPath(), java.util.Arrays.copyOf(bytes, bytes.length - 4));
        File output = tempFolder.newFile();
        assertTrue(AlzipArchiveReader.extractSingleEntry(archive, "data.bin", output, null));
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(output.toPath()));
    }

    @Test public void malformedAlzIndexIsNotCachedAndEmptyReadsHonorCancellation() throws Exception {
        File archive = alzWithComments(null, commentRecords());
        byte[] valid = Files.readAllBytes(archive.toPath()), bad = valid.clone();
        bad[8] = 99;
        Files.write(archive.toPath(), bad);
        assertThrows(IOException.class, () -> AlzipArchiveReader.indexFor(archive));
        Files.write(archive.toPath(), valid);
        assertTrue(AlzipArchiveReader.listEntries(archive, null).isEmpty());
        File directory = tempFolder.newFolder();
        try {
            Thread.currentThread().interrupt();
            assertThrows(IOException.class, () -> AlzipArchiveReader.listEntries(archive, null));
            assertThrows(IOException.class, () -> AlzipArchiveReader.extractArchiveIntoDirectory(archive, directory, null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private File alzWithComments(File source, byte[] records) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (source == null) { writeIntLE(out, 0x015a4c41); writeIntLE(out, 0); }
        else {
            byte[] bytes = Files.readAllBytes(source.toPath());
            out.write(bytes, 0, bytes.length - 4);
        }
        writeIntLE(out, 0x015a4c45); out.write(records);
        writeIntLE(out, 0x015a4c43); writeIntLE(out, records.length + 4);
        writeIntLE(out, 0); writeIntLE(out, 0x025a4c43);
        File archive = new File(tempFolder.newFolder(), "comments.alz");
        Files.write(archive.toPath(), out.toByteArray());
        return archive;
    }

    private byte[] commentRecords() throws Exception {
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        writeIntLE(records, -1); writeShortLE(records, 8);
        // Embedded record magics are comment bytes, never parser resync points.
        writeIntLE(records, 0x015a4c42); writeIntLE(records, 0x025a4c43);
        writeIntLE(records, 0); writeShortLE(records, 0);
        return records.toByteArray();
    }

    private File splitWithFinalTail(byte[] bytes, int cut) throws Exception {
        File directory = tempFolder.newFolder();
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        first.write(bytes, 0, cut); writeSegmentTrailer(first, 0x035a4c43);
        Files.write(new File(directory, "split.alz").toPath(), first.toByteArray());
        ByteArrayOutputStream last = new ByteArrayOutputStream();
        writeIntLE(last, 0x015a4c41); writeIntLE(last, 0x0001000a);
        last.write(bytes, cut, bytes.length - cut);
        File selected = new File(directory, "split.a00");
        Files.write(selected.toPath(), last.toByteArray());
        return selected;
    }

    private void assertMalformedIndexPreservesOutputs(File archive) throws Exception {
        assertThrows(IOException.class, () -> AlzipArchiveReader.listEntries(archive, null));
        File output = tempFolder.newFile(); Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> AlzipArchiveReader.extractSingleEntry(archive, "data.bin", output, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
        File directory = tempFolder.newFolder(), existing = new File(directory, "data.bin");
        Files.write(existing.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> AlzipArchiveReader.extractArchiveIntoDirectory(archive, directory, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
        assertEquals(1, directory.list().length);
    }

    @Test public void bulkRejectsLateTraversalBeforeReplacingEarlierOutput() throws Exception {
        preflightProtectsEarlierOutput("../outside");
    }

    @Test public void bulkRejectsImplicitDirectoryCollisionBeforeReplacingEarlierOutput() throws Exception {
        preflightProtectsEarlierOutput("first/child");
    }

    @Test public void unsafeEntryIsHiddenFromListingButStillRejectedForBulk() throws Exception {
        File archive = buildAlzArchive("../outside", new byte[]{1}, 0, false, null);
        assertTrue(AlzipArchiveReader.listEntries(archive, null).isEmpty());
        File output = tempFolder.newFile(); Files.write(output.toPath(), new byte[]{42});
        assertFalse(AlzipArchiveReader.extractSingleEntry(archive, "../outside", output, null));
        org.junit.Assert.assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
        File directory = tempFolder.newFolder();
        org.junit.Assert.assertThrows(IOException.class,
                () -> AlzipArchiveReader.extractArchiveIntoDirectory(archive, directory, null));
    }

    @Test public void duplicateListingUsesFirstSelectedFileSize() throws Exception {
        File archive = buildAlzArchive("same", new byte[]{1}, 0, false, null);
        appendLocalEntries(archive, buildAlzArchive("same", new byte[]{2,3}, 0, false, null));
        List<ArchiveSupport.EntryInfo> entries = AlzipArchiveReader.listEntries(archive, null);
        assertEquals(1, entries.size()); assertEquals(1, entries.get(0).size);
        File output = tempFolder.newFile();
        assertTrue(AlzipArchiveReader.extractSingleEntry(archive, "same", output, null));
        org.junit.Assert.assertArrayEquals(new byte[]{1}, Files.readAllBytes(output.toPath()));
    }

    private void preflightProtectsEarlierOutput(String later) throws Exception {
        File archive = buildAlzArchive("first", new byte[]{1}, 0, false, null);
        appendLocalEntries(archive, buildAlzArchive(later, new byte[]{2}, 0, false, null));
        File directory = tempFolder.newFolder(), existing = new File(directory, "first");
        Files.write(existing.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class,
                () -> AlzipArchiveReader.extractArchiveIntoDirectory(archive, directory, null));
        org.junit.Assert.assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
        assertEquals(1, directory.list().length);
    }

    @Test public void malformedEggContentDoesNotInventPasswordRequirement() throws Exception {
        File archive = writeStubArchive("malformed.alz", "EGGA");
        assertFalse(AlzipArchiveReader.requiresPasswordForExtraction(archive));
        IOException failure = org.junit.Assert.assertThrows(IOException.class,
                () -> AlzipArchiveReader.listEntries(archive, null));
        assertFalse(failure instanceof ArchiveSupport.PasswordRequiredException);
    }

    @Test public void otherFamilyExtensionPlainArchiveUsesActualDecoder() throws Exception { familyBridge(false); }
    @Test public void otherFamilyExtensionEncryptedArchiveUsesActualDecoder() throws Exception { familyBridge(true); }
    @Test public void otherFamilyExtensionWrongPasswordPreservesTarget() throws Exception {
        byte[] payload = {1, 2, 3}; char[] password = "pw".toCharArray(); boolean encrypted = true;
        File original = buildAlzArchive("data.bin", payload, 2, encrypted, password);
        File archive = new File(tempFolder.newFolder(), "misnamed.egg");
        Files.copy(original.toPath(), archive.toPath());
        File output = tempFolder.newFile(); Files.write(output.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class,
                () -> EggArchiveReader.extractSingleEntry(archive, "data.bin", output, "wrong".toCharArray()));
        org.junit.Assert.assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    private void familyBridge(boolean encrypted) throws Exception {
        byte[] payload = {1, 2, 3}; char[] password = encrypted ? "pw".toCharArray() : null;
        File original = buildAlzArchive("data.bin", payload, 2, encrypted, password);
        File archive = new File(tempFolder.newFolder(), "misnamed.egg");
        Files.copy(original.toPath(), archive.toPath());
        assertEquals(encrypted, EggArchiveReader.requiresPasswordForExtraction(archive));
        assertEquals("data.bin", EggArchiveReader.listEntries(archive, password).get(0).path);
        File output = tempFolder.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(archive, "data.bin", output, password));
        org.junit.Assert.assertArrayEquals(payload, Files.readAllBytes(output.toPath()));
        File directory = tempFolder.newFolder();
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive, directory, password, null, null));
        org.junit.Assert.assertArrayEquals(payload, Files.readAllBytes(new File(directory, "data.bin").toPath()));
    }


    @Test public void decodedLengthMismatchIsRejectedEvenWithCorrectPayloadCrc() throws Exception {
        byte[] payload = new byte[]{1, 2, 3};
        for (int method : new int[]{0, 2}) {
            for (boolean encrypted : new boolean[]{false, true}) {
                for (int declared : new int[]{2, 4}) {
                    char[] password = encrypted ? "pw".toCharArray() : null;
                    File archive = buildAlzArchive("data.bin", payload, method, encrypted, password);
                    byte[] bytes = Files.readAllBytes(archive.toPath());
                    // Fixture has a four-byte size field, after CRC and packed size.
                    for (int i = 0; i < 4; i++) bytes[31 + i] = (byte) (declared >>> (i * 8));
                    Files.write(archive.toPath(), bytes);
                    File out = tempFolder.newFile();
                    Files.write(out.toPath(), new byte[]{42});
                    try {
                        AlzipArchiveReader.extractSingleEntry(archive, "data.bin", out, password);
                        fail("A correct CRC cannot authorize the wrong decoded length");
                    } catch (IOException expected) {
                        org.junit.Assert.assertArrayEquals(new byte[]{42}, Files.readAllBytes(out.toPath()));
                    }
                }
            }
        }
    }

    @Test public void bulkSizeMismatchPreservesExistingDestination() throws Exception {
        File archive = buildAlzArchive("data.bin", new byte[]{1, 2, 3}, 2, false, null);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        bytes[31] = 2;
        Files.write(archive.toPath(), bytes);
        File target = tempFolder.newFolder();
        File out = new File(target, "data.bin");
        Files.write(out.toPath(), new byte[]{42});
        try { AlzipArchiveReader.extractArchiveIntoDirectory(archive, target, null); fail("Size mismatch"); }
        catch (IOException expected) {
            org.junit.Assert.assertArrayEquals(new byte[]{42}, Files.readAllBytes(out.toPath()));
            assertEquals(1, target.list().length);
        }
    }

    @Test public void bulkCrcFailurePreservesExistingDestination() throws Exception {
        File archive = buildAlzArchive("data.bin", new byte[]{1, 2, 3}, 0, false, null);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        bytes[bytes.length - 5] ^= 1;
        Files.write(archive.toPath(), bytes);
        File target = tempFolder.newFolder();
        File out = new File(target, "data.bin");
        Files.write(out.toPath(), new byte[]{42});
        try { AlzipArchiveReader.extractArchiveIntoDirectory(archive, target, null); fail("CRC mismatch"); }
        catch (IOException expected) {
            org.junit.Assert.assertArrayEquals(new byte[]{42}, Files.readAllBytes(out.toPath()));
            assertEquals(1, target.list().length);
        }
    }

    @Test public void bulkWrongPasswordPreservesExistingDestination() throws Exception {
        File archive = buildAlzArchive("data.bin", new byte[]{1, 2, 3}, 2, true, "pw".toCharArray());
        File target = tempFolder.newFolder();
        File out = new File(target, "data.bin");
        Files.write(out.toPath(), new byte[]{42});
        try { AlzipArchiveReader.extractArchiveIntoDirectory(archive, target, "wrong".toCharArray()); fail("Password mismatch"); }
        catch (IOException expected) {
            org.junit.Assert.assertArrayEquals(new byte[]{42}, Files.readAllBytes(out.toPath()));
            assertEquals(1, target.list().length);
        }
    }

    @Test public void emptyStoredAndDeflatedEntriesRemainReadable() throws Exception {
        for (int method : new int[]{0, 2}) {
            File archive = buildAlzArchive("empty", new byte[0], method, false, null);
            File out = tempFolder.newFile();
            Files.write(out.toPath(), new byte[]{42});
            assertTrue(AlzipArchiveReader.extractSingleEntry(archive, "empty", out, null));
            assertEquals(0, out.length());
        }
    }

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void index_reusesMetadataAndReadsEntriesInReverseOrder() throws Exception {
        AlzipArchiveReader.clearIndexes();
        File first = buildAlzArchive("first.txt", "first".getBytes(StandardCharsets.UTF_8), 0, false, null);
        File second = buildAlzArchive("second.txt", "second".getBytes(StandardCharsets.UTF_8), 2, false, null);
        appendLocalEntries(first, second);
        AlzipArchiveReader.Index index = AlzipArchiveReader.indexFor(first);
        File out = tempFolder.newFile("indexed-reverse.txt");
        assertTrue(AlzipArchiveReader.extractSingleEntry(first, "second.txt", out, null));
        assertEquals("second", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
        assertTrue(AlzipArchiveReader.extractSingleEntry(first, "first.txt", out, null));
        assertEquals("first", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
        org.junit.Assert.assertSame(index, AlzipArchiveReader.indexFor(first));
    }

    @Test
    public void index_duplicateNameStillUsesFirstFile() throws Exception {
        File first = buildAlzArchive("same.txt", new byte[] {1}, 0, false, null);
        File second = buildAlzArchive("same.txt", new byte[] {2}, 0, false, null);
        appendLocalEntries(first, second);
        File out = tempFolder.newFile("duplicate-index.txt");
        assertTrue(AlzipArchiveReader.extractSingleEntry(first, "same.txt", out, null));
        org.junit.Assert.assertArrayEquals(new byte[] {1}, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void index_rebuildsAfterFirstVolumeSizeChanges() throws Exception {
        File first = buildAlzArchive("first.txt", new byte[] {1}, 0, false, null);
        AlzipArchiveReader.Index old = AlzipArchiveReader.indexFor(first);
        appendLocalEntries(first, buildAlzArchive("second.txt", new byte[] {2}, 0, false, null));
        org.junit.Assert.assertNotSame(old, AlzipArchiveReader.indexFor(first));
        assertEquals(2, AlzipArchiveReader.listEntries(first, null).size());
    }

    @Test
    public void index_checksLaterVolumeTimestampAndReleasesThroughContinuation() throws Exception {
        writeSplitAlzFixture("index-split", "data.txt", "split data repeated split data".getBytes(StandardCharsets.UTF_8));
        File first = new File(tempFolder.getRoot(), "index-split.alz");
        File part = new File(tempFolder.getRoot(), "index-split.a00");
        AlzipArchiveReader.Index old = AlzipArchiveReader.indexFor(first);
        assertTrue(part.setLastModified(part.lastModified() + 10000));
        AlzipArchiveReader.Index changed = AlzipArchiveReader.indexFor(first);
        org.junit.Assert.assertNotSame(old, changed);
        AlzipArchiveReader.releaseArchiveIndex(part);
        org.junit.Assert.assertNotSame(changed, AlzipArchiveReader.indexFor(first));
    }

    @Test
    public void index_missingContinuationCannotReuseWarmMetadata() throws Exception {
        writeSplitAlzFixture("index-missing", "data.txt", "split data repeated split data".getBytes(StandardCharsets.UTF_8));
        File first = new File(tempFolder.getRoot(), "index-missing.alz");
        AlzipArchiveReader.indexFor(first);
        assertTrue(new File(tempFolder.getRoot(), "index-missing.a00").delete());
        File out = new File(tempFolder.getRoot(), "missing-index.txt");
        try {
            AlzipArchiveReader.extractSingleEntry(first, "data.txt", out, null);
            fail("Missing volume must not succeed through a cached offset");
        } catch (IOException expected) { assertFalse(out.exists()); }
    }

    @Test
    public void index_passwordSuccessDoesNotAuthorizeLaterExtraction() throws Exception {
        File archive = buildAlzArchive("secret.txt", "secret".getBytes(StandardCharsets.UTF_8), 2, true, "pw".toCharArray());
        File out = tempFolder.newFile("password-index.txt");
        assertTrue(AlzipArchiveReader.extractSingleEntry(archive, "secret.txt", out, "pw".toCharArray()));
        AlzipArchiveReader.Index index = AlzipArchiveReader.indexFor(archive);
        for (char[] password : new char[][] {null, "wrong".toCharArray()}) {
            try {
                AlzipArchiveReader.extractSingleEntry(archive, "secret.txt", out, password);
                fail("Metadata reuse must not cache password authorization");
            } catch (IOException expected) {
                assertEquals("secret", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
            }
        }
        org.junit.Assert.assertSame(index, AlzipArchiveReader.indexFor(archive));
    }

    @Test
    public void index_stillChecksPayloadCrcOnWarmHitAndPreservesExistingTarget() throws Exception {
        File archive = buildAlzArchive("data.txt", new byte[] {1, 2, 3}, 0, false, null);
        AlzipArchiveReader.Index index = AlzipArchiveReader.indexFor(archive);
        long modified = archive.lastModified();
        byte[] corrupt = Files.readAllBytes(archive.toPath());
        corrupt[corrupt.length - 5] ^= 1; // Last payload byte, before the four-byte end marker.
        Files.write(archive.toPath(), corrupt);
        assertTrue(archive.setLastModified(modified));
        org.junit.Assert.assertSame(index, AlzipArchiveReader.indexFor(archive));
        File out = tempFolder.newFile("crc-index.txt");
        Files.write(out.toPath(), new byte[] {9});
        try {
            AlzipArchiveReader.extractSingleEntry(archive, "data.txt", out, null);
            fail("Cached metadata must not bypass payload CRC");
        } catch (IOException expected) {
            org.junit.Assert.assertArrayEquals(new byte[] {9}, Files.readAllBytes(out.toPath()));
        }
    }

    @Test
    public void index_lruRetainsRecentlyUsedArchiveAndEvictsOldest() throws Exception {
        AlzipArchiveReader.clearIndexes();
        File[] archives = new File[4];
        AlzipArchiveReader.Index[] indexes = new AlzipArchiveReader.Index[3];
        for (int i = 0; i < 4; i++) {
            archives[i] = buildAlzArchive("page.txt", new byte[] {(byte) i}, 0, false, null);
            if (i < 3) indexes[i] = AlzipArchiveReader.indexFor(archives[i]);
        }
        org.junit.Assert.assertSame(indexes[0], AlzipArchiveReader.indexFor(archives[0]));
        AlzipArchiveReader.indexFor(archives[3]);
        org.junit.Assert.assertSame(indexes[0], AlzipArchiveReader.indexFor(archives[0]));
        org.junit.Assert.assertNotSame(indexes[1], AlzipArchiveReader.indexFor(archives[1]));
    }

    @Test
    public void index_interruptionRejectsEvenWarmCacheWithoutClearingInterrupt() throws Exception {
        File archive = buildAlzArchive("page.txt", new byte[] {1}, 0, false, null);
        AlzipArchiveReader.indexFor(archive);
        Thread.currentThread().interrupt();
        try {
            AlzipArchiveReader.indexFor(archive);
            fail("Cancelled indexed operation must fail");
        } catch (IOException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }

    @Test
    public void index_largeMetadataRemainsUsableWithoutCacheAdmission() throws Exception {
        File archive = buildAlzArchive("page.txt", new byte[] {1}, 0, false, null);
        byte[] template = Files.readAllBytes(archive.toPath());
        ByteArrayOutputStream repeated = new ByteArrayOutputStream();
        repeated.write(template, 0, 8);
        for (int i = 0; i < 20001; i++) repeated.write(template, 8, template.length - 12);
        repeated.write(template, template.length - 4, 4);
        Files.write(archive.toPath(), repeated.toByteArray());
        AlzipArchiveReader.Index first = AlzipArchiveReader.indexFor(archive);
        org.junit.Assert.assertNotSame(first, AlzipArchiveReader.indexFor(archive));
        File out = tempFolder.newFile("uncached-large-index.txt");
        assertTrue(AlzipArchiveReader.extractSingleEntry(archive, "page.txt", out, null));
        org.junit.Assert.assertArrayEquals(new byte[] {1}, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void index_doesNotKeepArchiveFileOpen() throws Exception {
        File archive = buildAlzArchive("page.txt", new byte[] {1}, 0, false, null);
        AlzipArchiveReader.indexFor(archive);
        AlzipArchiveReader.releaseArchiveIndex(archive);
        File renamed = new File(tempFolder.getRoot(), "renamed-index.alz");
        assertTrue(archive.renameTo(renamed));
        assertEquals(1, AlzipArchiveReader.listEntries(renamed, null).size());
    }

    private static void appendLocalEntries(File first, File second) throws IOException {
        byte[] a = Files.readAllBytes(first.toPath());
        byte[] b = Files.readAllBytes(second.toPath());
        ByteArrayOutputStream merged = new ByteArrayOutputStream();
        merged.write(a, 0, a.length - 4); // Drop first archive's end marker.
        merged.write(b, 8, b.length - 8); // Omit second archive's container header.
        Files.write(first.toPath(), merged.toByteArray());
    }

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
        assertFalse(ArchiveSupport.requiresPasswordForExtraction(alzPart));
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
