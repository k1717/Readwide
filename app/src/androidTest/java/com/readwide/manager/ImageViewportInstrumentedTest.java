package com.readwide.manager;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.view.ZoomImageView;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

/** Display transforms only: no file decoding, archive access or Activity launch. */
@RunWith(AndroidJUnit4.class)
public class ImageViewportInstrumentedTest {
    private static final class Fixture implements AutoCloseable {
        final ZoomImageView view = new ZoomImageView(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        final Bitmap bitmap;

        Fixture() {
            this(600, 1200);
        }

        Fixture(int width, int height) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setDensity(Bitmap.DENSITY_NONE);
            bitmap.eraseColor(Color.WHITE);
            view.setBackgroundColor(Color.BLACK);
            view.layout(0, 0, 600, 1000);
            view.setImageBitmapReady(bitmap);
        }

        RectF displayed() {
            RectF rect = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
            view.getImageMatrix().mapRect(rect);
            rect.offset(view.getPaddingLeft(), view.getPaddingTop());
            return rect;
        }

        float scale() {
            float[] values = new float[9];
            view.getImageMatrix().getValues(values);
            return values[Matrix.MSCALE_X];
        }

        float[] centerInImage() {
            float[] point = {(view.getWidth() - view.getPaddingLeft() - view.getPaddingRight()) / 2f,
                    (view.getHeight() - view.getPaddingTop() - view.getPaddingBottom()) / 2f};
            Matrix inverse = new Matrix();
            assertTrue(view.getImageMatrix().invert(inverse));
            inverse.mapPoints(point);
            return point;
        }

        void doubleTap(float x, float y) {
            try {
                Method method = ZoomImageView.class.getDeclaredMethod("toggleZoom", float.class, float.class);
                method.setAccessible(true);
                method.invoke(view, x, y);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }

        @Override public void close() {
            view.setCallbacks(null);
            view.clearImageReady();
            bitmap.recycle();
        }
    }

    @Test public void panelsRefitTheSameDrawableAndKeepTheFullTouchSurface() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                Drawable original = f.view.getDrawable();
                RectF full = f.displayed();
                f.view.setViewportInsets(0, 100, 0, 160);
                RectF reduced = f.displayed();
                assertEquals(100f, reduced.top, 0.01f);
                assertEquals(840f, reduced.bottom, 0.01f);
                assertTrue(reduced.width() < full.width());
                assertEquals(600, f.view.getWidth());
                assertEquals(1000, f.view.getHeight());
                assertSame(original, f.view.getDrawable());
                f.view.setViewportInsets(0, 0, 0, 0);
                assertEquals(full.top, f.displayed().top, 0.01f);
                assertEquals(full.bottom, f.displayed().bottom, 0.01f);
                assertSame(original, f.view.getDrawable());
            }
        });
    }

    @Test public void shortImageDoesNotMoveVerticallyWhenPanelsToggle() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture(600, 300)) {
                Drawable original = f.view.getDrawable();
                RectF before = f.displayed();
                for (int i = 0; i < 20; i++) {
                    f.view.setViewportInsets(0, 100, 0, 180);
                    assertEquals(before.top, f.displayed().top, 0.01f);
                    assertEquals(before.bottom, f.displayed().bottom, 0.01f);
                    f.view.setViewportInsets(0, 0, 0, 0);
                    assertEquals(before.top, f.displayed().top, 0.01f);
                    assertEquals(before.bottom, f.displayed().bottom, 0.01f);
                }
                assertSame(original, f.view.getDrawable());
            }
        });
    }

    @Test public void shortImageLoadedWithPanelsVisibleUsesSameScreenPosition() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture(600, 300)) {
                f.view.clearImageReady();
                f.view.setViewportInsets(0, 100, 0, 180);
                f.view.setImageBitmapReady(f.bitmap);
                assertEquals(350f, f.displayed().top, 0.01f);
                f.view.setViewportInsets(0, 0, 0, 0);
                assertEquals(350f, f.displayed().top, 0.01f);
            }
        });
    }

    @Test public void zoomedShortImageStaysStillIfItFitsBothHeights() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture(600, 200)) {
                f.doubleTap(300, 500);
                RectF before = f.displayed();
                f.view.setViewportInsets(0, 100, 0, 180);
                assertEquals(before.top, f.displayed().top, 0.01f);
                assertEquals(before.bottom, f.displayed().bottom, 0.01f);
                f.view.setViewportInsets(0, 0, 0, 0);
                assertEquals(before.top, f.displayed().top, 0.01f);
                f.doubleTap(300, 500);
                assertEquals(400f, f.displayed().top, 0.01f);
            }
        });
    }

    @Test public void shortDetailReplacementKeepsScreenTop() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture(600, 300)) {
                f.view.setViewportInsets(0, 100, 0, 180);
                Bitmap detail = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888);
                detail.setDensity(Bitmap.DENSITY_NONE);
                try {
                    f.view.setImageBitmapReady(detail, true);
                    RectF rect = new RectF(0, 0, 1200, 600);
                    f.view.getImageMatrix().mapRect(rect);
                    assertEquals(350f, rect.top + f.view.getPaddingTop(), 0.01f);
                } finally { f.view.clearImageReady(); detail.recycle(); }
            }
        });
    }

    @Test public void panelCollisionShiftsOnlyEnoughToKeepImageVisible() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture(600, 700)) {
                f.view.setViewportInsets(0, 100, 0, 180);
                assertEquals(1f, f.scale(), 0.0001f);
                assertEquals(120f, f.displayed().top, 0.01f);
                assertEquals(820f, f.displayed().bottom, 0.01f);
                f.view.setViewportInsets(0, 0, 0, 0);
                assertEquals(150f, f.displayed().top, 0.01f);
            }
        });
    }

    @Test public void repeatedPanelTogglesDoNotAccumulateScaleOrPositionDrift() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                Matrix before = new Matrix(f.view.getImageMatrix());
                for (int i = 0; i < 20; i++) {
                    f.view.setViewportInsets(20, 90, 30, 180);
                    f.view.setViewportInsets(0, 0, 0, 0);
                }
                float[] expected = new float[9], actual = new float[9];
                before.getValues(expected);
                f.view.getImageMatrix().getValues(actual);
                assertArrayEquals(expected, actual, 0.001f);
            }
        });
    }

    @Test public void sameInsetsLeaveAnExistingZoomMatrixUntouched() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                f.view.setViewportInsets(40, 140, 20, 100);
                f.doubleTap(270, 430);
                Matrix before = new Matrix(f.view.getImageMatrix());
                f.view.setViewportInsets(40, 140, 20, 100);
                assertEquals(before, f.view.getImageMatrix());
            }
        });
    }

    @Test public void resizedViewportRetainsRelativeZoomAndCenterWhereBoundsAllow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                f.doubleTap(300, 500);
                float[] center = f.centerInImage();
                f.view.setViewportInsets(0, 100, 0, 160);
                assertEquals(2f * 740f / 1200f, f.scale(), 0.0001f);
                assertArrayEquals(center, f.centerInImage(), 0.01f);
                f.view.setViewportInsets(0, 0, 0, 0);
                assertEquals(2f * 1000f / 1200f, f.scale(), 0.0001f);
                assertArrayEquals(center, f.centerInImage(), 0.01f);
            }
        });
    }

    @Test public void doubleTapUsesContentCoordinatesInsideAsymmetricInsets() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                f.view.setViewportInsets(40, 140, 20, 100);
                float[] focus = {270 - 40, 430 - 140};
                Matrix inverse = new Matrix();
                assertTrue(f.view.getImageMatrix().invert(inverse));
                inverse.mapPoints(focus);
                f.doubleTap(270, 430);
                f.view.getImageMatrix().mapPoints(focus);
                assertEquals(230f, focus[0], 0.01f);
                assertEquals(290f, focus[1], 0.01f);
            }
        });
    }

    @Test public void zoomedImageDoesNotDrawUnderThePanels() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                f.view.setViewportInsets(20, 100, 30, 160);
                f.doubleTap(295, 470);
                Bitmap surface = Bitmap.createBitmap(600, 1000, Bitmap.Config.ARGB_8888);
                try {
                    f.view.draw(new Canvas(surface));
                    assertEquals(Color.BLACK, surface.getPixel(300, 50));
                    assertEquals(Color.BLACK, surface.getPixel(300, 900));
                    assertEquals(Color.BLACK, surface.getPixel(10, 400));
                    assertEquals(Color.BLACK, surface.getPixel(590, 400));
                    assertEquals(Color.WHITE, surface.getPixel(300, 470));
                } finally { surface.recycle(); }
            }
        });
    }

    @Test public void replacementDetailBitmapUsesTheSameViewportAnchor() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                f.view.setViewportInsets(40, 140, 20, 100);
                f.doubleTap(270, 430);
                float[] center = f.centerInImage();
                float oldScale = f.scale();
                Bitmap detail = Bitmap.createBitmap(1200, 2400, Bitmap.Config.ARGB_8888);
                detail.setDensity(Bitmap.DENSITY_NONE);
                try {
                    f.view.setImageBitmapReady(detail, true);
                    assertEquals(oldScale / 2, f.scale(), 0.0001f);
                    float[] after = f.centerInImage();
                    assertEquals(center[0] * 2, after[0], 0.01f);
                    assertEquals(center[1] * 2, after[1], 0.01f);
                } finally { f.view.clearImageReady(); detail.recycle(); }
            }
        });
    }

    @Test public void zeroContentAreaRecoversWithoutReplacingTheImage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                Drawable original = f.view.getDrawable();
                f.view.setViewportInsets(0, 600, 0, 600);
                f.view.setViewportInsets(0, 0, 0, 0);
                assertSame(original, f.view.getDrawable());
                assertEquals(1000f / 1200f, f.scale(), 0.0001f);
            }
        });
    }

    @Test public void drawableContentIsNotRecreatedByInsets() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                Drawable drawable = new ColorDrawable(Color.WHITE) {
                    @Override public int getIntrinsicWidth() { return 300; }
                    @Override public int getIntrinsicHeight() { return 900; }
                };
                f.view.setImageDrawableReady(drawable);
                f.view.setViewportInsets(0, 100, 0, 160);
                assertSame(drawable, f.view.getDrawable());
                assertEquals(740f / 900f, f.scale(), 0.0001f);
            }
        });
    }

    @Test public void bottomPanelGapStillPagesThroughThePaddedImageView() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Fixture f = new Fixture()) {
                int[] taps = {0};
                f.view.setCallbacks(new ZoomImageView.Callbacks() {
                    @Override public void onSingleTap(float x, float y) {
                        assertTrue(x > 0.67f);
                        assertEquals(0.95f, y, 0.001f);
                        taps[0]++;
                    }
                    @Override public boolean shouldHandleTapImmediately(float x, float y) { return y > 0.67f; }
                    @Override public void onSwipeLeft() { fail("Unexpected swipe"); }
                    @Override public void onSwipeRight() { fail("Unexpected swipe"); }
                    @Override public void onZoomRequested() { fail("Insets must not reload detail"); }
                });
                FrameLayout root = new FrameLayout(f.view.getContext());
                root.addView(f.view, new FrameLayout.LayoutParams(-1, -1));
                ImageReaderSliderLayout panel = new ImageReaderSliderLayout(f.view.getContext());
                panel.setSystemInsets(0, 0, 20);
                root.addView(panel, new FrameLayout.LayoutParams(-1, 160, Gravity.BOTTOM));
                root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, 600, 1000);
                f.view.setViewportInsets(0, 100, 0, 160);
                long down = SystemClock.uptimeMillis();
                for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                    MotionEvent event = MotionEvent.obtain(down, down + (action == MotionEvent.ACTION_UP ? 10 : 0),
                            action, 590, 950, 0);
                    try { assertTrue(root.dispatchTouchEvent(event)); } finally { event.recycle(); }
                }
                assertEquals(1, taps[0]);
            }
        });
    }
}
