package com.readwide.manager;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.util.PrefsManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Controlled DOM results test cache ownership without executing a renderer script. */
@RunWith(AndroidJUnit4.class)
public class DocumentAnchorCacheFreshnessInstrumentedTest {
    private static final String CAPTURE = "{\"anchorMode\":\"block\",\"blockIndex\":4,"
            + "\"scrollY\":150,\"maxScrollY\":900,\"text\":\"Stable sample paragraph.\"}";

    @Test public void acceptedCurrentCaptureCanBeReusedAtTheSameViewport() {
        check(f -> {
            f.capture();
            assertTrue(f.fresh(false));
            assertEquals(f.activity.lastDocumentContentAnchorJson, f.semantic());
            assertEquals(f.activity.lastDocumentContentAnchorJson, f.viewport().getString("anchor"));
        });
    }

    @Test public void geometryOnlyRotationKeepsSemanticButNotExactSaveCache() {
        check(f -> {
            f.capture();
            String anchor = f.activity.lastDocumentContentAnchorJson;
            f.view.layout(0, 0, 900, 600);
            assertFalse(f.fresh(false));
            assertEquals(anchor, f.semantic());
            assertEquals(anchor, f.viewport().getString("anchor"));
        });
    }

    @Test public void onePixelScrollInvalidatesCacheEvenAfterReturningToSameOffset() {
        check(f -> {
            f.capture();
            f.view.scrollTo(20, 151);
            f.activity.onDocumentContentNativeScroll(f.view);
            f.view.scrollTo(20, 150);
            assertFalse(f.fresh(false));
            assertEquals("", f.semantic());
            assertEquals(1, f.view.delayed.size());
        });
    }

    @Test public void fastScrollBeforeDebounceCannotReuseTheEarlierSentence() {
        check(f -> {
            f.capture();
            f.view.scrollTo(20, 800);
            assertFalse(f.fresh(false));
            assertEquals("", f.semantic());
            Bundle state = f.viewport();
            assertEquals("", state.getString("anchor"));
            assertEquals(800, state.getInt("nativeY"));
            assertEquals(20, state.getInt("nativeX"));
        });
    }

    @Test public void interactionWithoutNativeMotionStillRejectsEarlierCache() {
        check(f -> {
            f.capture();
            f.activity.documentInteractionGeneration++;
            assertFalse(f.fresh(false));
            assertEquals("", f.semantic());
        });
    }

    @Test public void scaleChangeRejectsCacheWithoutRequiringNativeScroll() {
        check(f -> {
            f.capture();
            f.view.scale = 1.25f;
            assertFalse(f.fresh(false));
            assertEquals("", f.semantic());
        });
    }

    @Test public void sameIndexDocumentReplacementRejectsItsLateCapture() {
        check(f -> {
            f.activity.updateDocumentContentAnchorFromWebView();
            f.activity.loadGeneration++;
            f.activity.complete(CAPTURE);
            assertEquals("", f.activity.lastDocumentContentAnchorJson);
            assertFalse(f.fresh(false));
        });
    }

    @Test public void sameIndexPaneReloadRejectsItsLateCapture() {
        check(f -> {
            f.activity.updateDocumentContentAnchorFromWebView();
            f.activity.primaryDocumentPageLoadGeneration++;
            f.activity.primaryDocumentPageReadyGeneration = f.activity.primaryDocumentPageLoadGeneration;
            f.activity.complete(CAPTURE);
            assertEquals("", f.activity.lastDocumentContentAnchorJson);
        });
    }

    @Test public void oldInstallContinuationCannotCaptureAnUnreadyReplacementDom() {
        check(f -> {
            f.activity.primaryDocumentPageLoadGeneration++;
            f.activity.updateDocumentContentAnchorFromWebView();
            assertNull(f.activity.pending);
            assertEquals("", f.activity.lastDocumentContentAnchorJson);
            assertFalse(f.viewport().containsKey("nativeX"));
            f.activity.primaryDocumentPageReadyGeneration = f.activity.primaryDocumentPageLoadGeneration;
            f.activity.primaryDocumentPageLoadPage = 1;
            f.activity.updateDocumentContentAnchorFromWebView();
            assertNull(f.activity.pending);
        });
    }

    @Test public void pendingExplicitAnchorRemainsAuthoritativeBeforePageReady() {
        check(f -> {
            f.activity.pendingDocumentRestoreAnchorJson = "explicit-restore-anchor";
            f.activity.primaryDocumentPageLoadGeneration++;
            Bundle state = f.viewport();
            assertEquals("explicit-restore-anchor", state.getString("anchor"));
            assertFalse(state.containsKey("nativeX"));
        });
    }

    @Test public void staleOutgoingSaveDoesNotPublishOldAnchorOrChapterStart() {
        check(f -> {
            f.capture();
            f.view.scrollTo(20, 800);
            assertNull(f.activity.bookmarkManager);
            // An accidental publication fails with the deliberately absent manager.
            f.activity.saveOutgoingDocumentReadingState();
            f.activity.activityDestroyed = true;
            f.activity.saveReadingState();
        });
    }

    @Test public void destroyedActivityCanStillReadAnOwnedUnmovedCache() {
        check(f -> {
            f.capture();
            f.activity.activityDestroyed = true;
            assertTrue(f.fresh(false));
        });
    }

    @Test public void preRestoreCaptureCannotRepopulateCacheAfterDomOnlyMovement() {
        check(f -> {
            f.capture();
            f.activity.updateDocumentContentAnchorFromWebView();
            ValueCallback<String> beforeRestore = f.activity.pending;
            f.activity.pending = null;
            invoke(f.activity, "evaluateDocumentAnchorRestoreJavascript",
                    new Class<?>[]{String.class, ValueCallback.class}, "restore", null);
            beforeRestore.onReceiveValue(CAPTURE.replace("Stable sample", "Earlier sample"));
            assertFalse(f.fresh(false));
            assertFalse(f.activity.lastDocumentContentAnchorJson.contains("Earlier sample"));
            assertEquals(20, f.view.getScrollX());
            assertEquals(150, f.view.getScrollY());
            f.activity.saveOutgoingDocumentReadingState();
        });
    }

    @Test public void successfulPostRestoreCaptureSurvivesLatePreRestoreCallback() {
        check(f -> {
            f.activity.updateDocumentContentAnchorFromWebView();
            ValueCallback<String> beforeRestore = f.activity.pending;
            f.activity.pending = null;
            invoke(f.activity, "evaluateDocumentAnchorRestoreJavascript",
                    new Class<?>[]{String.class, ValueCallback.class}, "restore", null);
            f.capture();
            String fresh = f.activity.lastDocumentContentAnchorJson;
            beforeRestore.onReceiveValue(CAPTURE.replace("Stable sample", "Earlier sample"));
            assertEquals(fresh, f.activity.lastDocumentContentAnchorJson);
            assertTrue(f.fresh(false));
        });
    }

    @Test public void completedDomRestoreCannotSaveItsInitialCacheBeforeRefresh() {
        check(f -> {
            f.capture();
            f.activity.pendingDocumentRestoreAnchorJson = f.activity.lastDocumentContentAnchorJson;
            f.activity.restoreDocumentContentAnchorAfterLoadIfNeeded(f.view);
            f.view.runNext();
            f.activity.complete("true");
            assertFalse(f.fresh(false));
            assertTrue(f.activity.canSaveDocumentReadingState());
            assertFalse(f.view.delayed.isEmpty());
            // No manager: publishing the old initial anchor here fails the test.
            f.activity.saveOutgoingDocumentReadingState();
        });
    }

    @Test public void latePreRestoreSaveCannotEraseANewerAcceptedCapture() {
        check(f -> {
            invoke(f.activity, "saveReadingStateWithFreshAnchor", new Class<?>[]{int.class}, 0);
            ValueCallback<String> beforeRestore = f.activity.pending;
            assertNotNull(beforeRestore);
            f.activity.pending = null;
            invoke(f.activity, "evaluateDocumentAnchorRestoreJavascript",
                    new Class<?>[]{String.class, ValueCallback.class}, "restore", null);
            f.capture();
            String fresh = f.activity.lastDocumentContentAnchorJson;
            beforeRestore.onReceiveValue(CAPTURE.replace("Stable sample", "Earlier sample"));
            assertEquals(fresh, f.activity.lastDocumentContentAnchorJson);
            assertTrue(f.fresh(false));
        });
    }

    @Test public void domOnlyMovementDuringReloadCaptureUsesItsBoundedFreshRetry() {
        check(f -> {
            int generation = f.activity.primaryDocumentPageLoadGeneration;
            f.activity.reloadCurrentDocumentPreservingPosition();
            f.activity.invalidateDocumentContentPositionCapture();
            f.activity.complete(CAPTURE.replace("Stable sample", "Earlier sample"));
            assertEquals(generation, f.activity.primaryDocumentPageLoadGeneration);
            assertNotNull(f.activity.pending);
            f.activity.complete(CAPTURE);
            assertEquals(generation + 1, f.activity.primaryDocumentPageLoadGeneration);
            assertTrue(f.viewport().getString("anchor", "").contains("Stable sample"));
            assertTrue(f.view.delayed.isEmpty());
        });
    }

    @Test public void pendingEmptyAnchorReloadKeepsCapturedNativePositionNotReplacementZero() {
        check(f -> {
            f.startNativeReload();
            assertEquals(0, f.view.getScrollY());
            Bundle state = f.viewport();
            assertEquals("", state.getString("anchor"));
            assertEquals(150, state.getInt("nativeY"));
            assertEquals(20, state.getInt("nativeX"));
        });
    }

    @Test public void inPlaceNativeReloadRetainsItsExistingResizeFallback() {
        check(f -> {
            f.startNativeReload();
            f.view.layout(0, 0, 900, 600);
            f.markReady();
            f.activity.restoreDocumentScrollAfterThemeRefreshIfNeeded(f.view);
            f.view.runNext();
            assertEquals(150, f.view.getScrollY());
            assertEquals(20, f.view.getScrollX());
        });
    }

    @Test public void compatibleRecreationNativeFallbackRestoresAndSettles() {
        check(f -> {
            f.prepareNativeRecreation(f.viewport());
            f.markReady();
            f.activity.restoreDocumentScrollAfterThemeRefreshIfNeeded(f.view);
            f.view.runNext();
            assertEquals(150, f.view.getScrollY());
            assertEquals(20, f.view.getScrollX());
            assertTrue(f.activity.canSaveDocumentReadingState());
        });
    }

    @Test public void incompatibleRecreationNativeFallbackDoesNotApplyOldPixels() {
        check(f -> {
            Bundle state = f.viewport();
            f.prepareNativeRecreation(state);
            f.view.layout(0, 0, 900, 600);
            f.markReady();
            f.activity.restoreDocumentScrollAfterThemeRefreshIfNeeded(f.view);
            f.view.runNext();
            assertEquals(0, f.view.getScrollY());
            assertFalse(f.activity.canSaveDocumentReadingState());
            assertEquals(150, f.viewport().getInt("nativeY"));
            f.activity.documentInteractionGeneration++;
            assertTrue(f.activity.canSaveDocumentReadingState());
        });
    }

    @Test public void themeReloadBeforeRecreationReadyCarriesNativeRestoreExactlyOnce() {
        check(f -> {
            f.prepareNativeRecreation(f.viewport());
            f.activity.reloadCurrentDocumentPreservingPosition();
            assertNull(f.activity.pending);
            assertEquals(150, f.viewport().getInt("nativeY"));
            f.markReady();
            f.activity.restoreDocumentScrollAfterThemeRefreshIfNeeded(f.view);
            f.view.runNext();
            assertEquals(150, f.view.getScrollY());
            assertTrue(f.activity.canSaveDocumentReadingState());
            assertTrue(f.view.delayed.isEmpty());
        });
    }

    private static Object invoke(DocumentPageActivity activity, String method, Class<?>[] types, Object... args) {
        try {
            Method target = DocumentPageActivity.class.getDeclaredMethod(method, types);
            target.setAccessible(true);
            return target.invoke(activity, args);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void check(Consumer<Fixture> action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            try { action.accept(fixture); }
            finally { fixture.close(); }
        });
    }

    private static final class Fixture {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String preferencesName = "document-anchor-cache-" + UUID.randomUUID();
        final Screen activity = new Screen();
        final HeldView view = new HeldView(context);

        Fixture() {
            activity.webView = view;
            activity.docType = "EPUB";
            activity.currentPage = 0;
            activity.filePath = "/readwide-test-only/cache.epub";
            activity.setIntent(new Intent().putExtra(DocumentPageActivity.EXTRA_FILE_PATH, activity.filePath));
            activity.loadGeneration = 4;
            activity.primaryDocumentPageLoadGeneration = 7;
            activity.documentAnchorPageGeneration = 11;
            activity.documentInteractionGeneration = 2;
            activity.documentLoadAccepted = true;
            activity.pages.add(new DocumentPageActivity.Page("Text", "", "OPS/text.xhtml"));
            markReady();
            view.layout(0, 0, 600, 900);
            view.scrollTo(20, 150);
            try {
                SharedPreferences store = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
                Context isolated = new ContextWrapper(context) {
                    @Override public Context getApplicationContext() { return this; }
                    @Override public SharedPreferences getSharedPreferences(String ignored, int mode) { return store; }
                };
                Constructor<PrefsManager> constructor = PrefsManager.class.getDeclaredConstructor(Context.class);
                constructor.setAccessible(true);
                activity.prefs = constructor.newInstance(isolated);
                activity.prefs.setAutoSavePosition(true);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }

        void capture() {
            activity.updateDocumentContentAnchorFromWebView();
            activity.complete(CAPTURE);
        }

        boolean fresh(boolean beforeReflow) {
            return (Boolean) invoke(activity, "documentContentAnchorCacheMatches",
                    new Class<?>[]{boolean.class}, beforeReflow);
        }

        String semantic() {
            return (String) invoke(activity, "cachedDocumentSemanticAnchorForCurrentPage", new Class<?>[0]);
        }

        Bundle viewport() {
            return (Bundle) invoke(activity, "captureDocumentViewport", new Class<?>[0]);
        }

        void startNativeReload() {
            activity.reloadCurrentDocumentPreservingPosition();
            activity.complete("null");
            assertTrue(view.delayed.isEmpty());
        }

        void prepareNativeRecreation(Bundle state) {
            assertTrue(state.containsKey("nativeX"));
            Bundle saved = new Bundle();
            saved.putBundle(DocumentPageActivity.STATE_DOCUMENT_VIEWPORT, state);
            activity.setIntent(activity.documentIntentForRecreation(saved));
            activity.beginDocumentViewportLoad();
            view.scrollTo(0, 0);
            activity.rememberOpeningDocumentViewport();
            activity.showPage(0, 0);
            assertFalse(activity.canSaveDocumentReadingState());
        }

        void markReady() {
            activity.primaryDocumentPageReadyGeneration = activity.primaryDocumentPageLoadGeneration;
            activity.primaryDocumentPageLoadPage = activity.currentPage;
        }

        void close() {
            activity.activityDestroyed = true;
            activity.pending = null;
            view.delayed.clear();
            view.destroy();
            activity.executor.shutdownNow();
            assertTrue(context.deleteSharedPreferences(preferencesName));
        }
    }

    private static final class Screen extends DocumentPageActivity {
        ValueCallback<String> pending;

        @Override void evaluateDocumentAnchorJavascript(String script, ValueCallback<String> callback) {
            assertNull(pending);
            pending = callback;
        }

        @Override void showPage(int page, int direction) {
            currentPage = page;
            documentAnchorPageGeneration++;
            primaryDocumentPageLoadGeneration++;
            primaryDocumentPageReadyGeneration = -1;
            primaryDocumentPageLoadPage = page;
            webView.scrollTo(0, 0);
        }

        void complete(String value) {
            ValueCallback<String> callback = pending;
            assertNotNull(callback);
            pending = null;
            callback.onReceiveValue(value);
        }
    }

    private static final class HeldView extends WebView {
        final List<Runnable> delayed = new ArrayList<>();
        float scale = 1f;
        HeldView(Context context) { super(context); }
        @Override @SuppressWarnings("deprecation") public float getScale() { return scale; }
        @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) { }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) { }
        @Override public boolean postDelayed(Runnable action, long delayMillis) { delayed.add(action); return true; }
        @Override public boolean removeCallbacks(Runnable action) { delayed.removeIf(queued -> queued == action); return true; }
        void runNext() { assertFalse(delayed.isEmpty()); delayed.remove(0).run(); }
    }
}
