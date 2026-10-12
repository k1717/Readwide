package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.readwide.manager.view.CustomReaderView;

import org.junit.Test;

import java.util.ArrayList;

public class LargeTextExactPageIndexStateTest {
    @Test public void contentsLookupRequiresCurrentExactLayoutWithoutMutatingIt() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        int generation = state.beginBuild("layout");
        assertEquals(0, state.pageForCharIfUsable("layout", 120)[0]);
        state.completeCurrent(generation, "layout", anchors(0, 100, 200), false, "");
        assertEquals(0, state.pageForCharIfUsable("old-layout", 120)[0]);
        int[] page = state.pageForCharIfUsable("layout", 120);
        assertEquals(2, page[0]);
        assertEquals(3, page[1]);
        assertEquals(3, state.readyPageCount());
    }

    @Test public void savedIndexValidationCanCompleteWithoutEnteringRecount() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        assertTrue(state.isCheckingCache());
        int generation = state.beginBuild("same-layout");
        assertTrue(state.isCheckingCache());
        assertFalse(state.isReady());
        assertEquals(0, state.readyPageCount());
        state.completeCurrent(generation, "same-layout", anchors(0, 100, 200), false, "");
        assertFalse(state.isCheckingCache());
        assertTrue(state.isReady());
        assertEquals(3, state.readyPageCount());
        assertFalse(state.beginCalculation(generation, "same-layout"));
    }

    @Test public void onlyTheCurrentCacheMissCanEnterRecount() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        int stale = state.beginBuild("old");
        int current = state.beginBuild("new");
        assertFalse(state.beginCalculation(stale, "old"));
        assertTrue(state.isCheckingCache());
        assertFalse(state.beginCalculation(current, "wrong"));
        assertTrue(state.beginCalculation(current, "new"));
        assertFalse(state.isCheckingCache());
        assertFalse(state.isReady());
        state.reset();
        assertTrue(state.isCheckingCache());
        assertFalse(state.beginCalculation(current, "new"));
    }

    @Test public void validationFailureAndCancellationDoNotLeavePendingLabels() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        int generation = state.beginBuild("bad");
        state.completeCurrent(generation, "bad", null, true, "read failure");
        assertFalse(state.isCheckingCache());
        assertTrue(state.isFailed());
        generation = state.beginBuild("retry");
        state.invalidate();
        assertFalse(state.isCheckingCache());
        assertFalse(state.beginCalculation(generation, "retry"));
        int next = state.beginBuild("retry");
        assertTrue(state.isCheckingCache());
        state.discardCurrent(next, "retry");
        assertFalse(state.isCheckingCache());
    }

    @Test
    public void beginBuild_blocksDuplicateSignatureWhileBuildingOrReady() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();

        int generation = state.beginBuild("sig");
        assertTrue(generation > 0);
        assertEquals(-1, state.beginBuild("sig"));

        LargeTextExactPageIndexState.CommitResult commit =
                state.completeCurrent(generation, "sig", anchors(0, 100), false, "");

        assertTrue(commit.current);
        assertTrue(commit.ready);
        assertEquals(2, commit.pageCount);
        assertEquals(-1, state.beginBuild("sig"));
    }

    @Test
    public void copyAnchorsIfUsable_requiresReadyMatchingSignature() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        int generation = state.beginBuild("sig");

        assertNull(state.copyAnchorsIfUsable("sig"));

        state.completeCurrent(generation, "sig", anchors(0, 100), false, "");

        assertNull(state.copyAnchorsIfUsable(""));
        assertNull(state.copyAnchorsIfUsable("other"));
        assertNotNull(state.copyAnchorsIfUsable("sig"));
    }

    @Test
    public void invalidate_makesOldBuildCommitNonCurrent() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        int generation = state.beginBuild("sig");

        state.invalidate();
        LargeTextExactPageIndexState.CommitResult commit =
                state.completeCurrent(generation, "sig", anchors(0), false, "");

        assertFalse(commit.current);
        assertFalse(state.isReady());
    }

    @Test
    public void completeCurrent_emptyAnchorsMarksFailureUntilReset() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        int generation = state.beginBuild("sig");

        LargeTextExactPageIndexState.CommitResult commit =
                state.completeCurrent(generation, "sig", new ArrayList<>(), true, "boom");

        assertTrue(commit.current);
        assertFalse(commit.ready);
        assertTrue(state.isFailed());
        assertEquals("boom", state.failureReason());

        state.reset();

        assertFalse(state.isFailed());
        assertFalse(state.isReady());
        assertEquals(0, state.readyPageCount());
    }

    @Test public void directLookupPreservesExistingAnchorMathAndClamping() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        assertNull(state.anchorForPage(1));
        ArrayList<CustomReaderView.PageTextAnchor> expected = anchors(0, 100, 100, 275);
        int generation = state.beginBuild("sig");
        state.completeCurrent(generation, "sig", expected, false, "");
        for (int position : new int[]{-10, 0, 1, 99, 100, 101, 274, 275, 999}) {
            assertEquals(LargeTextAnchorMath.findExactPageForChar(expected, position),
                    state.findPageForChar(position));
        }
        assertEquals(0, state.anchorForPage(-1).charPosition);
        assertEquals(100, state.anchorForPage(2).charPosition);
        assertEquals(275, state.anchorForPage(999).charPosition);
        state.reset();
        assertNull(state.anchorForPage(1));
    }

    private static ArrayList<CustomReaderView.PageTextAnchor> anchors(int... positions) {
        ArrayList<CustomReaderView.PageTextAnchor> anchors = new ArrayList<>();
        for (int position : positions) {
            anchors.add(new CustomReaderView.PageTextAnchor(position, "", ""));
        }
        return anchors;
    }

    @Test public void cancelledBuildAllowsSameSignatureRestartWithoutResettingCompletedPages() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        int cancelled = state.beginBuild("sig");
        state.invalidate();
        int replacement = state.beginBuild("sig");
        assertTrue(replacement > cancelled);
        assertFalse(state.completeCurrent(cancelled, "sig", anchors(0, 10), false, "").current);
        assertTrue(state.completeCurrent(replacement, "sig", anchors(0, 100), false, "").ready);
        state.invalidate();
        assertEquals(2, state.readyPageCount());
        assertEquals(-1, state.beginBuild("sig"));
    }

    @Test public void noCopyTapLookupMatchesPreviousNavigationForAllOffsetsAndDeltas() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        ArrayList<CustomReaderView.PageTextAnchor> expected = anchors(0, 10, 100, 500, 501);
        int generation = state.beginBuild("sig");
        state.completeCurrent(generation, "sig", expected, false, "");
        for (int position = -1; position <= 550; position++) {
            for (int delta : new int[]{-20, -3, -1, 0, 1, 3, 20}) {
                int index = LargeTextAnchorMath.findTapTargetAnchorIndex(expected, position, delta);
                if (index >= 0 && Math.abs(delta) > 1) {
                    index += delta > 0 ? Math.abs(delta) - 1 : -(Math.abs(delta) - 1);
                    index = Math.max(0, Math.min(expected.size() - 1, index));
                }
                LargeTextExactPageIndexState.TapTarget actual = state.tapTargetIfUsable("sig", position, delta);
                assertNotNull(actual);
                assertEquals(expected.size(), actual.total);
                assertEquals(index < 0 ? 0 : index + 1, actual.page);
                if (index < 0) assertNull(actual.anchor);
                else assertTrue(expected.get(index) == actual.anchor);
            }
        }
    }

    @Test public void noCopyTapLookupRejectsStaleUnreadyAndResetIndexes() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        int generation = state.beginBuild("old");
        assertNull(state.tapTargetIfUsable("old", 0, 1));
        state.completeCurrent(generation, "old", anchors(0, 100), false, "");
        assertNull(state.tapTargetIfUsable("new", 0, 1));
        assertNull(state.tapTargetIfUsable("", 0, 1));
        state.reset();
        assertNull(state.tapTargetIfUsable("old", 0, 1));
    }
}
