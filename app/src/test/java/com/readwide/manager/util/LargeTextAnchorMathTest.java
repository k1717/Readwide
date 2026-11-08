package com.readwide.manager.util;

import com.readwide.manager.view.CustomReaderView;
import org.junit.Test;
import java.util.ArrayList;
import static org.junit.Assert.assertEquals;

public class LargeTextAnchorMathTest {
    @Test public void backwardFromShortOrBlankRowsReturnsToCurrentPageStart() {
        ArrayList<CustomReaderView.PageTextAnchor> pages = anchors(0, 100, 200);
        for (int topRow : new int[]{101, 102, 104, 108, 109, 199}) {
            assertEquals(1, LargeTextAnchorMath.findTapTargetAnchorIndex(pages, topRow, -1));
            assertEquals(2, LargeTextAnchorMath.findExactPageForChar(pages, topRow));
        }
        assertEquals(0, LargeTextAnchorMath.findTapTargetAnchorIndex(pages, 100, -1));
        assertEquals(1, LargeTextAnchorMath.findTapTargetAnchorIndex(pages, 200, -1));
    }

    @Test public void firstPageInteriorStillHasABackwardDestination() {
        ArrayList<CustomReaderView.PageTextAnchor> pages = anchors(0, 100);
        assertEquals(0, LargeTextAnchorMath.findTapTargetAnchorIndex(pages, 1, -1));
        assertEquals(0, LargeTextAnchorMath.findTapTargetAnchorIndex(pages, 8, -1));
        assertEquals(-1, LargeTextAnchorMath.findTapTargetAnchorIndex(pages, 0, -1));
        assertEquals(-1, LargeTextAnchorMath.findTapTargetAnchorIndex(pages, -1, -1));
    }

    @Test public void forwardAndEndBoundariesAreUnchanged() {
        ArrayList<CustomReaderView.PageTextAnchor> pages = anchors(0, 100, 200);
        for (int topRow : new int[]{100, 101, 108, 199}) {
            assertEquals(2, LargeTextAnchorMath.findTapTargetAnchorIndex(pages, topRow, 1));
        }
        assertEquals(-1, LargeTextAnchorMath.findTapTargetAnchorIndex(pages, 200, 1));
        assertEquals(-1, LargeTextAnchorMath.findTapTargetAnchorIndex(pages, 100, 0));
        assertEquals(-1, LargeTextAnchorMath.findTapTargetAnchorIndex(anchors(), 1, -1));
    }

    @Test public void anchorNearIntegerMaximumDoesNotOverflowBackwardComparison() {
        ArrayList<CustomReaderView.PageTextAnchor> pages = anchors(0, Integer.MAX_VALUE - 4);
        assertEquals(0, LargeTextAnchorMath.findTapTargetAnchorIndex(pages, Integer.MAX_VALUE - 4, -1));
        assertEquals(1, LargeTextAnchorMath.findTapTargetAnchorIndex(pages, Integer.MAX_VALUE - 3, -1));
        assertEquals(1, LargeTextAnchorMath.findTapTargetAnchorIndex(pages, Integer.MAX_VALUE, -1));
    }

    private static ArrayList<CustomReaderView.PageTextAnchor> anchors(int... positions) {
        ArrayList<CustomReaderView.PageTextAnchor> result = new ArrayList<>();
        for (int position : positions) result.add(new CustomReaderView.PageTextAnchor(position, "", ""));
        return result;
    }
}
