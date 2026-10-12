package com.readwide.manager;

import org.junit.Test;
import static org.junit.Assert.*;

public class TtsPlaybackTimerTest {
    @Test public void immediateModeExpiresDuringAnUtterance() {
        TtsPlaybackTimer timer = new TtsPlaybackTimer();
        timer.reset(1, false);
        timer.start(0);
        assertEquals(1000L, timer.deadlineDelay(59000));
        assertFalse(timer.shouldStop(59999, false));
        assertTrue(timer.shouldStop(60000, false));
    }

    @Test public void finishSentenceWaitsAcrossChunkBoundaries() {
        TtsPlaybackTimer timer = new TtsPlaybackTimer();
        timer.reset(1, true);
        timer.start(100);
        timer.pause(61100);
        assertEquals(-1L, timer.deadlineDelay(61100));
        assertFalse(timer.shouldStop(61100, false));
        timer.start(62000);
        assertTrue(timer.shouldStop(63000, true));
    }

    @Test public void pausesAndSynthesisGapsDoNotCount() {
        TtsPlaybackTimer timer = new TtsPlaybackTimer();
        timer.reset(1, false);
        timer.start(1000);
        timer.start(2000); // Duplicate start must not reset elapsed time.
        timer.pause(11000);
        assertEquals(10000L, timer.elapsed(999999));
        timer.start(1000000);
        assertEquals(50000L, timer.deadlineDelay(1000000));
        assertTrue(timer.shouldStop(1050000, false));
    }

    @Test public void changingPolicyPreservesElapsedAndDisablingCancelsDeadline() {
        TtsPlaybackTimer timer = new TtsPlaybackTimer();
        timer.reset(1, true);
        timer.start(0);
        timer.configure(1, false);
        assertEquals(0L, timer.deadlineDelay(61000));
        timer.configure(0, false);
        assertEquals(-1L, timer.deadlineDelay(62000));
        assertFalse(timer.shouldStop(62000, true));
        timer.reset(1, false);
        assertEquals(0L, timer.elapsed(62000));
    }
}
