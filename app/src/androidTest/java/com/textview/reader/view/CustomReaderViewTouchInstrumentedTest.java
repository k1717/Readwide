package com.readwide.manager.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Source regressions for touch continuity; no page-count formula is duplicated here. */
@RunWith(AndroidJUnit4.class)
public class CustomReaderViewTouchInstrumentedTest {
    @Test
    public void subpixelDragMovementAccumulatesInsteadOfBeingDiscarded() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = reader();
            try {
                TouchSequence touch = beginDrag(view);
                int start = view.getReaderScrollY();
                for (int i = 0; i < 8; i++) touch.moveBy(0.25f);
                assertEquals(start + 2, view.getReaderScrollY());
                touch.cancel();
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void directionReversalDoesNotSpendThePreviousDirectionRemainder() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = reader();
            try {
                TouchSequence touch = beginDrag(view);
                int start = view.getReaderScrollY();
                touch.moveBy(0.75f);
                assertEquals(start, view.getReaderScrollY());
                for (int i = 0; i < 4; i++) touch.moveBy(-0.25f);
                assertEquals(start - 1, view.getReaderScrollY());
                touch.cancel();
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void clampedDragDoesNotKeepFractionalOverscrollDebt() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = reader();
            try {
                TouchSequence touch = beginDrag(view);
                view.setReaderScrollY(view.getMaxScrollY());
                int end = view.getReaderScrollY();
                touch.moveBy(0.75f);
                assertEquals(0f, remainder(view), 0f);
                for (int i = 0; i < 4; i++) touch.moveBy(-0.25f);
                assertEquals(end - 1, view.getReaderScrollY());
                view.scrollToPage(1);
                int start = view.getReaderScrollY();
                touch = new TouchSequence(view);
                touch.moveBy(-dragDistance(view));
                touch.moveBy(-0.75f);
                assertEquals(start, view.getReaderScrollY());
                assertEquals(0f, remainder(view), 0f);
                for (int i = 0; i < 4; i++) touch.moveBy(0.25f);
                assertEquals(start + 1, view.getReaderScrollY());
                touch.cancel();
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void stopAndTouchReleaseDiscardOldFractionalMovement() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = reader();
            try {
                TouchSequence touch = beginDrag(view);
                touch.moveBy(0.75f);
                assertEquals(0.75f, remainder(view), 0f);
                view.stopScrollMotion();
                assertEquals(0f, remainder(view), 0f);
                assertTrue(view.isTouchActive());
                touch.up();
                assertFalse(view.isTouchActive());
                touch = beginDrag(view);
                int start = view.getReaderScrollY();
                touch.moveBy(0.25f);
                assertEquals(start, view.getReaderScrollY());
                touch.up();
                assertEquals(0f, remainder(view), 0f);
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void secondFingerRetiresDragUntilTheNextFreshDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = reader();
            int[] taps = {0};
            view.setReaderListener(tapListener(taps));
            try {
                TouchSequence touch = beginDrag(view);
                int start = view.getReaderScrollY();
                touch.pointer(MotionEvent.ACTION_POINTER_DOWN
                        | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
                assertTrue(view.isTouchActive());
                assertTrue(scroller(view).isFinished());
                touch.pointer(MotionEvent.ACTION_POINTER_UP);
                // The remaining finger is now at a distant coordinate; do not
                // interpret its new pointer-index-0 position as scroll or tap.
                touch.moveBy(300f);
                touch.up();
                assertEquals(start, view.getReaderScrollY());
                assertEquals(0, taps[0]);
                assertFalse(view.isTouchActive());
                assertFalse(view.isUserDraggingOrFlinging());
                TouchSequence fresh = new TouchSequence(view);
                fresh.up();
                assertEquals(1, taps[0]);
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void finalSearchResultReturnsSmoothlyDuringManualDragOnly() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = reader();
            try {
                placeSearchBeyondNormalBottom(view);
                int total = view.getTotalPageCount();
                int normalMax = view.getMaxScrollY();
                int searchPosition = view.getReaderScrollY();
                TouchSequence touch = new TouchSequence(view);
                int distance = dragDistance(view);
                touch.moveBy(-distance);
                assertEquals(searchPosition - distance, view.getReaderScrollY());
                assertTrue(view.getReaderScrollY() > normalMax);
                int returned = view.getReaderScrollY();
                touch.moveBy(20f);
                assertEquals("do not extend a search-only range while dragging forward",
                        returned, view.getReaderScrollY());
                touch.moveBy(-(returned - normalMax + 10));
                assertEquals(normalMax - 10, view.getReaderScrollY());
                touch.moveBy(20f);
                assertEquals(normalMax, view.getReaderScrollY());
                touch.cancel();
                assertEquals(total, view.getTotalPageCount());
                placeSearchBeyondNormalBottom(view);
                view.setReaderScrollY(view.getReaderScrollY());
                assertEquals("ordinary navigation keeps its original normal bound",
                        view.getMaxScrollY(), view.getReaderScrollY());
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void flingFrameBeyondNormalBottomUsesTheSameContinuousReturnBound() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = reader();
            try {
                placeSearchBeyondNormalBottom(view);
                int searchPosition = view.getReaderScrollY();
                // Zero-duration synthetic frame avoids clock/sleep assumptions:
                // exercise exactly the computeScroll path used by a real fling.
                scroller(view).startScroll(0, searchPosition, 0, -10, 0);
                view.computeScroll();
                assertEquals(searchPosition - 10, view.getReaderScrollY());
                assertTrue(view.getReaderScrollY() > view.getMaxScrollY());
                view.stopScrollMotion();
            } finally {
                view.releaseTextResources();
            }
        });
    }

    private static CustomReaderView reader() {
        CustomReaderView view = new CustomReaderView(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        float density = view.getResources().getDisplayMetrics().scaledDensity;
        view.setReaderStyle(18f / density, 1.35f, Color.BLACK, Color.WHITE,
                0, 0, Typeface.DEFAULT);
        view.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 720, 1000);
        view.setTextContent(lines(500));
        view.setReaderScrollY(view.getMaxScrollY() / 2);
        return view;
    }

    private static TouchSequence beginDrag(CustomReaderView view) {
        TouchSequence touch = new TouchSequence(view);
        touch.moveBy(dragDistance(view));
        return touch;
    }

    private static int dragDistance(CustomReaderView view) {
        return Math.max(32, ViewConfiguration.get(view.getContext()).getScaledTouchSlop() * 2);
    }

    private static void placeSearchBeyondNormalBottom(CustomReaderView view) {
        StaticLayout layout = (StaticLayout) field(view, "layout");
        int rowHeight = layout.getLineBottom(1) - layout.getLineTop(1);
        int fullRows = Math.max(4, view.getViewportHeight() / rowHeight);
        view.setTextContent(lines(fullRows * 3 + fullRows / 2));
        view.scrollToSearchResultPosition(view.getTextContent().length() - 1);
        assertTrue("fixture needs a partially filled last page with search-only bottom space",
                view.getReaderScrollY() > view.getMaxScrollY() + dragDistance(view) + 20);
    }

    private static String lines(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) text.append('\n');
            text.append("row ").append(i);
        }
        return text.toString();
    }

    private static float remainder(CustomReaderView view) {
        return (Float) field(view, "dragScrollRemainder");
    }

    private static OverScroller scroller(CustomReaderView view) {
        return (OverScroller) field(view, "scroller");
    }

    private static Object field(CustomReaderView view, String name) {
        try {
            java.lang.reflect.Field field = CustomReaderView.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(view);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static CustomReaderView.ReaderListener tapListener(int[] taps) {
        return new CustomReaderView.ReaderListener() {
            @Override public void onSingleTap(float x, float y) { taps[0]++; }
            @Override public void onTextLongPress(String text, int start, int end, float x, float y) {}
            @Override public void onReaderScrollChanged() {}
            @Override public void onReaderManualScroll() {}
            @Override public void onReaderManualOverscroll(int direction) {}
        };
    }

    private static final class TouchSequence {
        private final CustomReaderView view;
        private final long downTime = SystemClock.uptimeMillis();
        private long eventTime = downTime;
        private float y = 500f;

        TouchSequence(CustomReaderView view) {
            this.view = view;
            single(MotionEvent.ACTION_DOWN);
        }

        void moveBy(float scrollDelta) {
            y -= scrollDelta;
            single(MotionEvent.ACTION_MOVE);
        }

        void up() { single(MotionEvent.ACTION_UP); }

        void cancel() { single(MotionEvent.ACTION_CANCEL); }

        private void single(int action) {
            eventTime += 16;
            MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, 100f, y, 0);
            try {
                view.onTouchEvent(event);
            } finally {
                event.recycle();
            }
        }

        void pointer(int action) {
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
            MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
            for (int i = 0; i < 2; i++) {
                properties[i] = new MotionEvent.PointerProperties();
                properties[i].id = i;
                properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coordinates[i] = new MotionEvent.PointerCoords();
                coordinates[i].x = 100f;
                coordinates[i].y = i == 0 ? y : y - 300f;
            }
            eventTime += 16;
            MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, 2,
                    properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0);
            try {
                view.onTouchEvent(event);
            } finally {
                event.recycle();
            }
        }
    }
}
