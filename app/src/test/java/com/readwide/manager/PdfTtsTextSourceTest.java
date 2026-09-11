package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Unit coverage for the PDF read-aloud text buffer's page-offset math. Uses the
 * map-based {@code build} overload so no real PDF or Android context is needed;
 * the current-page methods (which touch the activity) are exercised separately
 * on device. This mirrors the Python model that validated the same math across
 * 3000 randomized page layouts including missing and image-only pages.
 */
public class PdfTtsTextSourceTest {

    @Test
    public void inferredWhitespaceSurvivesResidentBufferAndPageBoundaries() {
        PdfTtsTextSource src = PdfTtsTextSource.build(null, pages("cat dog\n", "bird\n"), 2);
        assertEquals("cat dog\n\nbird\n\n", src.getTextContent());
        assertEquals(0, src.pageIndexForChar(8));
        assertEquals(1, src.pageIndexForChar(9));
    }

    @Test
    public void legacyAndUnknownResumeFormatsUseSavedPageStart() {
        PdfTtsTextSource src = PdfTtsTextSource.build(null, pages("cat dog", "bird song"), 2);
        assertEquals(8, src.resolveSavedPosition(2, 2, 0));
        assertEquals(8, src.resolveSavedPosition(2, 2, 99));
        assertEquals(0, src.resolveSavedPosition(12, 1, 0));
    }

    @Test
    public void currentResumeFormatPreservesValidExactOffset() {
        PdfTtsTextSource src = PdfTtsTextSource.build(null, pages("cat dog", "bird song"), 2);
        assertEquals(10, src.resolveSavedPosition(10, 1, PdfTtsTextSource.TEXT_FORMAT_VERSION));
        assertEquals(0, src.resolveSavedPosition(0, 2, PdfTtsTextSource.TEXT_FORMAT_VERSION));
    }

    @Test
    public void invalidResumeCoordinatesFallBackToClampedPage() {
        PdfTtsTextSource src = PdfTtsTextSource.build(null, pages("cat dog", "bird song"), 2);
        assertEquals(8, src.resolveSavedPosition(Integer.MAX_VALUE, 2, PdfTtsTextSource.TEXT_FORMAT_VERSION));
        assertEquals(0, src.resolveSavedPosition(-1, Integer.MIN_VALUE, 0));
        assertEquals(8, src.resolveSavedPosition(-1, Integer.MAX_VALUE, 0));
        PdfTtsTextSource empty = PdfTtsTextSource.build(null, pages(), 0);
        assertEquals(0, empty.resolveSavedPosition(999, 999, 0));
    }

    private static Map<Integer, String> pages(String... text) {
        Map<Integer, String> m = new HashMap<>();
        for (int i = 0; i < text.length; i++) {
            if (text[i] != null) m.put(i, text[i]);
        }
        return m;
    }

    @Test
    public void concatenatesPagesWithSeparatorAndStrictlyIncreasingOffsets() {
        PdfTtsTextSource src = PdfTtsTextSource.build(null, pages("alpha", "beta", "gamma"), 3);
        // "alpha\nbeta\ngamma\n"
        assertEquals("alpha\nbeta\ngamma\n", src.getTextContent());
        assertTrue(src.hasAnyText());
        // Page starts: 0, 6, 11; each page owns its own range.
        assertEquals(0, src.pageIndexForChar(0));
        assertEquals(0, src.pageIndexForChar(5));   // still page 0 (the '\n' after alpha)
        assertEquals(1, src.pageIndexForChar(6));   // 'b' of beta
        assertEquals(1, src.pageIndexForChar(10));  // '\n' after beta
        assertEquals(2, src.pageIndexForChar(11));  // 'g' of gamma
    }

    @Test
    public void clampsOutOfRangePositions() {
        PdfTtsTextSource src = PdfTtsTextSource.build(null, pages("one", "two"), 2);
        assertEquals(0, src.pageIndexForChar(-100));
        assertEquals(1, src.pageIndexForChar(src.getTextContent().length()));
        assertEquals(1, src.pageIndexForChar(src.getTextContent().length() + 50));
    }

    @Test
    public void imageOnlyAndMissingPagesStillGetOffsets() {
        // Page 0 has text, page 1 is empty (image-only), page 2 is absent from
        // the map (extraction skipped it). All three must still map cleanly.
        Map<Integer, String> m = new HashMap<>();
        m.put(0, "cover text");
        m.put(1, "");
        // page 2 intentionally missing
        PdfTtsTextSource src = PdfTtsTextSource.build(null, m, 3);
        assertTrue(src.hasAnyText());
        assertEquals(0, src.pageIndexForChar(0));
        // Each page contributes at least the '\n' separator, so offsets are
        // strictly increasing and every page index resolves.
        assertEquals(1, src.pageIndexForChar(11)); // just after "cover text\n"
        assertEquals(2, src.pageIndexForChar(12));
    }

    @Test
    public void scannedPdfWithNoTextReportsNoText() {
        Map<Integer, String> m = new HashMap<>();
        m.put(0, "");
        m.put(1, "   ");
        PdfTtsTextSource src = PdfTtsTextSource.build(null, m, 2);
        assertFalse("blank/whitespace pages must not count as text", src.hasAnyText());
    }

    @Test
    public void growsToCoverPagesBeyondReportedCount() {
        // Extraction reported a page index past the renderer's count; the buffer
        // must grow so offsets stay valid rather than throwing.
        Map<Integer, String> m = new HashMap<>();
        m.put(0, "a");
        m.put(3, "d");
        PdfTtsTextSource src = PdfTtsTextSource.build(null, m, 1);
        assertTrue(src.hasAnyText());
        assertEquals(3, src.pageIndexForChar(src.getTextContent().length() - 1));
    }
}
