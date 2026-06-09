package com.textview.reader.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LargeTextPartitionSwitchStateTest {
    @Test
    public void begin_clampsPendingPageToStableTotal() {
        LargeTextPartitionSwitchState state = new LargeTextPartitionSwitchState();

        state.begin(99, 12, 4, 20);

        assertTrue(state.isInProgress());
        assertEquals(12, state.pendingTotalPages());
        assertEquals(12, state.pendingDisplayPage());
    }

    @Test
    public void begin_usesFallbackPageAndTotalWhenCallerDoesNotProvideThem() {
        LargeTextPartitionSwitchState state = new LargeTextPartitionSwitchState();

        state.begin(0, 0, 7, 30);

        assertTrue(state.isInProgress());
        assertEquals(30, state.pendingTotalPages());
        assertEquals(7, state.pendingDisplayPage());
    }

    @Test
    public void queuePageDelta_accumulatesFromPendingPageWithoutSkippingPastBounds() {
        LargeTextPartitionSwitchState state = new LargeTextPartitionSwitchState();
        state.begin(10, 12, 1, 12);

        state.queuePageDelta(+1, 12, 10);
        state.queuePageDelta(+1, 12, 10);
        state.queuePageDelta(+1, 12, 10);

        assertEquals(12, state.pendingDisplayPage());
        assertEquals(12, state.pendingTotalPages());
        assertTrue(state.hasQueuedDelta());
        assertEquals(2, state.consumeQueuedDelta());
        assertFalse(state.hasQueuedDelta());
    }

    @Test
    public void queuePageDelta_handlesBackwardTapsFromPendingPage() {
        LargeTextPartitionSwitchState state = new LargeTextPartitionSwitchState();
        state.begin(3, 20, 1, 20);

        state.queuePageDelta(-1, 20, 3);
        state.queuePageDelta(-1, 20, 3);
        state.queuePageDelta(-1, 20, 3);

        assertEquals(1, state.pendingDisplayPage());
        assertEquals(-2, state.consumeQueuedDelta());
    }

    @Test
    public void clearPending_keepsQueuedDeltaForPostApplyProcessing() {
        LargeTextPartitionSwitchState state = new LargeTextPartitionSwitchState();
        state.begin(5, 20, 1, 20);
        state.queuePageDelta(+1, 20, 5);

        state.clearPending();

        assertFalse(state.isInProgress());
        assertEquals(0, state.pendingDisplayPage());
        assertEquals(0, state.pendingTotalPages());
        assertTrue(state.hasQueuedDelta());
        assertEquals(1, state.consumeQueuedDelta());
    }
}
