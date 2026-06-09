package com.textview.reader.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.textview.reader.model.LargeTextLinePartitionResult;

import org.junit.Test;

import java.util.ArrayList;

public class LargeTextContinuityRegressionTest {
    @Test
    public void canonicalPartitionStart_usesFixedLineWindows() {
        assertEquals(1, LargeTextContinuityMath.partitionStartLineForLine(-10, 4000));
        assertEquals(1, LargeTextContinuityMath.partitionStartLineForLine(1, 4000));
        assertEquals(1, LargeTextContinuityMath.partitionStartLineForLine(4000, 4000));
        assertEquals(4001, LargeTextContinuityMath.partitionStartLineForLine(4001, 4000));
        assertEquals(8001, LargeTextContinuityMath.partitionStartLineForLine(9999, 4000));
    }

    @Test
    public void partitionWindow_keepsLookbehindOutOfCanonicalStart() {
        LargeTextContinuityMath.PartitionWindow normal =
                LargeTextContinuityMath.partitionWindowForStartLine(
                        4001, 10000, 4000, 400, 400, false);
        assertEquals(4001, normal.startLine);
        assertEquals(8000, normal.bodyEndLine);
        assertEquals(4001, normal.windowStartLine);
        assertEquals(8400, normal.captureEndLine);

        LargeTextContinuityMath.PartitionWindow handoff =
                LargeTextContinuityMath.partitionWindowForStartLine(
                        4001, 10000, 4000, 400, 400, true);
        assertEquals(4001, handoff.startLine);
        assertEquals(8000, handoff.bodyEndLine);
        assertEquals(3601, handoff.windowStartLine);
        assertEquals(8400, handoff.captureEndLine);
    }

    @Test
    public void bodyOwnership_excludesLookaheadAndLookbehindOverlap() {
        LargeTextLinePartitionResult first = new LargeTextLinePartitionResult(
                repeated('a', 140),
                140,
                1,
                100,
                200,
                0,
                0,
                100,
                1,
                false,
                200);
        LargeTextLinePartitionResult secondWithLookbehind = new LargeTextLinePartitionResult(
                repeated('b', 140),
                140,
                101,
                200,
                200,
                80,
                20,
                120,
                81,
                true,
                220);

        assertTrue(LargeTextContinuityMath.ownsAbsoluteChar(first, 0));
        assertTrue(LargeTextContinuityMath.ownsAbsoluteChar(first, 99));
        assertFalse(LargeTextContinuityMath.ownsAbsoluteChar(first, 100));
        assertFalse(LargeTextContinuityMath.ownsAbsoluteChar(first, 139));

        assertFalse(LargeTextContinuityMath.ownsAbsoluteChar(secondWithLookbehind, 80));
        assertFalse(LargeTextContinuityMath.ownsAbsoluteChar(secondWithLookbehind, 99));
        assertTrue(LargeTextContinuityMath.ownsAbsoluteChar(secondWithLookbehind, 100));
        assertTrue(LargeTextContinuityMath.ownsAbsoluteChar(secondWithLookbehind, 199));
        assertFalse(LargeTextContinuityMath.ownsAbsoluteChar(secondWithLookbehind, 200));
    }

    @Test
    public void currentBodyCheck_usesOnlyOwnedBodyRange() {
        assertFalse(LargeTextContinuityMath.isAbsoluteCharInsideCurrentBody(99, 80, 140, 20, 120));
        assertTrue(LargeTextContinuityMath.isAbsoluteCharInsideCurrentBody(100, 80, 140, 20, 120));
        assertTrue(LargeTextContinuityMath.isAbsoluteCharInsideCurrentBody(199, 80, 140, 20, 120));
        assertFalse(LargeTextContinuityMath.isAbsoluteCharInsideCurrentBody(200, 80, 140, 20, 120));
    }

    @Test
    public void forwardHandoffTarget_skipsOnlySyntheticSeparatorAtBodyEnd() {
        assertEquals(201, LargeTextContinuityMath.forwardHandoffTargetAbs(100, 100, 100));
        assertEquals(199, LargeTextContinuityMath.forwardHandoffTargetAbs(100, 99, 100));
    }

    @Test
    public void forwardHandoffTarget_preservesLookaheadPageStartPastBodyEnd() {
        assertEquals(205, LargeTextContinuityMath.forwardHandoffTargetAbs(100, 105, 100));
        assertEquals(400, LargeTextContinuityMath.forwardHandoffTargetAbs(0, 400, 399));
    }

    @Test
    public void forwardHandoffTarget_clampsManualHandoffToKnownText() {
        assertEquals(0, LargeTextContinuityMath.clampAbsolutePositionToKnownText(-20, 100));
        assertEquals(99, LargeTextContinuityMath.clampAbsolutePositionToKnownText(140, 100));
        assertEquals(0, LargeTextContinuityMath.clampAbsolutePositionToKnownText(140, 0));
    }

    @Test
    public void estimatedFallbacks_areClampedAndStable() {
        assertEquals(0, LargeTextContinuityMath.estimateCharPositionForLine(1, 1, 1));
        assertEquals(0, LargeTextContinuityMath.estimateCharPositionForLine(-100, 1000, 5000));
        assertEquals(4999, LargeTextContinuityMath.estimateCharPositionForLine(999999, 1000, 5000));
        assertEquals(1, LargeTextContinuityMath.estimateDisplayedPageForLine(-10, 300, 1000));
        assertEquals(300, LargeTextContinuityMath.estimateDisplayedPageForLine(999999, 300, 1000));
        assertEquals(1, LargeTextContinuityMath.targetLineForEstimatedPage(1, 300, 1000));
        assertEquals(1000, LargeTextContinuityMath.targetLineForEstimatedPage(999, 300, 1000));
    }

    @Test
    public void bookmarkSavedPageTarget_rejectsStaleLegacyLayoutSignature() {
        assertArrayEquals(new int[] { 4, 10 },
                BookmarkPageModelMath.resolveSavedPageTarget("sig", "sig", 4, 10));
        assertArrayEquals(new int[] { 10, 10 },
                BookmarkPageModelMath.resolveSavedPageTarget("sig", "sig", 99, 10));
        assertArrayEquals(new int[] { 0, 0 },
                BookmarkPageModelMath.resolveSavedPageTarget("new", "old", 4, 10));
        assertArrayEquals(new int[] { 0, 0 },
                BookmarkPageModelMath.resolveSavedPageTarget("", "sig", 4, 10));
    }

    @Test
    public void bookmarkPageModel_normalizesPageAndDetectsMetadataChanges() {
        assertEquals(1, BookmarkPageModelMath.normalizePage(-5));
        assertEquals(7, BookmarkPageModelMath.normalizeTotalPages(7, 3));

        assertFalse(BookmarkPageModelMath.pageModelFieldsChanged(
                4, 10, "sig",
                4, 10, "sig"));
        assertTrue(BookmarkPageModelMath.pageModelFieldsChanged(
                4, 10, "sig",
                4, 10, "new-sig"));
        assertTrue(BookmarkPageModelMath.pageModelFieldsChanged(
                4, 10, null,
                4, 10, "sig"));
    }

    @Test
    public void partitionCache_separatesExactOwnerFromManualHandoffLookbehind() {
        LargeTextPartitionCache cache = new LargeTextPartitionCache();
        LargeTextLinePartitionResult normal = new LargeTextLinePartitionResult(
                repeated('a', 140), 140, 1, 100, 200, 0, 0, 100, 1, false, 200);
        LargeTextLinePartitionResult handoff = new LargeTextLinePartitionResult(
                repeated('b', 140), 140, 101, 200, 200, 80, 20, 120, 81, true, 220);

        assertTrue(cache.markNormalPrefetchPending(1, 100));
        assertFalse(cache.markNormalPrefetchPending(1, 100));
        cache.cache(normal);
        assertFalse(cache.markNormalPrefetchPending(1, 100));
        assertEquals(normal, cache.getNormalByStartLine(99, 100));

        assertTrue(cache.markManualHandoffPrefetchPending(101, 100));
        cache.cache(handoff);
        assertEquals(handoff, cache.getManualHandoffByStartLine(101, 100));

        assertEquals(normal, cache.getNormalOwnerForChar(99));
        assertEquals(null, cache.getNormalOwnerForChar(100));
    }

    @Test
    public void partitionWindowJoin_hasNoSyntheticTrailingNewline() {
        ArrayList<String> lines = new ArrayList<>();
        assertEquals("", LargeTextPartitionReader.joinWindow(lines));

        lines.add("first");
        assertEquals("first", LargeTextPartitionReader.joinWindow(lines));

        lines.add("second");
        lines.add("");
        lines.add("fourth");
        assertEquals("first\nsecond\n\nfourth", LargeTextPartitionReader.joinWindow(lines));
    }

    private static String repeated(char value, int count) {
        StringBuilder out = new StringBuilder(Math.max(0, count));
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
