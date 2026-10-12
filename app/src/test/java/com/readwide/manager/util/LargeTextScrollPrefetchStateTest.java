package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LargeTextScrollPrefetchStateTest {
    @Test public void steadySlowScrollUsesTwoScreensAndFastForwardUsesFour() {
        LargeTextScrollPrefetchState state = new LargeTextScrollPrefetchState();
        assertEquals(2, state.observeScroll(0, 1000, 1000));
        assertEquals(2, state.observeScroll(100, 1100, 1000));
        assertEquals(4, state.observeScroll(300, 1200, 1000));
        assertEquals(4, state.observeScroll(350, 1300, 1000));
    }

    @Test public void reverseDirectionAndLongGapsDoNotRetainFastForwardHorizon() {
        LargeTextScrollPrefetchState state = new LargeTextScrollPrefetchState();
        state.observeScroll(0, 1000, 1000);
        assertEquals(4, state.observeScroll(250, 1100, 1000));
        assertEquals(2, state.observeScroll(200, 1150, 1000));
        assertEquals(4, state.observeScroll(400, 1250, 1000));
        assertEquals(2, state.observeScroll(3000, 2000, 1000));
    }

    @Test public void sameMillisecondSampleDoesNotInventSpeedOrEraseAnExistingFastHorizon() {
        LargeTextScrollPrefetchState state = new LargeTextScrollPrefetchState();
        state.observeScroll(0, 1000, 1000);
        assertEquals(2, state.observeScroll(100, 1000, 1000));
        assertEquals(4, state.observeScroll(300, 1100, 1000));
        assertEquals(4, state.observeScroll(300, 1100, 1000));
    }

    @Test public void proximityAndRetryCooldownBoundSpeculativeRequests() {
        LargeTextScrollPrefetchState state = new LargeTextScrollPrefetchState();
        assertFalse(state.shouldRequest(999, 1000, 1000));
        assertFalse(state.shouldRequest(1000, 0, 1000));
        assertTrue(state.shouldRequest(1000, 1000, 1000));
        assertFalse(state.shouldRequest(1100, 1000, 1016));
        assertFalse(state.shouldRequest(1500, 1000, 1499));
        assertTrue(state.shouldRequest(1500, 1000, 1500));
    }

    @Test public void newWindowResetsVelocityAndAllowsItsFirstPrefetchImmediately() {
        LargeTextScrollPrefetchState state = new LargeTextScrollPrefetchState();
        state.observeScroll(0, 1000, 1000);
        assertEquals(4, state.observeScroll(200, 1100, 1000));
        assertTrue(state.shouldRequest(1000, 1000, 1100));
        state.reset();
        assertEquals(2, state.observeScroll(90000, 1150, 1000));
        assertTrue(state.shouldRequest(1000, 1000, 1150));
    }
}
