package com.readwide.manager;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.view.CustomReaderView;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

import static org.junit.Assert.*;

/** Layout preconditions only: these regressions never submit an index build. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29) // Handler.hasCallbacks inspects the pending debounce without sleeping.
public class ReaderExactIndexLayoutInstrumentedTest {
    @Test public void consumedTextWidthCannotExposeOrBuildOnePixelExactIndex() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LayoutActivity activity = prepareReader();
            try {
                ReaderLargeTextExactPageIndexController controller =
                        new ReaderLargeTextExactPageIndexController(activity);
                String signature = controller.buildCurrentSignature(activity.filePath);
                int generation = seedReady(activity, signature);
                assertTrue(controller.isReady());

                activity.readerView.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE,
                        360, 16, Typeface.DEFAULT);
                assertEquals(1, activity.readerView.getTextLayoutWidthForIndex());
                assertFalse(activity.readerView.hasCurrentTextLayout());
                assertEquals("", controller.buildCurrentSignature(activity.filePath));
                assertFalse(controller.isReady());
                assertEquals(0, controller.readyPageCount());
                assertNull(controller.getAnchorForPage(2));
                assertTrue(controller.copyAnchors().isEmpty());
                assertNull(controller.copyAnchorsIfUsable(signature));

                controller.scheduleRestart();
                controller.startIndexing(activity.filePath);
                controller.handleRestartIndexingTick();
                assertFalse(activity.largeTextExactPageIndexState.isGenerationCurrent(generation));
                assertFalse(activity.largeTextExactPageIndexState.isReady());
                assertFalse(activity.largeTextExactPageIndexState.isFailed());
                assertFalse(activity.handler.hasCallbacks(activity.largeTextRestartIndexingRunnable));
            } finally { releaseReader(activity); }
        });
    }

    @Test public void unavailableLayoutCancelsWorkerAndWaitsForRealGeometryEvent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LayoutActivity activity = prepareReader();
            try {
                ReaderLargeTextExactPageIndexController controller =
                        new ReaderLargeTextExactPageIndexController(activity);
                String signature = controller.buildCurrentSignature(activity.filePath);
                int old = activity.largeTextExactPageIndexState.beginBuild(signature);
                activity.readerView.layout(0, 0, 720, 0);
                controller.scheduleRestart();
                controller.handleRestartIndexingTick();
                assertFalse(activity.largeTextExactPageIndexState.isGenerationCurrent(old));
                assertFalse(activity.largeTextExactPageIndexState.completeCurrent(
                        old, signature, anchors(), false, "").current);
                assertFalse(activity.handler.hasCallbacks(activity.largeTextRestartIndexingRunnable));

                activity.readerView.layout(0, 0, 720, 960);
                assertTrue(activity.readerView.hasCurrentTextLayout());
                assertEquals(signature, controller.buildCurrentSignature(activity.filePath));
                controller.scheduleRestart(); // Mirrors the usable-geometry callback.
                assertTrue(activity.handler.hasCallbacks(activity.largeTextRestartIndexingRunnable));
            } finally { releaseReader(activity); }
        });
    }

    @Test public void redundantUsableLayoutEventKeepsReadyAnchorsAndCacheValidation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LayoutActivity activity = prepareReader();
            try {
                ReaderLargeTextExactPageIndexController controller =
                        new ReaderLargeTextExactPageIndexController(activity);
                String signature = controller.buildCurrentSignature(activity.filePath);
                int ready = seedReady(activity, signature);
                controller.scheduleRestart();
                assertTrue(controller.isReady());
                assertEquals(2, controller.readyPageCount());
                assertTrue(activity.largeTextExactPageIndexState.isGenerationCurrent(ready));

                activity.largeTextExactPageIndexState.reset();
                int checking = activity.largeTextExactPageIndexState.beginBuild(signature);
                controller.scheduleRestart();
                assertTrue(activity.largeTextExactPageIndexState.isCheckingCache());
                assertTrue(activity.largeTextExactPageIndexState.isGenerationCurrent(checking));
            } finally { releaseReader(activity); }
        });
    }

    @Test public void unloadedLayoutPreservesStartupCacheCheckWithoutRetryLoop() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LayoutActivity activity = prepareReader();
            try {
                ReaderLargeTextExactPageIndexController controller =
                        new ReaderLargeTextExactPageIndexController(activity);
                activity.readerView.releaseTextResources();
                controller.startIndexing(activity.filePath);
                controller.scheduleRestart();
                controller.handleRestartIndexingTick();
                assertEquals("", controller.buildCurrentSignature(activity.filePath));
                assertTrue(activity.largeTextExactPageIndexState.isCheckingCache());
                assertFalse(activity.largeTextExactPageIndexState.isReady());
                assertFalse(activity.handler.hasCallbacks(activity.largeTextRestartIndexingRunnable));
            } finally { releaseReader(activity); }
        });
    }

    private static LayoutActivity prepareReader() {
        LayoutActivity activity = new LayoutActivity();
        activity.readerView = new CustomReaderView(activity.getApplicationContext());
        activity.readerView.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE,
                24, 16, Typeface.DEFAULT);
        activity.readerView.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        activity.readerView.layout(0, 0, 720, 960);
        activity.filePath = "/exact-index-layout.txt";
        activity.fileContent = "first line\nsecond line\nthird line";
        activity.readerView.setTextContent(activity.fileContent);
        activity.largeTextEstimateActive = true;
        return activity;
    }

    private static int seedReady(LayoutActivity activity, String signature) {
        assertFalse(signature.isEmpty());
        int generation = activity.largeTextExactPageIndexState.beginBuild(signature);
        activity.largeTextExactPageIndexState.completeCurrent(generation, signature, anchors(), false, "");
        return generation;
    }

    private static ArrayList<CustomReaderView.PageTextAnchor> anchors() {
        ArrayList<CustomReaderView.PageTextAnchor> result = new ArrayList<>();
        result.add(new CustomReaderView.PageTextAnchor(0, "", ""));
        result.add(new CustomReaderView.PageTextAnchor(11, "", ""));
        return result;
    }

    private static void releaseReader(LayoutActivity activity) {
        activity.activityDestroyed = true;
        activity.handler.removeCallbacksAndMessages(null);
        if (activity.readerView != null) activity.readerView.releaseTextResources();
        activity.executor.shutdownNow();
        activity.largeTextPartitionExecutor.shutdownNow();
        activity.largeTextSearchExecutor.shutdownNow();
        activity.largeTextSearchCountExecutor.shutdownNow();
    }

    private static final class LayoutActivity extends ReaderActivity {
        @Override boolean ensureLoadedTextCurrent() { return true; } // No file I/O in layout-only checks.
        @Override public Context getApplicationContext() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext();
        }

        @Override void updatePositionLabel() { }
    }
}
