package com.readwide.manager;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.util.PrefsManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Checks double-tap decisions only, without simulating rendered pagination. */
@RunWith(AndroidJUnit4.class)
public class DocumentDoubleTapZoomInstrumentedTest {
    @Test public void epubCenterKeepsResetWhenAnotherProbeWouldDisagree() {
        check("EPUB", false, true, false, 1, 1, 0, 0);
    }

    @Test public void epubCenterKeepsZoomInWhenAnotherProbeWouldDisagree() {
        check("EPUB", false, false, false, 1, 0, 2, 0);
    }

    @Test public void zoomedEpubSideResetsInsteadOfTurningPage() {
        check("EPUB", true, true, false, 1, 1, 0, 0);
    }

    @Test public void fittedEpubSideStillTurnsPage() {
        check("EPUB", true, false, false, 1, 0, 0, 1);
    }

    @Test public void wordSideKeepsPagePriorityWithoutProbingZoom() {
        check("Word", true, true, false, 0, 0, 0, 1);
    }

    @Test public void wordCenterStillResetsZoom() {
        check("Word", false, true, false, 1, 1, 0, 0);
    }

    @Test public void rightPaneIsTheOnlyZoomTarget() {
        check("EPUB", false, true, true, 1, 1, 0, 0);
    }

    private static void check(String type, boolean side, boolean initiallyZoomed,
                              boolean targetRight, int probes, int zoomOut, int zoomIn,
                              int nextPageCalls) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            String name = "document-double-tap-" + UUID.randomUUID();
            SharedPreferences store = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            Screen screen = new Screen();
            ProbeWebView left = new ProbeWebView(context, initiallyZoomed);
            ProbeWebView right = new ProbeWebView(context, initiallyZoomed);
            MotionEvent event = MotionEvent.obtain(0, 10, MotionEvent.ACTION_DOWN,
                    side ? 950f : 500f, 500f, 0);
            try {
                Context isolated = new ContextWrapper(context) {
                    @Override public Context getApplicationContext() { return this; }
                    @Override public SharedPreferences getSharedPreferences(String ignored, int mode) {
                        return store;
                    }
                };
                Constructor<PrefsManager> constructor = PrefsManager.class.getDeclaredConstructor(Context.class);
                constructor.setAccessible(true);
                screen.prefs = constructor.newInstance(isolated);
                screen.prefs.setEpubFontSize(24f);
                screen.prefs.setEpubTapPagingEnabled(true);
                screen.prefs.setWordTapPagingEnabled(true);
                screen.prefs.setTapZoneMode(PrefsManager.TAP_ZONE_HORIZONTAL);
                screen.docType = type;
                screen.webView = left;
                screen.rightWebView = right;
                screen.pages.add(new DocumentPageActivity.Page("Left", "", "left.xhtml", false, false, false));
                screen.pages.add(new DocumentPageActivity.Page("Right", "", "right.xhtml", false, false, false));
                left.layout(0, 0, 1000, 1000);
                right.layout(0, 0, 1000, 1000);
                ProbeWebView target = targetRight ? right : left;
                ProbeWebView other = targetRight ? left : right;
                Field source = DocumentPageActivity.class.getDeclaredField("documentGestureSourceView");
                source.setAccessible(true);
                source.set(screen, target);
                Method doubleTap = DocumentPageActivity.class.getDeclaredMethod("handleDocumentDoubleTap", MotionEvent.class);
                doubleTap.setAccessible(true);
                assertEquals(Boolean.TRUE, doubleTap.invoke(screen, event));
                assertEquals(probes, target.probes);
                assertEquals(zoomOut, target.zoomOutCalls);
                assertEquals(zoomIn, target.zoomInCalls);
                assertEquals(0, other.probes);
                assertEquals(0, other.zoomOutCalls);
                assertEquals(0, other.zoomInCalls);
                assertEquals(nextPageCalls, screen.pageCalls);
                assertEquals(nextPageCalls, screen.lastDirection);
                assertEquals(0, screen.currentPage);
                assertEquals(2, screen.pages.size());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            } finally {
                event.recycle();
                screen.activityDestroyed = true;
                screen.executor.shutdownNow();
                left.destroy();
                right.destroy();
                assertTrue(context.deleteSharedPreferences(name));
            }
        });
    }

    private static final class Screen extends DocumentPageActivity {
        int pageCalls;
        int lastDirection;
        @Override boolean isLandscapeTwoPageDocumentMode() { return false; }
        @Override int documentRightSpreadPageIndex() { return 1; }
        @Override void turnDocumentDisplayPageBy(int direction) {
            pageCalls++;
            lastDirection = direction;
        }
    }

    private static final class ProbeWebView extends WebView {
        final boolean initiallyZoomed;
        int probes;
        int zoomOutCalls;
        int zoomInCalls;
        ProbeWebView(Context context, boolean initiallyZoomed) {
            super(context);
            this.initiallyZoomed = initiallyZoomed;
        }
        @Override @SuppressWarnings("deprecation") public boolean canZoomOut() {
            return ++probes == 1 ? initiallyZoomed : !initiallyZoomed;
        }
        @Override public boolean zoomOut() { zoomOutCalls++; return false; }
        @Override public boolean zoomIn() { zoomInCalls++; return true; }
        @Override public boolean post(Runnable action) { return true; }
        @Override public boolean postDelayed(Runnable action, long delayMillis) { return true; }
    }
}
