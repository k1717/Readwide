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
            // Locate the top-visible block's text directly in the rendered
            // buffer - both come from the same rendering, so a whitespace-
            // insensitive search gives the exact start of what the user sees.
            // The proportional source-offset mapping below is only a fallback:
            // Markdown syntax makes source and rendered text distribute
            // differently, so the ratio drifts by pages on larger documents.
            String anchorText = activity.lastMarkdownAnchorText;
            if (anchorText != null && !anchorText.trim().isEmpty()) {
                int found = indexOfCollapsed(fullText, anchorText);
                if (found >= 0) return snapToNaturalStart(fullText, found);
            }
            String source = activity.markdownSourceText;
            int srcLen = source != null ? source.length() : 0;
            if (srcLen <= 0) return 0;
            int srcOffset = Math.max(0, Math.min(srcLen, activity.lastMarkdownSourceOffset));
            double ratio = (double) srcOffset / (double) srcLen;
            return Math.max(0, Math.min(fullText.length(),
                    (int) Math.round(ratio * fullText.length())));
        }
        // Paged documents (EPUB/Word/HWP): normally the current page's start
        // offset, but if a one-shot resume anchor is set and falls within the
        // current page, begin from that exact saved position instead so
        // "continue reading aloud" resumes mid-page. The anchor is validated
        // against the current page bounds so a stale anchor (page changed) is
        // ignored rather than mispositioning playback.
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
        // Drive Markdown's scroll-following / speech anchor (no-op for paged
        // documents beyond the anchor bookkeeping).
        activity.onDocumentTtsSegmentSpoken(startChar, endChar);
        // Highlight the spoken sentence in the WebView. The buffer is plain text
        // (HTML flattened), so exact offsets don't map to the DOM; the controller
        // searches the DOM for this sentence's text instead.
        int s = Math.max(0, Math.min(fullText.length(), startChar));
        int e = Math.max(s, Math.min(fullText.length(), endChar));
        if (e > s) {
            // Markdown follows playback with its own scroll; let the highlight
            // scroll only in the paged viewers, and only when off-screen.
            activity.documentTtsHighlight().highlight(fullText.substring(s, e),
                    !activity.isMarkdownDocument());
        }
    }

    @Override
    public void clearTtsHighlight() {
        activity.documentTtsHighlight().clear();
        // Playback has stopped (this is only invoked on stop/finish/error paths,
        // never on start - verified against every ReaderTtsController call site).
        // Retire the Markdown speech anchor so the NEXT start reads from the
        // position the user is looking at: scrolled to the top means "from the
        // beginning". During playback the view follows the spoken position, so
        // an immediate restart still lands on the sentence that was being heard.
        activity.markdownTtsAnchorCharPosition = -1;
    }

    /** Delegates to {@link com.readwide.manager.util.TtsAnchorTextMath}. */
    static int snapToNaturalStart(String text, int pos) {
        return com.readwide.manager.util.TtsAnchorTextMath.snapToNaturalStart(text, pos);
    }

    /** Delegates to {@link com.readwide.manager.util.TtsAnchorTextMath}. */
    static int indexOfCollapsed(String hay, String needle) {
        return com.readwide.manager.util.TtsAnchorTextMath.indexOfCollapsed(hay, needle);
    }
}
