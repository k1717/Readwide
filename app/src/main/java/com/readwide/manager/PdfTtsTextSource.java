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
        return pageStartOffsets[clampedCurrentPage()];
    }

    @Override
    public int getCharPositionAfterCurrentVisibleContent() {
        if (pageCount() <= 0) return 0;
        return pageStartOffsets[clampedCurrentPage() + 1];
    }

    @Override
    public void setTtsHighlightRange(int startChar, int endChar) {
        // No glyph-level highlight surface on the bitmap page yet (see class Javadoc).
    }

    @Override
    public void clearTtsHighlight() {
        // No-op; nothing is ever highlighted.
    }
}
