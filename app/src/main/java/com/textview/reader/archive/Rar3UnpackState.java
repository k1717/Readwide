package com.textview.reader.archive;

import java.io.IOException;

final class Rar3UnpackState {
    private final int[] oldDistances = new int[4];
    private int lastDistance;
    private int lastLength;
    private int previousLowDistance;
    private int lowDistanceRepeatCount;

    /**
     * Records a newly decoded long/short-distance LZ match.
     *
     * <p>RAR3 keeps the four most recent explicit distances in move-to-front order. Repeat-last
     * matches do not push a new old-distance entry, while old-distance matches promote the selected
     * slot. The older ring-buffer approximation decoded synthetic tests, but it corrupts real RAR3
     * streams whose old-distance symbols rely on this move-to-front behavior.</p>
     */
    void rememberNewDistanceMatch(int distance, int length) {
        if (distance > 0) {
            pushOldDistance(distance);
            lastDistance = distance;
        }
        if (length > 0) lastLength = length;
    }

    /** Keeps LastDist/LastLength semantics for symbol 258 without touching the old-distance list. */
    void rememberRepeatLastMatch(int length) {
        if (length > 0) lastLength = length;
    }

    void rememberOldDistanceMatch(int index, int length) throws IOException {
        int distance = oldDistance(index);
        if (distance <= 0) throw new IOException("RAR3/RAR4 old-distance match has no previous distance");
        if (index > 0) {
            for (int i = index; i > 0; i--) oldDistances[i] = oldDistances[i - 1];
            oldDistances[0] = distance;
        }
        lastDistance = distance;
        if (length > 0) lastLength = length;
    }

    int oldDistance(int index) {
        if (index < 0 || index >= oldDistances.length) return 0;
        return oldDistances[index];
    }

    int lastDistance() {
        return lastDistance;
    }

    int lastLength() {
        return lastLength;
    }

    int previousLowDistance() {
        return previousLowDistance;
    }

    int lowDistanceRepeatCount() {
        return lowDistanceRepeatCount;
    }

    void rememberLowDistance(int lowDistance) {
        previousLowDistance = lowDistance & 0x0f;
        lowDistanceRepeatCount = 0;
    }

    void startLowDistanceRepeat(int count) {
        lowDistanceRepeatCount = Math.max(0, count);
    }

    void consumeRepeatedLowDistance() {
        if (lowDistanceRepeatCount > 0) lowDistanceRepeatCount--;
    }

    void resetNonSolid() {
        for (int i = 0; i < oldDistances.length; i++) oldDistances[i] = 0;
        lastDistance = 0;
        lastLength = 0;
        previousLowDistance = 0;
        lowDistanceRepeatCount = 0;
    }

    private void pushOldDistance(int distance) {
        for (int i = oldDistances.length - 1; i > 0; i--) oldDistances[i] = oldDistances[i - 1];
        oldDistances[0] = distance;
    }
}
