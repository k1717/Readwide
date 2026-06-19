package com.readwide.manager;

import static org.junit.Assert.assertEquals;
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
}
