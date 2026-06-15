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
        // Must not crash and any returned offsets stay within the original string.
        assertTrue(hit == null || (hit.start >= 0 && hit.end <= decomposed.length()));
    }

    @Test
    public void literalFactoryIsCaseSensitivePlainSubstring() {
        assertEquals(6, SearchMatcher.compile("world", SearchOptions.literal()).firstFrom("hello world", 0).start);
        assertNull(SearchMatcher.compile("WORLD", SearchOptions.literal()).firstFrom("hello world", 0));
    }
}
