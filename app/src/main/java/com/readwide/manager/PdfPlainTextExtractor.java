package com.readwide.manager;

import android.content.Context;

import androidx.annotation.NonNull;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Extracts per-page plain text from a PDF for read-aloud. This mirrors the
 * single-pass stripper approach proven in {@link PdfTextSearchEngine} (repeated
 * per-page {@code getText()} calls on one PDDocument were unreliable, only
 * returning the first page), but keeps only the text - it drops the glyph
 * rectangles the search engine needs, since read-aloud v1 follows pages, not
 * glyphs.
 *
 * <p>Rendering in the PDF viewer stays on {@code PdfRenderer}; this is a
 * separate, short-lived PdfBox handle opened only when read-aloud starts and
 * closed immediately after the one-pass extraction, so it does not hold a
 * second copy of the document open for the reader's lifetime.</p>
 *
 * <p>Runs off the main thread (PdfBox extraction is slow on large PDFs). The
 * caller owns threading; this class is a pure function of the file.</p>
 */
final class PdfPlainTextExtractor {

    private PdfPlainTextExtractor() {
    }

    /**
     * Extracts every page's plain text in one pass.
     *
     * @param context any context; used to initialize PdfBox's Android resource
     *     loader, which MUST happen before the stripper runs. The search path
     *     initializes it in {@code PdfSearchController}'s constructor, but
     *     read-aloud can be the first PdfBox user in the process (a user who
     *     never opened search), and without the init PdfBox's font/glyph
     *     resource loading fails - partly with {@link Error}s from static
     *     initializers, which no {@code catch (RuntimeException)} contains and
     *     which killed the process when read-aloud started.
     * @param pdf the PDF file
     * @return page index (0-based) -> plain text; pages with no extractable
     *     text (e.g. scanned/image-only pages) map to an empty string. Returns
     *     an empty map if the document cannot be opened.
     */
    @NonNull
    static Map<Integer, String> extractPageText(@NonNull Context context, @NonNull File pdf) {
        try {
            PDFBoxResourceLoader.init(context.getApplicationContext());
        } catch (Throwable ignored) {
            // Mirrors PdfSearchController: init is best-effort; extraction below
            // still runs and reports "no text" if PdfBox cannot operate.
        }
        PDDocument document = null;
        try {
            document = PDDocument.load(pdf);
            PageStripper stripper = new PageStripper();
            stripper.setSortByPosition(true);
            stripper.getText(document);
            return stripper.result;
        } catch (Throwable e) {
            // Extraction is best-effort and must never take the process down:
            // this runs on a bare executor thread where an uncaught throw kills
            // the app. Throwable (not just IOException/RuntimeException) because
            // PdfBox's font machinery can throw Errors (static-init failures,
            // NoClassDefFoundError on retry) for broken environments or PDFs.
            // An empty map means "no text" and surfaces as the scanned-PDF toast.
            // Log it - a contained failure that is also silent would leave "PDF
            // read-aloud says scanned but the PDF has text" reports undiagnosable.
            android.util.Log.w("ReadwideTts", "PDF text extraction failed for "
                    + pdf.getName(), e);
            return new HashMap<>();
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (IOException ignored) {
                    // Nothing else to do on close failure.
                }
            }
        }
    }

    /** One-pass stripper that buckets text by page, like the search engine's. */
    private static final class PageStripper extends PDFTextStripper {
        final Map<Integer, String> result = new HashMap<>();
        private StringBuilder builder;
        private int pageIndex0 = -1;

        PageStripper() throws IOException {
            super();
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            pageIndex0 = getCurrentPageNo() - 1;
            builder = new StringBuilder();
            super.startPage(page);
        }

        @Override
        protected void writeString(String string,
                                   java.util.List<com.tom_roush.pdfbox.text.TextPosition> textPositions) {
            if (builder != null && string != null) {
                builder.append(string);
            }
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            if (builder != null) {
                builder.append('\n');
            }
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            if (pageIndex0 >= 0) {
                result.put(pageIndex0, builder != null ? builder.toString() : "");
            }
            super.endPage(page);
        }
    }
}
