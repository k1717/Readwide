package com.readwide.manager.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pending character ownership is independent of whether page numbers are still
 * estimated or an exact index has become ready during the partition read.
 */
public class ReaderSaveAnchorMathTest {

    @Test
    public void usesOwnedDestinationRegardlessOfPageIndexReadiness() {
        // The contract has no index-readiness input: the original character is
        // authoritative both before and after a page-index result arrives.
        assertTrue(ReaderSaveAnchorMath.shouldUsePendingTarget(
                true, true, false, 120));
        assertTrue(ReaderSaveAnchorMath.shouldUsePendingTarget(
                true, true, false, Integer.MAX_VALUE));
    }

    @Test
    public void acceptsStartOfFileAsARealDestination() {
        assertTrue(ReaderSaveAnchorMath.shouldUsePendingTarget(
                true, true, false, 0));
    }

    @Test
    public void keepsCurrentPositionWhenNotSwitching() {
        // The common, non-switch save must be untouched.
        assertFalse(ReaderSaveAnchorMath.shouldUsePendingTarget(
                true, false, false, 120));
    }

    @Test
    public void automaticHandoffKeepsVisiblePosition() {
        assertFalse(ReaderSaveAnchorMath.shouldUsePendingTarget(
                true, true, true, 120));
        assertFalse(ReaderSaveAnchorMath.shouldUsePendingTarget(
                true, true, true, 0));
    }

    @Test
    public void keepsCurrentPositionWhenDestinationIsUnavailable() {
        assertFalse(ReaderSaveAnchorMath.shouldUsePendingTarget(
                true, true, false, -1));
        assertFalse(ReaderSaveAnchorMath.shouldUsePendingTarget(
                true, true, false, Integer.MIN_VALUE));
    }

    @Test
    public void keepsCurrentPositionWhenNotLargeText() {
        assertFalse(ReaderSaveAnchorMath.shouldUsePendingTarget(
                false, true, false, 120));
    }
}
