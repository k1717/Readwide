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
 * Highlights render in single-page (Matrix) mode; continuous scroll mode jumps
 * to the match page (highlight rendering there is a follow-up).
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

    private boolean active;
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
        this.filePath = filePath;
        this.pageCount = pageCount;
    }

    void setStatusListener(@Nullable StatusListener l) {
        this.statusListener = l;
    }

    /** True while the search dialog is open: enables highlights. */
    void setActive(boolean active) {
        this.active = active;
        if (!active) {
            main.removeCallbacks(searchRunnable);
            currentMatch = null;
            clearHighlights();
        }
    }

    /* ---- search ---- */

    /** Called by the dialog on every text change; debounced. */
    void startQuery(String query) {
        pendingQuery = query == null ? "" : query;
        main.removeCallbacks(searchRunnable);
        main.postDelayed(searchRunnable, 250);
    }

    private void runSearchNow() {
        final String q = pendingQuery;
        currentMatch = null;
        if (q.isEmpty()) {
            emitStatus();
            clearHighlights();
            return;
        }
        ensureEngine(() -> {
            if (engine == null) {
                emitStatus();
                return;
            }
            engine.startSearch(q, options, new PdfTextSearchEngine.Listener() {
                @Override
                public void onSearchProgress(int matchesSoFar, int scannedPages, int totalPages,
                                             @Nullable PdfTextSearchEngine.Match firstMatch) {
                    if (firstMatch != null && currentMatch == null) {
                        currentMatch = firstMatch;
                        if (firstMatch.pageIndex == host.currentPage()) {
                            refreshCurrentPageHighlights();
                        } else {
                            host.goToPage(firstMatch.pageIndex); // highlights on page shown
                        }
                    }
                    emitStatus();
                }

                @Override
                public void onSearchFinished(int totalMatches, boolean cancelled) {
                    if (!cancelled) {
                        emitStatus();
                    }
                }
            });
        });
    }

    void move(boolean forward) {
        if (engine == null) {
            return;
        }
        PdfTextSearchEngine.Match m = engine.moveTo(forward);
        if (m == null) {
            return;
        }
        currentMatch = m;
        if (m.pageIndex == host.currentPage()) {
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
        List<RectF> ptsRects = engine.matchesOnPage(pageIndex);
        List<RectF> norm = new ArrayList<>(ptsRects.size());
        for (RectF r : ptsRects) {
            norm.add(normalize(r, pageWpts, pageHpts));
        }
        RectF currentNorm = null;
        if (currentMatch != null && currentMatch.pageIndex == pageIndex
                && !currentMatch.rectsPts.isEmpty()) {
            currentNorm = normalize(currentMatch.rectsPts.get(0), pageWpts, pageHpts);
        }
        pv.setHighlights(norm, currentNorm);
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
        if (statusListener == null) {
            return;
        }
        int total = engine == null ? 0 : engine.total();
        int ord = engine == null ? 0 : engine.ordinal();
        statusListener.onStatus(ord, total);
    }

    /* ---- lazy document load ---- */

    private void ensureEngine(Runnable then) {
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
            PDDocument doc;
            try {
                doc = PDDocument.load(new File(path));
            } catch (Throwable t) {
                doc = null;
            }
            final PDDocument loaded = doc;
            host.runOnUi(() -> {
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
        main.removeCallbacks(searchRunnable);
        loader.shutdownNow();
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }
}
