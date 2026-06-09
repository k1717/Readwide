package com.textview.reader.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LargeTextPageDirectionStateTest {
    @Test
    public void record_ignoresInactiveOrZeroDirection() {
        LargeTextPageDirectionState state = new LargeTextPageDirectionState();

        state.record(false, 1);
        state.record(true, 0);

        assertEquals(0, state.lastDirection());
        assertEquals(0, state.sameDirectionCount());
        assertFalse(state.shouldPrefetchSecondNeighbor());
    }

    @Test
    public void record_countsSameDirectionAndCapsCount() {
        LargeTextPageDirectionState state = new LargeTextPageDirectionState();

        for (int i = 0; i < 20; i++) {
            state.record(true, 1);
        }

        assertEquals(1, state.lastDirection());
        assertEquals(8, state.sameDirectionCount());
        assertTrue(state.shouldPrefetchSecondNeighbor());
        assertFalse(state.preferPrevious());
    }

    @Test
    public void record_switchesDirectionAndResetClearsState() {
        LargeTextPageDirectionState state = new LargeTextPageDirectionState();

        state.record(true, 1);
        state.record(true, 1);
        state.record(true, -1);

        assertEquals(-1, state.lastDirection());
        assertEquals(1, state.sameDirectionCount());
        assertTrue(state.preferPrevious());
        assertFalse(state.shouldPrefetchSecondNeighbor());

        state.reset();

        assertEquals(0, state.lastDirection());
        assertEquals(0, state.sameDirectionCount());
    }
}
