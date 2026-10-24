package com.readwide.manager;

import android.app.Dialog;
import android.content.Context;
import android.view.Window;
import android.view.WindowManager;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Window-policy regressions; visible system-bar behavior still needs a device check. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 28)
public class ReaderWindowPolicyInstrumentedTest {
    @Test public void shortEdgePolicyIsInstalledBeforeReaderContent() {
        onMain(() -> {
            WindowReader activity = reader();
            try {
                WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
                attributes.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
                attributes.screenBrightness = 0.42f;
                attributes.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
                activity.getWindow().setAttributes(attributes);

                new ReaderActivityStartupController(activity).configureWindow();

                assertEquals(R.layout.activity_reader, activity.contentResource);
                assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                        activity.cutoutModeAtContent);
                assertEquals(0.42f, activity.getWindow().getAttributes().screenBrightness, 0f);
                assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
                        activity.getWindow().getAttributes().softInputMode);
            } finally { close(activity); }
        });
    }

    @Test public void statusBarRequestsDoNotSwitchTheWindowCutoutPolicy() {
        onMain(() -> {
            WindowReader activity = reader();
            try {
                new ReaderActivityStartupController(activity).configureWindow();
                Window window = activity.getWindow();
                WindowInsetsControllerCompat controller =
                        WindowCompat.getInsetsController(window, window.getDecorView());
                for (boolean visible : new boolean[]{true, false, true, false}) {
                    if (visible) controller.show(WindowInsetsCompat.Type.statusBars());
                    else controller.hide(WindowInsetsCompat.Type.statusBars());
                    assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                            window.getAttributes().layoutInDisplayCutoutMode);
                }
            } finally { close(activity); }
        });
    }

    private static WindowReader reader() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        WindowReader activity = new WindowReader();
        // An unattached real Window checks the attributes without launching a
        // file or changing the saved reading preferences.
        activity.windowOwner = new Dialog(context);
        assertNotNull(activity.windowOwner.getWindow());
        return activity;
    }

    private static void onMain(Runnable check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(check);
    }

    private static void close(WindowReader activity) {
        activity.handler.removeCallbacksAndMessages(null);
        activity.executor.shutdownNow();
        activity.largeTextPartitionExecutor.shutdownNow();
        activity.largeTextSearchExecutor.shutdownNow();
        activity.largeTextSearchCountExecutor.shutdownNow();
        activity.windowOwner.dismiss();
    }

    private static final class WindowReader extends ReaderActivity {
        Dialog windowOwner;
        int contentResource;
        int cutoutModeAtContent;

        @Override public Window getWindow() { return windowOwner.getWindow(); }

        @Override public void setContentView(int layoutResource) {
            contentResource = layoutResource;
            cutoutModeAtContent = getWindow().getAttributes().layoutInDisplayCutoutMode;
        }
    }
}
