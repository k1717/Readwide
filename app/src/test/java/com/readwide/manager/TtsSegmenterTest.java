package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class TtsSegmenterTest {
    @Test
    public void segmentPage_prefersSentenceBoundariesAndKeepsOffsets() {
        String text = "First sentence. Second sentence? Third sentence!";

        List<TtsSpeechSegment> segments = TtsSegmenter.segmentPage(text, 100, 24);

        assertEquals(3, segments.size());
        assertEquals(100, segments.get(0).startChar);
        assertEquals(115, segments.get(0).endChar);
        assertEquals("First sentence.", segments.get(0).speechText);
        assertEquals(116, segments.get(1).startChar);
        assertEquals("Second sentence?", segments.get(1).speechText);
        assertEquals(133, segments.get(2).startChar);
        assertEquals(148, segments.get(2).endChar);
    }

    @Test
    public void segmentPage_splitsLongTextWithoutDroppingContent() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            builder.append("가나다라마바사아자차카타파하");
        }
        String text = builder.toString();

        List<TtsSpeechSegment> segments = TtsSegmenter.segmentPage(text, 7, 120);

        assertTrue(segments.size() >= 2);
        assertEquals(7, segments.get(0).startChar);
        TtsSpeechSegment last = segments.get(segments.size() - 1);
        assertEquals(7 + text.length(), last.endChar);
    }

    @Test
    public void segmentPage_shortPageStillSplitsBySentence() {
        // Regression: a page shorter than maxSegmentChars must split by sentence,
        // not collapse into a single segment (which made pause/resume rewind to
        // the page start).
        String text = "Hello world. This is a short page. Done.";

        List<TtsSpeechSegment> segments = TtsSegmenter.segmentPage(text, 0, 700);

        assertEquals(3, segments.size());
        assertEquals("Hello world.", segments.get(0).speechText);
        assertEquals("This is a short page.", segments.get(1).speechText);
        assertEquals("Done.", segments.get(2).speechText);
    }

    @Test
    public void normalizeForSpeech_trimsTabsAndExcessBlankLines() {
        assertEquals("hello world\n\nagain",
                TtsSegmenter.normalizeForSpeech("  hello\tworld\n\n\n\nagain  "));
    }

    @Test
    public void normalizeForSpeech_mutesEllipsesButKeepsLoneDots() {
        // Runs of 2+ dots and the "…" character are muted so the engine does not
        // read them aloud as "점점"; a lone "." (sentence end, decimal point,
        // abbreviation) is preserved.
        assertEquals("He hesitated and left.",
                TtsSegmenter.normalizeForSpeech("He hesitated... and left."));
        assertEquals("Wait", TtsSegmenter.normalizeForSpeech("Wait\u2026"));
        assertEquals("그래 알았어",
                TtsSegmenter.normalizeForSpeech("그래\u2026 알았어"));
        assertEquals("version 1.0.6 e.g. ok",
                TtsSegmenter.normalizeForSpeech("version 1.0.6 e.g. ok"));
    }

    @Test
    public void normalizeForSpeech_mutesUnderscoreAndSemicolon() {
        // Underscores are muted to a space (often voiced as "밑줄"); a semicolon
        // becomes a comma so any spoken name is dropped but the clause pause stays.
        assertEquals("snake case word",
                TtsSegmenter.normalizeForSpeech("snake_case_word"));
        assertEquals("user id, value",
                TtsSegmenter.normalizeForSpeech("user_id; value"));
        assertEquals("a, b, c", TtsSegmenter.normalizeForSpeech("a; b; c"));
    }

    // ---- Phrase length and pause reduction (issue #7) ---------------------

    @Test
    public void phraseLengthMapsToChunkSizes() {
        assertEquals(200, TtsSegmenter.phraseLengthToChars(0)); // Short
        assertEquals(400, TtsSegmenter.phraseLengthToChars(1)); // Medium
        assertEquals(700, TtsSegmenter.phraseLengthToChars(2)); // Long
        // Out-of-range falls back to the long default rather than throwing.
        assertEquals(700, TtsSegmenter.phraseLengthToChars(9));
        assertEquals(700, TtsSegmenter.phraseLengthToChars(-1));
    }

    @Test
    public void pauseReductionOffKeepsPunctuation() {
        String out = TtsSegmenter.normalizeForSpeech("Hello, world. Yes?", 0);
        assertTrue(out.contains(","));
        assertTrue(out.contains("."));
        assertTrue(out.contains("?"));
    }

    @Test
    public void pauseReductionMediumDropsCommasKeepsPeriods() {
        String out = TtsSegmenter.normalizeForSpeech("a, b, c. d.", 1);
        assertFalse("commas should be dropped", out.contains(","));
        assertTrue("periods should remain", out.contains("."));
    }

    @Test
    public void pauseReductionAggressiveRemovesSentenceStops() {
        String out = TtsSegmenter.normalizeForSpeech("A. B! C?", 2);
        assertFalse("no sentence terminators should remain", out.matches(".*[.!?].*"));
        // Stops become comma-length pauses, not nothing - the cadence keeps
        // moving but a short pause survives at each old sentence boundary.
        assertTrue("stops should soften to commas, not vanish", out.contains(","));
        assertTrue(out.contains("A"));
        assertTrue(out.contains("B"));
        assertTrue(out.contains("C"));
    }

    @Test
    public void pauseReductionAggressiveDropsOriginalCommasButKeepsConverted() {
        String out = TtsSegmenter.normalizeForSpeech("First. Second, third. End!", 2);
        // Original comma (after "Second") gone; converted stops present.
        assertFalse(out.contains("Second,"));
        assertTrue(out.contains("First,"));
        assertTrue(out.contains("third,"));
    }

    @Test
    public void standardCleanupAppliesAtAllPauseLevels() {
        for (int level = 0; level <= 2; level++) {
            String out = TtsSegmenter.normalizeForSpeech("Wait... foo_bar", level);
            assertFalse("ellipsis muted at level " + level, out.contains(".."));
            assertFalse("underscore muted at level " + level, out.contains("_"));
        }
    }

    @Test
    public void shorterPhraseLengthProducesMoreOrEqualChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("This is sentence number ").append(i).append(" in the paragraph. ");
        }
        String paragraph = sb.toString();
        int shortChunks = TtsSegmenter.segmentPage(paragraph, 0, 200, 0).size();
        int longChunks = TtsSegmenter.segmentPage(paragraph, 0, 700, 0).size();
        assertTrue("short phrase length should not produce fewer chunks",
                shortChunks >= longChunks);
    }

    @Test
    public void aggressiveMutesQuotesButLowerLevelsKeepThem() {
        String src = "\u201CHello!\u201D she said. \"Fine.\" \u300C\uC548\uB155\u300D";
        // Off and Medium keep quotes (they carry meaning in fiction).
        assertTrue(TtsSegmenter.normalizeForSpeech(src, 0).contains("\u201C"));
        assertTrue(TtsSegmenter.normalizeForSpeech(src, 1).contains("\u201C"));
        // Aggressive mutes straight, curly, and CJK corner quotes.
        String agg = TtsSegmenter.normalizeForSpeech(src, 2);
        assertFalse(agg.contains("\u201C"));
        assertFalse(agg.contains("\u201D"));
        assertFalse(agg.contains("\""));
        assertFalse(agg.contains("\u300C"));
        assertFalse(agg.contains("\u300D"));
        // The words themselves survive.
        assertTrue(agg.contains("Hello"));
        assertTrue(agg.contains("\uC548\uB155"));
    }

    @Test
    public void aggressiveSoftensDialogueFinalStopsOnceQuotesAreMuted() {
        // "...!<closing quote>" - the stop is not followed by whitespace until
        // the quote is muted, so quote muting must run before stop softening.
        String agg = TtsSegmenter.normalizeForSpeech("\u201CStop!\u201D he shouted.", 2);
        assertFalse("dialogue-final stop should soften", agg.matches(".*[.!?].*"));
        assertTrue("softened stop should leave a comma pause", agg.contains(","));
    }

    @Test
    public void apostrophesSurviveAggressiveQuoteMuting() {
        String agg = TtsSegmenter.normalizeForSpeech("Don't stop, it's fine.", 2);
        assertTrue(agg.contains("Don't"));
        assertTrue(agg.contains("it's"));
    }
}
