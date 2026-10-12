package com.readwide.manager.archive;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeListingParserTest {
    @Test public void legacyAndEscapedRowsUseTheSameSelectedPathIdentity() throws Exception {
        for (String prefix : Arrays.asList("", RarLibarchiveFallback.LISTING_HEADER)) {
            List<ArchiveSupport.EntryInfo> entries = RarLibarchiveFallback.parseListingRows(prefix
                    + "F\t./dir//page\t7\t10\nF\tdir/page\t99\t20\n");
            assertEquals(2, entries.size());
            assertEquals("dir/", entries.get(0).path);
            assertEquals("dir/page", entries.get(1).path);
            assertEquals(7, entries.get(1).size);
        }
    }

    @Test public void invalidExtractionPathsAreNotPublishedAsDifferentFiles() throws Exception {
        for (String path : Arrays.asList("/absolute", "C:/drive", "dir/..", "../outside", "bad\0name", ".//absolute")) {
            assertTrue(path, RarLibarchiveFallback.parseListingRows("F\t" + path + "\t1\t0\n").isEmpty());
        }
    }

    @Test public void dotsInsideDirectoryNamesAreNotTraversal() throws Exception {
        List<ArchiveSupport.EntryInfo> entries = RarLibarchiveFallback.parseListingRows("F\tnotes../page\t1\t0");
        assertEquals("notes../page", entries.get(1).path);
    }

    @Test public void blankRowsAndFinalRowWithoutNewlineRemainSupported() throws Exception {
        List<ArchiveSupport.EntryInfo> entries = RarLibarchiveFallback.parseListingRows(
                RarLibarchiveFallback.LISTING_HEADER + "\nF\tfirst\t2\t3\n\nF\tlast\t4\t5");
        assertEquals(2, entries.size());
        assertEquals("last", entries.get(1).path);
        assertEquals(5, entries.get(1).timeMillis);
        assertTrue(RarLibarchiveFallback.parseListingRows(RarLibarchiveFallback.LISTING_HEADER).isEmpty());
    }

    @Test public void malformedColumnsStillFailAndNumericFallbackRemainsCompatible() throws Exception {
        for (String row : Arrays.asList("F\tfile\t1", "F\tfile\t1\t0\textra", "X\tfile\t1\t0")) {
            assertThrows(IOException.class, () -> RarLibarchiveFallback.parseListingRows(row));
        }
        List<ArchiveSupport.EntryInfo> entries = RarLibarchiveFallback.parseListingRows("F\tfile\tbad\tbad");
        assertEquals(0, entries.get(0).size);
        assertEquals(0, entries.get(0).timeMillis);
    }

    @Test public void cancelledParsingDoesNotProcessRemainingRows() throws Exception {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> RarLibarchiveFallback.parseListingRows("F\tfile\t1\t0\n"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
