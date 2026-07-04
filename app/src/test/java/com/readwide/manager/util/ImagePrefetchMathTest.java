package com.readwide.manager.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Tests for the direction-aware prefetch planning in {@link ImagePrefetchMath}. */
public class ImagePrefetchMathTest {

    @Test
    public void streakBuildsPerDirectionAndResetsOnJumpsAndReversals() {
        int streak = 0;
        streak = ImagePrefetchMath.updateStreak(streak, 1);
        assertEquals(1, streak);
        streak = ImagePrefetchMath.updateStreak(streak, 1);
        assertEquals(2, streak);
        // Reversal starts a fresh streak of one in the new direction.
        streak = ImagePrefetchMath.updateStreak(streak, -1);
        assertEquals(-1, streak);
        streak = ImagePrefetchMath.updateStreak(streak, -1);
        assertEquals(-2, streak);
        // A slider jump breaks the streak entirely.
        assertEquals(0, ImagePrefetchMath.updateStreak(streak, 5));
    }

    @Test
    public void directionRequiresSustainedTurns() {
        assertEquals(0, ImagePrefetchMath.sustainedDirection(0));
        assertEquals(0, ImagePrefetchMath.sustainedDirection(1));
        assertEquals(0, ImagePrefetchMath.sustainedDirection(-1));
        assertEquals(1, ImagePrefetchMath.sustainedDirection(2));
        assertEquals(-1, ImagePrefetchMath.sustainedDirection(-2));
        assertEquals(1, ImagePrefetchMath.sustainedDirection(7));
    }

    @Test
    public void neutralExtractionWindowMatchesHistoricalPlan() {
        assertArrayEquals(new int[] {1, -1, 2, -2, 3, -3}, ImagePrefetchMath.extractionOffsets(0));
        assertArrayEquals(new int[] {1, -1, 2, -2, 3, -3}, ImagePrefetchMath.bitmapOffsets());
    }

    @Test
    public void forwardExtractionWindowDeepensAheadKeepsOneBehind() {
        int[] offsets = ImagePrefetchMath.extractionOffsets(1);
        assertArrayEquals(new int[] {1, -1, 2, 3, 4, 5, 6, 7, 8}, offsets);
    }

    @Test
    public void backwardExtractionWindowMirrorsForward() {
        int[] offsets = ImagePrefetchMath.extractionOffsets(-1);
        assertArrayEquals(new int[] {-1, 1, -2, -3, -4, -5, -6, -7, -8}, offsets);
    }
}
