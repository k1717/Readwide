package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for the bookmark/resume anchor resolver used by both the small and
 * large-TXT paths. A stored char position can drift when the reader coordinate
 * space changes (most notably after toggling blank-line collapse), so the
 * resolver re-locates the saved passage from its surrounding anchor text rather
 * than trusting the raw offset.
 */
public class TxtAnchorResolveAfterCollapseTest {

    private static final String MARKER = "BBBB marker passage CCCC.";

    @Test
    public void anchorRelocatesPassageAfterCollapseShiftsTheOffset() {
        // Same logical document in two collapse states. The marker sits later in
        // the uncollapsed text (extra blank lines push it right).
        String uncollapsed = "P1.\n\n\nP2.\n\n\n" + MARKER + "\n\n\nEnd.";
        String collapsed = "P1.\n\nP2.\n\n" + MARKER + "\n\nEnd.";

        int stalePosition = uncollapsed.indexOf(MARKER); // saved while collapse was OFF
        int expectedInCollapsed = collapsed.indexOf(MARKER);
        // Sanity: collapse actually moved the passage, so this is a real drift case.
        org.junit.Assert.assertNotEquals(stalePosition, expectedInCollapsed);

        String anchorBefore = "P2.\n\n\n";
        String anchorAfter = MARKER;

        int resolved = TextSearchMath.resolveAnchoredAbsolutePosition(
                collapsed, 0, stalePosition, anchorBefore, anchorAfter);

        assertEquals(expectedInCollapsed, resolved);
    }

    @Test
    public void missingAnchorFallsBackToClampedRawPosition() {
        String content = "short content body";
        int raw = 5;
        int resolved = TextSearchMath.resolveAnchoredAbsolutePosition(
                content, 0, raw, "NONEXISTENT BEFORE TEXT", "NONEXISTENT AFTER TEXT");
        // Anchor text is absent, so the resolver returns the raw position clamped
        // into the content rather than throwing or jumping elsewhere.
        assertEquals(raw, resolved);
    }

    @Test
    public void rawPositionBeyondContentClampsToEnd() {
        String content = "tiny body";
        int resolved = TextSearchMath.resolveAnchoredAbsolutePosition(
                content, 0, 99999, "", "");
        assertEquals(content.length(), resolved);
    }

    @Test
    public void resolutionAddsPartitionBaseOffsetBack() {
        // Large-TXT partitions resolve within a window whose first character maps
        // to an absolute file offset (baseCharOffset). The returned position must
        // be absolute.
        int baseCharOffset = 1000;
        String partitionBody = "xxxx" + MARKER + "yyyy";
        int localIndex = partitionBody.indexOf(MARKER); // 4
        int staleAbsolute = baseCharOffset + 40; // wrong-ish, forces anchor use

        int resolved = TextSearchMath.resolveAnchoredAbsolutePosition(
                partitionBody, baseCharOffset, staleAbsolute, "xxxx", MARKER);

        assertEquals(baseCharOffset + localIndex, resolved);
    }
}
