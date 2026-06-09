package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ExternalArchiveFixtureSmokeTest {
    private static final char[] PASSWORD = "password".toCharArray();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void passwordZipFixture_listsAndExtractsWithPassword() throws Exception {
        File root = fixtureRoot();
        assertPasswordArchiveWorks(new File(root, "zip_sample_password.zip"));
    }

    @Test
    public void password7zFixture_listsAndExtractsWithPassword() throws Exception {
        File root = fixtureRoot();
        assertPasswordArchiveWorks(new File(root, "7z_sample_password.7z"));
    }

    @Test
    public void passwordZipxFixture_listsAndReportsUnsupportedExtraction() throws Exception {
        File root = fixtureRoot();
        File archive = new File(root, "zipx_sample_password.zipx");
        assumeTrue("Missing fixture: " + archive.getAbsolutePath(), archive.isFile());
        assertTrue("Not supported: " + archive.getName(), ArchiveSupport.isSupportedArchive(archive));
        assertTrue("Password was not detected for " + archive.getName(),
                ArchiveSupport.requiresPasswordForExtraction(archive));

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, PASSWORD);
        ArchiveSupport.EntryInfo entry = firstFileEntry(entries);
        File out = tempFolder.newFile("zipx-entry.out");
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                archive,
                entry.path,
                out,
                PASSWORD);

        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE, result.failure);
        assertFalse(out.exists());
    }

    @Test
    public void rarTestFilesFixtures_matchCurrentSupportBoundary() throws Exception {
        File root = fixtureRoot();
        File bundle = rarFixtureBundle(root);

        File rarRoot = extractRarFixtures(bundle);
        File build = new File(rarRoot, "rar-test-files-master/build");
        File rar5 = new File(build, "testfile.rar5.rar");
        File rar5Cbr = new File(build, "testfile.rar5.cbr");
        File rar3 = new File(build, "testfile.rar3.rar");
        File rar5Solid = new File(build, "testfile.rar5.solid.rar");

        assertTrue(ArchiveSupport.isSupportedArchive(rar5));
        assertTrue(ArchiveSupport.isSupportedArchive(rar5Cbr));
        assertTrue(ArchiveSupport.isSupportedArchive(rar3));
        assertTrue(ArchiveSupport.isSupportedArchive(rar5Solid));

        assertFalse(ArchiveSupport.listEntries(rar5, null).isEmpty());
        assertFalse(ArchiveSupport.listEntries(rar5Cbr, null).isEmpty());
        assertFalse(ArchiveSupport.listEntries(rar3, null).isEmpty());
        assertFalse(ArchiveSupport.listEntries(rar5Solid, null).isEmpty());

        File storedPng = tempFolder.newFile("rar-cbr-stored.png");
        assertTrue(ArchiveSupport.extractSingleEntry(rar5Cbr, "testfile.png", storedPng, null));
        assertTrue(storedPng.isFile());
        assertTrue(Files.size(storedPng.toPath()) > 0L);

        File compressedJpg = tempFolder.newFile("rar5-cbr-compressed.jpg");
        ArchiveSupport.ExtractionResult rar5Compressed = ArchiveSupport.extractSingleEntryDetailed(
                rar5Cbr,
                "testfile.jpg",
                compressedJpg,
                null);
        if (RarLibarchiveFallback.isAvailable()) {
            assertTrue("RAR5 compressed extraction failed with libarchive backend: "
                            + rar5Compressed.failure + " " + rar5Compressed.detail,
                    rar5Compressed.success);
            assertTrue(compressedJpg.isFile());
            assertTrue(Files.size(compressedJpg.toPath()) > 0L);
        } else {
            assertFalse(rar5Compressed.success);
            assertEquals(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE, rar5Compressed.failure);
            assertFalse(compressedJpg.exists());
        }

        File rar5SolidText = tempFolder.newFile("rar5-solid.txt");
        ArchiveSupport.ExtractionResult rar5SolidResult = ArchiveSupport.extractSingleEntryDetailed(
                rar5Solid,
                "testfile.txt",
                rar5SolidText,
                null);
        if (RarLibarchiveFallback.isAvailable()) {
            assertTrue("RAR5 solid extraction failed with libarchive backend: "
                            + rar5SolidResult.failure + " " + rar5SolidResult.detail,
                    rar5SolidResult.success);
            assertTrue(rar5SolidText.isFile());
            assertTrue(Files.size(rar5SolidText.toPath()) > 0L);
        } else {
            assertEquals(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE, rar5SolidResult.failure);
            assertFalse(rar5SolidText.exists());
        }

        File rar3Text = tempFolder.newFile("rar3-compressed.txt");
        ArchiveSupport.ExtractionResult rar3Compressed = ArchiveSupport.extractSingleEntryDetailed(
                rar3,
                "testfile.txt",
                rar3Text,
                null);
        if (RarLibarchiveFallback.isAvailable()) {
            assertTrue("RAR3 compressed extraction failed with libarchive backend: "
                            + rar3Compressed.failure + " " + rar3Compressed.detail,
                    rar3Compressed.success);
            assertTrue(rar3Text.isFile());
            assertTrue(Files.size(rar3Text.toPath()) > 0L);
        } else {
            assertFalse(rar3Compressed.success);
            assertEquals(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE, rar3Compressed.failure);
            assertFalse(rar3Text.exists());
        }

        File rar3Solid = new File(build, "testfile.rar3.solid.rar");
        File rar3SolidText = tempFolder.newFile("rar3-solid.txt");
        ArchiveSupport.ExtractionResult rar3SolidResult = ArchiveSupport.extractSingleEntryDetailed(
                rar3Solid,
                "testfile.txt",
                rar3SolidText,
                null);
        if (RarLibarchiveFallback.isAvailable()) {
            assertTrue("RAR3 solid extraction failed with libarchive backend: "
                            + rar3SolidResult.failure + " " + rar3SolidResult.detail,
                    rar3SolidResult.success);
            assertTrue(rar3SolidText.isFile());
            assertTrue(Files.size(rar3SolidText.toPath()) > 0L);
        } else {
            assertFalse(rar3SolidResult.success);
            assertEquals(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE, rar3SolidResult.failure);
            assertFalse(rar3SolidText.exists());
        }

        File rar3SolidCbr = new File(build, "testfile.rar3.solid.cbr");
        File rar3SolidPng = tempFolder.newFile("rar3-solid-cbr.png");
        ArchiveSupport.ExtractionResult rar3SolidCbrResult = ArchiveSupport.extractSingleEntryDetailed(
                rar3SolidCbr,
                "testfile.png",
                rar3SolidPng,
                null);
        if (rar3SolidCbrResult.success) {
            assertTrue(rar3SolidPng.isFile());
            assertTrue(Files.size(rar3SolidPng.toPath()) > 0L);
        } else {
            assertEquals(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE, rar3SolidCbrResult.failure);
            assertFalse(rar3SolidPng.exists());
        }
    }

    @Test
    public void rarTestFilesRar3Fixtures_listKnownEntriesWithFirstPartyParser() throws Exception {
        File root = fixtureRoot();
        File bundle = rarFixtureBundle(root);
        File rarRoot = extractRarFixtures(bundle);
        File build = new File(rarRoot, "rar-test-files-master/build");

        assertRarEntryCount(new File(build, "testfile.rar3.rar"), 1, "testfile.txt");
        assertRarEntryCount(new File(build, "testfile.rar3.av.rar"), 1, "testfile.txt");
        assertRarEntryCount(new File(build, "testfile.rar3.locked.rar"), 1, "testfile.txt");
        assertRarEntryCount(new File(build, "testfile.rar3.rr.rar"), 1, "testfile.txt");
        assertRarEntryCount(new File(build, "testfile.rar3.solid.rar"), 1, "testfile.txt");

        assertRarEntryCount(new File(build, "testfile.rar3.cbr"), 2, "testfile.jpg");
        assertRarEntryCount(new File(build, "testfile.rar3.av.cbr"), 2, "testfile.jpg");
        assertRarEntryCount(new File(build, "testfile.rar3.locked.cbr"), 2, "testfile.jpg");
        assertRarEntryCount(new File(build, "testfile.rar3.rr.cbr"), 2, "testfile.jpg");
        assertRarEntryCount(new File(build, "testfile.rar3.solid.cbr"), 2, "testfile.png");

        // SFX wrappers are dispatched only when the file body contains an embedded RAR
        // signature. Plain .exe files are still not treated as archives by filename alone.
        assertRarSfxEntryCount(new File(build, "testfile.rar3.dos_sfx.exe"), 2, "testfile.txt");
        assertRarSfxEntryCount(new File(build, "testfile.rar3.wincon.sfx.exe"), 2, "testfile.txt");
        assertRarSfxEntryCount(new File(build, "testfile.rar3.wingui.sfx.exe"), 2, "testfile.txt");
    }

    @Test
    public void rarTestFilesRar3SfxFixtures_reportEmbeddedSignatureOffsets() throws Exception {
        File root = fixtureRoot();
        File bundle = rarFixtureBundle(root);
        File rarRoot = extractRarFixtures(bundle);
        File build = new File(rarRoot, "rar-test-files-master/build");

        assertEquals(0L, RarArchiveReader.findEmbeddedRarSignatureOffsetForBackend(
                new File(build, "testfile.rar3.rar")));
        assertTrue(RarArchiveReader.findEmbeddedRarSignatureOffsetForBackend(
                new File(build, "testfile.rar3.dos_sfx.exe")) > 0L);
        assertTrue(RarArchiveReader.findEmbeddedRarSignatureOffsetForBackend(
                new File(build, "testfile.rar3.wincon.sfx.exe")) > 0L);
        assertTrue(RarArchiveReader.findEmbeddedRarSignatureOffsetForBackend(
                new File(build, "testfile.rar3.wingui.sfx.exe")) > 0L);
    }


    @Test
    public void rarFixtureReport_generatesMarkdownForExternalRarFixtures() throws Exception {
        File root = fixtureRoot();
        File bundle = rarFixtureBundle(root);
        File rarRoot = extractRarFixtures(bundle);
        File build = new File(rarRoot, "rar-test-files-master/build");

        RarFixtureReport report = RarFixtureReport.generate(build, null, 1);
        String markdown = report.toMarkdown();

        assertTrue("Expected at least one readable RAR fixture report row", report.readableCount() > 0);
        assertTrue(markdown.contains("RAR fixture support boundary report"));
        assertTrue(markdown.contains("Normal compressed RAR extraction remains libarchive-first"));
        assertTrue(markdown.contains("Backend route"));
        assertTrue(markdown.contains("backend route counts"));
        assertTrue(markdown.contains("testfile.rar3.rar"));
    }

    @Test
    public void rarSolidFixtureReport_separatesLibarchiveAndFirstPartyBoundaries() throws Exception {
        File root = fixtureRoot();
        File bundle = rarFixtureBundle(root);
        File rarRoot = extractRarFixtures(bundle);
        File build = new File(rarRoot, "rar-test-files-master/build");
        File probeDir = tempFolder.newFolder("rar-solid-probes");

        RarSolidFixtureReport report = RarSolidFixtureReport.generate(build, null, 1, probeDir);
        String markdown = report.toMarkdown();

        assertTrue("Expected at least one solid RAR fixture row", report.solidArchiveCount() > 0);
        assertTrue("Expected at least one compressed-solid boundary row", report.compressedSolidArchiveCount() > 0);
        assertTrue(markdown.contains("RAR solid fixture boundary report"));
        assertTrue(markdown.contains("solid RAR extraction remains libarchive-first"));
        assertTrue(markdown.contains("Backend route"));
        assertTrue(markdown.contains("testfile.rar3.solid.rar"));
    }

    @Test
    public void rarSolidFirstPartyProbe_generatesDiagnosticMarkdown() throws Exception {
        File root = fixtureRoot();
        File bundle = rarFixtureBundle(root);
        File rarRoot = extractRarFixtures(bundle);
        File build = new File(rarRoot, "rar-test-files-master/build");
        File probeDir = tempFolder.newFolder("rar-solid-first-party-probes");

        java.util.List<RarSolidFirstPartyProbe.Result> results =
                RarSolidFirstPartyProbe.probeReadableArchives(build, null, probeDir, true);
        String markdown = RarSolidFirstPartyProbe.resultsToMarkdown(results);

        assertTrue(markdown.contains("RAR compressed-solid first-party probe results"));
        assertTrue(markdown.contains("libarchive remains primary"));
    }

    @Test
    public void userSampleRarFixtures_listAndExtractFirstFile() throws Exception {
        File root = fixtureRoot();
        boolean foundAny = false;
        for (int i = 1; i <= 5; i++) {
            File archive = new File(root, "sample-" + i + ".rar");
            if (!archive.isFile()) continue;
            foundAny = true;
            assertSampleRarExtractsFirstFile(archive);
        }
        assumeTrue("Missing sample-1.rar through sample-5.rar fixtures in " + root.getAbsolutePath(), foundAny);
    }

    private void assertPasswordArchiveWorks(File archive) throws Exception {
        assumeTrue("Missing fixture: " + archive.getAbsolutePath(), archive.isFile());
        assertTrue("Not supported: " + archive.getName(), ArchiveSupport.isSupportedArchive(archive));
        assertTrue("Password was not detected for " + archive.getName(),
                ArchiveSupport.requiresPasswordForExtraction(archive));

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, PASSWORD);
        ArchiveSupport.EntryInfo entry = firstFileEntry(entries);
        File out = tempFolder.newFile(archive.getName().replaceAll("[^A-Za-z0-9_.-]", "_") + ".out");

        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                archive,
                entry.path,
                out,
                PASSWORD);

        assertTrue("Extraction failed for " + archive.getName() + ": " + result.failure + " " + result.detail,
                result.success);
        assertTrue(out.isFile());
        assertTrue(Files.size(out.toPath()) > 0L);
    }

    private ArchiveSupport.EntryInfo firstFileEntry(List<ArchiveSupport.EntryInfo> entries) {
        assertNotNull(entries);
        for (ArchiveSupport.EntryInfo entry : entries) {
            if (entry != null && !entry.directory) return entry;
        }
        throw new AssertionError("No file entry found");
    }

    private void assertSampleRarExtractsFirstFile(File archive) throws Exception {
        assertTrue("Not supported: " + archive.getName(), ArchiveSupport.isSupportedArchive(archive));
        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);
        ArchiveSupport.EntryInfo first = firstFileEntry(entries);
        File out = tempFolder.newFile(archive.getName().replaceAll("[^A-Za-z0-9_.-]", "_") + ".sample.out");
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                archive,
                first.path,
                out,
                null);
        if (result.success) {
            assertTrue(out.isFile());
            assertTrue(Files.size(out.toPath()) > 0L);
            return;
        }
        if (!RarLibarchiveFallback.isAvailable()) {
            assertEquals("Unexpected failure without libarchive for " + archive.getName(),
                    ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE,
                    result.failure);
            assertFalse(out.exists());
            return;
        }
        assertTrue("Extraction failed for " + archive.getName() + " entry " + first.path
                        + ": " + result.failure + " " + result.detail,
                result.success);
    }

    private File fixtureRoot() {
        String path = System.getProperty("textview.externalArchiveFixtureDir");
        if (path == null || path.trim().length() == 0) {
            path = System.getenv("TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR");
        }
        assumeTrue("External archive fixture dir not provided", path != null && path.trim().length() > 0);
        File root = new File(path);
        assumeTrue("External archive fixture dir missing: " + root.getAbsolutePath(), root.isDirectory());
        return root;
    }

    private File rarFixtureBundle(File root) {
        File exact = new File(root, "rar-test-files-master.zip");
        if (exact.isFile()) return exact;
        File uploadedName = new File(root, "rar-test-files-master(1).zip");
        if (uploadedName.isFile()) return uploadedName;
        File[] matches = root.listFiles((dir, name) -> name.startsWith("rar-test-files-master") && name.endsWith(".zip"));
        assumeTrue("Missing rar-test-files-master fixture bundle in " + root.getAbsolutePath(),
                matches != null && matches.length > 0 && matches[0].isFile());
        return matches[0];
    }

    private void assertRarEntryCount(File archive, int expectedCount, String firstPath) throws Exception {
        assumeTrue("Missing fixture: " + archive.getAbsolutePath(), archive.isFile());
        List<ArchiveSupport.EntryInfo> entries = RarArchiveReader.listEntries(archive, null);
        assertEquals(archive.getName(), expectedCount, entries.size());
        assertEquals(archive.getName(), firstPath, firstFileEntry(entries).path);
    }

    private void assertRarSfxEntryCount(File archive, int expectedCount, String firstPath) throws Exception {
        assumeTrue("Missing fixture: " + archive.getAbsolutePath(), archive.isFile());
        assertEquals(archive.getName(), ArchiveSupport.Type.RAR, ArchiveSupport.getSupportedArchiveType(archive));
        assertTrue(archive.getName(), ArchiveSupport.isSupportedArchive(archive));
        assertRarEntryCount(archive, expectedCount, firstPath);
    }

    private File extractRarFixtures(File bundle) throws Exception {
        File target = tempFolder.newFolder("rar-fixtures");
        boolean found = false;
        try (ZipFile zip = new ZipFile(bundle)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("rar-test-files-master/build/")) continue;
                found = true;
                File out = new File(target, name);
                File parent = out.getParentFile();
                assertNotNull(parent);
                assertTrue(parent.exists() || parent.mkdirs());
                try (InputStream in = zip.getInputStream(entry);
                     FileOutputStream fileOut = new FileOutputStream(out)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) fileOut.write(buffer, 0, read);
                }
            }
        }
        assertTrue("No rar-test-files build fixtures found in " + bundle.getName(), found);
        return target;
    }
}
