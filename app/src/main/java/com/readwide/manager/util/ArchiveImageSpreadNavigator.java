package com.readwide.manager.util;

import java.util.ArrayDeque;

/**
 * Remembers the actual screen starts visited by the optional archive spread.
 *
 * <p>Aspect-ratio knowledge arrives asynchronously. Reconstructing a previous
 * screen only from the currently known portrait pairs can therefore create an
 * overlapping screen that the reader never visited. The small history here
 * makes Back reverse the real forward path; the math fallback is used only
 * when a session starts from a restored or slider-selected page.</p>
 */
public final class ArchiveImageSpreadNavigator {
    private final ArrayDeque<Integer> previousStarts = new ArrayDeque<>();

    public int forward(int currentIndex,
                       int itemCount,
                       boolean currentPairVisible) {
        int current = ImageSequenceNavigationMath.clampIndex(currentIndex, itemCount);
        int target = ArchiveImageSpreadMath.forwardTarget(
                current, itemCount, currentPairVisible);
        if (target != current) previousStarts.push(current);
        return target;
    }

    public int backward(int currentIndex,
                        int itemCount,
                        boolean[] pairAtStart) {
        return backwardWithPairLookup(currentIndex, itemCount,
                index -> pairAtStart != null && index < pairAtStart.length && pairAtStart[index]);
    }

    /** History needs no pair lookup; fallback asks only about the adjacent candidate. */
    public int backwardWithPairLookup(int currentIndex, int itemCount,
                                      java.util.function.IntPredicate pairAtStart) {
        int current = ImageSequenceNavigationMath.clampIndex(currentIndex, itemCount);
        while (!previousStarts.isEmpty()) {
            int candidate = previousStarts.pop();
            if (candidate >= 0 && candidate < itemCount && candidate < current) {
                return candidate;
            }
        }
        if (current <= 0) return 0;
        int pairCandidate = current - 2;
        return pairCandidate >= 0 && pairAtStart.test(pairCandidate) ? pairCandidate : current - 1;
    }

    public void clear() {
        previousStarts.clear();
    }

    int historySizeForTest() {
        return previousStarts.size();
    }
}
