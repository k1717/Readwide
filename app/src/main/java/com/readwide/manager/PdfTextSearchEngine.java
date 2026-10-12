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
 * Memory: scans glyphs at each page boundary. Only complete small-document
 * extractions are cached (8 pages / 128K UTF-16 slots); larger documents are
 * traversed again for a new query. PDDocument, current-page glyphs and result
 * rectangles remain outside that cache budget.
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
        default void onSearchFailed() { onSearchFinished(0, true); }
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
    private static final int CACHE_MAX_PAGES = 8;
    private static final int CACHE_MAX_CHARS = 128 * 1024;
    // Worker-owned, complete extractions only. Never publish a cancelled prefix.
    private java.util.Map<Integer, PageText> pageTexts;
    private final java.util.concurrent.ConcurrentHashMap<Integer, float[]> pageSizes =
            new java.util.concurrent.ConcurrentHashMap<>();
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
        try {
            final int n = document.getNumberOfPages();
            Scan scan = new Scan(gen, query, listener, n);
            if (pageTexts != null) {
                scan.cache = null; // Reuse the complete cache, do not copy it.
                for (int page = 0; page < n; page++) scan.accept(page, pageTexts.get(page));
            } else {
                DocStripper stripper = new DocStripper(scan);
                stripper.setSortByPosition(true);
                // One whole-document traversal; no redundant aggregate output string.
                stripper.writeText(document, new java.io.Writer() {
                    @Override public void write(char[] data, int offset, int length) { }
                    @Override public void flush() { }
                    @Override public void close() { }
                });
                scan.checkCurrent();
                pageTexts = scan.cache; // null for large documents, complete otherwise.
            }
            scan.checkCurrent();
            main.post(() -> {
                if (!closed && gen == generation.get()) listener.onSearchFinished(matchCount(), false);
            });
        } catch (IOException | RuntimeException failure) {
            synchronized (matches) {
                if (closed || gen != generation.get()) return;
                matches.clear();
                currentIndex = -1;
            }
            main.post(() -> {
                if (!closed && gen == generation.get()) listener.onSearchFailed();
            });
        }
    }

    private final class Scan {
        final int gen;
        final PdfSearchText.Query query;
        final Listener listener;
        final int totalPages;
        java.util.Map<Integer, PageText> cache;
        int cachedChars;
        boolean reportedFirst;

        Scan(int gen, PdfSearchText.Query query, Listener listener, int totalPages) {
            this.gen = gen;
            this.query = query;
            this.listener = listener;
            this.totalPages = totalPages;
            cache = totalPages <= CACHE_MAX_PAGES ? new java.util.HashMap<>() : null;
        }

        void checkCurrent() throws java.io.InterruptedIOException {
            if (closed || gen != generation.get()) throw new java.io.InterruptedIOException("Search superseded");
        }

        void accept(int page, PageText text) throws IOException {
            checkCurrent();
            if (text != null) {
                pageSizes.put(page, new float[]{text.pageWpts, text.pageHpts});
                if (cache != null) {
                    if (text.text.length() > CACHE_MAX_CHARS - cachedChars) {
                        cache.clear();
                        cache = null;
                    } else {
                        cachedChars += text.text.length();
                        cache.put(page, text);
                    }
                }
                List<Match> found = findOnPage(text, page, query, gen);
                synchronized (matches) {
                    checkCurrent();
                    matches.addAll(found);
                    if (currentIndex < 0 && !matches.isEmpty()) currentIndex = 0;
                }
            }
            checkCurrent();
            boolean firstNow = !reportedFirst && matchCount() > 0;
            if (firstNow) reportedFirst = true;
            if (firstNow || page % 8 == 0 || page == totalPages - 1) {
                final int soFar = matchCount();
                final Match first = firstNow ? matchAt(0) : null;
                main.post(() -> {
                    if (!closed && gen == generation.get()) {
                        listener.onSearchProgress(soFar, page + 1, totalPages, first);
                    }
                });
            }
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
            // scan() appends complete pages in ascending order. Find this
            // page's range without revisiting every other page's matches on
            // each highlight refresh, including while a scan is still running.
            int low = 0;
            int high = matches.size();
            while (low < high) {
                int middle = low + (high - low) / 2;
                if (matches.get(middle).pageIndex < pageIndex) low = middle + 1;
                else high = middle;
            }
            for (int i = low; i < matches.size(); i++) {
                Match match = matches.get(i);
                if (match.pageIndex != pageIndex) break;
                out.addAll(match.rectsPts);
            }
        }
        return out;
    }

    /** Point size of a page once it has been extracted, for the overlay scale.
     *  Returns null if not yet extracted. */
    @Nullable
    float[] pageSizePts(int pageIndex) {
        float[] size = pageSizes.get(pageIndex);
        return size == null ? null : size.clone();
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
            pageSizes.clear();
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

    private static RectF glyphBox(TextPosition tp) {
        return com.readwide.manager.util.PdfGlyphBoxMath.glyphBox(tp);
    }

    private static final class DocStripper extends PDFTextStripper {
        private final Scan scan;
        private StringBuilder builder;
        private List<RectF> rects;
        private float pageWpts = 1f;
        private float pageHpts = 1f;
        private int pageIndex0 = -1;

        DocStripper(Scan scan) throws IOException {
            super();
            this.scan = scan;
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            scan.checkCurrent();
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
        protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
            scan.checkCurrent();
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
                scan.accept(pageIndex0, new PageText(builder.toString(), rects, pageWpts, pageHpts));
                builder = null;
                rects = null;
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
