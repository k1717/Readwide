package com.textview.reader.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ImageSequenceNavigationMathTest {
    @Test
    public void clampIndex_boundsToAvailableImages() {
        assertEquals(0, ImageSequenceNavigationMath.clampIndex(-4, 5));
        assertEquals(2, ImageSequenceNavigationMath.clampIndex(2, 5));
        assertEquals(4, ImageSequenceNavigationMath.clampIndex(99, 5));
        assertEquals(0, ImageSequenceNavigationMath.clampIndex(3, 0));
    }

    @Test
    public void nextIndex_staysAtSequenceEdges() {
        assertEquals(0, ImageSequenceNavigationMath.nextIndex(0, -1, 4));
        assertEquals(1, ImageSequenceNavigationMath.nextIndex(0, 1, 4));
        assertEquals(3, ImageSequenceNavigationMath.nextIndex(3, 1, 4));
        assertEquals(2, ImageSequenceNavigationMath.nextIndex(3, -1, 4));
    }
    @Test
    public void mirroredVisualDelta_flipsOnlyPhysicalVisualDirections() {
        assertEquals(1, ImageSequenceNavigationMath.mirroredVisualDelta(1, false));
        assertEquals(-1, ImageSequenceNavigationMath.mirroredVisualDelta(-1, false));
        assertEquals(-1, ImageSequenceNavigationMath.mirroredVisualDelta(1, true));
        assertEquals(1, ImageSequenceNavigationMath.mirroredVisualDelta(-1, true));
        assertEquals(0, ImageSequenceNavigationMath.mirroredVisualDelta(0, true));
    }


    @Test
    public void visualTapZonesUseFixed33_34_33Split() {
        assertEquals(-1, ImageSequenceNavigationMath.visualTapZoneDelta(0.0f, false));
        assertEquals(-1, ImageSequenceNavigationMath.visualTapZoneDelta(0.3299f, false));
        assertEquals(0, ImageSequenceNavigationMath.visualTapZoneDelta(0.33f, false));
        assertEquals(0, ImageSequenceNavigationMath.visualTapZoneDelta(0.6699f, false));
        assertEquals(1, ImageSequenceNavigationMath.visualTapZoneDelta(0.67f, false));
        assertEquals(1, ImageSequenceNavigationMath.visualTapZoneDelta(1.0f, false));
    }

    @Test
    public void visualTapZonesMirrorLeftAndRightMeaning() {
        assertEquals(1, ImageSequenceNavigationMath.visualTapZoneDelta(0.2f, true));
        assertEquals(0, ImageSequenceNavigationMath.visualTapZoneDelta(0.5f, true));
        assertEquals(-1, ImageSequenceNavigationMath.visualTapZoneDelta(0.8f, true));
    }

}
