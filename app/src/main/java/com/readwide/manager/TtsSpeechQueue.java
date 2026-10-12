package com.readwide.manager;

import java.util.ArrayList;
import java.util.List;

/** A rolling window whose engine IDs do not change when spoken entries are retired. */
final class TtsSpeechQueue {
    private final ArrayList<TtsSpeechSegment> segments = new ArrayList<>();
    private int firstSequence;

    int size() { return segments.size(); }
    boolean isEmpty() { return segments.isEmpty(); }
    TtsSpeechSegment get(int index) { return segments.get(index); }
    void addAll(List<TtsSpeechSegment> additions) { segments.addAll(additions); }
    void remove(int index) { segments.remove(index); }
    int sequenceAt(int index) { return firstSequence + index; }
    int indexOfSequence(int sequence) { return sequence - firstSequence; }

    void discardBefore(int index) {
        if (index <= 0) return;
        if (index > segments.size()) throw new IndexOutOfBoundsException();
        segments.subList(0, index).clear();
        firstSequence += index;
    }

    void clear() {
        segments.clear();
        firstSequence = 0;
    }
}
