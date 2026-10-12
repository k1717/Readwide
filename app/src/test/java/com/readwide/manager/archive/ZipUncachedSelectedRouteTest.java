package com.readwide.manager.archive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.*;
import net.lingala.zip4j.exception.ZipException;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.*;
import static org.junit.Assert.*;

/** First public selected read, without a listing/bulk/supplemental-index warmup. */
public final class ZipUncachedSelectedRouteTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] DATA = "Fresh ZIP selected payload\n".repeat(93).getBytes(StandardCharsets.UTF_8);
    @Before @After public void releaseIndexes() { ZipxAesArchiveReader.clearCachedIndexes(); }

    @Test public void freshPlainLzmaWithoutEndMarker() throws Exception { success(14, false); }
    @Test public void freshPlainLzmaWithEndMarker() throws Exception { success(14, true); }
    @Test public void freshPlainBzip2() throws Exception { success(12, false); }
    @Test public void freshPlainXz() throws Exception { success(95, false); }
    @Test public void freshStoredKeepsOrdinaryPath() throws Exception { success(0, false); }
    @Test public void freshDeflateKeepsOrdinaryPath() throws Exception { success(8, false); }
    @Test public void freshPlainLzmaCrcFailurePreservesTarget() throws Exception {
        File archive = archive(14, false, true), output = temporary.newFile();
        Files.write(output.toPath(), new byte[]{42});
        assertFalse(ZipxAesArchiveReader.hasCachedIndex(archive));
        assertFalse(ArchiveSupport.extractSingleEntry(archive, "page.bin", output, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
        assertTrue(ZipxAesArchiveReader.hasCachedIndex(archive));
    }
    @Test public void missingSelectedEntryLeavesTargetAlone() throws Exception {
        File archive = archive(14, false, false), output = temporary.newFile();
        Files.write(output.toPath(), new byte[]{42});
        assertFalse(ArchiveSupport.extractSingleEntry(archive, "missing.bin", output, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    @Test public void extendedMethodMetadataFailureAlreadyActivatesProbe() throws Exception {
        for (int method : new int[]{1, 6, 9, 12, 14, 20, 93, 95, 96, 97}) {
            // These ZIP methods are outside Zip4j's enum; no output decode is attempted here.
            assertThrows(ZipException.class,
                    () -> net.lingala.zip4j.model.enums.CompressionMethod.getCompressionMethodFromCode(method));
            File archive = rawArchive(method, 0x800, new byte[]{1}, false);
            assertFalse(ZipxAesArchiveReader.hasCachedIndex(archive));
            assertTrue("method " + method, ArchiveSupport.needsZipxAesProbe(archive));
            assertFalse("probe must not warm supplemental index", ZipxAesArchiveReader.hasCachedIndex(archive));
        }
    }
    @Test public void freshZstandardWithoutCodecPreservesTarget() throws Exception {
        Assume.assumeFalse(LibarchiveNativeBridge.isAvailable());
        for (int method : new int[]{20, 93}) {
            File archive = rawArchive(method, 0x800, new byte[]{0x28, (byte)0xb5, 0x2f, (byte)0xfd}, false);
            File output = temporary.newFile(); Files.write(output.toPath(), new byte[]{42});
            assertFalse(ZipxAesArchiveReader.hasCachedIndex(archive));
            assertFalse(ArchiveSupport.extractSingleEntry(archive, "page.bin", output, null));
            assertTrue("supplemental route was entered", ZipxAesArchiveReader.hasCachedIndex(archive));
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
        }
    }
    private void success(int method, boolean eos) throws Exception {
        File archive = archive(method, eos, false), output = temporary.newFile();
        assertFalse(ZipxAesArchiveReader.hasCachedIndex(archive));
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(archive, "page.bin", output, null);
        assertTrue(result.failure + ": " + result.detail, result.success);
        assertArrayEquals(DATA, Files.readAllBytes(output.toPath()));
        assertEquals(method != 0 && method != 8, ZipxAesArchiveReader.hasCachedIndex(archive));
    }
    @Test public void plainXzDirectBulkUsesExistingCodec() throws Exception {
        File archive = archive(95, false, false), output = temporary.newFolder();
        assertTrue(ZipxAesArchiveReader.extractArchiveIntoDirectory(archive, output, null, null, null));
        assertArrayEquals(DATA, Files.readAllBytes(new File(output, "page.bin").toPath()));
    }
    @Test public void plainXzCrcFailurePreservesSelectedTarget() throws Exception {
        File archive = archive(95, false, true), output = temporary.newFile();
        Files.write(output.toPath(), new byte[]{42});
        assertFalse(ArchiveSupport.extractSingleEntry(archive, "page.bin", output, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    @Test public void ordinaryAesDeflateStillRequiresCorrectPassword() throws Exception {
        File archive = new File(temporary.newFolder(), "aes.zip"), output = temporary.newFile();
        char[] password = "test-password".toCharArray();
        net.lingala.zip4j.model.ZipParameters params = new net.lingala.zip4j.model.ZipParameters();
        params.setFileNameInZip("page.bin"); params.setEncryptFiles(true);
        params.setEncryptionMethod(net.lingala.zip4j.model.enums.EncryptionMethod.AES);
        params.setCompressionMethod(net.lingala.zip4j.model.enums.CompressionMethod.DEFLATE);
        try (net.lingala.zip4j.ZipFile zip = new net.lingala.zip4j.ZipFile(archive, password)) {
            zip.addStream(new ByteArrayInputStream(DATA), params);
        }
        Files.write(output.toPath(), new byte[]{42});
        assertFalse(ArchiveSupport.extractSingleEntry(archive, "page.bin", output, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
        assertFalse(ArchiveSupport.extractSingleEntry(archive, "page.bin", output, new char[]{'x'}));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page.bin", output, password));
        assertArrayEquals(DATA, Files.readAllBytes(output.toPath()));
    }
    private File archive(int method, boolean eos, boolean badCrc) throws Exception {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        if (method == 0) packed.write(DATA);
        else if (method == 8) {
            Deflater deflater = new Deflater(6, true);
            try (OutputStream stream = new DeflaterOutputStream(packed, deflater)) { stream.write(DATA); }
            finally { deflater.end(); }
        } else if (method == 12) {
            try (OutputStream stream = new BZip2CompressorOutputStream(packed)) { stream.write(DATA); }
        } else if (method == 95) {
            try (OutputStream stream = new XZOutputStream(packed, new LZMA2Options())) { stream.write(DATA); }
        } else {
            LZMA2Options options = new LZMA2Options(); options.setDictSize(1 << 20);
            packed.write(9); packed.write(20); le(packed, 5, 2);
            packed.write((options.getPb() * 5 + options.getLp()) * 9 + options.getLc());
            le(packed, options.getDictSize(), 4);
            try (OutputStream stream = new LZMAOutputStream(packed, options, eos)) { stream.write(DATA); }
        }
        return rawArchive(method, 0x800 | (method == 14 && eos ? 2 : 0), packed.toByteArray(), badCrc);
    }
    private File rawArchive(int method, int flags, byte[] packed, boolean badCrc) throws IOException {
        byte[] name = "page.bin".getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32(); crc.update(DATA); long checksum = crc.getValue() ^ (badCrc ? 1 : 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        le(out, 0x04034b50, 4); le(out, 63, 2); le(out, flags, 2); le(out, method, 2);
        le(out, 0, 4); le(out, checksum, 4); le(out, packed.length, 4); le(out, DATA.length, 4);
        le(out, name.length, 2); le(out, 0, 2); out.write(name); out.write(packed);
        int central = out.size(); le(out, 0x02014b50, 4); le(out, 63, 2); le(out, 63, 2);
        le(out, flags, 2); le(out, method, 2); le(out, 0, 4); le(out, checksum, 4);
        le(out, packed.length, 4); le(out, DATA.length, 4); le(out, name.length, 2);
        le(out, 0, 2); le(out, 0, 2); le(out, 0, 2); le(out, 0, 2); le(out, 0, 4); le(out, 0, 4); out.write(name);
        int size = out.size() - central; le(out, 0x06054b50, 4); le(out, 0, 2); le(out, 0, 2);
        le(out, 1, 2); le(out, 1, 2); le(out, size, 4); le(out, central, 4); le(out, 0, 2);
        File archive = new File(temporary.newFolder(), "fresh.zip"); Files.write(archive.toPath(), out.toByteArray()); return archive;
    }
    private static void le(OutputStream out, long value, int count) throws IOException {
        for (int i = 0; i < count; i++) out.write((int)(value >>> (8 * i)) & 255);
    }
}
