package com.readwide.manager.util;

import org.junit.Test;
import java.text.Normalizer;
import static org.junit.Assert.*;

public class NormalizedSearchRunsTest {
    @Test public void isolatedAccentDoesNotMoveLargeAsciiRuns() {
        String prefix = new String(new char[100000]).replace('\0', 'x');
        String text = prefix + "e\u0301" + prefix;
        NormalizedSearchText view = NormalizedSearchText.nfc(text);
        assertEquals(prefix + "\u00e9" + prefix, view.value);
        assertEquals(prefix.length(), view.originalStart(prefix.length()));
        assertEquals(prefix.length() + 2, view.originalEnd(prefix.length() + 1));
        assertEquals(text.length(), view.originalEnd(view.value.length()));
    }

    @Test public void canonicalAsciiExpansionAndHangulKeepBoundaries() {
        // Kelvin and Greek question mark normalize to ASCII; they must NOT be
        // mistaken for original ASCII boundaries when constructing the map.
        String text = "ab\u212a\u0301!\u037e \u1100\u1161\u11a8 end\u0344";
        NormalizedSearchText view = NormalizedSearchText.nfc(text);
        assertEquals(Normalizer.normalize(text, Normalizer.Form.NFC), view.value);
        SearchMatcher matcher = SearchMatcher.compile("\uac01", new SearchOptions(true, false, false, true));
        SearchMatcher.Match hit = matcher.firstFrom(text, 0);
        assertNotNull(hit);
        assertEquals(text.indexOf('\u1100'), hit.start);
        assertEquals(hit.start + 3, hit.end);
        int prior = -1;
        for (int i = 0; i < view.value.length(); i++) {
            int start = view.originalStart(i);
            assertTrue(start >= prior);
            assertTrue(view.originalEnd(i + 1) > start);
            prior = start;
        }
    }
}
