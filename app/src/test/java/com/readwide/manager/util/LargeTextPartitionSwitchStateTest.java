package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LargeTextPartitionSwitchStateTest {
    @Test
    public void viewportHandoffPreservesRowUnlessExplicitDestinationIsQueued() {
        LargeTextPartitionSwitchState state = new LargeTextPartitionSwitchState();
        state.markViewportHandoff();
        assertFalse(state.isViewportHandoff());
        state.begin(10, 200, 1, 200);
        state.markViewportHandoff();
        assertTrue(state.shouldPreserveVisiblePosition());
        state.setPendingTargetChar(1000);
        state.queueTarget(1100, 11, 200);
        assertTrue(state.isViewportHandoff());
        assertFalse(state.shouldPreserveVisiblePosition());
        state.clearQueuedTarget();
        assertEquals(1000, state.destinationChar());
        assertTrue(state.shouldPreserveVisiblePosition());
        state.clearPending();
        assertFalse(state.isViewportHandoff());
        assertFalse(state.shouldPreserveVisiblePosition());
        state.begin(20, 200, 10, 200);
        assertFalse(state.shouldPreserveVisiblePosition());
    }

    @Test
    public void replacingDestination_discardsTapsForTheOldRequest() {
        LargeTextPartitionSwitchState state = new LargeTextPartitionSwitchState();
        state.begin(10, 200, 1, 200);
        state.setPendingTargetChar(1000);
        state.queueTarget(1100, 11, 200);
        state.queueTarget(1200, 12, 200);
        state.markViewportHandoff();
        state.begin(100, 200, 12, 200);
        assertEquals(100, state.pendingDisplayPage());
        assertEquals(-1, state.destinationChar());
        assertFalse(state.hasQueuedTarget());
        assertFalse(state.isViewportHandoff());
        state.setPendingTargetChar(10000);
        state.queueTarget(9900, 99, 200);
        state.clearPending();
        assertEquals(9900, state.consumeQueuedTarget());
    }

    @Test
    public void consumedContinuation_canStartAnotherSwitchWithoutLosingLaterTaps() {
        LargeTextPartitionSwitchState state = new LargeTextPartitionSwitchState();
        state.begin(10, 200, 1, 200);
        state.setPendingTargetChar(1000);
        state.queueTarget(1100, 11, 200);
        state.clearPending();
        int target = state.consumeQueuedTarget();
        assertEquals(1100, target);
        state.begin(11, 200, 10, 200);
        state.setPendingTargetChar(target);
        state.queueTarget(1200, 12, 200);
        assertEquals(12, state.pendingDisplayPage());
        assertEquals(1200, state.consumeQueuedTarget());
        assertEquals(1100, state.destinationChar());
    }

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
    public void queuedDestinationUsesLatestCharacterInsteadOfAccumulatingPageDeltas() {
        LargeTextPartitionSwitchState state = new LargeTextPartitionSwitchState();
        state.begin(10, 12, 1, 12);
        state.setPendingTargetChar(1000);

        state.queueTarget(1100, 11, 12);
        state.queueTarget(1200, 12, 12);
        state.queueTarget(1000, 10, 12);

        assertEquals(10, state.pendingDisplayPage());
        assertEquals(12, state.pendingTotalPages());
        assertTrue(state.hasQueuedTarget());
        assertEquals(1000, state.destinationChar());
        assertEquals(1000, state.consumeQueuedTarget());
        assertFalse(state.hasQueuedTarget());
        assertEquals(-1, state.consumeQueuedTarget());
    }

    @Test
    public void backwardSnapFromMidPageRemainsAQueuedTargetEvenOnTheSamePage() {
        LargeTextPartitionSwitchState state = new LargeTextPartitionSwitchState();
        state.begin(10, 20, 1, 20);
        state.setPendingTargetChar(1050);
        state.markViewportHandoff();

        state.queueTarget(1000, 10, 20);

        assertEquals(10, state.pendingDisplayPage());
        assertTrue(state.hasQueuedTarget());
        assertFalse(state.shouldPreserveVisiblePosition());
        assertEquals(1000, state.destinationChar());
        assertEquals(1000, state.consumeQueuedTarget());
    }

    @Test
    public void clearPending_keepsQueuedCharacterForPostApplyProcessing() {
        LargeTextPartitionSwitchState state = new LargeTextPartitionSwitchState();
        state.begin(5, 20, 1, 20);
        state.setPendingTargetChar(500);
        state.markViewportHandoff();
        state.queueTarget(600, 6, 20);

        state.clearPending();

        assertFalse(state.isInProgress());
        assertEquals(0, state.pendingDisplayPage());
        assertEquals(0, state.pendingTotalPages());
        assertFalse(state.isViewportHandoff());
        assertTrue(state.hasQueuedTarget());
        assertEquals(600, state.destinationChar());
        state.setPendingTargetChar(900);
        state.queueTarget(1000, 10, 20);
        assertEquals(600, state.consumeQueuedTarget());
        assertEquals(-1, state.destinationChar());
    }

    @Test
    public void zeroCharacterIsAValidPendingAndQueuedDestination() {
        LargeTextPartitionSwitchState state = new LargeTextPartitionSwitchState();
        assertEquals(-1, state.destinationChar());
        state.setPendingTargetChar(100);
        state.queueTarget(100, 2, 20);
        assertEquals(-1, state.destinationChar());
        assertFalse(state.hasQueuedTarget());

        state.begin(1, 20, 1, 20);
        state.setPendingTargetChar(0);
        assertEquals(0, state.destinationChar());
        assertFalse(state.hasQueuedTarget());
        state.queueTarget(0, 1, 20);
        assertTrue(state.hasQueuedTarget());
        state.clearPending();
        assertEquals(0, state.consumeQueuedTarget());
        assertFalse(state.hasQueuedTarget());
        assertEquals(-1, state.destinationChar());
    }

    @Test
    public void relocatedReadTargetDoesNotOverwriteNewerQueuedDestination() {
        LargeTextPartitionSwitchState state = new LargeTextPartitionSwitchState();
        state.begin(10, 200, 1, 200);
        state.setPendingTargetChar(1000);
        state.queueTarget(1100, 11, 200);

        state.setPendingTargetChar(950);

        assertEquals(1100, state.destinationChar());
        assertEquals(11, state.pendingDisplayPage());
        state.clearQueuedTarget();
        assertEquals(950, state.destinationChar());
    }

    @Test
    public void queuedPageHintsAreClampedWithoutChangingTheCharacterDestination() {
        LargeTextPartitionSwitchState state = new LargeTextPartitionSwitchState();
        state.begin(10, 200, 1, 200);
        state.setPendingTargetChar(1000);
        state.queueTarget(1250, 99, 12);
        assertEquals(12, state.pendingDisplayPage());
        assertEquals(12, state.pendingTotalPages());
        assertEquals(1250, state.destinationChar());

        state.queueTarget(-1, 1, 1);
        assertEquals(12, state.pendingDisplayPage());
        assertEquals(12, state.pendingTotalPages());
        assertEquals(1250, state.destinationChar());

        state.queueTarget(0, 0, 0);
        assertEquals(1, state.pendingDisplayPage());
        assertEquals(1, state.pendingTotalPages());
        assertEquals(0, state.destinationChar());
    }

    @Test
    public void resetDiscardsPendingQueuedAndViewportState() {
        LargeTextPartitionSwitchState state = new LargeTextPartitionSwitchState();
        state.begin(10, 200, 1, 200);
        state.setPendingTargetChar(1000);
        state.markViewportHandoff();
        state.queueTarget(1100, 11, 200);

        state.reset();

        assertFalse(state.isInProgress());
        assertEquals(0, state.pendingDisplayPage());
        assertEquals(0, state.pendingTotalPages());
        assertFalse(state.isViewportHandoff());
        assertFalse(state.shouldPreserveVisiblePosition());
        assertFalse(state.hasQueuedTarget());
        assertEquals(-1, state.destinationChar());
        assertEquals(-1, state.consumeQueuedTarget());
    }
}
