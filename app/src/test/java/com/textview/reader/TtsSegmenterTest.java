package com.textview.reader;

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
    public void normalizeForSpeech_trimsTabsAndExcessBlankLines() {
        assertEquals("hello world\n\nagain",
                TtsSegmenter.normalizeForSpeech("  hello\tworld\n\n\n\nagain  "));
    }
}
