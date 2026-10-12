package com.readwide.manager.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileThumbnailMathTest {
    @Test public void coverEligibilityDoesNotRejectLargeOrUnknownEntries() {
        for (long size : new long[] {-1, 1, 129L * 1024 * 1024, Long.MAX_VALUE}) {
            assertTrue(FileThumbnailMath.hasPossibleImageData(size));
        }
        assertFalse(FileThumbnailMath.hasPossibleImageData(0));
        assertFalse(FileThumbnailMath.hasPossibleImageData(-2));
    }

    @Test public void splitCoverSuffixesAreExactAndCaseInsensitive() {
        for (String name : new String[] {"a.ZIP.001", "a.ZipX.001", "a.7Z.001", "a.CB7.001"}) {
            assertTrue(FileThumbnailMath.isThumbnailArchiveName(name));
            assertTrue(FileThumbnailMath.isThumbnailCandidateName(name));
        }
        for (String name : new String[] {"a.zip.002", "a.zip.001.tmp", "a.zip.0001",
                "a.rar.001", "a.cbz.001", "a.zipx001", "a.001", "", null}) {
            assertFalse(FileThumbnailMath.isThumbnailArchiveName(name));
            assertFalse(FileThumbnailMath.isThumbnailCandidateName(name));
        }
    }

    @Test public void choosesAlphabeticallyFirstImage() {
        assertEquals(2, FileThumbnailMath.firstImageIndex(
                new String[] {"notes.txt", "002.PNG", "001.jpg"}));
    }

    @Test public void ignoresNonImages() {
        assertEquals(-1, FileThumbnailMath.firstImageIndex(
                new String[] {"book.cbz", "book.epub", "readme.txt"}));
    }

    @Test public void supportedNamesAreCaseInsensitive() {
        assertTrue(FileThumbnailMath.isThumbnailImageName("Cover.JPEG"));
        assertTrue(FileThumbnailMath.isThumbnailImageName("page.WEBP"));
        assertTrue(FileThumbnailMath.isThumbnailImageName("scan.JFIF"));
        assertTrue(FileThumbnailMath.isThumbnailImageName("photo.HEIC"));
        assertTrue(FileThumbnailMath.isThumbnailImageName("page.AVIF"));
        assertFalse(FileThumbnailMath.isThumbnailImageName("cover.svg"));
    }

    @Test public void firstCoverUsesNaturalNumberOrder() {
        assertEquals(1, FileThumbnailMath.firstImageIndex(
                new String[] {"page10.jpg", "page2.jpg"}));
    }

    @Test public void browserThumbnailArchiveTypesAreNarrowAndCaseInsensitive() {
        assertTrue(FileThumbnailMath.isThumbnailArchiveName("comic.CBZ"));
        assertTrue(FileThumbnailMath.isThumbnailArchiveName("book.cbr"));
        assertTrue(FileThumbnailMath.isThumbnailArchiveName("images.zip"));
        assertTrue(FileThumbnailMath.isThumbnailArchiveName("images.zipx"));
        assertTrue(FileThumbnailMath.isThumbnailArchiveName("images.rar"));
        assertTrue(FileThumbnailMath.isThumbnailArchiveName("images.cab"));
        assertTrue(FileThumbnailMath.isThumbnailArchiveName("images.lha"));
        assertTrue(FileThumbnailMath.isThumbnailArchiveName("images.lzh"));
        assertTrue(FileThumbnailMath.isThumbnailArchiveName("images.7z"));
        assertTrue(FileThumbnailMath.isThumbnailArchiveName("images.CB7"));
        assertTrue(FileThumbnailMath.isThumbnailArchiveName("images.cbt"));
        assertTrue(FileThumbnailMath.isThumbnailArchiveName("images.tar.zst"));
        assertTrue(FileThumbnailMath.isThumbnailArchiveName("images.7z.001"));
        assertFalse(FileThumbnailMath.isThumbnailArchiveName("images.7z.002"));
        assertFalse(FileThumbnailMath.isThumbnailArchiveName("book.epub"));
        assertFalse(FileThumbnailMath.isThumbnailArchiveName("image.jpg"));
    }

    @Test public void folderFallbackCanChooseFirstBookCoverSource() {
        assertEquals(2, FileThumbnailMath.firstCandidateIndex(
                new String[] {"notes.txt", "volume10.cbz", "volume2.cbz"}));
        assertTrue(FileThumbnailMath.isThumbnailCandidateName("book.pdf"));
        assertTrue(FileThumbnailMath.isThumbnailCandidateName("book.EPUB"));
        assertFalse(FileThumbnailMath.isThumbnailCandidateName("book.docx"));
    }

    @Test public void fileNameHandlesArchiveEntrySeparators() {
        assertEquals("cover.jpg", FileThumbnailMath.fileName("__MACOSX\\cover.jpg"));
        assertEquals("001.png", FileThumbnailMath.fileName("pages/001.png"));
    }
}
