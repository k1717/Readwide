package com.readwide.manager;

/** Foreground/focus policy, separate from MediaPlayer so late callbacks are testable. */
final class EpubPlaybackGate {
    private boolean foreground;
    private boolean focused;
    private boolean requested;
    private boolean resumeOnGain;

    void enterForeground() { foreground = true; }
    void leaveForeground() { foreground = false; abandonFocus(); }
    boolean isForeground() { return foreground; }
    boolean canPlay() { return foreground && focused; }
    void cancelAutoResume() { resumeOnGain = false; }
    void abandonFocus() { focused = false; requested = false; resumeOnGain = false; }
    boolean recordFocusRequest(boolean granted) {
        focused = foreground && granted;
        requested = focused;
        return focused;
    }
    void transientLoss(boolean wasPlaying) {
        focused = false;
        // Repeated loss callbacks must not forget an earlier resumable pause.
        resumeOnGain = foreground && requested && (resumeOnGain || wasPlaying);
    }
    boolean focusGain() {
        focused = foreground && requested;
        boolean resume = focused && resumeOnGain;
        resumeOnGain = false;
        return resume;
    }
}
