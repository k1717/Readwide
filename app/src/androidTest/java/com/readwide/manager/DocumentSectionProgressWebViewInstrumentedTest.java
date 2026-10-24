package com.readwide.manager;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real WebView URL/geometry integration; no activity, external resource or saved preference is used. */
@RunWith(AndroidJUnit4.class)
public class DocumentSectionProgressWebViewInstrumentedTest {
    @Test public void nullHistoryInlineLoadShowsProgressAfterValidatedPageFinish() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Fixture> fixture = new AtomicReference<>();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Fixture f = new Fixture(done, failure);
                fixture.set(f);
                f.load();
            });
            assertTrue("Inline EPUB WebView did not finish with native scroll metrics",
                    done.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError(failure.get());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Fixture f = fixture.get();
                if (f != null) f.close();
            });
        }
    }

    private static final class Fixture {
        final TestActivity activity = new TestActivity();
        final Handler handler = new Handler(Looper.getMainLooper());
        final WebView web;
        final CountDownLatch done;
        final AtomicReference<Throwable> failure;
        final long deadline = SystemClock.uptimeMillis() + 10_000L;
        boolean closed;

        final Runnable awaitNativeMetrics = new Runnable() {
            @Override public void run() {
                if (closed || done.getCount() == 0L) return;
                try {
                    if (!hasScrollableNativeMetrics()) {
                        if (SystemClock.uptimeMillis() >= deadline) {
                            throw new AssertionError("Loaded tall HTML did not expose native scroll geometry");
                        }
                        // onPageFinished can precede the renderer's native size update.
                        handler.postDelayed(this, 16L);
                        return;
                    }
                    String visibleUrl = web.getUrl();
                    assertTrue("Unexpected visible URL: " + visibleUrl,
                            "about:blank".equals(visibleUrl)
                                    || activity.isCurrentDocumentPageLoad(web, visibleUrl));
                    assertTrue(activity.canSaveDocumentReadingState());
                    assertEquals(0, activity.currentEpubSectionProgressPercent());
                    activity.refreshEpubSectionProgressViews();
                    String expected = String.format(Locale.getDefault(), "%d (%d%%) / %d", 1, 0, 1);
                    assertEquals(expected, activity.pageStatus.getText().toString());
                    assertEquals(expected, activity.topPageStatus.getText().toString());
                    assertEquals(0, activity.currentPage);
                    assertEquals(1, activity.documentPageCount());
                    assertEquals(0, web.getScrollY());
                    assertEquals("anchor-not-modified", activity.lastDocumentContentAnchorJson);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
                done.countDown();
            }
        };

        Fixture(CountDownLatch done, AtomicReference<Throwable> failure) {
            this.done = done;
            this.failure = failure;
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            web = new WebView(context);
            activity.webView = web;
            activity.docType = "EPUB";
            activity.pages.add(new DocumentPageActivity.Page("Section", "", "section.xhtml"));
            activity.currentPage = 0;
            activity.documentLoadAccepted = true;
            activity.primaryDocumentPageLoadGeneration = 7;
            activity.primaryDocumentPageLoadPage = 0;
            activity.primaryDocumentPageReadyGeneration = -1;
            activity.lastDocumentContentAnchorJson = "anchor-not-modified";
            activity.pageStatus = new TextView(context);
            activity.topPageStatus = new TextView(context);
        }

        void load() {
            web.getSettings().setJavaScriptEnabled(false);
            web.getSettings().setAllowFileAccess(false);
            web.getSettings().setAllowContentAccess(false);
            web.getSettings().setBlockNetworkLoads(true);
            web.getSettings().setBlockNetworkImage(true);
            web.getSettings().setUseWideViewPort(false);
            web.getSettings().setLoadWithOverviewMode(false);
            web.getSettings().setTextZoom(100);
            web.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
            web.layout(0, 0, 600, 900);
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) {
                    if (closed || done.getCount() == 0L) return;
                    try {
                        // Use the callback URL for the same generation check as production,
                        // not getUrl(), whose history entry may be about:blank.
                        assertTrue("Finished URL did not match the current load: " + url,
                                activity.isCurrentDocumentPageLoad(view, url));
                        activity.primaryDocumentPageReadyGeneration =
                                activity.primaryDocumentPageLoadGeneration;
                        handler.removeCallbacks(awaitNativeMetrics);
                        awaitNativeMetrics.run();
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        done.countDown();
                    }
                }
            });
            String html = "<!doctype html><html><head>"
                    + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                    + "<style>html,body{margin:0;padding:0}main{height:6000px}</style>"
                    + "</head><body><main>Controlled tall EPUB section</main></body></html>";
            web.loadDataWithBaseURL("https://readwide.invalid/epub/?rw_load=7",
                    html, "text/html", "UTF-8", null);
        }

        @SuppressWarnings("deprecation")
        boolean hasScrollableNativeMetrics() {
            // Observe the real renderer, including its actual initial display scale.
            return web.getWidth() > 0 && web.getHeight() > 0 && web.getContentHeight() > 0
                    && web.getContentHeight() * (double) web.getScale() > web.getHeight();
        }

        void close() {
            closed = true;
            activity.activityDestroyed = true;
            handler.removeCallbacks(awaitNativeMetrics);
            web.stopLoading();
            web.setWebViewClient(new WebViewClient());
            web.destroy();
            activity.webView = null;
            activity.executor.shutdownNow();
        }
    }

    private static final class TestActivity extends DocumentPageActivity {
        @Override public Resources getResources() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext().getResources();
        }
        @Override boolean isLandscapeTwoPageDocumentMode() { return false; }
    }
}
