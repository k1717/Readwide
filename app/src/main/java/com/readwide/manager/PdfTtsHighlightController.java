package com.readwide.manager;

import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
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
    /** Page currently queued/running on the shared executor, or -1. */
    private int extractingPageIndex = -1;
    /** Invalidates an in-flight page extraction after navigation/teardown state changes. */
    private int extractionGeneration = 0;

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
        // Always retain the latest request. The TTS callback can arrive while a
        // page/spread render is still in flight; once its exact bitmap geometry
        // commits, onDisplayedBitmapChanged() reapplies this request.
        pendingPageIndex = pageIndex;
        pendingStart = startChar;
        pendingEnd = endChar;
        pendingExpectedText = expectedPageText;
        if (!activity.isPdfPageVisibleInCurrentDisplay(pageIndex)) {
            // Segment for a display that has not settled yet. Do not paint stale
            // coordinates, but keep the request for the winning render.
            clearOverlay();
            return;
        }
        if (pageIndex == unmappablePageIndex) {
            // A spread can keep the other page's valid sentence overlay alive
            // while speech returns to this known-unmappable page. Showing that
            // stale rectangle is worse than showing no highlight.
            clearOverlay();
            return;
        }
        if (cachedGlyphs != null && cachedPageIndex == pageIndex) {
            if (extractingPageIndex >= 0 && extractingPageIndex != pageIndex) {
                extractionGeneration++;
                extractingPageIndex = -1;
            }
            apply(cachedGlyphs, startChar, endChar);
            return;
        }
        // A spread can move speech from the left page to the already-visible
        // right page without changing Activity.currentPage. Do not leave the
        // previous page's sentence painted while the new page's glyphs load.
        clearOverlay();
        requestPendingExtractionIfNeeded();
    }

    /**
     * Ensures the latest visible request has a glyph extraction. If page B is
     * requested while page A is still running, invalidate A's callback and
     * enqueue B immediately (the shared executor serializes the actual work).
     * Repeated sentence updates on the same page only replace the pending range.
     */
    private void requestPendingExtractionIfNeeded() {
        if (activity.activityDestroyed || pendingPageIndex < 0 || pendingStart < 0
                || pendingExpectedText == null
                || !activity.isPdfPageVisibleInCurrentDisplay(pendingPageIndex)
                || pendingPageIndex == unmappablePageIndex
                || (cachedGlyphs != null && cachedPageIndex == pendingPageIndex)) {
            return;
        }
        if (extractingPageIndex == pendingPageIndex) return;

        final int extractionPage = pendingPageIndex;
        final String extractionExpectedText = pendingExpectedText;
        extractingPageIndex = extractionPage;
        final java.io.File file = activity.localFile;
        final int generation = ++extractionGeneration;
        activity.executor.execute(() -> {
            PdfPlainTextExtractor.PageGlyphs glyphs = file != null
                    ? PdfPlainTextExtractor.extractPageGlyphs(
                            activity.getApplicationContext(), file, extractionPage)
                    : null;
            activity.handler.post(() -> {
                if (generation != extractionGeneration) return;
                extractingPageIndex = -1;
                if (activity.activityDestroyed) return;
                if (glyphs == null
                        || !glyphs.text.equals(extractionExpectedText)) {
                    // Extraction failed or the two extractions disagree; offsets
                    // can't be trusted for this page.
                    unmappablePageIndex = extractionPage;
                    clearOverlay();
                    return;
                }
                cachedGlyphs = glyphs;
                cachedPageIndex = extractionPage;
                if (pendingPageIndex == extractionPage
                        && activity.isPdfPageVisibleInCurrentDisplay(pendingPageIndex)
                        && pendingStart >= 0) {
                    apply(glyphs, pendingStart, pendingEnd);
                } else {
                    // The request moved to another visible half while this page
                    // was extracting. Guarantee that page gets its own job even
                    // if it has only one utterance and sends no later callback.
                    requestPendingExtractionIfNeeded();
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
        List<RectF> pageNormalized =
                PdfTtsHighlightMath.normalize(lines, glyphs.wPts, glyphs.hPts);
        List<RectF> displayed = new ArrayList<>(pageNormalized.size());
        for (RectF rect : pageNormalized) {
            RectF mapped = activity.mapPdfPageRectToDisplayedBitmap(cachedPageIndex, rect);
            if (mapped != null) displayed.add(mapped);
        }
        if (displayed.isEmpty()) {
            view.clearTtsHighlights();
        } else {
            view.setTtsHighlights(displayed);
        }
    }

    /** Reprojects the pending sentence after a new single/spread bitmap commits. */
    void onDisplayedBitmapChanged() {
        if (pendingPageIndex < 0 || pendingStart < 0 || pendingExpectedText == null
                || pendingPageIndex == unmappablePageIndex
                || !activity.isPdfPageVisibleInCurrentDisplay(pendingPageIndex)) {
            // Rotation can replace a spread with a single page without changing
            // Activity.currentPage. Never leave the old spread-normalized
            // rectangle painted over that new bitmap.
            clearOverlay();
            return;
        }
        if (cachedGlyphs != null && cachedPageIndex == pendingPageIndex
                && pendingStart >= 0) {
            apply(cachedGlyphs, pendingStart, pendingEnd);
        } else {
            clearOverlay();
            requestPendingExtractionIfNeeded();
        }
    }

    /** Removes the current highlight and cancels deferred re-projection. */
    void clear() {
        pendingStart = -1;
        pendingEnd = -1;
        pendingPageIndex = -1;
        pendingExpectedText = null;
        clearOverlay();
    }

    private void clearOverlay() {
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
        extractionGeneration++;
        extractingPageIndex = -1;
        clear();
        cachedGlyphs = null;
        cachedPageIndex = -1;
        unmappablePageIndex = -1;
    }
}
