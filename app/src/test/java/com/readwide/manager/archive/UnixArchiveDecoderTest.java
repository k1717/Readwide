package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

    @Test public void concatenatedSingleStreamsDecodeEveryMember() throws Exception {
        for (Compression compression : framedFormats()) {
            File archive = concatenated(compression, payload("one"), payload("two"), false);
            File output = tempFolder.newFile();
            String name = ArchiveSupport.listEntries(archive, null).get(0).path;
            assertTrue(ArchiveSupport.extractSingleEntry(archive, name, output, null));
            assertArrayEquals(join(payload("one"), payload("two")), Files.readAllBytes(output.toPath()));
        }
    }

    @Test public void tarPayloadCanCrossCompressedMemberBoundaries() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            addTarEntry(tar, "page.txt", payload("split"));
        }
        byte[] tarBytes = bytes.toByteArray();
        for (Compression compression : framedFormats()) {
            File archive = concatenated(compression, Arrays.copyOf(tarBytes, 513),
                    Arrays.copyOfRange(tarBytes, 513, tarBytes.length), true);
            assertEquals("page.txt", ArchiveSupport.listEntries(archive, null).get(0).path);
            File output = tempFolder.newFile();
            assertTrue(ArchiveSupport.extractSingleEntry(archive, "page.txt", output, null));
            assertArrayEquals(payload("split"), Files.readAllBytes(output.toPath()));
            File directory = new File(tempFolder.getRoot(), "bulk-" + compression);
            assertTrue(ArchiveSupport.extractArchive(archive, directory, false, null));
            assertArrayEquals(payload("split"), Files.readAllBytes(new File(directory, "page.txt").toPath()));
            try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null)) {
                assertEquals("page.txt", reader.nextEntry().path);
                ByteArrayOutputStream decoded = new ByteArrayOutputStream();
                byte[] buffer = new byte[3];
                int read;
                while ((read = reader.read(buffer)) != -1) decoded.write(buffer, 0, read);
                assertArrayEquals(payload("split"), decoded.toByteArray());
            }
        }
    }

    @Test public void emptyFirstMemberDoesNotHideFollowingPayload() throws Exception {
        for (Compression compression : framedFormats()) {
            File archive = concatenated(compression, new byte[0], payload("two"), false);
            File output = tempFolder.newFile();
            extractGuarded(archive, output);
            assertArrayEquals(payload("two"), Files.readAllBytes(output.toPath()));
        }
    }

    @Test public void truncatedLaterMemberRestoresExistingTarget() throws Exception {
        for (Compression compression : framedFormats()) {
            File archive = concatenated(compression, payload("one"), payload("two"), false);
            byte[] bytes = Files.readAllBytes(archive.toPath());
            Files.write(archive.toPath(), Arrays.copyOf(bytes, bytes.length - 4));
            assertGuardedFailure(archive);
        }
    }

    @Test public void invalidTrailingMemberRestoresExistingTarget() throws Exception {
        for (Compression compression : framedFormats()) {
            File archive = concatenated(compression, payload("one"), payload("two"), false);
            try (FileOutputStream append = new FileOutputStream(archive, true)) { append.write(0x7f); }
            assertGuardedFailure(archive);
        }
    }

    @Test public void decodedBudgetIsSharedAcrossCompressedMembers() throws Exception {
        File archive = concatenated(Compression.GZIP, new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, false);
        try (ArchiveExtractionByteBudget.Scope ignored = ArchiveExtractionByteBudget.begin(4)) {
            assertGuardedFailure(archive);
        }
    }

    private void assertGuardedFailure(File archive) throws Exception {
        File output = tempFolder.newFile();
        Files.writeString(output.toPath(), "existing");
        try { extractGuarded(archive, output); fail("Expected failure for " + archive.getName()); }
        catch (IOException expected) { }
        assertEquals("existing", Files.readString(output.toPath()));
    }

    // The public preview wrapper intentionally deletes disposable targets after failure.
    private static void extractGuarded(File archive, File output) throws Exception {
        java.lang.reflect.Method method = ArchiveSupport.class.getDeclaredMethod("extractSingleCompressedPayload",
                File.class, File.class, ArchiveSupport.Type.class,
                com.readwide.manager.util.FileOperationProgress.class);
        method.setAccessible(true);
        try { assertEquals(Boolean.TRUE, method.invoke(null, archive, output,
                ArchiveSupport.getSupportedArchiveType(archive.getName()), null)); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw failure;
        }
    }

    private File concatenated(Compression compression, byte[] first, byte[] second, boolean tar) throws Exception {
        String extension = compression == Compression.GZIP ? ".gz" : compression == Compression.BZIP2 ? ".bz2"
                : compression == Compression.XZ ? ".xz" : ".lz4";
        File archive = tempFolder.newFile("concat-" + System.nanoTime() + (tar ? ".tar" : ".txt") + extension);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] member : new byte[][]{first, second}) {
            try (OutputStream output = wrapOutput(bytes, compression)) { output.write(member); }
        }
        Files.write(archive.toPath(), bytes.toByteArray());
        return archive;
    }

    private static Compression[] framedFormats() {
        return new Compression[]{Compression.GZIP, Compression.BZIP2, Compression.XZ, Compression.LZ4};
    }

    private static byte[] join(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

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
            case LZ4:
                return new FramedLZ4CompressorOutputStream(out);
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
        LZ4,
        LZMA
    }
}
