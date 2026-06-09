package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class Rar3UnpackStateTest {
    @Test
    public void rememberNewDistanceMatch_tracksLastAndMoveToFrontDistances() {
        Rar3UnpackState state = new Rar3UnpackState();

        state.rememberNewDistanceMatch(10, 3);
        state.rememberNewDistanceMatch(20, 4);
        state.rememberNewDistanceMatch(30, 5);

        assertEquals(30, state.lastDistance());
        assertEquals(5, state.lastLength());
        assertEquals(30, state.oldDistance(0));
        assertEquals(20, state.oldDistance(1));
        assertEquals(10, state.oldDistance(2));
    }

    @Test
    public void repeatLastMatch_doesNotPushOldDistance() {
        Rar3UnpackState state = new Rar3UnpackState();

        state.rememberNewDistanceMatch(10, 3);
        state.rememberNewDistanceMatch(20, 4);
        state.rememberRepeatLastMatch(4);

        assertEquals(20, state.oldDistance(0));
        assertEquals(10, state.oldDistance(1));
    }

    @Test
    public void oldDistanceMatch_promotesSelectedDistance() throws Exception {
        Rar3UnpackState state = new Rar3UnpackState();

        state.rememberNewDistanceMatch(10, 3);
        state.rememberNewDistanceMatch(20, 4);
        state.rememberNewDistanceMatch(30, 5);
        state.rememberOldDistanceMatch(2, 6);

        assertEquals(10, state.lastDistance());
        assertEquals(6, state.lastLength());
        assertEquals(10, state.oldDistance(0));
        assertEquals(30, state.oldDistance(1));
        assertEquals(20, state.oldDistance(2));
    }

    @Test
    public void resetNonSolid_clearsMatchHistory() {
        Rar3UnpackState state = new Rar3UnpackState();
        state.rememberNewDistanceMatch(10, 3);
        state.rememberLowDistance(7);
        state.startLowDistanceRepeat(3);

        state.resetNonSolid();

        assertEquals(0, state.lastDistance());
        assertEquals(0, state.lastLength());
        assertEquals(0, state.oldDistance(0));
        assertEquals(0, state.previousLowDistance());
        assertEquals(0, state.lowDistanceRepeatCount());
    }

    @Test
    public void lowDistanceRepeatCountTracksRepeatedLowDistance() {
        Rar3UnpackState state = new Rar3UnpackState();

        state.rememberLowDistance(5);
        state.startLowDistanceRepeat(2);
        state.consumeRepeatedLowDistance();

        assertEquals(5, state.previousLowDistance());
        assertEquals(1, state.lowDistanceRepeatCount());
    }
}
