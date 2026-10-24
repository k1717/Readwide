package com.readwide.manager;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.util.PrefsManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.Assert.*;

/** Pane-specific zoom reset only; no rendered layout or paging is simulated. */
@RunWith(AndroidJUnit4.class)
public class EpubZoomResetInstrumentedTest {
    @Test public void rightFixedPageResetsToItsOriginalSizeBesideEnlargedText() {
        check(false, true, false, true, 100);
    }

    @Test public void rightTextPageKeepsItsTextSizeBesideFixedPage() {
        check(true, false, false, true, 150);
    }

    @Test public void rightImagePageKeepsOriginalSizeBesideEnlargedText() {
        check(false, false, true, true, 100);
    }

    @Test public void primaryTextResetStillUsesItsOwnPage() {
        check(false, true, false, false, 150);
    }

    private static void check(boolean leftFixed, boolean rightFixed, boolean rightImage,
                              boolean targetRight, int expected) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            String name = "epub-reset-" + UUID.randomUUID();
            SharedPreferences store = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            Screen screen = new Screen();
            QuietWebView left = new QuietWebView(context);
            QuietWebView right = new QuietWebView(context);
            try {
                Context isolated = new ContextWrapper(context) {
                    @Override public Context getApplicationContext() { return this; }
                    @Override public SharedPreferences getSharedPreferences(String ignored, int mode) { return store; }
                };
                Constructor<PrefsManager> constructor = PrefsManager.class.getDeclaredConstructor(Context.class);
                constructor.setAccessible(true);
                screen.prefs = constructor.newInstance(isolated);
                screen.prefs.setEpubFontSize(24f);
                screen.docType = "EPUB";
                screen.webView = left;
                screen.rightWebView = right;
                screen.pages.add(new DocumentPageActivity.Page("Left", "", "left.xhtml", false, leftFixed, false));
                screen.pages.add(new DocumentPageActivity.Page("Right", "", "right.xhtml", rightImage, rightFixed, false));
                left.getSettings().setTextZoom(190);
                right.getSettings().setTextZoom(190);
                Method reset = DocumentPageActivity.class.getDeclaredMethod("resetDocumentZoom", WebView.class);
                reset.setAccessible(true);
                reset.invoke(screen, targetRight ? right : left);
                assertEquals(expected, (targetRight ? right : left).getSettings().getTextZoom());
                assertEquals(190, (targetRight ? left : right).getSettings().getTextZoom());
                assertEquals(0, screen.currentPage);
                assertEquals(2, screen.pages.size());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            } finally {
                screen.activityDestroyed = true;
                screen.executor.shutdownNow();
                left.destroy();
                right.destroy();
                assertTrue(context.deleteSharedPreferences(name));
            }
        });
    }

    private static final class Screen extends DocumentPageActivity {
        @Override int documentRightSpreadPageIndex() { return 1; }
    }

    private static final class QuietWebView extends WebView {
        QuietWebView(Context context) { super(context); }
        @Override public boolean zoomOut() { return false; }
        @Override public boolean post(Runnable action) { return true; }
        @Override public boolean postDelayed(Runnable action, long delayMillis) { return true; }
    }
}
