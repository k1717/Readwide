package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class LightweightZipArchiveReaderTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void listEntries_plainZip_matchesArchiveSupportShape() throws Exception {
        File archive = buildZip("sample.zip", 6, 128, ZipEntry.DEFLATED);

        List<ArchiveSupport.EntryInfo> entries = LightweightZipArchiveReader.listEntries(archive);

        assertEquals("images/", entries.get(0).path);
        assertEquals("images/page000.txt", entries.get(1).path);
        assertEquals(7, entries.size());
    }

    @Test
    public void extractSingleEntry_plainZip_writesPayload() throws Exception {
        File archive = tempFolder.newFile("single.zip");
        byte[] payload = "zip payload".getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            ZipEntry entry = new ZipEntry("page001.txt");
            zip.putNextEntry(entry);
            zip.write(payload);
            zip.closeEntry();
        }
        File out = tempFolder.newFile("page001.txt");

        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page001.txt", out, null));

        assertArrayEquals(payload, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void extractArchive_plainZip_matchesZip4jOutputAndRecordsBenchmark() throws Exception {
        File archive = buildZip("benchmark.zip", 96, 8192, ZipEntry.DEFLATED);
        File lightweightOut = tempFolder.newFolder("lightweight");
        File zip4jOut = tempFolder.newFolder("zip4j");

        long lightweightStart = System.nanoTime();
        assertTrue(LightweightZipArchiveReader.extractArchiveIntoDirectory(archive, lightweightOut));
        long lightweightNs = System.nanoTime() - lightweightStart;

        long zip4jStart = System.nanoTime();
        new ZipFile(archive).extractAll(zip4jOut.getAbsolutePath());
        long zip4jNs = System.nanoTime() - zip4jStart;

        assertDirectoryPayloadsEqual(lightweightOut, zip4jOut);
        writeBenchmarkReport(lightweightNs, zip4jNs);
    }

    @Test
    public void encryptedZip_usesZip4jCompatibilityPath() throws Exception {
        File source = writeSourceFile("secret.txt", "encrypted payload");
        File archive = new File(tempFolder.getRoot(), "encrypted.zip");
        ZipParameters params = new ZipParameters();
        params.setFileNameInZip("secret/page001.txt");
        params.setCompressionMethod(CompressionMethod.DEFLATE);
        params.setEncryptFiles(true);
        params.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
        new ZipFile(archive, "pass".toCharArray()).addFile(source, params);

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, "pass".toCharArray());
        assertEquals("secret/", entries.get(0).path);
        assertEquals("secret/page001.txt", entries.get(1).path);

        File out = tempFolder.newFile("encrypted-out.txt");
        assertTrue(ArchiveSupport.extractSingleEntry(archive, "secret/page001.txt", out, "pass".toCharArray()));
        assertArrayEquals(Files.readAllBytes(source.toPath()), Files.readAllBytes(out.toPath()));
    }

    @Test
    public void encryptedZip_requiresPasswordForExtraction() throws Exception {
        File source = writeSourceFile("secret-required.txt", "encrypted payload");
        File archive = new File(tempFolder.getRoot(), "encrypted-required.zip");
        ZipParameters params = new ZipParameters();
        params.setFileNameInZip("secret/page001.txt");
        params.setCompressionMethod(CompressionMethod.DEFLATE);
        params.setEncryptFiles(true);
        params.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
        new ZipFile(archive, "pass".toCharArray()).addFile(source, params);

        assertTrue(ArchiveSupport.requiresPasswordForExtraction(archive));
    }

    @Test
    public void createZipArchive_preservesFileAndFolderEntries() throws Exception {
        File sourceFile = writeSourceFile("plain.txt", "plain payload");
        File folder = tempFolder.newFolder("folder-source");
        File nested = new File(folder, "nested.txt");
        Files.write(nested.toPath(), "nested payload".getBytes(StandardCharsets.UTF_8));
        File archive = new File(tempFolder.getRoot(), "created.zip");

        assertTrue(ArchiveSupport.createZipArchive(Arrays.asList(sourceFile, folder), archive, null));

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);
        assertEquals("plain.txt", entries.get(0).path);
        assertEquals("folder-source/", entries.get(1).path);
        assertEquals("folder-source/nested.txt", entries.get(2).path);
        File out = tempFolder.newFile("created-nested.txt");
        assertTrue(ArchiveSupport.extractSingleEntry(archive, "folder-source/nested.txt", out, null));
        assertArrayEquals(Files.readAllBytes(nested.toPath()), Files.readAllBytes(out.toPath()));
    }

    @Test
    public void splitZip_usesZip4jCompatibilityPath() throws Exception {
        File source1 = writeSourceFile("split-a.bin", repeatedText("split-a-", 80 * 1024));
        File source2 = writeSourceFile("split-b.bin", repeatedText("split-b-", 80 * 1024));
        File archive = new File(tempFolder.getRoot(), "split.zip");
        ZipParameters params = new ZipParameters();
        params.setCompressionMethod(CompressionMethod.DEFLATE);
        new ZipFile(archive).createSplitZipFile(Arrays.asList(source1, source2), params, true, 65536L);

        File outDir = new File(tempFolder.getRoot(), "split-out");
        assertTrue(ArchiveSupport.extractArchive(archive, outDir, false, null));

        assertArrayEquals(Files.readAllBytes(source1.toPath()), Files.readAllBytes(new File(outDir, source1.getName()).toPath()));
        assertArrayEquals(Files.readAllBytes(source2.toPath()), Files.readAllBytes(new File(outDir, source2.getName()).toPath()));
    }

    private File buildZip(String name, int fileCount, int payloadSize, int method) throws Exception {
        File archive = tempFolder.newFile(name);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            for (int i = 0; i < fileCount; i++) {
                byte[] payload = payloadFor(i, payloadSize);
                ZipEntry entry = new ZipEntry(String.format(Locale.ROOT, "images/page%03d.txt", i));
                entry.setMethod(method);
                zip.putNextEntry(entry);
                zip.write(payload);
                zip.closeEntry();
            }
        }
        return archive;
    }

    private byte[] payloadFor(int index, int size) {
        byte[] seed = ("page-" + index + "-").getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[size];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = seed[i % seed.length];
        }
        return payload;
    }

    private File writeSourceFile(String name, String text) throws Exception {
        File file = tempFolder.newFile(name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private String repeatedText(String seed, int length) {
        StringBuilder builder = new StringBuilder(length);
        while (builder.length() < length) builder.append(seed);
        return builder.substring(0, length);
    }

    private void assertDirectoryPayloadsEqual(File left, File right) throws Exception {
        List<File> leftFiles = regularFiles(left);
        List<File> rightFiles = regularFiles(right);
        assertEquals(leftFiles.size(), rightFiles.size());
        for (int i = 0; i < leftFiles.size(); i++) {
            String leftRel = left.toPath().relativize(leftFiles.get(i).toPath()).toString();
            String rightRel = right.toPath().relativize(rightFiles.get(i).toPath()).toString();
            assertEquals(leftRel, rightRel);
            assertArrayEquals(Files.readAllBytes(leftFiles.get(i).toPath()), Files.readAllBytes(rightFiles.get(i).toPath()));
        }
    }

    private List<File> regularFiles(File root) {
        List<File> result = new ArrayList<>();
        collectRegularFiles(root, result);
        result.sort(Comparator.comparing(File::getAbsolutePath));
        return result;
    }

    private void collectRegularFiles(File file, List<File> result) {
        if (file == null || !file.exists()) return;
        if (file.isFile()) {
            result.add(file);
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) collectRegularFiles(child, result);
    }

    private void writeBenchmarkReport(long lightweightNs, long zip4jNs) throws Exception {
        File report = new File("build/reports/archive-engine-benchmark.txt");
        File parent = report.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        String winner = lightweightNs <= zip4jNs ? "lightweight" : "zip4j";
        String text = "plain ZIP extract benchmark\n"
                + "fixture: 96 deflated files x 8192 bytes\n"
                + "lightweight_ns=" + lightweightNs + "\n"
                + "zip4j_ns=" + zip4jNs + "\n"
                + "winner=" + winner + "\n";
        Files.write(report.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
}
