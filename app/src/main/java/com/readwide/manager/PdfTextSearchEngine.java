package com.readwide.manager;

import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Production text-search engine for the PDF reader, compatibility path.
 *
 * Rendering stays on PdfRenderer; this only reads text + glyph positions from
 * PdfBox-Android and finds matches. Rectangles are kept in PDF point space
 * (top-left origin), so the overlay can draw them through PdfPageView's live
 * zoom/pan Matrix. The page -> pixel scale uses each page point size, captured
 * here, against the rendered bitmap size (or your lastRenderedPage*Pts).
 *
 * This supersedes the extraction in PdfTextSearchSpike. Keep the spike only as
 * the on-device harness for confirming the top/baseline of a glyph box.
 *
 * Threading: extraction and scanning run on a single background thread; a new
 * query cancels the previous scan via a generation token. Listener callbacks
 * are posted to the main thread.
 *
 * Memory: holds the PDDocument open and extracts all page text/geometry on the
 * first nonempty valid query. The retained map is not a bounded page LRU.
 * This is a second handle on the file,
 * separate from the render PdfRenderer. Call close() when leaving the reader
 * (the same lifecycle care the 1.0.7/1.0.8 trim work taught us).
 *
 * Setup: implementation "com.tom-roush:pdfbox-android"; and once at startup
 * com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(appContext).
 *
 * Verification history and pending device checks are recorded in docs.
 */
final class PdfTextSearchEngine {

    /* ---- options mirror ReaderSearchController toggles ---- */
    static final class Options {
        boolean caseSensitive;
        boolean wholeWord;
        boolean regex;
    }

    /** A single match, located on one page, in PDF point space (top-left). */
    static final class Match {
        final int pageIndex;     // 0-based
        final int charStart;
        final int charEnd;
        final List<RectF> rectsPts; // one box per visual line of the match

        Match(int pageIndex, int charStart, int charEnd, List<RectF> rectsPts) {
            this.pageIndex = pageIndex;
            this.charStart = charStart;
            this.charEnd = charEnd;
            this.rectsPts = rectsPts;
        }
    }

    /** Progress + result callbacks, delivered on the main thread. */
    interface Listener {
        /** Called as pages are scanned. firstMatch is non-null the first time
         *  any match is found, so the caller can jump to it immediately. */
        void onSearchProgress(int matchesSoFar, int scannedPages, int totalPages,
                              @Nullable Match firstMatch);

        /** Completion for the current query only; superseded/cleared queries are silent. */
        void onSearchFinished(int totalMatches, boolean cancelled);
    }

    private static final class PageText {
        final String text;
        final List<RectF> charRectsPts; // index-aligned with text
        final float pageWpts;
        final float pageHpts;

        PageText(String text, List<RectF> charRectsPts, float pageWpts, float pageHpts) {
            this.text = text;
            this.charRectsPts = charRectsPts;
            this.pageWpts = pageWpts;
            this.pageHpts = pageHpts;
        }
    }

    private final PDDocument document;
    private final int pageCount;
    private volatile java.util.Map<Integer, PageText> pageTexts;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean closed;

    private final List<Match> matches = new ArrayList<>();
    private int currentIndex = -1;

    PdfTextSearchEngine(PDDocument document, int pageCount) {
        this.document = document;
        this.pageCount = pageCount;
    }

    /* ------------------------------------------------------------------ */
    /* Search                                                              */
    /* ------------------------------------------------------------------ */

    /** Start a fresh search. Cancels any running scan. Safe to call on the
     *  main thread; the scan itself runs in the background. */
    synchronized void startSearch(final String rawQuery, final Options options,
                                  final Listener listener) {
        if (closed) return;
        clearSearch();
        final int gen = generation.get();
        if (rawQuery == null || rawQuery.isEmpty()) {
            main.post(() -> {
                if (!closed && gen == generation.get()) {
                    listener.onSearchFinished(0, false);
                }
            });
            return;
        }
        // Snapshot mutable controller options before handing work to the scan thread.
        final boolean caseSensitive = options.caseSensitive;
        final boolean wholeWord = options.wholeWord;
        final boolean regex = options.regex;
        try {
            executor.execute(() -> {
                if (closed || gen != generation.get()) return;
                PdfSearchText.Query query = PdfSearchText.compile(rawQuery, caseSensitive, wholeWord, regex);
                scan(gen, query, listener);
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            postCancelled(gen, listener);
        }
    }

    /** Invalidate matches immediately, including while the next query is debouncing. */
    synchronized void clearSearch() {
        if (closed) return;
        generation.incrementAndGet();
        executor.getQueue().clear(); // One running scan plus at most one newest pending query.
        synchronized (matches) {
            matches.clear();
            currentIndex = -1;
        }
    }

    private void scan(int gen, PdfSearchText.Query query, Listener listener) {
        if (closed || gen != generation.get()) return;
        if (query == null) {
            main.post(() -> {
                if (!closed && gen == generation.get()) listener.onSearchFinished(0, false);
            });
            return;
        }
        ensureExtracted();
        if (closed || gen != generation.get()) {
            return;
        }
        final int n = document.getNumberOfPages();
        boolean reportedFirst = false;
        for (int page = 0; page < n; page++) {
            if (closed || gen != generation.get()) {
                return;
            }
            PageText pt = pageTexts == null ? null : pageTexts.get(page);
            if (pt == null) {
                continue; // no extractable text on this page
            }
            List<Match> pageMatches = findOnPage(pt, page, query, gen);
            if (!pageMatches.isEmpty()) {
                boolean cancelled;
                synchronized (matches) {
                    // startSearch increments generation before taking this same
                    // lock to clear results. Therefore an old scan can either
                    // commit before the new clear or be rejected after it, but
                    // can never append query-A matches into query-B results.
                    cancelled = gen != generation.get();
                    if (!cancelled) {
                        matches.addAll(pageMatches);
                        if (currentIndex < 0) {
                            currentIndex = 0;
                        }
                    }
                }
                if (cancelled) {
                    return;
                }
            }

            boolean firstNow = !reportedFirst && matchCount() > 0;
            if (firstNow) {
                reportedFirst = true;
            }
            // Throttle UI: report on first match, every 8 pages, and at the end.
            if (firstNow || page % 8 == 0 || page == n - 1) {
                final int scanned = page + 1;
                final int soFar = matchCount();
                final Match first = firstNow ? matchAt(0) : null;
                if (gen == generation.get()) {
                    main.post(() -> {
                        if (!closed && gen == generation.get()) {
                            listener.onSearchProgress(soFar, scanned, n, first);
                        }
                    });
                }
            }
        }
        if (gen == generation.get()) {
            main.post(() -> {
                if (!closed && gen == generation.get()) {
                    listener.onSearchFinished(matchCount(), false);
                }
            });
        }
    }

    private void postCancelled(int gen, Listener listener) {
        main.post(() -> {
            if (!closed && gen == generation.get()) listener.onSearchFinished(matchCount(), true);
        });
    }

    /* ------------------------------------------------------------------ */
    /* Navigation (mirrors performTextSearchMove ordinal/total)            */
    /* ------------------------------------------------------------------ */

    @Nullable
    Match moveTo(boolean forward) {
        synchronized (matches) {
            if (matches.isEmpty()) {
                return null;
            }
            if (currentIndex < 0) {
                currentIndex = 0;
            } else {
                currentIndex = forward
                        ? (currentIndex + 1) % matches.size()
                        : (currentIndex - 1 + matches.size()) % matches.size();
            }
            return matches.get(currentIndex);
        }
    }

    @Nullable
    Match current() {
        synchronized (matches) {
            return (currentIndex >= 0 && currentIndex < matches.size())
                    ? matches.get(currentIndex) : null;
        }
    }

    /** 1-based position of the current match, or 0 if none. */
    int ordinal() {
        synchronized (matches) {
            return currentIndex < 0 ? 0 : currentIndex + 1;
        }
    }

    int total() {
        return matchCount();
    }

    /** All match rectangles on a page (point space) for the overlay to draw. */
    List<RectF> matchesOnPage(int pageIndex) {
        List<RectF> out = new ArrayList<>();
        synchronized (matches) {
            for (Match m : matches) {
                if (m.pageIndex == pageIndex) {
                    out.addAll(m.rectsPts);
                }
            }
        }
        return out;
    }

    /** Point size of a page once it has been extracted, for the overlay scale.
     *  Returns null if not yet extracted. */
    @Nullable
    float[] pageSizePts(int pageIndex) {
        java.util.Map<Integer, PageText> snapshot = pageTexts;
        PageText pt = snapshot == null ? null : snapshot.get(pageIndex);
        return pt == null ? null : new float[]{pt.pageWpts, pt.pageHpts};
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        generation.incrementAndGet();
        executor.getQueue().clear();
        main.removeCallbacksAndMessages(null);
        synchronized (matches) {
            matches.clear();
            currentIndex = -1;
        }
        // PDFBox extraction is not reliably interruptible. Queue cleanup on the
        // same single-thread executor so PDDocument/pageTexts are never closed
        // concurrently with PDFTextStripper or scan(). shutdown() still runs
        // this final task after the current scan observes the new generation.
        executor.execute(() -> {
            pageTexts = null;
            try {
                document.close();
            } catch (IOException ignored) {
            }
        });
        executor.shutdown();
    }

    /* ------------------------------------------------------------------ */
    /* Extraction (self-contained; supersedes the spike)                   */
    /* ------------------------------------------------------------------ */

    /**
     * Extract every page once, in a single pass over the whole document.
     * Repeated per-page getText() calls on the same PDDocument proved
     * unreliable (only the first page came back), so this walks the document
     * once and buckets text and glyph boxes by page. Runs on the scan thread.
     */
    private void ensureExtracted() {
        if (pageTexts != null) {
            return;
        }
        DocStripper stripper;
        try {
            stripper = new DocStripper();
            stripper.setSortByPosition(true);
            stripper.getText(document);
        } catch (IOException | RuntimeException e) {
            stripper = null; // Do not publish a partially extracted document as complete.
        }
        pageTexts = java.util.Collections.unmodifiableMap(
                (stripper != null) ? stripper.result : new java.util.HashMap<>());
    }

    private static RectF glyphBox(TextPosition tp) {
        return com.readwide.manager.util.PdfGlyphBoxMath.glyphBox(tp);
    }

    private static final class DocStripper extends PDFTextStripper {
        final java.util.Map<Integer, PageText> result = new java.util.HashMap<>();
        private StringBuilder builder;
        private List<RectF> rects;
        private float pageWpts = 1f;
        private float pageHpts = 1f;
        private int pageIndex0 = -1;

        DocStripper() throws IOException {
            super();
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            pageIndex0 = getCurrentPageNo() - 1;
            builder = new StringBuilder();
            rects = new ArrayList<>();
            PDRectangle box = page.getCropBox();
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
        protected void writeString(String string, List<TextPosition> textPositions) {
            if (builder == null) {
                return;
            }
            for (TextPosition tp : textPositions) {
                String u = tp.getUnicode();
                if (u == null || u.isEmpty()) {
                    continue;
                }
                RectF box = glyphBox(tp);
                for (int i = 0; i < u.length(); i++) {
                    builder.append(u.charAt(i));
                    rects.add(box);
                }
            }
        }

        @Override
        protected void writeWordSeparator() {
            if (builder != null) PdfSearchText.appendSeparator(builder, rects, " ");
        }

        @Override
        protected void writeLineSeparator() {
            if (builder != null) PdfSearchText.appendSeparator(builder, rects, "\n");
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            if (pageIndex0 >= 0 && builder != null) {
                result.put(pageIndex0, new PageText(builder.toString(), rects, pageWpts, pageHpts));
            }
            super.endPage(page);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Matching                                                            */
    /* ------------------------------------------------------------------ */

    private List<Match> findOnPage(PageText pt, int pageIndex, PdfSearchText.Query query, int gen) {
        List<Match> out = new ArrayList<>();
        query.forEach(pt.text, () -> closed || gen != generation.get(),
                (start, end) -> out.add(new Match(pageIndex, start, end,
                        mergeRun(pt.charRectsPts, start, end))));
        return out;
    }

    /** Merge charRects[start, end) into one box per visual line. */
    private static List<RectF> mergeRun(List<RectF> charRects, int start, int end) {
        List<RectF> out = new ArrayList<>();
        RectF run = null;
        for (int i = start; i < end && i < charRects.size(); i++) {
            RectF c = charRects.get(i);
            if (c == null) {
                continue;
            }
            if (run == null) {
                run = new RectF(c);
                continue;
            }
            boolean sameLine = Math.abs(c.centerY() - run.centerY()) <= c.height() * 0.5f
                    && c.left >= run.left - 1f;
            if (sameLine) {
                run.union(c);
            } else {
                out.add(run);
                run = new RectF(c);
            }
        }
        if (run != null) {
            out.add(run);
        }
        return out;
    }

    /* ------------------------------------------------------------------ */

    private int matchCount() {
        synchronized (matches) {
            return matches.size();
        }
    }

    @Nullable
    private Match matchAt(int index) {
        synchronized (matches) {
            return (index >= 0 && index < matches.size()) ? matches.get(index) : null;
        }
    }
}
