package com.readwide.manager.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.readwide.manager.model.LargeTextLinePartitionResult;

import org.junit.Test;

import java.util.ArrayList;

public class LargeTextContinuityRegressionTest {
    @Test
    public void backwardHandoff_movesBeforeTheWindowIncludingLookbehind() {
        // One character plus a separator per logical line: window 3601, body 4001.
        int base = 3600 * 2;
        int target = LargeTextContinuityMath.backwardHandoffTargetAbs(base);
        assertEquals(7199, target);
        assertTrue(target < base);
        assertEquals(0, LargeTextContinuityMath.backwardHandoffTargetAbs(0));
        assertEquals(0, LargeTextContinuityMath.backwardHandoffTargetAbs(-1));
    }

    @Test
    public void loadedCharLookup_preservesLogicalLineAndSeparatorOwnership() {
        String text = "a\nb\nc\nd";
        assertEquals(1, LargeTextContinuityMath.partitionStartLineForLoadedChar(text, 100, 3999, 103, 4000, 4));
        assertEquals(4001, LargeTextContinuityMath.partitionStartLineForLoadedChar(text, 100, 3999, 104, 4000, 4));
        assertEquals(-1, LargeTextContinuityMath.partitionStartLineForLoadedChar(text, 100, 3999, 99, 4000, 4));
        assertEquals(-1, LargeTextContinuityMath.partitionStartLineForLoadedChar(text, 100, 3999, 107, 4000, 4));
    }

    @Test
    public void loadedCharLookup_doesNotGuessThroughDisplayRuleLineBreaks() {
        // Three emitted logical lines, but the first display rule adds a newline.
        assertEquals(-1, LargeTextContinuityMath.partitionStartLineForLoadedChar(
                "a\ninserted\nb\nc", 100, 3999, 113, 4000, 3));
        assertEquals(4001, LargeTextContinuityMath.partitionStartLineForLoadedChar(
                "a\n\nc", 100, 3999, 103, 4000, 3));
        assertEquals(-1, LargeTextContinuityMath.partitionStartLineForLoadedChar(null, 0, 1, 0, 4000, 1));
    }

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

        assertNotNull(cache.acquirePrefetch(1, 100, false, 1));
        assertNull(cache.acquirePrefetch(1, 100, false, 1));
        cache.cache(normal);
        assertNull(cache.acquirePrefetch(1, 100, false, 1));
        assertEquals(normal, cache.getNormalByStartLine(99, 100));

        assertNotNull(cache.acquirePrefetch(101, 100, true, 1));
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

    @Test
    public void highCoordinateWindow_doesNotWrapItsBodyOrLookahead() {
        LargeTextContinuityMath.PartitionWindow window =
                LargeTextContinuityMath.partitionWindowForStartLine(
                        Integer.MAX_VALUE, Integer.MAX_VALUE, 4000, 400, 400, true);
        assertEquals(2147480001, window.startLine);
        assertEquals(Integer.MAX_VALUE, window.bodyEndLine);
        assertEquals(Integer.MAX_VALUE, window.captureEndLine);
        assertEquals(2147479601, window.windowStartLine);
        LargeTextContinuityMath.PartitionWindow wide =
                LargeTextContinuityMath.partitionWindowForStartLine(
                        1, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, false);
        assertEquals(Integer.MAX_VALUE, wide.bodyEndLine);
        assertEquals(Integer.MAX_VALUE, wide.captureEndLine);
    }

    @Test
    public void highCoordinateHandoff_neverWrapsBackToDocumentStart() {
        assertEquals(Integer.MAX_VALUE,
                LargeTextContinuityMath.forwardHandoffTargetAbs(Integer.MAX_VALUE - 2, 2, 2));
        assertEquals(Integer.MAX_VALUE,
                LargeTextContinuityMath.forwardHandoffTargetAbs(Integer.MAX_VALUE - 2, 4, 1));
        assertFalse(LargeTextContinuityMath.isAbsoluteCharInsideCurrentBody(
                Integer.MIN_VALUE, Integer.MAX_VALUE, 10, 0, 10));
        assertEquals(0, LargeTextContinuityMath.clampAbsolutePositionToKnownText(50, Integer.MIN_VALUE));
    }

    @Test
    public void highLogicalLineLookup_rejectsAnUnrepresentableInferredLine() {
        assertEquals(Integer.MAX_VALUE, LargeTextContinuityMath.partitionStartLineForLoadedChar(
                "a\nb", 0, Integer.MAX_VALUE, 0, 1, 2));
        assertEquals(-1, LargeTextContinuityMath.partitionStartLineForLoadedChar(
                "a\nb", 0, Integer.MAX_VALUE, 2, 1, 2));
    }

    @Test
    public void highCoordinateEstimates_keepLastPageAndLineAtTheEnd() {
        assertEquals(Integer.MAX_VALUE, LargeTextContinuityMath.estimateDisplayedPageForLine(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, LargeTextContinuityMath.targetLineForEstimatedPage(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    private static String repeated(char value, int count) {
        StringBuilder out = new StringBuilder(Math.max(0, count));
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
