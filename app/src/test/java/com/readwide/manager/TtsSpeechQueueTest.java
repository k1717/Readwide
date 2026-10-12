package com.readwide.manager;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class TtsSpeechQueueTest {
    private static TtsSpeechSegment segment(int position) {
        return new TtsSpeechSegment(position, position + 1, "a");
    }

    @Test public void prefixRetirementKeepsEngineSequencesStable() {
        TtsSpeechQueue queue = new TtsSpeechQueue();
        queue.addAll(Arrays.asList(segment(0), segment(1), segment(2)));
        int currentId = queue.sequenceAt(1);
        int nextId = queue.sequenceAt(2);
        queue.discardBefore(1);
        assertEquals(2, queue.size());
        assertEquals(currentId, queue.sequenceAt(0));
        assertEquals(nextId, queue.sequenceAt(1));
        assertEquals(-1, queue.indexOfSequence(0));
        queue.addAll(Collections.singletonList(segment(3)));
        assertEquals(3, queue.sequenceAt(2));
    }

    @Test public void longPlaybackDoesNotRetainSpokenHistory() {
        TtsSpeechQueue queue = new TtsSpeechQueue();
        queue.addAll(Collections.singletonList(segment(0)));
        for (int i = 1; i <= 10000; i++) {
            queue.addAll(Collections.singletonList(segment(i)));
            queue.discardBefore(1);
            assertEquals(1, queue.size());
            assertEquals(i, queue.sequenceAt(0));
            assertEquals(i, queue.get(0).startChar);
        }
    }

    @Test public void rejectedSuffixDoesNotRemoveAcceptedEntries() {
        TtsSpeechQueue queue = new TtsSpeechQueue();
        queue.addAll(Arrays.asList(segment(0), segment(1), segment(2)));
        queue.remove(2);
        queue.discardBefore(1);
        queue.addAll(Collections.singletonList(segment(2)));
        assertEquals(1, queue.sequenceAt(0));
        assertEquals(2, queue.sequenceAt(1));
        queue.clear();
        assertTrue(queue.isEmpty());
        queue.addAll(Collections.singletonList(segment(20)));
        assertEquals(0, queue.sequenceAt(0)); // Controller also rotates generation.
    }
}
