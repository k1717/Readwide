package com.readwide.manager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Pure matching/alignment regressions; Android controller lifecycle needs device checks. */
public class PdfSearchTextTest {
    @Test public void lowercaseExpansionCannotShiftFollowingHighlight() {
        assertEquals(Arrays.asList("2:5"), ranges("\u0130 CAT", "cat", false, false, false));
        assertEquals(Arrays.asList("0:1"), ranges("\u0130 CAT", "i", false, false, false));
    }

    @Test public void caseSensitiveQueryKeepsOriginalOffsets() {
        assertEquals(Arrays.asList("4:7"), ranges("cat CAT", "CAT", true, false, false));
        assertTrue(ranges("cat", "CAT", true, false, false).isEmpty());
    }

    @Test public void literalMetacharactersAreNotRegex() {
        assertEquals(Arrays.asList("2:5"), ranges("a a.b axb", "a.b", true, false, false));
        assertEquals(Arrays.asList("0:2"), ranges("\\E", "\\E", true, false, false));
    }

    @Test public void literalMatchesStayNonOverlapping() {
        assertEquals(Arrays.asList("0:2", "2:4"), ranges("aaaa", "aa", true, false, false));
    }

    @Test public void emptyRegexHitsDoNotHideLaterNonemptyMatches() {
        assertEquals(Arrays.asList("1:4"), ranges(" cat", "^|cat", true, false, true));
        assertTrue(ranges("abc", "(?=.)", true, false, true).isEmpty());
    }

    @Test public void multilineAnchorsUseExtractedLineSeparators() {
        assertEquals(Arrays.asList("4:7"), ranges("dog\ncat\nbird", "^cat$", true, false, true));
    }

    @Test public void wholeWordChecksSupplementaryLettersAndUnderscores() {
        // U+10400 is a supplementary letter, occupying two UTF-16 units.
        String text = "\uD801\uDC00cat cat\uD801\uDC00 _cat cat_ cat";
        assertEquals(Arrays.asList("22:25"), ranges(text, "cat", true, true, false));
    }

    @Test public void invalidAndEmptyQueriesDoNotProduceMatchers() {
        assertNull(PdfSearchText.compile(null, false, false, false));
        assertNull(PdfSearchText.compile("", false, false, false));
        assertNull(PdfSearchText.compile("[", false, false, true));
    }

    @Test public void compiledQueryIsReusableAcrossPagesWithoutStateLeak() {
        PdfSearchText.Query query = PdfSearchText.compile("cat", false, false, false);
        assertNotNull(query);
        assertEquals(Arrays.asList("0:3"), collect(query, "CAT"));
        assertEquals(Arrays.asList("2:5", "6:9"), collect(query, "x cat CAT"));
        assertEquals(Arrays.asList("0:3"), collect(query, "CAT"));
    }

    @Test public void cancellationStopsBeforeNextDeliveredMatch() {
        PdfSearchText.Query query = PdfSearchText.compile("a", true, false, false);
        AtomicBoolean cancelled = new AtomicBoolean(true);
        List<String> found = new ArrayList<>();
        query.forEach("aaa", cancelled::get, (start, end) -> found.add(start + ":" + end));
        assertTrue(found.isEmpty());
        cancelled.set(false);
        query.forEach("aaa", cancelled::get, (start, end) -> {
            found.add(start + ":" + end);
            cancelled.set(true);
        });
        assertEquals(Arrays.asList("0:1"), found);
    }

    @Test public void inferredSpacesAndNewlinesKeepGlyphAlignment() {
        StringBuilder text = new StringBuilder("cat");
        List<Integer> boxes = new ArrayList<>(Arrays.asList(1, 2, 3));
        PdfSearchText.appendSeparator(text, boxes, " ");
        text.append("dog");
        boxes.addAll(Arrays.asList(4, 5, 6));
        PdfSearchText.appendSeparator(text, boxes, "\n");
        assertEquals("cat dog\n", text.toString());
        assertEquals(text.length(), boxes.size());
        assertNull(boxes.get(3));
        assertNull(boxes.get(7));
        assertEquals(Integer.valueOf(4), boxes.get(4));
        assertEquals(Arrays.asList("0:7"), ranges(text.toString(), "cat dog", true, false, false));
        assertTrue(ranges(text.toString(), "catdog", true, false, false).isEmpty());
    }

    private static List<String> ranges(String text, String query, boolean sensitive,
                                       boolean wholeWord, boolean regex) {
        PdfSearchText.Query compiled = PdfSearchText.compile(query, sensitive, wholeWord, regex);
        assertNotNull(compiled);
        return collect(compiled, text);
    }

    private static List<String> collect(PdfSearchText.Query query, String text) {
        List<String> found = new ArrayList<>();
        query.forEach(text, () -> false, (start, end) -> found.add(start + ":" + end));
        return found;
    }
}
