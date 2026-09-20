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
    public void metadataBounds_truncatedExtraCannotPublishPartialIndex() throws Exception {
        File archive = buildEggArchive("page.txt", new byte[] {1}, 0, false);
        byte[] valid = Files.readAllBytes(archive.toPath());
        byte[] malformed = valid.clone();
        int name = boundsTestMagicOffset(malformed, MAGIC_FILENAME);
        malformed[name + 5] = (byte) 0xff;
        malformed[name + 6] = (byte) 0xff; // Declared name payload crosses physical EOF.
        Files.write(archive.toPath(), malformed);
        expectInvalidMetadata(archive);
        Files.write(archive.toPath(), valid);
        assertEquals("page.txt", EggArchiveReader.listEntries(archive, null).get(0).path);
    }

    @Test
    public void metadataBounds_truncatedBlockCannotAppearAsValidListing() throws Exception {
        File archive = buildEggArchive("page.txt", new byte[] {1}, 0, false);
        byte[] malformed = Files.readAllBytes(archive.toPath());
        int block = boundsTestMagicOffset(malformed, MAGIC_BLOCK);
        for (int i = 10; i < 14; i++) malformed[block + i] = (byte) 0xff;
        Files.write(archive.toPath(), malformed);
        expectInvalidMetadata(archive);
        File output = tempFolder.newFile("invalid-metadata.out");
        Files.write(output.toPath(), new byte[] {9});
        try {
            EggArchiveReader.extractSingleEntry(archive, "page.txt", output, null);
            fail("Malformed metadata must fail before touching the target");
        } catch (IOException expected) {
            org.junit.Assert.assertArrayEquals(new byte[] {9}, Files.readAllBytes(output.toPath()));
        }
    }

    @Test
    public void metadataBounds_unrepresentableFileSizeRejectedDuringListing() throws Exception {
        File archive = buildEggArchive("page.txt", new byte[] {1}, 0, false);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        int file = boundsTestMagicOffset(bytes, MAGIC_FILE);
        bytes[file + 15] |= (byte) 0x80;
        Files.write(archive.toPath(), bytes);
        expectInvalidMetadata(archive);
    }

    @Test
    public void metadataBounds_validLegacyMissingBlockEndStillExtracts() throws Exception {
        File archive = buildEggArchive("page.txt", new byte[] {1, 2, 3, 4, 5}, 0, false);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        int blockEnd = boundsTestMagicOffset(bytes, MAGIC_BLOCK) + 18;
        ByteArrayOutputStream legacy = new ByteArrayOutputStream();
        legacy.write(bytes, 0, blockEnd);
        legacy.write(bytes, blockEnd + 4, bytes.length - blockEnd - 4);
        Files.write(archive.toPath(), legacy.toByteArray());
        File output = tempFolder.newFile("legacy-no-block-end.out");
        assertTrue(EggArchiveReader.extractSingleEntry(archive, "page.txt", output, null));
        org.junit.Assert.assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, Files.readAllBytes(output.toPath()));
    }

    @Test
    public void metadataBounds_unknownBoundedExtraStillSkipped() throws Exception {
        File archive = buildEggArchive("page.txt", new byte[] {1}, 0, false);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        int beforeName = boundsTestMagicOffset(bytes, MAGIC_FILENAME);
        ByteArrayOutputStream extended = new ByteArrayOutputStream();
        extended.write(bytes, 0, beforeName);
        writeIntLE(extended, 0x76543210);
        extended.write(0); writeShortLE(extended, 3); extended.write(new byte[] {1, 2, 3});
        extended.write(bytes, beforeName, bytes.length - beforeName);
        Files.write(archive.toPath(), extended.toByteArray());
        assertEquals("page.txt", EggArchiveReader.listEntries(archive, null).get(0).path);
    }

    @Test
    public void metadataBounds_rangeArithmeticHandlesLongLimitsWithoutWrapping() throws Exception {
        assertEquals(Long.MAX_VALUE, EggArchiveReader.checkedPayloadEnd(Long.MAX_VALUE - 3, 3, Long.MAX_VALUE));
        assertEquals(10, EggArchiveReader.checkedPayloadEnd(10, 0, 10));
        long[][] invalid = {{-1, 1, 10}, {0, -1, 10}, {0, 0, -1}, {11, 0, 10},
                {Long.MAX_VALUE - 3, 4, Long.MAX_VALUE}};
        for (long[] range : invalid) {
            try {
                EggArchiveReader.checkedPayloadEnd(range[0], range[1], range[2]);
                fail("Invalid range accepted");
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("range")); }
        }
    }

    private void expectInvalidMetadata(File archive) throws Exception {
        try {
            EggArchiveReader.indexFor(archive);
            fail("Invalid metadata must not produce a cacheable index");
        } catch (IOException expected) { assertTrue(expected.getMessage() != null); }
    }

    private static int boundsTestMagicOffset(byte[] bytes, int magic) {
        for (int i = 0; i <= bytes.length - 4; i++) {
            int value = (bytes[i] & 0xff) | ((bytes[i + 1] & 0xff) << 8)
                    | ((bytes[i + 2] & 0xff) << 16) | ((bytes[i + 3] & 0xff) << 24);
            if (value == magic) return i;
        }
        throw new AssertionError("Fixture magic not found");
    }

    @Test
    public void splitDiscovery_mixedCaseAndPaddingExtractFromEitherPart() throws Exception {
        byte[] payload = buildRepeatingPayload(6000);
        writeSplitPair("renamed-split", payload);
        File first = new File(tempFolder.getRoot(), "Comic.VOL001.EGG");
        File second = new File(tempFolder.getRoot(), "comic.vol02.eGg");
        assertTrue(new File(tempFolder.getRoot(), "renamed-split.vol1.egg").renameTo(first));
        assertTrue(new File(tempFolder.getRoot(), "renamed-split.vol2.egg").renameTo(second));
        assertEquals(first.getCanonicalFile(), EggArchiveReader.resolveFirstVolume(second).getCanonicalFile());
        assertEquals(first.getCanonicalFile(), ArchiveSupport.normalizeExtractionQueueArchive(second).getCanonicalFile());
        File out = tempFolder.newFile("renamed-split.out");
        assertTrue(EggArchiveReader.extractSingleEntry(first, "data.txt", out, null));
        org.junit.Assert.assertArrayEquals(payload, Files.readAllBytes(out.toPath()));
        assertTrue(ArchiveSupport.extractSingleEntry(second, "data.txt", out, null));
        org.junit.Assert.assertArrayEquals(payload, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void splitDiscovery_ambiguousNextOrdinalIsRejectedEvenWithWarmIndex() throws Exception {
        writeSplitPair("duplicate-next", buildRepeatingPayload(6000));
        File first = new File(tempFolder.getRoot(), "duplicate-next.vol1.egg");
        File second = new File(tempFolder.getRoot(), "duplicate-next.vol2.egg");
        EggArchiveReader.indexFor(first);
        Files.copy(second.toPath(), new File(tempFolder.getRoot(), "duplicate-next.vol02.egg").toPath());
        File out = tempFolder.newFile("ambiguous-next.out");
        Files.write(out.toPath(), new byte[] {9});
        try {
            EggArchiveReader.extractSingleEntry(first, "data.txt", out, null);
            fail("Duplicate numeric aliases must not pick an arbitrary volume");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Ambiguous"));
            org.junit.Assert.assertArrayEquals(new byte[] {9}, Files.readAllBytes(out.toPath()));
        }
    }

    @Test
    public void splitDiscovery_ambiguousFirstOrdinalIsRejected() throws Exception {
        writeSplitPair("duplicate-first", buildRepeatingPayload(6000));
        File first = new File(tempFolder.getRoot(), "duplicate-first.vol1.egg");
        File second = new File(tempFolder.getRoot(), "duplicate-first.vol2.egg");
        Files.copy(first.toPath(), new File(tempFolder.getRoot(), "duplicate-first.vol01.egg").toPath());
        try {
            EggArchiveReader.resolveFirstVolume(second);
            fail("First-volume aliases must be rejected");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("Ambiguous")); }
        try {
            EggArchiveReader.indexFor(first);
            fail("Direct first-party access must reject aliases too");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("Ambiguous")); }
    }

    @Test
    public void splitDiscovery_missingNumberCannotSkipToLaterVolume() throws Exception {
        writeSplitPair("number-gap", buildRepeatingPayload(6000));
        File first = new File(tempFolder.getRoot(), "number-gap.vol1.egg");
        assertTrue(new File(tempFolder.getRoot(), "number-gap.vol2.egg").renameTo(
                new File(tempFolder.getRoot(), "number-gap.vol03.egg")));
        try {
            EggArchiveReader.indexFor(first);
            fail("Header links do not authorize skipping a missing ordinal");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("Missing")); }
    }

    @Test
    public void splitDiscovery_forwardIdMustMatchNextVolumesHeader() throws Exception {
        writeSplitPair("forward-id", buildRepeatingPayload(6000));
        File first = new File(tempFolder.getRoot(), "forward-id.vol1.egg");
        EggArchiveReader.indexFor(first);
        long modified = first.lastModified();
        byte[] bytes = Files.readAllBytes(first.toPath());
        bytes[25] ^= 1; // First prefix's next-volume id; next volume's prev remains correct.
        Files.write(first.toPath(), bytes);
        assertTrue(first.setLastModified(modified));
        try {
            EggArchiveReader.indexFor(first);
            fail("Both link directions must be verified even on a warm index");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("chain mismatch")); }
    }

    @Test
    public void splitDiscovery_replacedNextHeaderIdIsRejected() throws Exception {
        writeSplitPair("replaced-id", buildRepeatingPayload(6000));
        File first = new File(tempFolder.getRoot(), "replaced-id.vol1.egg");
        File second = new File(tempFolder.getRoot(), "replaced-id.vol2.egg");
        byte[] bytes = Files.readAllBytes(second.toPath());
        bytes[6] ^= 1; // Header id; previous-volume id is deliberately unchanged.
        Files.write(second.toPath(), bytes);
        try {
            EggArchiveReader.indexFor(first);
            fail("The next member must have the advertised header id");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("chain mismatch")); }
    }

    @Test
    public void splitDiscovery_validPaddingChangeInvalidatesIndexWithoutBreakingPayload() throws Exception {
        writeSplitPair("padding-change", buildRepeatingPayload(6000));
        File first = new File(tempFolder.getRoot(), "padding-change.vol1.egg");
        EggArchiveReader.Index old = EggArchiveReader.indexFor(first);
        assertTrue(new File(tempFolder.getRoot(), "padding-change.vol2.egg").renameTo(
                new File(tempFolder.getRoot(), "padding-change.vol0002.egg")));
        org.junit.Assert.assertNotSame(old, EggArchiveReader.indexFor(first));
        File out = tempFolder.newFile("padding-change.out");
        assertTrue(EggArchiveReader.extractSingleEntry(first, "data.txt", out, null));
        org.junit.Assert.assertArrayEquals(buildRepeatingPayload(6000), Files.readAllBytes(out.toPath()));
    }

    @Test
    public void splitDiscovery_invalidOrOverflowingSelectedOrdinalFails() throws Exception {
        for (String number : new String[] {"0", "9223372036854775808"}) {
            try {
                EggArchiveReader.resolveFirstVolume(new File(tempFolder.getRoot(), "invalid.vol" + number + ".egg"));
                fail("Invalid ordinals must not alias volume 1");
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("Invalid")); }
        }
    }

    @Test
    public void splitDiscovery_leadingZeroesDoNotOverflowNumericIdentity() throws Exception {
        writeSplitPair("leading-zero", buildRepeatingPayload(6000));
        File first = new File(tempFolder.getRoot(), "leading-zero.vol00000000000000000000001.egg");
        File second = new File(tempFolder.getRoot(), "leading-zero.vol00000000000000000000002.egg");
        assertTrue(new File(tempFolder.getRoot(), "leading-zero.vol1.egg").renameTo(first));
        assertTrue(new File(tempFolder.getRoot(), "leading-zero.vol2.egg").renameTo(second));
        assertEquals(first.getCanonicalFile(), EggArchiveReader.resolveFirstVolume(second).getCanonicalFile());
        File out = tempFolder.newFile("leading-zero.out");
        assertTrue(EggArchiveReader.extractSingleEntry(first, "data.txt", out, null));
    }

    @Test
    public void index_reusesMetadataAcrossListingProbesAndReverseExtraction() throws Exception {
        EggArchiveReader.clearIndexes();
        File first = buildEggArchive("first.txt", new byte[] {1}, 0, false);
        File second = buildEggArchive("second.txt", new byte[] {2, 3}, 1, false);
        appendIndexedEntries(first, second);
        EggArchiveReader.Index index = EggArchiveReader.indexFor(first);
        assertEquals(2, EggArchiveReader.listEntries(first, null).size());
        assertFalse(EggArchiveReader.requiresPasswordForExtraction(first));
        assertFalse(EggArchiveReader.isSolidArchive(first));
        File out = tempFolder.newFile("index-reverse.txt");
        assertTrue(EggArchiveReader.extractSingleEntry(first, "second.txt", out, null));
        org.junit.Assert.assertArrayEquals(new byte[] {2, 3}, Files.readAllBytes(out.toPath()));
        assertTrue(EggArchiveReader.extractSingleEntry(first, "first.txt", out, null));
        org.junit.Assert.assertArrayEquals(new byte[] {1}, Files.readAllBytes(out.toPath()));
        org.junit.Assert.assertSame(index, EggArchiveReader.indexFor(first));
    }

    @Test
    public void index_directoryClassificationFollowsDecodedName() throws Exception {
        File archive = buildEggArchive("folder/", new byte[0], 0, false);
        assertTrue(EggArchiveReader.listEntries(archive, null).get(0).directory);
        File out = new File(tempFolder.getRoot(), "not-a-file.txt");
        assertFalse(EggArchiveReader.extractSingleEntry(archive, "folder/", out, null));
        assertFalse(out.exists());
    }

    @Test
    public void index_duplicateNameUsesFirstNonDirectoryEntry() throws Exception {
        File first = buildEggArchive("same.txt", new byte[] {1}, 0, false);
        appendIndexedEntries(first, buildEggArchive("same.txt", new byte[] {2}, 0, false));
        File out = tempFolder.newFile("index-duplicate.txt");
        assertTrue(EggArchiveReader.extractSingleEntry(first, "same.txt", out, null));
        org.junit.Assert.assertArrayEquals(new byte[] {1}, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void index_rebuildsAfterFirstVolumeGrows() throws Exception {
        File archive = buildEggArchive("first.txt", new byte[] {1}, 0, false);
        EggArchiveReader.Index old = EggArchiveReader.indexFor(archive);
        appendIndexedEntries(archive, buildEggArchive("second.txt", new byte[] {2}, 0, false));
        org.junit.Assert.assertNotSame(old, EggArchiveReader.indexFor(archive));
        assertEquals(2, EggArchiveReader.listEntries(archive, null).size());
    }

    @Test
    public void index_laterVolumeChangesInvalidateAndContinuationReleaseWorks() throws Exception {
        writeSplitPair("index-split", buildRepeatingPayload(6000));
        File first = new File(tempFolder.getRoot(), "index-split.vol1.egg");
        File second = new File(tempFolder.getRoot(), "index-split.vol2.egg");
        EggArchiveReader.Index old = EggArchiveReader.indexFor(first);
        assertTrue(second.setLastModified(second.lastModified() + 10000));
        EggArchiveReader.Index changed = EggArchiveReader.indexFor(first);
        org.junit.Assert.assertNotSame(old, changed);
        EggArchiveReader.releaseArchiveIndex(second);
        org.junit.Assert.assertNotSame(changed, EggArchiveReader.indexFor(first));
        File out = tempFolder.newFile("index-split.out");
        assertTrue(EggArchiveReader.extractSingleEntry(first, "data.txt", out, null));
        org.junit.Assert.assertArrayEquals(buildRepeatingPayload(6000), Files.readAllBytes(out.toPath()));
    }

    @Test
    public void index_warmCacheDoesNotBypassMissingVolume() throws Exception {
        writeSplitPair("index-missing", buildRepeatingPayload(6000));
        File first = new File(tempFolder.getRoot(), "index-missing.vol1.egg");
        EggArchiveReader.indexFor(first);
        assertTrue(new File(tempFolder.getRoot(), "index-missing.vol2.egg").delete());
        File out = new File(tempFolder.getRoot(), "missing-index.out");
        try {
            EggArchiveReader.extractSingleEntry(first, "data.txt", out, null);
            fail("Missing split volume must fail on a warm index");
        } catch (IOException expected) { assertFalse(out.exists()); }
    }

    @Test
    public void index_warmZipCryptoStillChecksEveryPasswordAndKeepsExistingTarget() throws Exception {
        File archive = buildTwoBlockEncryptedStoreArchive("multi.txt", buildRepeatingPayload(4000));
        File out = tempFolder.newFile("index-crypto.out");
        for (int i = 0; i < 2; i++) {
            assertTrue(EggArchiveReader.extractSingleEntry(archive, "multi.txt", out, TEST_PASSWORD.toCharArray()));
            org.junit.Assert.assertArrayEquals(buildRepeatingPayload(4000), Files.readAllBytes(out.toPath()));
        }
        EggArchiveReader.Index index = EggArchiveReader.indexFor(archive);
        for (char[] password : new char[][] {null, "wrong".toCharArray()}) {
            try {
                EggArchiveReader.extractSingleEntry(archive, "multi.txt", out, password);
                fail("Successful metadata lookup is not password authorization");
            } catch (IOException expected) {
                org.junit.Assert.assertArrayEquals(buildRepeatingPayload(4000), Files.readAllBytes(out.toPath()));
            }
        }
        org.junit.Assert.assertSame(index, EggArchiveReader.indexFor(archive));
    }

    @Test
    public void index_warmAesUsesFreshDecryptorAndFooterVerification() throws Exception {
        File archive = writeFixture("index-aes.egg", AES256_DEFLATE_B64);
        File out = tempFolder.newFile("index-aes.out");
        for (int i = 0; i < 2; i++) {
            assertTrue(EggArchiveReader.extractSingleEntry(archive, "secret.txt", out, AES_PASSWORD.toCharArray()));
            org.junit.Assert.assertArrayEquals(aesDeflatePayload(), Files.readAllBytes(out.toPath()));
        }
        try {
            EggArchiveReader.extractSingleEntry(archive, "secret.txt", out, "wrong".toCharArray());
            fail("Warm AES lookup must still reject the wrong password");
        } catch (IOException expected) {
            org.junit.Assert.assertArrayEquals(aesDeflatePayload(), Files.readAllBytes(out.toPath()));
        }
    }

    @Test
    public void index_warmHitStillChecksBlockCrcAndRollsBackOutput() throws Exception {
        File archive = buildEggArchive("data.txt", new byte[] {1, 2, 3}, 0, false);
        EggArchiveReader.Index index = EggArchiveReader.indexFor(archive);
        long modified = archive.lastModified();
        byte[] bytes = Files.readAllBytes(archive.toPath());
        bytes[bytes.length - 5] ^= 1;
        Files.write(archive.toPath(), bytes);
        assertTrue(archive.setLastModified(modified));
        org.junit.Assert.assertSame(index, EggArchiveReader.indexFor(archive));
        File out = tempFolder.newFile("index-crc.out");
        Files.write(out.toPath(), new byte[] {9});
        try {
            EggArchiveReader.extractSingleEntry(archive, "data.txt", out, null);
            fail("Warm index must not bypass CRC");
        } catch (IOException expected) {
            org.junit.Assert.assertArrayEquals(new byte[] {9}, Files.readAllBytes(out.toPath()));
        }
    }

    @Test
    public void index_solidMetadataIsNotRetainedAsRandomAccessIndex() throws Exception {
        File archive = buildSolidEggArchive(new String[] {"a.txt", "b.txt"},
                new byte[][] {new byte[] {1}, new byte[] {2}}, 0, 1);
        EggArchiveReader.Index first = EggArchiveReader.indexFor(archive);
        org.junit.Assert.assertNotSame(first, EggArchiveReader.indexFor(archive));
        File out = tempFolder.newFile("index-solid.out");
        assertTrue(EggArchiveReader.extractSingleEntry(archive, "b.txt", out, null));
        org.junit.Assert.assertArrayEquals(new byte[] {2}, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void index_lruEvictsOldestNotRecentlyUsedArchive() throws Exception {
        EggArchiveReader.clearIndexes();
        File[] archives = new File[4];
        EggArchiveReader.Index[] indexes = new EggArchiveReader.Index[3];
        for (int i = 0; i < 4; i++) {
            archives[i] = buildEggArchive("page.txt", new byte[] {(byte) i}, 0, false);
            if (i < 3) indexes[i] = EggArchiveReader.indexFor(archives[i]);
        }
        org.junit.Assert.assertSame(indexes[0], EggArchiveReader.indexFor(archives[0]));
        EggArchiveReader.indexFor(archives[3]);
        org.junit.Assert.assertSame(indexes[0], EggArchiveReader.indexFor(archives[0]));
        org.junit.Assert.assertNotSame(indexes[1], EggArchiveReader.indexFor(archives[1]));
    }

    @Test
    public void index_interruptedWarmLookupRejectsWithoutClearingFlag() throws Exception {
        File archive = buildEggArchive("page.txt", new byte[] {1}, 0, false);
        EggArchiveReader.indexFor(archive);
        Thread.currentThread().interrupt();
        try {
            EggArchiveReader.indexFor(archive);
            fail("Interrupted lookup must fail");
        } catch (IOException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }

    @Test
    public void index_blockAdmissionBudgetDoesNotRejectExtraction() throws Exception {
        File archive = buildEggArchive("empty.txt", new byte[0], 0, false);
        byte[] template = Files.readAllBytes(archive.toPath());
        int blockStart = template.length - 4 - 22;
        ByteArrayOutputStream many = new ByteArrayOutputStream();
        many.write(template, 0, blockStart);
        for (int i = 0; i < 40001; i++) many.write(template, blockStart, 22);
        many.write(template, template.length - 4, 4);
        Files.write(archive.toPath(), many.toByteArray());
        EggArchiveReader.Index first = EggArchiveReader.indexFor(archive);
        org.junit.Assert.assertNotSame(first, EggArchiveReader.indexFor(archive));
        File out = tempFolder.newFile("index-many-blocks.out");
        assertTrue(EggArchiveReader.extractSingleEntry(archive, "empty.txt", out, null));
        assertEquals(0, out.length());
    }

    @Test
    public void index_warmLookupRevalidatesSplitLinkEvenWithPreservedStats() throws Exception {
        writeSplitPair("index-link", buildRepeatingPayload(6000));
        File first = new File(tempFolder.getRoot(), "index-link.vol1.egg");
        File second = new File(tempFolder.getRoot(), "index-link.vol2.egg");
        EggArchiveReader.indexFor(first);
        long modified = second.lastModified();
        byte[] bytes = Files.readAllBytes(second.toPath());
        bytes[21] ^= 1; // Split extra's previous-volume id, after its magic/flags/size.
        Files.write(second.toPath(), bytes);
        assertTrue(second.setLastModified(modified));
        try {
            EggArchiveReader.indexFor(first);
            fail("A cached index must not bypass split-prefix link validation");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("chain mismatch")); }
    }

    @Test
    public void index_keepsNoOpenArchiveHandle() throws Exception {
        File archive = buildEggArchive("page.txt", new byte[] {1}, 0, false);
        EggArchiveReader.indexFor(archive);
        File renamed = new File(tempFolder.getRoot(), "renamed-index.egg");
        assertTrue(archive.renameTo(renamed));
        assertEquals(1, EggArchiveReader.listEntries(renamed, null).size());
    }

    private static void appendIndexedEntries(File first, File second) throws IOException {
        byte[] a = Files.readAllBytes(first.toPath());
        byte[] b = Files.readAllBytes(second.toPath());
        ByteArrayOutputStream merged = new ByteArrayOutputStream();
        merged.write(a, 0, a.length - 4);
        merged.write(b, 18, b.length - 18); // Skip ordinary 14-byte header and prefix END.
        Files.write(first.toPath(), merged.toByteArray());
    }

    @Test public void solidForwardReaderReusesVerifiedBlockAndCrossesBoundaries() throws Exception {
        byte[][] payloads = {new byte[]{1}, new byte[]{2, 3}, buildRepeatingPayload(6000)};
        File archive = buildSolidEggArchive(new String[]{"a.jpg", "b.jpg", "c.jpg"}, payloads, 1, 2);
        File spool = tempFolder.newFolder("forward-spool");
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null, spool)) {
            org.junit.Assert.assertNotNull(reader);
            assertEquals("a.jpg", reader.nextEntry().path);
            org.junit.Assert.assertArrayEquals(payloads[0], readForwardEntry(reader));
            File[] firstBlock = spool.listFiles();
            assertEquals(1, firstBlock.length);
            assertEquals("b.jpg", reader.nextEntry().path);
            org.junit.Assert.assertArrayEquals(payloads[1], readForwardEntry(reader));
            assertEquals(firstBlock[0], spool.listFiles()[0]);
            assertEquals("c.jpg", reader.nextEntry().path);
            org.junit.Assert.assertArrayEquals(payloads[2], readForwardEntry(reader));
            org.junit.Assert.assertNull(reader.nextEntry());
            assertEquals(0, spool.listFiles().length);
        }
        assertEquals(0, spool.listFiles().length);
    }

    @Test public void solidForwardReaderDrainsSkippedEntryAndDeletesSpoolOnClose() throws Exception {
        File archive = buildSolidEggArchive(new String[]{"skip.bin", "page.jpg"},
                new byte[][]{buildRepeatingPayload(3000), new byte[]{8, 9}}, 0, 2);
        File spool = tempFolder.newFolder("skip-spool");
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null, spool)) {
            assertEquals("skip.bin", reader.nextEntry().path);
            assertEquals("page.jpg", reader.nextEntry().path);
            org.junit.Assert.assertArrayEquals(new byte[]{8, 9}, readForwardEntry(reader));
        }
        assertEquals(0, spool.listFiles().length);
    }

    @Test public void solidEntryEndingInsideCorruptBlockCannotBePublished() throws Exception {
        File archive = buildSolidEggArchive(new String[]{"first.jpg", "last.bin"},
                new byte[][]{new byte[]{1}, new byte[]{2, 3, 4}}, 0, 1);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        bytes[bytes.length - 5] ^= 1; // Corrupt the last stored byte, not first.jpg.
        Files.write(archive.toPath(), bytes);
        File output = new File(tempFolder.getRoot(), "bad-first.jpg");
        assertFalse(ArchiveSupport.extractSingleEntry(archive, "first.jpg", output, null));
        assertFalse(output.exists());
        File spool = tempFolder.newFolder("bad-spool");
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null, spool)) {
            reader.nextEntry();
            try { reader.read(new byte[1]); fail("CRC must fail before the first byte is exposed"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("CRC")); }
        }
        assertEquals(0, spool.listFiles().length);
    }

    @Test public void solidForwardSpoolHonorsSharedBudgetAndCleansFailedOutput() throws Exception {
        File archive = buildSolidEggArchive(new String[]{"first.jpg", "last.bin"},
                new byte[][]{new byte[]{1}, new byte[]{2, 3, 4}}, 0, 1);
        File spool = tempFolder.newFolder("budget-spool");
        try (ArchiveExtractionByteBudget.Scope ignored = ArchiveExtractionByteBudget.begin(2);
             ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null, spool)) {
            reader.nextEntry();
            try { reader.read(new byte[1]); fail("Spool must use the extraction budget"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("safety limit")); }
        }
        assertEquals(0, spool.listFiles().length);
    }

    @Test public void solidForwardInterruptionClosesOwnedResourcesImmediately() throws Exception {
        File archive = buildSolidEggArchive(new String[]{"page.jpg"},
                new byte[][]{new byte[]{1, 2, 3}}, 0, 1);
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null, spool)) {
            reader.nextEntry();
            assertEquals(1, reader.read(new byte[1]));
            assertEquals(1, spool.listFiles().length);
            Thread.currentThread().interrupt();
            try { reader.read(new byte[1]); fail("Interrupted reads must retire the session"); }
            catch (IOException expected) { }
            finally { Thread.interrupted(); }
            assertEquals(0, spool.listFiles().length);
            assertRetiredForward(reader);
        }
    }

    @Test public void solidForwardTruncatedSpoolRetiresSession() throws Exception {
        File archive = buildSolidEggArchive(new String[]{"page.jpg"},
                new byte[][]{new byte[]{1, 2, 3}}, 0, 1);
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null, spool)) {
            reader.nextEntry();
            reader.read(new byte[1]);
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(spool.listFiles()[0], "rw")) {
                file.setLength(0);
            }
            try { reader.read(new byte[1]); fail("Truncated spool must fail"); }
            catch (IOException expected) { }
            assertEquals(0, spool.listFiles().length);
            assertRetiredForward(reader);
        }
    }

    @Test public void solidForwardNegativeDrainBoundRetiresSession() throws Exception {
        File archive = buildSolidEggArchive(new String[]{"page.jpg"},
                new byte[][]{new byte[]{1, 2, 3}}, 0, 1);
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null, spool)) {
            reader.nextEntry();
            try { reader.drainCurrentEntry(-1); fail("Negative drain budget must fail"); }
            catch (IOException expected) { }
            assertEquals(0, spool.listFiles().length);
            assertRetiredForward(reader);
        }
    }

    @Test public void solidForwardCrcFailureCannotBeRetriedOnSameReader() throws Exception {
        File archive = buildSolidEggArchive(new String[]{"page.jpg"},
                new byte[][]{new byte[]{1, 2, 3}}, 0, 1);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        bytes[bytes.length - 5] ^= 1;
        Files.write(archive.toPath(), bytes);
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null, spool)) {
            reader.nextEntry();
            byte[] unchanged = new byte[]{42};
            try { reader.read(unchanged); fail("CRC must precede publication"); }
            catch (IOException expected) { }
            org.junit.Assert.assertArrayEquals(new byte[]{42}, unchanged);
            assertEquals(0, spool.listFiles().length);
            assertRetiredForward(reader);
        }
    }

    @Test public void solidForwardShorteningToCursorCannotSpliceNextBlockIntoImage() throws Exception {
        assertChangedSolidBlockFails(false, false);
    }

    @Test public void solidForwardDrainRejectsShorteningToCursor() throws Exception {
        assertChangedSolidBlockFails(true, false);
    }

    @Test public void solidForwardRejectsGrowthOfVerifiedBlock() throws Exception {
        assertChangedSolidBlockFails(false, true);
    }

    private void assertChangedSolidBlockFails(boolean drain, boolean grow) throws Exception {
        File archive = buildSolidEggArchive(new String[]{"page.jpg", "later.jpg"},
                new byte[][]{new byte[]{1, 2}, new byte[]{3, 4, 5, 6, 7, 8}}, 0, 2);
        File directory = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = EggArchiveReader.openSolidForwardReader(archive, directory)) {
            assertEquals("page.jpg", reader.nextEntry().path);
            assertEquals(1, reader.read(new byte[1])); // First block holds bytes 1..4; cursor is now 1.
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(directory.listFiles()[0], "rw")) {
                file.setLength(grow ? 5 : 1);
            }
            byte[] unchanged = {42};
            try {
                if (drain) reader.drainCurrentEntry(Long.MAX_VALUE);
                else reader.read(unchanged);
                fail("Changed block must fail before advancing to the next block");
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("spool")); }
            org.junit.Assert.assertArrayEquals(new byte[]{42}, unchanged);
            assertRetiredForward(reader);
        }
        assertEquals(0, directory.list().length);
    }

    @Test public void solidForwardCloseRetriesFailedSpoolDeletion() throws Exception {
        assertSolidCleanupRetry(false);
    }

    @Test public void solidForwardPreservesCloseFailureWhenDeletionAlsoFails() throws Exception {
        assertSolidCleanupRetry(true);
    }

    private void assertSolidCleanupRetry(boolean failHandleClose) throws Exception {
        File archive = buildSolidEggArchive(new String[]{"page.jpg"}, new byte[][]{new byte[]{1, 2, 3}}, 0, 1);
        File directory = tempFolder.newFolder();
        ArchiveSupport.ForwardArchiveReader reader = EggArchiveReader.openSolidForwardReader(archive, directory);
        java.lang.reflect.Field pathField = reader.getClass().getDeclaredField("spoolFile");
        java.lang.reflect.Field handleField = reader.getClass().getDeclaredField("spool");
        pathField.setAccessible(true);
        handleField.setAccessible(true);
        File original = null;
        try {
            reader.nextEntry();
            assertEquals(1, reader.read(new byte[1]));
            original = (File) pathField.get(reader);
            pathField.set(reader, new File(original.getAbsolutePath()) {
                @Override public boolean delete() { return false; }
            });
            if (failHandleClose) {
                ((java.io.RandomAccessFile) handleField.get(reader)).close();
                handleField.set(reader, new java.io.RandomAccessFile(original, "r") {
                    @Override public void close() throws IOException {
                        super.close();
                        throw new IOException("Injected handle-close failure");
                    }
                });
            }
            try { reader.close(); fail("Cleanup failure must be reported"); }
            catch (IOException expected) {
                if (failHandleClose) {
                    assertEquals("Injected handle-close failure", expected.getMessage());
                    assertEquals(1, expected.getSuppressed().length);
                    assertTrue(expected.getSuppressed()[0].getMessage().contains("spool"));
                } else assertTrue(expected.getMessage().contains("spool"));
            }
            assertTrue(original.exists());
            org.junit.Assert.assertNotNull(pathField.get(reader));
            pathField.set(reader, original);
            reader.close();
            reader.close();
            assertFalse(original.exists());
            assertRetiredForward(reader);
        } finally {
            if (original != null && original.exists()) pathField.set(reader, original);
            reader.close();
        }
        assertEquals(0, directory.list().length);
    }

    private static void assertRetiredForward(ArchiveSupport.ForwardArchiveReader reader) throws Exception {
        try { reader.read(new byte[1]); fail("Failed reader must reject reads"); }
        catch (IOException expected) { }
        try { reader.nextEntry(); fail("Failed reader must reject advance"); }
        catch (IOException expected) { }
        try { reader.drainCurrentEntry(100); fail("Failed reader must reject drains"); }
        catch (IOException expected) { }
    }

    @Test public void nonSolidEggKeepsDirectEntryRoute() throws Exception {
        File archive = buildEggArchive("page.jpg", new byte[]{1, 2, 3}, 0, false);
        assertFalse(ArchiveSupport.isForwardImageReadableType(archive));
        org.junit.Assert.assertNull(ArchiveSupport.openForwardReader(archive, null,
                tempFolder.newFolder("non-solid-spool")));
    }

    private static byte[] readForwardEntry(ArchiveSupport.ForwardArchiveReader reader) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[97];
        int count;
        while ((count = reader.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }

    @Test
    public void streamingMethodsAcceptBlockSizesAboveFormer512MiBLimit() throws Exception {
        for (int method : new int[] {0, 1, 2, 4}) {
            EggArchiveReader.validateBlockSizes(method, 513L * 1024 * 1024, 513L * 1024 * 1024);
            EggArchiveReader.validateBlockSizes(method, 0xffffffffL, 0xffffffffL);
        }
        assertEquals(6L * 1024 * 1024 * 1024,
                EggArchiveReader.addEntryBytes(3L * 1024 * 1024 * 1024, 3L * 1024 * 1024 * 1024));
    }

    @Test
    public void azoRetainsItsPerBlockMemoryGuard() throws Exception {
        long limit = 512L * 1024 * 1024;
        EggArchiveReader.validateBlockSizes(3, limit, limit);
        for (long[] sizes : new long[][] {{limit + 1, 1}, {1, limit + 1}}) {
            try {
                EggArchiveReader.validateBlockSizes(3, sizes[0], sizes[1]);
                fail("Array-based AZO must retain its memory guard");
            } catch (ArchiveSupport.UnsupportedArchiveFeatureException expected) {
                assertTrue(expected.getMessage().contains("AZO"));
            }
        }
    }

    @Test
    public void negativeSizesAndSolidOffsetOverflowAreRejected() throws Exception {
        for (long[] sizes : new long[][] {{-1, 1}, {1, -1}}) {
            try { EggArchiveReader.validateBlockSizes(0, sizes[0], sizes[1]); fail("Negative size"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("negative")); }
        }
        for (long[] sizes : new long[][] {{Long.MAX_VALUE, 1}, {-1, 1}, {1, -1}}) {
            try { EggArchiveReader.addEntryBytes(sizes[0], sizes[1]); fail("Invalid offset"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("overflow")); }
        }
    }

    @Test
    public void storedAndDeflateBlocksMustProduceExactlyTheirDeclaredSize() throws Exception {
        byte[] actual = "actual".getBytes(StandardCharsets.UTF_8);
        for (int method : new int[] {0, 1}) {
            for (int declared : new int[] {actual.length - 1, actual.length + 1}) {
                byte[] packed = method == 0 ? actual : rawDeflate(actual);
                File archive = buildEggArchiveWithStoredPayload(
                        "size.txt", new byte[declared], method, packed, false);
                File output = new File(tempFolder.getRoot(), "size-" + method + "-" + declared + ".txt");
                try {
                    EggArchiveReader.extractSingleEntry(archive, "size.txt", output, null);
                    fail("Declared size mismatch must fail");
                } catch (IOException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage().contains("declared unpacked size"));
                    assertFalse("Failed output must be removed", output.exists());
                }
            }
        }
    }

    @Test
    public void azoZeroSizeDeclarationCannotAcceptNonemptyFramedOutput() throws Exception {
        byte[] actual = "azo".getBytes(StandardCharsets.UTF_8);
        File archive = buildEggArchiveWithStoredPayload(
                "zero-azo", new byte[0], 3, buildAzoStoredStream(actual), false);
        File output = new File(tempFolder.getRoot(), "bad-zero-azo");
        try {
            EggArchiveReader.extractSingleEntry(archive, "zero-azo", output, null);
            fail("Nonempty AZO output cannot satisfy a zero-size declaration");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("declared unpacked size"));
            assertFalse(output.exists());
        }
    }

    @Test
    public void nonSolidFileSizeMustMatchItsBlockSizesBeforeWriting() throws Exception {
        File archive = buildEggArchive("size.txt", new byte[] {1, 2, 3}, 0, false);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        // Prefix is 18 bytes; FILE signature/id precede its u64 size at byte 26.
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(26, 4L);
        Files.write(archive.toPath(), bytes);
        File output = new File(tempFolder.getRoot(), "bad-file-size.txt");
        try {
            EggArchiveReader.extractSingleEntry(archive, "size.txt", output, null);
            fail("Inconsistent file metadata must fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("declared entry size"));
            assertFalse(output.exists());
        }
    }

    @Test
    public void solidRangeRejectsOverflowBeforeWritingTarget() throws Exception {
        byte[] bytes = buildSolidEggBytesWithStream(
                new String[] {"first", "second", "target"},
                new long[] {Long.MAX_VALUE, 1, 1}, new byte[] {1}, 0);
        File archive = tempFolder.newFile("overflow.egg");
        Files.write(archive.toPath(), bytes);
        File output = new File(tempFolder.getRoot(), "overflow-target");
        try {
            EggArchiveReader.extractSingleEntry(archive, "target", output, null);
            fail("Solid offset overflow must fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("overflow"));
            assertFalse(output.exists());
        }
    }

    @Test
    public void largeDeflateEntryCrossesFormer512MiBLimitWhenEnabled() throws Exception {
        org.junit.Assume.assumeTrue("Opt in to a large disk-output regression",
                Boolean.getBoolean("readwide.largeArchiveTests"));
        long size = 513L * 1024 * 1024;
        byte[] chunk = new byte[64 * 1024];
        java.util.Arrays.fill(chunk, (byte) 'E');
        CRC32 crc = new CRC32();
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        Deflater compressor = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(packed, compressor)) {
            for (long written = 0; written < size; written += chunk.length) {
                stream.write(chunk);
                crc.update(chunk);
            }
        } finally { compressor.end(); }

        File archive = tempFolder.newFile("large-stream.egg");
        try (OutputStream out = new FileOutputStream(archive)) {
            writeIntLE(out, MAGIC_EGG); writeShortLE(out, 0x0100);
            writeIntLE(out, 0x11111111); writeIntLE(out, 0); writeIntLE(out, MAGIC_END);
            writeIntLE(out, MAGIC_FILE); writeIntLE(out, 0); writeLongLE(out, size);
            byte[] name = "large.bin".getBytes(StandardCharsets.UTF_8);
            writeIntLE(out, MAGIC_FILENAME); out.write(0); writeShortLE(out, name.length); out.write(name);
            writeIntLE(out, MAGIC_END);
            writeIntLE(out, MAGIC_BLOCK); out.write(1); out.write(0);
            writeIntLE(out, (int) size); writeIntLE(out, packed.size());
            writeIntLE(out, (int) crc.getValue()); writeIntLE(out, MAGIC_END);
            packed.writeTo(out); writeIntLE(out, MAGIC_END);
        }
        File output = new File(tempFolder.getRoot(), "large-decoded.bin");
        assertTrue(EggArchiveReader.extractSingleEntry(archive, "large.bin", output, null));
        assertEquals(size, output.length());
        CRC32 decodedCrc = new CRC32();
        try (java.io.InputStream input = Files.newInputStream(output.toPath())) {
            int count;
            while ((count = input.read(chunk)) != -1) decodedCrc.update(chunk, 0, count);
        }
        assertEquals(crc.getValue(), decodedCrc.getValue());
    }

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
