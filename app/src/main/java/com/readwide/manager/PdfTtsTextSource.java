package com.readwide.manager;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.Map;

/**
 * {@link TtsTextSource} for the PDF reader ({@code PdfReaderActivity}, which
 * displays pages with {@code android.graphics.pdf.PdfRenderer} and has no text
 * layer of its own). Text comes from PdfBox via {@link PdfPlainTextExtractor}
 * (the same library the PDF search already uses), extracted once and
 * concatenated into a single buffer with per-page start offsets, so the TTS
 * controller's absolute char positions map to page indices both ways - exactly
 * like {@link DocumentTtsTextSource} does for the WebView viewer.
 *
 * <p>The "visible page" the controller reads is the whole current PDF page
 * {@code [pageStart[current], pageStart[current + 1])}. Because the buffer is
 * fully resident, the controller's cross-page prefetch works, so continuous
 * read-aloud keeps synthesizing across page turns.</p>
 *
 * <p>Highlight is a no-op in this first version: read-aloud follows pages, not
 * glyphs. (PdfBox does expose glyph rectangles - the search engine uses them -
 * so sentence highlight on the bitmap page is a possible later pass.)</p>
 *
 * <p>Scanned / image-only PDFs extract no text; {@link #hasAnyText()} lets the
 * caller show a clear "no selectable text" message instead of starting silent
 * playback.</p>
 */
final class PdfTtsTextSource implements TtsTextSource {

    private final PdfReaderActivity activity;
    private final String fullText;
    /** Start offset of each page in {@link #fullText}; length = pageCount + 1. */
    private final int[] pageStartOffsets;
    private final boolean hasAnyText;

    private PdfTtsTextSource(@NonNull PdfReaderActivity activity,
                             @NonNull String fullText,
                             @NonNull int[] pageStartOffsets,
                             boolean hasAnyText) {
        this.activity = activity;
        this.fullText = fullText;
        this.pageStartOffsets = pageStartOffsets;
        this.hasAnyText = hasAnyText;
    }

    /**
     * Extracts the PDF's text and builds the buffer. Call off the main thread:
     * PdfBox extraction is slow on large PDFs.
     *
     * @param pageCount the renderer's page count (authoritative for paging;
     *     text extraction may not report every page, e.g. image-only pages)
     */
    @NonNull
    static PdfTtsTextSource build(@NonNull PdfReaderActivity activity,
                                  @NonNull File pdf,
                                  int pageCount) {
        Map<Integer, String> byPage =
                PdfPlainTextExtractor.extractPageText(activity.getApplicationContext(), pdf);
        return build(activity, byPage, pageCount);
    }

    /**
     * Builds the buffer from an already-extracted per-page text map. Split out
     * from the file-loading {@link #build(PdfReaderActivity, File, int)} so the
     * offset/paging math is unit-testable without a real PDF.
     */
    @NonNull
    static PdfTtsTextSource build(@NonNull PdfReaderActivity activity,
                                  @NonNull Map<Integer, String> byPage,
                                  int pageCount) {
        int count = Math.max(pageCount, 0);
        // If extraction saw more pages than the renderer reported (shouldn't
        // happen, but be defensive), grow to cover them so offsets stay valid.
        for (Integer key : byPage.keySet()) {
            if (key != null && key + 1 > count) count = key + 1;
        }

        int[] starts = new int[count + 1];
        StringBuilder text = new StringBuilder();
        boolean sawText = false;
        for (int i = 0; i < count; i++) {
            starts[i] = text.length();
            String pageText = byPage.get(i);
            if (pageText != null && !pageText.isEmpty()) {
                text.append(pageText);
                if (!pageText.trim().isEmpty()) sawText = true;
            }
            // Hard per-page separator: keeps page offsets strictly increasing
            // (pageIndexForChar relies on it) and stops the segmenter fusing the
            // last sentence of one page with the first of the next.
            text.append('\n');
        }
        starts[count] = text.length();
        return new PdfTtsTextSource(activity, text.toString(), starts, sawText);
    }

    /** False for scanned/image-only PDFs with no extractable text. */
    boolean hasAnyText() {
        return hasAnyText;
    }

    private int pageCount() {
        return pageStartOffsets.length - 1;
    }

    private int clampedCurrentPage() {
        int page = activity.currentPage;
        return Math.max(0, Math.min(pageCount() - 1, page));
    }

    /** Page index owning the given absolute char position (clamped into range). */
    int pageIndexForChar(int charPosition) {
        int count = pageCount();
        if (count <= 0) return 0;
        if (charPosition <= 0) return 0;
        if (charPosition >= fullText.length()) return count - 1;
        int low = 0;
        int high = count - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (pageStartOffsets[mid] <= charPosition) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    @Override
    public String getTextContent() {
        return fullText;
    }

    @Override
    public int getCurrentCharPosition() {
        if (pageCount() <= 0) return 0;
        // Normally the current page's start offset, but if a one-shot resume
        // anchor is set and falls within the current page, begin from that exact
        // saved position instead so "continue reading aloud" resumes mid-page.
        // The anchor is validated against the current page bounds so a stale
        // anchor (page changed) is ignored rather than mispositioning playback.
        int page = clampedCurrentPage();
        int pageStart = pageStartOffsets[page];
        int anchor = activity.pagedTtsResumeAnchorCharPosition;
        if (anchor > pageStart) {
            int pageEnd = pageStartOffsets[page + 1];
            if (anchor < pageEnd) {
                // One-shot: consume on read. The resume queue reads it exactly
                // once to start mid-page; leaving it armed made the dialog's
                // "page" restart begin from the saved spot instead of the top.
                activity.pagedTtsResumeAnchorCharPosition = -1;
                return anchor;
            }
        }
        return pageStart;
    }

    @Override
    public int getCharPositionAfterCurrentVisibleContent() {
        if (pageCount() <= 0) return 0;
        return pageStartOffsets[clampedCurrentPage() + 1];
    }

    @Override
    public void setTtsHighlightRange(int startChar, int endChar) {
        if (pageCount() <= 0 || endChar <= startChar) return;
        int page = pageIndexForChar(startChar);
        int pageStart = pageStartOffsets[page];
        // The buffer appends one '\n' separator after every page's text (see
        // build); the page's real content ends just before it. The glyph
        // extraction sees only the page content, so both the expected text and
        // the char range must exclude the separator or the alignment check
        // would always fail and the highlight would never appear.
        int contentEnd = pageStartOffsets[page + 1] - 1;
        if (contentEnd <= pageStart) return; // empty (e.g. image-only) page
        int relStart = Math.max(0, startChar - pageStart);
        int relEnd = Math.max(relStart, Math.min(endChar, contentEnd) - pageStart);
        if (relEnd <= relStart) return;
        // The expected text lets the highlighter verify its glyph extraction
        // lines up with this buffer before trusting any coordinates.
        activity.pdfTtsHighlight().highlight(page, relStart, relEnd,
                fullText.substring(pageStart, contentEnd));
    }

    @Override
    public void clearTtsHighlight() {
        activity.pdfTtsHighlight().clear();
    }
}
