package com.readwide.manager.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers the predicate that decides whether the large-TXT save path replaces
 * the current char position and anchors with the pending page's exact anchor.
 * The bug it guards: during a partition switch the persisted page number is the
 * pending page, so the persisted position/anchors must come from that same page
 * or a mid-switch pause saves an inconsistent state.
 */
public class ReaderSaveAnchorMathTest {

    @Test
    public void usesPendingAnchorWhenSwitchingWithReadyIndexAndAnchor() {
        assertTrue(ReaderSaveAnchorMath.shouldUsePendingPageAnchor(
                true,  // largeTextActive
                true,  // partitionSwitchInProgress
                true,  // exactPageIndexReady
                7,     // pendingDisplayPage
                true)); // pendingAnchorAvailable
    }

    @Test
    public void keepsCurrentPositionWhenNotSwitching() {
        // The common, non-switch save must be untouched.
        assertFalse(ReaderSaveAnchorMath.shouldUsePendingPageAnchor(
                true, false, true, 3, true));
    }

    @Test
    public void keepsCurrentPositionWhenExactIndexNotReady() {
        // Without the exact index there is no correct char position for the
        // pending page, so we must not override (and must not crash).
        assertFalse(ReaderSaveAnchorMath.shouldUsePendingPageAnchor(
                true, true, false, 7, true));
    }

    @Test
    public void keepsCurrentPositionWhenNoPendingPage() {
        assertFalse(ReaderSaveAnchorMath.shouldUsePendingPageAnchor(
                true, true, true, 0, true));
        assertFalse(ReaderSaveAnchorMath.shouldUsePendingPageAnchor(
                true, true, true, -1, true));
    }

    @Test
    public void keepsCurrentPositionWhenAnchorMissing() {
        assertFalse(ReaderSaveAnchorMath.shouldUsePendingPageAnchor(
                true, true, true, 7, false));
    }

    @Test
    public void keepsCurrentPositionWhenNotLargeText() {
        assertFalse(ReaderSaveAnchorMath.shouldUsePendingPageAnchor(
                false, true, true, 7, true));
    }
}
