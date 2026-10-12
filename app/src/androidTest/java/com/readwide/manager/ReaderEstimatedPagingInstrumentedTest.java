package com.readwide.manager;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.util.LargeTextContinuityMath;
import com.readwide.manager.view.CustomReaderView;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

import static org.junit.Assert.*;

/** Estimated labels must not become physical TXT navigation boundaries. */
@RunWith(AndroidJUnit4.class)
public class ReaderEstimatedPagingInstrumentedTest {
    @Test public void reachingEstimatedTotalStillAdvancesOneVisiblePage() {
        for (int overlap : new int[]{0, 1, 8}) {
            withReader(overlap, activity -> {
                activity.largeTextEstimatedTotalPages = 1;
                assertEquals(1, activity.getDisplayedCurrentPageNumber());
                assertEquals(1, activity.getDisplayedTotalPageCount());
                int next = activity.readerView.getCharPositionForNextPageStartRespectingOverlap();
                assertTrue(next > 0);
                new ReaderLargeTextPagingController(activity).pageBy(+1, false);
                assertEquals(2, activity.readerView.getCurrentPageNumber());
                assertTrue(activity.readerView.getCurrentCharPosition() > 0);
                assertTrue(activity.readerView.getCurrentCharPosition() <= next);
                assertEquals(-1, activity.reloadedAt);
                assertEquals(0, activity.finalJumps);
            });
        }
    }

    @Test public void estimatedLastPageDoesNotSkipTheRestOfTheFinalPartition() {
        for (int overlap : new int[]{0, 1, 8}) {
            withReader(overlap, activity -> {
                activity.largeTextTotalLogicalLines = activity.largeTextPartitionEndLine;
                activity.readerView.setTextContent(activity.fileContent, true);
                activity.largeTextEstimatedTotalPages = 2;
                assertFalse(activity.hasNextLargeTextPartition());
                assertFalse(activity.readerView.isDocumentEndFullyVisible());
                assertTrue(activity.readerView.getTotalPageCount() > 3);
                int next = activity.readerView.getCharPositionForNextPageStartRespectingOverlap();
                new ReaderLargeTextPagingController(activity).pageBy(+1, false);
                assertEquals(0, activity.finalJumps);
                assertEquals(2, activity.readerView.getCurrentPageNumber());
                assertTrue(activity.readerView.getCurrentCharPosition() <= next);
                assertFalse(activity.readerView.isDocumentEndFullyVisible());
            });
        }
    }

    @Test public void estimatedFirstPageStillAllowsBackwardMovementWithinTheWindow() {
        withReader(1, activity -> {
            activity.largeTextEstimatedTotalPages = 1;
            activity.readerView.scrollToPage(2);
            assertEquals(1, activity.getDisplayedCurrentPageNumber());
            assertTrue(activity.readerView.getCurrentCharPosition() > 0);
            new ReaderLargeTextPagingController(activity).pageBy(-1, false);
            assertEquals(1, activity.readerView.getCurrentPageNumber());
            assertEquals(0, activity.readerView.getCurrentCharPosition());
            assertEquals(-1, activity.previousStartLine);
        });
    }

    @Test public void estimatedFirstPageStillAllowsThePreviousPartition() {
        withReader(1, activity -> {
            activity.largeTextEstimatedTotalPages = 1;
            activity.largeTextPartitionStartLine = 201;
            activity.largeTextPartitionEndLine = 400;
            activity.largeTextTotalLogicalLines = 600;
            activity.largeTextPreviewBaseCharOffset = 10000;
            assertTrue(activity.hasPreviousLargeTextPartition());
            assertEquals(1, activity.getDisplayedCurrentPageNumber());
            new ReaderLargeTextPagingController(activity).pageBy(-1, false);
            assertEquals(1, activity.previousStartLine);
            assertEquals(1, activity.requestedPage);
            assertEquals(1, activity.requestedTotal);
        });
    }

    @Test public void estimatedLastPageStillAllowsTheNextPartition() {
        withReader(1, activity -> {
            activity.largeTextEstimatedTotalPages = 1;
            activity.largeTextPreviewBaseCharOffset = 10000;
            int next = activity.readerView.getCharPositionForNextPageStartRespectingOverlap();
            activity.largeTextPartitionBodyCharCount = next;
            assertTrue(next > 0);
            assertTrue(activity.hasNextLargeTextPartition());
            new ReaderLargeTextPagingController(activity).pageBy(+1, false);
            assertEquals(LargeTextContinuityMath.forwardHandoffTargetAbs(10000, next, next),
                    activity.reloadedAt);
            assertEquals(1, activity.requestedPage);
            assertEquals(1, activity.requestedTotal);
            assertEquals(0, activity.finalJumps);
        });
    }

    @Test public void saturatedDisplayHintDoesNotWrapToTheFirstPage() {
        withReader(1, activity -> {
            activity.largeTextEstimatedTotalPages = Integer.MAX_VALUE;
            activity.largeTextEstimatedBasePageOffset = Integer.MAX_VALUE - 1;
            int next = activity.readerView.getCharPositionForNextPageStartRespectingOverlap();
            activity.largeTextPartitionBodyCharCount = next;
            assertEquals(Integer.MAX_VALUE, activity.getDisplayedCurrentPageNumber());
            new ReaderLargeTextPagingController(activity).pageBy(+1, false);
            assertTrue(activity.reloadedAt > 0);
            assertEquals(Integer.MAX_VALUE, activity.requestedPage);
            assertEquals(Integer.MAX_VALUE, activity.requestedTotal);
        });
    }

    @Test public void fullyVisibleRealEofAndPhysicalStartRemainBoundaries() {
        withReader(1, activity -> {
            activity.largeTextEstimatedTotalPages = 100;
            activity.fileContent = "last row\n";
            activity.largeTextPartitionBodyCharCount = 8;
            activity.readerView.setTextContent(activity.fileContent, true);
            assertTrue("real EOF can be visible in lookahead", activity.hasNextLargeTextPartition());
            assertTrue(activity.readerView.isDocumentEndFullyVisible());
            int before = activity.readerView.getReaderScrollY();
            ReaderLargeTextPagingController paging = new ReaderLargeTextPagingController(activity);
            paging.pageBy(+1, false);
            paging.pageBy(-1, false);
            assertEquals(before, activity.readerView.getReaderScrollY());
            assertEquals(-1, activity.reloadedAt);
            assertEquals(-1, activity.previousStartLine);
            assertEquals(0, activity.finalJumps);
        });
    }

    @Test public void readyExactAnchorsStillOwnTheNextAndFinalPage() {
        withReader(1, activity -> {
            ArrayList<CustomReaderView.PageTextAnchor> anchors = new ArrayList<>();
            for (int page = 1; page <= 3; page++) {
                anchors.add(new CustomReaderView.PageTextAnchor(
                        activity.readerView.getPageStartCharPosition(page), "", ""));
            }
            int generation = activity.largeTextExactPageIndexState.beginBuild("estimated-paging-test");
            activity.largeTextExactPageIndexState.completeCurrent(generation,
                    "estimated-paging-test", anchors, false, "");
            assertTrue(activity.isLargeTextExactPageIndexReady());
            ReaderLargeTextPagingController paging = new ReaderLargeTextPagingController(activity);
            paging.pageBy(+1, false);
            assertEquals(anchors.get(1).charPosition, activity.exactTarget);
            assertEquals(2, activity.requestedPage);
            assertEquals(3, activity.requestedTotal);
            assertEquals(0, activity.finalJumps);
            activity.readerView.scrollToPage(2);
            paging.pageBy(+1, false);
            assertEquals(1, activity.finalJumps);
            assertEquals(3, activity.requestedTotal);
        });
    }

    private interface ReaderCheck { void run(NavigationActivity activity); }

    private static void withReader(int overlap, ReaderCheck check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                activity.readerView = new CustomReaderView(
                        InstrumentationRegistry.getInstrumentation().getTargetContext());
                activity.readerView.setOverlapLines(overlap);
                activity.readerView.setReaderStyle(18f, 1.35f,
                        Color.BLACK, Color.WHITE, 24, 16, Typeface.DEFAULT);
                activity.readerView.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
                activity.readerView.layout(0, 0, 720, 960);
                StringBuilder text = new StringBuilder();
                for (int row = 0; row < 200; row++) {
                    if (row > 0) text.append('\n');
                    text.append("row ").append(row).append(" 가나다 문서 page content");
                }
                activity.fileContent = text.toString();
                activity.filePath = "/estimated-paging.txt";
                activity.largeTextEstimateActive = true;
                activity.largeTextPartitionStartLine = 1;
                activity.largeTextPartitionEndLine = 200;
                activity.largeTextTotalLogicalLines = 400;
                activity.largeTextPartitionBodyCharCount = text.length();
                activity.largeTextEstimatedTotalPages = 1;
                activity.readerView.setTextContent(activity.fileContent, false);
                assertTrue(activity.readerView.hasCurrentTextLayout());
                assertFalse(activity.isLargeTextExactPageIndexReady());
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

    /** Record navigation requests without file I/O, preferences or launching an Activity. */
    private static final class NavigationActivity extends ReaderActivity {
        int reloadedAt = -1, previousStartLine = -1, exactTarget = -1;
        int requestedPage, requestedTotal, finalJumps;

        @Override void updatePositionLabel() { }
        @Override void stopAutoPageTurnForManualNavigation() { }
        @Override void prefetchNeighborLargeTextPartitions() { }
        @Override int getLargeTextPartitionLines() { return 200; }
        @Override int getLargeTextPartitionStartLineForLine(int line) {
            return LargeTextContinuityMath.partitionStartLineForLine(line, 200);
        }
        @Override String buildCurrentLargeTextExactPageIndexSignature(String path) {
            return "estimated-paging-test";
        }
        @Override void reloadLargeTextPreviewAround(int position, int page, int total,
                String before, String after, int startLine, boolean showLoading) {
            reloadedAt = position;
            requestedPage = page;
            requestedTotal = total;
        }
        @Override void reloadLargeTextPartitionByStartLine(int startLine, int page, int total) {
            previousStartLine = startLine;
            requestedPage = page;
            requestedTotal = total;
        }
        @Override void jumpToAbsoluteCharPosition(int position, int page, int total,
                String before, String after, boolean showLoading) {
            exactTarget = position;
            requestedPage = page;
            requestedTotal = total;
        }
        @Override boolean jumpToFinalLargeTextPage(int total, boolean showLoading) {
            finalJumps++;
            requestedTotal = total;
            return true;
        }
    }
}
