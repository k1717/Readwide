package com.readwide.manager;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class UriOpenRequestTest {
    @Test public void mimeOnlyDocumentsReceiveTheirSupportedSuffix() {
        assertEquals("book.epub", UriOpenRequest.documentFileName("book", "application/epub+zip"));
        assertEquals("notes.md", UriOpenRequest.documentFileName("notes", "text/x-markdown"));
        assertEquals("report.doc", UriOpenRequest.documentFileName("report", "application/msword"));
        assertEquals("report.docx", UriOpenRequest.documentFileName("report",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertEquals("report.hwp", UriOpenRequest.documentFileName("report", "application/x-hwp"));
        assertEquals("report.hwpx", UriOpenRequest.documentFileName("report", "application/vnd.hancom.hwpx"));
    }

    @Test public void recognizedFilenameWinsOverConflictingMime() {
        assertEquals("book.EPUB", UriOpenRequest.documentFileName("book.EPUB", "application/msword"));
        assertEquals("report.docx", UriOpenRequest.documentFileName("report.docx", "application/epub+zip"));
        assertEquals("notes.md", UriOpenRequest.documentFileName("notes.md", null));
    }

    @Test public void unknownTypesStayUnchangedAndMissingNamesHaveSafeFallback() {
        assertEquals("report.bin", UriOpenRequest.documentFileName("report.bin", "application/octet-stream"));
        assertEquals("document.epub", UriOpenRequest.documentFileName(null, "application/epub+zip"));
        assertEquals("report.bin.doc", UriOpenRequest.documentFileName("report.bin", "APPLICATION/MSWORD"));
    }
}
