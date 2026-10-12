package com.readwide.manager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DocumentLoadOwnershipInstrumentedTest {
    @Test public void recreatedViewportOverridesOldBookmarkWithoutChangingLaunchIntent() {
        android.content.Intent launch = new android.content.Intent()
                .putExtra(DocumentPageActivity.EXTRA_FILE_PATH, "/book.epub")
                .putExtra(DocumentPageActivity.EXTRA_JUMP_TO_PAGE, 2)
                .putExtra(DocumentPageActivity.EXTRA_CONTENT_ANCHOR_JSON, "old");
        android.os.Bundle viewport = new android.os.Bundle();
        viewport.putString("path", "/book.epub");
        viewport.putInt("page", 9);
        viewport.putString("anchor", "current");
        android.content.Intent restored = DocumentPageActivity.restoreDocumentViewportIntent(launch, viewport);
        assertEquals(9, restored.getIntExtra(DocumentPageActivity.EXTRA_JUMP_TO_PAGE, -1));
        assertEquals("current", restored.getStringExtra(DocumentPageActivity.EXTRA_CONTENT_ANCHOR_JSON));
        assertEquals(2, launch.getIntExtra(DocumentPageActivity.EXTRA_JUMP_TO_PAGE, -1));
        viewport.putString("path", "/other.epub");
        assertSame(launch, DocumentPageActivity.restoreDocumentViewportIntent(launch, viewport));
    }

    @Test public void markdownScrollFallbackRemovesStaleSourceAnchorIncludingAtTop() {
        android.content.Intent launch = new android.content.Intent()
                .putExtra(DocumentPageActivity.EXTRA_FILE_URI, "content://books/current")
                .putExtra(DocumentPageActivity.EXTRA_MARKDOWN_SOURCE_OFFSET, 4000)
                .putExtra(DocumentPageActivity.EXTRA_CONTENT_ANCHOR_JSON, "old");
        android.os.Bundle viewport = new android.os.Bundle();
        viewport.putString("uri", "content://books/current");
        viewport.putInt("scroll", 0);
        android.content.Intent restored = DocumentPageActivity.restoreDocumentViewportIntent(launch, viewport);
        assertFalse(restored.hasExtra(DocumentPageActivity.EXTRA_MARKDOWN_SOURCE_OFFSET));
        assertEquals("", restored.getStringExtra(DocumentPageActivity.EXTRA_CONTENT_ANCHOR_JSON));
        assertEquals(0, restored.getIntExtra(DocumentPageActivity.EXTRA_MARKDOWN_SCROLL_Y, -1));
        viewport.remove("scroll");
        viewport.putInt("page", 7);
        restored = DocumentPageActivity.restoreDocumentViewportIntent(launch, viewport);
        assertFalse(restored.hasExtra(DocumentPageActivity.EXTRA_MARKDOWN_SCROLL_Y));
        assertEquals(7, restored.getIntExtra(DocumentPageActivity.EXTRA_JUMP_TO_PAGE, -1));
    }

    @Test public void discardedParseClosesOnlyItsOwnZip() throws Exception {
        File file = File.createTempFile("document-load-", ".zip",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        try {
            try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
                output.putNextEntry(new ZipEntry("chapter.xhtml"));
                output.write("<html><body>Chapter</body></html>".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
            try (DocumentPageActivity.ParsedDocument first = new DocumentPageActivity.ParsedDocument("first.local");
                 DocumentPageActivity.ParsedDocument second = new DocumentPageActivity.ParsedDocument("second.local")) {
                first.resourceZip = new ZipFile(file);
                second.resourceZip = new ZipFile(file);
                first.pages.add(new DocumentPageActivity.Page("first", "first", null));
                second.pages.add(new DocumentPageActivity.Page("second", "second", null));
                ZipFile discarded = first.resourceZip;
                first.close();
                first.close();
                assertNull(first.resourceZip);
                assertNotNull(second.resourceZip.getEntry("chapter.xhtml"));
                assertEquals("second", second.pages.get(0).html);
                try { discarded.getEntry("chapter.xhtml"); fail("Discarded ZIP must be closed"); }
                catch (IllegalStateException expected) { }
            }
        } finally { assertTrue(file.delete()); }
    }

    @Test public void unfinishedLoadAndPendingAnchorsCannotBeSaved() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            DocumentPageActivity activity = new DocumentPageActivity();
            try {
                activity.pages.add(new DocumentPageActivity.Page("page", "text", null));
                activity.docType = "EPUB";
                activity.primaryDocumentPageLoadGeneration = 3;
                activity.primaryDocumentPageReadyGeneration = 3;
                assertFalse(activity.canSaveDocumentReadingState());
                activity.documentLoadAccepted = true;
                assertTrue(activity.canSaveDocumentReadingState());
                activity.primaryDocumentPageReadyGeneration = 2;
                assertFalse(activity.canSaveDocumentReadingState());
                activity.primaryDocumentPageReadyGeneration = 3;
                activity.pendingDocumentRestoreAnchorJson = "{\"pageIndex\":0}";
                assertFalse(activity.canSaveDocumentReadingState());
                activity.pendingDocumentRestoreAnchorJson = "";
                activity.docType = "Markdown";
                activity.pendingMarkdownRestoreSourceOffset = 123;
                assertFalse(activity.canSaveDocumentReadingState());
                activity.pendingMarkdownRestoreSourceOffset = -1;
                assertTrue(activity.canSaveDocumentReadingState());
            } finally { activity.executor.shutdownNow(); }
        });
    }
}
