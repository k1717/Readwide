package com.textview.reader.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.textview.reader.view.CustomReaderView;

import org.junit.Test;

import java.util.ArrayList;

public class LargeTextExactPageIndexStateTest {
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

    private static ArrayList<CustomReaderView.PageTextAnchor> anchors(int... positions) {
        ArrayList<CustomReaderView.PageTextAnchor> anchors = new ArrayList<>();
        for (int position : positions) {
            anchors.add(new CustomReaderView.PageTextAnchor(position, "", ""));
        }
        return anchors;
    }
}
