package com.readwide.manager;

import android.content.Intent;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.model.ReaderState;
import com.readwide.manager.model.Bookmark;
import com.readwide.manager.model.LargeTextLinePartitionResult;
import com.readwide.manager.view.CustomReaderView;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Saved-instance state remains usable without the process-local text snapshot. */
@RunWith(AndroidJUnit4.class)
public class ReaderViewportInstrumentedTest {
    @Test public void automaticHandoffPreservesBaselineEvenWithActiveSearch() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                int base = activity.fileContent.indexOf('\n') + 1;
                activity.readerView.scrollToPage(3);
                activity.readerView.setReaderScrollY(activity.readerView.getReaderScrollY() + 3);
                CustomReaderView.ScrollViewportAnchor before = activity.readerView.captureScrollViewportAnchor();
                assertNotNull(before);
                ReaderLargeTextPartitionNavigator.ViewportHandoff handoff =
                        new ReaderLargeTextPartitionNavigator.ViewportHandoff(activity, before);
                activity.activeSearchQuery = "row";
                activity.activeSearchIndex = before.charPosition;
                activity.beginLargeTextPartitionSwitchPending(3, 100);
                activity.largeTextPartitionSwitchState.markViewportHandoff();
                activity.applyLargeTextPartitionInPlace(partition(activity.fileContent.substring(base), base),
                        before.charPosition, 3, 100, null, null, false, 0, handoff);
                CustomReaderView.ScrollViewportAnchor after = activity.readerView.captureScrollViewportAnchor();
                assertNotNull(after);
                assertEquals(before.charPosition, base + after.charPosition);
                assertEquals(before.baselineOffsetPx, after.baselineOffsetPx);
                assertEquals("automatic replacement is not a search jump", -1, activity.searchLandedAt);
                assertTrue(activity.textContentReadyForPersistence);
                assertFalse(activity.largeTextPartitionSwitchState.isInProgress());
            } finally { releaseReader(activity); }
        });
    }

    @Test public void newContactRejectsOldAutomaticResultWithoutReplacingText() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                activity.readerView.scrollToPage(3);
                String original = activity.fileContent;
                CustomReaderView.ScrollViewportAnchor before = activity.readerView.captureScrollViewportAnchor();
                ReaderLargeTextPartitionNavigator.ViewportHandoff handoff =
                        new ReaderLargeTextPartitionNavigator.ViewportHandoff(activity, before);
                activity.beginLargeTextPartitionSwitchPending(3, 100);
                activity.largeTextPartitionSwitchState.markViewportHandoff();
                touch(activity.readerView, MotionEvent.ACTION_DOWN, 700);
                int base = original.indexOf('\n') + 1;
                activity.applyLargeTextPartitionInPlace(partition(original.substring(base), base),
                        before.charPosition, 3, 100, null, null, false, 0, handoff);
                assertSame(original, activity.fileContent);
                assertSame(original, activity.readerView.getTextContent());
                assertTrue(activity.readerView.isTouchActive());
                assertFalse(activity.largeTextPartitionSwitchState.isInProgress());
                touch(activity.readerView, MotionEvent.ACTION_MOVE, 300);
                assertTrue(activity.readerView.getCurrentCharPosition() > before.charPosition);
                touch(activity.readerView, MotionEvent.ACTION_CANCEL, 300);
            } finally { releaseReader(activity); }
        });
    }

    @Test public void pageTapSupersedesAutomaticReadRatherThanQueueingBehindIt() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                int next = activity.getExactLargeTextAnchorForPage(2).charPosition;
                activity.beginLargeTextPartitionSwitchPending(1, 100);
                activity.largeTextPartitionSwitchState.markViewportHandoff();
                new ReaderLargeTextPagingController(activity).pageBy(1, false);
                assertEquals(1, activity.largeTextPartitionSwitchGeneration.get());
                assertFalse(activity.largeTextPartitionSwitchState.isInProgress());
                assertFalse(activity.largeTextPartitionSwitchState.hasQueuedTarget());
                assertEquals(next, activity.landedAt);
            } finally { releaseReader(activity); }
        });
    }

    @Test public void pendingAutomaticHandoffSavesVisibleRowNotPageStart() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                activity.readerView.scrollToPage(3);
                int target = activity.readerView.getCharPositionFromCurrentLineOffset(4);
                activity.readerView.scrollToCharPosition(target);
                int savedRow = activity.getBookmarkSaveCharPosition();
                int page = activity.findExactLargeTextPageForChar(savedRow);
                assertTrue(savedRow > activity.getExactLargeTextAnchorForPage(page).charPosition);
                activity.textContentReadyForPersistence = true;
                activity.beginLargeTextPartitionSwitchPending(page, 100);
                activity.largeTextPartitionSwitchState.markViewportHandoff();
                ReaderState automatic = new ReaderMemoryController(activity).captureReadingState();
                assertNotNull(automatic);
                assertEquals(savedRow, automatic.getCharPosition());
                activity.largeTextPartitionSwitchState.begin(page + 1, 100, page, 100);
                activity.largeTextPartitionSwitchState.setPendingTargetChar(
                        activity.getExactLargeTextAnchorForPage(page + 1).charPosition);
                ReaderState explicit = new ReaderMemoryController(activity).captureReadingState();
                assertEquals(activity.getExactLargeTextAnchorForPage(page + 1).charPosition,
                        explicit.getCharPosition());
            } finally { releaseReader(activity); }
        });
    }

    @Test public void unmeasuredPartitionWaitsForLayoutAndCancelledLandingStaysCancelled() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                activity.readerView.layout(0, 0, 0, 0);
                activity.beginLargeTextPartitionSwitchPending(2, 100);
                activity.applyLargeTextPartitionInPlace(partition(activity.fileContent, 0),
                        30, 2, 100, null, null, false, 0);
                assertFalse(activity.textContentReadyForPersistence);
                assertEquals(-1, activity.landedAt);
                activity.readerView.layout(0, 0, 720, 960);
                assertEquals(30, activity.landedAt);
                assertTrue(activity.textContentReadyForPersistence);
                activity.readerView.layout(0, 0, 0, 0);
                activity.beginLargeTextPartitionSwitchPending(3, 100);
                activity.applyLargeTextPartitionInPlace(partition(activity.fileContent, 0),
                        60, 3, 100, null, null, false, 0);
                activity.cancelLargeTextPartitionSwitchForLocalJump();
                activity.readerView.layout(0, 0, 720, 960);
                assertEquals(30, activity.landedAt);
            } finally { releaseReader(activity); }
        });
    }

    @Test public void backgroundRestoreUsesOneSnapshotWithoutOldPartitionHints() {
        ReaderState target = new ReaderState("/book.txt");
        target.setCharPosition(23456);
        target.setPageNumber(90);
        target.setTotalPages(100);
        target.setAnchorTextBefore("new-before");
        target.setAnchorTextAfter("new-after");
        Intent launch = new Intent().putExtra(ReaderActivity.EXTRA_FILE_PATH, "/book.txt")
                .putExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE, 1)
                .putExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE, 0L)
                .putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, 5);
        Intent restored = ReaderMemoryController.backgroundRestoreIntent(launch, target);
        assertEquals(23456, restored.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
        assertEquals(90, restored.getIntExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, -1));
        assertEquals("new-before", restored.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE));
        assertFalse(restored.hasExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE));
        assertFalse(restored.hasExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE));
        assertEquals(5, launch.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
    }

    @Test public void manualDragSupersedesPendingReadQueuedTapsAndSeekPreview() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                activity.readerSeekController = new ReaderSeekController(activity);
                activity.readerSeekController.beginPendingToolbarSeekJump(20, 100);
                activity.largeTextPartitionSwitchGeneration.set(7);
                activity.largeTextPartitionSwitchState.begin(20, 100, 1, 100);
                activity.largeTextPartitionSwitchState.queueTarget(210, 21, 100);
                activity.readerView.setReaderListener(new CustomReaderView.ReaderListener() {
                    public void onSingleTap(float x, float y) { }
                    public void onTextLongPress(String text, int start, int end, float x, float y) { }
                    public void onReaderScrollChanged() { }
                    public void onReaderManualScroll() { activity.onReaderManualScroll(); }
                    public void onReaderManualOverscroll(int direction) { }
                });
                touch(activity.readerView, MotionEvent.ACTION_DOWN, 700);
                touch(activity.readerView, MotionEvent.ACTION_MOVE, 300);
                assertEquals(8, activity.largeTextPartitionSwitchGeneration.get());
                assertFalse(activity.largeTextPartitionSwitchState.isInProgress());
                assertFalse(activity.largeTextPartitionSwitchState.hasQueuedTarget());
                assertFalse(activity.readerSeekController.showPendingPositionIfNeeded(1, 100));
                assertTrue(activity.textContentReadyForPersistence);
                assertTrue("cancellation must not swallow the new drag",
                        activity.readerView.isUserDraggingOrFlinging());
                assertTrue(activity.readerView.getCurrentCharPosition() > 0);
                touch(activity.readerView, MotionEvent.ACTION_CANCEL, 300);
            } finally {
                releaseReader(activity);
            }
        });
    }

    @Test public void measuredRowsSurviveTwoOverlappingWindowReplacements() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                ReaderPagePositionController positions = prepareReader(activity);
                int target = activity.readerView.getCharPositionFromCurrentLineOffset(4);
                int base = activity.readerView.getCharPositionFromCurrentLineOffset(1);
                activity.readerView.scrollToCharPosition(target);
                int page = activity.findExactLargeTextPageForChar(target);
                int expected = positions.getLineOffsetWithinDisplayedPage(page, 100);
                assertTrue(expected > 1);
                replaceWindow(activity, positions, base);
                activity.readerView.scrollToCharPosition(target - base);
                assertEquals(expected, positions.getLineOffsetWithinDisplayedPage(page, 100));
                int secondBase = base + activity.fileContent.indexOf('\n') + 1;
                replaceWindow(activity, positions, secondBase);
                activity.readerView.scrollToCharPosition(target - secondBase);
                assertEquals(expected, positions.getLineOffsetWithinDisplayedPage(page, 100));
                int nextPageAnchor = activity.getExactLargeTextAnchorForPage(page + 1).charPosition;
                activity.readerView.scrollToCharPosition(nextPageAnchor - secondBase);
                assertEquals("the next exact page starts at row one", 1,
                        positions.getLineOffsetWithinDisplayedPage(page + 1, 100));
            } finally {
                releaseReader(activity);
            }
        });
    }

    @Test public void rowOriginRejectsChangedLayoutAndUnknownNonoverlappingWindows() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                ReaderPagePositionController positions = prepareReader(activity);
                int base = activity.readerView.getCharPositionFromCurrentLineOffset(1);
                int target = activity.readerView.getCharPositionFromCurrentLineOffset(4);
                int page = activity.findExactLargeTextPageForChar(target);
                replaceWindow(activity, positions, base);
                activity.readerView.scrollToCharPosition(target - base);
                assertTrue(positions.getLineOffsetWithinDisplayedPage(page, 100) > 1);
                activity.layoutSignature = "changed-layout";
                assertEquals(1, positions.getLineOffsetWithinDisplayedPage(page, 100));
                activity.layoutSignature = "test-layout";
                LargeTextLinePartitionResult unknown = partition("unrelated\ntext", 1_000_000);
                positions.preparePartitionRowOrigin(unknown);
                assertEquals("an unavailable bridge must clear the previous origin", 1,
                        positions.getLineOffsetWithinDisplayedPage(page, 100));
            } finally {
                releaseReader(activity);
            }
        });
    }

    @Test public void fractionalScrollKeepsStatusAndSavedRowOnTheSameHalfVisibleBoundary() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                ReaderPagePositionController positions = prepareBoundaryReader(activity);
                int lastRow = activity.fileContent.indexOf("row 24\n");
                int nextPage = activity.fileContent.indexOf("row 25\n");
                assertFractionalPageBoundary(activity, positions, lastRow, nextPage);
            } finally { releaseReader(activity); }
        });
    }

    @Test public void fractionalScrollKeepsOffWindowOriginAndSavedRowOnTheSameHalfVisibleBoundary() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                ReaderPagePositionController positions = prepareBoundaryReader(activity);
                int lastRow = activity.fileContent.indexOf("row 24\n");
                int nextPage = activity.fileContent.indexOf("row 25\n");
                int base = activity.fileContent.indexOf("row 10\n");
                replaceWindow(activity, positions, base);
                assertTrue("the current page anchor is outside the replacement window",
                        activity.getExactLargeTextAnchorForPage(1).charPosition < base);
                assertFractionalPageBoundary(activity, positions, lastRow, nextPage);
            } finally { releaseReader(activity); }
        });
    }

    @Test public void normalAndEstimatedPagesUseTheSameHalfVisibleStatusRow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                ReaderPagePositionController positions = prepareReader(activity);
                activity.largeTextExactPageIndexState.reset();
                CustomReaderView view = activity.readerView;
                int next = view.getPageStartCharPosition(3);
                view.scrollToCharPosition(next);
                int nextTop = view.getReaderScrollY();
                int previous = view.getCharPositionFromCurrentLineOffset(-1);
                view.scrollToCharPosition(previous);
                int previousTop = view.getReaderScrollY();
                int height = nextTop - previousTop;
                assertTrue(height > 2);
                for (boolean estimated : new boolean[]{false, true}) {
                    activity.largeTextEstimateActive = estimated;
                    activity.largeTextEstimatedBasePageOffset = estimated ? 7 : 0;
                    activity.largeTextEstimatedTotalPages = estimated ? 500 : 0;
                    for (int hidden : new int[]{height / 2, height / 2 + 1}) {
                        view.setReaderScrollY(previousTop + hidden);
                        int displayChar = hidden * 2 > height ? next : previous;
                        int localPage = view.getPageNumberForCharPosition(displayChar);
                        int page = localPage + activity.largeTextEstimatedBasePageOffset;
                        assertEquals(page, positions.getStatusCurrentPageNumber());
                        assertEquals(view.getLineOffsetBetweenCharPositions(
                                        view.getPageStartCharPosition(localPage), displayChar),
                                positions.getLineOffsetWithinDisplayedPage(page, positions.getDisplayedTotalPageCount()));
                        assertEquals(previous, activity.getCurrentCharPosition());
                        assertEquals(displayChar, activity.getBookmarkSaveCharPosition());
                    }
                }
            } finally { releaseReader(activity); }
        });
    }

    @Test public void estimatedPendingTargetRetainsCharacterWhenExactIndexCompletes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                ReaderPagePositionController positions = prepareReader(activity);
                activity.largeTextExactPageIndexState.reset();
                activity.largeTextEstimatedTotalPages = 2;
                activity.beginLargeTextPartitionSwitchPending(1, 2);
                activity.largeTextPartitionSwitchState.setPendingTargetChar(120);
                activity.textContentReadyForPersistence = true;
                ReaderMemoryController memory = new ReaderMemoryController(activity);
                assertEquals(120, memory.captureReadingState().getCharPosition());
                setExactAnchors(activity, 0, 100, 200);
                assertEquals(3, positions.getDisplayedTotalPageCount());
                assertEquals(2, positions.getDisplayedCurrentPageNumber());
                ReaderState saved = memory.captureReadingState();
                assertEquals(120, saved.getCharPosition());
                assertEquals(2, saved.getPageNumber());
                assertEquals(3, saved.getTotalPages());
                assertEquals(120, activity.largeTextPartitionSwitchState.destinationChar());
                activity.applyLargeTextPartitionInPlace(partition(activity.fileContent, 0),
                        120, 1, 2, null, null, false, 0);
                assertEquals(120, activity.landedAt);
            } finally { releaseReader(activity); }
        });
    }

    @Test public void pendingBackwardSnapUsesSamePageStartAndSurvivesIndexInvalidation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                setExactAnchors(activity, 0, 100, 200);
                activity.beginLargeTextPartitionSwitchPending(1, 2);
                activity.largeTextPartitionSwitchState.setPendingTargetChar(120);
                activity.textContentReadyForPersistence = true;
                ReaderLargeTextPagingController paging = new ReaderLargeTextPagingController(activity);
                paging.pageBy(-1, false);
                assertTrue(activity.largeTextPartitionSwitchState.hasQueuedTarget());
                assertEquals(100, activity.largeTextPartitionSwitchState.destinationChar());
                assertEquals(2, activity.getDisplayedCurrentPageNumber());
                assertEquals(100, new ReaderMemoryController(activity).captureReadingState().getCharPosition());
                // A layout change must not reinterpret/drop an already accepted tap.
                activity.largeTextExactPageIndexState.reset();
                activity.clearLargeTextPartitionSwitchPending();
                paging.processQueuedLargeTextPageDeltaAfterPartitionApply();
                assertEquals(100, activity.landedAt);
                assertFalse(activity.largeTextPartitionSwitchState.hasQueuedTarget());
            } finally { releaseReader(activity); }
        });
    }

    @Test public void finalJumpUsesExactTotalInsteadOfOldPendingEstimate() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                setExactAnchors(activity, 0, 100, 200);
                activity.beginLargeTextPartitionSwitchPending(2, 2);
                activity.largeTextPartitionSwitchState.setPendingTargetChar(120);
                ReaderPageJumpController jumps = new ReaderPageJumpController(activity);
                assertTrue(jumps.jumpToFinalLargeTextPage(2, false));
                assertEquals(200, activity.landedAt);
                assertFalse(activity.largeTextPartitionSwitchState.isInProgress());
                activity.beginLargeTextPartitionSwitchPending(2, 2);
                activity.largeTextPartitionSwitchState.setPendingTargetChar(120);
                assertTrue(jumps.scrollToPageNumber(3, false, false));
                assertEquals(200, activity.landedAt);
            } finally { releaseReader(activity); }
        });
    }

    @Test public void exactOffsetCommitRejectsCapturedEstimateAndOutsideTargetHasNoOldContext() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                ReaderPagePositionController positions = prepareReader(activity);
                setExactAnchors(activity, 0, 100, 200);
                activity.readerView.scrollToCharPosition(120);
                int localPage = activity.readerView.getCurrentPageNumber();
                int exactPage = activity.findExactLargeTextPageForChar(activity.getCurrentCharPosition());
                positions.recomputeLargeTextDisplayPageOffset(99, 100);
                assertEquals(3, activity.largeTextEstimatedTotalPages);
                assertEquals(Math.max(0, exactPage - localPage), activity.largeTextEstimatedBasePageOffset);
                activity.beginLargeTextPartitionSwitchPending(2, 100);
                int outside = activity.fileContent.length() + 100;
                activity.largeTextPartitionSwitchState.setPendingTargetChar(outside);
                activity.textContentReadyForPersistence = true;
                ReaderState state = new ReaderMemoryController(activity).captureReadingState();
                assertEquals(outside, state.getCharPosition());
                assertEquals("", state.getAnchorTextBefore());
                assertEquals("", state.getAnchorTextAfter());
            } finally { releaseReader(activity); }
        });
    }

    @Test public void queuedDirectionChangesUseLastTargetAndExtraEndTapKeepsIt() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                setExactAnchors(activity, 0, 100, 200);
                activity.beginLargeTextPartitionSwitchPending(2, 3);
                activity.largeTextPartitionSwitchState.setPendingTargetChar(120);
                ReaderLargeTextPagingController paging = new ReaderLargeTextPagingController(activity);
                paging.pageBy(-1, false); // same page start 100
                paging.pageBy(-1, false); // previous page 0
                assertEquals(0, activity.largeTextPartitionSwitchState.destinationChar());
                paging.pageBy(1, false);
                paging.pageBy(1, false);
                paging.pageBy(1, false); // already at final anchor
                assertEquals(200, activity.largeTextPartitionSwitchState.destinationChar());
                activity.clearLargeTextPartitionSwitchPending();
                paging.processQueuedLargeTextPageDeltaAfterPartitionApply();
                assertEquals(200, activity.landedAt);
            } finally { releaseReader(activity); }
        });
    }

    @Test public void releasedSeekPreviewCannotMaskRebasedPendingPage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                activity.largeTextExactPageIndexState.reset();
                activity.largeTextEstimatedTotalPages = 100;
                activity.beginLargeTextPartitionSwitchPending(90, 100);
                activity.largeTextPartitionSwitchState.setPendingTargetChar(120);
                ReaderSeekController seek = new ReaderSeekController(activity);
                seek.beginPendingToolbarSeekJump(90, 100);
                setExactAnchors(activity, 0, 100, 200);
                assertEquals(2, activity.getDisplayedCurrentPageNumber());
                assertEquals(3, activity.getDisplayedTotalPageCount());
                assertFalse(seek.showPendingPositionIfNeeded(2, 3));
                assertEquals(120, activity.largeTextPartitionSwitchState.destinationChar());
            } finally { releaseReader(activity); }
        });
    }

    @Test public void loadedWindowEndIncludesLookaheadAndNotOnlyCanonicalBody() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                int lookahead = activity.getLargeTextPartitionLookaheadLines();
                activity.largeTextPartitionEndLine = 4000;
                activity.largeTextTotalLogicalLines = 4000 + lookahead + 1;
                assertFalse(activity.loadedTextWindowReachesDocumentEnd());
                activity.largeTextTotalLogicalLines--;
                assertTrue(activity.loadedTextWindowReachesDocumentEnd());
                activity.largeTextTotalLogicalLines = 4001;
                assertTrue(activity.loadedTextWindowReachesDocumentEnd());
                activity.largeTextEstimateActive = false;
                assertTrue(activity.loadedTextWindowReachesDocumentEnd());
            } finally { releaseReader(activity); }
        });
    }

    @Test public void sequentialForwardDoesNotReloadAnAlreadyVisibleEofLookahead() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                activity.largeTextExactPageIndexState.reset();
                activity.largeTextEstimatedTotalPages = 100;
                activity.largeTextPartitionEndLine = 4000;
                activity.largeTextTotalLogicalLines = 4001;
                for (String content : new String[]{"first\nlast", "first\n"}) {
                    activity.fileContent = content;
                    activity.largeTextPartitionBodyCharCount = 5;
                    activity.readerView.setTextContent(content, activity.loadedTextWindowReachesDocumentEnd());
                    assertTrue(activity.hasNextLargeTextPartition());
                    assertTrue(activity.readerView.isDocumentEndFullyVisible());
                    assertTrue(activity.getDisplayedCurrentPageNumber() < activity.getDisplayedTotalPageCount());
                    int scroll = activity.readerView.getReaderScrollY();
                    new ReaderLargeTextPagingController(activity).pageBy(1, false);
                    assertEquals(-1, activity.reloadRequestedAt);
                    assertEquals(-1, activity.landedAt);
                    assertEquals(scroll, activity.readerView.getReaderScrollY());
                }
            } finally { releaseReader(activity); }
        });
    }

    @Test public void partitionLandingRetainsOnlyTheRealEofBlankRowPosition() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                int base = 100;
                for (boolean finalWindow : new boolean[]{false, true}) {
                    for (String content : new String[]{"last", "last\n"}) {
                        int totalLines = finalWindow ? 4002
                                : 4002 + activity.getLargeTextPartitionLookaheadLines() + 1;
                        LargeTextLinePartitionResult loaded = new LargeTextLinePartitionResult(
                                content, 2, 4001, 4002, totalLines, base, 0,
                                content.length(), 4001, false, base + content.length());
                        activity.beginLargeTextPartitionSwitchPending(2, 3);
                        activity.applyLargeTextPartitionInPlace(loaded, base + content.length(),
                                2, 3, null, null, false, 0);
                        int expected = base + content.length()
                                - (finalWindow && content.endsWith("\n") ? 0 : 1);
                        assertEquals(expected, activity.landedAt);
                        assertTrue(activity.textContentReadyForPersistence);
                    }
                }
            } finally { releaseReader(activity); }
        });
    }

    private static void setExactAnchors(NavigationActivity activity, int... positions) {
        activity.largeTextExactPageIndexState.reset();
        ArrayList<CustomReaderView.PageTextAnchor> anchors = new ArrayList<>();
        for (int position : positions) anchors.add(new CustomReaderView.PageTextAnchor(position, "", ""));
        int generation = activity.largeTextExactPageIndexState.beginBuild(activity.layoutSignature);
        activity.largeTextExactPageIndexState.completeCurrent(generation,
                activity.layoutSignature, anchors, false, "");
    }

    private static ReaderPagePositionController prepareBoundaryReader(NavigationActivity activity) {
        ReaderPagePositionController positions = prepareReader(activity);
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < 80; row++) text.append("row ").append(row).append('\n');
        activity.fileContent = text.toString();
        activity.largeTextPartitionBodyCharCount = activity.fileContent.length();
        activity.readerView.setTextContent(activity.fileContent);
        // Explicit whole-document anchors isolate status coordinates from viewport capacity.
        setExactAnchors(activity, 0, activity.fileContent.indexOf("row 25\n"),
                activity.fileContent.indexOf("row 50\n"));
        return positions;
    }

    private static void assertFractionalPageBoundary(NavigationActivity activity,
            ReaderPagePositionController positions, int lastRow, int nextPage) {
        CustomReaderView view = activity.readerView;
        int base = activity.largeTextPreviewBaseCharOffset;
        view.scrollToCharPosition(lastRow - base);
        int lastRowTop = view.getReaderScrollY();
        view.scrollToCharPosition(nextPage - base);
        int nextRowTop = view.getReaderScrollY();
        int rowHeight = nextRowTop - lastRowTop;
        assertTrue("fixture must provide distinct rendered rows", rowHeight > 2);
        activity.textContentReadyForPersistence = true;
        for (int direction : new int[]{1, -1}) {
            for (int step = 0; step < rowHeight; step++) {
                int hidden = direction > 0 ? step : rowHeight - 1 - step;
                boolean next = hidden * 2 > rowHeight;
                int expectedChar = next ? nextPage : lastRow;
                int expectedPage = next ? 2 : 1;
                view.setReaderScrollY(lastRowTop + hidden);
                assertEquals("raw navigation coordinates must not change",
                        lastRow, activity.getCurrentCharPosition());
                assertEquals(1, positions.getDisplayedCurrentPageNumber());
                assertEquals(expectedPage, positions.getStatusCurrentPageNumber());
                assertEquals(next ? 1 : 25,
                        positions.getLineOffsetWithinDisplayedPage(expectedPage, 3));
                assertEquals(expectedChar, activity.getBookmarkSaveCharPosition());
                ReaderState saved = new ReaderMemoryController(activity).captureReadingState();
                assertNotNull(saved);
                assertEquals(expectedChar, saved.getCharPosition());
                assertEquals(expectedPage, saved.getPageNumber());
                Bookmark bookmark = new Bookmark(activity.filePath, "fixture", expectedChar, 1, "");
                new ReaderBookmarkPageModelController(activity).applyCurrentSavePageModel(bookmark);
                assertEquals(expectedChar, bookmark.getCharPosition());
                assertEquals(expectedPage, bookmark.getPageNumber());
            }
        }
        view.setReaderScrollY(nextRowTop);
        assertEquals(nextPage, activity.getCurrentCharPosition());
        assertEquals(2, positions.getStatusCurrentPageNumber());
        assertEquals("the next page starts at row one", 1,
                positions.getLineOffsetWithinDisplayedPage(2, 3));

        view.setReaderScrollY(lastRowTop + rowHeight / 2 + 1);
        activity.beginLargeTextPartitionSwitchPending(1, 3);
        activity.largeTextPartitionSwitchState.setPendingTargetChar(lastRow);
        activity.largeTextPartitionSwitchState.markViewportHandoff();
        ReaderState automatic = new ReaderMemoryController(activity).captureReadingState();
        assertEquals("automatic disk-window replacement still saves the half-visible row",
                nextPage, automatic.getCharPosition());
        assertEquals(2, automatic.getPageNumber());
        int thirdPage = activity.getExactLargeTextAnchorForPage(3).charPosition;
        activity.largeTextPartitionSwitchState.queueTarget(thirdPage, 3, 3);
        ReaderState queued = new ReaderMemoryController(activity).captureReadingState();
        assertEquals("an explicit queued destination still takes precedence over the visible row",
                thirdPage, queued.getCharPosition());
        assertEquals(3, queued.getPageNumber());
        Bookmark manual = new Bookmark(activity.filePath, "fixture",
                activity.getBookmarkSaveCharPosition(), 1, "");
        new ReaderBookmarkPageModelController(activity).applyCurrentSavePageModel(manual);
        assertEquals(nextPage, manual.getCharPosition());
        assertEquals("manual bookmark metadata describes its visible saved character", 2, manual.getPageNumber());
        activity.largeTextPartitionSwitchState.reset();
    }

    private static ReaderPagePositionController prepareReader(NavigationActivity activity) {
        activity.readerView = new CustomReaderView(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        activity.prefs = com.readwide.manager.util.PrefsManager.getInstance(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        activity.readerView.setOverlapLines(activity.prefs.getPagingOverlapLines());
        activity.readerView.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE,
                24, 16, Typeface.DEFAULT);
        activity.readerView.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        activity.readerView.layout(0, 0, 720, 960);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 180; i++) {
            if (i > 0) text.append('\n');
            if (i % 3 != 1) text.append("row ").append(i);
            if (i == 2) {
                for (int word = 0; word < 40; word++) text.append(" wrapped");
            }
        }
        activity.fileContent = text.toString();
        activity.filePath = "/row-origin.txt";
        activity.largeTextEstimateActive = true;
        activity.readerView.setTextContent(activity.fileContent);
        activity.largeTextPartitionBodyCharCount = activity.fileContent.length();
        ArrayList<CustomReaderView.PageTextAnchor> anchors = CustomReaderView.buildPageTextAnchors(
                activity.fileContent, activity.readerView.copyTextPaintForIndex(),
                activity.readerView.getTextLayoutWidthForIndex(), activity.readerView.getViewportHeight(),
                activity.readerView.getMarginVerticalPxForIndex(), activity.readerView.getOverlapLinesForIndex(),
                activity.readerView.getLineSpacingMultiplierForIndex());
        int generation = activity.largeTextExactPageIndexState.beginBuild(activity.layoutSignature);
        activity.largeTextExactPageIndexState.completeCurrent(generation,
                activity.layoutSignature, anchors, false, "");
        return new ReaderPagePositionController(activity);
    }

    private static void replaceWindow(NavigationActivity activity,
                                      ReaderPagePositionController positions, int absoluteBase) {
        String next = activity.fileContent.substring(absoluteBase - activity.largeTextPreviewBaseCharOffset);
        positions.preparePartitionRowOrigin(partition(next, absoluteBase));
        activity.fileContent = next;
        activity.largeTextPreviewBaseCharOffset = absoluteBase;
        activity.readerView.setTextContent(next);
    }

    private static LargeTextLinePartitionResult partition(String text, int base) {
        return new LargeTextLinePartitionResult(text, 1, 1, 1, 1, base, 0,
                text.length(), 1, false, base + text.length());
    }

    private static void touch(CustomReaderView view, int action, float y) {
        MotionEvent event = MotionEvent.obtain(0, 20, action, 300, y, 0);
        try { view.onTouchEvent(event); } finally { event.recycle(); }
    }

    private static void releaseReader(NavigationActivity activity) {
        activity.clearLargeTextPartitionSwitchPending();
        if (activity.readerView != null) activity.readerView.releaseTextResources();
        activity.handler.removeCallbacksAndMessages(null);
        activity.executor.shutdownNow();
        activity.largeTextPartitionExecutor.shutdownNow();
        activity.largeTextSearchExecutor.shutdownNow();
        activity.largeTextSearchCountExecutor.shutdownNow();
    }

    @Test public void localJumpSupersedesPendingPartitionReadAndQueuedTaps() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                activity.largeTextEstimateActive = true;
                activity.fileContent = "first line\nsecond line\nthird line";
                activity.largeTextPartitionBodyCharCount = activity.fileContent.length();
                activity.largeTextPartitionSwitchGeneration.set(7);
                activity.largeTextPartitionSwitchState.begin(20, 100, 1, 100);
                activity.largeTextPartitionSwitchState.queueTarget(210, 21, 100);
                new ReaderLargeTextJumpController(activity).jumpToAbsoluteCharPosition(
                        11, 2, 100, null, null, false, false);
                assertEquals(11, activity.landedAt);
                assertEquals(8, activity.largeTextPartitionSwitchGeneration.get());
                assertFalse(activity.largeTextPartitionSwitchState.isInProgress());
                assertFalse(activity.largeTextPartitionSwitchState.hasQueuedTarget());
                assertTrue(activity.textContentReadyForPersistence);
            } finally {
                activity.handler.removeCallbacksAndMessages(null);
                activity.executor.shutdownNow();
                activity.largeTextPartitionExecutor.shutdownNow();
                activity.largeTextSearchExecutor.shutdownNow();
                activity.largeTextSearchCountExecutor.shutdownNow();
            }
        });
    }

    // Exercise controller ownership without launching a document or changing preferences.
    private static final class NavigationActivity extends ReaderActivity {
        @Override boolean ensureLoadedTextCurrent() { return true; } // In-memory geometry fixture.
        int landedAt = -1;
        int searchLandedAt = -1;
        int reloadRequestedAt = -1;
        int snapshotRequests;
        String layoutSignature = "test-layout";
        @Override void scrollToCharPosition(int position) { landedAt = position; }
        @Override void recomputeLargeTextDisplayPageOffset(int page, int total) { }
        @Override void updatePositionLabel() { }
        @Override void stopAutoPageTurnForManualNavigation() { }
        @Override void scrollToSearchResultPosition(int position) { searchLandedAt = position; }
        @Override void refreshTxtAnnotationHighlights() { }
        @Override void applySearchHighlight() { }
        @Override void prefetchNeighborLargeTextPartitions() { }
        @Override void cacheLargeTextPartition(LargeTextLinePartitionResult partition) { }
        @Override void cacheLoadedTextSnapshot() { snapshotRequests++; }
        @Override void reloadLargeTextPreviewAround(int position, int page, int total,
                String before, String after, int startLine, boolean showLoading) {
            reloadRequestedAt = position;
        }
        @Override String readerPageLayoutSignatureForPath(String path) { return layoutSignature; }
        @Override String buildCurrentLargeTextExactPageIndexSignature(String path) { return layoutSignature; }
    }

    @Test public void currentPositionReplacesOpeningBookmarkAndOldPartitionCoordinates() {
        Intent launch = new Intent().putExtra(ReaderActivity.EXTRA_FILE_PATH, "/book.txt")
                .putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, 900)
                .putExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE, 500)
                .putExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE, 1000L)
                .putExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, 50)
                .putExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, 300);
        ReaderState current = new ReaderState("/book.txt");
        current.setCharPosition(1234);
        current.setAnchorTextBefore("before");
        current.setAnchorTextAfter("after");
        Bundle saved = ReaderLifecycleController.viewportBundle(launch, current);
        Intent restored = ReaderLifecycleController.restoreViewportIntent(launch, saved);
        assertEquals(1234, restored.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
        assertEquals("before", restored.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE));
        assertEquals("after", restored.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER));
        assertFalse(restored.hasExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE));
        assertFalse(restored.hasExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE));
        assertFalse(restored.hasExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE));
        assertFalse(restored.hasExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES));
        assertEquals(900, launch.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
    }

    @Test public void recreationRetainsExplicitAndQueuedTargetsInsteadOfTheReadyOldWindow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                activity.setIntent(new Intent().putExtra(ReaderActivity.EXTRA_FILE_PATH, activity.filePath));
                activity.textContentReadyForPersistence = true;
                setExactAnchors(activity, 0, 100, 200);
                int visible = activity.getBookmarkSaveCharPosition();
                activity.beginLargeTextPartitionSwitchPending(2, 3);
                activity.largeTextPartitionSwitchState.setPendingTargetChar(120);
                ReaderLifecycleController lifecycle = new ReaderLifecycleController(activity);
                Bundle first = new Bundle();
                lifecycle.onSaveInstanceState(first);
                assertEquals(120, first.getBundle(ReaderLifecycleController.STATE_VIEWPORT).getInt("position", -1));
                assertTrue(first.getBoolean(ReaderActivity.STATE_RESTORE_FROM_MEMORY));
                assertEquals(1, activity.snapshotRequests);
                assertFalse("the cached old screen must not override an explicit destination",
                        ReaderLifecycleController.snapshotMatchesSavedViewport(activity.getIntent(), first, visible));
                activity.largeTextPartitionSwitchState.queueTarget(200, 3, 3);
                Bundle queued = new Bundle();
                lifecycle.onSaveInstanceState(queued);
                assertEquals(200, queued.getBundle(ReaderLifecycleController.STATE_VIEWPORT).getInt("position", -1));
                assertEquals(200, activity.captureReaderViewport().getCharPosition());
                assertEquals(200, new ReaderMemoryController(activity).captureReadingState().getCharPosition());
                assertFalse(ReaderLifecycleController.snapshotMatchesSavedViewport(activity.getIntent(), queued, visible));
                assertTrue(ReaderLifecycleController.snapshotMatchesSavedViewport(activity.getIntent(), queued, 200));
            } finally { releaseReader(activity); }
        });
    }

    @Test public void adoptedUnmeasuredPartitionKeepsItsTargetOnlyForRecreation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                activity.setIntent(new Intent().putExtra(ReaderActivity.EXTRA_FILE_PATH, activity.filePath));
                String original = activity.fileContent;
                int base = original.indexOf('\n') + 1;
                int target = base + 30;
                activity.readerView.layout(0, 0, 0, 0);
                activity.beginLargeTextPartitionSwitchPending(2, 100);
                activity.applyLargeTextPartitionInPlace(partition(original.substring(base), base),
                        target, 2, 100, null, null, false, 0);
                assertFalse(activity.textContentReadyForPersistence);
                assertEquals(-1, activity.landedAt);
                ReaderMemoryController memory = new ReaderMemoryController(activity);
                assertNull("an adopted but unlanded window must not overwrite persistent reading state",
                        memory.captureReadingState());
                ReaderState temporary = memory.captureReadingStateForRecreation();
                assertNotNull(temporary);
                assertEquals(target, temporary.getCharPosition());
                assertEquals(activity.getAnchorTextBefore(target), temporary.getAnchorTextBefore());
                assertEquals(activity.getAnchorTextAfter(target), temporary.getAnchorTextAfter());
                assertEquals(target, activity.captureReaderViewport().getCharPosition());
                Bundle saved = new Bundle();
                new ReaderLifecycleController(activity).onSaveInstanceState(saved);
                assertEquals(target, saved.getBundle(ReaderLifecycleController.STATE_VIEWPORT).getInt("position", -1));
                assertFalse(saved.getBoolean(ReaderActivity.STATE_RESTORE_FROM_MEMORY));
                assertEquals(0, activity.snapshotRequests);

                int outside = base + activity.fileContent.length() + 100;
                activity.largeTextPartitionSwitchState.queueTarget(outside, 3, 100);
                ReaderState queued = memory.captureReadingStateForRecreation();
                assertEquals(outside, queued.getCharPosition());
                assertEquals("", queued.getAnchorTextBefore());
                assertEquals("", queued.getAnchorTextAfter());
                assertNull(memory.captureReadingState());
            } finally { releaseReader(activity); }
        });
    }

    @Test public void repeatedRecreationBeforeLandingCarriesTheNewestSavedDestination() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                Intent launch = new Intent().putExtra(ReaderActivity.EXTRA_FILE_PATH, "/recreate.txt")
                        .putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, 9);
                activity.setIntent(launch);
                ReaderState pending = new ReaderState("/recreate.txt");
                pending.setCharPosition(54321);
                pending.setAnchorTextBefore("target-before");
                pending.setAnchorTextAfter("target-after");
                Bundle saved = new Bundle();
                saved.putBundle(ReaderLifecycleController.STATE_VIEWPORT,
                        ReaderLifecycleController.viewportBundle(launch, pending));
                for (int recreation = 0; recreation < 3; recreation++) {
                    ReaderLifecycleController lifecycle = new ReaderLifecycleController(activity);
                    Intent restored = lifecycle.intentForRecreation(saved);
                    assertEquals(54321, restored.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
                    assertEquals("target-before", restored.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE));
                    assertNull(new ReaderMemoryController(activity).captureReadingState());
                    Bundle next = new Bundle();
                    lifecycle.onSaveInstanceState(next);
                    assertEquals(54321, next.getBundle(ReaderLifecycleController.STATE_VIEWPORT).getInt("position", -1));
                    assertFalse(next.getBoolean(ReaderActivity.STATE_RESTORE_FROM_MEMORY));
                    saved = next;
                }
                assertEquals(0, activity.snapshotRequests);
                assertEquals(9, launch.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
            } finally { releaseReader(activity); }
        });
    }

    @Test public void cancelledOrInactivePendingStateCannotCreateARecreationCoordinate() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationActivity activity = new NavigationActivity();
            try {
                prepareReader(activity);
                activity.setIntent(new Intent().putExtra(ReaderActivity.EXTRA_FILE_PATH, activity.filePath));
                activity.readerView.layout(0, 0, 0, 0);
                activity.textContentReadyForPersistence = false;
                ReaderMemoryController memory = new ReaderMemoryController(activity);
                assertNull(memory.captureReadingState());
                assertNull(memory.captureReadingStateForRecreation());
                activity.beginLargeTextPartitionSwitchPending(2, 100);
                activity.largeTextPartitionSwitchState.setPendingTargetChar(0);
                assertEquals("the first character is a valid explicit destination",
                        0, memory.captureReadingStateForRecreation().getCharPosition());
                activity.largeTextPartitionSwitchState.setPendingTargetChar(123);
                assertEquals(123, memory.captureReadingStateForRecreation().getCharPosition());
                activity.cancelLargeTextPartitionSwitchForLocalJump();
                assertNull(memory.captureReadingStateForRecreation());

                activity.beginLargeTextPartitionSwitchPending(2, 100);
                activity.largeTextPartitionSwitchState.queueTarget(234, 3, 100);
                activity.clearLargeTextPartitionSwitchPending(); // Failure cleanup may retain a queued value briefly.
                assertNull("inactive queued data is not an owned destination", memory.captureReadingStateForRecreation());
                activity.largeTextPartitionSwitchState.reset();
                activity.beginLargeTextPartitionSwitchPending(2, 100);
                activity.largeTextPartitionSwitchState.setPendingTargetChar(345);
                activity.largeTextEstimateActive = false; // New document load has left the large-TXT mode.
                assertNull(memory.captureReadingStateForRecreation());
                activity.largeTextEstimateActive = true;
                activity.backgroundTextMemoryReleased = true;
                assertNull(memory.captureReadingStateForRecreation());
                activity.backgroundTextMemoryReleased = false;
                activity.filePath = null;
                assertNull(memory.captureReadingStateForRecreation());
                assertNull(memory.captureReadingState());
            } finally { releaseReader(activity); }
        });
    }

    @Test public void snapshotGateMatchesSavedLaunchAndPositionIncludingZero() {
        Intent launch = new Intent().putExtra(ReaderActivity.EXTRA_FILE_PATH, "/book.txt")
                .putExtra(ReaderActivity.EXTRA_FILE_URI, "content://books/a");
        assertTrue(ReaderLifecycleController.snapshotMatchesSavedViewport(launch, null, 123));
        assertTrue(ReaderLifecycleController.snapshotMatchesSavedViewport(launch, new Bundle(), 123));
        for (int position : new int[]{0, 12345}) {
            ReaderState state = new ReaderState("/book.txt");
            state.setCharPosition(position);
            Bundle saved = new Bundle();
            saved.putBundle(ReaderLifecycleController.STATE_VIEWPORT,
                    ReaderLifecycleController.viewportBundle(launch, state));
            assertTrue(ReaderLifecycleController.snapshotMatchesSavedViewport(launch, saved, position));
            assertFalse(ReaderLifecycleController.snapshotMatchesSavedViewport(launch, saved, position + 1));
            assertFalse(ReaderLifecycleController.snapshotMatchesSavedViewport(launch, saved, -1));
            assertFalse(ReaderLifecycleController.snapshotMatchesSavedViewport(
                    new Intent(launch).putExtra(ReaderActivity.EXTRA_FILE_PATH, "/other.txt"), saved, position));
            assertFalse(ReaderLifecycleController.snapshotMatchesSavedViewport(
                    new Intent(launch).putExtra(ReaderActivity.EXTRA_FILE_URI, "content://books/b"), saved, position));
        }
        ReaderState invalid = new ReaderState("/book.txt");
        invalid.setCharPosition(-1);
        Bundle invalidSaved = new Bundle();
        invalidSaved.putBundle(ReaderLifecycleController.STATE_VIEWPORT,
                ReaderLifecycleController.viewportBundle(launch, invalid));
        assertFalse(ReaderLifecycleController.snapshotMatchesSavedViewport(launch, invalidSaved, -1));
    }

    @Test public void topOfFileClearsOldAnchorsAndAnotherUriDoesNotUseSavedPosition() {
        Intent launch = new Intent().putExtra(ReaderActivity.EXTRA_FILE_URI, "content://books/a")
                .putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, 999)
                .putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE, "old-before")
                .putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER, "old-after");
        ReaderState top = new ReaderState();
        top.setCharPosition(0);
        Bundle saved = ReaderLifecycleController.viewportBundle(launch, top);
        Intent restored = ReaderLifecycleController.restoreViewportIntent(launch, saved);
        assertEquals(0, restored.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
        assertEquals("", restored.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE));
        assertEquals("", restored.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER));
        Intent other = new Intent(launch).putExtra(ReaderActivity.EXTRA_FILE_URI, "content://books/b");
        assertSame(other, ReaderLifecycleController.restoreViewportIntent(other, saved));
        assertSame(launch, ReaderLifecycleController.restoreViewportIntent(launch, null));
    }
}
