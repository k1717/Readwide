package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ImageZoomScaleMathTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void doubleTap_fromFirstState_goesToTwoTimesFirstState() {
        assertEquals(4f, ImageZoomScaleMath.doubleTapTargetScale(2f, 2f, 4f, 0.01f), EPSILON);
    }

    @Test
    public void doubleTap_fromOriginalBelowUpscaledFirstState_stillGoesToTwoTimesFirstState() {
        assertEquals(4.8f, ImageZoomScaleMath.doubleTapTargetScale(1f, 2.4f, 4.8f, 0.01f), EPSILON);
    }

    @Test
    public void doubleTap_fromAnyZoomAboveFirstState_returnsToFirstState() {
        assertEquals(2f, ImageZoomScaleMath.doubleTapTargetScale(4f, 2f, 4f, 0.01f), EPSILON);
        assertEquals(2f, ImageZoomScaleMath.doubleTapTargetScale(8f, 2f, 4f, 0.01f), EPSILON);
    }

    @Test
    public void doubleTapTarget_neverFallsBelowFirstState() {
        assertEquals(2f, ImageZoomScaleMath.doubleTapTargetScale(2f, 2f, 1.5f, 0.01f), EPSILON);
    }

    @Test
    public void minimumPinchScale_stopsAtFirstStateUnlessFirstStateUpscalesPastOriginal() {
        assertEquals(0.5f, ImageZoomScaleMath.minimumPinchScale(0.5f), EPSILON);
        assertEquals(1f, ImageZoomScaleMath.minimumPinchScale(2.4f), EPSILON);
    }
}
