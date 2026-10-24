package com.readwide.manager;

import android.graphics.pdf.PdfDocument;
import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

/** Native session ownership checks; run explicitly on a device, not during packaging. */
@RunWith(AndroidJUnit4.class)
public class PdfRendererSessionInstrumentedTest {
    @Test public void unopenedHandoffCanCloseBothRenderersIdempotently() throws Exception {
        File file = onePageFixture();
        try {
            // Instrumentation test methods run off the app's main thread.
            PdfRendererSession session = PdfRendererSession.open(file);
            try {
                assertEquals(1, session.pageCount);
                assertNotNull(session.primary);
                android.graphics.pdf.PdfRenderer.Page page = session.primary.openPage(0);
                try {
                    assertEquals(100, page.getWidth());
                } finally { page.close(); }
            } finally {
                session.closeUnpublished();
                session.closeUnpublished();
            }
        } finally { assertTrue(file.delete()); }
    }

    @Test public void spreadBufferKeepsItsSizeAndWhitePaperAndReleasesNativePage() throws Exception {
        File file = onePageFixture();
        try {
            PdfRendererSession session = PdfRendererSession.open(file);
            Bitmap bitmap = null;
            try {
                bitmap = PdfReaderActivity.renderPdfPageBitmapLocked(session.primary, 0, 64, 96);
                assertFalse(bitmap.isRecycled());
                assertEquals(64, bitmap.getWidth());
                assertEquals(96, bitmap.getHeight());
                assertEquals(Color.WHITE, bitmap.getPixel(32, 48));
                try (android.graphics.pdf.PdfRenderer.Page page = session.primary.openPage(0)) {
                    assertEquals(100, page.getWidth());
                }
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                session.closeUnpublished();
            }
        } finally { assertTrue(file.delete()); }
    }

    @Test public void failedSpreadPageOpenAllowsTheNextValidRender() throws Exception {
        File file = onePageFixture();
        try {
            PdfRendererSession session = PdfRendererSession.open(file);
            Bitmap bitmap = null;
            try {
                try {
                    bitmap = PdfReaderActivity.renderPdfPageBitmapLocked(session.primary,
                            session.pageCount, 64, 96);
                    fail("An out-of-range page must not return a bitmap");
                } catch (IllegalArgumentException expected) {
                    // Bitmap cleanup on this non-returning path is also source-checked.
                }
                bitmap = PdfReaderActivity.renderPdfPageBitmapLocked(session.primary, 0, 64, 96);
                assertFalse(bitmap.isRecycled());
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                session.closeUnpublished();
            }
        } finally { assertTrue(file.delete()); }
    }

    private static File onePageFixture() throws Exception {
        File file = File.createTempFile("pdf-session-", ".pdf",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        boolean written = false;
        try {
            PdfDocument document = new PdfDocument();
            try {
                PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(100, 100, 1).create());
                document.finishPage(page);
                try (FileOutputStream out = new FileOutputStream(file)) { document.writeTo(out); }
            } finally { document.close(); }
            written = true;
            return file;
        } finally {
            if (!written) file.delete();
        }
    }
}
