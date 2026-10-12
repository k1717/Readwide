package com.readwide.manager.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.graphics.Color;
import android.graphics.Typeface;
import android.content.Context;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.view.MotionEvent;
import android.widget.OverScroller;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.LargeTextContinuityMath;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class CustomReaderViewContinuityInstrumentedTest {
    private static final String REAL_FIXTURE_ASSET = "large_txt_real_fixture.txt";

    @Test
    public void viewportShorterThanOneRowKeepsItsAvailableDrawSlice() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            try {
                view.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE, 24, 0, Typeface.DEFAULT);
                view.setOverlapLines(0);
                String content = "first\nsecond\nthird\nfourth\nfifth";
                view.setTextContent(content);
                StaticLayout layout = textLayout(view);
                int rowHeight = layout.getLineBottom(1) - layout.getLineTop(1);
                sizeReader(view, 720, Math.max(1, rowHeight / 2));
                view.scrollToPage(2);
                assertEquals(content.indexOf("second"), view.getCurrentCharPosition());
                assertEquals("a tall row must not make the text clip empty",
                        view.getViewportHeight(), fullLineClipBottom(view));
                int[] offsets = CustomReaderView.buildPageStartOffsets(content, content,
                        view.copyTextPaintForIndex(), view.getTextLayoutWidthForIndex(),
                        view.getViewportHeight(), view.getMarginVerticalPxForIndex(),
                        view.getOverlapLinesForIndex(), view.getLineSpacingMultiplierForIndex());
                assertEquals("drawing fallback must not change page anchors",
                        offsets.length, view.getTotalPageCount());
                assertEquals(offsets[1], view.getCurrentCharPosition());
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void tinyViewportRetainsAPositiveClipWhileScrollingThroughTallRows() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            try {
                view.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE, 24, 0, Typeface.DEFAULT);
                view.setOverlapLines(0);
                view.setTextContent("first\nsecond\nthird\nfourth\nfifth");
                StaticLayout layout = textLayout(view);
                int rowHeight = layout.getLineBottom(1) - layout.getLineTop(1);
                sizeReader(view, 720, Math.max(1, rowHeight / 2));
                layout = textLayout(view);
                int start = layout.getLineTop(1);
                int end = layout.getLineTop(3);
                for (int direction : new int[]{1, -1}) {
                    for (int pixel = 0; pixel < end - start; pixel++) {
                        int scroll = direction > 0 ? start + pixel : end - 1 - pixel;
                        view.setReaderScrollY(scroll);
                        int clipBottom = fullLineClipBottom(view);
                        assertTrue("scrolling inside a tall row must not blank the viewport", clipBottom > 0);
                        assertTrue("the fallback must not draw below the viewport",
                                clipBottom <= view.getViewportHeight());
                    }
                }
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void normalViewportStillSuppressesItsPartialBottomRowWhileScrolling() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            try {
                view.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE, 24, 0, Typeface.DEFAULT);
                view.setTextContent("first\nsecond\nthird\nfourth\nfifth\nsixth\nseventh");
                StaticLayout layout = textLayout(view);
                int rowHeight = layout.getLineBottom(1) - layout.getLineTop(1);
                sizeReader(view, 720, 2 * rowHeight + Math.max(1, rowHeight / 3));
                layout = textLayout(view);
                int start = layout.getLineTop(1);
                for (int pixel : new int[]{0, Math.max(1, rowHeight / 3)}) {
                    view.setReaderScrollY(start + pixel);
                    int visibleBottom = view.getReaderScrollY() + view.getViewportHeight();
                    int bottomLine = layout.getLineForVertical(visibleBottom);
                    assertTrue(layout.getLineBottom(bottomLine) > visibleBottom);
                    int expectedClip = layout.getLineTop(bottomLine) - view.getReaderScrollY();
                    assertTrue(expectedClip > 0 && expectedClip < view.getViewportHeight());
                    assertEquals("ordinary incomplete bottom rows must stay hidden",
                            expectedClip, fullLineClipBottom(view));
                }
            } finally {
                view.releaseTextResources();
            }
        });
    }

    private static int fullLineClipBottom(CustomReaderView view) {
        try {
            java.lang.reflect.Method method = CustomReaderView.class.getDeclaredMethod("getFullLineClipBottom");
            method.setAccessible(true);
            return (Integer) method.invoke(view);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    public void displayRowChangesOnlyAfterMoreThanHalfTheMeasuredRowIsHidden() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            try {
                view.setTextContent(numberedLines(180, "half row"));
                StaticLayout layout = textLayout(view);
                int line = 12;
                int current = layout.getLineStart(line);
                int next = layout.getLineStart(line + 1);
                int rowHeight = layout.getLineBottom(line) - layout.getLineTop(line);
                int start = layout.getLineTop(line) + view.getMarginVerticalPxForIndex();
                int total = view.getTotalPageCount();
                for (int direction : new int[]{1, -1}) {
                    for (int step = 0; step < rowHeight; step++) {
                        int hidden = direction > 0 ? step : rowHeight - 1 - step;
                        view.setReaderScrollY(start + hidden);
                        assertEquals(hidden * 2 > rowHeight ? next : current,
                                view.getDisplayTopCharPosition());
                        assertEquals("raw navigation keeps its top-edge row",
                                current, view.getCurrentCharPosition());
                        assertEquals(total, view.getTotalPageCount());
                    }
                }
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void shortViewportNeverSelectsAnUndrawnNextRowForStatusOrSaving() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            try {
                view.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE, 24, 0, Typeface.DEFAULT);
                view.setTextContent("first\nsecond\nthird\nfourth\nfifth");
                StaticLayout layout = textLayout(view);
                int rowHeight = layout.getLineBottom(1) - layout.getLineTop(1);
                sizeReader(view, 720, Math.max(1, rowHeight / 4));
                layout = textLayout(view);
                for (int hidden = rowHeight / 2 + 1; hidden < rowHeight; hidden++) {
                    view.setReaderScrollY(layout.getLineTop(1) + hidden);
                    assertEquals("do not choose a row below or suppressed by the drawing clip",
                            layout.getLineStart(1), view.getDisplayTopCharPosition());
                }
                view.setReaderScrollY(layout.getLineTop(2));
                assertEquals(layout.getLineStart(2), view.getDisplayTopCharPosition());
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void overlapChangesKeepSinglePageDocumentWithinNaturalBounds() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView changed = laidOutReaderView();
            CustomReaderView fresh = laidOutReaderView();
            try {
                String content = "first row\nsecond row";
                changed.setOverlapLines(2);
                changed.setTextContent(content);
                fresh.setOverlapLines(0);
                fresh.setTextContent(content);
                assertEquals("overlap must not create a second page from already visible rows",
                        1, changed.getTotalPageCount());
                assertEquals(0, changed.getMaxScrollY());
                changed.scrollToVisualEndOfText();
                changed.setOverlapLines(0);
                assertEquals(fresh.getTotalPageCount(), changed.getTotalPageCount());
                assertEquals(fresh.getMaxScrollY(), changed.getMaxScrollY());
                assertTrue(changed.getReaderScrollY() <= changed.getMaxScrollY());
                changed.scrollToPage(changed.getTotalPageCount());
                fresh.scrollToPage(fresh.getTotalPageCount());
                assertEquals(fresh.getReaderScrollY(), changed.getReaderScrollY());
                changed.scrollToVisualEndOfText();
                fresh.scrollToVisualEndOfText();
                assertEquals(fresh.getReaderScrollY(), changed.getReaderScrollY());
                assertEquals(fresh.getCurrentCharPosition(), changed.getCurrentCharPosition());
            } finally {
                changed.releaseTextResources();
                fresh.releaseTextResources();
            }
        });
    }

    @Test
    public void shortDocumentsHaveOnePageAndEndSentinelForEveryOverlap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            try {
                for (String content : new String[]{"row", "first\nlast", "row\n", "first\nlast\n"}) {
                    for (int overlap = 0; overlap <= 8; overlap++) {
                        view.setOverlapLines(overlap);
                        view.setTextContent(content);
                        assertEquals(1, view.getTotalPageCount());
                        assertEquals(0, view.getMaxScrollY());
                        assertPageCoverageAndOverlap(view);
                        assertEquals(0, view.getCurrentCharPosition());
                        assertEquals(content.length(), view.getCharPositionAfterCurrentVisibleContent());
                    }
                }
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void nonterminalWindowRetainsOverlapUntilRealEofIsKnown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            try {
                String content = "first\nlast";
                view.setOverlapLines(1);
                view.setTextContent(content, false);
                assertFalse(view.isDocumentEndFullyVisible());
                int[] continued = CustomReaderView.buildPageStartOffsets(content, content,
                        view.copyTextPaintForIndex(), view.getTextLayoutWidthForIndex(),
                        view.getViewportHeight(), view.getMarginVerticalPxForIndex(),
                        view.getOverlapLinesForIndex(), view.getLineSpacingMultiplierForIndex(), false);
                assertEquals(2, continued.length);
                assertEquals(continued.length, view.getTotalPageCount());
                view.scrollToPage(1);
                assertEquals(content.indexOf('\n') + 1,
                        view.getCharPositionForNextPageStartRespectingOverlap());
                view.setTextContent(content, true);
                assertTrue(view.isDocumentEndFullyVisible());
                assertEquals(1, view.getTotalPageCount());
                assertEquals(0, view.getMaxScrollY());
                assertEquals(content.length(), view.getCharPositionForNextPageStartRespectingOverlap());
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void exactlyFittingFinalLineDoesNotCreateAnOverlapOnlyPage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            try {
                // No margins: the last line bottom equals the actual viewport boundary,
                // not just a comfortably fitting short-document case.
                view.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE, 24, 0, Typeface.DEFAULT);
                for (String content : new String[]{"row", "first\nlast", "first\nlast\n"}) {
                    view.setTextContent(content);
                    int exactHeight = textLayout(view).getHeight();
                    sizeReader(view, 720, exactHeight);
                    for (int overlap = 0; overlap <= 8; overlap++) {
                        view.setOverlapLines(overlap);
                        view.scrollToPage(1);
                        StaticLayout layout = textLayout(view);
                        assertEquals(layout.getLineBottom(layout.getLineCount() - 1),
                                view.getViewportHeight());
                        assertEquals(0, view.getReaderScrollY());
                        assertEquals(1, view.getTotalPageCount());
                        assertPageCoverageAndOverlap(view);
                    }
                }
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void endCoverageWaitsForTheCurrentTextLayout() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            try {
                view.setTextContent("short\n", true);
                assertTrue(view.isDocumentEndFullyVisible());
                sizeReader(view, 1, 960); // Horizontal margins consume the width.
                view.setTextContent(numberedLines(200, "replacement"), true);
                assertFalse(view.hasCurrentTextLayout());
                assertFalse(view.isDocumentEndFullyVisible());
                sizeReader(view, 720, 960);
                view.scrollToPage(1);
                assertTrue(view.hasCurrentTextLayout());
                assertFalse(view.isDocumentEndFullyVisible());
                view.scrollToPage(view.getTotalPageCount());
                assertTrue(view.isDocumentEndFullyVisible());
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void partialFinalPageKeepsOrdinaryOverlapWithoutRepeatingTheVisibleTail() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            try {
                StringBuilder rows = new StringBuilder();
                for (int row = 0; row < 41; row++) {
                    if (row > 0) rows.append('\n');
                    rows.append("Row ").append(row);
                }
                for (boolean terminalNewline : new boolean[]{false, true}) {
                    String content = rows.toString() + (terminalNewline ? "\n" : "");
                    view.setTextContent(content);
                    StaticLayout layout = textLayout(view);
                    assertEquals(terminalNewline ? 42 : 41, layout.getLineCount());
                    int rowHeight = layout.getLineTop(2) - layout.getLineTop(1);
                    sizeReader(view, 720, layout.getLineBottom(11) + Math.max(2, rowHeight / 2));
                    for (int overlap = 0; overlap <= 8; overlap++) {
                        view.setOverlapLines(overlap);
                        view.scrollToPage(1);
                        assertTrue(view.getTotalPageCount() > 2);
                        layout = textLayout(view);
                        int firstPad = Math.max(0,
                                layout.getLineBaseline(0) - layout.getLineTop(0)
                                - (layout.getLineBaseline(1) - layout.getLineTop(1)));
                        assertEquals("EOF correction must retain the first-page visual anchor",
                                view.getMarginVerticalPxForIndex() + firstPad,
                                view.getReaderScrollY());
                        assertPageCoverageAndOverlap(view);
                        int finalBottom = view.getReaderScrollY() - view.getMarginVerticalPxForIndex()
                                + view.getViewportHeight();
                        assertTrue("fixture must end on a partial final page",
                                finalBottom > layout.getLineBottom(layout.getLineCount() - 1));
                    }
                }
            } finally {
                view.releaseTextResources();
            }
        });
    }

    private static void assertPageCoverageAndOverlap(CustomReaderView view) {
        String content = view.getTextContent();
        StaticLayout layout = textLayout(view);
        int[] offsets = CustomReaderView.buildPageStartOffsets(content, content,
                view.copyTextPaintForIndex(), view.getTextLayoutWidthForIndex(),
                view.getViewportHeight(), view.getMarginVerticalPxForIndex(),
                view.getOverlapLinesForIndex(), view.getLineSpacingMultiplierForIndex());
        assertEquals(offsets.length, view.getTotalPageCount());
        assertEquals(0, offsets[0]);
        for (int page = 1; page <= offsets.length; page++) {
            view.scrollToPage(page);
            assertEquals(offsets[page - 1], view.getCurrentCharPosition());
            int top = Math.max(0, view.getReaderScrollY() - view.getMarginVerticalPxForIndex());
            int bottom = top + view.getViewportHeight();
            int lastFullLine = layout.getLineForVertical(bottom);
            if (layout.getLineBottom(lastFullLine) > bottom) lastFullLine--;
            if (page < offsets.length) {
                assertFalse(view.isDocumentEndFullyVisible());
                assertTrue("no page may follow one that already fully covers the final line",
                        lastFullLine < layout.getLineCount() - 1);
                int startLine = layout.getLineForOffset(offsets[page - 1]);
                int expectedLine = Math.max(startLine + 1,
                        lastFullLine + 1 - view.getOverlapLinesForIndex());
                assertEquals("ordinary page overlap must be retained",
                        layout.getLineStart(expectedLine), offsets[page]);
                assertEquals(offsets[page], view.getCharPositionForNextPageStartRespectingOverlap());
                view.pageForwardWithoutSkippingContent();
                assertEquals(offsets[page], view.getCurrentCharPosition());
            } else {
                assertTrue(view.isDocumentEndFullyVisible());
                assertEquals("last real or terminal blank line must be fully covered",
                        layout.getLineCount() - 1, lastFullLine);
                assertEquals(content.length(), view.getCharPositionForNextPageStartRespectingOverlap());
                assertEquals(content.length(), view.getCharPositionAfterCurrentVisibleContent());
                int finalScroll = view.getReaderScrollY();
                view.pageForwardWithoutSkippingContent();
                assertEquals("extra forward tap must not create a tail jump",
                        finalScroll, view.getReaderScrollY());
            }
        }
    }

    private static StaticLayout textLayout(CustomReaderView view) {
        try {
            java.lang.reflect.Field field = CustomReaderView.class.getDeclaredField("layout");
            field.setAccessible(true);
            return (StaticLayout) field.get(view);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    public void overlapChangePreservesInteriorPixelsAndMatchesFreshBounds() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView changed = laidOutReaderView();
            CustomReaderView fresh = laidOutReaderView();
            try {
                String content = numberedLines(200, "overlap bounds");
                changed.setOverlapLines(2);
                changed.setTextContent(content);
                changed.scrollToPage(3);
                changed.setReaderScrollY(changed.getReaderScrollY() + 3);
                int position = changed.getCurrentCharPosition();
                int scrollY = changed.getReaderScrollY();
                scroller(changed).fling(0, scrollY, 0, 2000, 0, 0, 0, changed.getMaxScrollY());
                fresh.setTextContent(content);
                changed.setOverlapLines(0);
                assertEquals(scrollY, changed.getReaderScrollY());
                assertEquals(position, changed.getCurrentCharPosition());
                assertTrue(scroller(changed).isFinished());
                assertEquals(fresh.getMaxScrollY(), changed.getMaxScrollY());
                assertEquals(fresh.getTotalPageCount(), changed.getTotalPageCount());
                changed.scrollToPage(changed.getTotalPageCount());
                fresh.scrollToPage(fresh.getTotalPageCount());
                assertEquals(fresh.getReaderScrollY(), changed.getReaderScrollY());
                changed.scrollToVisualEndOfText();
                fresh.scrollToVisualEndOfText();
                assertEquals(fresh.getReaderScrollY(), changed.getReaderScrollY());
            } finally {
                changed.releaseTextResources();
                fresh.releaseTextResources();
            }
        });
    }

    @Test
    public void activeSelectionRemainsDistinctFromFingerContact() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            try {
                view.setTextContent(numberedLines(120, "selection"));
                assertFalse(view.isTextSelectionActive());
                java.lang.reflect.Method select = CustomReaderView.class.getDeclaredMethod(
                        "setTextSelection", int.class, int.class);
                select.setAccessible(true);
                select.invoke(view, 0, 4);
                assertFalse(view.isTouchActive());
                assertTrue(view.isTextSelectionActive());
                view.stopScrollMotion();
                assertTrue(view.isTextSelectionActive());
                view.clearTextSelection();
                assertFalse(view.isTextSelectionActive());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void markdownColorOnly_keepsPixelsAndFlingAndPendingUnsizedAnchor() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setMarkdownHighlightingEnabled(true);
            view.setTextContent(numberedLines(240, "markdown"));
            view.scrollToPage(3);
            view.setReaderScrollY(view.getReaderScrollY() + 3);
            CustomReaderView.ScrollViewportAnchor before = view.captureScrollViewportAnchor();
            assertNotNull(before);
            int scrollY = view.getReaderScrollY();
            scroller(view).fling(0, scrollY, 0, 2000, 0, 0, 0, view.getMaxScrollY());
            view.setReaderStyle(18f, 1.35f, Color.WHITE, Color.BLACK, 24, 16, Typeface.DEFAULT);
            assertEquals(scrollY, view.getReaderScrollY());
            CustomReaderView.ScrollViewportAnchor recolored = view.captureScrollViewportAnchor();
            assertNotNull(recolored);
            assertEquals(before.charPosition, recolored.charPosition);
            assertEquals(before.baselineOffsetPx, recolored.baselineOffsetPx);
            assertFalse(scroller(view).isFinished());
            sizeReader(view, 0, 0);
            view.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE, 24, 16, Typeface.DEFAULT);
            sizeReader(view, 720, 960);
            assertEquals(before.charPosition, view.getCurrentCharPosition());
            view.releaseTextResources();
        });
    }

    @Test
    public void viewportLookahead_isMonotonicBoundedAndDoesNotMoveReader() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setTextContent(numberedLines(240, "prefetch"));
            view.scrollToPage(4);
            int position = view.getCurrentCharPosition();
            int scrollY = view.getReaderScrollY();
            assertEquals(position, view.getCharPositionAfterViewportScreens(0));
            assertEquals(position, view.getCharPositionAfterViewportScreens(-1));
            int one = view.getCharPositionAfterViewportScreens(1);
            int two = view.getCharPositionAfterViewportScreens(2);
            assertTrue(one > position);
            assertTrue(two > one);
            assertEquals(view.getTextContent().length(),
                    view.getCharPositionAfterViewportScreens(Integer.MAX_VALUE));
            assertEquals(scrollY, view.getReaderScrollY());
            sizeReader(view, 0, 0);
            assertEquals(0, view.getCharPositionAfterViewportScreens(2));
            view.releaseTextResources();
        });
    }

    @Test
    public void touchDownBeforeDrag_blocksAutomaticHandoffUntilRelease() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setTextContent(numberedLines(180, "contact"));
            long start = SystemClock.uptimeMillis();
            touch(view, start, 0, MotionEvent.ACTION_DOWN, 600);
            assertTrue(view.isTouchActive());
            assertTrue(view.isUserDraggingOrFlinging());
            view.stopScrollMotion(); // Explicit navigation may ignore this old gesture.
            assertTrue(view.isTouchActive()); // Physical contact still blocks an automatic swap.
            touch(view, start, 16, MotionEvent.ACTION_UP, 600);
            assertFalse(view.isTouchActive());
            assertFalse(view.isUserDraggingOrFlinging());
            touch(view, start, 32, MotionEvent.ACTION_DOWN, 600);
            touch(view, start, 48, MotionEvent.ACTION_CANCEL, 600);
            assertFalse(view.isTouchActive());
            touch(view, start, 64, MotionEvent.ACTION_DOWN, 600);
            view.releaseTextResources();
            assertFalse(view.isTouchActive());
        });
    }

    @Test
    public void viewportBaseline_survivesOverlappingWindowAndFirstLinePadding() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            String[] lines = generatedLines(240, "baseline");
            String firstWindow = joinLines(lines, 0, 160);
            int secondBase = joinLines(lines, 0, 100).length() + 1;
            for (int targetLine : new int[]{100, 107}) {
                CustomReaderView first = laidOutReaderView();
                first.setTextContent(firstWindow);
                int absoluteTarget = joinLines(lines, 0, targetLine).length() + 1;
                first.scrollToCharPosition(absoluteTarget);
                first.setReaderScrollY(first.getReaderScrollY() + 3);
                CustomReaderView.ScrollViewportAnchor before = first.captureScrollViewportAnchor();
                assertNotNull(before);
                assertEquals(absoluteTarget, before.charPosition);

                CustomReaderView second = laidOutReaderView();
                second.setTextContent(joinLines(lines, 100, 240));
                assertTrue(second.restoreScrollViewportAnchor(
                        before.charPosition - secondBase, before.baselineOffsetPx));
                CustomReaderView.ScrollViewportAnchor after = second.captureScrollViewportAnchor();
                assertNotNull(after);
                assertEquals(before.charPosition, secondBase + after.charPosition);
                assertEquals("includePad on the new first row must not displace the baseline",
                        before.baselineOffsetPx, after.baselineOffsetPx);
                first.releaseTextResources();
                second.releaseTextResources();
            }
        });
    }

    @Test
    public void geometryReflow_preservesTextRowAndRetiresOldFling() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            StringBuilder content = new StringBuilder();
            for (int line = 0; line < 240; line++) content.append("Row ").append(line).append('\n');
            view.setTextContent(content.toString());
            int target = content.indexOf("Row 100\n");
            view.scrollToCharPosition(target);
            scroller(view).fling(0, view.getReaderScrollY(), 0, 2000, 0, 0, 0, view.getMaxScrollY());
            view.setReaderStyle(24f, 1.5f, Color.BLACK, Color.WHITE, 28, 24, Typeface.DEFAULT);
            assertEquals(target, view.getCurrentCharPosition());
            assertFalse(view.isUserDraggingOrFlinging());
            view.setReaderStyle(12f, 1.1f, Color.BLACK, Color.WHITE, 16, 8, Typeface.DEFAULT);
            assertEquals(target, view.getCurrentCharPosition());
            sizeReader(view, 640, 1000);
            assertEquals(target, view.getCurrentCharPosition());
            assertTrue(view.hasCurrentTextLayout());
            view.releaseTextResources();
        });
    }

    @Test
    public void unmeasuredGeometry_keepsAnchorButNewTextNeverReusesIt() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            String content = numberedLines(200, "geometry");
            view.setTextContent(content);
            view.scrollToPage(4);
            int target = view.getCurrentCharPosition();
            sizeReader(view, 0, 0);
            assertFalse(view.hasCurrentTextLayout());
            assertNull(view.captureScrollViewportAnchor());
            assertFalse(view.restoreScrollViewportAnchor(target, 10));
            sizeReader(view, 720, 960);
            assertTrue(view.hasCurrentTextLayout());
            assertEquals(target, view.getCurrentCharPosition());
            sizeReader(view, 0, 0);
            view.setTextContent(content); // Even the same String instance is an explicit replacement.
            sizeReader(view, 720, 960);
            assertEquals(0, view.getCurrentCharPosition());
            assertFalse(view.restoreScrollViewportAnchor(-1, 10));
            assertFalse(view.restoreScrollViewportAnchor(content.length() + 1, 10));
            view.releaseTextResources();
        });
    }

    private static void sizeReader(CustomReaderView view, int width, int height) {
        view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    @Test
    public void touchUpAndCancel_doNotLeaveDraggingSet() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setTextContent(numberedLines(180, "drag"));
            long start = SystemClock.uptimeMillis();
            touch(view, start, 0, MotionEvent.ACTION_DOWN, 600);
            touch(view, start, 16, MotionEvent.ACTION_MOVE, 200);
            assertTrue(view.isUserDraggingOrFlinging());
            touch(view, start, 32, MotionEvent.ACTION_UP, 200);
            scroller(view).forceFinished(true); // Isolate the finger state from inertia.
            assertFalse(view.isUserDraggingOrFlinging());
            touch(view, start, 100, MotionEvent.ACTION_DOWN, 600);
            touch(view, start, 116, MotionEvent.ACTION_MOVE, 200);
            touch(view, start, 132, MotionEvent.ACTION_CANCEL, 200);
            assertFalse(view.isUserDraggingOrFlinging());
            view.releaseTextResources();
        });
    }

    @Test
    public void replacingText_retiresOldTouchUntilNextDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setTextContent(numberedLines(180, "old"));
            long start = SystemClock.uptimeMillis();
            touch(view, start, 0, MotionEvent.ACTION_DOWN, 600);
            touch(view, start, 16, MotionEvent.ACTION_MOVE, 200);
            view.setTextContent(numberedLines(180, "new"));
            view.scrollToPage(3);
            int landing = view.getCurrentCharPosition();
            touch(view, start, 32, MotionEvent.ACTION_MOVE, 50);
            touch(view, start, 48, MotionEvent.ACTION_UP, 50);
            view.computeScroll();
            assertEquals(landing, view.getCurrentCharPosition());
            assertFalse(view.isUserDraggingOrFlinging());
            touch(view, start, 100, MotionEvent.ACTION_DOWN, 600);
            touch(view, start, 116, MotionEvent.ACTION_MOVE, 200);
            assertTrue(view.getCurrentCharPosition() > landing);
            touch(view, start, 132, MotionEvent.ACTION_CANCEL, 200);
            view.releaseTextResources();
        });
    }

    @Test
    public void explicitJumpAndReplacement_stopOldFlingCoordinates() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setTextContent(numberedLines(180, "fling"));
            scroller(view).fling(0, 100, 0, 2000, 0, 0, 0, view.getMaxScrollY());
            assertTrue(view.isUserDraggingOrFlinging());
            view.scrollToPage(4);
            int landing = view.getCurrentCharPosition();
            view.computeScroll();
            assertEquals(landing, view.getCurrentCharPosition());
            assertFalse(view.isUserDraggingOrFlinging());
            scroller(view).fling(0, 100, 0, 2000, 0, 0, 0, view.getMaxScrollY());
            view.setTextContent(numberedLines(180, "replacement"));
            view.scrollToPage(2);
            landing = view.getCurrentCharPosition();
            view.computeScroll();
            assertEquals(landing, view.getCurrentCharPosition());
            assertFalse(view.isUserDraggingOrFlinging());
            view.releaseTextResources();
        });
    }

    @Test
    public void unloadedGlobalAnchor_doesNotInventALocalRowSuffix() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setTextContent(numberedLines(180, "label"));
            view.scrollToPage(3);
            assertEquals(1, view.getCurrentLineOffsetFromCharPosition(-40));
            assertEquals(1, view.getCurrentLineOffsetFromCharPosition(view.getTextContent().length()));
            view.releaseTextResources();
        });
    }

    private static void touch(CustomReaderView view, long start, long elapsed, int action, float y) {
        MotionEvent event = MotionEvent.obtain(start, start + elapsed, action, 100, y, 0);
        try { view.onTouchEvent(event); } finally { event.recycle(); }
    }

    private static OverScroller scroller(CustomReaderView view) {
        try {
            java.lang.reflect.Field field = CustomReaderView.class.getDeclaredField("scroller");
            field.setAccessible(true);
            return (OverScroller) field.get(view);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    public void seamAnchorReportsFirstRowEvenInsidePartitionLocalPage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setTextContent(numberedLines(180, "seam"));
            view.scrollToPage(2);
            int seam = view.getCharPositionFromCurrentLineOffset(2);
            view.scrollToCharPosition(seam);
            assertTrue(view.getCurrentLineOffsetWithinPage() > 1);
            assertEquals(1, view.getCurrentLineOffsetFromCharPosition(seam));
            int nextLine = view.getCharPositionFromCurrentLineOffset(1);
            view.scrollToCharPosition(nextLine);
            assertEquals(2, view.getCurrentLineOffsetFromCharPosition(seam));
            view.releaseTextResources();
        });
    }

    @Test
    public void fractionalRowLabelsStayWithTheirPageBoundaryInBothDirections() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            try {
                view.setTextContent(numberedLines(180, "boundary"));
                for (int overlap : new int[]{0, 1, 8}) {
                    view.setOverlapLines(overlap);
                    assertTrue(view.getTotalPageCount() > 3);
                    StaticLayout layout = textLayout(view);
                    int pageStart = view.getPageStartCharPosition(2);
                    int nextStart = view.getPageStartCharPosition(3);
                    int nextLine = layout.getLineForOffset(nextStart);
                    int previousLine = nextLine - 1;
                    int previousChar = layout.getLineStart(previousLine);
                    int top = layout.getLineTop(previousLine) + view.getMarginVerticalPxForIndex();
                    int boundary = layout.getLineTop(nextLine) + view.getMarginVerticalPxForIndex();
                    int expectedRow = view.getLineOffsetBetweenCharPositions(pageStart, previousChar);
                    assertTrue(expectedRow >= 1);
                    for (int direction : new int[]{1, -1}) {
                        // Leave the existing local-page 1 px boundary tolerance unchanged.
                        for (int pixel = 0; pixel < boundary - top - 1; pixel++) {
                            int scroll = direction > 0 ? top + pixel : boundary - 2 - pixel;
                            view.setReaderScrollY(scroll);
                            assertEquals(2, view.getCurrentPageNumber());
                            assertEquals(previousChar, view.getCurrentCharPosition());
                            assertEquals("the row must not advance before its top-edge boundary",
                                    expectedRow, view.getCurrentLineOffsetWithinPage());
                            assertEquals(expectedRow, view.getCurrentLineOffsetFromCharPosition(pageStart));
                        }
                        view.setReaderScrollY(boundary);
                        assertEquals(3, view.getCurrentPageNumber());
                        assertEquals(nextStart, view.getCurrentCharPosition());
                        assertEquals(1, view.getCurrentLineOffsetWithinPage());
                    }
                }
            } finally {
                view.releaseTextResources();
            }
        });
    }

    @Test
    public void localTapPagingMovesForwardWithoutSkippingComputedNextStart() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setOverlapLines(0);
            view.setTextContent(numberedLines(180, "local"));

            int total = view.getTotalPageCount();
            assertTrue("test document should span multiple pages", total > 3);

            int previous = view.getCurrentCharPosition();
            for (int page = 1; page < total; page++) {
                int expectedNextStart = view.getCharPositionForNextPageStartRespectingOverlap();
                boolean corrected = view.pageForwardWithoutSkippingContent();
                int actual = view.getCurrentCharPosition();

                assertTrue("page " + page + " must move forward", actual > previous);
                assertTrue("page " + page + " must not skip past computed next start",
                        actual <= expectedNextStart);
                if (!corrected && actual < expectedNextStart) {
                    assertTrue("only the final clamp may land before computed next start",
                            page >= total - 1);
                }
                previous = actual;
            }
        });
    }

    @Test
    public void partitionHandoffTargetLandsOnNextPartitionLineStart() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            String[] lines = generatedLines(150, "partition");
            String firstWindow = joinLines(lines, 0, 110);
            String secondWindow = joinLines(lines, 100, 150);
            int bodyEnd = joinLines(lines, 0, 100).length();
            int secondBase = bodyEnd + 1;

            CustomReaderView first = laidOutReaderView();
            first.setOverlapLines(0);
            first.setTextContent(firstWindow);

            int nextStart = -1;
            int guard = first.getTotalPageCount() + 2;
            while (guard-- > 0) {
                nextStart = first.getCharPositionForNextPageStartRespectingOverlap();
                if (nextStart >= bodyEnd) break;
                first.pageForwardWithoutSkippingContent();
            }
            assertTrue("test must reach partition body seam", nextStart >= bodyEnd);

            int targetAbs = LargeTextContinuityMath.forwardHandoffTargetAbs(0, nextStart, bodyEnd);
            int expectedSecondLocal = targetAbs - secondBase;
            if (nextStart == bodyEnd) {
                assertEquals(0, expectedSecondLocal);
            } else {
                assertTrue(expectedSecondLocal > 0);
            }

            CustomReaderView second = laidOutReaderView();
            second.setOverlapLines(0);
            second.setTextContent(secondWindow);
            second.scrollToCharPosition(expectedSecondLocal);

            assertEquals(expectedSecondLocal, second.getCurrentCharPosition());
        });
    }

    @Test
    public void exactTextAnchorsMatchRenderedPageStarts() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setOverlapLines(0);
            String content = numberedLines(220, "anchor");
            view.setTextContent(content);

            ArrayList<CustomReaderView.PageTextAnchor> anchors =
                    CustomReaderView.buildPageTextAnchors(
                            content,
                            view.copyTextPaintForIndex(),
                            view.getTextLayoutWidthForIndex(),
                            view.getViewportHeight(),
                            view.getMarginVerticalPxForIndex(),
                            view.getOverlapLinesForIndex(),
                            view.getLineSpacingMultiplierForIndex());

            int total = view.getTotalPageCount();
            assertEquals("exact anchor count must match rendered page count", total, anchors.size());
            for (int page = 1; page <= total; page++) {
                view.scrollToPage(page);
                assertEquals("page " + page + " start char must match exact anchor",
                        anchors.get(page - 1).charPosition,
                        view.getCurrentCharPosition());
            }
        });
    }

    @Test
    public void backwardPagingReturnsToPreviousRenderedPageStart() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setOverlapLines(0);
            view.setTextContent(numberedLines(180, "backward"));

            int total = view.getTotalPageCount();
            assertTrue("test document should span multiple pages", total > 3);

            int[] starts = new int[total];
            for (int page = 1; page <= total; page++) {
                view.scrollToPage(page);
                starts[page - 1] = view.getCurrentCharPosition();
            }

            view.scrollToPage(total);
            for (int page = total; page > 1; page--) {
                view.pageBackwardWithoutSkippingContent();
                assertEquals("previous from page " + page + " must land on page " + (page - 1),
                        starts[page - 2],
                        view.getCurrentCharPosition());
            }
        });
    }

    @Test
    public void configuredOverlapRepeatsOnlyExpectedPageStart() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setOverlapLines(2);
            view.setTextContent(numberedLines(140, "overlap"));

            int total = view.getTotalPageCount();
            assertTrue("test document should span multiple pages", total > 3);

            for (int page = 1; page < Math.min(total, 6); page++) {
                int expectedNextStart = view.getCharPositionForNextPageStartRespectingOverlap();
                view.pageForwardWithoutSkippingContent();
                assertEquals("overlap page " + page + " must land on computed next start",
                        expectedNextStart,
                        view.getCurrentCharPosition());
            }
        });
    }

    @Test
    public void realFixture_decodesAsReadableKoreanText() throws Exception {
        assumeRealFixtureAvailable();
        File file = copyRealFixtureToCache();
        String text = FileUtils.readTextFile(file);

        assertTrue("real fixture should be a large TXT", text.length() > 1_000_000);
        assertTrue("decoded real fixture should contain strong Hangul signal",
                countHangul(text, 200_000) > 10_000);
        assertTrue("decoded real fixture should not look like common UTF-8 mojibake",
                countOccurrences(text, "ì", 200_000) < 50);
        assertTrue("decoded real fixture should not contain replacement-heavy decode",
                countOccurrences(text, "\uFFFD", 200_000) < 10);
    }

    @Test
    public void realFixture_sampledAnchorsMatchRenderedPageStarts() throws Exception {
        assumeRealFixtureAvailable();
        String text = FileUtils.readTextFile(copyRealFixtureToCache());
        int sampleLength = Math.min(80_000, text.length());
        int[] starts = new int[] {
                0,
                Math.max(0, text.length() / 2 - sampleLength / 2),
                Math.max(0, text.length() - sampleLength)
        };

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int start : starts) {
                String sample = safeSlice(text, start, Math.min(text.length(), start + sampleLength));
                assertAnchorsMatchRenderedPages(sample);
            }
        });
    }

    @Test
    public void realFixture_firstFourThousandLineSeamUsesExpectedHandoffTarget() throws Exception {
        assumeRealFixtureAvailable();
        String text = FileUtils.readTextFile(copyRealFixtureToCache());
        String[] lines = text.split("\n", -1);
        assertTrue("real fixture should have enough lines for the 4000-line seam", lines.length > 4400);

        String firstWindow = joinLines(lines, 0, 4400);
        String secondWindow = joinLines(lines, 4000, 4400);
        int bodyEnd = joinLines(lines, 0, 4000).length();
        int secondBase = bodyEnd + 1;

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView first = laidOutReaderView();
            first.setOverlapLines(0);
            first.setTextContent(firstWindow);

            int nextStart = -1;
            int guard = first.getTotalPageCount() + 2;
            while (guard-- > 0) {
                nextStart = first.getCharPositionForNextPageStartRespectingOverlap();
                if (nextStart >= bodyEnd) break;
                first.pageForwardWithoutSkippingContent();
            }
            assertTrue("real fixture should reach first 4000-line body seam", nextStart >= bodyEnd);

            int targetAbs = LargeTextContinuityMath.forwardHandoffTargetAbs(0, nextStart, bodyEnd);
            int expectedSecondLocal = targetAbs - secondBase;
            assertTrue("handoff target should land inside the next real fixture window",
                    expectedSecondLocal >= 0 && expectedSecondLocal < secondWindow.length());

            CustomReaderView second = laidOutReaderView();
            second.setOverlapLines(0);
            second.setTextContent(secondWindow);
            second.scrollToCharPosition(expectedSecondLocal);

            assertEquals(expectedSecondLocal, second.getCurrentCharPosition());
        });
    }

    @Test
    public void realFixture_tailPagesMoveForwardAndBackwardConsistently() throws Exception {
        assumeRealFixtureAvailable();
        String text = FileUtils.readTextFile(copyRealFixtureToCache());
        String tail = safeSlice(text, Math.max(0, text.length() - 100_000), text.length());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setOverlapLines(0);
            view.setTextContent(tail);

            int total = view.getTotalPageCount();
            assertTrue("real fixture tail should span multiple pages", total > 3);

            int[] starts = new int[total];
            for (int page = 1; page <= total; page++) {
                view.scrollToPage(page);
                starts[page - 1] = view.getCurrentCharPosition();
            }

            for (int i = 1; i < starts.length; i++) {
                assertTrue("tail page starts must be strictly increasing", starts[i] > starts[i - 1]);
            }

            view.scrollToPage(total);
            for (int page = total; page > Math.max(1, total - 6); page--) {
                view.pageBackwardWithoutSkippingContent();
                assertEquals(starts[page - 2], view.getCurrentCharPosition());
            }
        });
    }

    private static CustomReaderView laidOutReaderView() {
        CustomReaderView view = new CustomReaderView(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        view.setReaderStyle(
                18f,
                1.35f,
                Color.BLACK,
                Color.WHITE,
                24,
                16,
                Typeface.DEFAULT);
        int width = 720;
        int height = 960;
        view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        return view;
    }

    private static void assertAnchorsMatchRenderedPages(String content) {
        CustomReaderView view = laidOutReaderView();
        view.setOverlapLines(0);
        view.setTextContent(content);

        ArrayList<CustomReaderView.PageTextAnchor> anchors =
                CustomReaderView.buildPageTextAnchors(
                        content,
                        view.copyTextPaintForIndex(),
                        view.getTextLayoutWidthForIndex(),
                        view.getViewportHeight(),
                        view.getMarginVerticalPxForIndex(),
                        view.getOverlapLinesForIndex(),
                        view.getLineSpacingMultiplierForIndex());

        int total = view.getTotalPageCount();
        assertEquals("exact anchor count must match rendered page count", total, anchors.size());
        for (int page = 1; page <= total; page++) {
            view.scrollToPage(page);
            assertEquals("page " + page + " start char must match exact anchor",
                    anchors.get(page - 1).charPosition,
                    view.getCurrentCharPosition());
        }
    }

    private static File copyRealFixtureToCache() throws IOException {
        Context assetContext = InstrumentationRegistry.getInstrumentation().getContext();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File cacheDir = targetContext.getCacheDir();
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            throw new IOException("Unable to create instrumentation cache dir: " + cacheDir);
        }
        File out = new File(cacheDir, REAL_FIXTURE_ASSET);
        if (out.isFile() && out.length() > 0) return out;

        try (InputStream in = assetContext.getAssets().open(REAL_FIXTURE_ASSET);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                fos.write(buffer, 0, read);
            }
        }
        return out;
    }

    private static void assumeRealFixtureAvailable() {
        assumeTrue("local-only real TXT fixture is absent", realFixtureAssetAvailable());
    }

    private static boolean realFixtureAssetAvailable() {
        Context assetContext = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream ignored = assetContext.getAssets().open(REAL_FIXTURE_ASSET)) {
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String safeSlice(String text, int start, int end) {
        int safeStart = Math.max(0, Math.min(text.length(), start));
        int safeEnd = Math.max(safeStart, Math.min(text.length(), end));
        safeStart = FileUtils.clampToSurrogateSafeStart(text, safeStart);
        safeEnd = FileUtils.clampToSurrogateSafeEnd(text, safeEnd);
        return text.substring(safeStart, safeEnd);
    }

    private static int countHangul(String text, int limit) {
        int count = 0;
        int end = Math.min(text.length(), Math.max(0, limit));
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            if (c >= '\uAC00' && c <= '\uD7A3') count++;
        }
        return count;
    }

    private static int countOccurrences(String text, String needle, int limit) {
        int count = 0;
        int end = Math.min(text.length(), Math.max(0, limit));
        int idx = 0;
        while (idx >= 0 && idx < end) {
            idx = text.indexOf(needle, idx);
            if (idx < 0 || idx >= end) break;
            count++;
            idx += Math.max(1, needle.length());
        }
        return count;
    }

    private static String numberedLines(int count, String prefix) {
        return joinLines(generatedLines(count, prefix), 0, count);
    }

    private static String[] generatedLines(int count, String prefix) {
        String[] lines = new String[count];
        for (int i = 0; i < count; i++) {
            lines[i] = prefix + " line " + i
                    + " abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ "
                    + "0123456789 repeated text for stable wrapping.";
        }
        return lines;
    }

    private static String joinLines(String[] lines, int start, int endExclusive) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < endExclusive; i++) {
            if (i > start) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }
}
