package com.readwide.manager.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ImageViewportMathTest {
    private static final float EPSILON = 0.0001f;

    private static float screenTop(float height, int top, int bottom) {
        return top + ImageViewportMath.centeredTop(height, 1000, top, bottom);
    }

    @Test public void shortImageKeepsSameScreenTopWithUnequalPanels() {
        assertEquals(350f, screenTop(300f, 0, 0), EPSILON);
        assertEquals(350f, screenTop(300f, 100, 180), EPSILON);
        assertEquals(350f, screenTop(300f, 180, 100), EPSILON);
    }

    @Test public void panelCollisionMovesOnlyAsFarAsNecessary() {
        assertEquals(150f, screenTop(700f, 0, 0), EPSILON);
        assertEquals(120f, screenTop(700f, 100, 180), EPSILON);
        assertEquals(180f, screenTop(700f, 180, 100), EPSILON);
    }

    @Test public void exactFitStartsAtTheContentTop() {
        assertEquals(100f, screenTop(720f, 100, 180), EPSILON);
    }

    @Test public void noInsetsRetainsOriginalCentering() {
        assertEquals(137.5f, screenTop(725f, 0, 0), EPSILON);
    }

    @Test public void oversizedBaseUsesContentCenter() {
        assertEquals(-140f, ImageViewportMath.centeredTop(1000f, 1000, 100, 180), EPSILON);
    }

    @Test public void fractionalEdgesStayInsideTheViewport() {
        assertEquals(100.25f, screenTop(719.75f, 100, 180), EPSILON);
        assertEquals(180f, screenTop(719.75f, 180, 100), EPSILON);
    }

    @Test public void unusableGeometryDoesNotProduceNonFiniteOffsets() {
        assertEquals(0f, ImageViewportMath.centeredTop(300f, 0, 0, 0), EPSILON);
        assertEquals(0f, ImageViewportMath.centeredTop(300f, 1000, 600, 600), EPSILON);
        assertEquals(0f, ImageViewportMath.centeredTop(Float.NaN, 1000, 0, 0), EPSILON);
        assertEquals(0f, ImageViewportMath.centeredTop(Float.POSITIVE_INFINITY, 1000, 0, 0), EPSILON);
        assertEquals(350f, ImageViewportMath.centeredTop(300f, 1000, -1, -1), EPSILON);
    }
}
