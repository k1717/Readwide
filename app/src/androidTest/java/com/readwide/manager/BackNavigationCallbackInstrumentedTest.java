package com.readwide.manager;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.OnBackPressedDispatcher;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.util.PrefsManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Deque;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;

/** Production callbacks and AndroidX dispatch only; no Activity, reader or archive is launched. */
@RunWith(AndroidJUnit4.class)
public class BackNavigationCallbackInstrumentedTest {
    @Test public void archiveBackWalksOneFolderBeforeDefaultDispatch() {
        onMain(() -> {
            ArchiveScreen screen = new ArchiveScreen();
            try (Route route = new Route(screen, ArchiveBrowserActivity.class)) {
                prepareArchiveViews(screen);
                set(screen, ArchiveBrowserActivity.class, "currentPrefix", "series/chapter/");
                route.press();
                assertEquals("series/", get(screen, ArchiveBrowserActivity.class, "currentPrefix"));
                assertEquals(0, route.defaultCalls);
                route.press();
                assertEquals("", get(screen, ArchiveBrowserActivity.class, "currentPrefix"));
                assertEquals(0, route.defaultCalls);
                route.press();
                assertEquals(1, route.defaultCalls);
                assertTrue(route.callback.isEnabled());
            } finally { closeArchive(screen); }
        });
    }

    @Test public void archiveBackRestoresTheParentFolderSortState() {
        onMain(() -> {
            ArchiveScreen screen = new ArchiveScreen();
            try (Route route = new Route(screen, ArchiveBrowserActivity.class)) {
                prepareArchiveViews(screen);
                set(screen, ArchiveBrowserActivity.class, "currentPrefix", "series/chapter/");
                set(screen, ArchiveBrowserActivity.class, "archiveSortMode", PrefsManager.SORT_NAME_ASC);
                pushArchiveSortState(screen, "series/", PrefsManager.SORT_NAME_DESC);
                route.press();
                assertEquals("series/", get(screen, ArchiveBrowserActivity.class, "currentPrefix"));
                assertEquals(PrefsManager.SORT_NAME_DESC,
                        get(screen, ArchiveBrowserActivity.class, "archiveSortMode"));
                assertEquals(0, route.defaultCalls);
            } finally { closeArchive(screen); }
        });
    }

    @Test public void documentBackClosesSearchBeforeDelegatingTheNextPress() {
        onMain(() -> {
            DocumentScreen screen = new DocumentScreen();
            try (Route route = new Route(screen, DocumentPageActivity.class)) {
                screen.documentSearchPanelContainer = new FrameLayout(context());
                route.press();
                assertEquals(1, screen.searchCloses);
                assertTrue(screen.saveQuery);
                assertTrue(screen.clearWebView);
                assertEquals(0, route.defaultCalls);
                route.press();
                assertEquals(1, route.defaultCalls);
                assertEquals(1, screen.searchCloses);
            } finally { screen.executor.shutdownNow(); }
        });
    }

    @Test public void documentOverlaySearchAlsoConsumesBack() {
        onMain(() -> {
            DocumentScreen screen = new DocumentScreen();
            try (Route route = new Route(screen, DocumentPageActivity.class)) {
                screen.documentSearchOverlayContainer = new FrameLayout(context());
                route.press();
                assertEquals(1, screen.searchCloses);
                assertTrue(screen.saveQuery && screen.clearWebView);
                assertEquals(0, route.defaultCalls);
            } finally { screen.executor.shutdownNow(); }
        });
    }

    @Test public void unlockBackFinishesTheTaskWithoutDefaultNavigation() {
        onMain(() -> {
            LockScreen screen = new LockScreen();
            try (Route route = new Route(screen, LockActivity.class)) {
                set(screen, LockActivity.class, "mode", LockActivity.MODE_UNLOCK);
                route.press();
                assertEquals(1, screen.affinityFinishes);
                assertEquals(0, route.defaultCalls);
                assertTrue(route.callback.isEnabled());
            }
        });
    }

    @Test public void bothPinEditingModesKeepDefaultBackNavigation() {
        onMain(() -> {
            for (int mode : new int[] { LockActivity.MODE_SET_PIN, LockActivity.MODE_CHANGE_PIN }) {
                LockScreen screen = new LockScreen();
                try (Route route = new Route(screen, LockActivity.class)) {
                    set(screen, LockActivity.class, "mode", mode);
                    route.press();
                    assertEquals(0, screen.affinityFinishes);
                    assertEquals(1, route.defaultCalls);
                    assertTrue(route.callback.isEnabled());
                }
            }
        });
    }

    @Test public void defaultDispatchFailureDoesNotDisableLaterBackHandling() {
        onMain(() -> {
            ComponentActivity[] screens = { new ArchiveScreen(), new DocumentScreen(), new LockScreen() };
            Class<?>[] types = { ArchiveBrowserActivity.class, DocumentPageActivity.class, LockActivity.class };
            try {
                set(screens[2], LockActivity.class, "mode", LockActivity.MODE_SET_PIN);
                for (int i = 0; i < screens.length; i++) {
                    try (Route route = new Route(screens[i], types[i])) {
                        route.failDefault = true;
                        try {
                            route.press();
                            fail("Expected the injected default-dispatch failure");
                        } catch (IllegalStateException expected) {
                            assertEquals("default route", expected.getMessage());
                        }
                        assertTrue(route.callback.isEnabled());
                        route.failDefault = false;
                        route.press();
                        assertEquals(2, route.defaultCalls);
                    }
                }
            } finally {
                closeArchive((ArchiveScreen) screens[0]);
                ((DocumentScreen) screens[1]).executor.shutdownNow();
            }
        });
    }

    @Test public void lifecycleStopsAndRemovesBackInterception() {
        onMain(() -> {
            LockScreen screen = new LockScreen();
            try (Route route = new Route(screen, LockActivity.class)) {
                set(screen, LockActivity.class, "mode", LockActivity.MODE_UNLOCK);
                route.press();
                assertEquals(1, screen.affinityFinishes);
                route.owner.registry.setCurrentState(Lifecycle.State.CREATED);
                route.press();
                assertEquals(1, screen.affinityFinishes);
                assertEquals(1, route.defaultCalls);
                route.owner.registry.setCurrentState(Lifecycle.State.STARTED);
                route.press();
                assertEquals(2, screen.affinityFinishes);
                route.owner.registry.setCurrentState(Lifecycle.State.DESTROYED);
                route.press();
                assertEquals(2, screen.affinityFinishes);
                assertEquals(2, route.defaultCalls);
            }
        });
    }

    private static void onMain(Runnable check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(check);
    }

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static Object get(Object target, Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void set(Object target, Class<?> owner, String name, Object value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void prepareArchiveViews(ArchiveScreen screen) {
        set(screen, ArchiveBrowserActivity.class, "pathText", new TextView(context()));
        set(screen, ArchiveBrowserActivity.class, "emptyText", new TextView(context()));
        set(screen, ArchiveBrowserActivity.class, "recyclerView", new RecyclerView(context()));
        try {
            Class<?> type = Class.forName(ArchiveBrowserActivity.class.getName() + "$EntryAdapter");
            Constructor<?> constructor = type.getDeclaredConstructor(
                    ArchiveBrowserActivity.class, int.class, int.class, int.class);
            constructor.setAccessible(true);
            set(screen, ArchiveBrowserActivity.class, "adapter", constructor.newInstance(screen, 0, 0, 0));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    @SuppressWarnings("unchecked")
    private static void pushArchiveSortState(ArchiveScreen screen, String prefix, int sortMode) {
        try {
            Class<?> type = Class.forName(ArchiveBrowserActivity.class.getName() + "$ArchiveFolderSortState");
            Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class);
            constructor.setAccessible(true);
            ((Deque<Object>) get(screen, ArchiveBrowserActivity.class, "archiveFolderSortStack"))
                    .push(constructor.newInstance(prefix, sortMode));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void closeArchive(ArchiveScreen screen) {
        ((ExecutorService) get(screen, ArchiveBrowserActivity.class, "executor")).shutdownNow();
    }

    private static final class Route implements AutoCloseable {
        final TestOwner owner = new TestOwner();
        final OnBackPressedDispatcher dispatcher;
        final OnBackPressedCallback callback;
        final OnBackPressedCallback fallback;
        int defaultCalls;
        boolean failDefault;

        Route(ComponentActivity screen, Class<?> type) {
            dispatcher = screen.getOnBackPressedDispatcher();
            callback = (OnBackPressedCallback) get(screen, type, "backCallback");
            fallback = new OnBackPressedCallback(true) {
                @Override public void handleOnBackPressed() {
                    defaultCalls++;
                    if (failDefault) throw new IllegalStateException("default route");
                }
            };
            dispatcher.addCallback(fallback);
            // Drive only the callback's lifecycle, not an unattached Activity's framework hooks.
            dispatcher.addCallback(owner, callback);
            owner.registry.setCurrentState(Lifecycle.State.STARTED);
        }

        void press() { dispatcher.onBackPressed(); }

        @Override public void close() {
            owner.registry.setCurrentState(Lifecycle.State.DESTROYED);
            callback.remove();
            fallback.remove();
        }
    }

    private static final class TestOwner implements LifecycleOwner {
        final LifecycleRegistry registry = new LifecycleRegistry(this);
        @Override public Lifecycle getLifecycle() { return registry; }
    }

    private static final class ArchiveScreen extends ArchiveBrowserActivity {
        @Override public Resources getResources() { return context().getResources(); }
    }

    private static final class DocumentScreen extends DocumentPageActivity {
        int searchCloses;
        boolean saveQuery;
        boolean clearWebView;
        @Override void hideDocumentSearchPanel(boolean saveQuery, boolean clearWebView) {
            searchCloses++;
            this.saveQuery = saveQuery;
            this.clearWebView = clearWebView;
            if (documentSearchPanelContainer != null) documentSearchPanelContainer.setVisibility(View.GONE);
            if (documentSearchOverlayContainer != null) documentSearchOverlayContainer.setVisibility(View.GONE);
        }
    }

    private static final class LockScreen extends LockActivity {
        int affinityFinishes;
        @Override public void finishAffinity() { affinityFinishes++; }
    }
}
