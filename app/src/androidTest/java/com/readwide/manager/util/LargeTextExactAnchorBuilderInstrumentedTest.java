package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.readwide.manager.view.CustomReaderView;
import com.readwide.manager.view.CustomReaderView.PageTextAnchor;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.TreeSet;
import java.io.IOException;

/** Integration of canonical body ownership, lookahead handoffs, and global page lookup.
 * Partition-local paging has its own phase; this does not assume that concatenating
 * partitions yields the same anchors as one unpartitioned StaticLayout. */
@RunWith(AndroidJUnit4.class)
public class LargeTextExactAnchorBuilderInstrumentedTest {
    private static final int BODY_LINES = 32;
    private static final int LOOKAHEAD_LINES = 20;
    private static final int WIDTH = 237;
    private static final int HEIGHT = 251;
    private static final int MARGIN = 13;
    private static final float SPACING = 1.2f;

    @Test public void fourBodiesPreserveFilteringHandoffsAndLookupWithBlankRunsAndUnicode() throws IOException {
        ArrayList<String> lines = fixtureLines();
        ArrayList<Integer> starts = new ArrayList<>();
        int offset = 0;
        for (String line : lines) {
            starts.add(offset);
            offset += line.length() + 1;
        }
        String fullText = LargeTextPartitionReader.joinWindow(lines);
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(22.5f);

        for (int overlap : new int[]{0, 1, 8}) {
            ArrayList<PageTextAnchor> anchors = new ArrayList<>();
            ArrayList<Long> handoffs = new ArrayList<>();
            long minimum = 0L;
            for (int firstLine = 0; firstLine < lines.size(); firstLine += BODY_LINES) {
                int bodyLines = Math.min(BODY_LINES, lines.size() - firstLine);
                int end = Math.min(lines.size(), firstLine + bodyLines + LOOKAHEAD_LINES);
                ArrayList<String> windowLines = new ArrayList<>(lines.subList(firstLine, end));
                ArrayList<Integer> windowStarts = new ArrayList<>(starts.subList(firstLine, end));
                String window = LargeTextPartitionReader.joinWindow(windowLines);
                int base = starts.get(firstLine);
                // A body is joined without its synthetic trailing separator, just
                // as the live reader's bodyCharCount contract specifies.
                int bodyChars = LargeTextPartitionReader.joinWindow(
                        new ArrayList<>(windowLines.subList(0, bodyLines))).length();
                long previousMinimum = minimum;
                int before = anchors.size();
                boolean reachesDocumentEnd = end == lines.size();

                int[] localOffsets = CustomReaderView.buildPageStartOffsets(window, window, paint,
                        WIDTH, HEIGHT, MARGIN, overlap, SPACING, reachesDocumentEnd);
                TreeSet<Integer> expectedBodyAnchors = new TreeSet<>();
                if (previousMinimum > base && previousMinimum < base + bodyChars) {
                    expectedBodyAnchors.add((int) previousMinimum);
                }
                long expectedHandoff = previousMinimum;
                boolean hasOutgoingAnchor = false;
                for (int local : localOffsets) {
                    int absolute = base + local;
                    if (absolute < previousMinimum) continue;
                    if (local >= bodyChars) {
                        expectedHandoff = Math.max(previousMinimum,
                                LargeTextContinuityMath.forwardHandoffTargetAbs(base, local, bodyChars));
                        hasOutgoingAnchor = true;
                        break;
                    }
                    expectedBodyAnchors.add(absolute);
                }
                if (reachesDocumentEnd && !expectedBodyAnchors.isEmpty() && !hasOutgoingAnchor) {
                    expectedHandoff = Math.max(expectedHandoff, base + window.length() + 1);
                }

                minimum = append(anchors, windowLines, windowStarts, bodyLines, paint,
                        overlap, previousMinimum, reachesDocumentEnd);
                assertEquals("handoff must match the runtime boundary rule", expectedHandoff, minimum);
                assertEquals("only canonical body anchors plus its incoming handoff",
                        before + expectedBodyAnchors.size(), anchors.size());
                int index = before;
                for (int expected : expectedBodyAnchors) {
                    int actual = anchors.get(index++).charPosition;
                    assertEquals(expected, actual);
                    assertTrue(actual >= base && actual < base + bodyChars);
                    assertTrue(actual >= previousMinimum);
                }
                int count = anchors.size();
                append(anchors, windowLines, windowStarts, bodyLines, paint, overlap,
                        previousMinimum, reachesDocumentEnd);
                assertEquals("replaying a window must not duplicate anchors", count, anchors.size());
                if (firstLine + bodyLines < lines.size()) {
                    assertTrue("lookahead must expose a next-page handoff", minimum >= base + bodyChars);
                    handoffs.add(minimum);
                }
            }

            assertEquals(3, handoffs.size());
            assertFalse(anchors.isEmpty());
            assertEquals(0, anchors.get(0).charPosition);
            int previous = -1;
            for (int i = 0; i < anchors.size(); i++) {
                int position = anchors.get(i).charPosition;
                assertTrue("global anchors must strictly increase", position > previous);
                assertTrue(position >= 0 && position < fullText.length());
                assertFalse("an anchor cannot split a supplementary character",
                        position > 0 && Character.isLowSurrogate(fullText.charAt(position))
                                && Character.isHighSurrogate(fullText.charAt(position - 1)));
                assertEquals(i + 1, LargeTextAnchorMath.findExactPageForChar(anchors, position));
                if (i > 0) {
                    assertEquals("the character before an anchor belongs to its predecessor",
                            i, LargeTextAnchorMath.findExactPageForChar(anchors, position - 1));
                }
                previous = position;
            }
            for (long handoffValue : handoffs) {
                int handoff = Math.toIntExact(handoffValue);
                int page = LargeTextAnchorMath.findExactPageForChar(anchors, handoff);
                assertEquals("handoff itself must survive in the combined exact index",
                        handoff, anchors.get(page - 1).charPosition);
                assertTrue(page > 1);
                assertEquals(page - 1, LargeTextAnchorMath.findExactPageForChar(anchors, handoff - 1));
                assertEquals("forward tap reaches the immediate seam anchor",
                        page - 1, LargeTextAnchorMath.findTapTargetAnchorIndex(anchors, handoff - 1, 1));
            }
        }
    }

    @Test public void terminalLookaheadAlreadyVisibleDoesNotBecomeAnotherBodyPage() throws IOException {
        for (boolean finalBlank : new boolean[]{false, true}) {
            ArrayList<String> lines = shortTailLines(finalBlank);
            ArrayList<Integer> starts = startsFor(lines);
            String window = LargeTextPartitionReader.joinWindow(lines);
            TextPaint paint = shortTailPaint();
            int height = eightRowViewport(window, paint);
            int bodyLines = 10;
            int bodyEnd = starts.get(bodyLines) - 1;
            for (int overlap : new int[]{0, 1, 8}) {
                int[] terminalOffsets = CustomReaderView.buildPageStartOffsets(window, window,
                        paint, WIDTH, height, 0, overlap, 1f, true);
                assertTrue("the last real page starts in the canonical body",
                        terminalOffsets[terminalOffsets.length - 1] < bodyEnd);

                ArrayList<PageTextAnchor> anchors = new ArrayList<>();
                long minimum = LargeTextExactAnchorBuilder.appendPartitionAnchors(anchors,
                        lines, starts, bodyLines, paint, WIDTH, height, 0, overlap, 1f,
                        false, Color.BLACK, Color.WHITE, 0, true);
                assertEquals(window.length() + 1, minimum);
                assertEquals(terminalOffsets.length, anchors.size());
                for (int i = 0; i < terminalOffsets.length; i++) {
                    assertEquals(terminalOffsets[i], anchors.get(i).charPosition);
                }

                ArrayList<String> residualLines = new ArrayList<>(lines.subList(bodyLines, lines.size()));
                ArrayList<Integer> residualStarts = new ArrayList<>(starts.subList(bodyLines, starts.size()));
                int count = anchors.size();
                assertEquals(minimum, LargeTextExactAnchorBuilder.appendPartitionAnchors(anchors,
                        residualLines, residualStarts, residualLines.size(), paint,
                        WIDTH, height, 0, overlap, 1f, false, Color.BLACK, Color.WHITE,
                        minimum, true));
                assertEquals("draining the covered tail must not add another page", count, anchors.size());
                if (finalBlank) {
                    ArrayList<String> blankTail = new ArrayList<>();
                    blankTail.add("");
                    ArrayList<Integer> blankStart = new ArrayList<>();
                    blankStart.add(window.length());
                    assertEquals(minimum, LargeTextExactAnchorBuilder.appendPartitionAnchors(anchors,
                            blankTail, blankStart, 1, paint, WIDTH, height, 0, overlap, 1f,
                            false, Color.BLACK, Color.WHITE, minimum, true));
                    assertEquals("one terminal empty line is already consumed", count, anchors.size());
                }
            }
        }
    }

    @Test public void nonterminalWindowRetainsOverlapContinuationAcrossItsLocalEnd() throws IOException {
        ArrayList<String> lines = shortTailLines(false);
        ArrayList<Integer> starts = startsFor(lines);
        String window = LargeTextPartitionReader.joinWindow(lines);
        TextPaint paint = shortTailPaint();
        int height = eightRowViewport(window, paint);
        int bodyLines = 10;
        int bodyEnd = starts.get(bodyLines) - 1;
        int overlap = 2;
        int[] terminal = CustomReaderView.buildPageStartOffsets(window, window,
                paint, WIDTH, height, 0, overlap, 1f, true);
        int[] continuing = CustomReaderView.buildPageStartOffsets(window, window,
                paint, WIDTH, height, 0, overlap, 1f, false);
        assertTrue("only document EOF suppresses overlap-only tail anchors",
                continuing.length > terminal.length);
        ArrayList<PageTextAnchor> anchors = new ArrayList<>();
        long minimum = LargeTextExactAnchorBuilder.appendPartitionAnchors(anchors,
                lines, starts, bodyLines, paint, WIDTH, height, 0, overlap, 1f,
                false, Color.BLACK, Color.WHITE, 0, false);
        assertEquals("eight visible rows with overlap two continue from row twelve",
                (int) starts.get(12), minimum);
        assertTrue(minimum > bodyEnd && minimum < window.length());
        assertEquals(terminal.length, anchors.size());
        assertEquals(minimum, LargeTextContinuityMath.forwardHandoffTargetAbs(
                0, starts.get(12), bodyEnd));
    }

    @Test public void incomingHandoffInLookaheadIsNotConsumedBeforeItsOwnerBody() throws IOException {
        ArrayList<String> lines = shortTailLines(false);
        ArrayList<Integer> starts = startsFor(lines);
        String window = LargeTextPartitionReader.joinWindow(lines);
        TextPaint paint = shortTailPaint();
        int height = eightRowViewport(window, paint);
        int incoming = starts.get(12);
        ArrayList<PageTextAnchor> anchors = new ArrayList<>();
        anchors.add(new PageTextAnchor(0, "", ""));
        long minimum = LargeTextExactAnchorBuilder.appendPartitionAnchors(anchors,
                lines, starts, 10, paint, WIDTH, height, 0, 2, 1f,
                false, Color.BLACK, Color.WHITE, incoming, true);
        assertEquals("no qualifying body page owns the pending handoff yet", incoming, minimum);
        assertEquals(1, anchors.size());

        ArrayList<String> tail = new ArrayList<>(lines.subList(10, lines.size()));
        ArrayList<Integer> tailStarts = new ArrayList<>(starts.subList(10, starts.size()));
        minimum = LargeTextExactAnchorBuilder.appendPartitionAnchors(anchors,
                tail, tailStarts, tail.size(), paint, WIDTH, height, 0, 2, 1f,
                false, Color.BLACK, Color.WHITE, minimum, true);
        assertEquals(2, anchors.size());
        assertEquals(incoming, anchors.get(1).charPosition);
        assertEquals(window.length() + 1, minimum);
    }

    @Test public void unseenFinalBlankRowRemainsAnAnchorAtTheWindowEnd() throws IOException {
        ArrayList<String> lines = shortTailLines(true);
        ArrayList<Integer> starts = startsFor(lines);
        String window = LargeTextPartitionReader.joinWindow(lines);
        TextPaint paint = shortTailPaint();
        StaticLayout layout = shortTailLayout(window, paint);
        int firstPad = Math.max(0,
                layout.getLineBaseline(0) - layout.getLineTop(0)
                        - (layout.getLineBaseline(1) - layout.getLineTop(1)));
        // The first page stops exactly before the final empty layout row.
        int height = layout.getLineBottom(12) - firstPad;
        int[] offsets = CustomReaderView.buildPageStartOffsets(window, window,
                paint, WIDTH, height, 0, 0, 1f, true);
        assertEquals(2, offsets.length);
        assertEquals(window.length(), offsets[1]);

        ArrayList<PageTextAnchor> anchors = new ArrayList<>();
        long minimum = LargeTextExactAnchorBuilder.appendPartitionAnchors(anchors,
                lines, starts, 10, paint, WIDTH, height, 0, 0, 1f,
                false, Color.BLACK, Color.WHITE, 0, true);
        assertEquals("EOF itself is an outgoing blank-row anchor, not consumed EOF",
                window.length(), minimum);
        assertEquals(1, anchors.size());
        ArrayList<String> tail = new ArrayList<>(lines.subList(10, lines.size()));
        ArrayList<Integer> tailStarts = new ArrayList<>(starts.subList(10, starts.size()));
        minimum = LargeTextExactAnchorBuilder.appendPartitionAnchors(anchors,
                tail, tailStarts, tail.size(), paint, WIDTH, height, 0, 0, 1f,
                false, Color.BLACK, Color.WHITE, minimum, true);
        assertEquals(window.length() + 1, minimum);
        assertEquals(2, anchors.size());
        assertEquals(window.length(), anchors.get(1).charPosition);

        ArrayList<PageTextAnchor> unpartitioned = new ArrayList<>();
        LargeTextExactAnchorBuilder.appendPartitionAnchors(unpartitioned,
                lines, starts, lines.size(), paint, WIDTH, height, 0, 0, 1f,
                false, Color.BLACK, Color.WHITE, 0, true);
        assertEquals(2, unpartitioned.size());
        assertEquals(window.length(), unpartitioned.get(1).charPosition);

        ArrayList<PageTextAnchor> emptyTailAnchors = new ArrayList<>();
        emptyTailAnchors.add(new PageTextAnchor(0, "", ""));
        ArrayList<String> blankTail = new ArrayList<>();
        blankTail.add("");
        ArrayList<Integer> blankStart = new ArrayList<>();
        blankStart.add(window.length());
        assertEquals(window.length() + 1, LargeTextExactAnchorBuilder.appendPartitionAnchors(
                emptyTailAnchors, blankTail, blankStart, 1, paint, WIDTH, height, 0, 0, 1f,
                false, Color.BLACK, Color.WHITE, window.length(), true));
        assertEquals(2, emptyTailAnchors.size());
        assertEquals(window.length(), emptyTailAnchors.get(1).charPosition);
    }

    @Test public void emptyDocumentStillHasItsFirstAnchor() throws IOException {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("");
        ArrayList<Integer> starts = startsFor(lines);
        ArrayList<PageTextAnchor> anchors = new ArrayList<>();
        assertEquals(1, LargeTextExactAnchorBuilder.appendPartitionAnchors(anchors,
                lines, starts, 1, shortTailPaint(), WIDTH, HEIGHT, 0, 8, 1f,
                false, Color.BLACK, Color.WHITE, 0, true));
        assertEquals(1, anchors.size());
        assertEquals(0, anchors.get(0).charPosition);
    }

    @Test public void consumedFinalBlankAtLargestCoordinateIsNotPublishedTwice() throws IOException {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("a");
        lines.add("");
        ArrayList<Integer> starts = new ArrayList<>();
        starts.add(Integer.MAX_VALUE - 2);
        starts.add(Integer.MAX_VALUE);
        ArrayList<PageTextAnchor> anchors = new ArrayList<>();
        long minimum = LargeTextExactAnchorBuilder.appendPartitionAnchors(anchors,
                lines, starts, 1, shortTailPaint(), WIDTH, HEIGHT, 0, 0, 1f,
                false, Color.BLACK, Color.WHITE, Integer.MAX_VALUE - 2L, true);
        assertEquals((long) Integer.MAX_VALUE + 1L, minimum);
        assertEquals(1, anchors.size());
        assertEquals(Integer.MAX_VALUE - 2, anchors.get(0).charPosition);

        lines.remove(0);
        starts.remove(0);
        assertEquals(minimum, LargeTextExactAnchorBuilder.appendPartitionAnchors(anchors,
                lines, starts, 1, shortTailPaint(), WIDTH, HEIGHT, 0, 0, 1f,
                false, Color.BLACK, Color.WHITE, minimum, true));
        assertEquals("consumed MAX_VALUE must remain distinct from an unseen blank", 1, anchors.size());
    }

    @Test public void unseenFinalBlankAtLargestCoordinateRetainsItsAnchor() throws IOException {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("");
        ArrayList<Integer> starts = new ArrayList<>();
        starts.add(Integer.MAX_VALUE);
        ArrayList<PageTextAnchor> anchors = new ArrayList<>();
        anchors.add(new PageTextAnchor(Integer.MAX_VALUE - 2, "", ""));
        assertEquals((long) Integer.MAX_VALUE + 1L,
                LargeTextExactAnchorBuilder.appendPartitionAnchors(anchors,
                        lines, starts, 1, shortTailPaint(), WIDTH, HEIGHT, 0, 0, 1f,
                        false, Color.BLACK, Color.WHITE, Integer.MAX_VALUE, true));
        assertEquals(2, anchors.size());
        assertEquals(Integer.MAX_VALUE, anchors.get(1).charPosition);
    }

    @Test(expected = IOException.class)
    public void unrepresentableWindowEndFailsBeforeLayoutOrAnchorPublication() throws IOException {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("abc");
        ArrayList<Integer> starts = new ArrayList<>();
        starts.add(Integer.MAX_VALUE - 1);
        LargeTextExactAnchorBuilder.appendPartitionAnchors(new ArrayList<>(),
                lines, starts, 1, shortTailPaint(), WIDTH, HEIGHT, 0, 0, 1f,
                false, Color.BLACK, Color.WHITE, 0L, true);
    }

    private static ArrayList<String> shortTailLines(boolean finalBlank) {
        ArrayList<String> lines = new ArrayList<>();
        for (int line = 0; line < 14; line++) {
            lines.add(finalBlank && line == 13 ? "" : "row " + line);
        }
        return lines;
    }

    private static ArrayList<Integer> startsFor(ArrayList<String> lines) {
        ArrayList<Integer> starts = new ArrayList<>();
        int offset = 0;
        for (String line : lines) {
            starts.add(offset);
            offset += line.length() + 1;
        }
        return starts;
    }

    private static TextPaint shortTailPaint() {
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(20f);
        return paint;
    }

    private static int eightRowViewport(String text, TextPaint paint) {
        StaticLayout layout = shortTailLayout(text, paint);
        assertEquals(14, layout.getLineCount());
        return Math.max(layout.getLineBottom(7),
                layout.getLineBottom(13) - layout.getLineTop(6));
    }

    private static StaticLayout shortTailLayout(String text, TextPaint paint) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, WIDTH)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(true)
                .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
    }

    private static long append(ArrayList<PageTextAnchor> anchors, ArrayList<String> lines,
                              ArrayList<Integer> starts, int bodyLines, TextPaint paint,
                              int overlap, long minimum, boolean reachesDocumentEnd) throws IOException {
        return LargeTextExactAnchorBuilder.appendPartitionAnchors(anchors, lines, starts,
                bodyLines, paint, WIDTH, HEIGHT, MARGIN, overlap, SPACING,
                false, Color.BLACK, Color.WHITE, minimum, reachesDocumentEnd);
    }

    private static ArrayList<String> fixtureLines() {
        ArrayList<String> lines = new ArrayList<>();
        for (int line = 0; line < BODY_LINES * 4; line++) {
            // The first and third boundaries land inside long blank runs.
            if ((line >= 22 && line <= 42) || (line >= 86 && line <= 106)) {
                lines.add("");
            } else {
                lines.add("row " + line + " 한글 中文 😀 e\u0301 العربية "
                        + "wrapped words 한글 中文 😀 repeated words repeated words");
            }
        }
        return lines;
    }
}
