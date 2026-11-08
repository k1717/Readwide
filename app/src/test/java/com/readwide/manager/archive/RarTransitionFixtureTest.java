package com.readwide.manager.archive;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Opt-in libarchive fixtures; provenance and extraction oracles are in the archive support notes. */
public class RarTransitionFixtureTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void ppmdLzTransitionMatchesCompleteMember() throws Exception {
        checkFixture("test_read_format_rar_ppmd_lzss_conversion",
                "ppmd_lzss_conversion_test.txt", 241647978L, 0xf72b4477L);
    }

    @Test public void executableFilterMatchesCompleteMember() throws Exception {
        checkFixture("test_read_format_rar_filter", "bsdcat.exe", 204288L, 0x4db10349L);
    }

    @Test public void pendingFilterCanFinishAfterPpmdTransition() throws Exception {
        // Upstream calls this incomplete because its reader rejects the transition.
        // The RAR 6.24 archive is valid; both reference extractors produce these bytes.
        checkFixture("test_read_format_rar_filter_incomplete_block", "f2500.bin", 10000L, 0x13078c9eL);
    }

    private void checkFixture(String stem, String name, long size, long crc) throws Exception {
        String path = System.getProperty("textview.externalArchiveFixtureDir");
        if (path == null || path.trim().isEmpty()) path = System.getenv("TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR");
        assumeTrue("External archive fixture directory not supplied", path != null && !path.trim().isEmpty());
        File archive = new File(new File(path, "rar-transitions"), stem + ".rar");
        assumeTrue("Missing external fixture: " + archive, archive.isFile());
        List<ArchiveSupport.EntryInfo> listed = ArchiveSupport.listEntries(archive, null);
        assertEquals(1, listed.size());
        assertEquals(name, listed.get(0).path);
        assertFalse(listed.get(0).directory);
        assertEquals(size, listed.get(0).size);

        File selected = new File(temporary.getRoot(), "selected");
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(archive, name, selected, null);
        assertTrue("Selected: " + result.failure + " " + result.detail, result.success);
        checkFile(selected, size, crc);
        File bulk = new File(temporary.getRoot(), "bulk");
        result = ArchiveSupport.extractArchiveDetailed(archive, bulk, true, null, null);
        assertTrue("Bulk: " + result.failure + " " + result.detail, result.success);
        checkFile(new File(bulk, name), size, crc);
        assertEquals("Selected/bulk bytes differ", -1L, Files.mismatch(selected.toPath(), new File(bulk, name).toPath()));

        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        for (boolean logical : new boolean[]{false, true}) {
            for (boolean discard : new boolean[]{false, true}) {
                File spool = temporary.newFolder("spool-" + logical + "-" + discard);
                try (ArchiveSupport.ForwardArchiveReader reader = logical
                        ? Rar3LogicalSolidArchiveExtractor.openForwardReader(entries, null, spool)
                        : ArchiveSupport.openForwardReader(archive, null, spool)) {
                    assertNotNull("Missing forward route", reader);
                    ArchiveSupport.ForwardEntry entry = reader.nextEntry();
                    assertNotNull(entry);
                    assertEquals(name, entry.path);
                    if (discard) assertTrue("Drain failed", reader.drainCurrentEntry(size));
                    else checkStream(reader::read, size, crc);
                    assertNull("Unexpected extra member", reader.nextEntry());
                }
                assertArrayEquals("Forward spool leaked", new String[0], spool.list());
            }
        }
    }

    private static void checkFile(File file, long size, long crc) throws Exception {
        assertEquals(size, file.length());
        try (InputStream input = Files.newInputStream(file.toPath())) {
            checkStream(input::read, size, crc);
        }
    }

    private interface Read { int read(byte[] buffer) throws IOException; }

    private static void checkStream(Read input, long size, long expectedCrc) throws Exception {
        byte[] buffer = new byte[8191];
        CRC32 crc = new CRC32();
        long count = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            assertTrue("Reader made no progress", read > 0);
            assertTrue("Output exceeds fixture size", read <= size - count);
            crc.update(buffer, 0, read);
            count += read;
        }
        assertEquals(size, count);
        assertEquals(expectedCrc, crc.getValue());
    }
}
