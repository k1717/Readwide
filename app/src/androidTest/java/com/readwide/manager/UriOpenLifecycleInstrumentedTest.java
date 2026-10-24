package com.readwide.manager;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Source-only lifecycle cases: no provider queries or reader launches are required. */
@RunWith(AndroidJUnit4.class)
public class UriOpenLifecycleInstrumentedTest {
    @Test public void acceptedLocalOpenInvalidatesQueuedUriButRejectedTapDoesNot() {
        check(screen -> {
            holdWorker(screen);
            screen.openFileFromUri(Uri.parse("content://example/old.txt"));
            int old = (Integer) get(screen, "uriOpenGeneration");
            Future<?> pending = (Future<?>) get(screen, "pendingUriOpen");
            assertFalse((Boolean) invoke(screen, "acceptFileOpenNow"));
            assertFalse(pending.isCancelled());
            set(screen, "lastFileOpenElapsedMs", 0L);
            // Both local and URI opens pass through this same admission funnel.
            assertTrue((Boolean) invoke(screen, "acceptFileOpenNow"));
            assertTrue(pending.isCancelled());
            assertEquals(0, screen.uriOpenExecutor.getQueue().size());
            screen.completeUriOpen(old, request());
            assertEquals(0, screen.launches);
        });
    }

    @Test public void newerUriKeepsOnlyNewestQueuedTaskAndOldCompletionCannotClearIt() {
        check(screen -> {
            holdWorker(screen);
            screen.openFileFromUri(Uri.parse("content://example/first.txt"));
            int old = (Integer) get(screen, "uriOpenGeneration");
            set(screen, "lastFileOpenElapsedMs", 0L);
            screen.openFileFromUri(Uri.parse("content://example/second.txt"));
            Future<?> latest = (Future<?>) get(screen, "pendingUriOpen");
            assertEquals(1, screen.uriOpenExecutor.getQueue().size());
            screen.completeUriOpen(old, null);
            assertSame(latest, get(screen, "pendingUriOpen"));
            assertEquals(0, screen.launches);
        });
    }

    @Test public void currentResultRoutesUnchangedAndDeadActivitiesCannotLaunch() {
        check(screen -> {
            UriOpenRequest request = request();
            screen.completeUriOpen(0, request);
            assertEquals(1, screen.launches);
            assertSame(request.intent, screen.launched);
            assertTrue((screen.launched.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
            screen.finishing = true;
            screen.completeUriOpen(0, request());
            screen.finishing = false;
            screen.frameworkDestroyed = true;
            screen.completeUriOpen(0, request());
            screen.frameworkDestroyed = false;
            screen.activityDestroyed = true;
            screen.completeUriOpen(0, request());
            assertEquals(1, screen.launches);
        });
    }

    @Test public void failedCurrentResolutionClearsPendingAndAllowsImmediateRetry() {
        check(screen -> {
            holdWorker(screen);
            screen.openFileFromUri(Uri.parse("content://example/first.txt"));
            int current = (Integer) get(screen, "uriOpenGeneration");
            screen.completeUriOpen(current, null);
            assertNull(get(screen, "pendingUriOpen"));
            assertEquals(0L, get(screen, "lastFileOpenElapsedMs"));
            assertTrue((Boolean) invoke(screen, "acceptFileOpenNow"));
        });
    }

    @Test public void rejectedSubmissionDoesNotLeavePendingStateOrDebounceLocked() {
        check(screen -> {
            screen.uriOpenExecutor.shutdownNow();
            screen.openFileFromUri(Uri.parse("content://example/first.txt"));
            assertNull(get(screen, "pendingUriOpen"));
            assertEquals(0L, get(screen, "lastFileOpenElapsedMs"));
            assertEquals(0, screen.launches);
        });
    }

    @Test public void mainPauseCancellationRejectsAlreadyPostedResultWhileActivityIsStillAlive() {
        check(screen -> {
            holdWorker(screen);
            screen.openFileFromUri(Uri.parse("content://example/first.txt"));
            int generation = (Integer) get(screen, "uriOpenGeneration");
            Future<?> pending = (Future<?>) get(screen, "pendingUriOpen");
            // Exercise the same cancellation called by onPause, without launching
            // another activity or invoking framework lifecycle on an unattached one.
            invoke(screen, "cancelPendingUriOpen");
            assertTrue(pending.isCancelled());
            assertNull(get(screen, "pendingUriOpen"));
            assertEquals(0, screen.uriOpenExecutor.getQueue().size());
            screen.completeUriOpen(generation, request());
            assertFalse(screen.activityDestroyed);
            assertFalse(screen.isFinishing());
            assertEquals(0, screen.launches);
        });
    }

    @Test public void safStaleCompletionCannotReleaseNewOwnerAndNavigationCancelsPending() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            SafBrowserActivity screen = new SafBrowserActivity();
            try {
                ((AtomicInteger) get(screen, "documentOpenGeneration")).set(4);
                set(screen, "openingDocument", true);
                java.util.concurrent.FutureTask<Void> pending = new java.util.concurrent.FutureTask<>(() -> null);
                set(screen, "pendingDocumentOpen", pending);
                screen.completeDocumentOpen(3, 0, null);
                assertEquals(true, get(screen, "openingDocument"));
                // Shared by folder navigation and onPause (Settings/Home/another reader).
                invoke(screen, "cancelPendingDocumentOpenForNavigation");
                assertTrue(pending.isCancelled());
                assertNull(get(screen, "pendingDocumentOpen"));
                assertEquals(false, get(screen, "openingDocument"));
                screen.completeDocumentOpen(4, 0, request());
                assertEquals(false, get(screen, "openingDocument"));
            } finally {
                ((java.util.concurrent.ExecutorService) get(screen, "queryExecutor")).shutdownNow();
                ((java.util.concurrent.ExecutorService) get(screen, "documentOpenExecutor")).shutdownNow();
            }
        });
    }

    private interface Check { void run(Screen screen); }
    private static void check(Check check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen();
            try { check.run(screen); }
            finally {
                screen.activityDestroyed = true;
                screen.uriOpenExecutor.shutdownNow();
                screen.fileSearchExecutor.shutdownNow();
                screen.fileOperationExecutor.shutdownNow();
                screen.folderValidationExecutor.shutdownNow();
                screen.fileSearchHandler.removeCallbacksAndMessages(null);
            }
        });
    }

    private static void holdWorker(Screen screen) {
        CountDownLatch entered = new CountDownLatch(1);
        screen.uriOpenExecutor.submit(() -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        });
        try { assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }

    private static UriOpenRequest request() {
        try {
            Constructor<UriOpenRequest> constructor = UriOpenRequest.class.getDeclaredConstructor(Intent.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(new Intent("test.reader"), false);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static Class<?> owner(Object screen) {
        return screen instanceof MainActivity ? MainActivity.class : SafBrowserActivity.class;
    }
    private static Object get(Object screen, String name) {
        try { Field field = owner(screen).getDeclaredField(name); field.setAccessible(true); return field.get(screen); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void set(Object screen, String name, Object value) {
        try { Field field = owner(screen).getDeclaredField(name); field.setAccessible(true); field.set(screen, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static Object invoke(Object screen, String name) {
        try { Method method = owner(screen).getDeclaredMethod(name); method.setAccessible(true); return method.invoke(screen); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static final class Screen extends MainActivity {
        int launches;
        boolean finishing;
        boolean frameworkDestroyed;
        Intent launched;
        @Override public Context getApplicationContext() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext();
        }
        @Override public boolean isFinishing() { return finishing; }
        @Override public boolean isDestroyed() { return frameworkDestroyed; }
        @Override public void startActivity(Intent intent) { launches++; launched = intent; }
    }
}
