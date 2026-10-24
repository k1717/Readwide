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

    @Test public void retiredViewCannotCompleteTheCurrentLoad() {
        withFixture(f -> {
            TestWebView retired = new TestWebView(f.left.getContext());
            try {
                retired.url = f.left.url;
                assertFalse(f.activity.isCurrentDocumentPageLoad(retired, retired.url));
                assertTrue(f.activity.isCurrentDocumentPageLoad(f.left, f.left.url));
                assertTrue(f.activity.isCurrentDocumentPageLoad(f.right, f.right.url));
            } finally {
                retired.destroy();
            }
        });
    }

    @Test public void invalidatedOrDestroyedPageCannotComplete() {
        withFixture(f -> {
            f.activity.primaryDocumentPageLoadPage = -1;
            f.activity.currentPage = -1;
            assertFalse(f.activity.isCurrentDocumentPageLoad(f.left, f.left.url));
            f.activity.activityDestroyed = true;
            assertFalse(f.activity.isCurrentDocumentPageLoad(f.right, f.right.url));
        });
    }

    @Test public void markdownShrinkingToOneViewportReplacesTheOldHeight() {
        withFixture(f -> {
            f.left.snapshot = 1f;
            f.left.contentHeight = 3000;
            assertEquals(3000, f.activity.markdownContentHeightPx());
            f.left.contentHeight = 500;
            assertEquals(1000, f.activity.markdownContentHeightPx());
            assertEquals(0, f.activity.markdownMaxScrollY());
            f.left.contentHeight = 0;
            assertEquals(1000, f.activity.markdownContentHeightPx());
        });
    }

    @Test public void markdownExactViewportHeightAlsoReplacesTheOldHeight() {
        withFixture(f -> {
            f.left.snapshot = 1f;
            f.activity.lastStableMarkdownContentHeightPx = 3000;
            f.left.contentHeight = 1000;
            assertEquals(1000, f.activity.markdownContentHeightPx());
            assertEquals(1000, f.activity.lastStableMarkdownContentHeightPx);
        });
    }

    @Test public void markdownUnavailableHeightRetainsTheMeasuredFallback() {
        withFixture(f -> {
            f.left.snapshot = 2f;
            f.left.contentHeight = 1500;
            assertEquals(3000, f.activity.markdownContentHeightPx());
            f.left.contentHeight = 0;
            assertEquals(3000, f.activity.markdownContentHeightPx());
            f.activity.lastStableMarkdownContentHeightPx = 0;
            assertEquals(1000, f.activity.markdownContentHeightPx());
        });
    }

    @Test public void epubReadingSystemVersionUsesTheAppBuildVersion() {
        withFixture(f -> assertTrue(f.activity.epubReadingSystemJavascript().contains(
                "version:" + org.json.JSONObject.quote(BuildConfig.VERSION_NAME) + ",layoutStyle:")));
    }

    @Test public void mixedEpubSpreadUsesEachPagesLayoutAndIndependentTextSize() {
        withFixture(f -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            String name = "epub-spread-typography-" + java.util.UUID.randomUUID();
            android.content.SharedPreferences store = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            try {
                Context isolated = new android.content.ContextWrapper(context) {
                    @Override public Context getApplicationContext() { return this; }
                    @Override public android.content.SharedPreferences getSharedPreferences(String ignored, int mode) { return store; }
                };
                java.lang.reflect.Constructor<com.readwide.manager.util.PrefsManager> constructor =
                        com.readwide.manager.util.PrefsManager.class.getDeclaredConstructor(Context.class);
                constructor.setAccessible(true);
                f.activity.prefs = constructor.newInstance(isolated);
                f.activity.prefs.setFontSize(32f);
                f.activity.prefs.setEpubFontSize(24f);
                f.activity.docType = "EPUB";
                f.activity.pages.add(new DocumentPageActivity.Page("Cover", "", "cover.xhtml", false, true, false));
                f.activity.pages.add(new DocumentPageActivity.Page("Text", "", "text.xhtml"));
                f.controller.configureForCurrentPage();
                assertEquals(100, f.left.getSettings().getTextZoom());
                assertEquals(150, f.right.getSettings().getTextZoom());
                java.util.Collections.swap(f.activity.pages, 0, 1);
                f.controller.configureForCurrentPage();
                assertEquals(150, f.left.getSettings().getTextZoom());
                assertEquals(100, f.right.getSettings().getTextZoom());
                assertEquals(32f, f.activity.prefs.getFontSize(), 0f);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            } finally {
                f.activity.prefs = null;
                assertTrue(context.deleteSharedPreferences(name));
            }
        });
    }

    @Test public void epubLabelsDescribeSectionsWithoutChangingIndicesOrCount() {
        withFixture(f -> {
            f.activity.docType = "EPUB";
            for (int i = 0; i < 3; i++) f.activity.pages.add(new DocumentPageActivity.Page("Part", "", "part" + i));
            f.activity.currentPage = 1;
            assertEquals(R.string.epub_section_move, f.activity.documentMoveTitleResource());
            String number = String.format(java.util.Locale.getDefault(), "%d", 2);
            String expected = f.activity.getString(R.string.epub_section_progress, number, 3);
            assertEquals(expected, f.activity.formatPageMoveLabel(2, 3));
            assertEquals(expected, f.activity.documentPageStatusLabel(2, 3));
            assertEquals(1, f.activity.currentDisplayDocumentPageIndex());
            assertEquals(3, f.activity.documentPageCount());
            f.activity.docType = "Word";
            assertEquals(R.string.page_move, f.activity.documentMoveTitleResource());
            assertEquals(String.format(java.util.Locale.getDefault(), "%d / %d", 2, 3),
                    f.activity.documentPageStatusLabel(2, 3));
        });
    }

    @Test public void epubSpreadLabelUsesSectionRangeAndClampsAtTheEnd() {
        withFixture(f -> {
            f.activity.docType = "EPUB";
            f.activity.spread = true;
            assertEquals(f.activity.getString(R.string.epub_section_progress,
                            String.format(java.util.Locale.getDefault(), "%d-%d", 2, 3), 3),
                    f.activity.documentPageStatusLabel(2, 3));
            assertEquals(f.activity.getString(R.string.epub_section_progress,
                            String.format(java.util.Locale.getDefault(), "%d", 3), 3),
                    f.activity.documentPageStatusLabel(3, 3));
        });
    }

    @Test public void epubCountersAreNumericWhileMovementAndAccessibilityDescribeSections() {
        withFixture(f -> {
            f.activity.docType = "EPUB";
            for (int i = 0; i < 3; i++) f.activity.pages.add(new DocumentPageActivity.Page("Part", "", "part" + i));
            f.activity.currentPage = 1;
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            f.activity.topPageStatus = new android.widget.TextView(context);
            f.activity.pageStatus = new android.widget.TextView(context);
            f.activity.updateDocumentPageStatusViews(false);
            String numeric = String.format(java.util.Locale.getDefault(), "%d / %d", 2, 3);
            String described = f.activity.getString(R.string.epub_section_progress,
                    String.format(java.util.Locale.getDefault(), "%d", 2), 3);
            assertEquals(numeric, f.activity.topPageStatus.getText().toString());
            assertEquals(described, f.activity.topPageStatus.getContentDescription().toString());
            assertEquals(numeric, f.activity.pageStatus.getText().toString());
            assertEquals(described, f.activity.pageStatus.getContentDescription().toString());
            assertEquals(described, f.activity.formatPageMoveLabel(2, 3));
            assertEquals(R.string.epub_section_move, f.activity.documentMoveTitleResource());
            assertEquals(1, f.activity.currentDisplayDocumentPageIndex());
            assertEquals(3, f.activity.documentPageCount());
        });
    }

    @Test public void numericTopCounterPreservesSpreadRangeAndEndClamp() {
        withFixture(f -> {
            f.activity.docType = "EPUB";
            f.activity.spread = true;
            assertEquals(String.format(java.util.Locale.getDefault(), "%d-%d / %d", 2, 3, 3),
                    f.activity.documentTopPageStatusLabel(2, 3));
            assertEquals(String.format(java.util.Locale.getDefault(), "%d / %d", 3, 3),
                    f.activity.documentTopPageStatusLabel(3, 3));
            assertEquals(f.activity.documentTopPageStatusLabel(3, 3),
                    f.activity.documentTopPageStatusLabel(99, 3));
        });
    }

    @Test public void otherDocumentTopCountersRetainTheirExistingFormat() {
        withFixture(f -> {
            for (String type : new String[]{"Word", "HWP", "HWPX", "Markdown"}) {
                f.activity.docType = type;
                assertEquals(f.activity.documentPageStatusLabel(2, 3),
                        f.activity.documentTopPageStatusLabel(2, 3));
                assertEquals(String.format(java.util.Locale.getDefault(), "%d / %d", 2, 3),
                        f.activity.documentTopPageStatusLabel(2, 3));
            }
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
        boolean spread;
        @Override public android.content.res.Resources getResources() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext().getResources();
        }
        @Override boolean isLandscapeTwoPageDocumentMode() { return spread; }
        @Override int documentRightSpreadPageIndex() { return 1; }
        @Override int markdownViewportHeightPx() { return 1000; }
    }

    private static final class TestWebView extends WebView {
        String url;
        float snapshot = 1.5f;
        int snapshotReads;
        int contentHeight;

        TestWebView(Context context) { super(context); }
        @Override public String getUrl() { return url; }
        @Override public int getContentHeight() { return contentHeight; }
        @Override @SuppressWarnings("deprecation") public float getScale() {
            snapshotReads++;
            return snapshot;
        }
    }
}
