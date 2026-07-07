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
 * Extracts PDF text for read-aloud. The main path builds a page-indexed plain
 * text buffer with the single-pass stripper approach proven in
 * {@link PdfTextSearchEngine} (repeated per-page {@code getText()} calls on one
 * PDDocument were unreliable, only returning the first page). The helper path
 * can also extract page-indexed glyph rectangles for the PDF read-aloud
 * highlight layer.
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

    /**
     * One page's text with a glyph rectangle per character, for the read-aloud
     * sentence highlight. {@code charRectsPts} is index-aligned with
     * {@code text} (line separators get the previous glyph's box); rectangles
     * are in PDF point space with a top-left origin, and {@code wPts}/{@code
     * hPts} are the (rotation-adjusted) page dimensions to normalize against -
     * the same conventions the search-highlight overlay consumes.
     */
    static final class PageGlyphs {
        final String text;
        final java.util.List<android.graphics.RectF> charRectsPts;
        final float wPts;
        final float hPts;

        PageGlyphs(String text, java.util.List<android.graphics.RectF> charRectsPts,
                   float wPts, float hPts) {
            this.text = text;
            this.charRectsPts = charRectsPts;
            this.wPts = wPts;
            this.hPts = hPts;
        }
    }

    /**
     * Extracts one page's text together with per-character glyph boxes. Text
     * assembly mirrors {@link PageStripper} exactly (append the run string,
     * '\n' on line separators) so the result can be verified against the
     * read-aloud buffer's page slice; callers MUST do that verification and
     * skip highlighting when it fails, because a mismatch means the character
     * offsets don't line up. Returns null when the page can't be extracted or
     * the glyph list doesn't align 1:1 with the text.
     */
    static PageGlyphs extractPageGlyphs(@NonNull Context context, @NonNull File pdf, int pageIndex) {
        try {
            PDFBoxResourceLoader.init(context.getApplicationContext());
        } catch (Throwable ignored) {
        }
        PDDocument document = null;
        try {
            document = PDDocument.load(pdf);
            GlyphStripper stripper = new GlyphStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            stripper.getText(document);
            String text = stripper.builder != null ? stripper.builder.toString() : "";
            if (stripper.rects == null || stripper.rects.size() != text.length()) {
                // Chunk string and TextPosition list disagreed somewhere; offsets
                // would be unreliable, so report "can't map" instead of guessing.
                return null;
            }
            return new PageGlyphs(text, stripper.rects, stripper.pageWpts, stripper.pageHpts);
        } catch (Throwable e) {
            android.util.Log.w("ReadwideTts", "PDF glyph extraction failed for "
                    + pdf.getName() + " page " + pageIndex, e);
            return null;
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Single-page stripper collecting text (assembled exactly like
     * {@link PageStripper}) plus one glyph box per character. Box math and the
     * rotation-adjusted page dimensions mirror the search engine's proven
     * stripper.
     */
    private static final class GlyphStripper extends PDFTextStripper {
        StringBuilder builder;
        java.util.List<android.graphics.RectF> rects;
        float pageWpts;
        float pageHpts;

        GlyphStripper() throws IOException {
            super();
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            builder = new StringBuilder();
            rects = new java.util.ArrayList<>();
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle box = page.getCropBox();
            int rotation = (page.getRotation() % 360 + 360) % 360;
            if (rotation == 90 || rotation == 270) {
                pageWpts = box.getHeight();
                pageHpts = box.getWidth();
            } else {
                pageWpts = box.getWidth();
                pageHpts = box.getHeight();
            }
            super.startPage(page);
        }

        @Override
        protected void writeString(String string,
                                   java.util.List<com.tom_roush.pdfbox.text.TextPosition> textPositions) {
            if (builder == null || string == null) return;
            builder.append(string);
            if (rects == null) return;
            // Build the per-character boxes for this run. The run string is
            // normally the concatenation of the positions' unicode; when the
            // lengths disagree (rare normalization cases) pad/trim against the
            // string so rects stays index-aligned - the caller's whole-text
            // equality check still guards correctness.
            int need = string.length();
            int added = 0;
            android.graphics.RectF last = null;
            if (textPositions != null) {
                for (com.tom_roush.pdfbox.text.TextPosition tp : textPositions) {
                    String u = tp.getUnicode();
                    if (u == null || u.isEmpty()) continue;
                    android.graphics.RectF box = glyphBox(tp);
                    last = box;
                    for (int i = 0; i < u.length() && added < need; i++) {
                        rects.add(box);
                        added++;
                    }
                    if (added >= need) break;
                }
            }
            while (added < need) {
                rects.add(last != null ? last : new android.graphics.RectF());
                added++;
            }
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            if (builder != null) {
                builder.append('\n');
                if (rects != null) {
                    rects.add(!rects.isEmpty()
                            ? rects.get(rects.size() - 1) : new android.graphics.RectF());
                }
            }
        }

        /** Same box formula as the search engine's glyphBox. */
        private static android.graphics.RectF glyphBox(com.tom_roush.pdfbox.text.TextPosition tp) {
            float left = tp.getXDirAdj();
            float width = tp.getWidthDirAdj();
            float h = tp.getHeightDir();
            float top = tp.getYDirAdj() - h - h * 0.32f;
            float bottom = tp.getYDirAdj() + h * 0.42f;
            return new android.graphics.RectF(left, top, left + width, bottom);
        }
    }

    /** One-pass stripper that buckets text by page, like the search engine's. */    private static final class PageStripper extends PDFTextStripper {
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
