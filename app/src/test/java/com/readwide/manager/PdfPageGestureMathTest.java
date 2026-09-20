package com.readwide.manager;

import org.junit.Test;
import static org.junit.Assert.*;

public class PdfPageGestureMathTest {
    @Test public void zoomPriorityUsesRelativeFitScale() {
        assertFalse(PdfPageGestureMath.isZoomed(0.5f, 0.5f));
        assertFalse(PdfPageGestureMath.isZoomed(0.501f, 0.5f));
        assertTrue(PdfPageGestureMath.isZoomed(0.51f, 0.5f));
        assertTrue(PdfPageGestureMath.isZoomed(2.5f, 1f));
        assertFalse(PdfPageGestureMath.isZoomed(1f, 0f));
    }

    @Test public void fitPageAllowsBothSwipeDirections() {
        assertEquals(1, swipe(-300, 0, true, true, 0, 1000));
        assertEquals(-1, swipe(300, 0, true, true, 0, 1000));
    }

    @Test public void narrowCenteredPageHasBothEdgesAvailable() {
        assertTrue(PdfPageGestureMath.atLeftEdge(200));
        assertTrue(PdfPageGestureMath.atRightEdge(200, 600, 1000));
        assertEquals(1, swipe(-300, 0, true, true, 200, 600));
        assertEquals(-1, swipe(300, 0, true, true, 200, 600));
    }

    @Test public void zoomedRightEdgeAllowsOutwardNextSwipe() {
        assertFalse(PdfPageGestureMath.atLeftEdge(-1500));
        assertTrue(PdfPageGestureMath.atRightEdge(-1500, 2500, 1000));
        assertEquals(1, swipe(-300, 20, false, true, -1500, 2500));
    }

    @Test public void zoomedLeftEdgeAllowsOutwardPreviousSwipe() {
        assertTrue(PdfPageGestureMath.atLeftEdge(0));
        assertFalse(PdfPageGestureMath.atRightEdge(0, 2500, 1000));
        assertEquals(-1, swipe(300, 20, true, false, 0, 2500));
    }

    @Test public void inwardSwipesPanInsteadOfTurning() {
        assertEquals(0, swipe(300, 0, false, true, -1200, 2500));
        assertEquals(0, swipe(-300, 0, true, false, -300, 2500));
    }

    @Test public void interiorPanReachingEitherEdgeDoesNotTurn() {
        assertEquals(0, swipe(-900, 0, false, false, -1500, 2500));
        assertEquals(0, swipe(900, 0, false, false, 0, 2500));
    }

    @Test public void mustStillBeAtTheSameEdgeOnRelease() {
        assertEquals(0, swipe(-300, 0, false, true, -1400, 2500));
        assertEquals(0, swipe(300, 0, true, false, -100, 2500));
    }

    @Test public void verticalDiagonalAndShortDragsDoNotTurn() {
        assertEquals(0, swipe(-180, 0, false, true, -1500, 2500));
        assertEquals(0, swipe(-250, 200, false, true, -1500, 2500));
        assertEquals(0, swipe(5, 500, true, false, 0, 2500));
        assertEquals(1, swipe(-181, 0, false, true, -1500, 2500));
    }

    @Test public void subpixelEdgeRoundingDoesNotBlockSwipes() {
        assertEquals(1, swipe(-300, 0, false, true, -1499.5f, 2500));
        assertEquals(-1, swipe(300, 0, true, false, -0.5f, 2500));
        assertFalse(PdfPageGestureMath.atLeftEdge(-2f));
        assertFalse(PdfPageGestureMath.atRightEdge(-1498f, 2500, 1000));
    }

    @Test public void missingOrInvalidGeometryDoesNotTurn() {
        assertEquals(0, PdfPageGestureMath.pageSwipeDirection(300, 0, 0, true, true, 0, 1000));
        assertEquals(0, swipe(300, 0, true, true, 0, 0));
        assertEquals(0, swipe(300, 0, true, true, Float.NaN, 1000));
        assertEquals(0, swipe(300, 0, true, true, 0, Float.POSITIVE_INFINITY));
    }

    private static int swipe(float dx, float dy, boolean left, boolean right,
                             float pageLeft, float width) {
        return PdfPageGestureMath.pageSwipeDirection(dx, dy, 1000, left, right, pageLeft, width);
    }
}
