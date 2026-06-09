package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public class UnixArchiveDecoderTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void tarFamily_listAndExtractNestedPayloads() throws Exception {
        List<File> archives = Arrays.asList(
                buildTarArchive("sample.tar", Compression.NONE),
                buildTarArchive("sample.tar.gz", Compression.GZIP),
                buildTarArchive("sample.tgz", Compression.GZIP),
                buildTarArchive("sample.tar.bz2", Compression.BZIP2),
                buildTarArchive("sample.tbz2", Compression.BZIP2),
                buildTarArchive("sample.tar.xz", Compression.XZ),
                buildTarArchive("sample.txz", Compression.XZ),
                buildTarArchive("sample.tar.lzma", Compression.LZMA),
                buildTarArchive("sample.tlz", Compression.LZMA)
        );

        for (File archive : archives) {
            List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);
            assertEquals(3, entries.size());
            assertEquals("book/", entries.get(0).path);
            assertEquals("book/page001.txt", entries.get(1).path);
            assertEquals("book/page002.txt", entries.get(2).path);

            File singleOut = tempFolder.newFile("single-" + archive.getName().replace('.', '_') + ".txt");
            assertTrue(ArchiveSupport.extractSingleEntry(archive, "book/page002.txt", singleOut, null));
            assertArrayEquals(payload("two"), Files.readAllBytes(singleOut.toPath()));

            File allOut = new File(tempFolder.getRoot(), "out-" + archive.getName().replace('.', '_'));
            assertTrue(ArchiveSupport.extractArchive(archive, allOut, false, null));
            assertArrayEquals(payload("one"), Files.readAllBytes(new File(allOut, "book/page001.txt").toPath()));
            assertArrayEquals(payload("two"), Files.readAllBytes(new File(allOut, "book/page002.txt").toPath()));
        }
    }

    @Test
    public void tarDecoder_rejectsUnsafePathWithoutWritingOutsideTarget() throws Exception {
        File archive = buildTarArchiveWithEntry("../outside.txt", payload("bad"), "unsafe.tar");
        File target = new File(tempFolder.getRoot(), "unsafe-out");
        File outside = new File(tempFolder.getRoot(), "outside.txt");

        assertTrue(ArchiveSupport.listEntries(archive, null).isEmpty());
        assertFalse(ArchiveSupport.extractArchive(archive, target, false, null));
        assertFalse(target.exists());
        assertFalse(outside.exists());
    }

    @Test
    public void singleUnixCompression_extractsPayloadByDerivedName() throws Exception {
        List<File> archives = Arrays.asList(
                buildSingleCompressed("plain.txt.gz", Compression.GZIP),
                buildSingleCompressed("plain.txt.bz2", Compression.BZIP2),
                buildSingleCompressed("plain.txt.xz", Compression.XZ),
                buildSingleCompressed("plain.txt.lzma", Compression.LZMA)
        );

        for (File archive : archives) {
            List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);
            assertEquals(1, entries.size());
            assertEquals("plain.txt", entries.get(0).path);

            File out = tempFolder.newFile("single-out-" + archive.getName().replace('.', '_'));
            assertTrue(ArchiveSupport.extractSingleEntry(archive, "plain.txt", out, null));
            assertArrayEquals(payload("single"), Files.readAllBytes(out.toPath()));
        }
    }

    @Test
    public void unixFormats_ignoreSuppliedPasswordAndStillDecode() throws Exception {
        File tar = buildTarArchive("password-ignored.tar.gz", Compression.GZIP);
        File gzip = buildSingleCompressed("password-ignored.txt.gz", Compression.GZIP);

        File tarOut = tempFolder.newFile("password-tar.txt");
        assertTrue(ArchiveSupport.extractSingleEntry(tar, "book/page001.txt", tarOut, "unused".toCharArray()));
        assertArrayEquals(payload("one"), Files.readAllBytes(tarOut.toPath()));

        File gzipOut = tempFolder.newFile("password-gzip.txt");
        assertTrue(ArchiveSupport.extractSingleEntry(gzip, "password-ignored.txt", gzipOut, "unused".toCharArray()));
        assertArrayEquals(payload("single"), Files.readAllBytes(gzipOut.toPath()));
    }

    @Test
    public void zDecoder_invalidPayloadFailsWithoutLeavingOutput() throws Exception {
        File archive = tempFolder.newFile("broken.txt.Z");
        Files.write(archive.toPath(), "not unix compress".getBytes(StandardCharsets.US_ASCII));
        File out = tempFolder.newFile("broken.txt");

        assertFalse(ArchiveSupport.extractSingleEntry(archive, "broken.txt", out, null));
        assertFalse(out.exists());
    }

    private File buildTarArchive(String name, Compression compression) throws Exception {
        File archive = tempFolder.newFile(name);
        try (OutputStream fileOut = new BufferedOutputStream(new FileOutputStream(archive));
             OutputStream payloadOut = wrapOutput(fileOut, compression);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(payloadOut)) {
            addTarEntry(tar, "book/page001.txt", payload("one"));
            addTarEntry(tar, "book/page002.txt", payload("two"));
        }
        return archive;
    }

    private File buildTarArchiveWithEntry(String entryName, byte[] payload, String archiveName) throws Exception {
        File archive = tempFolder.newFile(archiveName);
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new FileOutputStream(archive))) {
            addTarEntry(tar, entryName, payload);
        }
        return archive;
    }

    private File buildSingleCompressed(String name, Compression compression) throws Exception {
        File archive = tempFolder.newFile(name);
        try (OutputStream fileOut = new BufferedOutputStream(new FileOutputStream(archive));
             OutputStream compressed = wrapOutput(fileOut, compression)) {
            compressed.write(payload("single"));
        }
        return archive;
    }

    private void addTarEntry(TarArchiveOutputStream tar, String name, byte[] payload) throws Exception {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(payload.length);
        tar.putArchiveEntry(entry);
        tar.write(payload);
        tar.closeArchiveEntry();
    }

    private OutputStream wrapOutput(OutputStream out, Compression compression) throws Exception {
        switch (compression) {
            case GZIP:
                return new GzipCompressorOutputStream(out);
            case BZIP2:
                return new BZip2CompressorOutputStream(out);
            case XZ:
                return new XZCompressorOutputStream(out);
            case LZMA:
                return new LZMACompressorOutputStream(out);
            case NONE:
            default:
                return out;
        }
    }

    private byte[] payload(String label) {
        return ("payload-" + label).getBytes(StandardCharsets.UTF_8);
    }

    private enum Compression {
        NONE,
        GZIP,
        BZIP2,
        XZ,
        LZMA
    }
}
