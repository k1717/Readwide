package com.readwide.manager;

import androidx.annotation.NonNull;

import com.readwide.manager.util.FileUtils;

import java.util.List;

/**
 * {@link TtsTextSource} for the WebView document viewer
 * ({@code DocumentPageActivity}: EPUB, Word, legacy .doc, HWP/HWPX). Built once
 * per loaded document from the rendered page HTML via
 * {@link FileUtils#htmlToPlainText}, it concatenates every page's plain text
 * into one buffer and keeps the start offset of each page, so the absolute
 * char positions the TTS controller works with map cleanly to page indices in
 * both directions.
 *
 * <p>The "visible page" the controller reads is the whole current document
 * page: {@code [pageStart[current], pageStart[current + 1])}. Because the full
 * buffer is resident, the controller's cross-page prefetch works, so a
 * high-latency neural voice keeps synthesizing across page seams exactly as in
 * the text reader.</p>
 *
 * <p>Highlight is a no-op in this first version: the WebView page has no char
 * offset to rendered-node mapping yet (a later pass can map spoken sentences to
 * the injected {@code window.__rwDocBlocks} anchors). Audio, page-follow,
 * pause/resume, the sleep timer, and the notification controls all work.</p>
 *
 * <p>Construction runs {@code Html.fromHtml} per page and should happen off the
 * main thread for large books; the object is immutable afterwards and safe to
 * read from the main thread.</p>
 */
final class DocumentTtsTextSource implements TtsTextSource {

    private final DocumentPageActivity activity;
    private final String fullText;
    /** Start offset of each page in {@link #fullText}; length = pageCount + 1. */
    private final int[] pageStartOffsets;

    private DocumentTtsTextSource(@NonNull DocumentPageActivity activity,
                                  @NonNull String fullText,
                                  @NonNull int[] pageStartOffsets) {
        this.activity = activity;
        this.fullText = fullText;
        this.pageStartOffsets = pageStartOffsets;
    }

    /** Build from the loaded pages. Call off the main thread for large documents. */
    @NonNull
    static DocumentTtsTextSource build(@NonNull DocumentPageActivity activity,
                                       @NonNull List<DocumentPageActivity.Page> pages) {
        int count = pages.size();
        int[] starts = new int[count + 1];
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            starts[i] = text.length();
            DocumentPageActivity.Page page = pages.get(i);
            String pageText = page != null ? FileUtils.htmlToPlainText(page.html) : "";
            if (pageText != null) {
                text.append(pageText);
            }
            // A hard separator so the segmenter never fuses the last sentence of
            // one page with the first of the next, and so every page owns at
            // least one char (keeps the start offsets strictly increasing, which
            // pageIndexForChar relies on).
            text.append('\n');
        }
        starts[count] = text.length();
        return new DocumentTtsTextSource(activity, text.toString(), starts);
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
        if (activity.isMarkdownDocument()) {
            // Markdown is one buffer over one rendered page, so the page-start
            // table would always answer 0 - which made every (re)start and every
            // resume speak from the top regardless of where playback or the view
            // actually was. Instead, track speech with an explicit anchor in
            // plain-text space: the activity advances it per spoken segment and
            // sets it on jumps (resume). When no anchor exists yet (fresh start),
            // reverse-map the viewer's current scroll position proportionally so
            // read-aloud begins near what the user is looking at.
            int anchor = activity.markdownTtsAnchorCharPosition;
            if (anchor >= 0) {
                return Math.max(0, Math.min(fullText.length(), anchor));
            }
            String source = activity.markdownSourceText;
            int srcLen = source != null ? source.length() : 0;
            if (srcLen <= 0) return 0;
            int srcOffset = Math.max(0, Math.min(srcLen, activity.lastMarkdownSourceOffset));
            double ratio = (double) srcOffset / (double) srcLen;
            return Math.max(0, Math.min(fullText.length(),
                    (int) Math.round(ratio * fullText.length())));
        }
        return pageStartOffsets[clampedCurrentPage()];
    }

    @Override
    public int getCharPositionAfterCurrentVisibleContent() {
        if (pageCount() <= 0) return 0;
        if (activity.isMarkdownDocument()) {
            // The whole remaining document is the "visible page" for Markdown -
            // there is no page list to bound it. See getCurrentCharPosition for
            // the anchor that supplies the start.
            return fullText.length();
        }
        return pageStartOffsets[clampedCurrentPage() + 1];
    }

    @Override
    public void setTtsHighlightRange(int startChar, int endChar) {
        // No glyph highlight on the WebView page yet, but this per-segment
        // callback is the signal the Markdown viewer uses to follow playback by
        // scrolling (approximate following) and to advance the speech anchor.
        // No-op for paged documents.
        activity.onDocumentTtsSegmentSpoken(startChar, endChar);
    }

    @Override
    public void clearTtsHighlight() {
        // No-op; nothing is ever highlighted.
    }
}
