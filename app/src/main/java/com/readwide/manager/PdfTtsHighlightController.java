package com.readwide.manager;

import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Highlights the currently spoken read-aloud sentence on the PDF page,
 * mirroring {@link DocumentTtsHighlightController} for the WebView viewers.
 * PDF pages are rendered bitmaps, so this uses glyph coordinates instead of
 * DOM search: per-character boxes are extracted lazily for the visible page
 * (via {@link PdfPlainTextExtractor#extractPageGlyphs}), the segment's char
 * range is merged into per-line rectangles, and they are drawn by
 * {@link PdfPageView}'s dedicated read-aloud overlay layer (independent of the
 * search highlights, so speaking and searching don't clobber each other).
 *
 * <p>Correctness guard: the glyph extraction assembles text exactly like the
 * read-aloud buffer's extractor, and the extracted page text is verified equal
 * to the buffer's page slice before any box is trusted. On any mismatch the
 * page is marked unmappable and highlighting is skipped - the offsets would
 * not line up, and a wrong highlight is worse than none.</p>
 */
final class PdfTtsHighlightController {

    private final PdfReaderActivity activity;

    /** Cached glyphs for one page (the page being spoken). */
    @Nullable private PdfPlainTextExtractor.PageGlyphs cachedGlyphs;
    private int cachedPageIndex = -1;
    /** Page whose extraction failed or mismatched the buffer; don't retry it. */
    private int unmappablePageIndex = -1;
    private boolean extracting = false;

    /** Latest requested highlight, applied when async extraction completes. */
    private int pendingPageIndex = -1;
    private int pendingStart = -1;
    private int pendingEnd = -1;
    @Nullable private String pendingExpectedText;

    PdfTtsHighlightController(@NonNull PdfReaderActivity activity) {
        this.activity = activity;
    }

    /**
     * Highlights the spoken sentence. Offsets are page-relative; {@code
     * expectedPageText} is the read-aloud buffer's slice for that page, used to
     * verify the glyph extraction lines up before its boxes are trusted.
     */
    void highlight(int pageIndex, int startChar, int endChar, @NonNull String expectedPageText) {
        if (activity.activityDestroyed) return;
        if (pageIndex != activity.currentPage) {
            // Segment for a page that isn't displayed (e.g. right around a page
            // turn); the next segment on the visible page will highlight.
            clear();
            return;
        }
        if (pageIndex == unmappablePageIndex) return;
        if (cachedGlyphs != null && cachedPageIndex == pageIndex) {
            apply(cachedGlyphs, startChar, endChar);
            return;
        }
        // Need this page's glyphs; remember the latest request and extract once.
        pendingPageIndex = pageIndex;
        pendingStart = startChar;
        pendingEnd = endChar;
        pendingExpectedText = expectedPageText;
        if (extracting) return;
        extracting = true;
        final java.io.File file = activity.localFile;
        final int generation = activity.renderGeneration;
        activity.executor.execute(() -> {
            PdfPlainTextExtractor.PageGlyphs glyphs = file != null
                    ? PdfPlainTextExtractor.extractPageGlyphs(
                            activity.getApplicationContext(), file, pageIndex)
                    : null;
            activity.handler.post(() -> {
                extracting = false;
                if (activity.activityDestroyed || generation != activity.renderGeneration) return;
                String expected = pendingExpectedText;
                if (glyphs == null
                        || expected == null || !glyphs.text.equals(expected)) {
                    // Extraction failed or the two extractions disagree; offsets
                    // can't be trusted for this page.
                    unmappablePageIndex = pageIndex;
                    clear();
                    return;
                }
                cachedGlyphs = glyphs;
                cachedPageIndex = pageIndex;
                if (pendingPageIndex == pageIndex && pendingPageIndex == activity.currentPage
                        && pendingStart >= 0) {
                    apply(glyphs, pendingStart, pendingEnd);
                }
            });
        });
    }

    private void apply(@NonNull PdfPlainTextExtractor.PageGlyphs glyphs, int start, int end) {
        PdfPageView view = activity.pdfPageMatrixView;
        if (view == null) return;
        List<RectF> lines = PdfTtsHighlightMath.mergeCharBoxes(glyphs.charRectsPts, start, end);
        if (lines.isEmpty()) {
            view.clearTtsHighlights();
            return;
        }
        view.setTtsHighlights(PdfTtsHighlightMath.normalize(lines, glyphs.wPts, glyphs.hPts));
    }

    /** Removes the current highlight. */
    void clear() {
        PdfPageView view = activity.pdfPageMatrixView;
        if (view != null) view.clearTtsHighlights();
    }

    /**
     * Called when the displayed page changes; drops page-specific state.
     * Unconditional drop: this runs before {@code currentPage} is updated to the
     * new page, so comparing against it would keep exactly the stale entries,
     * and one page's extraction is cheap to redo.
     */
    void onPageChanged() {
        clear();
        pendingStart = -1;
        pendingPageIndex = -1;
        pendingExpectedText = null;
        cachedGlyphs = null;
        cachedPageIndex = -1;
        unmappablePageIndex = -1;
    }
}
