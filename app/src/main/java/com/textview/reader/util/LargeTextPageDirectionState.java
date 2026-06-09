package com.textview.reader.util;

public final class LargeTextPageDirectionState {
    private int lastDirection;
    private int sameDirectionCount;

    public void record(boolean largeTextActive, int direction) {
        if (!largeTextActive || direction == 0) return;
        if (lastDirection == direction) {
            sameDirectionCount = Math.min(8, sameDirectionCount + 1);
        } else {
            lastDirection = direction;
            sameDirectionCount = 1;
        }
    }

    public void reset() {
        lastDirection = 0;
        sameDirectionCount = 0;
    }

    public boolean preferPrevious() {
        return lastDirection < 0;
    }

    public boolean shouldPrefetchSecondNeighbor() {
        return sameDirectionCount >= 2;
    }

    public int lastDirection() {
        return lastDirection;
    }

    public int sameDirectionCount() {
        return sameDirectionCount;
    }
}
