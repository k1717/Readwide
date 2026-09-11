package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Behavioral tests for the shared TXT find-in-page matcher: case folding,
 * whole-word, regex, overlapping counts, and original-coordinate stability.
 */
public class SearchMatcherTest {

    @Test public void regexLineAnchorsAgreeForWholeInputAndSeparateLines() {
        SearchMatcher matcher = m("^cat$", true, false, true, false);
        assertEquals(1, matcher.count("dog\ncat\nbird"));
        assertEquals(1, matcher.count("dog\r\ncat\r\nbird"));
        assertEquals(1, matcher.count("cat"));
        assertEquals(4, matcher.nthStart("dog\ncat\nbird", 1));
    }

    @Test public void regexRangeDoesNotInventOverlappingSuffixMatches() {
        SearchMatcher.PreparedText text = m("aa", true, false, true, false).prepareText("aaaa");
        java.util.List<Integer> hits = new java.util.ArrayList<>();
        text.forEachInRange(1, 4, (start, end) -> { hits.add(start); return true; });
        assertEquals(java.util.Arrays.asList(2), hits);
    }

    @Test public void preparedRangesKeepOverlapAndCrossBandTail() {
        SearchMatcher.PreparedText text = SearchMatcher.compile("aa", SearchOptions.literal()).prepareText("aaaaZaa");
        java.util.List<String> hits = new java.util.ArrayList<>();
        text.forEachInRange(1, 3, (start, end) -> { hits.add(start + ":" + end); return true; });
        assertEquals(java.util.Arrays.asList("1:3", "2:4"), hits);
        hits.clear();
        text.forEachInRange(5, 6, (start, end) -> { hits.add(start + ":" + end); return true; });
        assertEquals(java.util.Arrays.asList("5:7"), hits);
    }

    @Test public void preparedRangeUsesOriginalWordBoundariesAndOffsets() {
        SearchMatcher.PreparedText text = SearchMatcher.compile("CAT", new SearchOptions(false, true, false, true))
                .prepareText("xcat cat!");
        java.util.List<Integer> hits = new java.util.ArrayList<>();
        text.forEachInRange(1, 6, (start, end) -> { hits.add(start); return true; });
        assertEquals(java.util.Arrays.asList(5), hits);
    }

    @Test public void preparedRegexRetainsLookaroundOutsideBand() {
        SearchMatcher.PreparedText text = SearchMatcher.compile("(?<=x)cat(?=!)", new SearchOptions(true, false, true, false))
                .prepareText("xcat! xcat!");
        java.util.List<Integer> hits = new java.util.ArrayList<>();
        text.forEachInRange(1, 2, (start, end) -> { hits.add(start); return true; });
        assertEquals(java.util.Arrays.asList(1), hits);
        hits.clear();
        text.forEachInRange(7, 8, (start, end) -> { hits.add(start); return true; });
        assertEquals(java.util.Arrays.asList(7), hits);
    }

    private static SearchMatcher m(String q, boolean caseSensitive, boolean wholeWord, boolean regex, boolean normalize) {
        return SearchMatcher.compile(q, new SearchOptions(caseSensitive, wholeWord, regex, normalize));
    }

    @Test
    public void overlappingMatchesAreCounted() {
        // Legacy step=query.length() reported 2; correct overlapping count is 3.
        assertEquals(3, m("aa", true, false, false, false).count("aaaa"));
        assertEquals(2, m("ana", true, false, false, false).count("banana"));
    }

    @Test
    public void caseInsensitiveMatching() {
        SearchMatcher ci = m("apple", false, false, false, false);
        assertNotNull(ci.firstFrom("An Apple a day", 0));
        assertEquals(3, ci.firstFrom("An Apple a day", 0).start);
        assertEquals(3, ci.count("apple APPLE Apple"));
        // case-sensitive does not match different case
        assertNull(m("apple", true, false, false, false).firstFrom("An Apple", 0));
    }

    @Test
    public void wholeWordMatching() {
        SearchMatcher ww = m("cat", true, true, false, false);
        assertEquals(9, ww.firstFrom("category cat scatter", 0).start);
        assertEquals(1, ww.count("category cat scatter"));
        assertEquals(2, m("cat", true, true, false, false).count("cat cat category"));
    }

    @Test
    public void regexMatching() {
        SearchMatcher rx = m("colou?r", false, false, true, false);
        assertNotNull(rx.firstFrom("the Color is", 0));
        assertNotNull(rx.firstFrom("the colour is", 0));
        assertEquals(2, rx.count("color colour"));
        // digit pattern
        assertEquals(3, m("\\d+", false, false, true, false).count("a1 b22 c333"));
    }

    @Test
    public void invalidRegexCompilesToNull() {
        assertNull(SearchMatcher.compile("(unclosed", new SearchOptions(false, false, true, false)));
    }

    @Test
    public void emptyQueryCompilesToNull() {
        assertNull(SearchMatcher.compile("", SearchOptions.literal()));
        assertNull(SearchMatcher.compile(null, SearchOptions.literal()));
    }

    @Test
    public void matchOffsetsMapToOriginalText() {
        String text = "XxYy apple ZZ";
        SearchMatcher.Match hit = m("APPLE", false, false, false, false).firstFrom(text, 0);
        assertNotNull(hit);
        assertEquals(5, hit.start);
        assertEquals("apple", text.substring(hit.start, hit.end));
    }

    @Test
    public void nthAndOrdinalAndBackward() {
        SearchMatcher ci = m("x", false, false, false, false);
        assertEquals(1, ci.nthStart("xXx", 2));
        assertEquals(2, ci.ordinalForPosition("xXx", 1));
        assertNotNull(ci.lastUpTo("xXx", 2));
        assertEquals(2, ci.lastUpTo("xXx", 2).start);
    }

    @Test
    public void unicodeNormalizeStaysInRange() {
        // Decomposed "e + combining acute" searched with precomposed é.
        String decomposed = "cafe\u0301";
        SearchMatcher mm = m("\u00e9", false, false, false, true);
        SearchMatcher.Match hit = mm.firstFrom(decomposed, 0);
        // Length-changing NFC is deliberately not adopted in literal mode.
        assertNull(hit);
        assertEquals(3, m("e\u0301", true, false, false, true).firstFrom(decomposed, 0).start);
    }

    @Test
    public void literalFactoryIsCaseSensitivePlainSubstring() {
        assertEquals(6, SearchMatcher.compile("world", SearchOptions.literal()).firstFrom("hello world", 0).start);
        assertNull(SearchMatcher.compile("WORLD", SearchOptions.literal()).firstFrom("hello world", 0));
    }
}
