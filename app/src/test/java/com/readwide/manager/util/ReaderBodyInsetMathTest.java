package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ReaderBodyInsetMathTest {
    @Test
    public void landscapeSideNavigationDoesNotShrinkReaderBody() {
        assertEquals(0, ReaderBodyInsetMath.bodySideInset(0));
        assertEquals(144, ReaderBodyInsetMath.overlaySideInset(144, 0));
    }

    @Test
    public void physicalCutoutStillProtectsReaderBody() {
        assertEquals(72, ReaderBodyInsetMath.bodySideInset(72));
        assertEquals(0, ReaderBodyInsetMath.overlaySideInset(72, 72));
    }

    @Test
    public void navigationBeyondCutoutIsAddedOnlyToControls() {
        assertEquals(48, ReaderBodyInsetMath.bodySideInset(48));
        assertEquals(96, ReaderBodyInsetMath.overlaySideInset(144, 48));
    }
}
