package com.readwide.manager;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** Alignment decisions only: no Android rectangles or PDFBox execution. */
public class PdfGlyphTextTest {
    @Test public void exactRunMayUseDifferentGlyphChunkSizes() {
        assertTrue(matches("office", "of", "fi", "c", "e"));
    }

    @Test public void equalLengthButDifferentTextIsNotAlignment() {
        assertFalse(matches("abc", "a", "x", "c"));
        assertFalse(matches("abc", "c", "b", "a"));
    }

    @Test public void missingAndExtraGlyphTextCannotBePaddedOrTrimmed() {
        assertFalse(matches("abc", "a", "b"));
        assertFalse(matches("abc", "abc", "d"));
        assertFalse(matches("abc", "abcd"));
    }

    @Test public void normalizedOrExpandedLigatureRequiresExplicitMapping() {
        assertFalse(matches("fi", "\ufb01"));
        assertFalse(matches("\u00e9", "e\u0301"));
        assertTrue(matches("fi", "fi"));
    }

    @Test public void supplementaryCharactersRetainTheirUtf16Units() {
        assertTrue(matches("a\ud83d\ude00b", "a", "\ud83d\ude00", "b"));
        assertFalse(matches("a\ud83d\ude00b", "a", "\ud83d\ude01", "b"));
    }

    @Test public void missingRunOrGlyphListIsRejected() {
        assertFalse(PdfGlyphText.matchesRun(null, Collections.singletonList("a"), s -> s));
        assertFalse(PdfGlyphText.matchesRun("a", (List<String>) null, s -> s));
        assertFalse(matches("a", "a", null));
    }

    @Test public void emptyUnicodeMappingsDoNotShiftFollowingGlyphs() {
        List<String[]> glyphs = Arrays.asList(new String[] {null}, new String[] {""}, new String[] {"ab"});
        assertTrue(PdfGlyphText.matchesRun("ab", glyphs, glyph -> glyph[0]));
        assertFalse(PdfGlyphText.matchesRun("abc", glyphs, glyph -> glyph[0]));
    }

    @Test public void emptyRunRequiresNoNonemptyGlyphText() {
        assertTrue(matches(""));
        assertTrue(matches("", ""));
        assertFalse(matches("", "a"));
    }

    private static boolean matches(String run, String... glyphs) {
        return PdfGlyphText.matchesRun(run, Arrays.asList(glyphs), s -> s);
    }
}
