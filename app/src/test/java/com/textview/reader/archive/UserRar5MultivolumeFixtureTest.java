package com.textview.reader.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.List;

public class UserRar5MultivolumeFixtureTest {
    private static final char[] PASSWORD = "password".toCharArray();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void pass111Rar5Multivolume_listsAndExtractsGitignore() throws Exception {
        File archive = fixture("Readwide-1.0.0_pass111_github_docs_release_link_minimal.part1.rar");
        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, PASSWORD);
        ArchiveSupport.EntryInfo gitignore = findEntry(entries, ".gitignore");
        assertNotNull(".gitignore must be listed from pass111 multivolume RAR", gitignore);
        assertPass111GitignoreDecodesLikeSourceZip(archive, gitignore.path);

        File out = tempFolder.newFile("pass111-gitignore.out");
        ArchiveSupport.ExtractionResult result =
                ArchiveSupport.extractSingleEntryDetailed(archive, gitignore.path, out, PASSWORD);
        assertTrue("pass111 .gitignore extraction failed: "
                        + result.failure + " " + result.detail,
                result.success);
        assertTrue(out.isFile());
        assertTrue(Files.size(out.toPath()) > 0L);
    }

    @Test
    public void pass111Rar5Multivolume_extractsReadmeLikeSourceZip() throws Exception {
        File archive = fixture("Readwide-1.0.0_pass111_github_docs_release_link_minimal.part1.rar");
        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, PASSWORD);
        ArchiveSupport.EntryInfo readme = findEntry(entries, "README.md");
        assertNotNull("README.md must be listed from pass111 multivolume RAR", readme);
        assertPass111EntryExtractsLikeSourceZip(archive, readme.path, "README.md");
    }

    @Test
    public void pass251EncryptedRar5Multivolume_listsAndExtractsFirstFile() throws Exception {
        File archive = fixture("Readwide-1.0.2_pass251_private_name_audit_docs_source.part01.rar");
        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, PASSWORD);
        ArchiveSupport.EntryInfo first = firstFile(entries);
        assertNotNull("pass251 must list at least one file entry", first);

        File out = tempFolder.newFile("pass251-first-file.out");
        ArchiveSupport.ExtractionResult result =
                ArchiveSupport.extractSingleEntryDetailed(archive, first.path, out, PASSWORD);
        assertTrue("pass251 first-file extraction failed for " + first.path + ": "
                        + result.failure + " " + result.detail,
                result.success);
        assertTrue(out.isFile());
        assertTrue(Files.size(out.toPath()) > 0L);
    }

    @Test
    public void pass111LaterVolume_resolvesToFirstVolumeAndExtractsGitignore() throws Exception {
        File archive = fixture("Readwide-1.0.0_pass111_github_docs_release_link_minimal.part4.rar");
        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, PASSWORD);
        ArchiveSupport.EntryInfo gitignore = findEntry(entries, ".gitignore");
        assertNotNull(".gitignore must be listed when opening a later pass111 volume", gitignore);

        File out = tempFolder.newFile("pass111-later-gitignore.out");
        ArchiveSupport.ExtractionResult result =
                ArchiveSupport.extractSingleEntryDetailed(archive, gitignore.path, out, PASSWORD);
        assertTrue("pass111 later-volume .gitignore extraction failed: "
                        + result.failure + " " + result.detail,
                result.success);
        assertTrue(out.isFile());
        assertTrue(Files.size(out.toPath()) > 0L);
    }

    @Test
    public void pass251LaterVolume_resolvesToFirstVolumeAndExtractsFirstFile() throws Exception {
        File archive = fixture("Readwide-1.0.2_pass251_private_name_audit_docs_source.part05.rar");
        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, PASSWORD);
        ArchiveSupport.EntryInfo first = firstFile(entries);
        assertNotNull("pass251 must list a first file when opening a later volume", first);

        File out = tempFolder.newFile("pass251-later-first-file.out");
        ArchiveSupport.ExtractionResult result =
                ArchiveSupport.extractSingleEntryDetailed(archive, first.path, out, PASSWORD);
        assertTrue("pass251 later-volume first-file extraction failed for " + first.path + ": "
                        + result.failure + " " + result.detail,
                result.success);
        assertTrue(out.isFile());
        assertTrue(Files.size(out.toPath()) > 0L);
    }

    @Test
    public void pass251EncryptedRar5Multivolume_extractsWholeArchive() throws Exception {
        File archive = fixture("Readwide-1.0.2_pass251_private_name_audit_docs_source.part01.rar");
        File outDir = tempFolder.newFolder("pass251-whole");
        ArchiveSupport.ExtractionResult result =
                ArchiveSupport.extractArchiveDetailed(archive, outDir, true, PASSWORD, null);
        assertTrue("pass251 whole-archive extraction failed: "
                        + result.failure + " " + result.detail,
                result.success);
        assertTrue("whole extraction should create at least one file", containsFile(outDir));
    }

    private static File fixture(String name) {
        String path = System.getProperty("textview.externalArchiveFixtureDir");
        if (path == null || path.trim().isEmpty()) {
            path = System.getenv("TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR");
        }
        assumeTrue("External archive fixture dir not provided",
                path != null && !path.trim().isEmpty());
        File file = new File(path, name);
        assumeTrue("Missing fixture: " + file.getAbsolutePath(), file.isFile());
        return file;
    }

    private static ArchiveSupport.EntryInfo findEntry(List<ArchiveSupport.EntryInfo> entries,
                                                      String suffix) {
        assertNotNull(entries);
        assertFalse("entry list must not be empty", entries.isEmpty());
        for (ArchiveSupport.EntryInfo entry : entries) {
            if (entry != null && !entry.directory && entry.path.endsWith(suffix)) {
                return entry;
            }
        }
        return null;
    }

    private static ArchiveSupport.EntryInfo firstFile(List<ArchiveSupport.EntryInfo> entries) {
        assertNotNull(entries);
        for (ArchiveSupport.EntryInfo entry : entries) {
            if (entry != null && !entry.directory) return entry;
        }
        return null;
    }

    private static boolean containsFile(File root) {
        File[] files = root.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.isFile()) return true;
            if (file.isDirectory() && containsFile(file)) return true;
        }
        return false;
    }

    private static void assertPass111GitignoreDecodesLikeSourceZip(File archive,
                                                                   String entryPath) throws Exception {
        RarArchiveReader.RarEntry entry = null;
        for (RarArchiveReader.RarEntry candidate :
                RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, PASSWORD)) {
            if (candidate != null && !candidate.directory && candidate.path.equals(entryPath)) {
                entry = candidate;
                break;
            }
        }
        assertNotNull("RAR .gitignore diagnostic entry missing", entry);
        byte[] packed = readPackedPayload(entry, archive);

        byte[] actual = new Rar5CompressedDecoder()
                .decodeEntry(packed, entry.unpackedSize, entry.rar5CompressionInfo);
        byte[] expected = readPass111ZipEntry(archive.getParentFile(), ".gitignore");
        assertTrue("source ZIP .gitignore must not be empty", expected.length > 0);
        assertBytesEqual("pass111 .gitignore decoded bytes differ", expected, actual);
    }

    private static void assertPass111EntryExtractsLikeSourceZip(File archive,
                                                               String rarEntryPath,
                                                               String sourceZipSuffix) throws Exception {
        File out = File.createTempFile("pass111-entry", ".out");
        try {
            ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                    archive, rarEntryPath, out, PASSWORD);
            assertTrue("pass111 extraction failed for " + rarEntryPath + ": "
                            + result.failure + " " + result.detail,
                    result.success);
            byte[] actual = Files.readAllBytes(out.toPath());
            byte[] expected = readPass111ZipEntry(archive.getParentFile(), sourceZipSuffix);
            assertTrue("source ZIP entry must not be empty: " + sourceZipSuffix, expected.length > 0);
            assertBytesEqual("pass111 extracted bytes differ for " + rarEntryPath, expected, actual);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    private static byte[] readPass111ZipEntry(File root, String suffix) throws Exception {
        File zip = new File(root, "Readwide-1.0.0_pass111_github_docs_release_link_minimal.zip");
        assumeTrue("Missing pass111 source ZIP for byte comparison: " + zip.getAbsolutePath(), zip.isFile());
        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry chosen = null;
            java.util.Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(suffix)) {
                    chosen = entry;
                    break;
                }
            }
            assertNotNull("source ZIP entry missing: " + suffix, chosen);
            try (InputStream in = zf.getInputStream(chosen)) {
                return readAll(in);
            }
        }
    }

    private static void assertBytesEqual(String message, byte[] expected, byte[] actual) {
        if (!java.util.Arrays.equals(expected, actual)) {
            throw new AssertionError(message + ": expectedLen="
                    + expected.length + " actualLen=" + actual.length
                    + " expectedCrc=" + crc(expected) + " actualCrc=" + crc(actual)
                    + " firstDiff=" + firstDiff(expected, actual)
                    + " expectedPrefix=" + hexPrefix(expected)
                    + " actualPrefix=" + hexPrefix(actual));
        }
    }

    private static byte[] readPackedPayload(RarArchiveReader.RarEntry entry,
                                            File archive) throws Exception {
        List<RarArchiveReader.RarEntry> all =
                RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, PASSWORD);
        Method method = Rar5CompressedArchiveExtractor.class.getDeclaredMethod(
                "readPackedPayload",
                RarArchiveReader.RarEntry.class,
                List.class,
                char[].class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, entry, all, PASSWORD);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String crc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return Long.toHexString(crc.getValue());
    }

    private static int firstDiff(byte[] a, byte[] b) {
        int limit = Math.min(a.length, b.length);
        for (int i = 0; i < limit; i++) {
            if (a[i] != b[i]) return i;
        }
        return a.length == b.length ? -1 : limit;
    }

    private static String hexPrefix(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(data.length, 32);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(java.util.Locale.ROOT, "%02x", data[i] & 0xFF));
        }
        return sb.toString();
    }

}
