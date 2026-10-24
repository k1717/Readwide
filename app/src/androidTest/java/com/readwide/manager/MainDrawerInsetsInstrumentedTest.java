package com.readwide.manager;

import android.content.Context;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.WindowInsets;

import androidx.appcompat.widget.Toolbar;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Calls only the installed inset listener; no Activity, keyboard or drawer is opened. */
@RunWith(AndroidJUnit4.class)
public class MainDrawerInsetsInstrumentedTest {
    @Test public void listenerKeepsExactTopBottomAndDoesNotConsumeInsets() {
        check(screen -> {
            WindowInsets input = legacyInsets(7, 24, 9, 48);
            WindowInsets returned = screen.drawer.listener.onApplyWindowInsets(screen.drawer, input);
            assertSame(input, returned);
            assertEquals(24, screen.drawerTopInsetPx);
            assertEquals(48, screen.drawerBottomInsetPx);
            assertEquals(11, screen.drawer.getPaddingLeft());
            assertEquals(24, screen.drawer.getPaddingTop());
            assertEquals(13, screen.drawer.getPaddingRight());
            assertEquals(48, screen.drawer.getPaddingBottom());
            assertEquals(1, screen.themeUpdates);
            assertEquals(1, screen.drawer.insetRequests);
        });
    }

    @Test public void combinedKeyboardBottomIsNotReducedToTheNavigationBar() {
        check(screen -> {
            WindowInsets input = legacyInsets(0, 32, 0, 720);
            assertSame(input, screen.drawer.listener.onApplyWindowInsets(screen.drawer, input));
            assertEquals(32, screen.drawerTopInsetPx);
            assertEquals(720, screen.drawerBottomInsetPx);
            assertEquals(720, screen.drawer.getPaddingBottom());
        });
    }

    @Test public void repeatedInsetsDoNotReapplyPaddingOrTheme() {
        check(screen -> {
            WindowInsets input = legacyInsets(0, 24, 0, 48);
            screen.drawer.listener.onApplyWindowInsets(screen.drawer, input);
            int paddingUpdates = screen.drawer.paddingUpdates;
            screen.drawer.listener.onApplyWindowInsets(screen.drawer, input);
            assertEquals(paddingUpdates, screen.drawer.paddingUpdates);
            assertEquals(1, screen.themeUpdates);
            screen.drawer.listener.onApplyWindowInsets(screen.drawer, legacyInsets(0, 24, 0, 400));
            assertEquals(paddingUpdates + 1, screen.drawer.paddingUpdates);
            assertEquals(2, screen.themeUpdates);
        });
    }

    @Test public void nullInsetsResetVerticalPaddingWithoutChangingHorizontalPadding() {
        check(screen -> {
            screen.drawer.listener.onApplyWindowInsets(screen.drawer, legacyInsets(0, 24, 0, 400));
            assertNull(screen.drawer.listener.onApplyWindowInsets(screen.drawer, null));
            assertEquals(0, screen.drawerTopInsetPx);
            assertEquals(0, screen.drawerBottomInsetPx);
            assertEquals(0, screen.drawer.getPaddingTop());
            assertEquals(0, screen.drawer.getPaddingBottom());
            assertEquals(11, screen.drawer.getPaddingLeft());
            assertEquals(13, screen.drawer.getPaddingRight());
            assertEquals(2, screen.themeUpdates);
        });
    }

    @Test public void changedViewPaddingIsRepairedWithoutAnUnchangedMetricThemeRefresh() {
        check(screen -> {
            WindowInsets input = legacyInsets(0, 24, 0, 48);
            screen.drawer.listener.onApplyWindowInsets(screen.drawer, input);
            screen.drawer.setPadding(17, 1, 19, 2);
            screen.drawer.listener.onApplyWindowInsets(screen.drawer, input);
            assertEquals(17, screen.drawer.getPaddingLeft());
            assertEquals(19, screen.drawer.getPaddingRight());
            assertEquals(24, screen.drawer.getPaddingTop());
            assertEquals(48, screen.drawer.getPaddingBottom());
            assertEquals(1, screen.themeUpdates);
        });
    }

    @SuppressWarnings("deprecation")
    private static WindowInsets legacyInsets(int left, int top, int right, int bottom) {
        // This constructor models the combined legacy insets used before typed
        // status/navigation/IME queries; it remains available at minSdk 24.
        return new WindowInsets(new Rect(left, top, right, bottom));
    }

    private static void check(Consumer<Screen> assertion) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Screen screen = new Screen(context);
            try {
                MainDrawerController controller = new MainDrawerController(screen);
                Method setup = MainDrawerController.class.getDeclaredMethod("setupDrawerSystemInsets");
                setup.setAccessible(true);
                setup.invoke(controller);
                assertNotNull(screen.drawer.listener);
                assertion.accept(screen);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            } finally {
                screen.fileSearchHandler.removeCallbacksAndMessages(null);
                screen.uriOpenExecutor.shutdownNow();
                screen.fileSearchExecutor.shutdownNow();
                screen.fileOperationExecutor.shutdownNow();
                screen.folderValidationExecutor.shutdownNow();
            }
        });
    }

    private static final class Screen extends MainActivity {
        final InsetView drawer;
        final Toolbar toolbar;
        int themeUpdates;

        Screen(Context context) {
            drawer = new InsetView(context);
            drawer.setPadding(11, 2, 13, 3);
            drawer.paddingUpdates = 0;
            toolbar = new Toolbar(new ContextThemeWrapper(context, R.style.Theme_TextViewReader));
        }

        @Override @SuppressWarnings("unchecked") public <T extends View> T findViewById(int id) {
            if (id == R.id.nav_drawer) return (T) drawer;
            if (id == R.id.toolbar) return (T) toolbar;
            return null;
        }

        @Override void applyMainReadableTheme(Toolbar toolbar) { themeUpdates++; }
    }

    private static final class InsetView extends View {
        OnApplyWindowInsetsListener listener;
        int insetRequests;
        int paddingUpdates;

        InsetView(Context context) { super(context); }

        @Override public void setOnApplyWindowInsetsListener(OnApplyWindowInsetsListener listener) {
            this.listener = listener;
        }

        @Override public void requestApplyInsets() { insetRequests++; }

        @Override public void setPadding(int left, int top, int right, int bottom) {
            super.setPadding(left, top, right, bottom);
            paddingUpdates++;
        }
    }
}
