package com.readwide.manager;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.readwide.manager.util.PrefsManager;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Source regressions for late DOM captures; no JavaScript rendering is simulated. */
@RunWith(AndroidJUnit4.class)
public class DocumentAnchorCaptureOwnershipInstrumentedTest {
    private static final String PREVIOUS = "previous-anchor";
    private static final String CAPTURE = "{\"anchorMode\":\"block\",\"blockIndex\":4,"
            + "\"scrollY\":150,\"maxScrollY\":900,\"text\":\"Stable sentence\"}";
    private static final String VERTICAL_POSITION = "{\"anchorMode\":\"vertical-position\","
            + "\"writingMode\":\"vertical-rl\",\"scrollX\":-60,\"scrollY\":150,"
            + "\"maxScrollX\":900,\"maxScrollY\":900}";

    @Test public void stableViewportAcceptsItsCaptureAndCompletesOnce() {
        withFixture(f -> {
            f.startCapture();
            assertEquals(PREVIOUS, f.activity.lastDocumentContentAnchorJson);
            f.finishCapture();
            JSONObject saved = json(f.activity.lastDocumentContentAnchorJson);
            assertEquals("EPUB_CONTENT_ANCHOR_v1", saved.optString("kind"));
            assertEquals(4, saved.optInt("blockIndex"));
            assertEquals(150, saved.optInt("scrollY"));
            assertEquals("Stable sentence", saved.optString("text"));
            assertEquals(1, f.completions);
        });
    }

    @Test public void horizontalFlingWithoutAnotherInteractionRejectsEarlierCapture() {
        withFixture(f -> {
            f.startCapture();
            int interaction = f.activity.documentInteractionGeneration;
            f.view.scrollTo(40, 150);
            assertEquals(40, f.view.getScrollX());
            assertEquals(interaction, f.activity.documentInteractionGeneration);
            f.finishCapture();
            f.assertRejectedWithoutReplacingCache();
        });
    }

    @Test public void verticalFlingWithoutAnotherInteractionRejectsEarlierCapture() {
        withFixture(f -> {
            f.startCapture();
            int interaction = f.activity.documentInteractionGeneration;
            f.view.scrollTo(20, 240);
            assertEquals(240, f.view.getScrollY());
            assertEquals(interaction, f.activity.documentInteractionGeneration);
            f.finishCapture();
            f.assertRejectedWithoutReplacingCache();
        });
    }

    @Test public void widthChangeRejectsEarlierCaptureEvenAtTheSameScrollOffset() {
        withFixture(f -> {
            f.startCapture();
            f.view.layout(0, 0, 640, 900);
            assertEquals(20, f.view.getScrollX());
            assertEquals(150, f.view.getScrollY());
            f.finishCapture();
            f.assertRejectedWithoutReplacingCache();
        });
    }

    @Test public void heightChangeRejectsEarlierCaptureEvenAtTheSameScrollOffset() {
        withFixture(f -> {
            f.startCapture();
            f.view.layout(0, 0, 600, 940);
            assertEquals(20, f.view.getScrollX());
            assertEquals(150, f.view.getScrollY());
            f.finishCapture();
            f.assertRejectedWithoutReplacingCache();
        });
    }

    @Test public void explicitInteractionInvalidatesCaptureEvenWithUnchangedViewport() {
        withFixture(f -> {
            f.startCapture();
            f.activity.documentInteractionGeneration++;
            f.finishCapture();
            f.assertRejectedWithoutReplacingCache();
        });
    }

    @Test public void settledViewportCanReplaceTheRejectedCaptureWithANewOne() {
        withFixture(f -> {
            f.startCapture();
            f.view.scrollTo(20, 240);
            f.finishCapture();
            f.assertRejectedWithoutReplacingCache();
            f.startCapture();
            f.activity.complete(CAPTURE.replace("\"scrollY\":150", "\"scrollY\":240"));
            assertEquals(240, json(f.activity.lastDocumentContentAnchorJson).optInt("scrollY"));
            assertEquals(2, f.completions);
        });
    }

    @Test public void continuedFlingRetriesSaveOnceWithoutPublishingSectionStart() {
        withFixture(f -> withSavePreferences(f, () -> {
            // Deliberately no BookmarkManager: an unintended durable write must
            // fail instead of silently passing this control-flow regression.
            assertNull(f.activity.bookmarkManager);
            f.activity.saveReadingState();
            assertNotNull(f.activity.pending);
            f.view.scrollTo(20, 240);
            f.finishCapture();
            assertEquals(2, f.activity.captureRequests);
            assertNotNull(f.activity.pending);
            f.view.scrollTo(20, 320);
            f.activity.complete(CAPTURE.replace("\"scrollY\":150", "\"scrollY\":240"));
            assertEquals(2, f.activity.captureRequests);
            assertNull(f.activity.pending);
            assertEquals(PREVIOUS, f.activity.lastDocumentContentAnchorJson);
        }));
    }

    @Test public void repeatedResizeRetriesSaveOnceWithoutPublishingSectionStart() {
        withFixture(f -> withSavePreferences(f, () -> {
            assertNull(f.activity.bookmarkManager);
            f.activity.saveReadingState();
            f.view.layout(0, 0, 640, 900);
            f.finishCapture();
            assertEquals(2, f.activity.captureRequests);
            assertNotNull(f.activity.pending);
            f.view.layout(0, 0, 680, 900);
            f.finishCapture();
            assertEquals(2, f.activity.captureRequests);
            assertNull(f.activity.pending);
            assertEquals(PREVIOUS, f.activity.lastDocumentContentAnchorJson);
        }));
    }

    @Test public void motionDuringBookmarkRetryPauseDiscardsTheEarlierPositionFallback() {
        withFixture(f -> {
            prepareVerticalBookmarkRetry(f);
            int interaction = f.activity.documentInteractionGeneration;
            f.view.scrollTo(60, 150);
            assertEquals(interaction, f.activity.documentInteractionGeneration);
            f.view.runNextDelayed();
            assertEquals(2, f.activity.captureRequests);
            assertNotNull(f.activity.pending);
            f.activity.complete("null");
            assertEquals(0, f.activity.bookmarkPublications);
            assertEquals("", f.activity.lastDocumentContentAnchorJson);
            assertEquals(1, f.completions);
        });
    }

    @Test public void explicitInteractionDuringBookmarkRetryPauseCancelsTheRequest() {
        withFixture(f -> {
            prepareVerticalBookmarkRetry(f);
            f.activity.documentInteractionGeneration++;
            f.view.runNextDelayed();
            assertEquals(1, f.activity.captureRequests);
            assertNull(f.activity.pending);
            assertEquals(0, f.activity.bookmarkPublications);
            assertEquals(1, f.completions);
        });
    }

    @Test public void domOnlyMovementDuringBookmarkRetryDiscardsTheEarlierPositionFallback() {
        withFixture(f -> {
            prepareVerticalBookmarkRetry(f);
            f.activity.invalidateDocumentContentPositionCapture();
            f.view.runNextDelayed();
            assertEquals(2, f.activity.captureRequests);
            assertNotNull(f.activity.pending);
            f.activity.complete("null");
            assertEquals(0, f.activity.bookmarkPublications);
            assertEquals("", f.activity.lastDocumentContentAnchorJson);
            assertEquals(1, f.completions);
        });
    }

    private static void prepareVerticalBookmarkRetry(Fixture f) {
        f.activity.pages.set(0, new DocumentPageActivity.Page("Vertical", "", "OPS/text.xhtml", false, false, true));
        f.activity.filePath = "/readwide-test-only/bookmark.epub";
        f.view.captureDelayed = true;
        f.activity.addBookmarkForCurrentPage(() -> f.completions++);
        assertNotNull(f.activity.pending);
        f.activity.complete(VERTICAL_POSITION);
        assertNull(f.activity.pending);
        assertEquals(1, f.activity.captureRequests);
        assertEquals(1, f.view.delayed.size());
        assertEquals(0, f.activity.bookmarkPublications);
        assertEquals(0, f.completions);
    }

    private static void withSavePreferences(Fixture f, Runnable check) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "document-anchor-capture-" + UUID.randomUUID();
        SharedPreferences store = context.getSharedPreferences(name, Context.MODE_PRIVATE);
        try {
            Context isolated = new ContextWrapper(context) {
                @Override public Context getApplicationContext() { return this; }
                @Override public SharedPreferences getSharedPreferences(String ignored, int mode) { return store; }
            };
            Constructor<PrefsManager> constructor = PrefsManager.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            f.activity.prefs = constructor.newInstance(isolated);
            f.activity.prefs.setAutoSavePosition(true);
            f.activity.filePath = "/readwide-test-only/capture.epub";
            f.activity.documentLoadAccepted = true;
            f.activity.primaryDocumentPageLoadGeneration = 7;
            f.activity.primaryDocumentPageReadyGeneration = 7;
            f.activity.primaryDocumentPageLoadPage = 0;
            check.run();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        } finally {
            f.activity.prefs = null;
            f.activity.filePath = null;
            assertTrue(context.deleteSharedPreferences(name));
        }
    }

    private static JSONObject json(String text) {
        try {
            return new JSONObject(text);
        } catch (JSONException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void withFixture(Consumer<Fixture> check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture();
            try {
                check.accept(f);
            } finally {
                f.activity.activityDestroyed = true;
                f.activity.pending = null;
                f.activity.webView = null;
                f.view.captureDelayed = false;
                f.view.delayed.clear();
                f.view.destroy();
                f.activity.executor.shutdownNow();
            }
        });
    }

    private static final class Fixture {
        final TestActivity activity = new TestActivity();
        final TestWebView view;
        int completions;

        Fixture() {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            view = new TestWebView(context);
            view.layout(0, 0, 600, 900);
            view.scrollTo(20, 150);
            assertEquals(600, view.getWidth());
            assertEquals(900, view.getHeight());
            assertEquals(20, view.getScrollX());
            assertEquals(150, view.getScrollY());
            activity.webView = view;
            activity.docType = "EPUB";
            activity.currentPage = 0;
            activity.documentLoadAccepted = true;
            activity.primaryDocumentPageLoadGeneration = 7;
            activity.primaryDocumentPageReadyGeneration = 7;
            activity.primaryDocumentPageLoadPage = 0;
            activity.documentAnchorPageGeneration = 7;
            activity.documentInteractionGeneration = 11;
            activity.lastDocumentContentAnchorJson = PREVIOUS;
            activity.pages.add(new DocumentPageActivity.Page("Text", "", "OPS/text.xhtml"));
        }

        void startCapture() {
            assertNull(activity.pending);
            activity.updateDocumentContentAnchorFromWebView(() -> completions++);
            assertNotNull(activity.pending);
        }

        void finishCapture() { activity.complete(CAPTURE); }

        void assertRejectedWithoutReplacingCache() {
            assertEquals(PREVIOUS, activity.lastDocumentContentAnchorJson);
            assertEquals(1, completions);
        }
    }

    private static final class TestActivity extends DocumentPageActivity {
        ValueCallback<String> pending;
        int captureRequests;
        int bookmarkPublications;

        @Override public android.content.res.Resources getResources() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext().getResources();
        }

        @Override public Context getApplicationContext() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        }

        @Override void addRenderedDocumentBookmarkFromCurrentAnchor() { bookmarkPublications++; }

        @Override void evaluateDocumentAnchorJavascript(String javascript, ValueCallback<String> callback) {
            assertNull(pending);
            captureRequests++;
            pending = callback;
        }

        void complete(String value) {
            ValueCallback<String> callback = pending;
            assertNotNull(callback);
            pending = null;
            callback.onReceiveValue(value);
        }
    }

    private static final class TestWebView extends WebView {
        final List<Runnable> delayed = new ArrayList<>();
        boolean captureDelayed;

        TestWebView(Context context) { super(context); }

        // Keep real View scroll/frame fields, but do not let an empty Chromium
        // document clamp them or schedule renderer callbacks in this fixture.
        @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) { }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) { }

        @Override public boolean postDelayed(Runnable action, long delayMillis) {
            if (!captureDelayed) return super.postDelayed(action, delayMillis);
            delayed.add(action);
            return true;
        }

        void runNextDelayed() {
            assertFalse(delayed.isEmpty());
            delayed.remove(0).run();
        }
    }
}
