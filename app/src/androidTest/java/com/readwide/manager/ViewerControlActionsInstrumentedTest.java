package com.readwide.manager;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;

/** Unattached control fixtures; no PDF decoding, Activity startup or saved-position writes. */
@RunWith(AndroidJUnit4.class)
public class ViewerControlActionsInstrumentedTest {
    @Test public void pdfMenuUsesDisplayedZoomAndResetsWithoutChangingPageOrLegacyZoom() {
        onMain(() -> {
            try (PdfFixture f = new PdfFixture()) {
                f.legacyZoom(4f); // deliberately unlike the displayed fit matrix
                f.menu(0.2f);
                assertEquals(1.2f, f.anchor()[2], 0.001f);
                f.menu(0.2f);
                assertEquals(1.4f, f.anchor()[2], 0.001f);
                f.view.restoreViewportAnchor(0.6f, 0.4f, 2.5f, true);
                float[] before = f.anchor();
                f.menu(-0.2f);
                float[] after = f.anchor();
                assertEquals(2.3f, after[2], 0.001f);
                assertEquals(before[0], after[0], 0.001f);
                assertEquals(before[1], after[1], 0.001f);
                invoke(f.host, PdfReaderActivity.class, "resetZoomToOriginal");
                assertEquals(1f, f.anchor()[2], 0.001f);
                assertEquals(0.5f, f.anchor()[0], 0.001f);
                assertEquals(0.5f, f.anchor()[1], 0.001f);
                assertEquals(4f, f.legacyZoom(), 0f);
                assertEquals(3, f.host.currentPage);
                assertEquals(0, f.host.moves);
            }
        });
    }

    @Test public void pdfControlZoomKeepsGestureLimitsAndRejectsUnavailablePage() {
        onMain(() -> {
            try (PdfFixture f = new PdfFixture()) {
                // fitScale = 0.5: retain the existing absolute matrix scale limit,
                // not a new relative-to-fit maximum.
                f.view.layout(0, 0, 200, 300);
                f.view.setMaxScale(2f);
                f.view.changeZoomFromControls(100f);
                assertEquals(4f, f.anchor()[2], 0.001f);
                f.view.changeZoomFromControls(Float.NaN);
                f.view.changeZoomFromControls(Float.POSITIVE_INFINITY);
                assertEquals(4f, f.anchor()[2], 0.001f);
                f.view.changeZoomFromControls(-100f);
                assertEquals(1f, f.anchor()[2], 0.001f);
                f.view.detachBitmaps();
                f.view.changeZoomFromControls(0.2f);
                f.view.resetZoomFromControls();
                assertNull(f.view.captureViewportAnchor());
                assertFalse(f.bitmap.isRecycled()); // fit bitmap remains host-owned
            }
        });
    }

    @Test public void pdfMenuZoomSurvivesPendingViewportResizeAndResetStaysAtFit() {
        onMain(() -> {
            try (PdfFixture f = new PdfFixture()) {
                f.view.restoreViewportAnchor(0.5f, 0.5f, 2f, true);
                f.view.prepareForViewportResize();
                f.menu(0.2f);
                f.view.layout(0, 0, 300, 500);
                assertEquals(2.2f, f.anchor()[2], 0.001f);
                f.view.prepareForViewportResize();
                invoke(f.host, PdfReaderActivity.class, "resetZoomToOriginal");
                f.view.layout(0, 0, 400, 600);
                assertEquals(1f, f.anchor()[2], 0.001f);
            }
        });
    }

    @Test public void continuousPdfMenuKeepsLegacyZoomAndDoesNotChangeMatrix() {
        onMain(() -> {
            try (PdfFixture f = new PdfFixture()) {
                f.host.verticalPageSlideMode = true;
                f.legacyZoom(1.2f);
                f.menu(0.2f);
                assertEquals(1.4f, f.legacyZoom(), 0.001f);
                f.menu(-100f);
                assertEquals(0.55f, f.legacyZoom(), 0.001f);
                f.menu(100f);
                assertEquals(4.5f, f.legacyZoom(), 0.001f);
                invoke(f.host, PdfReaderActivity.class, "resetZoomToOriginal");
                assertEquals(1f, f.legacyZoom(), 0.001f);
                assertEquals(1f, f.anchor()[2], 0.001f);
            }
        });
    }

    @Test public void pdfAccessibleSliderCommitsOnceAndProgrammaticUpdatesDoNotMove() {
        onMain(() -> {
            try (PdfFixture f = new PdfFixture()) {
                f.slider.setProgress(2);
                assertEquals(0, f.host.moves);
                setAccessibleProgress(f.slider, 7);
                assertEquals(1, f.host.moves);
                assertEquals(7, f.host.lastTarget);
                assertEquals(1, f.host.lastDirection);
                assertFalse(f.host.pdfPageSeekBarUserTracking);
                f.slider.setProgress(4);
                assertEquals(1, f.host.moves);
                setAccessibleProgress(f.slider, 1);
                assertEquals(2, f.host.moves);
                assertEquals(-1, f.host.lastDirection);
            }
        });
    }

    @Test public void pdfSliderDragPreviewsThenCommitsOnlyOnRelease() {
        onMain(() -> {
            try (PdfFixture f = new PdfFixture()) {
                long time = SystemClock.uptimeMillis();
                touch(f.slider, MotionEvent.ACTION_DOWN, time, time, 100);
                touch(f.slider, MotionEvent.ACTION_MOVE, time, time + 20, 300);
                assertTrue(f.host.pdfPageSeekBarUserTracking);
                assertEquals(0, f.host.moves);
                touch(f.slider, MotionEvent.ACTION_UP, time, time + 40, 300);
                assertFalse(f.host.pdfPageSeekBarUserTracking);
                assertEquals(1, f.host.moves);
                assertEquals(f.slider.getProgress(), f.host.lastTarget);
            }
        });
    }

    @Test public void pdfEmptySliderDoesNotCommitAccessibilityChange() {
        onMain(() -> {
            try (PdfFixture f = new PdfFixture()) {
                f.host.pageCount = 0;
                setAccessibleProgress(f.slider, 7);
                assertEquals(0, f.host.moves);
            }
        });
    }

    @Test public void imageAccessibleSliderCommitsOnceInBothDirections() {
        onMain(() -> {
            try (ImageFixture f = new ImageFixture()) {
                for (int direction : new int[] {
                        com.readwide.manager.util.PrefsManager.IMAGE_SLIDER_DIRECTION_LTR,
                        com.readwide.manager.util.PrefsManager.IMAGE_SLIDER_DIRECTION_RTL}) {
                    f.controller.setSliderDirection(direction);
                    f.controller.update(0, 10, true);
                    int before = f.moves;
                    setAccessibleProgress(f.slider, 7);
                    assertEquals(before + 1, f.moves);
                    assertEquals(7, f.lastTarget);
                    f.controller.update(3, 10, true);
                    assertEquals(before + 1, f.moves);
                }
            }
        });
    }

    @Test public void imageSliderDragDefersDecodeAndUsesLatestSequenceBounds() {
        onMain(() -> {
            try (ImageFixture f = new ImageFixture()) {
                long time = SystemClock.uptimeMillis();
                touch(f.slider, MotionEvent.ACTION_DOWN, time, time, 100);
                touch(f.slider, MotionEvent.ACTION_MOVE, time, time + 20, 350);
                assertEquals(0, f.moves);
                f.controller.update(0, 3, true);
                assertEquals(0, f.moves);
                touch(f.slider, MotionEvent.ACTION_UP, time, time + 40, 350);
                assertEquals(1, f.moves);
                assertEquals(2, f.lastTarget);
                assertEquals(2, f.slider.getMax());
            }
        });
    }

    @Test public void imageEmptySequenceDoesNotCommitAccessibleSliderChange() {
        onMain(() -> {
            try (ImageFixture f = new ImageFixture()) {
                f.controller.update(0, 0, true);
                f.slider.setMax(9); // force a stale control event against an empty sequence
                setAccessibleProgress(f.slider, 7);
                assertEquals(0, f.moves);
            }
        });
    }

    @Test public void imageSpreadCounterMatchesVisiblePagesWithoutMoving() {
        onMain(() -> {
            try (ImageFixture f = new ImageFixture()) {
                f.controller.update(2, 10, true, true);
                String pair = String.format(java.util.Locale.getDefault(), "%d–%d / %d", 3, 4, 10);
                assertEquals(pair, f.label.getText().toString());
                f.controller.update(9, 10, true, true);
                assertEquals(String.format(java.util.Locale.getDefault(), "%d / %d", 10, 10),
                        f.label.getText().toString());
                assertEquals(0, f.moves);
            }
        });
    }

    @Test public void imageCounterOpensJumpOnlyForAnAvailableSequence() {
        onMain(() -> {
            try (ImageFixture f = new ImageFixture()) {
                f.label.performClick();
                assertEquals(1, f.host.jumpRequests);
                f.controller.update(0, 1, true);
                f.label.performClick();
                assertEquals(1, f.host.jumpRequests);
                assertEquals(0, f.moves);
            }
        });
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static Context context() {
        return new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.Theme_TextViewReader);
    }

    private static void setAccessibleProgress(SeekBar slider, int target) {
        Bundle args = new Bundle();
        args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, target);
        assertTrue(slider.performAccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), args));
        assertEquals(target, slider.getProgress());
    }

    private static void touch(SeekBar slider, int action, long down, long time, float x) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, 30f, 0);
        try { assertTrue(slider.onTouchEvent(event)); }
        finally { event.recycle(); }
    }

    private static void invoke(Object target, Class<?> owner, String name) {
        try {
            Method method = owner.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void stopWorkers(Object host, Class<?> owner, String... names) {
        for (String name : names) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(host);
                if (value instanceof ExecutorService) ((ExecutorService) value).shutdownNow();
                if (value instanceof Handler) ((Handler) value).removeCallbacksAndMessages(null);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
    }

    private static final class PdfHost extends PdfReaderActivity {
        int moves, lastTarget, lastDirection;
        @Override void goToPage(int page, int direction) {
            moves++; lastTarget = page; lastDirection = direction;
        }
        @Override boolean isPdfTwoPageSpreadMode() { return false; }
    }

    private static final class PdfFixture implements AutoCloseable {
        final PdfHost host = new PdfHost();
        final PdfPageView view = new PdfPageView(context());
        final Bitmap bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888);
        final SeekBar slider = new SeekBar(context());
        PdfFixture() {
            view.layout(0, 0, 400, 600);
            view.setFitBitmap(bitmap, 1f, 400, 600, true);
            host.pdfPageMatrixView = view;
            host.currentPage = 3;
            host.pageCount = 10;
            slider.setMax(9);
            slider.layout(0, 0, 400, 60);
            host.pdfPageSeekBar = slider;
            invoke(host, PdfReaderActivity.class, "setupPdfPageSeekBar");
        }
        float[] anchor() { return view.captureViewportAnchor(); }
        void legacyZoom(float value) {
            try {
                Field field = PdfReaderActivity.class.getDeclaredField("zoom");
                field.setAccessible(true);
                field.setFloat(host, value);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        float legacyZoom() {
            try {
                Field field = PdfReaderActivity.class.getDeclaredField("zoom");
                field.setAccessible(true);
                return field.getFloat(host);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        void menu(float step) {
            try {
                Method method = PdfReaderActivity.class.getDeclaredMethod("changeZoomFromMenu", float.class);
                method.setAccessible(true);
                method.invoke(host, step);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        @Override public void close() {
            host.activityDestroyed = true;
            view.detachBitmaps();
            bitmap.recycle();
            stopWorkers(host, PdfReaderActivity.class, "executor", "prefetchExecutor", "handler");
        }
    }

    private static final class ImageHost extends ImageReaderActivity {
        final Context context = context();
        int jumpRequests;
        @Override void showImagePageJumpDialog() { jumpRequests++; }
        ImageHost() { attachBaseContext(context); }
        @Override public Context getApplicationContext() { return context.getApplicationContext(); }
        @Override public Resources getResources() { return context.getResources(); }
        @Override public Resources.Theme getTheme() { return context.getTheme(); }
        @Override public Object getSystemService(String name) { return context.getSystemService(name); }
    }

    private static final class ImageFixture implements AutoCloseable {
        final ImageHost host = new ImageHost();
        int moves, lastTarget;
        final ImageReaderSliderController controller = new ImageReaderSliderController(host, target -> {
            moves++; lastTarget = target;
        });
        final SeekBar slider;
        final android.widget.TextView label;
        ImageFixture() {
            LinearLayout bar = (LinearLayout) controller.createView(10);
            label = (android.widget.TextView) bar.getChildAt(0);
            slider = (SeekBar) bar.getChildAt(1);
            slider.layout(0, 0, 400, 60);
        }
        @Override public void close() {
            stopWorkers(host, ImageReaderActivity.class, "executor", "sequenceExecutor", "detailExecutor",
                    "imageExportExecutor", "sourceCheckExecutor", "prefetchDecodeExecutor",
                    "mainHandler", "imageResultHandler");
        }
    }
}
