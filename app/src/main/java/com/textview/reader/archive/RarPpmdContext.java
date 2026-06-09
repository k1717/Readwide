package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Explicit PPMd context skeleton for the non-live diagnostic path.
 *
 * <p>This class is deliberately not a complete RAR PPMd implementation. It owns validated state
 * records, suffix linkage, and optional suballocator storage metadata so later passes can attach
 * SEE/escape logic and real context creation without changing the unpacker routing surface.</p>
 */
final class RarPpmdContext {
    static final int NO_CONTEXT = -1;
    private static final int MAX_STATES = 256;
    private static final int DEFAULT_MAX_SCALE = 1 << 14;

    @NonNull private final List<RarPpmdStateRecord> states = new ArrayList<>();
    private int suffixPointer = NO_CONTEXT;
    private int stateArrayPointer = NO_CONTEXT;
    private int stateArrayUnits;

    int suffixPointer() {
        return suffixPointer;
    }

    void setSuffixPointer(int suffixPointer) throws IOException {
        validatePointerOrNone(suffixPointer, "suffix");
        this.suffixPointer = suffixPointer;
    }

    int stateArrayPointer() {
        return stateArrayPointer;
    }

    int stateArrayUnits() {
        return stateArrayUnits;
    }

    int stateCount() {
        return states.size();
    }

    int scale() {
        int total = 0;
        for (RarPpmdStateRecord state : states) total += state.frequency();
        return total;
    }

    @Nullable
    RarPpmdStateRecord findState(int symbol) {
        int normalized = symbol & 0xff;
        for (RarPpmdStateRecord state : states) {
            if (state.symbol() == normalized) return state;
        }
        return null;
    }

    @NonNull
    RarPpmdStateRecord stateAt(int index) throws IOException {
        if (index < 0 || index >= states.size()) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd context state index is invalid: " + index);
        }
        return states.get(index);
    }

    @NonNull
    RarPpmdStateRecord insertOrUpdateState(int symbol,
                                           int frequencyDelta,
                                           int successorPointer) throws IOException {
        validatePointerOrNone(successorPointer, "successor");
        RarPpmdStateRecord existing = findState(symbol);
        if (existing != null) {
            existing.incrementFrequency(frequencyDelta);
            if (successorPointer != RarPpmdStateRecord.NO_SUCCESSOR) {
                existing.setSuccessorPointer(successorPointer);
            }
            return existing;
        }
        if (states.size() >= MAX_STATES) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd context cannot hold more than 256 symbols");
        }
        RarPpmdStateRecord state = new RarPpmdStateRecord(symbol & 0xff, frequencyDelta, successorPointer);
        states.add(state);
        return state;
    }

    boolean promoteState(int symbol) {
        int normalized = symbol & 0xff;
        for (int i = 0; i < states.size(); i++) {
            RarPpmdStateRecord state = states.get(i);
            if (state.symbol() == normalized) {
                if (i > 0) {
                    states.remove(i);
                    states.add(0, state);
                }
                return true;
            }
        }
        return false;
    }

    int allocateStateArray(@NonNull RarPpmdSubAllocator allocator) throws IOException {
        int units = Math.max(1, states.size());
        stateArrayPointer = allocator.allocUnits(units);
        stateArrayUnits = units;
        return stateArrayPointer;
    }

    void clearStateArrayOwner() {
        stateArrayPointer = NO_CONTEXT;
        stateArrayUnits = 0;
    }

    void rescaleIfNeeded() {
        if (scale() < DEFAULT_MAX_SCALE) return;
        for (RarPpmdStateRecord state : states) state.halveFrequencyButKeepAlive();
    }

    @NonNull
    RarPpmdContext snapshot() throws IOException {
        RarPpmdContext copy = new RarPpmdContext();
        copy.suffixPointer = suffixPointer;
        copy.stateArrayPointer = stateArrayPointer;
        copy.stateArrayUnits = stateArrayUnits;
        for (RarPpmdStateRecord state : states) copy.states.add(state.copy());
        return copy;
    }

    private static void validatePointerOrNone(int pointer, @NonNull String name) throws IOException {
        if (pointer < NO_CONTEXT) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd context " + name + " pointer is invalid: " + pointer);
        }
    }
}
