package com.readwide.manager.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EpubSectionProgressMathTest {
    @Test public void unavailableDimensionsHaveNoPercentage() {
        assertEquals(-1, EpubSectionProgressMath.percent(0, 1f, 100, 0, false));
        assertEquals(-1, EpubSectionProgressMath.percent(-1, 1f, 100, 0, false));
        assertEquals(-1, EpubSectionProgressMath.percent(1000, 1f, 0, 100, true));
        assertEquals(-1, EpubSectionProgressMath.percent(1000, 1f, -1, 100, true));
    }

    @Test public void invalidScaleHasNoPercentage() {
        float[] invalid = {0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float scale : invalid) {
            assertEquals(-1, EpubSectionProgressMath.percent(1000, scale, 100, 100, true));
        }
    }

    @Test public void contentFittingTheViewportHasNoPercentage() {
        assertEquals(-1, EpubSectionProgressMath.percent(100, 1f, 100, 0, false));
        assertEquals(-1, EpubSectionProgressMath.percent(99, 1f, 100, 0, false));
        assertEquals(-1, EpubSectionProgressMath.percent(1, Float.MIN_VALUE, 100, 0, false));
    }

    @Test public void startMiddleAndNativeEndHaveExpectedPercentages() {
        assertEquals(0, EpubSectionProgressMath.percent(1100, 1f, 100, 0, true));
        assertEquals(50, EpubSectionProgressMath.percent(1100, 1f, 100, 500, true));
        assertEquals(100, EpubSectionProgressMath.percent(1100, 1f, 100, 1000, false));
    }

    @Test public void partialPercentagesAreFlooredNotRounded() {
        assertEquals(0, EpubSectionProgressMath.percent(1100, 1f, 100, 9, true));
        assertEquals(1, EpubSectionProgressMath.percent(1100, 1f, 100, 19, true));
        assertEquals(99, EpubSectionProgressMath.percent(1100, 1f, 100, 999, true));
    }

    @Test public void negativeScrollRemainsAtTheStart() {
        assertEquals(0, EpubSectionProgressMath.percent(1100, 1f, 100, -50, true));
        assertEquals(0, EpubSectionProgressMath.percent(1100, 1f, 100, Integer.MIN_VALUE, false));
        assertEquals(0, EpubSectionProgressMath.percent(1100, 1f, 100, 0, false));
    }

    @Test public void downwardScrollAvailabilityPreventsPrematureCompletion() {
        assertEquals(99, EpubSectionProgressMath.percent(1100, 1f, 100, 1000, true));
        assertEquals(99, EpubSectionProgressMath.percent(1100, 1f, 100, Integer.MAX_VALUE, true));
    }

    @Test public void nativeBottomWinsOverCssRangeRounding() {
        assertEquals(100, EpubSectionProgressMath.percent(1100, 1f, 100, 998, false));
        assertEquals(100, EpubSectionProgressMath.percent(1100, 1f, 100, 1002, false));
    }

    @Test public void contentScaleUsesTheSameNativePixelsAsViewportAndOffset() {
        assertEquals(50, EpubSectionProgressMath.percent(1000, 2f, 500, 750, true));
        assertEquals(50, EpubSectionProgressMath.percent(2000, 0.5f, 200, 400, true));
    }

    @Test public void fractionalScaledHeightUsesRoundedNativeRange() {
        // 1001 * 1.5 = 1501.5 -> 1502px, minus 502px viewport = 1000px travel.
        assertEquals(50, EpubSectionProgressMath.percent(1001, 1.5f, 502, 500, true));
        assertEquals(99, EpubSectionProgressMath.percent(1001, 1.5f, 502, 999, true));
    }

    @Test public void scaledContentBeyondIntRangeDoesNotOverflow() {
        assertEquals(50, EpubSectionProgressMath.percent(
                Integer.MAX_VALUE, 2f, 1000, Integer.MAX_VALUE, true));
        assertEquals(0, EpubSectionProgressMath.percent(
                Integer.MAX_VALUE, Float.MAX_VALUE, 1, Integer.MAX_VALUE, true));
    }
}
