package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    private static final char[] PASSWORD = "readwide-zipx".toCharArray();
    private static final byte[] CONTENT = buildContent();

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

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
    public void aesPpmdRoutesPastJavaReaderAndFailsCleanlyWithoutNativeBackend() throws Exception {
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
    public void aesZstandardRoutesToLibarchiveBackend() throws Exception {
        File archive = buildAesZipx("zstandard.zipx", 93);
        assertFalse(ZipxAesArchiveReader.canExtractArchive(archive));
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
        byte[] compressed = compress(CONTENT, method, lzmaEndMarker);
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
        int flags = 0x0801 | (method == 14 && lzmaEndMarker ? 0x0002 : 0);

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
        if (method == 12) {
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
        } else if (method == 93 || method == 96 || method == 97 || method == 98) {
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
