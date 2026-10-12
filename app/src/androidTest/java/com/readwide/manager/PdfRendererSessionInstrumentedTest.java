package com.readwide.manager;

import android.graphics.pdf.PdfDocument;
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
        File file = File.createTempFile("pdf-session-", ".pdf",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        try {
            PdfDocument document = new PdfDocument();
            try {
                PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(100, 100, 1).create());
                document.finishPage(page);
                try (FileOutputStream out = new FileOutputStream(file)) { document.writeTo(out); }
            } finally { document.close(); }
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
}
