package com.readwide.manager.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class SearchStartAndBoundaryTest {
    @Test public void normalizedMatchesHaveUniqueStartsAndAgreeWithNavigation() {
        String[][] cases = {
                {"\u00e1\u0301\u0301\u0301\u031b\u0301\u00e1\u031b", "\u0301\u0301"},
                {"\u0308\u0308\u093c\u0308\u0301", "\u0308\u0308"},
                {"\u0344\u0344", "\u0308"},
                {"\u0f73\u0f73", "\u0f71"}
        };
        for (String[] pair : cases) {
            for (boolean wholeWord : new boolean[] {false, true}) {
                SearchOptions options = new SearchOptions(true, wholeWord, false, true);
                SearchMatcher matcher = SearchMatcher.compile(pair[1], options);
                List<SearchMatcher.Match> hits = new ArrayList<>();
                matcher.forEachMatch(pair[0], (s, e) -> {
                    if (!hits.isEmpty()) assertTrue(s > hits.get(hits.size() - 1).start);
                    hits.add(new SearchMatcher.Match(s, e));
                    return true;
                });
                if (!wholeWord) assertFalse(hits.isEmpty());
                TextMatchIndex index = TextMatchIndex.build(pair[0], pair[1], options, null);
                assertEquals(hits.size(), index.count());
                assertEquals(hits.size(), matcher.count(pair[0]));
                int from = 0;
                for (int i = 0; i < hits.size(); i++) {
                    SearchMatcher.Match hit = hits.get(i);
                    assertMatch(hit, matcher.firstFrom(pair[0], from));
                    assertEquals(hit.start, index.occurrence(i + 1, null).position);
                    from = hit.start + 1;
                }
                assertNull(matcher.firstFrom(pair[0], from));
                SearchMatcher.PreparedText prepared = matcher.prepareText(pair[0]);
                for (int start = 0; start <= pair[0].length(); start++) {
                    final int boundary = start;
                    List<SearchMatcher.Match> expected = new ArrayList<>();
                    for (SearchMatcher.Match hit : hits) if (hit.start >= boundary) expected.add(hit);
                    List<SearchMatcher.Match> actual = new ArrayList<>();
                    prepared.forEachInRange(start, pair[0].length(), (s, e) -> {
                        actual.add(new SearchMatcher.Match(s, e)); return true;
                    });
                    assertEquals(expected.size(), actual.size());
                    for (int i = 0; i < expected.size(); i++) assertMatch(expected.get(i), actual.get(i));
                }
            }
        }
    }

    @Test public void backwardBoundaryKeepsLastCompleteOverlappingLiteral() {
        SearchMatcher matcher = SearchMatcher.compile("aaa", SearchOptions.literal());
        assertMatch(new SearchMatcher.Match(2, 5), matcher.lastUpTo("aaaaaaaa", 7, 5));
        assertMatch(new SearchMatcher.Match(1, 4), matcher.lastUpTo("aaaaaaaa", 1, 5));
        assertNull(matcher.lastUpTo("aaaaaaaa", 7, 2));
        assertNull(matcher.lastUpTo("aaaaaaaa", -1, 8));
        assertNull(matcher.lastUpTo("aaaaaaaa", 7, 0));
        assertMatch(matcher.lastUpTo("aaaaaaaa", 7), matcher.lastUpTo("aaaaaaaa", 7, 99));
        assertEquals(3, SearchMatcher.compile("aa", SearchOptions.literal()).count("aaaa"));
    }

    @Test public void backwardBoundaryDoesNotTruncateRegexOrWholeWordContext() {
        SearchOptions regex = new SearchOptions(true, false, true, false);
        SearchMatcher lookahead = SearchMatcher.compile("abc(?=XYZ)", regex);
        assertMatch(new SearchMatcher.Match(0, 3), lookahead.lastUpTo("abcXYZ", 2, 3));
        assertNull(SearchMatcher.compile("a.+", regex).lastUpTo("aa aa", 4, 2));
        SearchOptions words = new SearchOptions(true, true, false, false);
        assertNull(SearchMatcher.compile("cat", words).lastUpTo("catfish cat", 2, 3));
        SearchOptions nfc = new SearchOptions(true, false, false, true);
        SearchMatcher accent = SearchMatcher.compile("\u00e9", nfc);
        assertNull(accent.lastUpTo("e\u0301", 1, 1));
        assertMatch(new SearchMatcher.Match(0, 2), accent.lastUpTo("e\u0301", 1, 2));
    }

    private static void assertMatch(SearchMatcher.Match expected, SearchMatcher.Match actual) {
        assertNotNull(actual);
        assertEquals(expected.start, actual.start);
        assertEquals(expected.end, actual.end);
    }
}
