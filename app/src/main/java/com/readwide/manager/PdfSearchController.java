package com.readwide.manager;

import android.content.Context;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives in-document Find for the PDF reader.
 *
 * The search UI itself is a stably-positioned dialog built by the activity
 * (showPdfSearchDialog, mirroring showGoToPageDialog) so the page and its
 * highlights stay visible. This controller owns the engine, the lazy PDDocument
 * load, the point-to-normalized rectangle conversion, and match navigation; the
 * dialog calls startQuery / move and receives status through a StatusListener.
 *
 * Lazy and cheap until used: the PDDocument loads in the background only on the
 * first query, so opening a PDF costs nothing extra unless the user searches.
 * Highlights render in single-page and landscape two-page Matrix modes;
 * continuous scroll mode jumps to the match page (highlight rendering there is
 * a follow-up).
 *
 * Unbuilt reference (no Android SDK in the authoring environment); build and
 * test in your own build.
 */
final class PdfSearchController {

    /** Minimal hooks the controller needs from the activity. */
    interface Host {
        void goToPage(int pageIndex);
        int currentPage();
        @Nullable PdfPageView pageView();
        void runOnUi(Runnable r);
        /** Right page in the committed spread bitmap, or -1 for a single page. */
        int visibleRightPageIndex();
        /** True for a page in the committed or currently rendering display. */
        boolean isPagePartOfCurrentDisplay(int pageIndex);
        /** Maps one page's normalized rectangle into the displayed bitmap. */
        @Nullable RectF mapPageRectToDisplayedBitmap(int pageIndex, RectF pageNormalizedRect);
    }

    /** Status updates for the dialog, delivered on the main thread. */
    interface StatusListener {
        void onStatus(int ordinal, int total);
    }

    private final Context appContext;
    private final Host host;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PdfTextSearchEngine.Options options = new PdfTextSearchEngine.Options();

    @Nullable private String filePath;
    private int pageCount;

    @Nullable private PdfTextSearchEngine engine;
    private boolean loadingDoc;
    private boolean loadFailed;
    /** Invalidates a lazy PDFBox load that outlives a singleTop document swap. */
    private volatile boolean closed;

    private boolean active;
    /** Rejects callbacks from a replaced query or a dismissed/reopened dialog. */
    private int queryGeneration;
    @Nullable private StatusListener statusListener;
    @Nullable private PdfTextSearchEngine.Match currentMatch;
    private String pendingQuery = "";

    private final Runnable searchRunnable = this::runSearchNow;

    PdfSearchController(Context context, Host host) {
        this.appContext = context.getApplicationContext();
        this.host = host;
        try {
            PDFBoxResourceLoader.init(appContext);
        } catch (Throwable ignored) {
        }
    }

    /** Called once the page count is known (after PdfRenderer opens the file). */
    void setSource(@Nullable String filePath, int pageCount) {
        if (closed) return;
        this.filePath = filePath;
        this.pageCount = pageCount;
    }

    void setStatusListener(@Nullable StatusListener l) {
        this.statusListener = l;
    }

    /** True while the search dialog is open: enables highlights. */
    void setActive(boolean active) {
        if (closed && active) return;
        this.active = active;
        if (!active) {
            queryGeneration++;
            main.removeCallbacks(searchRunnable);
            currentMatch = null;
            clearHighlights();
        }
    }

    /* ---- search ---- */

    /** Called by the dialog on every text change; debounced. */
    void startQuery(String query) {
        if (closed) return;
        queryGeneration++;
        pendingQuery = query == null ? "" : query;
        main.removeCallbacks(searchRunnable);
        main.postDelayed(searchRunnable, 250);
    }

    private void runSearchNow() {
        if (closed || !active) return;
        final int callbackGeneration = queryGeneration;
        final String q = pendingQuery;
        currentMatch = null;
        clearHighlights();
        if (q.isEmpty()) {
            emitStatus();
            return;
        }
        ensureEngine(() -> {
            if (closed || !active || callbackGeneration != queryGeneration) return;
            if (engine == null) {
                emitStatus();
                return;
            }
            engine.startSearch(q, options, new PdfTextSearchEngine.Listener() {
                @Override
                public void onSearchProgress(int matchesSoFar, int scannedPages, int totalPages,
                                             @Nullable PdfTextSearchEngine.Match firstMatch) {
                    if (closed || !active || callbackGeneration != queryGeneration) return;
                    if (firstMatch != null && currentMatch == null) {
                        currentMatch = firstMatch;
                        if (isPageVisible(firstMatch.pageIndex)) {
                            refreshCurrentPageHighlights();
                        } else {
                            host.goToPage(firstMatch.pageIndex); // highlights on page shown
                        }
                    } else if (currentMatch != null && isPageVisible(currentMatch.pageIndex)) {
                        // A later progress batch may have added hits on the other
                        // half of the visible spread. Refresh both halves together.
                        refreshCurrentPageHighlights();
                    }
                    emitStatus();
                }

                @Override
                public void onSearchFinished(int totalMatches, boolean cancelled) {
                    if (closed || !active || callbackGeneration != queryGeneration) return;
                    if (!cancelled) {
                        if (currentMatch != null && isPageVisible(currentMatch.pageIndex)) {
                            refreshCurrentPageHighlights();
                        }
                        emitStatus();
                    }
                }
            });
        });
    }

    void move(boolean forward) {
        if (closed || !active || engine == null) {
            return;
        }
        PdfTextSearchEngine.Match m = engine.moveTo(forward);
        if (m == null) {
            return;
        }
        currentMatch = m;
        if (isPageVisible(m.pageIndex)) {
            refreshCurrentPageHighlights();
        } else {
            host.goToPage(m.pageIndex); // highlights applied when the page is shown
        }
        emitStatus();
    }

    /* ---- highlight plumbing ---- */

    /**
     * Host calls this when a page finishes rendering (the setFitBitmap site),
     * passing the rendered page point size. This is the reliable highlight path.
     */
    void onPageShown(int pageIndex, int pageWpts, int pageHpts) {
        if (closed) return;
        applyHighlights(pageIndex, pageWpts, pageHpts);
    }

    private void refreshCurrentPageHighlights() {
        if (engine == null) {
            return;
        }
        int page = host.currentPage();
        float[] size = engine.pageSizePts(page);
        if (size != null) {
            applyHighlights(page, (int) size[0], (int) size[1]);
        }
    }

    private void applyHighlights(int pageIndex, int pageWpts, int pageHpts) {
        PdfPageView pv = host.pageView();
        if (pv == null || engine == null || !active || pageWpts <= 0 || pageHpts <= 0) {
            return;
        }
        List<RectF> displayed = new ArrayList<>();
        RectF[] currentDisplayed = {null};
        // In spread mode the renderer metadata passed by the host describes the
        // whole composite (left width + right width), not the left source page.
        // Prefer PdfBox's exact per-page size whenever extraction has it.
        float[] pageSize = engine.pageSizePts(pageIndex);
        float sourceWidth = pageSize != null ? pageSize[0] : pageWpts;
        float sourceHeight = pageSize != null ? pageSize[1] : pageHpts;
        appendPageHighlights(pageIndex, sourceWidth, sourceHeight,
                displayed, currentDisplayed);

        int rightPage = host.visibleRightPageIndex();
        if (rightPage >= 0 && rightPage != pageIndex) {
            float[] rightSize = engine.pageSizePts(rightPage);
            if (rightSize != null && rightSize[0] > 0f && rightSize[1] > 0f) {
                appendPageHighlights(rightPage, rightSize[0], rightSize[1],
                        displayed, currentDisplayed);
            }
        }
        pv.setHighlights(displayed, currentDisplayed[0]);
    }

    /** Append one visible source page after projecting it into bitmap space. */
    private void appendPageHighlights(int pageIndex, float pageWpts, float pageHpts,
                                      List<RectF> displayed, RectF[] currentDisplayed) {
        List<RectF> ptsRects = engine.matchesOnPage(pageIndex);
        RectF currentFirst = currentMatch != null && currentMatch.pageIndex == pageIndex
                && !currentMatch.rectsPts.isEmpty()
                ? currentMatch.rectsPts.get(0) : null;
        for (RectF r : ptsRects) {
            RectF mapped = host.mapPageRectToDisplayedBitmap(
                    pageIndex, normalize(r, pageWpts, pageHpts));
            if (mapped == null) continue;
            displayed.add(mapped);
            // matchesOnPage returns the engine's rectangle objects, so preserve
            // identity for PdfPageView's "draw current once" fast path.
            if (r == currentFirst) currentDisplayed[0] = mapped;
        }
        // Defensive fallback in case the engine later changes matchesOnPage to
        // return copies rather than its stored rectangle instances.
        if (currentFirst != null && currentDisplayed[0] == null) {
            currentDisplayed[0] = host.mapPageRectToDisplayedBitmap(
                    pageIndex, normalize(currentFirst, pageWpts, pageHpts));
        }
    }

    private boolean isPageVisible(int pageIndex) {
        return host.isPagePartOfCurrentDisplay(pageIndex);
    }

    private static RectF normalize(RectF ptsRect, float wPts, float hPts) {
        return new RectF(ptsRect.left / wPts, ptsRect.top / hPts,
                ptsRect.right / wPts, ptsRect.bottom / hPts);
    }

    private void clearHighlights() {
        PdfPageView pv = host.pageView();
        if (pv != null) {
            pv.clearHighlights();
        }
    }

    /* ---- status ---- */

    private void emitStatus() {
        if (closed || statusListener == null) {
            return;
        }
        int total = engine == null ? 0 : engine.total();
        int ord = engine == null ? 0 : engine.ordinal();
        statusListener.onStatus(ord, total);
    }

    /* ---- lazy document load ---- */

    private void ensureEngine(Runnable then) {
        if (closed) return;
        if (engine != null) {
            then.run();
            return;
        }
        if (loadFailed || filePath == null) {
            then.run();
            return;
        }
        if (loadingDoc) {
            main.postDelayed(() -> ensureEngine(then), 120); // retry once load settles
            return;
        }
        loadingDoc = true;
        final String path = filePath;
        final int count = pageCount;
        loader.execute(() -> {
            if (closed) return;
            PDDocument doc;
            try {
                doc = PDDocument.load(new File(path));
            } catch (Throwable t) {
                doc = null;
            }
            final PDDocument loaded = doc;
            host.runOnUi(() -> {
                if (closed) {
                    closeDocumentQuietly(loaded);
                    return;
                }
                loadingDoc = false;
                if (loaded == null) {
                    loadFailed = true;
                } else {
                    engine = new PdfTextSearchEngine(loaded, count);
                }
                then.run();
            });
        });
    }

    void close() {
        if (closed) return;
        closed = true;
        active = false;
        queryGeneration++;
        statusListener = null;
        currentMatch = null;
        pendingQuery = "";
        main.removeCallbacks(searchRunnable);
        loader.shutdownNow();
        clearHighlights();
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    private static void closeDocumentQuietly(@Nullable PDDocument document) {
        if (document == null) return;
        try {
            document.close();
        } catch (Throwable ignored) {
        }
    }
}
