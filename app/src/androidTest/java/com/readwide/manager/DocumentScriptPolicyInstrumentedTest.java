package com.readwide.manager;

import android.content.Context;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Late helper cleanup ownership; callbacks are held without executing scripts. */
@RunWith(AndroidJUnit4.class)
public class DocumentScriptPolicyInstrumentedTest {
    @Test public void ordinarySameLoadReturnsToDisabledPolicy() {
        check(f -> {
            f.activity.evaluateEpubJavascript(f.left, 0, "true");
            assertTrue(f.left.getSettings().getJavaScriptEnabled());
            f.left.complete();
            assertFalse(f.left.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void replacedWebViewRejectsOldCleanupAtTheSamePageIndex() {
        check(f -> {
            f.activity.evaluateEpubJavascript(f.left, 0, "true");
            f.activity.webView = f.replacement;
            f.replacement.getSettings().setJavaScriptEnabled(true);
            f.left.complete();
            assertTrue(f.left.getSettings().getJavaScriptEnabled());
            assertTrue(f.replacement.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void replacementDocumentAtTheSameIndexRejectsOldCleanup() {
        check(f -> {
            f.activity.evaluateEpubJavascript(f.left, 0, "true");
            f.activity.loadGeneration++;
            // A newer document helper may currently own this enabled setting.
            f.left.complete();
            assertTrue(f.left.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void samePaneReloadRejectsEarlierHelperCleanup() {
        check(f -> {
            f.activity.evaluateDocumentAnchorJavascript("true", null);
            f.activity.primaryDocumentPageLoadGeneration++;
            f.left.complete();
            assertTrue(f.left.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void rightPaneCleanupUsesItsOwnLoadGeneration() {
        check(f -> {
            f.activity.evaluateEpubJavascript(f.right, 1, "true");
            f.activity.rightDocumentPageLoadGeneration++;
            f.right.complete();
            assertTrue(f.right.getSettings().getJavaScriptEnabled());
            f.right.getSettings().setJavaScriptEnabled(false);
            f.activity.evaluateEpubJavascript(f.right, 1, "true");
            f.activity.primaryDocumentPageLoadGeneration++;
            f.right.complete();
            assertFalse(f.right.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void currentScriptedEpubRetainsItsRequiredPolicy() {
        check(f -> {
            f.activity.pages.set(0, new DocumentPageActivity.Page("Scripted", "", "scripted.xhtml",
                    false, false, false, true, "", "", 0, null));
            f.activity.evaluateEpubJavascript(f.left, 0, "true");
            f.left.complete();
            assertTrue(f.left.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void oldMarkdownSelectionCleanupCannotDisableReplacementEpub() {
        check(f -> {
            f.activity.docType = "Markdown";
            f.activity.clearMarkdownWebSelection();
            f.activity.docType = "EPUB";
            f.activity.loadGeneration++;
            f.activity.webView = f.replacement;
            f.activity.pages.set(0, new DocumentPageActivity.Page("Scripted", "", "scripted.xhtml",
                    false, false, false, true, "", "", 0, null));
            f.replacement.getSettings().setJavaScriptEnabled(true);
            f.left.complete();
            assertTrue(f.replacement.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void helperThatDidNotEnableScriptingDoesNotDisableIt() {
        check(f -> {
            f.left.getSettings().setJavaScriptEnabled(true);
            f.activity.evaluateEpubJavascript(f.left, 0, "true");
            f.left.complete();
            assertTrue(f.left.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void overlappingHelpersKeepScriptingUntilBothComplete() {
        check(f -> {
            f.activity.evaluateEpubJavascript(f.left, 0, "first");
            f.activity.evaluateDocumentAnchorJavascript("second", null);
            f.left.complete();
            assertTrue(f.left.getSettings().getJavaScriptEnabled());
            f.left.complete();
            assertFalse(f.left.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void reverseCompletionOrderAlsoWaitsForTheLastHelper() {
        check(f -> {
            f.activity.evaluateEpubJavascript(f.left, 0, "first");
            f.activity.evaluateEpubJavascript(f.left, 0, "second");
            f.left.completeAt(1);
            assertTrue(f.left.getSettings().getJavaScriptEnabled());
            f.left.complete();
            assertFalse(f.left.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void duplicateCompletionDoesNotReleaseAnotherHelper() {
        check(f -> {
            f.activity.evaluateEpubJavascript(f.left, 0, "first");
            ValueCallback<String> first = f.left.pending.get(0);
            f.activity.evaluateEpubJavascript(f.left, 0, "second");
            f.left.complete();
            first.onReceiveValue("true");
            assertTrue(f.left.getSettings().getJavaScriptEnabled());
            f.left.complete();
            assertFalse(f.left.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void throwingContinuationStillReleasesItsHelper() {
        check(f -> {
            IllegalStateException failure = new IllegalStateException("injected callback failure");
            f.activity.evaluateDocumentJavascript(f.left, 0, "first", value -> {
                assertTrue(f.left.getSettings().getJavaScriptEnabled());
                throw failure;
            });
            try {
                f.left.complete();
                fail("Callback failure must propagate");
            } catch (IllegalStateException expected) { assertSame(failure, expected); }
            assertFalse(f.left.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void nestedContinuationJoinsTheSameEnabledGroup() {
        check(f -> {
            f.activity.evaluateDocumentJavascript(f.left, 0, "first", value -> {
                assertTrue(f.left.getSettings().getJavaScriptEnabled());
                f.activity.evaluateEpubJavascript(f.left, 0, "nested");
            });
            f.left.complete();
            assertTrue(f.left.getSettings().getJavaScriptEnabled());
            f.left.complete();
            assertFalse(f.left.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void submissionFailureRestoresPolicyAndAllowsLaterRequest() {
        check(f -> {
            for (Throwable failure : submissionFailures()) {
                f.left.rejection = failure;
                expectSubmissionFailure(f, failure);
                assertFalse(f.left.getSettings().getJavaScriptEnabled());
                assertTrue(f.left.pending.isEmpty());
                f.left.rejection = null;
                f.activity.evaluateEpubJavascript(f.left, 0, "retry");
                assertTrue(f.left.getSettings().getJavaScriptEnabled());
                f.left.complete();
                assertFalse(f.left.getSettings().getJavaScriptEnabled());
            }
        });
    }

    @Test public void failedSubmissionDoesNotReleaseAnAlreadyPendingHelper() {
        check(f -> {
            f.activity.evaluateEpubJavascript(f.left, 0, "pending");
            for (Throwable failure : submissionFailures()) {
                f.left.rejection = failure;
                expectSubmissionFailure(f, failure);
                assertTrue(f.left.getSettings().getJavaScriptEnabled());
                assertEquals(1, f.left.pending.size());
            }
            f.left.rejection = null;
            f.left.complete();
            assertFalse(f.left.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void leftAndRightHelpersReleaseIndependently() {
        check(f -> {
            f.activity.evaluateEpubJavascript(f.left, 0, "left");
            f.activity.evaluateEpubJavascript(f.right, 1, "right");
            f.left.complete();
            assertFalse(f.left.getSettings().getJavaScriptEnabled());
            assertTrue(f.right.getSettings().getJavaScriptEnabled());
            f.right.complete();
            assertFalse(f.right.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void oldCompletionCannotReleaseNewLoadHelpersInSameView() {
        check(f -> {
            f.activity.evaluateEpubJavascript(f.left, 0, "old");
            f.activity.primaryDocumentPageLoadGeneration++;
            // The display pipeline applies the new page policy before loading it.
            f.left.getSettings().setJavaScriptEnabled(false);
            f.activity.evaluateEpubJavascript(f.left, 0, "new");
            f.left.complete();
            assertTrue(f.left.getSettings().getJavaScriptEnabled());
            f.left.complete();
            assertFalse(f.left.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void destroyedActivityDoesNotTouchTheRetiredViewSettings() {
        check(f -> {
            f.activity.evaluateEpubJavascript(f.left, 0, "old");
            f.activity.activityDestroyed = true;
            f.left.complete();
            assertTrue(f.left.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void explicitInvalidationRetiresTemporaryPolicyBeforeNewLoad() {
        check(f -> {
            f.activity.evaluateEpubJavascript(f.left, 0, "old");
            f.activity.invalidateDocumentPageLoad(f.left);
            assertFalse(f.left.getSettings().getJavaScriptEnabled());
            f.activity.documentBaseUrlForPageLoad(f.activity.pages.get(0), f.left, 0);
            f.activity.evaluateEpubJavascript(f.left, 0, "new");
            f.left.complete();
            assertTrue(f.left.getSettings().getJavaScriptEnabled());
            f.left.complete();
            assertFalse(f.left.getSettings().getJavaScriptEnabled());
        });
    }

    @Test public void overlappingHelpersPreserveAlreadyEnabledBaseline() {
        check(f -> {
            f.left.getSettings().setJavaScriptEnabled(true);
            f.activity.evaluateEpubJavascript(f.left, 0, "first");
            f.activity.evaluateEpubJavascript(f.left, 0, "second");
            f.left.complete();
            f.left.complete();
            assertTrue(f.left.getSettings().getJavaScriptEnabled());
        });
    }

    private static Throwable[] submissionFailures() {
        // Inject failures without exhausting the actual process heap or stack.
        return new Throwable[]{new IllegalStateException("injected rejection"),
                new OutOfMemoryError("injected allocation failure"),
                new StackOverflowError("injected stack failure")};
    }

    private static void expectSubmissionFailure(Fixture f, Throwable failure) {
        try {
            f.activity.evaluateEpubJavascript(f.left, 0, "rejected");
            fail("Submission failure must propagate");
        } catch (RuntimeException | Error expected) { assertSame(failure, expected); }
    }

    private static void check(Consumer<Fixture> check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture();
            try { check.accept(f); }
            finally {
                f.activity.activityDestroyed = true;
                f.activity.executor.shutdownNow();
                f.left.destroy();
                f.right.destroy();
                f.replacement.destroy();
            }
        });
    }

    private static final class Fixture {
        final Screen activity = new Screen();
        final HeldWebView left, right, replacement;
        Fixture() {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            left = new HeldWebView(context);
            right = new HeldWebView(context);
            replacement = new HeldWebView(context);
            activity.webView = left;
            activity.rightWebView = right;
            activity.docType = "EPUB";
            activity.loadGeneration = 4;
            activity.primaryDocumentPageLoadGeneration = 7;
            activity.rightDocumentPageLoadGeneration = 9;
            activity.pages.add(new DocumentPageActivity.Page("Left", "", "left.xhtml"));
            activity.pages.add(new DocumentPageActivity.Page("Right", "", "right.xhtml"));
        }
    }

    private static final class Screen extends DocumentPageActivity {
        @Override int documentRightSpreadPageIndex() { return 1; }
    }

    private static final class HeldWebView extends WebView {
        final List<ValueCallback<String>> pending = new ArrayList<>();
        Throwable rejection;
        HeldWebView(Context context) { super(context); }
        @Override public void evaluateJavascript(String script, ValueCallback<String> callback) {
            if (rejection instanceof RuntimeException) throw (RuntimeException) rejection;
            if (rejection instanceof Error) throw (Error) rejection;
            pending.add(callback);
        }
        void complete() { completeAt(0); }
        void completeAt(int index) {
            ValueCallback<String> callback = pending.remove(index);
            assertNotNull(callback);
            callback.onReceiveValue("true");
        }
    }
}
