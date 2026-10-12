package com.readwide.manager;

import android.content.Context;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Source regression coverage; these tests require an Android WebView runtime. */
@RunWith(AndroidJUnit4.class)
public class DocumentWebScaleInstrumentedTest {
    @Test public void acceptedCallbackWinsOverTheLegacySnapshot() {
        withFixture(f -> {
            assertTrue(f.controller.recordScaleChange(f.left, 2.5f));
            assertEquals(2.5f, f.controller.scaleFor(f.left), 0f);
            assertEquals(0, f.left.snapshotReads);
        });
    }

    @Test public void leftAndRightObservationsAreIndependent() {
        withFixture(f -> {
            assertTrue(f.controller.recordScaleChange(f.left, 2f));
            assertTrue(f.controller.recordScaleChange(f.right, 3f));
            assertEquals(2f, f.controller.scaleFor(f.left), 0f);
            assertEquals(3f, f.controller.scaleFor(f.right), 0f);
        });
    }

    @Test public void newLoadCannotReuseOrAcceptTheOldLoadScale() {
        withFixture(f -> {
            assertTrue(f.controller.recordScaleChange(f.left, 4f));
            f.activity.primaryDocumentPageLoadGeneration++;
            assertFalse(f.controller.recordScaleChange(f.left, 5f));
            assertEquals(1.5f, f.controller.scaleFor(f.left), 0f);
            f.left.url = "https://readwide.local/?rw_load=8";
            assertTrue(f.controller.recordScaleChange(f.left, 2f));
            assertEquals(2f, f.controller.scaleFor(f.left), 0f);
        });
    }

    @Test public void replacementViewDoesNotInheritTheOldViewObservation() {
        withFixture(f -> {
            assertTrue(f.controller.recordScaleChange(f.left, 4f));
            TestWebView replacement = new TestWebView(f.left.getContext());
            try {
                replacement.url = f.left.url;
                f.activity.webView = replacement;
                assertFalse(f.controller.recordScaleChange(f.left, 5f));
                assertEquals(1.5f, f.controller.scaleFor(f.left), 0f);
                assertEquals(1.5f, f.controller.scaleFor(replacement), 0f);
                assertTrue(f.controller.recordScaleChange(replacement, 2f));
                assertEquals(2f, f.controller.scaleFor(replacement), 0f);
            } finally {
                f.activity.webView = f.left;
                replacement.destroy();
            }
        });
    }

    @Test public void invalidCallbackCannotPoisonAcceptedScale() {
        withFixture(f -> {
            assertTrue(f.controller.recordScaleChange(f.left, 2f));
            for (float invalid : new float[]{0f, -1f, Float.NaN, Float.POSITIVE_INFINITY}) {
                assertFalse(f.controller.recordScaleChange(f.left, invalid));
                assertEquals(2f, f.controller.scaleFor(f.left), 0f);
            }
        });
    }

    @Test public void initialSnapshotIsNotCachedAndInvalidSnapshotIsSanitized() {
        withFixture(f -> {
            assertEquals(1.5f, f.controller.scaleFor(f.left), 0f);
            f.left.snapshot = 3f;
            assertEquals(3f, f.controller.scaleFor(f.left), 0f);
            f.left.snapshot = Float.NaN;
            assertEquals(1f, f.controller.scaleFor(f.left), 0f);
            assertEquals(3, f.left.snapshotReads);
        });
    }

    @Test public void blankPageOrDifferentPageCannotReuseScale() {
        withFixture(f -> {
            assertTrue(f.controller.recordScaleChange(f.left, 4f));
            f.left.url = "about:blank";
            assertFalse(f.controller.recordScaleChange(f.left, 2f));
            assertEquals(1.5f, f.controller.scaleFor(f.left), 0f);
            f.left.url = "https://readwide.local/?rw_load=7";
            f.activity.currentPage = 2;
            assertFalse(f.controller.recordScaleChange(f.left, 2f));
            assertEquals(1.5f, f.controller.scaleFor(f.left), 0f);
        });
    }

    @Test public void destroyedActivityRejectsCallbacks() {
        withFixture(f -> {
            f.activity.activityDestroyed = true;
            assertFalse(f.controller.recordScaleChange(f.left, 4f));
        });
    }

    private static void withFixture(Consumer<Fixture> check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture();
            try {
                check.accept(f);
            } finally {
                f.activity.activityDestroyed = true;
                f.left.destroy();
                f.right.destroy();
                f.activity.webView = null;
                f.activity.rightWebView = null;
                f.activity.executor.shutdownNow();
            }
        });
    }

    private static final class Fixture {
        final TestActivity activity = new TestActivity();
        final TestWebView left;
        final TestWebView right;
        final DocumentWebViewController controller;

        Fixture() {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            left = new TestWebView(context);
            right = new TestWebView(context);
            activity.webView = left;
            activity.rightWebView = right;
            activity.currentPage = 0;
            activity.primaryDocumentPageLoadGeneration = 7;
            activity.primaryDocumentPageLoadPage = 0;
            activity.rightDocumentPageLoadGeneration = 9;
            activity.rightDocumentPageLoadPage = 1;
            left.url = "https://readwide.local/?rw_load=7";
            right.url = "https://readwide.local/?rw_load=9";
            controller = new DocumentWebViewController(activity);
        }
    }

    private static final class TestActivity extends DocumentPageActivity {
        @Override int documentRightSpreadPageIndex() { return 1; }
    }

    private static final class TestWebView extends WebView {
        String url;
        float snapshot = 1.5f;
        int snapshotReads;

        TestWebView(Context context) { super(context); }
        @Override public String getUrl() { return url; }
        @Override @SuppressWarnings("deprecation") public float getScale() {
            snapshotReads++;
            return snapshot;
        }
    }
}
