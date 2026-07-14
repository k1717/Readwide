package com.readwide.manager;

import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import androidx.annotation.Nullable;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
 * Memory: holds the PDDocument open for lazy per-page extraction and caches a
 * bounded number of page indexes. This is a second handle on the file,
 * separate from the render PdfRenderer. Call close() when leaving the reader
 * (the same lifecycle care the 1.0.7/1.0.8 trim work taught us).
 *
 * Setup: implementation "com.tom-roush:pdfbox-android"; and once at startup
 * com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(appContext).
 *
 * Unbuilt reference (no Android SDK in the authoring environment); build and
 * test it in your own build.
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

        /** Called once the whole document has been scanned (or cancelled). */
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
    private java.util.Map<Integer, PageText> pageTexts;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
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
        final int gen = generation.incrementAndGet();
        synchronized (matches) {
            matches.clear();
            currentIndex = -1;
        }
        if (rawQuery == null || rawQuery.isEmpty()) {
            main.post(() -> {
                if (!closed && gen == generation.get()) {
                    listener.onSearchFinished(0, false);
                }
            });
            return;
        }
        try {
            executor.execute(() -> scan(gen, rawQuery, options, listener));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            postCancelled(listener);
        }
    }

    private void scan(int gen, String query, Options options, Listener listener) {
        ensureExtracted();
        if (gen != generation.get()) {
            postCancelled(listener);
            return;
        }
        final int n = document.getNumberOfPages();
        boolean reportedFirst = false;
        for (int page = 0; page < n; page++) {
            if (gen != generation.get()) {
                main.post(() -> listener.onSearchFinished(matchCount(), true));
                return;
            }
            PageText pt = pageTexts == null ? null : pageTexts.get(page);
            if (pt == null) {
                continue; // no extractable text on this page
            }
            List<Match> pageMatches = findOnPage(pt, page, query, options);
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
                    postCancelled(listener);
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

    private void postCancelled(Listener listener) {
        main.post(() -> {
            if (!closed) listener.onSearchFinished(matchCount(), true);
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
        PageText pt = pageTexts == null ? null : pageTexts.get(pageIndex);
        return pt == null ? null : new float[]{pt.pageWpts, pt.pageHpts};
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        generation.incrementAndGet();
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
            stripper = null; // use whatever pages were finalized before failure
        }
        pageTexts = (stripper != null) ? stripper.result : new java.util.HashMap<>();
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

    private static List<Match> findOnPage(PageText pt, int pageIndex, String query,
                                          Options opt) {
        List<Match> out = new ArrayList<>();
        if (pt.text.isEmpty()) {
            return out;
        }
        if (opt.regex) {
            try {
                int flags = opt.caseSensitive ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                Matcher m = Pattern.compile(query, flags).matcher(pt.text);
                while (m.find()) {
                    if (m.end() == m.start()) {
                        break; // empty match guard
                    }
                    addMatch(out, pt, pageIndex, m.start(), m.end(), opt);
                }
            } catch (PatternSyntaxException ignored) {
                // invalid regex while typing; treat as no matches
            }
            return out;
        }
        String hay = opt.caseSensitive ? pt.text : pt.text.toLowerCase(Locale.ROOT);
        String needle = opt.caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int at = hay.indexOf(needle, from);
            if (at < 0) {
                break;
            }
            addMatch(out, pt, pageIndex, at, at + needle.length(), opt);
            from = at + needle.length();
        }
        return out;
    }

    private static void addMatch(List<Match> out, PageText pt, int pageIndex,
                                 int start, int end, Options opt) {
        if (opt.wholeWord && !isWholeWord(pt.text, start, end)) {
            return;
        }
        out.add(new Match(pageIndex, start, end, mergeRun(pt.charRectsPts, start, end)));
    }

    private static boolean isWholeWord(String text, int start, int end) {
        boolean leftOk = start == 0 || !isWordChar(text.charAt(start - 1));
        boolean rightOk = end >= text.length() || !isWordChar(text.charAt(end));
        return leftOk && rightOk;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
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
