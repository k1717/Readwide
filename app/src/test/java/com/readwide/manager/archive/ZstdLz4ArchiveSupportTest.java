package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * End-to-end coverage for the Zstandard and LZ4 archive paths added alongside
 * the existing gzip/bzip2/xz/lzma/Z family: {@code .tar.zst}/{@code .tzst},
 * {@code .tar.lz4}, and the single-file {@code .zst}/{@code .lz4} forms.
 *
 * The fixtures are real frames (zstd magic 28 B5 2F FD, LZ4 frame magic
 * 04 22 4D 18) wrapping a deterministic two-entry USTAR tar or a single text
 * payload, embedded as base64. Zstandard decodes through commons-compress
 * backed by the bundled zstd-jni (which ships desktop natives, so this runs
 * in plain JVM unit tests); LZ4 decodes through the pure-Java framed reader.
 */
public class ZstdLz4ArchiveSupportTest {

    private static final String PAYLOAD_TEXT =
            "Readwide zstd/lz4 fixture payload. UTF-8: \ud55c\uae00 \ub0b4\uc6a9.\n";
    private static final String SECOND_TEXT = "second entry body\n";

    // Two-entry tar (payload.txt, dir/second.txt) compressed with zstd -19.
    private static final String TAR_ZST_BASE64 =
            "KLUv/WAAJ00FALIHHR+g7YSqWlfHXqodswpk3Uxyk5qBRO51q1WkSQ3m9xjKmqac13if9hfYIIiJerY18EhTQAg/U+zK50YS"
            + "9M5PkEBhmv/Nr55F0N9NGnS3tt86wnTeC62V8tyti9anGNtqb8X4Esa11OJK7z0q7VkXdKYkLVcBESDwLk1cDyRgMDwo5m7K"
            + "/8XCGHUUwTkAUzAA7DCsatjBi+elJHAp9yKxlVsGDbCaCh0=";

    // The same tar compressed as a single LZ4 frame.
    private static final String TAR_LZ4_BASE64 =
            "BCJNGGhAACgAAAAAAAAV7AAAAM9wYXlsb2FkLnR4dAABAEVxMDAwMDY0NAgAPDAwMAgA4DAwNzEAMTQ1MjQ3NzA0IACPMTAx"
            + "NzcAIDCRAEUIAgB/dXN0YXIAMGwAUg8CAID0C1JlYWR3aWRlIHpzdGQvbHo0IGZpeHR1cmUgGgL/CCBVVEYtODog7ZWc6riA"
            + "IOuCtOyaqS4KzACADwIA/yKfZGlyL3NlY29uAwRIAOMDDwAECiwyMgAEPzU3MwAE/1QC/AG/IGVudHJ5IGJvZHnZA/+2DwIA"
            + "//////////////////////////////////////////8vUAAAAAAAAAAAAA==";

    // payload.txt alone, zstd frame.
    private static final String SINGLE_ZST_BASE64 =
            "KLUv/SA5yQEAUmVhZHdpZGUgenN0ZC9sejQgZml4dHVyZSBwYXlsb2FkLiBVVEYtODog7ZWc6riAIOuCtOyaqS4K";

    // payload.txt alone, LZ4 frame.
    private static final String SINGLE_LZ4_BASE64 =
            "BCJNGGhAOQAAAAAAAADwOQAAgFJlYWR3aWRlIHpzdGQvbHo0IGZpeHR1cmUgcGF5bG9hZC4gVVRGLTg6IO2VnOq4gCDrgrTs"
            + "mqkuCgAAAAA=";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void detectsZstdAndLz4Names() {
        assertEquals(ArchiveSupport.Type.TAR_ZST, ArchiveSupport.getSupportedArchiveType("backup.tar.zst"));
        assertEquals(ArchiveSupport.Type.TAR_ZST, ArchiveSupport.getSupportedArchiveType("backup.tzst"));
        assertEquals(ArchiveSupport.Type.TAR_LZ4, ArchiveSupport.getSupportedArchiveType("backup.tar.lz4"));
        assertEquals(ArchiveSupport.Type.SINGLE_ZST, ArchiveSupport.getSupportedArchiveType("notes.zst"));
        assertEquals(ArchiveSupport.Type.SINGLE_LZ4, ArchiveSupport.getSupportedArchiveType("notes.lz4"));
        // Numeric split parts resolve through the same names.
        assertEquals(ArchiveSupport.Type.TAR_ZST, ArchiveSupport.getSupportedArchiveType("backup.tar.zst.001"));
        // The bare .z family must not swallow the new suffixes, and vice versa.
        assertEquals(ArchiveSupport.Type.SINGLE_Z, ArchiveSupport.getSupportedArchiveType("notes.z"));
        assertEquals(ArchiveSupport.Type.TAR_Z, ArchiveSupport.getSupportedArchiveType("backup.taz"));
    }

    @Test
    public void tarZstExtractsBothEntries() throws Exception {
        assertTarArchiveExtracts(writeFixture("fixture.tar.zst", TAR_ZST_BASE64));
    }

    @Test
    public void tzstExtractsBothEntries() throws Exception {
        assertTarArchiveExtracts(writeFixture("fixture.tzst", TAR_ZST_BASE64));
    }

    @Test
    public void tarLz4ExtractsBothEntries() throws Exception {
        assertTarArchiveExtracts(writeFixture("fixture.tar.lz4", TAR_LZ4_BASE64));
    }

    @Test
    public void singleZstDecompresses() throws Exception {
        assertSingleDecompresses(writeFixture("payload.txt.zst", SINGLE_ZST_BASE64), "payload.txt");
    }

    @Test
    public void singleLz4Decompresses() throws Exception {
        assertSingleDecompresses(writeFixture("payload.txt.lz4", SINGLE_LZ4_BASE64), "payload.txt");
    }

    private void assertTarArchiveExtracts(File archive) throws Exception {
        assertTrue(ArchiveSupport.isSupportedArchive(archive));
        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);
        assertEquals(2, countFiles(entries));
        File outDir = tempFolder.newFolder();
        assertTrue(ArchiveSupport.extractArchive(archive, outDir, true, null));
        assertEquals(PAYLOAD_TEXT, readUtf8(findFile(outDir, "payload.txt")));
        assertEquals(SECOND_TEXT, readUtf8(findFile(outDir, "second.txt")));
    }

    private void assertSingleDecompresses(File archive, String expectedName) throws Exception {
        assertTrue(ArchiveSupport.isSupportedArchive(archive));
        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);
        assertEquals(1, countFiles(entries));
        File outDir = tempFolder.newFolder();
        assertTrue(ArchiveSupport.extractArchive(archive, outDir, true, null));
        assertEquals(PAYLOAD_TEXT, readUtf8(findFile(outDir, expectedName)));
    }

    private File writeFixture(String name, String base64) throws IOException {
        File file = new File(tempFolder.getRoot(), name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(Base64.getDecoder().decode(base64));
        }
        return file;
    }

    private static int countFiles(List<ArchiveSupport.EntryInfo> entries) {
        int n = 0;
        for (ArchiveSupport.EntryInfo e : entries) {
            if (!e.directory) n++;
        }
        return n;
    }

    private static File findFile(File root, String name) {
        File[] children = root.listFiles();
        if (children == null) return new File(root, name);
        for (File child : children) {
            if (child.isFile() && child.getName().equals(name)) return child;
            if (child.isDirectory()) {
                File nested = findFile(child, name);
                if (nested.isFile()) return nested;
            }
        }
        return new File(root, name);
    }

    private static String readUtf8(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
