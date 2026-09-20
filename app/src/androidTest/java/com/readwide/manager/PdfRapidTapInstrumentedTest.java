package com.readwide.manager;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.MotionEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual Android detector/view checks. These do not open a PDF or saved reading state. */
@RunWith(AndroidJUnit4.class)
public class PdfRapidTapInstrumentedTest {
    private static final class Fixture implements AutoCloseable {
        final PdfPageView view = new PdfPageView(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        final Bitmap bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888);
        final long start = SystemClock.uptimeMillis();
        int taps, swipes, lastSwipeDirection;
        long lastTime, lastDown;

        Fixture() {
            view.layout(0, 0, 1000, 1000);
            view.setFitBitmap(bitmap, 1f, 1000, 1000, true);
            view.setTapZoneQuery((x, y) -> x < 100 || x > 700);
            view.setTapListener((x, y) -> taps++);
            view.setPageSwipeListener(direction -> { swipes++; lastSwipeDirection = direction; return true; });
        }

        void event(int action, long down, long time, float x) {
            lastDown = down; lastTime = time;
            MotionEvent e = MotionEvent.obtain(start + down, start + time, action, x, 500, 0);
            try { view.onTouchEvent(e); } finally { e.recycle(); }
        }

        void tap(long down, float x) {
            int before = taps;
            event(MotionEvent.ACTION_DOWN, down, down, x);
            assertEquals("Do not page on touch-down", before, taps);
            event(MotionEvent.ACTION_UP, down, down + 10, x);
            assertEquals("Each release turns exactly once", before + 1, taps);
        }

        @Override public void close() {
            event(MotionEvent.ACTION_CANCEL, lastDown, lastTime + 1, 900);
            view.detachBitmaps();
            bitmap.recycle();
        }
    }

    @Test public void rapidSamePointAndAlternatingPointsBothTurnOncePerRelease() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // 70 ms crosses the platform's usual minimum double-tap interval;
            // 20 ms also verifies that the app does not impose its own rate limit.
            for (long interval : new long[] {20, 70}) {
                try (Fixture same = new Fixture(); Fixture alternating = new Fixture()) {
                    for (int i = 0; i < 8; i++) {
                        same.tap(i * interval, 900);
                        alternating.tap(i * interval, i % 2 == 0 ? 900 : 50);
                    }
                    assertEquals(8, same.taps);
                    assertEquals(same.taps, alternating.taps);
                    assertEquals(0, same.swipes);
                }
            }
        });
    }

    @Test public void secondTouchCancelledDoesNotTurnEarly() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                f.tap(0, 900);
                f.event(MotionEvent.ACTION_DOWN, 70, 70, 900);
                assertEquals(1, f.taps);
                f.event(MotionEvent.ACTION_CANCEL, 70, 80, 900);
                assertEquals(1, f.taps);
                f.tap(140, 900);
            }
        });
    }

    @Test public void secondTouchDraggedSwipesWithoutAnExtraTapTurn() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                f.tap(0, 900);
                f.event(MotionEvent.ACTION_DOWN, 70, 70, 900);
                f.event(MotionEvent.ACTION_MOVE, 70, 100, 400);
                f.event(MotionEvent.ACTION_UP, 70, 120, 200);
                assertEquals(1, f.taps);
                assertEquals(1, f.swipes);
            }
        });
    }

    @Test public void centerDoubleTapStillZooms() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                f.event(MotionEvent.ACTION_DOWN, 0, 0, 500);
                f.event(MotionEvent.ACTION_UP, 0, 10, 500);
                f.event(MotionEvent.ACTION_DOWN, 70, 70, 500);
                f.event(MotionEvent.ACTION_UP, 70, 80, 500);
                assertEquals(0, f.taps);
                assertTrue(f.view.captureViewportAnchor()[2] > 1.5f);
            }
        });
    }

    @Test public void interveningPageTapBreaksCenterDoubleTapHistory() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                f.event(MotionEvent.ACTION_DOWN, 0, 0, 500);
                f.event(MotionEvent.ACTION_UP, 0, 10, 500);
                f.tap(70, 900);
                f.event(MotionEvent.ACTION_DOWN, 140, 140, 500);
                f.event(MotionEvent.ACTION_UP, 140, 150, 500);
                assertEquals(1, f.taps);
                assertEquals(1f, f.view.captureViewportAnchor()[2], 0.01f);
            }
        });
    }

    @Test public void zoomedDoubleTapInEitherPageZoneRestoresFitWithoutPaging() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (float x : new float[] {50, 900}) {
                try (Fixture f = new Fixture()) {
                    assertTrue(f.view.restoreViewportAnchor(0.5f, 0.5f, 2.5f, true));
                    assertTrue(f.view.isZoomedIn());
                    f.event(MotionEvent.ACTION_DOWN, 0, 0, x);
                    f.event(MotionEvent.ACTION_UP, 0, 10, x);
                    assertEquals(0, f.taps);
                    f.event(MotionEvent.ACTION_DOWN, 70, 70, x);
                    f.event(MotionEvent.ACTION_UP, 70, 80, x);
                    assertEquals(0, f.taps);
                    assertEquals(0, f.swipes);
                    assertFalse(f.view.isZoomedIn());
                    assertEquals(1f, f.view.captureViewportAnchor()[2], 0.01f);
                }
            }
        });
    }

    @Test public void zoomedRightEdgeOutwardSwipeTurnsNext() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                assertTrue(f.view.restoreViewportAnchor(1f, 0.5f, 2.5f, true));
                f.event(MotionEvent.ACTION_DOWN, 0, 0, 900);
                f.event(MotionEvent.ACTION_MOVE, 0, 40, 400);
                f.event(MotionEvent.ACTION_UP, 0, 70, 200);
                assertEquals(0, f.taps); assertEquals(1, f.swipes);
                assertEquals(1, f.lastSwipeDirection);
            }
        });
    }

    @Test public void zoomedLeftEdgeOutwardSwipeTurnsPrevious() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                assertTrue(f.view.restoreViewportAnchor(0f, 0.5f, 2.5f, true));
                f.event(MotionEvent.ACTION_DOWN, 0, 0, 50);
                f.event(MotionEvent.ACTION_MOVE, 0, 40, 600);
                f.event(MotionEvent.ACTION_UP, 0, 70, 800);
                assertEquals(0, f.taps); assertEquals(1, f.swipes);
                assertEquals(-1, f.lastSwipeDirection);
            }
        });
    }

    @Test public void zoomedInteriorPanReachingEdgeStaysOnPage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                assertTrue(f.view.restoreViewportAnchor(0.5f, 0.5f, 2.5f, true));
                f.event(MotionEvent.ACTION_DOWN, 0, 0, 950);
                f.event(MotionEvent.ACTION_MOVE, 0, 40, 50);
                f.event(MotionEvent.ACTION_UP, 0, 70, 50);
                assertEquals(0, f.taps); assertEquals(0, f.swipes);
                assertTrue(f.view.isZoomedIn());
            }
        });
    }

    @Test public void zoomedRightEdgeInwardDragPansWithoutTurning() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                assertTrue(f.view.restoreViewportAnchor(1f, 0.5f, 2.5f, true));
                float before = f.view.captureViewportAnchor()[0];
                f.event(MotionEvent.ACTION_DOWN, 0, 0, 100);
                f.event(MotionEvent.ACTION_MOVE, 0, 40, 800);
                f.event(MotionEvent.ACTION_UP, 0, 70, 800);
                assertEquals(0, f.taps); assertEquals(0, f.swipes);
                assertTrue(f.view.captureViewportAnchor()[0] < before);
            }
        });
    }
}
