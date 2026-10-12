package com.readwide.manager.archive;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** Pure bridge-row tests: no native backend is needed to check its identity policy. */
public class RarNativeListingIdentityTest {
    @Test public void duplicateFilesKeepFirstSelectedMetadata() throws Exception {
        List<ArchiveSupport.EntryInfo> entries = RarLibarchiveFallback.parseListingRows(
                "F\tpage.jpg\t3\t100\nF\tpage.jpg\t5\t200\n");
        assertEquals(1, entries.size());
        assertEquals(3, entries.get(0).size);
        assertEquals(100, entries.get(0).timeMillis);
    }
    @Test public void normalizedDuplicatesKeepFirstSelectedMetadata() throws Exception {
        List<ArchiveSupport.EntryInfo> entries = RarLibarchiveFallback.parseListingRows(
                "F\tfolder/page.jpg\t3\t100\nF\t./folder/page.jpg\t5\t200\n");
        assertEquals(2, entries.size());
        assertEquals("folder/page.jpg", entries.get(1).path);
        assertEquals(3, entries.get(1).size);
    }
    @Test public void explicitDirectoryCanReplaceSyntheticMetadata() throws Exception {
        List<ArchiveSupport.EntryInfo> entries = RarLibarchiveFallback.parseListingRows(
                "F\tfolder/page.jpg\t3\t100\nD\tfolder\t-1\t300\nF\tfolder/page.jpg\t5\t200\n");
        assertEquals(2, entries.size());
        assertTrue(entries.get(0).directory);
        assertEquals(300, entries.get(0).timeMillis);
        assertEquals(3, entries.get(1).size);
    }
    @Test public void escapedRowsUseSameFirstEntryPolicy() throws Exception {
        List<ArchiveSupport.EntryInfo> entries = RarLibarchiveFallback.parseListingRows(
                RarLibarchiveFallback.LISTING_HEADER + "F\tpage.jpg\t3\t100\nF\tpage.jpg\t5\t200\n");
        assertEquals(1, entries.size());
        assertEquals(3, entries.get(0).size);
    }
}
