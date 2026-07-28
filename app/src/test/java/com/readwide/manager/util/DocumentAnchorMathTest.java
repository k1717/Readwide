package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DocumentAnchorMathTest {
    @Test
    public void preciseVerticalSentenceRequiresMatchedCaret() {
        assertFalse(DocumentAnchorMath.isPreciseVerticalSentence(
                true, "visible-sentence", "vertical-rl", false, "sentence-1", "visible text"));
        assertTrue(DocumentAnchorMath.isPreciseVerticalSentence(
                true, "visible-sentence", "vertical-rl", true, "sentence-1", "visible text"));
    }

    @Test
    public void verticalWritingModeCanEstablishVerticalPayload() {
        assertTrue(DocumentAnchorMath.isPreciseVerticalSentence(
                false, "visible-sentence", "vertical-lr", true, "", "long enough text"));
        assertFalse(DocumentAnchorMath.isPreciseVerticalSentence(
                false, "visible-sentence", "horizontal-tb", true, "", "long enough text"));
    }

    @Test
    public void shortIdlessJapaneseSentenceRemainsPreciseWhenCaretMatched() {
        assertTrue(DocumentAnchorMath.isPreciseVerticalSentence(
                true, "visible-sentence", "vertical-rl", true, "", "「え？"));
        assertFalse(DocumentAnchorMath.isPreciseVerticalSentence(
                true, "visible-sentence", "vertical-rl", true, "", "   "));
    }

    @Test
    public void legacyStoredSentenceDoesNotNeedNewCaretFlag() {
        assertTrue(DocumentAnchorMath.isStoredVerticalSentence(
                false, "visible-sentence", "vertical-rl", "sentence-1", ""));
        assertFalse(DocumentAnchorMath.isStoredVerticalSentence(
                false, "block-top", "vertical-rl", "sentence-1", "visible"));
        assertFalse(DocumentAnchorMath.isStoredVerticalSentence(
                false, "visible-sentence", "horizontal-tb", "sentence-1", "visible"));
    }

    @Test
    public void sameVerticalPageCanHoldBookmarksAtDifferentGlyphs() {
        assertTrue(DocumentAnchorMath.isSameVerticalSentenceSpot(
                "", "", 59, 59, 2, 3));
        assertFalse(DocumentAnchorMath.isSameVerticalSentenceSpot(
                "", "", 59, 59, 2, 18));
        assertFalse(DocumentAnchorMath.isSameVerticalSentenceSpot(
                "", "", 59, 60, 2, 2));
    }

    @Test
    public void elementIdDoesNotCollapseDifferentOffsetsIntoOneBookmark() {
        assertTrue(DocumentAnchorMath.isSameVerticalSentenceSpot(
                "paragraph-1", "paragraph-1", 4, 4, 20, 20));
        assertFalse(DocumentAnchorMath.isSameVerticalSentenceSpot(
                "paragraph-1", "paragraph-1", 4, 4, 20, 45));
        assertFalse(DocumentAnchorMath.isSameVerticalSentenceSpot(
                "paragraph-1", "paragraph-2", 4, 4, 20, 20));
    }

    @Test
    public void kusamakuraSameSpinePageCapturesRemainSeparateBookmarks() {
        // These mirror distinct captures observed on one Kusamakura spine
        // page. A shared EPUB page number must not collapse them into one
        // bookmark merely because the DOM elements do not carry ids.
        int[][] captures = {
                {22, 8},
                {59, 2},
                {79, 0},
                {103, 0}
        };

        for (int oldIndex = 0; oldIndex < captures.length; oldIndex++) {
            for (int newIndex = oldIndex + 1; newIndex < captures.length; newIndex++) {
                assertFalse(DocumentAnchorMath.isSameVerticalSentenceSpot(
                        "", "",
                        captures[oldIndex][0], captures[newIndex][0],
                        captures[oldIndex][1], captures[newIndex][1]));
            }
        }
    }

    @Test
    public void oneGlyphCaptureJitterUpdatesButLaterGlyphCreatesBookmark() {
        assertTrue(DocumentAnchorMath.isSameVerticalSentenceSpot(
                "", "", 59, 59, 2, 3));
        assertTrue(DocumentAnchorMath.isSameVerticalSentenceSpot(
                "", "", 59, 59, 3, 2));
        assertFalse(DocumentAnchorMath.isSameVerticalSentenceSpot(
                "", "", 59, 59, 2, 4));
    }

    @Test
    public void nativeOnlyVerticalFallbackNeverCollapsesBookmarks() {
        assertFalse(DocumentAnchorMath.isSameVerticalPositionSpot(
                false, 0, 0, false, 0, 0));
        assertTrue(DocumentAnchorMath.isSameVerticalPositionSpot(
                true, -2000, 0, true, -2001, 1));
        assertFalse(DocumentAnchorMath.isSameVerticalPositionSpot(
                true, -2000, 0, true, -2010, 0));
    }

    @Test
    public void onlyNonSemanticV1AnchorIsUpgradeable() {
        assertTrue(DocumentAnchorMath.isUpgradeableLegacyVerticalAnchor(
                "EPUB_CONTENT_ANCHOR_v1", ""));
        assertFalse(DocumentAnchorMath.isUpgradeableLegacyVerticalAnchor(
                "EPUB_CONTENT_ANCHOR_v2", "visible-sentence"));
        assertFalse(DocumentAnchorMath.isUpgradeableLegacyVerticalAnchor(
                "EPUB_CONTENT_ANCHOR_v2", "vertical-position"));
    }

    @Test
    public void previewPrefersCapturedFocusText() {
        assertEquals("current visible phrase", DocumentAnchorMath.focusedPreview(
                " current   visible phrase ", "sentence beginning outside the screen", 22, 42));
    }

    @Test
    public void verticalBookmarkPreviewPrefersVisibleColumnStart() {
        assertEquals("column beginning", DocumentAnchorMath.bookmarkPreview(
                " column   beginning ",
                "middle capture",
                "long sentence",
                20,
                42));
    }

    @Test
    public void oldBookmarkWithoutColumnStartKeepsFocusedPreview() {
        assertEquals("middle capture", DocumentAnchorMath.bookmarkPreview(
                "",
                " middle   capture ",
                "long sentence",
                20,
                42));
    }

    @Test
    public void legacyPreviewIsCenteredAroundSentenceOffset() {
        String sentence = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        String preview = DocumentAnchorMath.focusedPreview("", sentence, 36, 20);

        assertTrue(preview.length() <= 20);
        assertTrue(preview.startsWith("…"));
        assertTrue(preview.endsWith("…"));
        assertTrue(preview.contains("Z"));
    }

    @Test
    public void previewEllipsisDoesNotSplitSupplementaryCharacter() {
        assertEquals("😀…", DocumentAnchorMath.focusedPreview(
                "😀abcdef", "", 0, 2));
        assertEquals("…", DocumentAnchorMath.focusedPreview(
                "😀abcdef", "", 0, 1));
    }
}
