package com.readwide.manager;

import org.junit.Test;
import static org.junit.Assert.*;

public class EpubPlaybackGateTest {
    @Test public void focusGainInBackgroundCannotResume() {
        EpubPlaybackGate gate = playing();
        gate.transientLoss(true);
        gate.leaveForeground();
        assertFalse(gate.focusGain());
        assertFalse(gate.canPlay());
        gate.enterForeground();
        assertFalse(gate.focusGain()); // old abandoned request remains invalid
        assertFalse(gate.canPlay());
        assertTrue(gate.recordFocusRequest(true)); // explicit user resume
    }

    @Test public void repeatedTransientLossResumesOnlyOnceWhileForeground() {
        EpubPlaybackGate gate = playing();
        gate.transientLoss(true);
        gate.transientLoss(false);
        assertFalse(gate.canPlay());
        assertTrue(gate.focusGain());
        assertTrue(gate.canPlay());
        assertFalse(gate.focusGain());
    }

    @Test public void manualPauseCancelsFocusAutoResume() {
        EpubPlaybackGate gate = playing();
        gate.transientLoss(true);
        gate.cancelAutoResume();
        gate.abandonFocus();
        assertFalse(gate.focusGain());
        assertFalse(gate.canPlay());
    }

    @Test public void deniedOrBackgroundRequestCannotStartPlayback() {
        EpubPlaybackGate gate = new EpubPlaybackGate();
        assertFalse(gate.recordFocusRequest(true));
        gate.enterForeground();
        assertFalse(gate.recordFocusRequest(false));
        assertFalse(gate.canPlay());
        assertFalse(gate.focusGain());
    }

    @Test public void lossWhileAlreadyPausedDoesNotCreateResumeIntent() {
        EpubPlaybackGate gate = playing();
        gate.transientLoss(false);
        assertFalse(gate.focusGain());
    }

    private static EpubPlaybackGate playing() {
        EpubPlaybackGate gate = new EpubPlaybackGate();
        gate.enterForeground();
        assertTrue(gate.recordFocusRequest(true));
        return gate;
    }
}
