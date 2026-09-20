package com.readwide.manager;

import static org.junit.Assert.*;
import org.junit.Test;

public class PdfSharpPatchPlanTest {
    @Test public void nativeOrDownsampledBaseNeedsNoExtraRender() {
        assertFalse(PdfSharpPatchPlan.needsSharpen(0.7f));
        assertFalse(PdfSharpPatchPlan.needsSharpen(1f));
        assertFalse(PdfSharpPatchPlan.needsSharpen(Float.NaN));
        assertFalse(PdfSharpPatchPlan.needsSharpen(Float.POSITIVE_INFINITY));
    }

    @Test public void magnifiedBaseStillRequestsSharpDetail() {
        assertTrue(PdfSharpPatchPlan.needsSharpen(1.01f));
        assertTrue(PdfSharpPatchPlan.needsSharpen(3f));
    }

    @Test public void viewportPlanKeepsPointCoordinatesAndDensity() {
        PdfSharpPatchPlan p = PdfSharpPatchPlan.create(600, 800, .25f, .25f, .75f, .75f, 2f);
        assertNotNull(p);
        assertEquals(150f, p.leftPts, 0f);
        assertEquals(200f, p.topPts, 0f);
        assertEquals(300f, p.widthPts, 0f);
        assertEquals(400f, p.heightPts, 0f);
        assertEquals(600, p.width);
        assertEquals(800, p.height);
    }

    @Test public void invalidRegionsAndScalesAreRejected() {
        assertNull(PdfSharpPatchPlan.create(600, 800, 0, 0, Float.NaN, 1, 2));
        assertNull(PdfSharpPatchPlan.create(600, 800, -.1f, 0, 1, 1, 2));
        assertNull(PdfSharpPatchPlan.create(600, 800, 1, 0, 0, 1, 2));
        assertNull(PdfSharpPatchPlan.create(600, 800, 0, 0, 1, 1, Float.POSITIVE_INFINITY));
        assertNull(PdfSharpPatchPlan.create(0, 800, 0, 0, 1, 1, 2));
    }

    @Test public void identicalPatchIsReusable() {
        PdfSharpPatchPlan p = PdfSharpPatchPlan.create(600, 800, 0, 0, 1, 1, 2);
        assertTrue(PdfSharpPatchPlan.canReuse(p.width, p.height, 0, 0, 1, 1, 0, 0, 1, 1, p));
    }

    @Test public void containedViewportReusesSufficientDensity() {
        PdfSharpPatchPlan p = PdfSharpPatchPlan.create(600, 800, .25f, .25f, .75f, .75f, 2);
        assertTrue(PdfSharpPatchPlan.canReuse(1200, 1600, 0, 0, 1, 1, .25f, .25f, .75f, .75f, p));
    }

    @Test public void panningBeyondCoveredAreaNeedsNewPatch() {
        PdfSharpPatchPlan p = PdfSharpPatchPlan.create(600, 800, .2f, .25f, .75f, .75f, 2);
        assertFalse(PdfSharpPatchPlan.canReuse(3000, 4000, .25f, .25f, .75f, .75f,
                .2f, .25f, .75f, .75f, p));
    }

    @Test public void zoomingBeyondCachedDensityNeedsNewPatch() {
        PdfSharpPatchPlan p = PdfSharpPatchPlan.create(600, 800, .25f, .25f, .75f, .75f, 4);
        assertFalse(PdfSharpPatchPlan.canReuse(1200, 1600, 0, 0, 1, 1, .25f, .25f, .75f, .75f, p));
    }

    @Test public void bothAxesMustHaveEnoughPixels() {
        PdfSharpPatchPlan p = PdfSharpPatchPlan.create(600, 800, 0, 0, 1, 1, 2);
        assertFalse(PdfSharpPatchPlan.canReuse(1200, 799, 0, 0, 1, 1, 0, 0, 1, 1, p));
        assertFalse(PdfSharpPatchPlan.canReuse(599, 1600, 0, 0, 1, 1, 0, 0, 1, 1, p));
    }

    @Test public void cappedPatchDoesNotTriggerIdenticalOversizedRenders() {
        PdfSharpPatchPlan p = PdfSharpPatchPlan.create(600, 800, 0, 0, 1, 1, 100);
        assertTrue((long) p.width * p.height <= PdfSharpPatchPlan.MAX_PIXELS);
        assertTrue(PdfSharpPatchPlan.canReuse(p.width, p.height, 0, 0, 1, 1, 0, 0, 1, 1, p));
    }
}
