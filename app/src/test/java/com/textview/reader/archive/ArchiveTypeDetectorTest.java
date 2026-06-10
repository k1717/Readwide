package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class ArchiveTypeDetectorTest {
    @Test
    public void fromFileNameRecognizesMainArchiveFamilies() {
        assertEquals(ArchiveSupport.Type.ZIP, ArchiveTypeDetector.fromFileName("book.cbz"));
        assertEquals(ArchiveSupport.Type.RAR, ArchiveTypeDetector.fromFileName("book.part1.rar"));
        assertEquals(ArchiveSupport.Type.RAR, ArchiveTypeDetector.fromFileName("book.r00"));
        assertEquals(ArchiveSupport.Type.SEVEN_Z, ArchiveTypeDetector.fromFileName("book.cb7.001"));
        assertEquals(ArchiveSupport.Type.TAR_XZ, ArchiveTypeDetector.fromFileName("backup.tar.xz"));
        assertEquals(ArchiveSupport.Type.EGG, ArchiveTypeDetector.fromFileName("comic.vol2.egg"));
    }

    @Test
    public void outputBaseNameStripsSplitAndCompoundExtensions() {
        assertEquals("book", ArchiveTypeDetector.outputBaseName(new File("book.part1.rar"), "fallback"));
        assertEquals("book", ArchiveTypeDetector.outputBaseName(new File("book.7z.001"), "fallback"));
        assertEquals("backup", ArchiveTypeDetector.outputBaseName(new File("backup.tar.gz"), "fallback"));
        assertEquals("plain", ArchiveTypeDetector.outputBaseName(new File("plain"), "fallback"));
    }

    @Test
    public void splitHelpersStayConservative() {
        assertTrue(ArchiveTypeDetector.isFirstRarSplitName("book.rar"));
        assertTrue(ArchiveTypeDetector.isFirstRarSplitName("book.part1.rar"));
        assertTrue(ArchiveTypeDetector.isFirstNumericSplitName("book.zip.001"));
    }
}
