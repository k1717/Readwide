package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers the approximate plain-text -> Markdown-source offset mapping used to
 * make read-aloud scroll-follow the Markdown viewer. Exact glyph accuracy isn't
 * the goal (there's no bijection without instrumenting the renderer); the tests
 * assert the mapping lands in the right neighborhood and degrades gracefully.
 */
public class MarkdownTtsFollowMathTest {

    private static final String MD =
            "# Title\n\nThis is the **first** paragraph with some text.\n\n"
            + "## Section\n\nAnd here is the *second* paragraph that follows along nicely.";
    private static final String PLAIN =
            "Title\nThis is the first paragraph with some text.\n"
            + "Section\nAnd here is the second paragraph that follows along nicely.";

    @Test
    public void mapsSpokenPositionNearMatchingSourceText() {
        int posSecond = PLAIN.indexOf("second");
        int off = MarkdownTtsFollowMath.approximateSourceOffset(PLAIN, posSecond, MD);
        int mdSecond = MD.indexOf("second");
        assertTrue("should land within a few chars of source 'second' (got " + off
                + ", expected ~" + mdSecond + ")", Math.abs(off - mdSecond) <= 10);
    }

    @Test
    public void mapsFirstParagraphNearItsSource() {
        int posFirst = PLAIN.indexOf("first paragraph");
        int off = MarkdownTtsFollowMath.approximateSourceOffset(PLAIN, posFirst, MD);
        assertTrue(Math.abs(off - MD.indexOf("first")) <= 10);
    }

    @Test
    public void positionZeroMapsToStart() {
        assertEquals(0, MarkdownTtsFollowMath.approximateSourceOffset(PLAIN, 0, MD));
    }

    @Test
    public void emptyInputsAreSafe() {
        assertEquals(0, MarkdownTtsFollowMath.approximateSourceOffset(PLAIN, 5, ""));
        assertEquals(0, MarkdownTtsFollowMath.approximateSourceOffset("", 5, MD));
    }

    @Test
    public void unmatchedProbeFallsBackProportionally() {
        String md = "aaaa bbbb cccc dddd eeee ffff gggg hhhh";
        String plain = "zzzz yyyy xxxx wwww"; // no shared words
        int off = MarkdownTtsFollowMath.approximateSourceOffset(plain, plain.length() / 2, md);
        // Around the middle of the source.
        assertTrue(Math.abs(off - md.length() / 2) <= 8);
    }

    @Test
    public void resultAlwaysWithinSourceRange() {
        for (int p = 0; p <= PLAIN.length(); p += 5) {
            int off = MarkdownTtsFollowMath.approximateSourceOffset(PLAIN, p, MD);
            assertTrue(off >= 0 && off <= MD.length());
        }
    }

    @Test
    public void indexOfNormalizedHandlesWrappingAndCase() {
        assertEquals(0, MarkdownTtsFollowMath.indexOfNormalized("hello\n   world foo", "hello world"));
        assertEquals(4, MarkdownTtsFollowMath.indexOfNormalized("The Quick Brown", "quick brown"));
        assertEquals(-1, MarkdownTtsFollowMath.indexOfNormalized("abc def", "xyz"));
    }

    @Test
    public void boundedSearchRespectsRange() {
        String src = "alpha beta alpha beta";
        // Second occurrence of "alpha" starts at 11; a range starting past the
        // first occurrence must find the second, and a range excluding all
        // occurrences must miss.
        assertEquals(11, MarkdownTtsFollowMath.indexOfNormalized(src, "alpha", 5, src.length()));
        assertEquals(-1, MarkdownTtsFollowMath.indexOfNormalized(src, "alpha", 12, src.length()));
    }

    @Test
    public void hintPrefersNearbyOccurrenceOfRepeatedPhrase() {
        // The same sentence appears early and late; while reading the late one,
        // the hint (near the late occurrence) must keep the mapping there instead
        // of snapping back to the early duplicate.
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 3000; i++) filler.append("filler word ").append(i % 97).append(' ');
        String repeated = "the exact same sentence appears twice in this file";
        String md = repeated + ". " + filler + repeated + ". done";
        String plain = md; // plain == source is fine for this mapping test
        int latePos = md.lastIndexOf(repeated) + 10;
        int lateStart = md.lastIndexOf(repeated);
        int mapped = MarkdownTtsFollowMath.approximateSourceOffset(
                plain, latePos, md, lateStart - 100);
        assertTrue("hinted mapping should stay near the late occurrence (got " + mapped
                + ", late starts at " + lateStart + ")", mapped >= lateStart - 200);
    }

    @Test
    public void buildProbeCollapsesWhitespaceAndKeepsWords() {
        assertEquals("abc def ghi", MarkdownTtsFollowMath.buildProbe("abc   def ghi", 6));
    }
}
