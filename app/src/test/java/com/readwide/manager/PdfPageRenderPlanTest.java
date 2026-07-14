package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PdfPageRenderPlanTest {
    @Test
    public void heightConstrainedFitUsesSmallerAxis() {
        PdfPageRenderPlan.Plan plan = PdfPageRenderPlan.create(
                600, 1200, 1200, 800, 1f, 1.4f,
                24, 16, true, 22_000_000L);

        assertEquals(392, plan.intendedDisplayWidthPx);
        assertEquals(784, plan.intendedDisplayHeightPx);
        assertTrue(plan.bitmapWidthPx > plan.intendedDisplayWidthPx);
    }

    @Test
    public void widthOnlyContinuousPlanKeepsIntendedHeightDespitePixelCap() {
        PdfPageRenderPlan.Plan plan = PdfPageRenderPlan.create(
                100, 100_000, 1000, 1, 1f, 1.4f,
                24, 0, false, 2_000_000L);

        assertEquals(976, plan.intendedDisplayWidthPx);
        assertEquals(976_000, plan.intendedDisplayHeightPx);
        assertTrue((long) plan.bitmapWidthPx * plan.bitmapHeightPx <= 2_000_000L);
    }

    @Test
    public void invalidZoomAndSupersampleFallBackSafely() {
        PdfPageRenderPlan.Plan plan = PdfPageRenderPlan.create(
                600, 800, 1000, 1200, Float.NaN, Float.POSITIVE_INFINITY,
                0, 0, true, 8_000_000L);

        assertTrue(plan.intendedDisplayWidthPx > 0);
        assertTrue(plan.bitmapWidthPx > 0);
        assertTrue(plan.bitmapHeightPx > 0);
    }
}
