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
}
