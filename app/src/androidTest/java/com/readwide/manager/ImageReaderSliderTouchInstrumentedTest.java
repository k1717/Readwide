package com.readwide.manager;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Android dispatch checks for the slider overlay, without opening an image or archive. */
@RunWith(AndroidJUnit4.class)
public class ImageReaderSliderTouchInstrumentedTest {
    private static final class Fixture {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final FrameLayout root = new FrameLayout(context);
        final ImageReaderSliderLayout panel = new ImageReaderSliderLayout(context);
        final TextView label = new TextView(context);
        final SeekBar slider = new SeekBar(context);
        final List<Integer> imageEvents = new ArrayList<>();
        final List<Integer> labelEvents = new ArrayList<>();
        final List<Integer> sliderEvents = new ArrayList<>();
        final List<Integer> insetEvents = new ArrayList<>();
        final long downTime = SystemClock.uptimeMillis();
        final int leftInset, rightInset, bottomInset;

        Fixture(int left, int right, int bottom) {
            leftInset = left; rightInset = right; bottomInset = bottom;
            View image = new View(context);
            record(image, imageEvents);
            root.addView(image, new FrameLayout.LayoutParams(-1, -1));
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setGravity(Gravity.CENTER);
            panel.setSystemInsets(left, right, bottom);
            panel.setPadding(left + 24, 0, right + 24, bottom + 10);
            record(panel, insetEvents);

            // Match the controller's weighted label row and overlapping slider bounds.
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            label.setText("12 / 100");
            label.setSingleLine(true);
            label.setMinHeight(36);
            label.setTranslationY(2);
            record(label, labelEvents);
            row.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));
            row.addView(label, new LinearLayout.LayoutParams(0, 36, 1));
            row.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));
            panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(-1, 38);
            seekParams.topMargin = -10;
            record(slider, sliderEvents);
            panel.addView(slider, seekParams);
            panel.setTouchTargets(label, slider);
            root.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            layout(600);
        }

        void layout(int width) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, 800);
        }

        Rect bounds(View view) {
            Rect rect = new Rect(0, 0, view.getWidth(), view.getHeight());
            root.offsetDescendantRectToMyCoords(view, rect);
            return rect;
        }

        void event(int action, long elapsed, float x, float y) {
            MotionEvent event = MotionEvent.obtain(downTime, downTime + elapsed, action, x, y, 0);
            try { assertTrue(root.dispatchTouchEvent(event)); }
            finally { event.recycle(); }
        }

        void tap(float x, float y) {
            event(MotionEvent.ACTION_DOWN, 0, x, y);
            event(MotionEvent.ACTION_UP, 10, x, y);
        }

        void assertOnly(List<Integer> target, Integer... expected) {
            assertEquals(Arrays.asList(expected), target);
            for (List<Integer> events : Arrays.asList(imageEvents, labelEvents, sliderEvents, insetEvents)) {
                if (events != target) assertTrue(events.isEmpty());
            }
        }

        static void record(View view, List<Integer> events) {
            view.setOnTouchListener((v, event) -> { events.add(event.getActionMasked()); return true; });
        }
    }

    @Test public void bottomAppPaddingReachesImageOnBothSides() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int bottom : new int[] {0, 18, 48}) {
                for (float x : new float[] {30, 570}) {
                    Fixture f = new Fixture(0, 0, bottom);
                    f.tap(x, 800 - bottom - 5);
                    f.assertOnly(f.imageEvents, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP);
                }
            }
        });
    }

    @Test public void sidesOfPageLabelReachImageIncludingAfterLongHold() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (float x : new float[] {30, 570}) {
                Fixture f = new Fixture(0, 0, 18);
                float y = f.bounds(f.label).centerY();
                f.event(MotionEvent.ACTION_DOWN, 0, x, y);
                f.event(MotionEvent.ACTION_UP, 800, x, y);
                f.assertOnly(f.imageEvents, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP);
            }
        });
    }

    @Test public void centerOfLabelRetainsSelectionTouch() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture(0, 0, 18);
            Rect label = f.bounds(f.label);
            f.tap(label.centerX(), label.centerY());
            f.assertOnly(f.labelEvents, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP);
        });
    }

    @Test public void labelOffsetLeavesPanelAndSliderGeometryUnchanged() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture(0, 0, 18);
            Rect slider = f.bounds(f.slider);
            int height = f.panel.getHeight();
            f.label.setTranslationY(0);
            f.layout(600);
            assertEquals(height, f.panel.getHeight());
            assertEquals(slider, f.bounds(f.slider));
            f.label.setTranslationY(2);
            Rect label = f.bounds(f.label);
            f.tap(label.centerX(), label.top + 1);
            f.assertOnly(f.imageEvents, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP);
        });
    }

    @Test public void seekBarKeepsFullTouchWidthIncludingLabelOverlap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (float x : new float[] {30, 300, 570}) {
                Fixture f = new Fixture(0, 0, 18);
                f.tap(x, f.bounds(f.slider).top + 1);
                f.assertOnly(f.sliderEvents, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP);
            }
        });
    }

    @Test public void systemBottomAndSideInsetsDoNotReachImage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (float[] point : new float[][] {{300, 799}, {1, 780}, {599, 780}}) {
                Fixture f = new Fixture(20, 30, 18);
                f.tap(point[0], point[1]);
                f.assertOnly(f.insetEvents, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP);
            }
        });
    }

    @Test public void gestureStartingInGapStaysWithImageAfterEnteringSlider() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture(0, 0, 18);
            f.event(MotionEvent.ACTION_DOWN, 0, 570, 777);
            f.event(MotionEvent.ACTION_MOVE, 20, 300, f.bounds(f.slider).centerY());
            f.event(MotionEvent.ACTION_UP, 40, 300, f.bounds(f.label).centerY());
            f.assertOnly(f.imageEvents, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP);
        });
    }

    @Test public void sliderDragStaysWithSliderAfterEnteringGap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture(0, 0, 18);
            f.event(MotionEvent.ACTION_DOWN, 0, 300, f.bounds(f.slider).centerY());
            f.event(MotionEvent.ACTION_MOVE, 20, 570, 777);
            f.event(MotionEvent.ACTION_UP, 40, 570, 777);
            f.assertOnly(f.sliderEvents, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP);
        });
    }

    @Test public void cancellationReturnsToOriginalTargetOnly() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture(0, 0, 18);
            f.event(MotionEvent.ACTION_DOWN, 0, 30, 777);
            f.event(MotionEvent.ACTION_CANCEL, 20, 300, f.bounds(f.label).centerY());
            f.assertOnly(f.imageEvents, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL);
        });
    }

    @Test public void hiddenPanelDoesNotCaptureItsOldControlBounds() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture(0, 0, 18);
            Rect label = f.bounds(f.label);
            f.panel.setVisibility(View.GONE);
            f.tap(label.centerX(), label.centerY());
            f.assertOnly(f.imageEvents, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP);
        });
    }

    @Test public void relayoutAndRtlKeepLabelCenteredAndSidesAvailable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int width : new int[] {360, 1200}) {
                for (boolean center : new boolean[] {false, true}) {
                    Fixture f = new Fixture(0, 0, 18);
                    f.root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                    f.layout(width);
                    Rect label = f.bounds(f.label);
                    assertEquals(width / 2, label.centerX(), 1);
                    f.tap(center ? label.centerX() : width - 30, label.centerY());
                    f.assertOnly(center ? f.labelEvents : f.imageEvents,
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP);
                }
            }
        });
    }
}
