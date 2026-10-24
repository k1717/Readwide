package com.readwide.manager;

import android.content.Context;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Dispatches captured callbacks; does not actually crash a renderer process. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 26)
public class DocumentRendererFailureInstrumentedTest {
    @Test public void eitherPaneClosesReaderOnceAndRevokesSavesForEveryDocumentType() {
        for (String type : new String[]{"EPUB", "Word", "HWP", "Markdown"}) {
            for (boolean rightFirst : new boolean[]{false, true}) {
                check(screen -> {
                    screen.docType = type;
                    Pane first = rightFirst ? screen.right : screen.left;
                    Pane second = rightFirst ? screen.left : screen.right;
                    first.dead = true;
                    second.dead = true; // Shared renderer; callback order is not fixed.
                    assertTrue(first.client.onRenderProcessGone(first, detail(rightFirst)));
                    assertTrue(second.client.onRenderProcessGone(second, detail(rightFirst)));
                    assertTrue(first.client.onRenderProcessGone(first, detail(rightFirst)));
                    assertRetired(screen);
                    assertEquals(1, screen.notices);
                    assertEquals(1, screen.zipRetirements);
                    assertEquals(1, screen.left.destroys);
                    assertEquals(1, screen.right.destroys);
                    assertEquals(10, screen.loadGeneration);
                    assertEquals(4, screen.primaryDocumentPageLoadGeneration);
                    assertEquals(6, screen.rightDocumentPageLoadGeneration);
                    assertEquals(-1, screen.primaryDocumentPageReadyGeneration);
                    assertEquals(-1, screen.rightDocumentPageReadyGeneration);
                    assertEquals(0, screen.currentPage);
                    assertEquals("saved-anchor", screen.lastDocumentContentAnchorJson);
                    // No persistence dependencies are installed: revoked saves must return early.
                    screen.saveReadingState();
                    screen.saveOutgoingDocumentReadingState();
                });
            }
        }
    }

    @Test public void lateScriptsAndPageCompletionCannotReviveDeadPanes() {
        check(screen -> {
            screen.evaluateEpubJavascript(screen.left, 0, "true");
            assertNotNull(screen.left.pendingScript);
            android.view.View.OnScrollChangeListener oldScroll = screen.left.scroll;
            screen.docType = "Word";
            screen.left.dead = true;
            assertTrue(screen.left.client.onRenderProcessGone(screen.left, detail(false)));
            screen.left.pendingScript.onReceiveValue("null");
            oldScroll.onScrollChange(screen.left, 0, 100, 0, 0);
            screen.left.client.onPageFinished(screen.left, "https://readwide.local/?rw_load=3");
            screen.right.client.onPageFinished(screen.right, "https://readwide.local/?rw_load=5");
            assertRetired(screen);
            assertEquals(-1, screen.primaryDocumentPageReadyGeneration);
            assertEquals(-1, screen.rightDocumentPageReadyGeneration);
        });
    }

    @Test public void cleanupOrNoticeFailureCannotSkipOtherPaneOrFinish() {
        check(screen -> {
            screen.left.failDestroy = true;
            screen.failZipRetirement = true;
            screen.failNotice = true;
            screen.left.dead = true;
            assertTrue(screen.left.client.onRenderProcessGone(screen.left, detail(true)));
            assertRetired(screen);
            assertEquals(1, screen.left.destroys);
            assertEquals(1, screen.right.destroys);
            assertEquals(1, screen.zipRetirements);
            assertEquals(1, screen.notices);
        });
    }

    @Test public void ordinaryDestructionDetachesBothPanesAndLateCallbackDoesNotFinishAgain() {
        check(screen -> {
            screen.activityDestroyed = true;
            screen.documentLoadAccepted = false;
            screen.dispose();
            assertNull(screen.webView);
            assertNull(screen.rightWebView);
            assertEquals(0, screen.root.getChildCount());
            assertEquals(1, screen.left.destroys);
            assertEquals(1, screen.right.destroys);
            assertTrue(screen.left.client.onRenderProcessGone(screen.left, detail(false)));
            assertEquals(0, screen.finishes);
            assertEquals(0, screen.notices);
        });
    }

    @Test public void queuedParseCannotReplaceReaderAfterRendererLoss() {
        check(screen -> {
            int generation = screen.loadGeneration;
            DocumentPageActivity.ParsedDocument parsed =
                    new DocumentPageActivity.ParsedDocument(screen.documentLoadHost());
            parsed.pages.add(new DocumentPageActivity.Page("Later", "new body", "later.xhtml"));
            screen.left.client.onRenderProcessGone(screen.left, detail(false));
            new DocumentPageLoadController(screen).acceptParsedDocument(generation, parsed,
                    new java.io.File("ignored.epub"), new android.content.Intent());
            assertRetired(screen);
            assertEquals("Chapter", screen.pages.get(0).title);
            assertEquals(1, screen.zipRetirements);
        });
    }

    private static void assertRetired(Screen screen) {
        assertTrue(screen.activityDestroyed);
        assertFalse(screen.documentLoadAccepted);
        assertFalse(screen.canSaveDocumentReadingState());
        assertNull(screen.webView);
        assertNull(screen.rightWebView);
        assertEquals(0, screen.root.getChildCount());
        assertEquals(1, screen.finishes);
    }

    private static RenderProcessGoneDetail detail(boolean crash) {
        return new RenderProcessGoneDetail() {
            @Override public boolean didCrash() { return crash; }
            @Override public int rendererPriorityAtExit() { return WebView.RENDERER_PRIORITY_IMPORTANT; }
        };
    }

    private static void check(Consumer<Screen> check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(InstrumentationRegistry.getInstrumentation().getTargetContext());
            try { check.accept(screen); }
            finally {
                screen.activityDestroyed = true;
                screen.documentLoadAccepted = false;
                screen.dispose();
                screen.executor.shutdownNow();
            }
        });
    }

    private static final class Screen extends DocumentPageActivity {
        final FrameLayout root;
        final Pane left, right;
        int finishes, notices, zipRetirements;
        boolean failNotice, failZipRetirement;

        Screen(Context context) {
            docType = "EPUB";
            pages.add(new Page("Chapter", "<p>body</p>", "chapter.xhtml"));
            pages.add(new Page("Next", "<p>next</p>", "next.xhtml"));
            currentPage = 0;
            lastDocumentContentAnchorJson = "saved-anchor";
            loadGeneration = 9;
            primaryDocumentPageLoadGeneration = primaryDocumentPageReadyGeneration = 3;
            primaryDocumentPageLoadPage = 0;
            rightDocumentPageLoadGeneration = rightDocumentPageReadyGeneration = 5;
            rightDocumentPageLoadPage = 1;
            documentLoadAccepted = true;
            root = new FrameLayout(context);
            left = new Pane(context); right = new Pane(context);
            webView = left; rightWebView = right;
            root.addView(left); root.addView(right);
            setupWebView();
        }

        @Override public void finish() { finishes++; }
        @Override void showDocumentRendererStoppedMessage() {
            notices++;
            if (failNotice) throw new OutOfMemoryError("injected notice failure");
        }
        @Override void closeResourceZip() {
            zipRetirements++;
            if (failZipRetirement) throw new IllegalStateException("injected retirement failure");
            super.closeResourceZip();
        }
        void dispose() {
            try {
                Method destroy = DocumentPageActivity.class.getDeclaredMethod("destroyDocumentWebView");
                destroy.setAccessible(true);
                destroy.invoke(this);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
    }

    private static final class Pane extends WebView {
        WebViewClient client;
        ValueCallback<String> pendingScript;
        android.view.View.OnScrollChangeListener scroll;
        boolean dead, failDestroy;
        int destroys;
        Pane(Context context) { super(context); }
        @Override public void setOnScrollChangeListener(android.view.View.OnScrollChangeListener listener) {
            scroll = listener;
            super.setOnScrollChangeListener(listener);
        }
        @Override public void setWebViewClient(WebViewClient value) {
            assertNotNull("Keep the exit handler until destruction", value);
            client = value;
            super.setWebViewClient(value);
        }
        @Override public WebSettings getSettings() {
            if (dead) throw new AssertionError("Settings queried after renderer exit");
            return super.getSettings();
        }
        @Override public void evaluateJavascript(String script, ValueCallback<String> callback) {
            if (dead) throw new AssertionError("Script submitted after renderer exit");
            pendingScript = callback;
        }
        @Override public void loadUrl(String url) {
            throw new AssertionError("Teardown must not load a replacement page");
        }
        @Override public void destroy() {
            assertNull("Detach before destroy", getParent());
            assertEquals("Destroy each pane once", 0, destroys++);
            super.destroy();
            if (failDestroy) throw new IllegalStateException("injected cleanup failure");
        }
    }
}
