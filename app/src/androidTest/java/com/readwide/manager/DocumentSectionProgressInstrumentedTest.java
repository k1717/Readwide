package com.readwide.manager;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Display-only progress with synthetic metrics; no document, script or preference is loaded. */
@RunWith(AndroidJUnit4.class)
public class DocumentSectionProgressInstrumentedTest {
    @Test public void readySectionShowsStartMiddleAndNativeEndWithoutChangingPositionState() {
        check(f -> {
            f.activity.lastDocumentContentAnchorJson = "unchanged-anchor";
            f.view.scrollTo(0, 0);
            assertEquals(0, f.activity.currentEpubSectionProgressPercent());
            f.view.scrollTo(0, 500);
            assertEquals(50, f.activity.currentEpubSectionProgressPercent());
            f.activity.refreshEpubSectionProgressViews();
            assertEquals(String.format(Locale.getDefault(), "%d (%d%%) / %d", 3, 50, 20),
                    f.activity.topPageStatus.getText().toString());
            assertEquals(f.activity.topPageStatus.getText().toString(), f.activity.pageStatus.getText().toString());
            String described = f.activity.getString(R.string.epub_section_progress,
                    String.format(Locale.getDefault(), "%d (%d%%)", 3, 50), 20);
            assertEquals(described, f.activity.topPageStatus.getContentDescription());
            assertEquals(f.activity.topPageStatus.getContentDescription(), f.activity.pageStatus.getContentDescription());
            assertEquals(2, f.activity.currentPage);
            assertEquals(20, f.activity.documentPageCount());
            assertEquals(500, f.view.getScrollY());
            assertEquals("unchanged-anchor", f.activity.lastDocumentContentAnchorJson);
            assertEquals(0, f.activity.documentAnchorPageGeneration);
            assertEquals(0, f.activity.documentInteractionGeneration);
            f.view.scrollTo(0, 999);
            f.view.canScrollDown = false;
            assertEquals(100, f.activity.currentEpubSectionProgressPercent());
        });
    }

    @Test public void incompleteMismatchedAndOldLoadsDoNotExposeProgress() {
        check(f -> {
            assertEquals(50, f.activity.currentEpubSectionProgressPercent());
            f.activity.primaryDocumentPageReadyGeneration = -1;
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
            f.activity.primaryDocumentPageReadyGeneration = 7;
            f.activity.primaryDocumentPageLoadPage = 1;
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
            f.activity.primaryDocumentPageLoadPage = 2;
            f.view.url = "https://readwide.local/?rw_load=6";
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
            f.view.url = null;
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
            f.view.url = "https://readwide.local/?rw_load=7";
            f.activity.documentLoadAccepted = false;
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
            f.activity.documentLoadAccepted = true;
            f.activity.activityDestroyed = true;
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
        });
    }

    @Test public void pendingAnchorNativeAndLinkRestoresSuppressIntermediateProgress() {
        check(f -> {
            f.activity.pendingDocumentRestoreAnchorJson = "pending-anchor";
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
            assertEquals("pending-anchor", f.activity.pendingDocumentRestoreAnchorJson);
            f.activity.pendingDocumentRestoreAnchorJson = "";
            Bundle restore = new Bundle();
            restore.putString("nativeType", "EPUB");
            restore.putInt("page", 2);
            set(f.activity, "pendingDocumentNativeRestore", restore);
            set(f.activity, "pendingDocumentNativeRestoreLoad", f.activity.loadGeneration);
            set(f.activity, "pendingDocumentNativeRestoreInteraction", f.activity.documentInteractionGeneration);
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
            set(f.activity, "pendingDocumentNativeRestore", null);
            set(f.activity, "pendingEpubAnchorPage", 2);
            set(f.activity, "pendingEpubAnchorInteractionGeneration", f.activity.documentInteractionGeneration);
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
            set(f.activity, "pendingEpubAnchorPage", -1);
            assertEquals(50, f.activity.currentEpubSectionProgressPercent());
        });
    }

    @Test public void readySectionWithBlankHistoryStillShowsProgressButUnreadyLoadsDoNot() {
        check(f -> {
            // Real loadDataWithBaseURL uses a null history URL; unlike the base
            // URL passed to the load callback, the visible URL can be about:blank.
            f.view.url = "about:blank";
            f.activity.refreshEpubSectionProgressViews();
            assertEquals(String.format(Locale.getDefault(), "%d (%d%%) / %d", 3, 50, 20),
                    f.activity.pageStatus.getText().toString());
            assertEquals(500, f.view.getScrollY());
            assertEquals(2, f.activity.currentPage);
            f.activity.primaryDocumentPageReadyGeneration = 6;
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
            f.activity.primaryDocumentPageReadyGeneration = 7;
            f.activity.primaryDocumentPageLoadPage = -1;
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
            f.activity.primaryDocumentPageLoadPage = 2;
            f.activity.pendingDocumentRestoreAnchorJson = "pending-anchor";
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
            f.activity.pendingDocumentRestoreAnchorJson = "";
            assertEquals(50, f.activity.currentEpubSectionProgressPercent());
        });
    }

    @Test public void originalLayoutVerticalAndSpreadSectionsKeepPlainCounters() {
        check(f -> {
            boolean[][] flags = {{true, false, false}, {false, true, false}, {false, false, true}};
            for (boolean[] flag : flags) {
                f.activity.pages.set(2, new DocumentPageActivity.Page("Part", "", "part2.xhtml",
                        flag[0], flag[1], flag[2]));
                assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
                f.activity.refreshEpubSectionProgressViews();
                assertFalse(f.activity.pageStatus.getText().toString().contains("%"));
            }
            f.activity.pages.set(2, new DocumentPageActivity.Page("Part", "", "part2.xhtml"));
            f.activity.spread = true;
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
            f.activity.refreshEpubSectionProgressViews();
            assertEquals(String.format(Locale.getDefault(), "%d-%d / %d", 3, 4, 20),
                    f.activity.pageStatus.getText().toString());
        });
    }

    @Test public void sliderPreviewIsNotReplacedByLiveSectionProgress() {
        check(f -> {
            f.activity.documentPageSeekBarUserTracking = true;
            f.activity.topPageStatus.setText("preview-top");
            f.activity.pageStatus.setText("preview-bottom");
            f.activity.topPageStatus.setContentDescription("preview-description");
            f.activity.refreshEpubSectionProgressViews();
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
            assertEquals("preview-top", f.activity.topPageStatus.getText().toString());
            assertEquals("preview-bottom", f.activity.pageStatus.getText().toString());
            assertEquals("preview-description", f.activity.topPageStatus.getContentDescription());
            assertEquals(2, f.activity.currentPage);
        });
    }

    @Test public void loadInvalidationClearsAnOldPercentage() {
        check(f -> {
            f.activity.refreshEpubSectionProgressViews();
            assertTrue(f.activity.pageStatus.getText().toString().contains("%"));
            f.activity.primaryDocumentPageReadyGeneration = -1;
            f.activity.refreshEpubSectionProgressViews();
            String plain = String.format(Locale.getDefault(), "%d / %d", 3, 20);
            assertEquals(plain, f.activity.pageStatus.getText().toString());
            assertEquals(plain, f.activity.topPageStatus.getText().toString());
            String described = f.activity.getString(R.string.epub_section_progress,
                    String.format(Locale.getDefault(), "%d", 3), 20);
            assertEquals(described, f.activity.pageStatus.getContentDescription());
            assertEquals(described, f.activity.topPageStatus.getContentDescription());
        });
    }

    @Test public void progressUsesCurrentScaleContentAndViewportMetrics() {
        check(f -> {
            assertEquals(50, f.activity.currentEpubSectionProgressPercent());
            f.view.scale = 2f;
            assertEquals(16, f.activity.currentEpubSectionProgressPercent());
            f.view.scale = 1f;
            f.view.contentHeight = 3000;
            assertEquals(25, f.activity.currentEpubSectionProgressPercent());
            f.view.layout(0, 0, 600, 500);
            assertEquals(20, f.activity.currentEpubSectionProgressPercent());
            assertEquals(500, f.view.getScrollY());
            f.view.contentHeight = 400;
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
            f.view.contentHeight = 2000;
            f.view.layout(0, 0, 0, 500);
            assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
        });
    }

    @Test public void nonEpubCountersAreNotTouchedByTheProgressRefresh() {
        check(f -> {
            for (String type : new String[]{"Word", "HWP", "HWPX", "Markdown"}) {
                f.activity.docType = type;
                f.activity.topPageStatus.setText("other-top");
                f.activity.pageStatus.setText("other-bottom");
                f.activity.refreshEpubSectionProgressViews();
                assertEquals(-1, f.activity.currentEpubSectionProgressPercent());
                assertEquals("other-top", f.activity.topPageStatus.getText().toString());
                assertEquals("other-bottom", f.activity.pageStatus.getText().toString());
            }
        });
    }

    @Test public void scheduledUpdatesCoalesceAndReadTheLatestMetrics() {
        check(f -> {
            f.view.captureAnimationPosts = true;
            f.activity.scheduleEpubSectionProgressUpdate();
            f.activity.scheduleEpubSectionProgressUpdate();
            f.activity.scheduleEpubSectionProgressUpdate();
            assertEquals(1, f.view.animationPosts);
            assertNotNull(f.view.pendingAnimation);
            f.view.contentHeight = 3000;
            f.view.scrollTo(0, 200);
            f.view.takeAnimation().run();
            String latest = String.format(Locale.getDefault(), "%d (%d%%) / %d", 3, 10, 20);
            assertEquals(latest, f.activity.topPageStatus.getText().toString());
            assertEquals(latest, f.activity.pageStatus.getText().toString());
            assertEquals(200, f.view.getScrollY());
            f.activity.scheduleEpubSectionProgressUpdate();
            assertEquals(2, f.view.animationPosts);
            assertNotNull(f.view.pendingAnimation);
        });
    }

    @Test public void replacementCancelsTheOldFrameAndDestroyedCallbacksDoNothing() {
        check(f -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            TestWebView replacement = new TestWebView(context);
            try {
                replacement.layout(0, 0, 600, 1000);
                replacement.scrollTo(0, 700);
                replacement.captureAnimationPosts = true;
                f.view.captureAnimationPosts = true;
                f.activity.scheduleEpubSectionProgressUpdate();
                assertNotNull(f.view.pendingAnimation);
                f.activity.webView = replacement;
                f.activity.scheduleEpubSectionProgressUpdate();
                assertNull(f.view.pendingAnimation);
                assertEquals(1, f.view.animationRemovals);
                assertEquals(1, replacement.animationPosts);
                f.activity.topPageStatus.setText("before-destroy-top");
                f.activity.pageStatus.setText("before-destroy-bottom");
                f.activity.activityDestroyed = true;
                replacement.takeAnimation().run();
                assertEquals("before-destroy-top", f.activity.topPageStatus.getText().toString());
                assertEquals("before-destroy-bottom", f.activity.pageStatus.getText().toString());
                f.activity.scheduleEpubSectionProgressUpdate();
                assertEquals(1, replacement.animationPosts);
                f.activity.activityDestroyed = false;
                f.activity.scheduleEpubSectionProgressUpdate();
                Runnable cancelled = replacement.pendingAnimation;
                cancelScheduledUpdate(f.activity);
                assertNull(replacement.pendingAnimation);
                assertEquals(1, replacement.animationRemovals);
                cancelled.run();
                assertEquals("before-destroy-top", f.activity.topPageStatus.getText().toString());
                assertEquals("before-destroy-bottom", f.activity.pageStatus.getText().toString());
            } finally {
                cancelScheduledUpdate(f.activity);
                f.activity.webView = f.view;
                replacement.destroy();
            }
        });
    }

    private static void cancelScheduledUpdate(DocumentPageActivity activity) {
        try {
            Method method = DocumentPageActivity.class.getDeclaredMethod("cancelEpubSectionProgressUpdate");
            method.setAccessible(true);
            method.invoke(activity);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void set(DocumentPageActivity activity, String name, Object value) {
        try {
            Field field = DocumentPageActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(activity, value);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void check(Consumer<Fixture> check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture();
            try { check.accept(f); } finally {
                f.activity.activityDestroyed = true;
                cancelScheduledUpdate(f.activity);
                f.view.destroy();
                f.activity.webView = null;
                f.activity.executor.shutdownNow();
            }
        });
    }

    private static final class Fixture {
        final TestActivity activity = new TestActivity();
        final TestWebView view;
        Fixture() {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            view = new TestWebView(context);
            activity.webView = view;
            activity.topPageStatus = new TextView(context);
            activity.pageStatus = new TextView(context);
            activity.docType = "EPUB";
            for (int i = 0; i < 20; i++) activity.pages.add(
                    new DocumentPageActivity.Page("Part", "", "part" + i + ".xhtml"));
            activity.currentPage = 2;
            activity.documentLoadAccepted = true;
            activity.primaryDocumentPageLoadGeneration = 7;
            activity.primaryDocumentPageReadyGeneration = 7;
            activity.primaryDocumentPageLoadPage = 2;
            view.layout(0, 0, 600, 1000);
            view.scrollTo(0, 500);
        }
    }

    private static final class TestActivity extends DocumentPageActivity {
        boolean spread;
        @Override public Resources getResources() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext().getResources();
        }
        @Override boolean isLandscapeTwoPageDocumentMode() { return spread; }
    }

    private static final class TestWebView extends WebView {
        String url = "https://readwide.local/?rw_load=7";
        int contentHeight = 2000;
        float scale = 1f;
        boolean canScrollDown = true;
        boolean captureAnimationPosts;
        Runnable pendingAnimation;
        int animationPosts, animationRemovals;
        TestWebView(Context context) { super(context); }
        @Override public String getUrl() { return url; }
        @Override public int getContentHeight() { return contentHeight; }
        @Override public boolean canScrollVertically(int direction) {
            return direction > 0 ? canScrollDown : getScrollY() > 0;
        }
        @Override @SuppressWarnings("deprecation") public float getScale() { return scale; }
        @Override public void postOnAnimation(Runnable action) {
            if (!captureAnimationPosts) {
                super.postOnAnimation(action);
                return;
            }
            pendingAnimation = action;
            animationPosts++;
        }
        @Override public boolean removeCallbacks(Runnable action) {
            if (action == pendingAnimation && pendingAnimation != null) {
                pendingAnimation = null;
                animationRemovals++;
                return true;
            }
            return super.removeCallbacks(action);
        }
        Runnable takeAnimation() {
            Runnable action = pendingAnimation;
            assertNotNull(action);
            pendingAnimation = null;
            return action;
        }
    }
}
