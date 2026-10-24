package com.readwide.manager;

import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Regression sources only; run explicitly on a device. */
@RunWith(AndroidJUnit4.class)
public class PdfIncrementalSearchInstrumentedTest {
    @Test public void largeDocumentReportsFirstPageAndDoesNotRetainGlyphMap() throws Exception {
        checkSearch(9, false);
    }

    @Test public void smallDocumentRetainsCompleteCacheForRepeatedQueries() throws Exception {
        checkSearch(3, true);
    }

    @Test public void extractionSetupFailureNotifiesFailureInsteadOfCompletion() throws Exception {
        checkExtractionFailure(0);
    }

    @Test public void extractionMemoryFailureNotifiesFailureInsteadOfEscapingWorker() throws Exception {
        checkExtractionFailure(1);
    }

    @Test public void extractionNestingFailureNotifiesFailureInsteadOfEscapingWorker() throws Exception {
        checkExtractionFailure(2);
    }

    private void checkExtractionFailure(int failureKind) throws Exception {
        PDFBoxResourceLoader.init(InstrumentationRegistry.getInstrumentation().getTargetContext());
        PDDocument broken = new PDDocument() {
            @Override public int getNumberOfPages() {
                if (failureKind == 1) throw new OutOfMemoryError("injected page tree allocation");
                if (failureKind == 2) throw new StackOverflowError("injected page tree depth");
                throw new IllegalStateException("broken page tree");
            }
        };
        PdfTextSearchEngine engine = new PdfTextSearchEngine(broken, 1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        try {
            engine.startSearch("needle", new PdfTextSearchEngine.Options(), new PdfTextSearchEngine.Listener() {
                @Override public void onSearchProgress(int count, int scanned, int total, PdfTextSearchEngine.Match hit) { }
                @Override public void onSearchFinished(int count, boolean cancelled) {
                    completions.incrementAndGet(); done.countDown();
                }
                @Override public void onSearchFailed() { failures.incrementAndGet(); done.countDown(); }
            });
            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertEquals(1, failures.get());
            assertEquals(0, completions.get());
            assertEquals(0, engine.total());
            assertNull(engine.current());
        } finally { engine.close(); }
    }

    @Test public void incompleteRegexStillCompletesWithoutReadingTheDocument() throws Exception {
        PDFBoxResourceLoader.init(InstrumentationRegistry.getInstrumentation().getTargetContext());
        PDDocument unread = new PDDocument() {
            @Override public int getNumberOfPages() { throw new AssertionError("Invalid query must not scan"); }
        };
        PdfTextSearchEngine engine = new PdfTextSearchEngine(unread, 1);
        PdfTextSearchEngine.Options options = new PdfTextSearchEngine.Options();
        options.regex = true;
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        try {
            engine.startSearch("[", options, new PdfTextSearchEngine.Listener() {
                @Override public void onSearchProgress(int count, int scanned, int total, PdfTextSearchEngine.Match hit) { }
                @Override public void onSearchFinished(int count, boolean cancelled) {
                    if (!cancelled && count == 0) completions.incrementAndGet();
                    done.countDown();
                }
                @Override public void onSearchFailed() { failures.incrementAndGet(); done.countDown(); }
            });
            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertEquals(1, completions.get());
            assertEquals(0, failures.get());
        } finally { engine.close(); }
    }

    @Test public void oldWorkerFailureCannotClearReplacementSearch() throws Exception {
        PDFBoxResourceLoader.init(InstrumentationRegistry.getInstrumentation().getTargetContext());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PDDocument broken = new PDDocument() {
            @Override public int getNumberOfPages() {
                entered.countDown();
                try { assertTrue(release.await(30, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                throw new OutOfMemoryError("injected superseded scan failure");
            }
        };
        PdfTextSearchEngine engine = new PdfTextSearchEngine(broken, 1);
        AtomicInteger oldEvents = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger newCompletions = new AtomicInteger();
        try {
            engine.startSearch("old", new PdfTextSearchEngine.Options(), new PdfTextSearchEngine.Listener() {
                @Override public void onSearchProgress(int count, int scanned, int total, PdfTextSearchEngine.Match hit) { oldEvents.incrementAndGet(); }
                @Override public void onSearchFinished(int count, boolean cancelled) { oldEvents.incrementAndGet(); }
                @Override public void onSearchFailed() { oldEvents.incrementAndGet(); }
            });
            assertTrue(entered.await(30, TimeUnit.SECONDS));
            PdfTextSearchEngine.Options options = new PdfTextSearchEngine.Options();
            options.regex = true;
            engine.startSearch("[", options, new PdfTextSearchEngine.Listener() {
                @Override public void onSearchProgress(int count, int scanned, int total, PdfTextSearchEngine.Match hit) { }
                @Override public void onSearchFinished(int count, boolean cancelled) {
                    if (!cancelled && count == 0) newCompletions.incrementAndGet();
                    done.countDown();
                }
                @Override public void onSearchFailed() { done.countDown(); }
            });
            release.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertEquals(0, oldEvents.get());
            assertEquals(1, newCompletions.get());
        } finally { release.countDown(); engine.close(); }
    }

    @Test public void unclaimedPdfDocumentClosesOffMainThread() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PDFBoxResourceLoader.init(context);
        CountDownLatch retired = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean closedOnMain = new java.util.concurrent.atomic.AtomicBoolean();
        PDDocument pending = new PDDocument() {
            @Override public void close() throws java.io.IOException {
                closedOnMain.set(android.os.Looper.myLooper() == android.os.Looper.getMainLooper());
                try { super.close(); } finally { retired.countDown(); }
            }
        };
        PdfSearchController controller = new PdfSearchController(context, new PdfSearchController.Host() {
            @Override public void goToPage(int page) { }
            @Override public int currentPage() { return 0; }
            @Override public PdfPageView pageView() { return null; }
            @Override public void runOnUi(Runnable r) { new android.os.Handler(android.os.Looper.getMainLooper()).post(r); }
            @Override public int visibleRightPageIndex() { return -1; }
            @Override public boolean isPagePartOfCurrentDisplay(int page) { return page == 0; }
            @Override public android.graphics.RectF mapPageRectToDisplayedBitmap(int page, android.graphics.RectF rect) { return rect; }
        });
        java.lang.reflect.Field field = PdfSearchController.class.getDeclaredField("pendingDocument");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<PDDocument> handoff =
                (java.util.concurrent.atomic.AtomicReference<PDDocument>) field.get(controller);
        handoff.set(pending);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(controller::close);
        assertTrue(retired.await(30, TimeUnit.SECONDS));
        assertFalse(closedOnMain.get());
        assertNull(handoff.get());
    }

    private void checkSearch(int pages, boolean cached) throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PDFBoxResourceLoader.init(context);
        File file = File.createTempFile("pdf-search-", ".pdf", context.getCacheDir());
        PdfTextSearchEngine engine = null;
        try {
            PdfDocument pdf = new PdfDocument();
            try {
                Paint paint = new Paint();
                paint.setTextSize(18);
                for (int i = 0; i < pages; i++) {
                    PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(240, 320, i + 1).create());
                    page.getCanvas().drawText("needle", 20, 40, paint);
                    pdf.finishPage(page);
                }
                try (FileOutputStream output = new FileOutputStream(file)) { pdf.writeTo(output); }
            } finally { pdf.close(); }
            engine = new PdfTextSearchEngine(PDDocument.load(file), pages);
            searchAndWait(engine, "needle", pages, 1);
            assertEquals(pages, engine.total());
            assertNotNull(engine.pageSizePts(pages - 1));
            assertFalse(engine.matchesOnPage(pages - 1).isEmpty());
            java.lang.reflect.Field cache = PdfTextSearchEngine.class.getDeclaredField("pageTexts");
            cache.setAccessible(true);
            assertEquals(cached, cache.get(engine) != null);
            searchAndWait(engine, "absent", 0, -1);
            assertEquals(0, engine.total());
            searchAndWait(engine, "needle", pages, 1);
        } finally {
            if (engine != null) engine.close();
            // Engine owns the open PDF and retires it on its worker.
            // Android permits removing the directory entry while that handle closes.
            assertTrue(file.delete());
        }
    }

    private void searchAndWait(PdfTextSearchEngine engine, String query, int expected, int firstPage)
            throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger found = new AtomicInteger(-1);
        AtomicInteger first = new AtomicInteger(-1);
        engine.startSearch(query, new PdfTextSearchEngine.Options(), new PdfTextSearchEngine.Listener() {
            @Override public void onSearchProgress(int count, int scanned, int total, PdfTextSearchEngine.Match hit) {
                if (hit != null) first.compareAndSet(-1, scanned);
            }
            @Override public void onSearchFinished(int total, boolean cancelled) {
                found.set(cancelled ? -2 : total);
                done.countDown();
            }
        });
        assertTrue("Search timeout", done.await(30, TimeUnit.SECONDS));
        assertEquals(expected, found.get());
        assertEquals(firstPage, first.get());
    }
}
