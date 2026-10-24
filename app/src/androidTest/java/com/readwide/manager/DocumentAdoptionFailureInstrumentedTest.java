package com.readwide.manager;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.view.View;

import androidx.appcompat.app.ActionBar;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** Injected UI adoption/render failures; does not load or render a real document. */
@RunWith(AndroidJUnit4.class)
public class DocumentAdoptionFailureInstrumentedTest {
    @Test public void adoptionFailureClosesUntransferredZipAndRevokesSaves() throws Exception {
        check(1);
    }

    @Test public void renderMemoryFailureRetiresTransferredZipAndRevokesSaves() throws Exception {
        check(2);
    }

    @Test public void renderStackFailureRetiresTransferredZipAndRevokesSaves() throws Exception {
        check(3);
    }

    @Test public void staleCallbackClosesOnlyItsParseAndDoesNotFinishNewerDocument() throws Exception {
        check(4);
    }

    @Test public void failedOldCallbackDoesNotClearAReentrantNewerDocument() throws Exception {
        check(5);
    }

    @Test public void successfulAdoptionTransfersOwnershipUntilRetirement() throws Exception {
        check(0);
    }

    private static void check(int mode) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File zip = File.createTempFile("readwide-adoption-", ".zip", context.getCacheDir());
        try {
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
                out.putNextEntry(new ZipEntry("chapter.xhtml"));
                out.write(1);
                out.closeEntry();
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Screen activity = new Screen();
                DocumentPageActivity.ParsedDocument parsed = new DocumentPageActivity.ParsedDocument("test.local");
                try {
                    parsed.resourceZip = new ZipFile(zip);
                    ZipFile owned = parsed.resourceZip;
                    parsed.docType = "EPUB";
                    parsed.pages.add(new DocumentPageActivity.Page("chapter", "<p>text</p>", "chapter.xhtml"));
                    activity.mode = mode;
                    activity.loadGeneration = mode == 4 ? 8 : 7;
                    activity.documentAnchorPageGeneration = 9;
                    activity.primaryDocumentPageReadyGeneration = 3;
                    activity.rightDocumentPageReadyGeneration = 4;
                    activity.documentLoadAccepted = mode == 4;
                    if (mode == 4) activity.pages.add(new DocumentPageActivity.Page("newer", "newer", null));
                    Intent intent = new Intent().putExtra(DocumentPageActivity.EXTRA_JUMP_TO_PAGE, 0);
                    new DocumentPageLoadController(activity).acceptParsedDocument(7, parsed, zip, intent);
                    assertNull(parsed.resourceZip);
                    if (mode == 0) {
                        assertNotNull(activity.epubNavigationSession());
                        assertNotNull(owned.getEntry("chapter.xhtml"));
                        assertTrue(activity.documentLoadAccepted);
                        assertEquals(0, activity.finishes);
                        assertEquals(0, activity.invalidatedPages);
                    } else {
                        try { owned.getEntry("chapter.xhtml"); fail("Retired parse must close"); }
                        catch (IllegalStateException expected) { }
                        if (mode == 4 || mode == 5) {
                            assertEquals(0, activity.finishes);
                            assertTrue(activity.documentLoadAccepted);
                            assertEquals("newer", activity.pages.get(0).html);
                            assertEquals(0, activity.invalidatedPages);
                            assertEquals(9, activity.documentAnchorPageGeneration);
                        } else {
                            assertEquals(1, activity.finishes);
                            assertFalse(activity.documentLoadAccepted);
                            assertFalse(activity.canSaveDocumentReadingState());
                            assertTrue(activity.pages.isEmpty());
                            assertNull(activity.epubNavigationSession());
                            assertEquals(2, activity.invalidatedPages);
                            assertEquals(10, activity.documentAnchorPageGeneration);
                            assertEquals(-1, activity.primaryDocumentPageReadyGeneration);
                            assertEquals(-1, activity.rightDocumentPageReadyGeneration);
                            // No Prefs/BookmarkManager: failure must stop before any save attempt.
                            activity.saveOutgoingDocumentReadingState();
                        }
                    }
                } catch (java.io.IOException failure) { throw new AssertionError(failure); }
                finally {
                    activity.activityDestroyed = true;
                    activity.closeResourceZip();
                    parsed.close();
                    activity.executor.shutdownNow();
                }
            });
        } finally { assertTrue(zip.delete()); }
    }

    private static final class Screen extends DocumentPageActivity {
        final View root = new View(InstrumentationRegistry.getInstrumentation().getTargetContext());
        int mode, finishes, invalidatedPages;
        @Override public Context getApplicationContext() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        }
        @Override public Resources getResources() { return getApplicationContext().getResources(); }
        @Override public ActionBar getSupportActionBar() { return null; }
        @SuppressWarnings("unchecked")
        @Override public <T extends View> T findViewById(int id) { return (T) root; }
        @Override void hideLoadingWindow() { }
        @Override void rememberOpeningDocumentViewport() { }
        @Override void applyDocumentTopPageStatusVisibility() { }
        @Override void applyDocumentSystemBarColors() { }
        @Override void invalidateDocumentPageLoad(android.webkit.WebView target) { invalidatedPages++; }
        @Override public void finish() { finishes++; }
        @Override void adoptParsedDocument(ParsedDocument parsed, File file) {
            if (mode == 1) throw new IllegalStateException("injected adoption failure");
            if (mode == 5) {
                loadGeneration++;
                documentLoadAccepted = true;
                pages.clear();
                pages.add(new Page("newer", "newer", null));
                throw new IllegalStateException("old callback after replacement");
            }
            super.adoptParsedDocument(parsed, file);
        }
        @Override void showPage(int page, int direction) {
            if (mode == 2) throw new OutOfMemoryError("injected display failure");
            if (mode == 3) throw new StackOverflowError("injected display nesting failure");
        }
    }
}
