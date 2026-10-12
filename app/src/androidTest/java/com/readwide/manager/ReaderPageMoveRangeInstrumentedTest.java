package com.readwide.manager;

import android.content.Context;
import android.content.res.Resources;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.lang.reflect.Field;

/** Regression source only; no device execution is implied. */
@RunWith(AndroidJUnit4.class)
public class ReaderPageMoveRangeInstrumentedTest {
    @Test public void exactTotalRefreshesTheOpenControlsInBothDirections() {
        withControls(f -> {
            assertEquals(999, f.seek.getMax());
            f.activity.pages = 2000;
            f.controls.refresh();
            assertEquals(1999, f.seek.getMax());
            assertEquals("1 - 2000", f.input.getHint().toString());
            assertEquals("20 / 2000", f.label.getText().toString());
            f.activity.pages = 10;
            f.controls.refresh();
            assertEquals(9, f.seek.getMax());
            assertEquals(9, f.seek.getProgress());
            assertEquals("10 / 10", f.label.getText().toString());
        });
    }

    @Test public void indexingDuringADragKeepsItsScaleUntilRelease() {
        withControls(f -> {
            f.controls.onStartTrackingTouch(f.seek);
            f.controls.onProgressChanged(f.seek, 999, true);
            f.activity.pages = 2000;
            f.controls.refresh();
            assertEquals(999, f.seek.getMax());
            assertEquals("1000 / 1000", f.label.getText().toString());
            f.controls.onStopTrackingTouch(f.seek);
            assertEquals(1000, f.activity.requestedPage);
            assertEquals(1999, f.seek.getMax());
            f.controls.onStartTrackingTouch(f.seek);
            f.controls.onProgressChanged(f.seek, 1999, true);
            f.controls.onStopTrackingTouch(f.seek);
            assertEquals(2000, f.activity.requestedPage);
        });
    }

    @Test public void smallerExactRangeClampsTheLandingWithoutChangingItMidDrag() {
        withControls(f -> {
            f.controls.onStartTrackingTouch(f.seek);
            f.controls.onProgressChanged(f.seek, 900, true);
            f.activity.pages = 800;
            f.controls.refresh();
            assertEquals(999, f.seek.getMax());
            f.controls.onStopTrackingTouch(f.seek);
            assertEquals(800, f.activity.requestedPage);
            assertEquals(799, f.seek.getMax());
        });
    }

    @Test public void exactUpdateDoesNotReplaceANumberBeingEditedOrNavigate() {
        withControls(f -> {
            assertTrue(f.input.requestFocus());
            f.input.setText("1234");
            f.activity.pages = 2000;
            f.controls.refresh();
            assertEquals("1234", f.input.getText().toString());
            assertEquals("1 - 2000", f.input.getHint().toString());
            assertEquals(-1, f.activity.requestedPage);
        });
    }

    @Test public void toolbarDragAlsoKeepsItsRangeWhenAnExactTotalArrives() {
        withControls(f -> {
            ReaderSeekController toolbar = new ReaderSeekController(f.activity);
            f.activity.seekBar = f.seek;
            try {
                Field tracking = ReaderSeekController.class.getDeclaredField("toolbarSeekTracking");
                tracking.setAccessible(true);
                tracking.setBoolean(toolbar, true);
                toolbar.previewToolbarSeekPage(700, 1000);
                assertTrue(toolbar.showPendingPositionIfNeeded(20, 2000));
                assertEquals(999, f.seek.getMax());
                assertEquals(699, f.seek.getProgress());
                assertTrue(toolbar.showPendingPositionIfNeeded(20, 800));
                assertEquals(999, f.seek.getMax());
                tracking.setBoolean(toolbar, false);
                toolbar.clearPendingToolbarSeekJump();
                toolbar.syncPosition(700, 800);
                assertEquals(799, f.seek.getMax());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            finally { toolbar.clearPendingToolbarSeekJump(); }
        });
    }

    private interface Check { void run(Fixture fixture); }
    private static void withControls(Check check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture();
            try { check.run(f); }
            finally {
                f.activity.handler.removeCallbacksAndMessages(null);
                f.activity.executor.shutdownNow();
                f.activity.largeTextPartitionExecutor.shutdownNow();
                f.activity.largeTextSearchExecutor.shutdownNow();
                f.activity.largeTextSearchCountExecutor.shutdownNow();
            }
        });
    }

    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static final class Fixture {
        final PageActivity activity = new PageActivity();
        final SeekBar seek = new SeekBar(context());
        final TextView label = new TextView(context());
        final EditText input = new EditText(context());
        final ReaderBottomControlsController controller = new ReaderBottomControlsController(activity);
        final ReaderBottomControlsController.PageMoveControls controls = controller.new PageMoveControls(seek, label, input);
    }

    private static final class PageActivity extends ReaderActivity {
        int pages = 1000, page = 20, requestedPage = -1;
        @Override public Resources getResources() { return context().getResources(); }
        @Override int getDisplayedTotalPageCount() { return pages; }
        @Override int getStatusCurrentPageNumber() { return page; }
        @Override int getLineOffsetWithinDisplayedPage(int page, int pages) { return 1; }
        @Override void previewToolbarSeekPage(int page, int pages) { }
        @Override boolean scrollToPageNumber(int page, boolean loading, boolean manual) {
            this.page = requestedPage = page;
            return true;
        }
        @Override void beginPendingToolbarSeekJump(int page, int pages) { }
        @Override void setPageLabels(int page, int pages) { }
    }
}
