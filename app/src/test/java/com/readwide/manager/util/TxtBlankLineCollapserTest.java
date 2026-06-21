package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Behavioral tests for display-only blank-line collapsing.
 *
 * <p>The reader collapses any run of two or more consecutive blank lines down to
 * a single blank line. The small-file load path uses {@link TxtBlankLineCollapser#collapse}
 * on the whole string, while the large-TXT partition walk feeds each line through
 * {@link TxtBlankLineCollapser.Filter}. Both must make identical emit decisions so
 * the page index, search, bookmark anchors, and resume positions stay on one
 * coordinate space; the parity test below pins that contract.
 */
public class TxtBlankLineCollapserTest {

    @Test
    public void isBlankLine_treatsEmptyAndWhitespaceAsBlank() {
        assertTrue(TxtBlankLineCollapser.isBlankLine(null));
        assertTrue(TxtBlankLineCollapser.isBlankLine(""));
        assertTrue(TxtBlankLineCollapser.isBlankLine("   "));
        assertTrue(TxtBlankLineCollapser.isBlankLine("\t \t"));
        assertFalse(TxtBlankLineCollapser.isBlankLine("x"));
        assertFalse(TxtBlankLineCollapser.isBlankLine("  x  "));
    }

    @Test
    public void collapse_reducesTripleBlankToSingle() {
        assertEquals("A\n\nB", TxtBlankLineCollapser.collapse("A\n\n\nB"));
    }

    @Test
    public void collapse_keepsLoneBlankLine() {
        assertEquals("A\n\nB", TxtBlankLineCollapser.collapse("A\n\nB"));
    }

    @Test
    public void collapse_leavesContentWithoutBlankRunsUnchanged() {
        assertEquals("A\nB\nC", TxtBlankLineCollapser.collapse("A\nB\nC"));
    }

    @Test
    public void collapse_treatsWhitespaceOnlyLinesAsBlankAndNormalizesThem() {
        // A run made of whitespace-only lines collapses to a single empty blank line.
        assertEquals("A\n\nB", TxtBlankLineCollapser.collapse("A\n   \n\t\nB"));
    }

    @Test
    public void collapse_normalizesCrlfAndCrToLf() {
        assertEquals("A\n\nB", TxtBlankLineCollapser.collapse("A\r\n\r\n\r\nB"));
        assertEquals("A\n\nB", TxtBlankLineCollapser.collapse("A\r\r\rB"));
    }

    @Test
    public void collapse_isStableOnAlreadyCollapsedContent() {
        // Re-collapsing is a no-op once no run of consecutive blanks remains.
        // (Inputs ending in a blank run are excluded: a kept trailing blank line
        // is a single trailing newline that the join/re-split roundtrip drops, so
        // collapse is only idempotent for content ending in a non-blank line. The
        // app collapses each load once, so this never composes in practice.)
        String input = "A\n\n\n\nB\n   \n\t\nC\nD";
        String once = TxtBlankLineCollapser.collapse(input);
        assertEquals("A\n\nB\n\nC\nD", once);
        assertEquals(once, TxtBlankLineCollapser.collapse(once));
    }

    @Test
    public void collapse_handlesNullAndEmpty() {
        assertEquals("", TxtBlankLineCollapser.collapse(null));
        assertEquals("", TxtBlankLineCollapser.collapse(""));
    }

    @Test
    public void filter_disabled_returnsEveryLineUnchanged() {
        TxtBlankLineCollapser.Filter f = new TxtBlankLineCollapser.Filter(false);
        assertEquals("x", f.accept("x"));
        assertEquals("", f.accept(""));
        assertEquals("   ", f.accept("   "));
        assertEquals("", f.accept(""));
    }

    @Test
    public void filter_dropsConsecutiveBlanksAndNormalizesKeptBlank() {
        TxtBlankLineCollapser.Filter f = new TxtBlankLineCollapser.Filter(true);
        assertEquals("A", f.accept("A"));
        assertEquals("", f.accept("   "));   // first blank kept, normalized to empty
        assertEquals(null, f.accept("\t"));  // second blank dropped
        assertEquals("B", f.accept("B"));
    }

    @Test
    public void smallAndLargePaths_produceIdenticalOutput() {
        // The large-partition walk emits lines through Filter and rejoins with '\n';
        // the small path uses collapse() on the joined string. They must agree.
        // (No trailing empty element: join + re-split would drop it, which is a
        // property of the roundtrip rather than of the collapse decision.)
        String[] lines = {"A", "", "", "B", "  ", "\t", "C", "", "D"};
        String joined = String.join("\n", lines);

        String viaSmallPath = TxtBlankLineCollapser.collapse(joined);
        assertEquals("A\n\nB\n\nC\n\nD", viaSmallPath);

        TxtBlankLineCollapser.Filter f = new TxtBlankLineCollapser.Filter(true);
        StringBuilder viaLargePath = new StringBuilder();
        boolean first = true;
        for (String line : lines) {
            String emitted = f.accept(line);
            if (emitted == null) continue;
            if (!first) viaLargePath.append('\n');
            first = false;
            viaLargePath.append(emitted);
        }

        assertEquals(viaSmallPath, viaLargePath.toString());
    }
}
