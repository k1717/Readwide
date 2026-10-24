package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.lingala.zip4j.crypto.AESEncrypter;
import net.lingala.zip4j.model.enums.AesKeyStrength;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Byte-exact fixtures for WinZip AES combined with extended ZIPX methods. */
public class ZipxAesArchiveReaderTest {
    @Test public void lzmaPropertiesPermitDictionaryAboveOld64MiBQuotaWithoutAllocation() throws Exception {
        byte[] properties = {0x5d, 0, 0, 0, 8}; // 128 MiB declared dictionary.
        assertEquals(128 * 1024 * 1024,
                ZipxAesArchiveReader.lzmaDictionarySize(properties, 256L * 1024 * 1024));
        assertEquals(4096, ZipxAesArchiveReader.lzmaDictionarySize(properties, 4096));
    }

    @Test public void lzmaPropertiesStillRejectMalformedMetadata() {
        org.junit.Assert.assertThrows(IOException.class,
                () -> ZipxAesArchiveReader.lzmaDictionarySize(new byte[] {-1, 0, 0, 0, 8}, 1));
        org.junit.Assert.assertThrows(IOException.class,
                () -> ZipxAesArchiveReader.lzmaDictionarySize(new byte[4], 1));
        org.junit.Assert.assertThrows(IOException.class,
                () -> ZipxAesArchiveReader.lzmaDictionarySize(new byte[5], -1));
    }

    private static final char[] PASSWORD = "readwide-zipx".toCharArray();
    private static final byte[] CONTENT = buildContent();

    @Test public void concatenatedAesBzipStreamsDecodeCompletely() throws Exception {
        runSuccessMatrix(buildAesZipx("bzip-concat.zipx",12,false,0,BzipArchiveCompatibilityTest.concatenated(CONTENT)));
    }
    @Test public void concatenatedAesBzipCrossesSplitVolume() throws Exception {
        byte[] packed = BzipArchiveCompatibilityTest.concatenated(CONTENT);
        File archive = buildAesZipx("bzip-concat-split.zipx", 12, false, 0, packed);
        int central = findSignature(Files.readAllBytes(archive.toPath()), 0x02014b50);
        // splitZipx prefixes four bytes; exclude the final ten-byte AES MAC.
        int encryptedStart = 4 + central - 10 - packed.length;
        int cut = encryptedStart + packed.length / 2;
        assertTrue(cut > encryptedStart && cut < 4 + central - 10);
        splitSuccess(splitZipx(archive, cut), "folder/page.txt", PASSWORD);
    }
    @Test public void concatenatedTraditionalBzipCrossesSplitAndDescriptor() throws Exception {
        File archive=traditionalArchive(12,true,PASSWORD,BzipArchiveCompatibilityTest.concatenated(CONTENT));
        splitSuccess(splitZipx(archive,20),"traditional.bin",PASSWORD);
    }
    @Test public void concatenatedPlainBzipUsesPublicAndSharedWriterRoutes() throws Exception {
        File archive=plainStreamArchive(12,false,BzipArchiveCompatibilityTest.concatenated(CONTENT));
        File output=temp.newFile();
        assertEquals(Boolean.TRUE,ZipxAesArchiveReader.tryExtractSingleEntry(archive,"plain.bin",output,null));
        assertArrayEquals(CONTENT,Files.readAllBytes(output.toPath()));
        File directory=new File(temp.newFolder(),"out");
        ArchiveSupport.ExtractionResult all=ArchiveSupport.extractArchiveDetailed(archive,directory,true,null,null);
        assertTrue(all.failure+": "+all.detail,all.success);
        assertArrayEquals(CONTENT,Files.readAllBytes(new File(directory,"plain.bin").toPath()));
        try(org.apache.commons.compress.archivers.zip.ZipFile zip=
                    org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(archive).get()) {
            assertTrue(ZipxAesArchiveReader.extractPlainEntry(zip,zip.getEntry("plain.bin"),output,null));
            assertArrayEquals(CONTENT,Files.readAllBytes(output.toPath()));
        }
    }
    @Test public void badLaterBzipStreamAndTrailingGarbageRestoreTargets() throws Exception {
        byte[] packed=BzipArchiveCompatibilityTest.concatenated(CONTENT);
        byte[] corrupt=packed.clone();corrupt[corrupt.length-2]^=0x40;
        byte[] truncated=java.util.Arrays.copyOf(packed,packed.length-5);
        byte[] junk=java.util.Arrays.copyOf(packed,packed.length+1);junk[junk.length-1]=1;
        for(byte[] bad:new byte[][]{corrupt,truncated,junk}) {
            assertPlainStreamFailurePreservesOutput(plainStreamArchive(12,false,bad));
        }
    }
    @Test public void concatenatedBzipAuthenticationFailureRestoresTarget() throws Exception {
        File archive=buildAesZipx("bzip-concat-auth.zipx",12,false,0,BzipArchiveCompatibilityTest.concatenated(CONTENT));
        byte[] bytes=Files.readAllBytes(archive.toPath());bytes[findSignature(bytes,0x02014b50)-1]^=1;
        Files.write(archive.toPath(),bytes);File output=temp.newFile();Files.write(output.toPath(),new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class,()->
                ZipxAesArchiveReader.tryExtractSingleEntry(archive,"folder/page.txt",output,PASSWORD));
        assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
    }

    @Test public void concatenatedAesXzStreamsIncludeEmptyStreamsAndPadding() throws Exception {
        runSuccessMatrix(buildAesZipx("xz-concat.zipx",95,false,0,concatenatedXz()));
    }

    @Test public void concatenatedTraditionalXzWithDescriptorExtracts() throws Exception {
        File archive=traditionalArchive(95,true,PASSWORD,concatenatedXz()), output=temp.newFile();
        assertEquals(Boolean.TRUE,ZipxAesArchiveReader.tryExtractSingleEntry(archive,"traditional.bin",output,PASSWORD));
        assertArrayEquals(CONTENT,Files.readAllBytes(output.toPath()));
        File directory=temp.newFolder();
        assertTrue(ZipxAesArchiveReader.extractArchiveIntoDirectory(archive,directory,PASSWORD,null,null));
        assertArrayEquals(CONTENT,Files.readAllBytes(new File(directory,"traditional.bin").toPath()));
    }

    @Test public void concatenatedPlainXzUsesPublicExtractionRoute() throws Exception {
        File archive=plainStreamArchive(95,false,concatenatedXz()), output=temp.newFile();
        assertEquals(Boolean.TRUE,ZipxAesArchiveReader.tryExtractSingleEntry(archive,"plain.bin",output,null));
        assertArrayEquals(CONTENT,Files.readAllBytes(output.toPath()));
        File directory=new File(temp.newFolder(),"out");
        ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(archive,directory,true,null,null);
        assertTrue(result.failure+": "+result.detail,result.success);
        assertArrayEquals(CONTENT,Files.readAllBytes(new File(directory,"plain.bin").toPath()));
    }

    @Test public void concatenatedAesXzCrossesSplitVolume() throws Exception {
        File archive=buildAesZipx("xz-concat-split.zipx",95,false,0,concatenatedXz());
        splitSuccess(splitZipx(archive,20),"folder/page.txt",PASSWORD);
    }

    @Test public void brokenLaterXzStreamAndInvalidPaddingRestoreExistingOutput() throws Exception {
        byte[] valid=concatenatedXz();
        byte[] corrupt=valid.clone();corrupt[corrupt.length-7]^=1; // Final stream footer, before four padding bytes.
        byte[] truncated=java.util.Arrays.copyOf(valid,valid.length-9);
        byte[] badPadding=java.util.Arrays.copyOf(valid,valid.length+1);
        byte[] junk=java.util.Arrays.copyOf(valid,valid.length+4);junk[junk.length-1]=1;
        for(byte[] bad:new byte[][]{corrupt,truncated,badPadding,junk}) {
            assertPlainStreamFailurePreservesOutput(plainStreamArchive(95,false,bad));
        }
    }

    @Test public void concatenatedXzStillChecksContainerSizeAndAuthentication() throws Exception {
        File plain=plainStreamArchive(95,false,concatenatedXz());
        byte[] bytes=Files.readAllBytes(plain.toPath());
        int central=findSignature(bytes,0x02014b50);
        for(int i=0;i<4;i++) {
            bytes[22+i]=(byte)((CONTENT.length-1) >>> (8*i));
            bytes[central+24+i]=bytes[22+i];
        }
        Files.write(plain.toPath(),bytes);assertPlainStreamFailurePreservesOutput(plain);
        File aes=buildAesZipx("xz-concat-auth.zipx",95,false,0,concatenatedXz());
        bytes=Files.readAllBytes(aes.toPath());bytes[findSignature(bytes,0x02014b50)-1]^=1;
        Files.write(aes.toPath(),bytes);
        File output=temp.newFile();Files.write(output.toPath(),new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class,()->
                ZipxAesArchiveReader.tryExtractSingleEntry(aes,"folder/page.txt",output,PASSWORD));
        assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
    }

    private static byte[] concatenatedXz() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(compress(java.util.Arrays.copyOfRange(CONTENT,0,CONTENT.length/2),95,false));
        out.write(new byte[4]);out.write(compress(new byte[0],95,false));out.write(new byte[8]);
        out.write(compress(java.util.Arrays.copyOfRange(CONTENT,CONTENT.length/2,CONTENT.length),95,false));
        out.write(new byte[4]);return out.toByteArray();
    }

    @Test public void directSupplementalAuthFailureRestoresExistingTarget() throws Exception {
        File archive = buildAesZipx("guard-auth.zipx", 12);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        bytes[findSignature(bytes, 0x02014b50) - 1] ^= 0x40;
        Files.write(archive.toPath(), bytes);
        File out = temp.newFile();
        Files.write(out.toPath(), new byte[]{42});
        try { ZipxAesArchiveReader.tryExtractSingleEntry(archive, "folder/page.txt", out, PASSWORD); fail("Authentication must fail"); }
        catch (IOException expected) { assertArrayEquals(new byte[]{42}, Files.readAllBytes(out.toPath())); }
    }

    @Test public void supplementalSizeFailureRestoresExistingBulkTarget() throws Exception {
        File archive = buildAesZipx("guard-size.zipx", 12);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        int central = findSignature(bytes, 0x02014b50);
        for (int i = 0; i < 4; i++) {
            bytes[22 + i] = (byte) ((CONTENT.length + 1) >>> (8 * i));
            bytes[central + 24 + i] = bytes[22 + i];
        }
        Files.write(archive.toPath(), bytes);
        File target = temp.newFolder();
        File folder = new File(target, "folder");
        assertTrue(folder.mkdir());
        File out = new File(folder, "page.txt");
        Files.write(out.toPath(), new byte[]{42});
        try { ZipxAesArchiveReader.extractArchiveIntoDirectory(archive, target, PASSWORD, null, null); fail("Size must fail"); }
        catch (IOException expected) {
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(out.toPath()));
            assertEquals(1, folder.list().length);
        }
    }

    @Test public void plainCompanionCrcIsCheckedAndDestinationRestored() throws Exception {
        File archive = temp.newFile("plain-guard.zipx");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(archive))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("plain.txt"));
            zip.write(CONTENT);
            zip.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(archive.toPath());
        bytes[findSignature(bytes, 0x02014b50) + 16] ^= 1;
        Files.write(archive.toPath(), bytes);
        File target = temp.newFolder();
        File out = new File(target, "plain.txt");
        Files.write(out.toPath(), new byte[]{42});
        // Exercise the same non-AES descriptor used by plain companions in a ZIPX.
        try { ZipxAesArchiveReader.extractArchiveIntoDirectory(archive, target, PASSWORD, null, null); fail("CRC must fail"); }
        catch (IOException expected) {
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(out.toPath()));
            assertEquals(1, target.list().length);
        }
    }

    @Test public void validSupplementalEntryReplacesExistingDestination() throws Exception {
        File archive = buildAesZipx("guard-success.zipx", 12);
        File out = temp.newFile();
        Files.write(out.toPath(), new byte[]{42});
        assertEquals(Boolean.TRUE, ZipxAesArchiveReader.tryExtractSingleEntry(archive, "folder/page.txt", out, PASSWORD));
        assertArrayEquals(CONTENT, Files.readAllBytes(out.toPath()));
    }

    @Test public void sharedCommonsWriterChecksDecodedSizeBeforeCommit() throws Exception {
        File archive = temp.newFile("commons-size.zip");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(archive))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("plain.txt"));
            zip.write(CONTENT);
            zip.closeEntry();
        }
        File out = temp.newFile();
        Files.write(out.toPath(), new byte[]{42});
        try (org.apache.commons.compress.archivers.zip.ZipFile zip =
                     org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(archive).get()) {
            org.apache.commons.compress.archivers.zip.ZipArchiveEntry entry = zip.getEntry("plain.txt");
            entry.setSize(CONTENT.length + 1L);
            try { ZipxAesArchiveReader.extractPlainEntry(zip, entry, out, null); fail("Size must fail"); }
            catch (IOException expected) { assertArrayEquals(new byte[]{42}, Files.readAllBytes(out.toPath())); }
        }
    }

    @Test public void sharedCommonsWriterAcceptsValidEmptyAndNonemptyEntries() throws Exception {
        File archive = temp.newFile("commons-valid.zip");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(archive))) {
            for (String name : new String[]{"empty", "page"}) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                if (name.equals("page")) zip.write(CONTENT);
                zip.closeEntry();
            }
        }
        File out = temp.newFile();
        try (org.apache.commons.compress.archivers.zip.ZipFile zip =
                     org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(archive).get()) {
            assertTrue(ZipxAesArchiveReader.extractPlainEntry(zip, zip.getEntry("page"), out, null));
            assertArrayEquals(CONTENT, Files.readAllBytes(out.toPath()));
            assertTrue(ZipxAesArchiveReader.extractPlainEntry(zip, zip.getEntry("empty"), out, null));
            assertEquals(0, out.length());
        }
    }

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @org.junit.Before @org.junit.After
    public void releaseIndexes() { ZipxAesArchiveReader.clearCachedIndexes(); }

    @Test public void ordinaryZipBypassesSupplementalIndex() throws Exception {
        File archive = temp.newFile("ordinary.zip");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(archive))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("page.txt"));
            zip.write(CONTENT);
            zip.closeEntry();
        }
        assertFalse(ArchiveSupport.needsZipxAesProbe(archive));
        File out = new File(temp.getRoot(), "ordinary.txt");
        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page.txt", out, null));
        assertArrayEquals(CONTENT, Files.readAllBytes(out.toPath()));
        assertEquals(0, cachedIndexes().size());
    }

    @Test public void supplementalIndexIsReusedButPasswordIsNotRemembered() throws Exception {
        File archive = buildAesZipx("reuse.zipx", 12);
        File out = new File(temp.getRoot(), "reuse.txt");
        assertTrue(ArchiveSupport.extractSingleEntry(archive, "folder/page.txt", out, PASSWORD));
        Object index = cachedIndexes().values().iterator().next();
        assertTrue(ArchiveSupport.extractSingleEntry(archive, "folder/page.txt", out, PASSWORD));
        org.junit.Assert.assertSame(index, cachedIndexes().values().iterator().next());
        ArchiveSupport.ExtractionResult missing = ArchiveSupport.extractSingleEntryDetailed(
                archive, "folder/page.txt", out, null);
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, missing.failure);
        ArchiveSupport.ExtractionResult wrong = ArchiveSupport.extractSingleEntryDetailed(
                archive, "folder/page.txt", out, "wrong".toCharArray());
        assertFalse(wrong.success);
        assertFalse(out.exists());
        assertTrue(ArchiveSupport.extractSingleEntry(archive, "folder/page.txt", out, PASSWORD));
        assertArrayEquals(CONTENT, Files.readAllBytes(out.toPath()));
    }

    @Test public void supplementalIndexesAreBoundedAndArchiveChangesInvalidate() throws Exception {
        File first = null;
        Object firstIndex = null;
        for (int i = 0; i < 4; i++) {
            File archive = buildAesZipx("cache-" + i + ".zipx", 12);
            assertTrue(ArchiveSupport.extractSingleEntry(archive, "folder/page.txt",
                    new File(temp.getRoot(), "cache-" + i + ".txt"), PASSWORD));
            if (i == 0) { first = archive; firstIndex = cachedIndexes().values().iterator().next(); }
        }
        assertEquals(3, cachedIndexes().size());
        assertFalse(cachedIndexes().containsValue(firstIndex));
        assertTrue(ArchiveSupport.extractSingleEntry(first, "folder/page.txt",
                new File(temp.getRoot(), "again.txt"), PASSWORD));
        Object reopened = cachedIndexes().values().toArray()[2];
        org.junit.Assume.assumeTrue(first.setLastModified(first.lastModified() + 5000L));
        assertTrue(ArchiveSupport.extractSingleEntry(first, "folder/page.txt",
                new File(temp.getRoot(), "changed.txt"), PASSWORD));
        assertFalse(cachedIndexes().containsValue(reopened));
        assertEquals(3, cachedIndexes().size());
    }

    private static java.util.Map<?, ?> cachedIndexes() throws Exception {
        java.lang.reflect.Field field = ZipxAesArchiveReader.class.getDeclaredField("INDEXES");
        field.setAccessible(true);
        return (java.util.Map<?, ?>) field.get(null);
    }

    @Test
    public void aesBzip2ListsAndExtractsWholeAndSingleEntry() throws Exception {
        runSuccessMatrix(buildAesZipx("bzip2.zipx", 12));
    }

    @Test
    public void aesXzListsAndExtractsWholeAndSingleEntry() throws Exception {
        runSuccessMatrix(buildAesZipx("xz.zipx", 95));
    }

    @Test
    public void aesLzmaListsAndExtractsWholeAndSingleEntry() throws Exception {
        runSuccessMatrix(buildAesZipx("lzma.zipx", 14));
    }

    @Test
    public void aesLzmaWithEndMarkerListsAndExtracts() throws Exception {
        runSuccessMatrix(buildAesZipx("lzma-eos.zipx", 14, true));
    }

    @Test
    public void wrongPasswordIsBadPasswordAndLeavesNoSingleOutput() throws Exception {
        File archive = buildAesZipx("wrong-password.zipx", 12);
        File out = new File(temp.newFolder("wrong"), "page.txt");
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                archive, "folder/page.txt", out, "wrong".toCharArray());

        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.BAD_PASSWORD, result.failure);
        assertFalse(out.exists());
    }

    @Test
    public void missingPasswordIsReportedBeforeExtraction() throws Exception {
        File archive = buildAesZipx("missing-password.zipx", 95);
        File out = new File(temp.newFolder("missing"), "page.txt");
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                archive, "folder/page.txt", out, null);

        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, result.failure);
        assertFalse(out.exists());
    }

    @Test
    public void damagedAuthenticationCodeIsBadPasswordAndLeavesNoOutput() throws Exception {
        File valid = buildAesZipx("valid.zipx", 12);
        byte[] damaged = Files.readAllBytes(valid.toPath());
        int central = findSignature(damaged, 0x02014b50);
        assertTrue("central directory signature missing", central > 0);
        damaged[central - 1] ^= 0x40; // final HMAC-SHA1-80 byte
        File archive = temp.newFile("damaged-auth.zipx");
        Files.write(archive.toPath(), damaged);

        File out = new File(temp.newFolder("damaged"), "page.txt");
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                archive, "folder/page.txt", out, PASSWORD.clone());

        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.BAD_PASSWORD, result.failure);
        assertFalse(out.exists());
    }

    @Test
    public void aesPpmdRetainsContainerFallbackWhenSupplementalDecoderIsUnavailable() throws Exception {
        org.junit.Assume.assumeFalse(com.readwide.codecs.PpmdZipInputStream.isAvailable());
        File archive = buildAesZipx("ppmd.zipx", 98);
        assertFalse(ZipxAesArchiveReader.canExtractArchive(archive));
        assertFalse(ZipxAesArchiveReader.hasUnsupportedAesMethod(archive));
        File out = new File(temp.newFolder("ppmd"), "page.txt");
        ArchiveSupport.ExtractionResult single = ArchiveSupport.extractSingleEntryDetailed(
                archive, "folder/page.txt", out, PASSWORD.clone());
        assertFalse(single.success);
        assertEquals(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE, single.failure);
        assertFalse(out.exists());

        File destination = new File(temp.newFolder("ppmd-whole"), "out");
        ArchiveSupport.ExtractionResult whole = ArchiveSupport.extractArchiveDetailed(
                archive, destination, true, PASSWORD.clone(), null);
        assertFalse(whole.success);
        assertEquals(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE, whole.failure);
        assertFalse(destination.exists());
    }

    @Test
    public void aesZstandardRoutesToAuthenticatedStreamBridge() throws Exception {
        File archive = buildAesZipx("zstandard.zipx", 93);
        assertTrue(ZipxAesArchiveReader.canExtractArchive(archive));
        assertFalse(ZipxAesArchiveReader.hasUnsupportedAesMethod(archive));
    }

    @Test
    public void aesJpegRoutesToFossNativeDecoder() throws Exception {
        File archive = buildAesZipx("jpeg.zipx", 96);
        assertTrue(ZipxAesArchiveReader.canExtractArchive(archive));
        assertFalse(ZipxAesArchiveReader.hasUnsupportedAesMethod(archive));
    }

    @Test
    public void aesWavPackRoutesToFossNativeDecoder() throws Exception {
        File archive = buildAesZipx("wavpack.zipx", 97);
        assertTrue(ZipxAesArchiveReader.canExtractArchive(archive));
        assertFalse(ZipxAesArchiveReader.hasUnsupportedAesMethod(archive));
    }

    @Test public void legacyAesZstandardRoutesToSameBridge() throws Exception {
        File archive = buildAesZipx("legacy-zstd.zipx", 20);
        assertTrue(ZipxAesArchiveReader.canExtractArchive(archive));
        assertFalse(ZipxAesArchiveReader.hasUnsupportedAesMethod(archive));
    }

    @Test public void zstandardMissingNativeDecoderPreservesTarget() throws Exception {
        org.junit.Assume.assumeFalse(LibarchiveNativeBridge.isAvailable());
        File archive = buildAesZipx("zstd-unavailable.zipx", 93), output = temp.newFile();
        Files.write(output.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class,
                () -> ZipxAesArchiveReader.tryExtractSingleEntry(archive, "folder/page.txt", output, PASSWORD));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void zstandardAesNativeDecodeMatrix() throws Exception {
        org.junit.Assume.assumeTrue(LibarchiveNativeBridge.isAvailable());
        runSuccessMatrix(buildAesZipx("zstd-native.zipx", 93));
    }

    @Test public void legacyZstandardAesNativeDecodeMatrix() throws Exception {
        org.junit.Assume.assumeTrue(LibarchiveNativeBridge.isAvailable());
        runSuccessMatrix(buildAesZipx("zstd-legacy-native.zipx", 20));
    }

    @Test public void zstandardAesAuthenticationFailureRestoresTarget() throws Exception {
        org.junit.Assume.assumeTrue(LibarchiveNativeBridge.isAvailable());
        File archive = buildAesZipx("zstd-auth.zipx", 93), output = temp.newFile();
        byte[] packed = Files.readAllBytes(archive.toPath());
        packed[findSignature(packed, 0x02014b50) - 1] ^= 1;
        Files.write(archive.toPath(), packed);
        Files.write(output.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class,
                () -> ZipxAesArchiveReader.tryExtractSingleEntry(archive, "folder/page.txt", output, PASSWORD));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void plainZstandardCompanionUsesRawDecoderAndCrc() throws Exception {
        org.junit.Assume.assumeTrue(LibarchiveNativeBridge.isAvailable());
        File archive = temp.newFile("plain-zstd.zipx");
        byte[] compressed = com.github.luben.zstd.Zstd.compress(CONTENT);
        org.apache.commons.compress.archivers.zip.ZipArchiveEntry entry =
                new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("plain.bin");
        entry.setMethod(93); entry.setSize(CONTENT.length); entry.setCompressedSize(compressed.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(CONTENT); entry.setCrc(crc.getValue());
        try (org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream zip =
                new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(archive)) {
            zip.addRawArchiveEntry(entry, new java.io.ByteArrayInputStream(compressed));
        }
        File directory = temp.newFolder();
        assertTrue(ZipxAesArchiveReader.extractArchiveIntoDirectory(archive, directory, PASSWORD, null, null));
        assertArrayEquals(CONTENT, Files.readAllBytes(new File(directory, "plain.bin").toPath()));
    }

    @Test public void zipLzmaUnsignedMaximumDictionaryWithSmallOutput() throws Exception {
        usefulDictionary(false, 0xffffffffL);
    }
    @Test public void zipLzmaUnsignedTwoGiBDictionaryWithSmallOutput() throws Exception {
        usefulDictionary(false, 0x80000000L);
    }
    @Test public void zipLzmaEndMarkerAlsoUsesBoundedDictionary() throws Exception {
        usefulDictionary(true, 0xffffffffL);
    }
    @Test public void zipLzmaUnsignedDictionaryUsesLargeUsefulHistoryWithoutAllocating() throws Exception {
        byte[] properties = {0x5d, -1, -1, -1, -1};
        assertEquals(1 << 29, ZipxAesArchiveReader.lzmaDictionarySize(properties, 1L << 29));
        org.junit.Assert.assertThrows(IOException.class,
                () -> ZipxAesArchiveReader.lzmaDictionarySize(properties, 0xffffffffL));
    }
    @Test public void zipLzmaInvalidPropertiesRemainRejected() throws Exception {
        byte[] packed = compress(CONTENT, 14, false); packed[4] = (byte) 255;
        org.junit.Assert.assertThrows(IOException.class, () -> openZipLzma(packed, CONTENT.length, false));
    }
    @Test public void zipLzmaUnknownOutputCannotAuthorizeDictionaryReduction() throws Exception {
        byte[] packed = compress(CONTENT, 14, true);
        org.junit.Assert.assertThrows(IOException.class, () -> openZipLzma(packed, -1, true));
    }

    private void usefulDictionary(boolean eos, long declared) throws Exception {
        byte[] packed = compress(CONTENT, 14, eos);
        for (int i = 0; i < 4; i++) packed[5 + i] = (byte) (declared >>> (8 * i));
        try (java.io.InputStream input = openZipLzma(packed, CONTENT.length, eos)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[257];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            assertArrayEquals(CONTENT, output.toByteArray());
        }
    }

    private static java.io.InputStream openZipLzma(byte[] packed, long size, boolean eos) throws Exception {
        java.lang.reflect.Method method = ZipxAesArchiveReader.class.getDeclaredMethod(
                "wrapZipLzmaStream", java.io.InputStream.class, long.class, boolean.class);
        method.setAccessible(true);
        try { return (java.io.InputStream) method.invoke(null, new java.io.ByteArrayInputStream(packed), size, eos); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw failure;
        }
    }

    @Test public void aesShrinkUsesBundledLegacyDecoder() throws Exception {
        runSuccessMatrix(buildAesZipx("shrink.zipx", 1));
    }
    @Test public void aesReduceFactorOneExtractsWithAuthentication() throws Exception { runSuccessMatrix(buildAesZipx("reduce-1.zipx", 2)); }
    @Test public void aesReduceFactorTwoExtractsWithAuthentication() throws Exception { runSuccessMatrix(buildAesZipx("reduce-2.zipx", 3)); }
    @Test public void aesReduceFactorThreeExtractsWithAuthentication() throws Exception { runSuccessMatrix(buildAesZipx("reduce-3.zipx", 4)); }
    @Test public void aesReduceFactorFourExtractsWithAuthentication() throws Exception { runSuccessMatrix(buildAesZipx("reduce-4.zipx", 5)); }
    @Test public void traditionalReduceAllFactorsExtractIncludingDescriptors() throws Exception {
        for (int method = 2; method <= 5; method++) traditionalSuccess(method, true, PASSWORD);
    }
    @Test public void aesReduceSplitStreamRetainsAuthentication() throws Exception {
        splitSuccess(splitZipx(buildAesZipx("reduce-split.zipx", 5), 20), "folder/page.txt", PASSWORD);
    }
    @Test public void aesImplode4kTwoTrees() throws Exception { legacyImplode(0); }
    @Test public void aesImplode8kTwoTrees() throws Exception { legacyImplode(2); }
    @Test public void aesImplode4kThreeTrees() throws Exception { legacyImplode(4); }
    @Test public void aesImplode8kThreeTrees() throws Exception { legacyImplode(6); }

    @Test public void aesLegacyDamagedAuthenticationPreservesTarget() throws Exception {
        File archive = buildAesZipx("legacy-auth.zipx", 6), output = temp.newFile();
        byte[] bytes = Files.readAllBytes(archive.toPath());
        bytes[findSignature(bytes, 0x02014b50) - 1] ^= 1;
        Files.write(archive.toPath(), bytes); Files.write(output.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class,
                () -> ZipxAesArchiveReader.tryExtractSingleEntry(archive, "folder/page.txt", output, PASSWORD));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    @Test public void aesLegacyWrongPasswordPreservesTarget() throws Exception {
        File archive = buildAesZipx("legacy-password.zipx", 1), output = temp.newFile();
        Files.write(output.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class, () -> ZipxAesArchiveReader.tryExtractSingleEntry(
                archive, "folder/page.txt", output, "wrong".toCharArray()));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    private void legacyImplode(int flags) throws Exception {
        runSuccessMatrix(buildAesZipx("implode.zipx", 6, false, flags));
    }

    /** Literal-only streams from documented ZIP encodings, independent of the decoders. */
    private static byte[] legacyLiterals(byte[] content, int method, int flags) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean literalTree = method == 6 && (flags & 4) != 0;
        if (method == 6) {
            if (literalTree) { output.write(15); for (int i = 0; i < 16; i++) output.write(0xf7); }
            for (int tree = 0; tree < 2; tree++) {
                output.write(3); for (int i = 0; i < 4; i++) output.write(0xf5);
            }
        }
        int pending = 0, bits = 0;
        for (byte value : content) {
            int symbol = value & 255;
            if (method == 6) {
                if (literalTree) symbol = Integer.reverse(255 - symbol) >>> 24;
                symbol = (symbol << 1) | 1;
            }
            pending |= symbol << bits; bits += 9;
            while (bits >= 8) { output.write(pending & 255); pending >>>= 8; bits -= 8; }
        }
        if (bits != 0) output.write(pending);
        return output.toByteArray();
    }

    @Test public void zipCryptoBzip2ExtractsWholeAndSelected() throws Exception { traditionalSuccess(12, false, PASSWORD); }
    @Test public void zipCryptoXzExtractsWholeAndSelected() throws Exception { traditionalSuccess(95, false, PASSWORD); }
    @Test public void zipCryptoLzmaExtractsWholeAndSelected() throws Exception { traditionalSuccess(14, false, PASSWORD); }
    @Test public void zipCryptoShrinkExtractsWholeAndSelected() throws Exception { traditionalSuccess(1, false, PASSWORD); }
    @Test public void zipCryptoImplodeExtractsWholeAndSelected() throws Exception { traditionalSuccess(6, false, PASSWORD); }
    @Test public void zipCryptoDescriptorUsesOriginalDosTime() throws Exception { traditionalSuccess(12, true, PASSWORD); }
    @Test public void zipCryptoUtf8PasswordUsesBundledPolicy() throws Exception { traditionalSuccess(12, true, "비밀🔐".toCharArray()); }

    @Test public void zipCryptoDecodedCrcFailureRestoresTarget() throws Exception {
        File archive = traditionalArchive(12, false, PASSWORD), output = temp.newFile();
        byte[] bytes = Files.readAllBytes(archive.toPath()); bytes[findSignature(bytes, 0x02014b50) + 16] ^= 1;
        Files.write(archive.toPath(), bytes); Files.write(output.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class,
                () -> ZipxAesArchiveReader.tryExtractSingleEntry(archive, "traditional.bin", output, PASSWORD));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    @Test public void zipCryptoWrongPasswordRestoresTarget() throws Exception {
        File archive = traditionalArchive(12, false, PASSWORD), output = temp.newFile();
        Files.write(output.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class, () -> ZipxAesArchiveReader.tryExtractSingleEntry(
                archive, "traditional.bin", output, "wrong".toCharArray()));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void aesExtendedAndTraditionalStoredCompanionShareBulkRoute() throws Exception {
        byte[] aes = Files.readAllBytes(buildAesZipx("mixed-aes.zipx", 12).toPath());
        byte[] traditional = Files.readAllBytes(traditionalArchive(0, true, PASSWORD).toPath());
        int aCentral = findSignature(aes, 0x02014b50), aEnd = findSignature(aes, 0x06054b50);
        int tCentral = findSignature(traditional, 0x02014b50), tEnd = findSignature(traditional, 0x06054b50);
        byte[] tDirectory = java.util.Arrays.copyOfRange(traditional, tCentral, tEnd);
        for (int i = 0; i < 4; i++) tDirectory[42 + i] = (byte) (aCentral >>> (8 * i));
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        zip.write(aes, 0, aCentral); zip.write(traditional, 0, tCentral);
        int directoryOffset = zip.size(); zip.write(aes, aCentral, aEnd - aCentral); zip.write(tDirectory);
        int directorySize = zip.size() - directoryOffset;
        writeInt(zip, 0x06054b50); writeShort(zip, 0); writeShort(zip, 0); writeShort(zip, 2); writeShort(zip, 2);
        writeInt(zip, directorySize); writeInt(zip, directoryOffset); writeShort(zip, 0);
        File archive = temp.newFile(); Files.write(archive.toPath(), zip.toByteArray());
        assertTrue(ZipxAesArchiveReader.canExtractArchive(archive));
        File directory = temp.newFolder();
        assertTrue(ZipxAesArchiveReader.extractArchiveIntoDirectory(archive, directory, PASSWORD, null, null));
        assertArrayEquals(CONTENT, Files.readAllBytes(new File(directory, "folder/page.txt").toPath()));
        assertArrayEquals(CONTENT, Files.readAllBytes(new File(directory, "traditional.bin").toPath()));
    }

    private void traditionalSuccess(int method, boolean descriptor, char[] password) throws Exception {
        File archive = traditionalArchive(method, descriptor, password), output = temp.newFile();
        assertTrue(ZipxAesArchiveReader.canExtractArchive(archive));
        assertEquals(Boolean.TRUE, ZipxAesArchiveReader.tryExtractSingleEntry(archive, "traditional.bin", output, password));
        assertArrayEquals(CONTENT, Files.readAllBytes(output.toPath()));
        File directory = temp.newFolder();
        assertTrue(ZipxAesArchiveReader.extractArchiveIntoDirectory(archive, directory, password, null, null));
        assertArrayEquals(CONTENT, Files.readAllBytes(new File(directory, "traditional.bin").toPath()));
    }

    private File traditionalArchive(int method, boolean descriptor, char[] password) throws Exception {
        return traditionalArchive(method, descriptor, password, method == 0 ? CONTENT.clone() : method == 1 || method == 6
                ? legacyLiterals(CONTENT, method, 0) : compress(CONTENT, method, false));
    }
    private File traditionalArchive(int method, boolean descriptor, char[] password, byte[] compressed) throws Exception {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(CONTENT);
        int checksum = (int) crc.getValue(), time = 0x4a21, flags = 0x0801 | (descriptor ? 8 : 0);
        byte[] header = new byte[12]; header[11] = (byte) (descriptor ? time >>> 8 : checksum >>> 24);
        Round4Fixtures.Crypto crypto = new Round4Fixtures.Crypto(password);
        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        encrypted.write(crypto.encrypt(header)); encrypted.write(crypto.encrypt(compressed));
        byte[] name = "traditional.bin".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        writeInt(zip, 0x04034b50); writeShort(zip, 63); writeShort(zip, flags); writeShort(zip, method);
        writeShort(zip, time); writeShort(zip, 0);
        writeInt(zip, descriptor ? 0 : checksum); writeInt(zip, descriptor ? 0 : encrypted.size());
        writeInt(zip, descriptor ? 0 : CONTENT.length); writeShort(zip, name.length); writeShort(zip, 0);
        zip.write(name); zip.write(encrypted.toByteArray());
        if (descriptor) { writeInt(zip, 0x08074b50); writeInt(zip, checksum); writeInt(zip, encrypted.size()); writeInt(zip, CONTENT.length); }
        int central = zip.size(); writeInt(zip, 0x02014b50); writeShort(zip, 63); writeShort(zip, 63);
        writeShort(zip, flags); writeShort(zip, method); writeShort(zip, time); writeShort(zip, 0);
        writeInt(zip, checksum); writeInt(zip, encrypted.size()); writeInt(zip, CONTENT.length);
        writeShort(zip, name.length); writeShort(zip, 0); writeShort(zip, 0); writeShort(zip, 0); writeShort(zip, 0);
        writeInt(zip, 0); writeInt(zip, 0); zip.write(name);
        int centralSize = zip.size() - central;
        writeInt(zip, 0x06054b50); writeShort(zip, 0); writeShort(zip, 0); writeShort(zip, 1); writeShort(zip, 1);
        writeInt(zip, centralSize); writeInt(zip, central); writeShort(zip, 0);
        File archive = temp.newFile(); Files.write(archive.toPath(), zip.toByteArray()); return archive;
    }

    @Test public void plainJpegRoutesToExistingNativePayloadDecoder() throws Exception { nativePlainRoute(96); }
    @Test public void plainWavPackRoutesToExistingNativePayloadDecoder() throws Exception { nativePlainRoute(97); }
    @Test public void traditionalJpegRoutesToExistingNativePayloadDecoder() throws Exception { nativeTraditionalRoute(96); }
    @Test public void traditionalWavPackRoutesToExistingNativePayloadDecoder() throws Exception { nativeTraditionalRoute(97); }

    private void nativePlainRoute(int method) throws Exception {
        File archive = temp.newFile();
        org.apache.commons.compress.archivers.zip.ZipArchiveEntry entry =
                new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("native.bin");
        entry.setMethod(method); entry.setSize(CONTENT.length); entry.setCompressedSize(CONTENT.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(CONTENT); entry.setCrc(crc.getValue());
        try (org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream zip =
                new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(archive)) {
            zip.addRawArchiveEntry(entry, new java.io.ByteArrayInputStream(CONTENT));
        }
        assertTrue(ZipxAesArchiveReader.canExtractArchive(archive));
        File output = temp.newFile(); Files.write(output.toPath(), new byte[]{42});
        // Deliberately malformed codec bytes: either unavailable or present backend must fail safely.
        org.junit.Assert.assertThrows(IOException.class,
                () -> ZipxAesArchiveReader.tryExtractSingleEntry(archive, "native.bin", output, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    private void nativeTraditionalRoute(int method) throws Exception {
        File archive = traditionalArchive(method, false, PASSWORD), output = temp.newFile();
        assertTrue(ZipxAesArchiveReader.canExtractArchive(archive));
        Files.write(output.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class,
                () -> ZipxAesArchiveReader.tryExtractSingleEntry(archive, "traditional.bin", output, PASSWORD));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void plainLzmaWithoutEndMarkerActivatesSupplement() throws Exception { plainStreamSuccess(14, false); }
    @Test public void plainLzmaWithEndMarkerActivatesSupplement() throws Exception { plainStreamSuccess(14, true); }
    @Test public void plainZstandardActivatesSupplement() throws Exception {
        org.junit.Assume.assumeTrue(LibarchiveNativeBridge.isAvailable()); plainStreamSuccess(93, false);
    }
    @Test public void plainLegacyZstandardActivatesSupplement() throws Exception {
        org.junit.Assume.assumeTrue(LibarchiveNativeBridge.isAvailable()); plainStreamSuccess(20, false);
    }
    @Test public void plainZstandardMissingNativeCodecPreservesOutput() throws Exception {
        org.junit.Assume.assumeFalse(LibarchiveNativeBridge.isAvailable());
        for (int method : new int[]{93, 20}) {
            File archive = plainStreamArchive(method, false), output = temp.newFile();
            assertTrue(ZipxAesArchiveReader.canExtractArchive(archive));
            Files.write(output.toPath(), new byte[]{42});
            org.junit.Assert.assertThrows(IOException.class,
                    () -> ZipxAesArchiveReader.tryExtractSingleEntry(archive, "plain.bin", output, null));
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
        }
    }
    @Test public void plainLzmaCrcFailurePreservesSingleAndBulkTargets() throws Exception {
        File archive = plainStreamArchive(14, false);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        bytes[14] ^= 1; bytes[findSignature(bytes, 0x02014b50) + 16] ^= 1;
        Files.write(archive.toPath(), bytes);
        assertPlainStreamFailurePreservesOutput(archive);
    }
    @Test public void malformedPlainLzmaPropertiesPreserveTargets() throws Exception {
        File archive = plainStreamArchive(14, false);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        bytes[30 + "plain.bin".length() + 4] = (byte) 255;
        Files.write(archive.toPath(), bytes);
        assertPlainStreamFailurePreservesOutput(archive);
    }
    @Test public void plainLzmaDecodedSizeMismatchPreservesTargets() throws Exception {
        File archive = plainStreamArchive(14, true);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        int central = findSignature(bytes, 0x02014b50), declared = CONTENT.length + 1;
        for (int i = 0; i < 4; i++) {
            bytes[22 + i] = (byte) (declared >>> (8 * i));
            bytes[central + 24 + i] = bytes[22 + i];
        }
        Files.write(archive.toPath(), bytes);
        assertPlainStreamFailurePreservesOutput(archive);
    }
    private void assertPlainStreamFailurePreservesOutput(File archive) throws Exception {
        File output = temp.newFile(); Files.write(output.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class,
                () -> ZipxAesArchiveReader.tryExtractSingleEntry(archive, "plain.bin", output, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
        File directory = temp.newFolder(), bulk = new File(directory, "plain.bin");
        Files.write(bulk.toPath(), new byte[]{43});
        org.junit.Assert.assertThrows(IOException.class,
                () -> ZipxAesArchiveReader.extractArchiveIntoDirectory(archive, directory, null, null, null));
        assertArrayEquals(new byte[]{43}, Files.readAllBytes(bulk.toPath()));
    }
    private void plainStreamSuccess(int method, boolean eos) throws Exception {
        File archive = plainStreamArchive(method, eos), output = temp.newFile();
        assertTrue(ZipxAesArchiveReader.canExtractArchive(archive));
        assertEquals(Boolean.TRUE, ZipxAesArchiveReader.tryExtractSingleEntry(archive, "plain.bin", output, null));
        assertArrayEquals(CONTENT, Files.readAllBytes(output.toPath()));
        File directory = new File(temp.newFolder(), "whole");
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(archive, directory, true, null, null);
        assertTrue(result.failure + ": " + result.detail, result.success);
        assertArrayEquals(CONTENT, Files.readAllBytes(new File(directory, "plain.bin").toPath()));
    }
    private File plainStreamArchive(int method, boolean eos) throws Exception {
        return plainStreamArchive(method, eos, compress(CONTENT, method, eos));
    }
    private File plainStreamArchive(int method, boolean eos, byte[] packed) throws Exception {
        byte[] name = "plain.bin".getBytes(StandardCharsets.UTF_8);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(CONTENT);
        int checksum = (int) crc.getValue(), flags = 0x0800 | (method == 14 && eos ? 2 : 0);
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        writeInt(zip, 0x04034b50); writeShort(zip, 63); writeShort(zip, flags); writeShort(zip, method);
        writeShort(zip, 0); writeShort(zip, 0); writeInt(zip, checksum);
        writeInt(zip, packed.length); writeInt(zip, CONTENT.length); writeShort(zip, name.length); writeShort(zip, 0);
        zip.write(name); zip.write(packed);
        int central = zip.size(); writeInt(zip, 0x02014b50); writeShort(zip, 63); writeShort(zip, 63);
        writeShort(zip, flags); writeShort(zip, method); writeShort(zip, 0); writeShort(zip, 0); writeInt(zip, checksum);
        writeInt(zip, packed.length); writeInt(zip, CONTENT.length); writeShort(zip, name.length); writeShort(zip, 0);
        writeShort(zip, 0); writeShort(zip, 0); writeShort(zip, 0); writeInt(zip, 0); writeInt(zip, 0); zip.write(name);
        int centralSize = zip.size() - central;
        writeInt(zip, 0x06054b50); writeShort(zip, 0); writeShort(zip, 0); writeShort(zip, 1); writeShort(zip, 1);
        writeInt(zip, centralSize); writeInt(zip, central); writeShort(zip, 0);
        File archive = new File(temp.newFolder(), "plain.zipx"); Files.write(archive.toPath(), zip.toByteArray());
        return archive;
    }

    @Test public void splitAesBzipPayloadCrossesVolumes() throws Exception { splitAesSuccess(12, false); }
    @Test public void splitAesXzPayloadCrossesVolumes() throws Exception { splitAesSuccess(95, false); }
    @Test public void splitAesLzmaPayloadCrossesVolumes() throws Exception { splitAesSuccess(14, false); }
    @Test public void splitAesAuthenticationFooterCrossesVolumes() throws Exception { splitAesSuccess(12, true); }
    @Test public void splitPlainLzmaUsesExistingStreamDecoder() throws Exception {
        File original = plainStreamArchive(14, true);
        File[] parts = splitZipx(original, findSignature(Files.readAllBytes(original.toPath()), 0x02014b50) / 2);
        splitSuccess(parts, "plain.bin", null);
    }
    @Test public void splitTraditionalHeaderOnLaterDiskUsesDiskRelativeOffset() throws Exception {
        File[] parts = splitZipx(traditionalArchive(12, true, PASSWORD), 4);
        splitSuccess(parts, "traditional.bin", PASSWORD);
    }
    @Test public void splitTraditionalLocalHeaderCanCrossBoundary() throws Exception {
        File[] parts = splitZipx(traditionalArchive(12, true, PASSWORD), 10);
        splitSuccess(parts, "traditional.bin", PASSWORD);
    }
    @Test public void splitAesBadAuthenticationPreservesTarget() throws Exception {
        File original = buildAesZipx("split-bad.zipx", 12);
        byte[] bytes = Files.readAllBytes(original.toPath()); int central = findSignature(bytes, 0x02014b50);
        bytes[central - 1] ^= 1; Files.write(original.toPath(), bytes);
        File[] parts = splitZipx(original, central - 1);
        File output = temp.newFile(); Files.write(output.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class, () -> ZipxAesArchiveReader.tryExtractSingleEntry(
                parts[parts.length - 1], "folder/page.txt", output, PASSWORD));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    @Test public void splitMissingMiddleVolumeFailsBeforeBulkOutput() throws Exception {
        File original = buildAesZipx("split-gap.zipx", 12);
        int central = findSignature(Files.readAllBytes(original.toPath()), 0x02014b50);
        File[] parts = splitZipx(original, central / 3, central * 2 / 3);
        Files.delete(parts[1].toPath()); File target = temp.newFolder();
        assertFalse(ZipxAesArchiveReader.canExtractArchive(parts[2]));
        org.junit.Assert.assertThrows(IOException.class,
                () -> ZipxAesArchiveReader.extractArchiveIntoDirectory(parts[2], target, PASSWORD, null, null));
        assertEquals(0, target.list().length);
    }
    @Test public void splitLocalMethodMismatchPreservesTarget() throws Exception {
        File original = buildAesZipx("split-local.zipx", 12);
        int central = findSignature(Files.readAllBytes(original.toPath()), 0x02014b50);
        File[] parts = splitZipx(original, central / 2);
        byte[] first = Files.readAllBytes(parts[0].toPath()); first[4 + 8] = 12;
        Files.write(parts[0].toPath(), first);
        File output = temp.newFile(); Files.write(output.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class, () -> ZipxAesArchiveReader.tryExtractSingleEntry(
                parts[1], "folder/page.txt", output, PASSWORD));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    @Test public void splitInvalidEntryStartDiskFailsBeforeBulkOutput() throws Exception {
        File original = buildAesZipx("split-disk.zipx", 12);
        int central = findSignature(Files.readAllBytes(original.toPath()), 0x02014b50);
        File[] parts = splitZipx(original, central / 2);
        byte[] last = Files.readAllBytes(parts[1].toPath());
        putLittle(last, findSignature(last, 0x02014b50) + 34, 2, 2); Files.write(parts[1].toPath(), last);
        File target = temp.newFolder();
        org.junit.Assert.assertThrows(IOException.class,
                () -> ZipxAesArchiveReader.extractArchiveIntoDirectory(parts[1], target, PASSWORD, null, null));
        assertEquals(0, target.list().length);
    }
    @Test public void splitSignatureFailureReleasesOpenedVolumeHandles() throws Exception {
        File original = buildAesZipx("split-signature.zipx", 12);
        int central = findSignature(Files.readAllBytes(original.toPath()), 0x02014b50);
        File[] parts = splitZipx(original, central / 2);
        byte[] first = Files.readAllBytes(parts[0].toPath()); first[0] ^= 1; Files.write(parts[0].toPath(), first);
        assertFalse(ZipxAesArchiveReader.canExtractArchive(parts[1]));
        // Windows exposes leaked open channels through failed rename/delete operations.
        for (File part : parts) Files.move(part.toPath(), new File(part.getParentFile(), part.getName() + ".moved").toPath());
    }
    @Test public void splitCacheIdentityIncludesEarlierVolumeAndReleaseAcceptsAnyMember() throws Exception {
        File original = buildAesZipx("split-cache.zipx", 12);
        int central = findSignature(Files.readAllBytes(original.toPath()), 0x02014b50);
        File[] parts = splitZipx(original, central / 2); File last = parts[1], output = temp.newFile();
        assertEquals(Boolean.TRUE, ZipxAesArchiveReader.tryExtractSingleEntry(last, "folder/page.txt", output, PASSWORD));
        assertTrue(ZipxAesArchiveReader.hasCachedIndex(last));
        assertTrue(parts[0].setLastModified(parts[0].lastModified() + 4000));
        assertFalse(ZipxAesArchiveReader.hasCachedIndex(last));
        assertEquals(Boolean.TRUE, ZipxAesArchiveReader.tryExtractSingleEntry(last, "folder/page.txt", output, PASSWORD));
        ZipxAesArchiveReader.releaseArchiveIndex(parts[0]);
        assertFalse(ZipxAesArchiveReader.hasCachedIndex(last));
    }
    private void splitAesSuccess(int method, boolean splitTag) throws Exception {
        File original = buildAesZipx("split-original.zipx", method);
        int central = findSignature(Files.readAllBytes(original.toPath()), 0x02014b50);
        File[] parts = splitZipx(original, splitTag ? central + 4 - 5 : central / 2);
        splitSuccess(parts, "folder/page.txt", PASSWORD);
    }
    private void splitSuccess(File[] parts, String name, char[] password) throws Exception {
        File last = parts[parts.length - 1], output = temp.newFile();
        assertTrue(ZipxAesArchiveReader.canExtractArchive(last));
        assertEquals(Boolean.TRUE, ZipxAesArchiveReader.tryExtractSingleEntry(last, name, output, password));
        assertArrayEquals(CONTENT, Files.readAllBytes(output.toPath()));
        File target = temp.newFolder();
        assertTrue(ZipxAesArchiveReader.extractArchiveIntoDirectory(last, target, password, null, null));
        assertArrayEquals(CONTENT, Files.readAllBytes(new File(target, name).toPath()));
        // A selected continuation resolves to the same declared chain.
        assertEquals(Boolean.TRUE, ZipxAesArchiveReader.tryExtractSingleEntry(parts[0], name, output, password));
        assertArrayEquals(CONTENT, Files.readAllBytes(output.toPath()));
        ZipxAesArchiveReader.releaseArchiveIndex(last);
    }
    /** One-entry PKZIP fixture with real disk-relative offsets; cuts precede the central directory. */
    private File[] splitZipx(File original, int... cuts) throws Exception {
        byte[] single = Files.readAllBytes(original.toPath());
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        writeInt(joined, 0x08074b50); joined.write(single); byte[] bytes = joined.toByteArray();
        int central = findSignature(bytes, 0x02014b50), end = findSignature(bytes, 0x06054b50);
        int startDisk = 0, startBase = 0, previous = 0;
        for (int cut : cuts) {
            assertTrue(cut > previous && cut < central); previous = cut;
            if (cut <= 4) { startDisk++; startBase = cut; }
        }
        putLittle(bytes, central + 34, startDisk, 2); putLittle(bytes, central + 42, 4 - startBase, 4);
        putLittle(bytes, end + 4, cuts.length, 2); putLittle(bytes, end + 6, cuts.length, 2);
        putLittle(bytes, end + 16, central - cuts[cuts.length - 1], 4);
        File directory = temp.newFolder(); File[] parts = new File[cuts.length + 1]; int from = 0;
        for (int i = 0; i < parts.length; i++) {
            parts[i] = new File(directory, i == cuts.length ? "split.zipx" : "split.z0" + (i + 1));
            int to = i == cuts.length ? bytes.length : cuts[i];
            Files.write(parts[i].toPath(), java.util.Arrays.copyOfRange(bytes, from, to)); from = to;
        }
        return parts;
    }
    private static void putLittle(byte[] bytes, int offset, int value, int width) {
        for (int i = 0; i < width; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
    }

    private void runSuccessMatrix(File archive) throws Exception {
        assertTrue(ArchiveSupport.isSupportedArchive(archive));
        assertTrue(ArchiveSupport.requiresPasswordForExtraction(archive));
        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, PASSWORD.clone());
        assertEquals(2, entries.size()); // synthetic folder + file
        assertEquals("folder/", entries.get(0).path);
        assertEquals("folder/page.txt", entries.get(1).path);

        File destination = new File(temp.newFolder("whole"), "out");
        ArchiveSupport.ExtractionResult whole = ArchiveSupport.extractArchiveDetailed(
                archive, destination, true, PASSWORD.clone(), null);
        assertTrue(whole.failure + ": " + whole.detail, whole.success);
        assertArrayEquals(CONTENT, Files.readAllBytes(new File(destination, "folder/page.txt").toPath()));

        File single = new File(temp.newFolder("single"), "page.txt");
        ArchiveSupport.ExtractionResult one = ArchiveSupport.extractSingleEntryDetailed(
                archive, "folder/page.txt", single, PASSWORD.clone());
        assertTrue(one.failure + ": " + one.detail, one.success);
        assertArrayEquals(CONTENT, Files.readAllBytes(single.toPath()));
    }

    private File buildAesZipx(String name, int method) throws Exception {
        return buildAesZipx(name, method, false);
    }

    private File buildAesZipx(String name, int method, boolean lzmaEndMarker) throws Exception {
        return buildAesZipx(name, method, lzmaEndMarker, 0);
    }

    private File buildAesZipx(String name, int method, boolean lzmaEndMarker, int legacyFlags) throws Exception {
        return buildAesZipx(name,method,lzmaEndMarker,legacyFlags,
                method == 1 || method == 6 ? legacyLiterals(CONTENT, method, legacyFlags)
                        : compress(CONTENT, method, lzmaEndMarker));
    }
    private File buildAesZipx(String name, int method, boolean lzmaEndMarker, int legacyFlags, byte[] packed) throws Exception {
        byte[] compressed = packed.clone();
        AESEncrypter encrypter = new AESEncrypter(
                PASSWORD.clone(), AesKeyStrength.KEY_STRENGTH_256, true);
        encrypter.encryptData(compressed);

        ByteArrayOutputStream encryptedPayload = new ByteArrayOutputStream();
        encryptedPayload.write(encrypter.getSaltBytes());
        encryptedPayload.write(encrypter.getDerivedPasswordVerifier());
        encryptedPayload.write(compressed);
        encryptedPayload.write(encrypter.getFinalMac(), 0, 10);

        byte[] entryName = "folder/page.txt".getBytes(StandardCharsets.UTF_8);
        byte[] aesExtra = buildAesExtra(method);
        int storedSize = encryptedPayload.size();
        int versionNeeded = method == 14 || method == 98 ? 63 : 51;
        int flags = 0x0801 | legacyFlags | (method == 14 && lzmaEndMarker ? 0x0002 : 0);

        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        writeInt(zip, 0x04034b50);
        writeShort(zip, versionNeeded);
        writeShort(zip, flags);    // encrypted + UTF-8 filename (+ LZMA EOS)
        writeShort(zip, 99);       // real method is in 0x9901
        writeShort(zip, 0);
        writeShort(zip, 0);
        writeInt(zip, 0);          // AE-2 uses authentication instead of ZIP CRC
        writeInt(zip, storedSize);
        writeInt(zip, CONTENT.length);
        writeShort(zip, entryName.length);
        writeShort(zip, aesExtra.length);
        zip.write(entryName);
        zip.write(aesExtra);
        zip.write(encryptedPayload.toByteArray());

        int centralOffset = zip.size();
        writeInt(zip, 0x02014b50);
        writeShort(zip, versionNeeded);
        writeShort(zip, versionNeeded);
        writeShort(zip, flags);
        writeShort(zip, 99);
        writeShort(zip, 0);
        writeShort(zip, 0);
        writeInt(zip, 0);
        writeInt(zip, storedSize);
        writeInt(zip, CONTENT.length);
        writeShort(zip, entryName.length);
        writeShort(zip, aesExtra.length);
        writeShort(zip, 0);
        writeShort(zip, 0);
        writeShort(zip, 0);
        writeInt(zip, 0);
        writeInt(zip, 0);
        zip.write(entryName);
        zip.write(aesExtra);

        int centralSize = zip.size() - centralOffset;
        writeInt(zip, 0x06054b50);
        writeShort(zip, 0);
        writeShort(zip, 0);
        writeShort(zip, 1);
        writeShort(zip, 1);
        writeInt(zip, centralSize);
        writeInt(zip, centralOffset);
        writeShort(zip, 0);

        File archive = temp.newFile(name);
        Files.write(archive.toPath(), zip.toByteArray());
        return archive;
    }

    private static byte[] compress(byte[] content, int method, boolean lzmaEndMarker)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (method >= 2 && method <= 5) {
            out.write(ZipReduceInputStreamTest.literals(content));
        } else if (method == 12) {
            try (OutputStream compressed = new BZip2CompressorOutputStream(out)) {
                compressed.write(content);
            }
        } else if (method == 95) {
            try (OutputStream compressed = new XZOutputStream(out, new LZMA2Options())) {
                compressed.write(content);
            }
        } else if (method == 14) {
            LZMA2Options options = new LZMA2Options();
            options.setDictSize(1024 * 1024);
            out.write(9);          // LZMA SDK version 9.20
            out.write(20);
            writeShort(out, 5);
            out.write((options.getPb() * 5 + options.getLp()) * 9 + options.getLc());
            writeInt(out, options.getDictSize());
            try (OutputStream compressed = new LZMAOutputStream(
                    out, options, lzmaEndMarker)) {
                compressed.write(content);
            }
        } else if (method == 93 || method == 20) {
            out.write(com.github.luben.zstd.Zstd.compress(content));
        } else if (method == 96 || method == 97 || method == 98) {
            // Routing-only fixture; native decoder invocation is covered on Android.
            out.write(content);
        } else {
            throw new IllegalArgumentException("unsupported test method " + method);
        }
        return out.toByteArray();
    }

    private static byte[] buildAesExtra(int method) throws IOException {
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        writeShort(extra, 0x9901);
        writeShort(extra, 7);
        writeShort(extra, 2);      // AE-2
        extra.write('A');
        extra.write('E');
        extra.write(3);            // AES-256
        writeShort(extra, method);
        return extra.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private static int findSignature(byte[] data, int signature) {
        for (int i = 0; i + 3 < data.length; i++) {
            int value = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | ((data[i + 3] & 0xff) << 24);
            if (value == signature) return i;
        }
        return -1;
    }

    @Test public void aesTailDrainObservesInterruptionAndRetainsFailure() throws Exception {
        try (java.io.InputStream stream = aesStream(false)) {
            assertEquals(0, stream.read());
            Thread.currentThread().interrupt();
            try { org.junit.Assert.assertThrows(java.io.InterruptedIOException.class, () -> authenticate(stream)); }
            finally { Thread.interrupted(); }
            org.junit.Assert.assertThrows(java.io.InterruptedIOException.class, () -> stream.read());
        }
    }

    @Test public void aesBadAuthenticationCannotBeRetriedAsSuccess() throws Exception {
        try (java.io.InputStream stream = aesStream(true)) {
            IOException first = org.junit.Assert.assertThrows(IOException.class, () -> authenticate(stream));
            org.junit.Assert.assertSame(first, org.junit.Assert.assertThrows(IOException.class, () -> authenticate(stream)));
            org.junit.Assert.assertSame(first, org.junit.Assert.assertThrows(IOException.class, () -> stream.read()));
        }
    }

    @Test public void aesValidTailAuthenticatesAfterPartialRead() throws Exception {
        try (java.io.InputStream stream = aesStream(false)) {
            assertEquals(0, stream.read());
            authenticate(stream);
            authenticate(stream);
        }
    }

    @Test public void fixedLengthZipReadRejectsZeroProgress() throws Exception {
        java.lang.reflect.Method method = ZipxAesArchiveReader.class.getDeclaredMethod(
                "readFully", java.io.InputStream.class, byte[].class);
        method.setAccessible(true);
        java.io.InputStream zero = new java.io.InputStream() {
            @Override public int read() { return 0; }
            @Override public int read(byte[] data, int off, int len) { return 0; }
        };
        java.lang.reflect.InvocationTargetException failure = org.junit.Assert.assertThrows(
                java.lang.reflect.InvocationTargetException.class, () -> method.invoke(null, zero, new byte[2]));
        assertTrue(failure.getCause() instanceof IOException);
    }

    private static java.io.InputStream aesStream(boolean corrupt) throws Exception {
        byte[] data = new byte[64];
        AESEncrypter encryption = new AESEncrypter(PASSWORD.clone(), AesKeyStrength.KEY_STRENGTH_256, true);
        encryption.encryptData(data);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(encryption.getSaltBytes()); payload.write(encryption.getDerivedPasswordVerifier());
        payload.write(data); payload.write(encryption.getFinalMac(), 0, 10);
        byte[] bytes = payload.toByteArray();
        if (corrupt) bytes[bytes.length-1] ^= 1;
        Class<?> metadataType = Class.forName(ZipxAesArchiveReader.class.getName()+"$AesMetadata");
        java.lang.reflect.Constructor<?> metadata = metadataType.getDeclaredConstructors()[0];
        metadata.setAccessible(true);
        Object aes = metadata.newInstance(net.lingala.zip4j.model.enums.AesVersion.TWO, AesKeyStrength.KEY_STRENGTH_256, 0);
        Class<?> streamType = Class.forName(ZipxAesArchiveReader.class.getName()+"$WinZipAesInputStream");
        java.lang.reflect.Constructor<?> constructor = streamType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (java.io.InputStream) constructor.newInstance(new java.io.ByteArrayInputStream(bytes), (long) bytes.length, aes, PASSWORD, null);
    }

    private static void authenticate(java.io.InputStream stream) throws Exception {
        java.lang.reflect.Method method = stream.getClass().getDeclaredMethod("verifyAuthentication");
        method.setAccessible(true);
        try { method.invoke(stream); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw (IOException) failure.getCause(); }
    }

    @Test public void decodedLimitRejectsBeforeReturningOversizedChunk() throws Exception {
        java.io.ByteArrayInputStream raw = new java.io.ByteArrayInputStream(new byte[100]);
        try (java.io.InputStream checked = boundedIntegrity(raw, 2)) {
            org.junit.Assert.assertThrows(IOException.class, () -> checked.read(new byte[50]));
            assertEquals(97, raw.available());
        }
    }
    @Test public void decodedLimitAllowsExactSizeAndChecksForExtraByte() throws Exception {
        try (java.io.InputStream checked = boundedIntegrity(new java.io.ByteArrayInputStream(new byte[]{1,2}), 2)) {
            assertEquals(1, checked.read()); assertEquals(2, checked.read()); assertEquals(-1, checked.read());
        }
        try (java.io.InputStream checked = boundedIntegrity(new java.io.ByteArrayInputStream(new byte[]{1}), 0)) {
            org.junit.Assert.assertThrows(IOException.class, () -> checked.read());
        }
    }
    @Test public void unknownDecodedSizeRetainsStreamingSupport() throws Exception {
        try (java.io.InputStream checked = boundedIntegrity(new java.io.ByteArrayInputStream(new byte[]{1,2}), -1)) {
            assertArrayEquals(new byte[]{1,2}, checked.readAllBytes());
        }
    }
    @Test public void decodedSizeFailureIsSticky() throws Exception {
        try (java.io.InputStream checked = boundedIntegrity(new java.io.ByteArrayInputStream(new byte[]{1}), 0)) {
            IOException first = org.junit.Assert.assertThrows(IOException.class, () -> checked.read());
            org.junit.Assert.assertSame(first, org.junit.Assert.assertThrows(IOException.class, () -> checked.read()));
        }
    }
    private static java.io.InputStream boundedIntegrity(java.io.InputStream raw, long size) throws Exception {
        Class<?> type = Class.forName(ZipxAesArchiveReader.class.getName()+"$IntegrityInputStream");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(java.io.InputStream.class, long.class);
        constructor.setAccessible(true);
        return (java.io.InputStream) constructor.newInstance(raw, size);
    }

    @Test public void localAesVersionMustMatchCentralDirectory() throws Exception { mismatchedLocalAes(4,1,false); }
    @Test public void localAesStrengthMustMatchCentralDirectory() throws Exception { mismatchedLocalAes(8,1,false); }
    @Test public void localAesInnerMethodMustMatchCentralDirectory() throws Exception { mismatchedLocalAes(9,95,false); }
    @Test public void invalidLocalAesVendorCannotUseCentralFallback() throws Exception { mismatchedLocalAes(6,'X',false); }
    @Test public void splitLocalAesMetadataMismatchPreservesDestination() throws Exception { mismatchedLocalAes(4,1,true); }
    @Test public void validLocalAesExtraMayCrossVolumeBoundary() throws Exception {
        File original=buildAesZipx("aes-extra-crossing.zipx",12);
        byte[] bytes=Files.readAllBytes(original.toPath());
        int extra=30+(bytes[26]&255)+((bytes[27]&255)<<8);
        File[] parts=splitZipx(original,4+extra+5);
        File output=temp.newFile();
        assertEquals(Boolean.TRUE,ZipxAesArchiveReader.tryExtractSingleEntry(parts[parts.length-1],"folder/page.txt",output,PASSWORD));
        assertArrayEquals(CONTENT,Files.readAllBytes(output.toPath()));
    }
    private void mismatchedLocalAes(int field,int value,boolean split)throws Exception{
        File original=buildAesZipx("aes-extra-mismatch.zipx",12);
        byte[] bytes=Files.readAllBytes(original.toPath());
        int extra=30+(bytes[26]&255)+((bytes[27]&255)<<8);
        bytes[extra+field]=(byte)value;Files.write(original.toPath(),bytes);
        File selected=original;
        if(split){File[] parts=splitZipx(original,4+extra+5);selected=parts[parts.length-1];}
        final File archive=selected;File output=temp.newFile();Files.write(output.toPath(),new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class,()->ZipxAesArchiveReader.tryExtractSingleEntry(archive,"folder/page.txt",output,PASSWORD));
        assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
    }

    private static byte[] buildContent() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 1200; i++) {
            byte[] line = ("Readwide ZIPX AES fixture " + i + " / 한글 파일명 검증\n")
                    .getBytes(StandardCharsets.UTF_8);
            out.write(line, 0, line.length);
        }
        return out.toByteArray();
    }
}
