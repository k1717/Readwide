package com.readwide.manager;

import android.content.Context;
import android.content.res.Resources;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Dispatch ownership only; scripts and delayed tasks are held, not rendered. */
@RunWith(AndroidJUnit4.class)
public class DocumentAutomaticScrollOwnershipInstrumentedTest {
    @Test public void currentDelayedSearchRevealInvalidatesBeforeEachDispatch() {
        check(f -> {
            f.search.applyDocumentSearchHighlightAfterPageLoad();
            assertEquals(3, f.view.delayed.size());
            f.view.runDelayed();
            assertEquals(3, f.activity.scripts.size());
            assertEquals(3, f.activity.invalidations);
            for (int i = 0; i < 3; i++) {
                assertEquals("invalidate", f.activity.events.get(i * 2));
                assertEquals("script", f.activity.events.get(i * 2 + 1));
            }
            assertEquals(5, f.activity.documentInteractionGeneration);
        });
    }

    @Test public void changedSearchOwnerDiscardsAllDelayedReveals() {
        for (Consumer<Fixture> change : ownerChanges()) {
            check(f -> {
                f.search.applyDocumentSearchHighlightAfterPageLoad();
                change.accept(f);
                f.view.runDelayed();
                assertTrue(f.activity.scripts.isEmpty());
                assertEquals(0, f.activity.invalidations);
                assertEquals(0, f.activity.reloads);
            });
        }
    }

    @Test public void newTouchAfterFirstRevealCancelsRemainingRetries() {
        check(f -> {
            f.search.applyDocumentSearchHighlightAfterPageLoad();
            f.view.delayed.remove(0).run();
            assertEquals(1, f.activity.scripts.size());
            f.activity.documentInteractionGeneration++;
            f.view.runDelayed();
            assertEquals(1, f.activity.scripts.size());
            assertEquals(1, f.activity.invalidations);
        });
    }

    @Test public void samePageMarkupSelectionDoesNotInvalidateUntilItActuallyReveals() {
        check(f -> {
            selectSamePage(f);
            assertEquals(1, f.activity.scripts.size());
            assertEquals(0, f.activity.invalidations);
            f.activity.complete(0, "true");
            assertEquals(2, f.activity.scripts.size());
            assertEquals(1, f.activity.invalidations);
            assertEquals("invalidate", f.activity.events.get(1));
            assertEquals("script", f.activity.events.get(2));
            assertEquals(0, f.activity.reloads);
        });
    }

    @Test public void changedSamePageOwnerCannotRevealOrReloadFromALateResult() {
        for (Consumer<Fixture> change : ownerChanges()) {
            for (String result : new String[] {"true", "false"}) {
                check(f -> {
                    selectSamePage(f);
                    change.accept(f);
                    f.activity.complete(0, result);
                    assertEquals(1, f.activity.scripts.size());
                    assertEquals(0, f.activity.invalidations);
                    assertEquals(0, f.activity.reloads);
                });
            }
        }
    }

    @Test public void currentMissingSearchMarkupStillUsesExistingReload() {
        check(f -> {
            selectSamePage(f);
            f.activity.complete(0, "false");
            assertEquals(1, f.activity.reloads);
            assertEquals(0, f.activity.invalidations);
        });
    }

    @Test public void inactiveSearchDoesNotInvalidateReadingPosition() {
        check(f -> {
            f.activity.activeDocumentSearchOrdinal = 0;
            f.search.scheduleDocumentSearchReveal();
            assertEquals(0, f.activity.invalidations);
            assertTrue(f.activity.scripts.isEmpty());
        });
    }

    @Test public void ttsScrollCapableHighlightInvalidatesWithoutAUserInteraction() {
        check(f -> {
            f.tts.highlight("Sentence", true, 0, location());
            assertEquals(1, f.activity.invalidations);
            assertEquals("invalidate", f.activity.events.get(0));
            assertEquals("script", f.activity.events.get(1));
            assertEquals(5, f.activity.documentInteractionGeneration);
        });
    }

    @Test public void ttsReadOnlyHighlightInstallAndClearDoNotInvalidatePosition() {
        check(f -> {
            f.tts.installScript();
            f.tts.highlight("Sentence", false, 0, location());
            f.tts.installScript(); // Replays the non-scrolling highlight.
            f.tts.clear();
            int before = f.activity.scripts.size();
            f.tts.installScript(); // No pending sentence remains after clear.
            assertEquals(before + 1, f.activity.scripts.size());
            assertEquals(0, f.activity.invalidations);
            assertEquals(5, f.activity.documentInteractionGeneration);
        });
    }

    @Test public void queuedTtsSentenceInvalidatesOnlyWhenMatchingPageReplaysIt() {
        check(f -> {
            f.tts.highlight("Sentence", true, 1, location());
            assertTrue(f.activity.scripts.isEmpty());
            assertEquals(0, f.activity.invalidations);
            f.tts.installScript();
            assertEquals(1, f.activity.scripts.size());
            assertEquals(0, f.activity.invalidations);
            f.activity.currentPage = 1;
            f.tts.installScript();
            assertEquals(3, f.activity.scripts.size());
            assertEquals(1, f.activity.invalidations);
            assertEquals("invalidate", f.activity.events.get(2));
            assertEquals("script", f.activity.events.get(3));
            assertEquals(5, f.activity.documentInteractionGeneration);
        });
    }

    private static List<Consumer<Fixture>> ownerChanges() {
        List<Consumer<Fixture>> changes = new ArrayList<>();
        changes.add(f -> f.activity.documentInteractionGeneration++);
        changes.add(f -> f.activity.loadGeneration++);
        changes.add(f -> f.activity.primaryDocumentPageLoadGeneration++);
        changes.add(f -> f.activity.documentAnchorPageGeneration++);
        changes.add(f -> f.activity.currentPage = 1);
        changes.add(f -> f.activity.activeDocumentSearchPage = 1);
        changes.add(f -> f.activity.activeDocumentSearchQuery = "changed");
        changes.add(f -> f.activity.activeDocumentSearchOrdinal = 2);
        changes.add(f -> f.activity.webView = f.replacement);
        changes.add(f -> f.activity.activityDestroyed = true);
        return changes;
    }

    private static DocumentTtsHighlightMath.TextLocation location() {
        return new DocumentTtsHighlightMath.TextLocation(0, 8, 0, "", "");
    }

    private static void selectSamePage(Fixture f) {
        try {
            Method method = DocumentSearchController.class.getDeclaredMethod(
                    "applySamePageDocumentSearchSelectionOrReload", TextView.class);
            method.setAccessible(true);
            method.invoke(f.search, (Object) null);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void check(Consumer<Fixture> test) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture();
            try { test.accept(f); } finally { f.close(); }
        });
    }

    private static final class Fixture {
        final Screen activity = new Screen();
        final DocumentSearchController search = new DocumentSearchController(activity);
        final DocumentTtsHighlightController tts = new DocumentTtsHighlightController(activity);
        final HeldView view;
        final HeldView replacement;
        Fixture() {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            view = new HeldView(context);
            replacement = new HeldView(context);
            activity.webView = view;
            activity.docType = "EPUB";
            activity.loadGeneration = 3;
            activity.primaryDocumentPageLoadGeneration = 7;
            activity.documentAnchorPageGeneration = 11;
            activity.documentInteractionGeneration = 5;
            activity.activeDocumentSearchQuery = "Sentence";
            activity.activeDocumentSearchPage = 0;
            activity.activeDocumentSearchOrdinal = 1;
            activity.activeDocumentSearchCountOnPage = 2;
            activity.activeDocumentSearchTotal = 2;
            activity.pages.add(new DocumentPageActivity.Page("First", "", "first.xhtml"));
            activity.pages.add(new DocumentPageActivity.Page("Second", "", "second.xhtml"));
            view.layout(0, 0, 600, 900);
        }
        void close() {
            activity.activityDestroyed = true;
            search.close();
            activity.executor.shutdownNow();
            view.delayed.clear();
            replacement.delayed.clear();
            view.destroy();
            replacement.destroy();
        }
    }

    private static final class Screen extends DocumentPageActivity {
        final List<String> scripts = new ArrayList<>();
        final List<ValueCallback<String>> callbacks = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        int invalidations, reloads;
        @Override public Resources getResources() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext().getResources();
        }
        @Override void invalidateDocumentContentPositionCapture() {
            super.invalidateDocumentContentPositionCapture();
            invalidations++;
            events.add("invalidate");
        }
        @Override void evaluateDocumentJavascript(WebView view, int page, String script,
                                                   ValueCallback<String> callback) {
            scripts.add(script);
            callbacks.add(callback);
            events.add("script");
        }
        @Override void reloadCurrentDocumentPreservingPosition() { reloads++; }
        void complete(int index, String value) {
            ValueCallback<String> callback = callbacks.get(index);
            assertNotNull(callback);
            callbacks.set(index, null);
            callback.onReceiveValue(value);
        }
    }

    private static final class HeldView extends WebView {
        final List<Runnable> delayed = new ArrayList<>();
        HeldView(Context context) { super(context); }
        @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) { }
        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { }
        @Override public boolean postDelayed(Runnable action, long delay) {
            if (delayed == null) return super.postDelayed(action, delay);
            delayed.add(action);
            return true;
        }
        void runDelayed() {
            while (!delayed.isEmpty()) delayed.remove(0).run();
        }
    }
}
