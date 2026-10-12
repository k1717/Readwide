package com.readwide.manager;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.controller.AutoPageTurnController;
import com.readwide.manager.util.TxtBlankLineCollapser;
import com.readwide.manager.view.CustomReaderView;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.BufferedReader;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.function.Function;
import static org.junit.Assert.*;

/** Regression source only: adding this file does not imply device execution. */
@RunWith(AndroidJUnit4.class)
public class ReaderDocumentNavigationInstrumentedTest {
    @Test public void lineJumpCountsWholeDocumentNotTheActivePartition() throws Exception {
        String text = "first\r\nsecond\r\nthird";
        assertEquals(0, line(text, 1, Function.identity()));
        assertEquals(6, line(text, 2, Function.identity()));
        assertEquals(13, line(text, 3, Function.identity()));
        String longText = "row\n".repeat(9000) + "end";
        assertEquals(36000, line(longText, 9001, Function.identity()));
    }

    @Test public void lineJumpHonorsInsertedNewlinesAndUnicodeCoordinates() throws Exception {
        Function<String, String> rules = s -> s.replace("|", "\n");
        assertEquals(4, line("가😀|나\nlast", 2, rules));
        assertEquals(6, line("가😀|나\nlast", 3, rules));
    }

    @Test public void lineJumpUsesCollapsedDisplayedLines() throws Exception {
        String text = "first\n\n\n\nsecond";
        assertEquals(9, line(text, 5, Function.identity()));
        TxtBlankLineCollapser.Filter filter = new TxtBlankLineCollapser.Filter(true);
        assertEquals(7, line(text, 3, filter::accept));
    }

    @Test public void lineJumpBeyondEofDoesNotInventTerminalSeparator() throws Exception {
        assertEquals(3, line("a\nb\n", Integer.MAX_VALUE, Function.identity()));
        assertEquals(0, line("", Integer.MAX_VALUE, Function.identity()));
        assertEquals(1, line("\n\n", Integer.MAX_VALUE, Function.identity()));
    }

    @Test public void supersededLineScanStopsBeforeReadingAnotherLine() throws Exception {
        int[] reads = {0};
        try (BufferedReader input = new BufferedReader(new StringReader("a\nb\nc"))) {
            try {
                ReaderLargeTextPartitionReadController.findCharForDisplayLine(input, 99,
                        s -> { reads[0]++; return s; }, () -> reads[0] < 1);
                fail("cancelled scan must not publish a coordinate");
            } catch (InterruptedIOException expected) { assertEquals(1, reads[0]); }
        }
    }

    private static int line(String text, int target, Function<String, String> transform) throws Exception {
        try (BufferedReader input = new BufferedReader(new StringReader(text))) {
            return ReaderLargeTextPartitionReadController.findCharForDisplayLine(input, target, transform, () -> true);
        }
    }

    @Test public void percentageUsesWholeDocumentPageRouteIncludingEndpoints() {
        withReader(activity -> {
            activity.largeTextEstimateActive = true;
            activity.exactReady = true;
            ReaderPageJumpController jumps = new ReaderPageJumpController(activity);
            jumps.scrollToDocumentPercent(0f);
            assertEquals(1, activity.requestedPage);
            jumps.scrollToDocumentPercent(.5f);
            assertEquals(51, activity.requestedPage);
            jumps.scrollToDocumentPercent(1f);
            assertEquals(101, activity.requestedPage);
            assertEquals(0, activity.localPercentCalls);
        });
    }

    @Test public void smallTextPositionControlsKeepTheirResidentCoordinates() {
        withReader(activity -> {
            ReaderPageJumpController jumps = new ReaderPageJumpController(activity);
            jumps.scrollToDocumentPercent(.5f);
            assertEquals(1, activity.localPercentCalls);
            jumps.scrollToDocumentLine(3);
            assertEquals(activity.fileContent.indexOf("row2"), activity.localChar);
        });
    }

    @Test public void nonFiniteOrNotReadyPositionRequestsDoNotNavigate() {
        withReader(activity -> {
            ReaderPageJumpController jumps = new ReaderPageJumpController(activity);
            jumps.scrollToDocumentPercent(Float.NaN);
            jumps.scrollToDocumentPercent(Float.POSITIVE_INFINITY);
            activity.textContentReadyForPersistence = false;
            jumps.scrollToDocumentPercent(.5f);
            jumps.scrollToDocumentLine(3);
            assertEquals(0, activity.localPercentCalls);
            assertEquals(-1, activity.localChar);
        });
    }

    @Test public void autoTurnDoesNotStopAtTheEstimatedLastPage() {
        withReader(activity -> {
            activity.largeTextEstimateActive = true;
            assertTrue(activity.isReadyForAutoPageTurn());
            assertFalse(activity.hasAutoPageTurnReachedEnd());
            withTimer(activity, (controller, callback, tick) -> {
                tick.run();
                assertEquals(1, callback.turns);
                assertEquals(0, callback.ends);
                assertTrue(controller.isRunning());
            });
        });
    }

    @Test public void autoTurnWaitsForPartitionAndLayoutInsteadOfQueuingPages() {
        withReader(activity -> {
            activity.largeTextEstimateActive = true;
            activity.largeTextPartitionSwitchState.begin(5, 101, 5, 101);
            withTimer(activity, (controller, callback, tick) -> {
                tick.run(); tick.run();
                assertEquals(0, callback.turns);
                assertEquals(0, callback.ends);
                activity.largeTextPartitionSwitchState.reset();
                activity.textContentReadyForPersistence = false;
                tick.run();
                assertEquals(0, callback.turns);
                activity.textContentReadyForPersistence = true;
                tick.run();
                assertEquals(1, callback.turns);
            });
        });
    }

    @Test public void realEofStopsAutoTurnEvenWhenPageHintsAreOnlyEstimates() {
        withReader(activity -> {
            activity.largeTextEstimateActive = true;
            activity.readerView.setTextContent("last", true);
            assertTrue(activity.hasAutoPageTurnReachedEnd());
            withTimer(activity, (controller, callback, tick) -> {
                tick.run(); tick.run();
                assertEquals(0, callback.turns);
                assertEquals(1, callback.ends);
                assertFalse(controller.isRunning());
            });
        });
    }

    private interface TimerCheck { void run(AutoPageTurnController timer, TimerCallback callback, Runnable tick); }
    private static void withTimer(NavigationReader activity, TimerCheck check) {
        Handler handler = new Handler(Looper.getMainLooper());
        TimerCallback callback = new TimerCallback(activity);
        AutoPageTurnController controller = new AutoPageTurnController(handler, callback);
        try {
            Field runnable = AutoPageTurnController.class.getDeclaredField("turnRunnable");
            runnable.setAccessible(true);
            controller.start();
            check.run(controller, callback, (Runnable) runnable.get(controller));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        finally { controller.release(); handler.removeCallbacksAndMessages(null); }
    }

    private static final class TimerCallback implements AutoPageTurnController.Callback {
        final NavigationReader activity;
        int turns, ends;
        TimerCallback(NavigationReader activity) { this.activity = activity; }
        public boolean isDestroyed() { return activity.activityDestroyed; }
        public boolean isReadyForAutoPageTurn() { return activity.isReadyForAutoPageTurn(); }
        public boolean hasAutoPageTurnReachedEnd() { return activity.hasAutoPageTurnReachedEnd(); }
        public int getIntervalSeconds() { return 8; }
        public void pageForwardFromAutoPageTurn() { turns++; }
        public void onAutoPageTurnStarted() { }
        public void onAutoPageTurnStopped() { }
        public void onAutoPageTurnEndReached() { ends++; }
    }

    private interface ReaderCheck { void run(NavigationReader activity); }
    private static void withReader(ReaderCheck check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationReader activity = new NavigationReader();
            try {
                activity.readerView = new CustomReaderView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                activity.readerView.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE, 24, 16, Typeface.DEFAULT);
                activity.readerView.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
                activity.readerView.layout(0, 0, 720, 960);
                activity.fileContent = "row0\nrow1\nrow2\n" + "remaining\n".repeat(300);
                activity.totalChars = activity.fileContent.length();
                activity.filePath = "/navigation.txt";
                activity.largeTextPartitionEndLine = 100;
                activity.largeTextTotalLogicalLines = 10000;
                activity.readerView.setTextContent(activity.fileContent, false);
                activity.textContentReadyForPersistence = true;
                check.run(activity);
            } finally {
                if (activity.readerView != null) activity.readerView.releaseTextResources();
                activity.handler.removeCallbacksAndMessages(null);
                activity.executor.shutdownNow();
                activity.largeTextPartitionExecutor.shutdownNow();
                activity.largeTextSearchExecutor.shutdownNow();
                activity.largeTextSearchCountExecutor.shutdownNow();
            }
        });
    }

    private static final class NavigationReader extends ReaderActivity {
        int requestedPage, localPercentCalls, localChar = -1;
        boolean exactReady;
        @Override boolean ensureLoadedTextCurrent() { return true; }
        @Override void stopAutoPageTurnForManualNavigation() { }
        @Override int getDisplayedTotalPageCount() { return 101; }
        @Override int getDisplayedCurrentPageNumber() { return 101; }
        @Override boolean isLargeTextExactPageIndexReady() { return exactReady; }
        @Override CustomReaderView.PageTextAnchor getExactLargeTextAnchorForPage(int page) {
            return new CustomReaderView.PageTextAnchor(page * 10, "", "");
        }
        @Override void jumpToAbsoluteCharPosition(int position, int page, int total,
                String before, String after, boolean showLoading) { requestedPage = page; }
        @Override int getLargeTextPartitionLookaheadLines() { return 20; }
        @Override int getLargeTextPartitionStartLineForLine(int line) { return 1; }
        @Override int estimateCharPositionForLargeTextLine(int line) { return line * 10; }
        @Override void scrollToPercent(float fraction) { localPercentCalls++; }
        @Override void scrollToCharPosition(int position) { localChar = position; }
        @Override void reloadLargeTextPreviewAround(int position, int page, int total,
                String before, String after, int line, boolean showLoading) { requestedPage = page; }
        @Override boolean jumpToFinalLargeTextPage(int total, boolean showLoading) {
            requestedPage = total; return true;
        }
        @Override public android.content.res.Resources getResources() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext().getResources();
        }
    }
}
