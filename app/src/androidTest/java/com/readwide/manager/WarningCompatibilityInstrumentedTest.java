package com.readwide.manager;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.core.view.WindowCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.util.EdgeToEdgeUtil;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Unattached window state only; this does not validate visible system bars or keyboard rendering. */
@RunWith(AndroidJUnit4.class)
public class WarningCompatibilityInstrumentedTest {
    @Test public void systemBarColorsLeaveWindowGeometryAndInsetHandlingUnchanged() {
        withWindow(f -> {
            Snapshot before = new Snapshot(f);
            int status = Color.rgb(19, 37, 61);
            int navigation = Color.rgb(67, 43, 29);
            EdgeToEdgeUtil.setSystemBarColors(f.window, status, navigation);
            before.assertPreserved(f, before.attributes.softInputMode);
            assertColorReadbackWhenSupported(f.window, status, navigation);
            f.assertInsetsListenerRetained();
        });
    }

    @Test public void readerContrastKeepsCutoutVisibilityAndLayoutPolicy() {
        withWindow(f -> {
            enableContrastWhenSupported(f.window);
            Snapshot before = new Snapshot(f);
            int navigation = Color.rgb(17, 31, 47);
            EdgeToEdgeUtil.setReaderBarContrast(f.window, navigation);
            before.assertPreserved(f, before.attributes.softInputMode);
            assertReaderContrastWhenSupported(f.window, navigation);
            f.assertInsetsListenerRetained();
        });
    }

    @Test public void resizeInputModePreservesEachRequestedKeyboardStateAndDialogGeometry() {
        withWindow(f -> {
            Snapshot before = new Snapshot(f);
            int[] states = {0, WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE};
            for (int state : states) {
                AdaptiveDialogLayoutHelper.setResizeSoftInputMode(f.window, state);
                before.assertPreserved(f, resizeWithState(state));
            }
            f.assertInsetsListenerRetained();
        });
    }

    @Test public void zoomObservationDoesNotProbeScaleOrChangeZoomAndScroll() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ProbeWebView view = new ProbeWebView(context());
            try {
                view.layout(0, 0, 600, 900);
                view.scrollTo(20, 150);
                assertTrue(DocumentWebViewController.canZoomOutForGesture(view));
                assertEquals(1, view.probes);
                assertFalse(DocumentWebViewController.canZoomOutForGesture(view));
                assertEquals(2, view.probes);
                assertEquals(0, view.zoomCalls);
                assertEquals(0, view.scaleReads);
                assertEquals(20, view.getScrollX());
                assertEquals(150, view.getScrollY());
            } finally { view.destroy(); }
        });
    }

    @SuppressWarnings("deprecation")
    private static int resizeWithState(int state) {
        return WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | state;
    }

    @SuppressWarnings("deprecation")
    private static void assertColorReadbackWhenSupported(Window window, int status, int navigation) {
        // Android 15+ can override these colors for target-35 edge-to-edge windows.
        if (Build.VERSION.SDK_INT < 35) {
            assertEquals(status, window.getStatusBarColor());
            assertEquals(navigation, window.getNavigationBarColor());
        }
    }

    @SuppressWarnings("deprecation")
    private static void enableContrastWhenSupported(Window window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(true);
            window.setNavigationBarContrastEnforced(true);
        }
    }

    @SuppressWarnings("deprecation")
    private static void assertReaderContrastWhenSupported(Window window, int navigation) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && Build.VERSION.SDK_INT < 35) {
            assertEquals(navigation, window.getNavigationBarDividerColor());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertFalse(window.isStatusBarContrastEnforced());
            assertFalse(window.isNavigationBarContrastEnforced());
        }
    }

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static void withWindow(Consumer<WindowFixture> check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WindowFixture f = new WindowFixture();
            try { check.accept(f); } finally { f.dialog.dismiss(); }
        });
    }

    private static final class WindowFixture {
        final Dialog dialog = new Dialog(context());
        final Window window = dialog.getWindow();
        final FrameLayout content = new FrameLayout(context());
        final View decor;
        int insetCalls;

        @SuppressWarnings("deprecation")
        WindowFixture() {
            assertNotNull(window);
            dialog.setContentView(content);
            decor = window.getDecorView();
            WindowCompat.setDecorFitsSystemWindows(window, false);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = 480;
            attributes.height = 640;
            attributes.x = 5;
            attributes.y = 23;
            attributes.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            attributes.screenBrightness = 0.42f;
            attributes.dimAmount = 0.31f;
            attributes.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            attributes.flags |= WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            window.setAttributes(attributes);
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            content.setFitsSystemWindows(true);
            content.setPadding(7, 11, 13, 17);
            content.layout(0, 0, 480, 640);
            content.setOnApplyWindowInsetsListener((view, insets) -> { insetCalls++; return insets; });
        }

        @SuppressWarnings("deprecation")
        void assertInsetsListenerRetained() {
            int before = insetCalls;
            content.dispatchApplyWindowInsets(new WindowInsets(new Rect(2, 3, 5, 7)));
            assertEquals(before + 1, insetCalls);
        }
    }

    private static final class Snapshot {
        final WindowManager.LayoutParams attributes = new WindowManager.LayoutParams();
        final int systemUiVisibility;
        final boolean fitsSystemWindows;

        @SuppressWarnings("deprecation")
        Snapshot(WindowFixture f) {
            attributes.copyFrom(f.window.getAttributes());
            systemUiVisibility = f.decor.getSystemUiVisibility();
            fitsSystemWindows = f.content.getFitsSystemWindows();
        }

        @SuppressWarnings("deprecation")
        void assertPreserved(WindowFixture f, int expectedSoftInputMode) {
            WindowManager.LayoutParams actual = f.window.getAttributes();
            assertEquals(attributes.flags, actual.flags);
            assertEquals(attributes.width, actual.width);
            assertEquals(attributes.height, actual.height);
            assertEquals(attributes.x, actual.x);
            assertEquals(attributes.y, actual.y);
            assertEquals(attributes.gravity, actual.gravity);
            assertEquals(attributes.type, actual.type);
            assertEquals(attributes.format, actual.format);
            assertEquals(attributes.windowAnimations, actual.windowAnimations);
            assertEquals(attributes.screenBrightness, actual.screenBrightness, 0f);
            assertEquals(attributes.dimAmount, actual.dimAmount, 0f);
            assertEquals(expectedSoftInputMode, actual.softInputMode);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                assertEquals(attributes.layoutInDisplayCutoutMode, actual.layoutInDisplayCutoutMode);
            }
            assertEquals(systemUiVisibility, f.decor.getSystemUiVisibility());
            assertEquals(fitsSystemWindows, f.content.getFitsSystemWindows());
            assertEquals(480, f.content.getWidth());
            assertEquals(640, f.content.getHeight());
            assertEquals(7, f.content.getPaddingLeft());
            assertEquals(11, f.content.getPaddingTop());
            assertEquals(13, f.content.getPaddingRight());
            assertEquals(17, f.content.getPaddingBottom());
        }
    }

    private static final class ProbeWebView extends WebView {
        int probes, zoomCalls, scaleReads;
        ProbeWebView(Context context) { super(context); }
        @Override @SuppressWarnings("deprecation") public boolean canZoomOut() { return ++probes == 1; }
        @Override @SuppressWarnings("deprecation") public float getScale() { scaleReads++; return 1f; }
        @Override public boolean zoomOut() { zoomCalls++; return false; }
        @Override public boolean zoomIn() { zoomCalls++; return true; }
    }
}
