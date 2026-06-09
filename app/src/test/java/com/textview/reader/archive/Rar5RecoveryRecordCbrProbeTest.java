package com.textview.reader.archive;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;

public class Rar5RecoveryRecordCbrProbeTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void probeRar5RecoveryRecordCbrExtractionBoundary() throws Exception {
        File archive = fixture("testfile.rar5.rr.cbr");
        System.out.println("archive=" + archive.getAbsolutePath() + " size=" + archive.length());
        System.out.println("type=" + ArchiveSupport.getSupportedArchiveType(archive));
        System.out.println("supported=" + ArchiveSupport.isSupportedArchive(archive));
        System.out.println("libarchive=" + RarLibarchiveFallback.isAvailable()
                + " status=" + LibarchiveNativeBridge.backendStatus()
                + " rarProbe=" + LibarchiveNativeBridge.rarFormatProbeStatus());

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);
        System.out.println("entries=" + entries.size());
        ArchiveSupport.EntryInfo first = null;
        for (ArchiveSupport.EntryInfo entry : entries) {
            System.out.println((entry.directory ? "D" : "F") + " " + entry.path + " size=" + entry.size);
            if (first == null && !entry.directory) first = entry;
        }
        assertNotNull(first);
        printParsedRarEntries(archive);

        File out = tempFolder.newFile("rar5-rr-cbr.out");
        assertTrue(out.delete());
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                archive,
                first.path,
                out,
                null);
        System.out.println("singleResult success=" + result.success
                + " failure=" + result.failure
                + " detail=" + result.detail
                + " exists=" + out.exists()
                + " size=" + (out.exists() ? Files.size(out.toPath()) : -1L));

        File storedOut = tempFolder.newFile("rar5-rr-cbr-stored-png.out");
        assertTrue(storedOut.delete());
        ArchiveSupport.ExtractionResult storedResult = ArchiveSupport.extractSingleEntryDetailed(
                archive,
                "testfile.png",
                storedOut,
                null);
        System.out.println("storedPngResult success=" + storedResult.success
                + " failure=" + storedResult.failure
                + " detail=" + storedResult.detail
                + " exists=" + storedOut.exists()
                + " size=" + (storedOut.exists() ? Files.size(storedOut.toPath()) : -1L));
        assertTrue("Stored RAR5 CBR fallback image should extract without libarchive: "
                        + storedResult.failure + " " + storedResult.detail,
                storedResult.success);
        assertTrue(storedOut.isFile());
        assertTrue(Files.size(storedOut.toPath()) > 0L);
    }

    private static File fixture(String name) {
        String root = System.getProperty("textview.externalArchiveFixtureDir");
        if (root == null || root.trim().length() == 0) {
            root = System.getenv("TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR");
        }
        if (root == null || root.trim().length() == 0) root = "C:\\tmp\\rar_probe";
        File file = new File(root, name);
        assumeTrue("Missing fixture: " + file.getAbsolutePath(), file.isFile());
        return file;
    }

    @SuppressWarnings("unchecked")
    private static void printParsedRarEntries(File archive) throws Exception {
        Method readEntries = RarArchiveReader.class.getDeclaredMethod("readEntries", File.class, char[].class);
        readEntries.setAccessible(true);
        List<RarArchiveReader.RarEntry> parsed =
                (List<RarArchiveReader.RarEntry>) readEntries.invoke(null, archive, null);
        for (RarArchiveReader.RarEntry entry : parsed) {
            System.out.println("parsed path=" + entry.path
                    + " rarVersion=" + entry.rarVersion
                    + " method=" + entry.method
                    + " packed=" + entry.packedSize
                    + " unpacked=" + entry.unpackedSize
                    + " dataOffset=" + entry.dataOffset
                    + " solid=" + entry.solid
                    + " splitBefore=" + entry.splitBefore
                    + " splitAfter=" + entry.splitAfter
                    + " encrypted=" + entry.encrypted());
        }
    }
}
