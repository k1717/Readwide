package com.readwide.manager.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

public class Rar5CompressedSplitGuardTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test public void bulkInvalidMetadataDoesNotCreateOutputDirectory() throws Exception {
        RarArchiveReader.RarEntry first = splitEntry(false, true, 1, 0, 4, 0, null);
        RarArchiveReader.RarEntry last = splitEntry(true, false, 2, 0, 4, 0, null);
        File output = new File(tempFolder.getRoot(), "not-created");
        java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> Rar5CompressedArchiveExtractor.tryExtractArchive(
                        Arrays.asList(first, last), output, null, null, null));
        assertChangedParameters(failure);
        assertFalse(output.exists());
    }

    @Test public void bulkLaterMetadataFailureKeepsEarlierCommittedFileAndExistingTarget() throws Exception {
        RarArchiveReader.RarEntry first = splitEntry(false, true, 1, 0, 4, 0, null);
        RarArchiveReader.RarEntry last = splitEntry(true, false, 2, 0, 4, 0, null);
        RarArchiveReader.RarEntry stored = storedPrefix();
        File output = tempFolder.newFolder();
        File existing = new File(output, first.path);
        java.nio.file.Files.write(existing.toPath(), new byte[]{42});
        java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> Rar5CompressedArchiveExtractor.tryExtractArchive(
                        Arrays.asList(stored, first, last), output, null, null, null));
        assertChangedParameters(failure);
        org.junit.Assert.assertArrayEquals(new byte[]{7, 8, 9},
                java.nio.file.Files.readAllBytes(new File(output, stored.path).toPath()));
        org.junit.Assert.assertArrayEquals(new byte[]{42}, java.nio.file.Files.readAllBytes(existing.toPath()));
        org.junit.Assert.assertEquals(2, output.list().length);
    }

    @Test public void forwardInvalidMetadataDoesNotCreateSpoolDirectoryAndRetiresReader() throws Exception {
        RarArchiveReader.RarEntry first = splitEntry(false, true, 1, 0, 4, 0, null);
        RarArchiveReader.RarEntry last = splitEntry(true, false, 2, 0, 4, 0, null);
        File spool = new File(tempFolder.getRoot(), "not-created-spool");
        try (ArchiveSupport.ForwardArchiveReader reader = metadataForward(Arrays.asList(first, last), spool)) {
            org.junit.Assert.assertEquals(first.path, reader.nextEntry().path);
            java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                    () -> reader.read(new byte[1]));
            assertChangedParameters(failure);
            assertFalse(spool.exists());
            org.junit.Assert.assertThrows(java.io.IOException.class, () -> reader.nextEntry());
            org.junit.Assert.assertThrows(java.io.IOException.class, () -> reader.drainCurrentEntry(Long.MAX_VALUE));
        }
        assertFalse(spool.exists());
    }

    @Test public void forwardLaterMetadataFailurePreservesReadablePrefixAndCleansSpool() throws Exception {
        RarArchiveReader.RarEntry first = splitEntry(false, true, 1, 0, 4, 0, null);
        RarArchiveReader.RarEntry last = splitEntry(true, false, 2, 0, 4, 0, null);
        RarArchiveReader.RarEntry stored = storedPrefix();
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = metadataForward(Arrays.asList(stored, first, last), spool)) {
            org.junit.Assert.assertEquals(stored.path, reader.nextEntry().path);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[2];
            int count;
            while ((count = reader.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            org.junit.Assert.assertArrayEquals(new byte[]{7, 8, 9}, bytes.toByteArray());
            org.junit.Assert.assertEquals(first.path, reader.nextEntry().path);
            java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                    () -> reader.drainCurrentEntry(Long.MAX_VALUE));
            assertChangedParameters(failure);
            org.junit.Assert.assertEquals(0, spool.list().length);
            org.junit.Assert.assertThrows(java.io.IOException.class, () -> reader.read(buffer));
        }
        org.junit.Assert.assertEquals(0, spool.list().length);
    }

    @Test public void unsafeLaterBulkPathDoesNotReplaceExistingPrefix() throws Exception {
        RarArchiveReader.RarEntry stored = storedPrefix();
        File output = tempFolder.newFolder();
        File prefix = new File(output, stored.path);
        java.nio.file.Files.write(prefix.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(java.io.IOException.class, () -> Rar5CompressedArchiveExtractor.tryExtractArchive(
                Arrays.asList(stored, unsafeCompressed()), output, null, null, null));
        org.junit.Assert.assertArrayEquals(new byte[]{42}, java.nio.file.Files.readAllBytes(prefix.toPath()));
        org.junit.Assert.assertEquals(1, output.list().length);
    }

    @Test public void unsafeLaterBulkPathDoesNotCreateOutputRoot() throws Exception {
        File output = new File(tempFolder.getRoot(), "uncreated-root");
        RarArchiveReader.RarEntry stored = storedPrefix();
        org.junit.Assert.assertThrows(java.io.IOException.class, () -> Rar5CompressedArchiveExtractor.tryExtractArchive(
                Arrays.asList(stored, unsafeCompressed()), output, null, null, null));
        assertFalse(output.exists());
    }

    @Test public void unsafeLaterBulkPathDoesNotCreateEarlierDirectory() throws Exception {
        File output = tempFolder.newFolder();
        RarArchiveReader.RarEntry directory = new RarArchiveReader.RarEntry("sub/", true,
                0, 0, 0, 5, 0, false, false, false, null, 0, 0, 0);
        org.junit.Assert.assertThrows(java.io.IOException.class, () -> Rar5CompressedArchiveExtractor.tryExtractArchive(
                Arrays.asList(directory, unsafeCompressed()), output, null, null, null));
        org.junit.Assert.assertEquals(0, output.list().length);
    }

    private RarArchiveReader.RarEntry unsafeCompressed() {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("../outside.bin", false,
                1, 1, 0, 5, 1, false, false, false, null, 0, 0, 0);
        entry.sourceArchive = new File(tempFolder.getRoot(), "absent-payload.rar");
        return entry;
    }

    private RarArchiveReader.RarEntry storedPrefix() throws Exception {
        byte[] bytes = {7, 8, 9};
        File source = tempFolder.newFile();
        java.nio.file.Files.write(source.toPath(), bytes);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes);
        RarArchiveReader.RarEntry stored = new RarArchiveReader.RarEntry("prefix.bin", false,
                bytes.length, bytes.length, 0, 5, 0, false, false, false, null, crc.getValue(), 0, 0);
        stored.sourceArchive = source;
        return stored;
    }

    private static ArchiveSupport.ForwardArchiveReader metadataForward(
            java.util.List<RarArchiveReader.RarEntry> entries, File spool) throws Exception {
        // Exercise the indexed session without encoding malformed metadata into headers.
        return new Rar5ForwardReader(entries, null, spool, RarVolumeChain.index(entries));
    }

    private static void assertChangedParameters(java.io.IOException failure) {
        org.junit.Assert.assertEquals("RAR5 compressed split parameters changed between volumes", failure.getMessage());
    }

    @Test public void selectedChangedContinuationMethodFailsBeforePrimer() throws Exception {
        assertMetadataFailure(splitEntry(false, true, 1, 0, 4, 0, null),
                splitEntry(true, false, 2, 0, 4, 0, null),
                "RAR5 compressed split parameters changed between volumes");
    }

    @Test public void selectedChangedContinuationCompressionInfoFailsBeforePrimer() throws Exception {
        assertMetadataFailure(splitEntry(false, true, 1, 0, 4, 0, null),
                splitEntry(true, false, 1, 1, 4, 0, null),
                "RAR5 compressed split parameters changed between volumes");
    }

    @Test public void selectedMixedContinuationEncryptionFailsBeforePrimer() throws Exception {
        assertMetadataFailure(splitEntry(false, true, 1, 0, 4, 0, null),
                splitEntry(true, false, 1, 0, 4, 0, encryption(1, 0)),
                "Mixed encrypted and plain RAR5 compressed split payload is not supported");
    }

    @Test public void selectedChangedContinuationIvFailsBeforePrimerOrPasswordPrompt() throws Exception {
        assertMetadataFailure(splitEntry(false, true, 1, 0, 16, 0, encryption(1, 0)),
                splitEntry(true, false, 1, 0, 16, 0, encryption(1, 1)),
                "RAR5 encrypted split parameters changed between volumes");
    }

    @Test public void selectedInvalidContinuationBoundsFailBeforePrimer() throws Exception {
        assertMetadataFailure(splitEntry(false, true, 1, 0, 4, 0, null),
                splitEntry(true, false, 1, 0, -1, 0, null),
                "Invalid RAR5 compressed split segment bounds");
        assertMetadataFailure(splitEntry(false, true, 1, 0, 4, 0, null),
                splitEntry(true, false, 1, 0, 4, -1, null),
                "Invalid RAR5 compressed split segment bounds");
        RarArchiveReader.RarEntry noSource = splitEntry(true, false, 1, 0, 4, 0, null);
        noSource.sourceArchive = null;
        assertMetadataFailure(splitEntry(false, true, 1, 0, 4, 0, null), noSource,
                "Invalid RAR5 compressed split segment bounds");
    }

    @Test public void selectedUnalignedEncryptedTotalFailsBeforePrimer() throws Exception {
        assertMetadataFailure(splitEntry(false, true, 1, 0, 11, 0, encryption(1, 0)),
                splitEntry(true, false, 1, 0, 6, 0, encryption(1, 0)),
                "RAR5 encrypted payload is not AES block aligned");
    }

    @Test public void bulkUnalignedEncryptedTotalDoesNotCreateOutputDirectory() throws Exception {
        RarArchiveReader.RarEntry first = splitEntry(false, true, 1, 0, 11, 0, encryption(1, 0));
        RarArchiveReader.RarEntry last = splitEntry(true, false, 1, 0, 6, 0, encryption(1, 0));
        File output = new File(tempFolder.getRoot(), "unaligned-output");
        java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> Rar5CompressedArchiveExtractor.tryExtractArchive(
                        Arrays.asList(first, last), output, null, null, null));
        org.junit.Assert.assertEquals("RAR5 encrypted payload is not AES block aligned", failure.getMessage());
        assertFalse(output.exists());
    }

    @Test public void forwardUnalignedEncryptedTotalDoesNotCreateSpoolDirectory() throws Exception {
        RarArchiveReader.RarEntry first = splitEntry(false, true, 1, 0, 11, 0, encryption(1, 0));
        RarArchiveReader.RarEntry last = splitEntry(true, false, 1, 0, 6, 0, encryption(1, 0));
        File spool = new File(tempFolder.getRoot(), "unaligned-spool");
        try (ArchiveSupport.ForwardArchiveReader reader = metadataForward(Arrays.asList(first, last), spool)) {
            org.junit.Assert.assertEquals(first.path, reader.nextEntry().path);
            java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                    () -> reader.read(new byte[1]));
            org.junit.Assert.assertEquals("RAR5 encrypted payload is not AES block aligned", failure.getMessage());
            assertFalse(spool.exists());
            org.junit.Assert.assertThrows(java.io.IOException.class, () -> reader.nextEntry());
        }
        assertFalse(spool.exists());
    }

    @Test public void encryptedPartBoundaryMayBeUnalignedWhenCombinedStreamIsAligned() throws Exception {
        assertValidLengthPreflight(encryption(1, 0), 11, 5);
    }

    @Test public void plainCombinedStreamNeedNotBeAesAligned() throws Exception {
        assertValidLengthPreflight(null, 11, 6);
    }

    private void assertValidLengthPreflight(RarArchiveReader.EncryptionInfo encryption,
            long firstSize, long lastSize) throws Exception {
        RarArchiveReader.RarEntry first = splitEntry(false, true, 1, 0, firstSize, 0, encryption);
        RarArchiveReader.RarEntry last = splitEntry(true, false, 1, 0, lastSize, 0, encryption);
        File output = new File(tempFolder.getRoot(), "valid-length-output");
        // Eligible metadata reaches the deliberately absent plain primer before target I/O or KDF.
        org.junit.Assert.assertThrows(java.io.FileNotFoundException.class,
                () -> Rar5CompressedArchiveExtractor.tryExtractEntry(first,
                        Arrays.asList(unreadablePrimer(), first, last), output, null, null));
        assertFalse(output.exists());
    }

    @Test public void selectedPackedSizeOverflowFailsBeforePrimer() throws Exception {
        assertMetadataFailure(splitEntry(false, true, 1, 0, Long.MAX_VALUE, 0, null),
                splitEntry(true, false, 1, 0, 4, 0, null),
                "RAR5 compressed split payload is too large");
    }

    @Test public void invalidNegativeFinalSizeFailsBeforePrimerAndLeavesExistingOutput() throws Exception {
        RarArchiveReader.RarEntry first = splitEntry(false, true, 1, 0, 4, 0, null);
        RarArchiveReader.RarEntry last = new RarArchiveReader.RarEntry(first.path, false,
                -2, 4, 0, 5, 1, true, true, false, null, 0, 0, first.rar5CompressionInfo);
        last.sourceArchive = first.sourceArchive;
        assertMetadataFailure(first, last, "Invalid RAR5 compressed split unpacked size");
    }

    @Test public void selectedIntermediateMetadataIsCheckedBeforePrimer() throws Exception {
        RarArchiveReader.RarEntry first = splitEntry(false, true, 1, 0, 4, 0, null);
        RarArchiveReader.RarEntry middle = splitEntry(true, true, 2, 0, 4, 0, null);
        RarArchiveReader.RarEntry last = splitEntry(true, false, 1, 0, 4, 0, null);
        assertMetadataFailure(first, Arrays.asList(first, middle, last),
                "RAR5 compressed split parameters changed between volumes");
    }

    @Test public void selectedRequiredPrimerMetadataIsCheckedBeforeEarlierPrimer() throws Exception {
        RarArchiveReader.RarEntry first = splitEntry(false, true, 1, 0, 4, 0, null);
        RarArchiveReader.RarEntry last = splitEntry(true, false, 2, 0, 4, 0, null);
        RarArchiveReader.RarEntry target = splitEntry(false, false, 1, 0, 4, 0, null);
        assertMetadataFailure(target, Arrays.asList(first, last, target),
                "RAR5 compressed split parameters changed between volumes");
    }

    @Test public void validEncryptedMetadataDoesNotReadTargetOrDerivePasswordBeforePrimer() throws Exception {
        RarArchiveReader.RarEntry first = splitEntry(false, true, 1, 0, 16, 0, encryption(1, 0));
        RarArchiveReader.RarEntry last = splitEntry(true, false, 1, 0, 16, 0, encryption(3, 0));
        File output = new File(tempFolder.getRoot(), "not-created.bin");
        // Matching AES stream parameters with differing per-volume check flags remain valid.
        // The absent plain primer must be opened first; metadata preflight needs no password.
        org.junit.Assert.assertThrows(java.io.FileNotFoundException.class,
                () -> Rar5CompressedArchiveExtractor.tryExtractEntry(first,
                        Arrays.asList(unreadablePrimer(), first, last), output, null, null));
        assertFalse(output.exists());
    }

    private void assertMetadataFailure(RarArchiveReader.RarEntry first,
            RarArchiveReader.RarEntry last, String message) throws Exception {
        assertMetadataFailure(first, Arrays.asList(first, last), message);
    }

    private void assertMetadataFailure(RarArchiveReader.RarEntry target,
            java.util.List<RarArchiveReader.RarEntry> required, String message) throws Exception {
        java.util.List<RarArchiveReader.RarEntry> entries = new java.util.LinkedList<>();
        entries.add(unreadablePrimer());
        entries.addAll(required);
        File output = tempFolder.newFile();
        java.nio.file.Files.write(output.toPath(), new byte[]{42});
        java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> Rar5CompressedArchiveExtractor.tryExtractEntry(target, entries, output, null, null));
        org.junit.Assert.assertEquals(message, failure.getMessage());
        org.junit.Assert.assertArrayEquals(new byte[]{42}, java.nio.file.Files.readAllBytes(output.toPath()));
    }

    private RarArchiveReader.RarEntry unreadablePrimer() {
        RarArchiveReader.RarEntry primer = rar5CompressedSplit(false, false);
        primer.sourceArchive = new File(tempFolder.getRoot(), "absent-primer.rar");
        return primer;
    }

    private RarArchiveReader.RarEntry splitEntry(boolean before, boolean after,
            int method, long info, long packedSize, long offset, RarArchiveReader.EncryptionInfo encryption) {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("target.png", false,
                8, packedSize, offset, 5, method, true, before, after, encryption, 0, 0, info | 0x40L);
        entry.sourceArchive = new File(tempFolder.getRoot(), "absent-target.rar");
        return entry;
    }

    private static RarArchiveReader.EncryptionInfo encryption(long flags, int ivByte) {
        byte[] iv = new byte[16];
        iv[0] = (byte) ivByte;
        return new RarArchiveReader.EncryptionInfo(0, flags, 1, new byte[16], iv, new byte[12]);
    }

    @Test public void selectedMissingTailFailsBeforeDecodingAnyPrimerAndPreservesOutput() throws Exception {
        RarArchiveReader.RarEntry primer = rar5CompressedSplit(false, false);
        primer.sourceArchive = tempFolder.newFile(); // Deliberately undecodable: topology must fail first.
        RarArchiveReader.RarEntry target = new RarArchiveReader.RarEntry("target.png", false,
                8, 4, 0, 5, 1, true, false, true, null, 0, 0, 0);
        target.sourceArchive = tempFolder.newFile();
        File output = tempFolder.newFile();
        java.nio.file.Files.write(output.toPath(), new byte[]{42});
        java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> Rar5CompressedArchiveExtractor.tryExtractEntry(
                        target, Arrays.asList(primer, target), output, null, null));
        assertTrue(failure.getMessage().contains("split continuation"));
        org.junit.Assert.assertArrayEquals(new byte[]{42}, java.nio.file.Files.readAllBytes(output.toPath()));
    }

    @Test public void bulkRejectsOrphanBeforePublishingAnyOutput() throws Exception {
        RarArchiveReader.RarEntry compressed = rar5CompressedSplit(false, false);
        compressed.sourceArchive = tempFolder.newFile();
        RarArchiveReader.RarEntry orphan = rar5CompressedSplit(true, false);
        orphan.sourceArchive = tempFolder.newFile();
        File target = new File(tempFolder.getRoot(), "not-created");
        java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> Rar5CompressedArchiveExtractor.tryExtractArchive(
                        Arrays.asList(compressed, orphan), target, null, null, null));
        assertTrue(failure.getMessage().contains("split head"));
        assertFalse(target.exists());
    }

    @Test public void bulkRejectsMissingTailBeforePublishingAnyOutput() throws Exception {
        RarArchiveReader.RarEntry compressed = rar5CompressedSplit(false, true);
        compressed.sourceArchive = tempFolder.newFile();
        File target = new File(tempFolder.getRoot(), "not-created");
        java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> Rar5CompressedArchiveExtractor.tryExtractArchive(
                        Arrays.asList(compressed), target, null, null, null));
        assertTrue(failure.getMessage().contains("split continuation"));
        assertFalse(target.exists());
    }

    @Test public void bulkDirectoryCollisionFailsBeforeCompressedDecoding() throws Exception {
        File target = tempFolder.newFolder();
        File occupied = new File(target, "occupied");
        java.nio.file.Files.write(occupied.toPath(), new byte[]{42});
        RarArchiveReader.RarEntry directory = new RarArchiveReader.RarEntry("occupied", true,
                0, 0, 0, 5, 0, false, false, false, null, 0, 0, 0);
        RarArchiveReader.RarEntry compressed = rar5CompressedSplit(false, false);
        compressed.sourceArchive = tempFolder.newFile();
        java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> Rar5CompressedArchiveExtractor.tryExtractArchive(
                        Arrays.asList(directory, compressed), target, null, null, null));
        org.junit.Assert.assertEquals("Cannot create output directory", failure.getMessage());
        org.junit.Assert.assertArrayEquals(new byte[]{42}, java.nio.file.Files.readAllBytes(occupied.toPath()));
        assertFalse(new File(target, "image.bin").exists());
    }

    @Test
    public void firstPartyRar5CompressedExtractorDeletesOutputWhenSplitPayloadIsMalformed() throws Exception {
        RarArchiveReader.RarEntry first = rar5CompressedSplit(false, true);
        RarArchiveReader.RarEntry last = rar5CompressedSplit(true, false);
        first.sourceArchive = tempFolder.newFile("sample.part1.rar");
        last.sourceArchive = tempFolder.newFile("sample.part2.rar");

        File out = new File(tempFolder.getRoot(), "out.bin");

        try {
            Rar5CompressedArchiveExtractor.tryExtractEntry(
                    first,
                    Arrays.asList(first, last),
                    out,
                    null,
                    null);
        } catch (java.io.IOException expected) {
            assertFalse("Split compressed fallback must not create partial output", out.exists());
            return;
        }
        throw new AssertionError("Malformed compressed split payload must fail");
    }

    @Test
    public void rar5CompressedFallbackFailureNamesSplitBoundary() {
        RarArchiveReader.RarEntry first = rar5CompressedSplit(false, true);
        RarArchiveReader.RarEntry last = rar5CompressedSplit(true, false);

        RarArchiveReader.UnsupportedRarFeatureException failure =
                RarFeatureClassifier.rar5CompressedFallbackFailure(
                        Arrays.asList(first, last), null);

        assertTrue(failure.getMessage().contains("RAR5 compressed split/multi-volume"));
        assertTrue(failure.getMessage().contains("could not be completed"));
        assertTrue(failure.getMessage().contains("never routed through the stored-entry fallback"));
    }

    private static RarArchiveReader.RarEntry rar5CompressedSplit(boolean splitBefore, boolean splitAfter) {
        return new RarArchiveReader.RarEntry(
                "image.bin",
                false,
                8L,
                4L,
                0L,
                5,
                1,
                false,
                splitBefore,
                splitAfter,
                null,
                0L,
                0L,
                0L);
    }
}
