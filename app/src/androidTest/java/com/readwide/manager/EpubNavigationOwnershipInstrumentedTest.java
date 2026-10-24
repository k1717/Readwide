package com.readwide.manager;

import android.content.Context;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Source regressions for navigation ownership; requires Android, not a JVM WebView stub. */
@RunWith(AndroidJUnit4.class)
public class EpubNavigationOwnershipInstrumentedTest {
    @Test public void loadingPrimaryTargetWaitsForItsCurrentReadyGeneration() {
        withFixture(f -> {
            f.activity.primaryDocumentPageReadyGeneration = 6;
            assertTrue(f.navigate(0, "chapter"));
            assertTrue(f.left.scripts.isEmpty());
            assertFalse(f.activity.canSaveDocumentReadingState());
            f.activity.applyPendingEpubAnchorAfterPageLoad(f.left);
            assertTrue(f.left.scripts.isEmpty());
            f.activity.primaryDocumentPageReadyGeneration = 7;
            f.activity.applyPendingEpubAnchorAfterPageLoad(f.left);
            assertEquals(1, f.left.scripts.size());
            assertTrue(f.left.scripts.get(0).contains("\"chapter\""));
            f.activity.applyPendingEpubAnchorAfterPageLoad(f.left);
            assertEquals(1, f.left.scripts.size());
            assertTrue(f.activity.canSaveDocumentReadingState());
        });
    }

    @Test public void loadingRightTargetCannotBeConsumedByPrimaryCompletion() {
        withFixture(f -> {
            f.activity.rightDocumentPageReadyGeneration = 8;
            assertTrue(f.navigate(1, "right-note"));
            assertFalse(f.activity.canSaveDocumentReadingState());
            f.activity.applyPendingEpubAnchorAfterPageLoad(f.left);
            assertTrue(f.left.scripts.isEmpty());
            assertTrue(f.right.scripts.isEmpty());
            f.activity.rightDocumentPageReadyGeneration = 9;
            f.activity.applyPendingEpubAnchorAfterPageLoad(f.right);
            assertEquals(1, f.right.scripts.size());
            assertTrue(f.right.scripts.get(0).contains("\"right-note\""));
            assertEquals(0, f.activity.currentPage);
            assertEquals(0, f.activity.showCalls);
            assertTrue(f.activity.canSaveDocumentReadingState());
        });
    }

    @Test public void samePageSelectionInvalidatesPendingRestoreAndCachedOldAnchor() {
        withFixture(f -> {
            int interaction = f.activity.documentInteractionGeneration;
            int reloadRequest = privateInt(f.activity, "documentReloadRequestGeneration");
            f.activity.pendingDocumentRestoreAnchorJson = "pending-old-anchor";
            f.activity.lastDocumentContentAnchorJson = "cached-old-anchor";
            assertTrue(restoreMatches(f, interaction));
            assertTrue(f.navigate(0, "new-location"));
            assertEquals(interaction + 1, f.activity.documentInteractionGeneration);
            assertEquals(reloadRequest + 1, privateInt(f.activity, "documentReloadRequestGeneration"));
            assertEquals("", f.activity.pendingDocumentRestoreAnchorJson);
            assertEquals("", f.activity.lastDocumentContentAnchorJson);
            assertFalse(restoreMatches(f, interaction));
            assertEquals(1, f.left.scripts.size());
            assertEquals(0, f.activity.showCalls);
        });
    }

    @Test public void aNewInteractionCancelsAnUnfinishedDestination() {
        withFixture(f -> {
            f.activity.primaryDocumentPageReadyGeneration = 6;
            assertTrue(f.navigate(0, "stale"));
            f.activity.documentInteractionGeneration++;
            f.activity.primaryDocumentPageReadyGeneration = 7;
            f.activity.applyPendingEpubAnchorAfterPageLoad(f.left);
            assertTrue(f.left.scripts.isEmpty());
            assertTrue(f.activity.canSaveDocumentReadingState());
            assertTrue(f.navigate(0, "fresh"));
            assertEquals(1, f.left.scripts.size());
            assertTrue(f.left.scripts.get(0).contains("\"fresh\""));
        });
    }

    @Test public void newestQueuedDestinationReplacesTheEarlierSelection() {
        withFixture(f -> {
            f.activity.primaryDocumentPageReadyGeneration = 6;
            assertTrue(f.navigate(0, "first"));
            assertTrue(f.navigate(0, "second"));
            f.activity.primaryDocumentPageReadyGeneration = 7;
            f.activity.applyPendingEpubAnchorAfterPageLoad(f.left);
            assertEquals(1, f.left.scripts.size());
            assertTrue(f.left.scripts.get(0).contains("\"second\""));
        });
    }

    @Test public void explicitCrossPageDestinationUsesTheNonDebouncedLoadPath() {
        withFixture(f -> {
            f.activity.pageTurnInFlight = true;
            assertTrue(f.navigate(2, "destination"));
            assertEquals(1, f.activity.showCalls);
            assertEquals(2, f.activity.shownPage);
            // DocumentPageDisplayController only debounces nonzero directions.
            assertEquals(0, f.activity.shownDirection);
            assertTrue(f.left.scripts.isEmpty());
            f.activity.primaryDocumentPageReadyGeneration = f.activity.primaryDocumentPageLoadGeneration;
            f.activity.applyPendingEpubAnchorAfterPageLoad(f.left);
            assertEquals(1, f.left.scripts.size());
            assertTrue(f.left.scripts.get(0).contains("\"destination\""));
        });
    }

    @Test public void stalePageIdentityAndRetiredViewCannotApplyAQueuedDestination() {
        withFixture(f -> {
            f.activity.primaryDocumentPageReadyGeneration = 6;
            assertTrue(f.navigate(0, "chapter"));
            TestWebView retired = new TestWebView(f.left.getContext());
            try {
                f.activity.primaryDocumentPageReadyGeneration = 7;
                f.activity.applyPendingEpubAnchorAfterPageLoad(retired);
                assertTrue(retired.scripts.isEmpty());
                f.activity.primaryDocumentPageLoadPage = 2;
                f.activity.applyPendingEpubAnchorAfterPageLoad(f.left);
                assertTrue(f.left.scripts.isEmpty());
                f.activity.primaryDocumentPageLoadPage = 0;
                f.activity.applyPendingEpubAnchorAfterPageLoad(f.left);
                assertEquals(1, f.left.scripts.size());
            } finally {
                retired.destroy();
            }
        });
    }

    @Test public void destroyedActivityDoesNotApplyAQueuedDestination() {
        withFixture(f -> {
            f.activity.primaryDocumentPageReadyGeneration = 6;
            assertTrue(f.navigate(0, "chapter"));
            f.activity.primaryDocumentPageReadyGeneration = 7;
            f.activity.activityDestroyed = true;
            f.activity.applyPendingEpubAnchorAfterPageLoad(f.left);
            assertTrue(f.left.scripts.isEmpty());
        });
    }

    @Test public void zeroSizeFixedLayoutRetryCannotOverrideANewerExplicitSelection() {
        withFixture(f -> {
            Runnable retry = queueFixedLayoutRetry(f);
            assertTrue(f.navigate(0, "new-selection"));
            retry.run();
            assertEquals(0, f.activity.showCalls);
            assertEquals(0, f.activity.currentPage);
        });
    }

    @Test public void zeroSizeFixedLayoutRetryCannotOverrideANewerPageLoad() {
        withFixture(f -> {
            Runnable retry = queueFixedLayoutRetry(f);
            f.activity.documentAnchorPageGeneration++;
            retry.run();
            assertEquals(0, f.activity.showCalls);
        });
    }

    @Test public void zeroSizeFixedLayoutRetryStillRunsForItsUnchangedOwner() {
        withFixture(f -> {
            Runnable retry = queueFixedLayoutRetry(f);
            retry.run();
            assertEquals(1, f.activity.showCalls);
            assertEquals(2, f.activity.shownPage);
            assertEquals(0, f.activity.shownDirection);
        });
    }

    private static Runnable queueFixedLayoutRetry(Fixture f) {
        f.activity.pages.set(2, new DocumentPageActivity.Page("Fixed", "", "OPS/part2.xhtml", false, true, false));
        f.left.capturePosts = true;
        f.left.posted.clear();
        new DocumentPageDisplayController(f.activity).showPage(2, 0);
        assertEquals(0, f.activity.showCalls);
        assertEquals(1, f.left.posted.size());
        return f.left.posted.get(0);
    }

    private static boolean restoreMatches(Fixture f, int interaction) {
        try {
            Method method = DocumentPageActivity.class.getDeclaredMethod("documentAnchorRestoreContextMatches",
                    WebView.class, int.class, int.class, int.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(f.activity, f.left, f.activity.currentPage,
                    f.activity.documentAnchorPageGeneration, interaction);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static int privateInt(DocumentPageActivity activity, String name) {
        try {
            Field field = DocumentPageActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(activity);
        } catch (ReflectiveOperationException failure) {
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
                f.activity.webView = null;
                f.activity.rightWebView = null;
                f.left.capturePosts = false;
                f.right.capturePosts = false;
                f.left.destroy();
                f.right.destroy();
                f.activity.executor.shutdownNow();
            }
        });
    }

    private static final class Fixture {
        final TestActivity activity = new TestActivity();
        final TestWebView left;
        final TestWebView right;

        Fixture() {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            left = new TestWebView(context);
            right = new TestWebView(context);
            activity.webView = left;
            activity.rightWebView = right;
            activity.docType = "EPUB";
            activity.currentPage = 0;
            activity.documentLoadAccepted = true;
            activity.documentAnchorPageGeneration = 11;
            activity.documentInteractionGeneration = 5;
            activity.primaryDocumentPageLoadGeneration = 7;
            activity.primaryDocumentPageReadyGeneration = 7;
            activity.primaryDocumentPageLoadPage = 0;
            activity.rightDocumentPageLoadGeneration = 9;
            activity.rightDocumentPageReadyGeneration = 9;
            activity.rightDocumentPageLoadPage = 1;
            for (int i = 0; i < 3; i++) {
                activity.pages.add(new DocumentPageActivity.Page("Section " + i, "", "OPS/part" + i + ".xhtml"));
            }
        }

        boolean navigate(int page, String fragment) {
            Uri target = new Uri.Builder().scheme("https").authority(activity.localDocumentHost())
                    .path(DocumentPageActivity.EPUB_PREFIX + activity.pages.get(page).sourcePath)
                    .fragment(fragment).build();
            return activity.navigateEpubInternal(left, target);
        }
    }

    private static final class TestActivity extends DocumentPageActivity {
        int showCalls;
        int shownPage = -1;
        int shownDirection = -1;

        @Override int documentRightSpreadPageIndex() { return currentPage == 0 ? 1 : -1; }

        @Override void showPage(int page, int direction) {
            showCalls++;
            shownPage = page;
            shownDirection = direction;
            currentPage = page;
            documentAnchorPageGeneration++;
            primaryDocumentPageLoadPage = page;
            primaryDocumentPageLoadGeneration++;
        }
    }

    private static final class TestWebView extends WebView {
        final List<String> scripts = new ArrayList<>();
        final List<Runnable> posted = new ArrayList<>();
        boolean capturePosts;

        TestWebView(Context context) { super(context); }

        @Override public void evaluateJavascript(String script, ValueCallback<String> callback) {
            scripts.add(script);
            if (callback != null) callback.onReceiveValue("true");
        }

        @Override public boolean post(Runnable action) {
            if (!capturePosts) return super.post(action);
            posted.add(action);
            return true;
        }
    }
}
