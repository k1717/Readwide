package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Explicit PPMd context skeleton for the legacy diagnostic/probe path.
 *
 * <p>This class is deliberately not a complete RAR PPMd implementation. It owns validated state
 * records, suffix linkage, and optional suballocator storage metadata so later passes can attach
 * SEE/escape logic and real context creation without changing the unpacker routing surface.</p>
 */
final class RarPpmdContext {
    static final int NO_CONTEXT = -1;
    private static final int MAX_STATES = 256;

    @NonNull private final List<RarPpmdStateRecord> states = new ArrayList<>();
    private int suffixPointer = NO_CONTEXT;
    private int contextPointer = NO_CONTEXT;
    private int stateArrayPointer = NO_CONTEXT;
    private int stateArrayUnits;
    private int primaryEscapeScale;

    int suffixPointer() {
        return suffixPointer;
    }

    void setSuffixPointer(int suffixPointer) throws IOException {
        validatePointerOrNone(suffixPointer, "suffix");
        this.suffixPointer = suffixPointer;
    }

    int contextPointer() {
        return contextPointer;
    }

    void attachContextPointer(int contextPointer) throws IOException {
        validatePointerOrNone(contextPointer, "context");
        if (contextPointer == NO_CONTEXT) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd context pointer cannot be unset during attachment");
        }
        if (this.contextPointer != NO_CONTEXT && this.contextPointer != contextPointer) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd context pointer cannot be reassigned: old="
                            + this.contextPointer + "; new=" + contextPointer);
        }
        this.contextPointer = contextPointer;
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

    int primaryEscapeScale() {
        return primaryEscapeScale;
    }

    void setPrimaryEscapeScale(int primaryEscapeScale) throws IOException {
        if (primaryEscapeScale < 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd primary context escape scale is invalid: "
                            + primaryEscapeScale);
        }
        this.primaryEscapeScale = primaryEscapeScale;
    }

    int primaryScale() throws IOException {
        long total = (long) scale() + (long) primaryEscapeScale;
        if (total <= 0 || total > Integer.MAX_VALUE) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd primary context scale is invalid: symbolScale="
                            + scale() + "; escapeScale=" + primaryEscapeScale);
        }
        return (int) total;
    }

    int unmaskedScale(@NonNull RarPpmdEscapeMask mask) throws IOException {
        int total = 0;
        for (RarPpmdStateRecord state : states) {
            if (!mask.isMasked(state.symbol())) total += state.frequency();
        }
        return total;
    }

    int decodeSymbolOrEscape(@NonNull RarPpmdRangeDecoder rangeDecoder,
                             @NonNull RarPpmdEscapeMask mask,
                             @NonNull RarPpmdSeeContext seeContext) throws IOException {
        return decodeMaskedSymbolOrEscape(rangeDecoder, mask, seeContext).symbolOrEscape();
    }

    int decodePrimarySymbolOrEscape(@NonNull RarPpmdRangeDecoder rangeDecoder,
                                    @NonNull RarPpmdEscapeMask mask) throws IOException {
        return decodePrimaryContextSymbolOrEscape(rangeDecoder, mask).symbolOrEscape();
    }

    int decodePrimarySymbolOrEscape(@NonNull RarPpmdRangeDecoder rangeDecoder,
                                    @NonNull RarPpmdEscapeMask mask,
                                    @NonNull RarPpmdPrimaryUpdatePolicy updatePolicy)
            throws IOException {
        return decodePrimaryContextSymbolOrEscape(rangeDecoder, mask, updatePolicy).symbolOrEscape();
    }

    @NonNull
    RarPpmdPrimaryContextDecoder.Result decodePrimaryContextSymbolOrEscape(
            @NonNull RarPpmdRangeDecoder rangeDecoder,
            @NonNull RarPpmdEscapeMask mask) throws IOException {
        return RarPpmdPrimaryContextDecoder.decode(this, rangeDecoder, mask);
    }

    @NonNull
    RarPpmdPrimaryContextDecoder.Result decodePrimaryContextSymbolOrEscape(
            @NonNull RarPpmdRangeDecoder rangeDecoder,
            @NonNull RarPpmdEscapeMask mask,
            @NonNull RarPpmdPrimaryUpdatePolicy updatePolicy) throws IOException {
        return RarPpmdPrimaryContextDecoder.decode(this, rangeDecoder, mask, updatePolicy);
    }

    @NonNull
    RarPpmdMaskedSymbolDecoder.Result decodeMaskedSymbolOrEscape(
            @NonNull RarPpmdRangeDecoder rangeDecoder,
            @NonNull RarPpmdEscapeMask mask,
            @NonNull RarPpmdSeeContext seeContext) throws IOException {
        return RarPpmdMaskedSymbolDecoder.decode(this, rangeDecoder, mask, seeContext);
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

    boolean promoteStateOneStepIfMoreFrequent(int symbol) {
        int normalized = symbol & 0xff;
        for (int i = 0; i < states.size(); i++) {
            RarPpmdStateRecord state = states.get(i);
            if (state.symbol() == normalized) {
                if (i > 0 && state.frequency() > states.get(i - 1).frequency()) {
                    states.set(i, states.get(i - 1));
                    states.set(i - 1, state);
                }
                return true;
            }
        }
        return false;
    }


    void ensureStateArrayCapacity(@NonNull RarPpmdSubAllocator allocator) throws IOException {
        int requiredUnits = stateArrayUnitsForStateCount(states.size());
        if (stateArrayPointer != NO_CONTEXT && stateArrayUnits >= requiredUnits) return;
        if (stateArrayPointer != NO_CONTEXT && stateArrayUnits > 0) {
            allocator.freeUnits(stateArrayPointer, stateArrayUnits);
            clearStateArrayOwner();
        }
        stateArrayPointer = allocator.allocUnits(requiredUnits);
        stateArrayUnits = requiredUnits;
    }

    int allocateStateArray(@NonNull RarPpmdSubAllocator allocator) throws IOException {
        int units = stateArrayUnitsForStateCount(states.size());
        stateArrayPointer = allocator.allocUnits(units);
        stateArrayUnits = units;
        return stateArrayPointer;
    }

    void clearStateArrayOwner() {
        stateArrayPointer = NO_CONTEXT;
        stateArrayUnits = 0;
    }

    boolean rescaleIfNeeded() throws IOException {
        return rescaleIfNeeded(RarPpmdRescalePolicy.diagnosticDefault());
    }

    boolean rescaleIfNeeded(@NonNull RarPpmdRescalePolicy policy) throws IOException {
        if (!policy.shouldRescale(scale())) return false;
        for (RarPpmdStateRecord state : states) state.halveFrequencyButKeepAlive();
        return true;
    }

    @NonNull
    RarPpmdStateRecord requireState(int symbol) throws IOException {
        RarPpmdStateRecord state = findState(symbol);
        if (state == null) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd decoded state is missing from context: symbol="
                            + (symbol & 0xff));
        }
        return state;
    }

    boolean hasContextPointer() {
        return contextPointer != NO_CONTEXT;
    }

    @NonNull
    RarPpmdContext snapshot() throws IOException {
        RarPpmdContext copy = new RarPpmdContext();
        copy.suffixPointer = suffixPointer;
        copy.contextPointer = contextPointer;
        copy.primaryEscapeScale = primaryEscapeScale;
        copy.stateArrayPointer = stateArrayPointer;
        copy.stateArrayUnits = stateArrayUnits;
        for (RarPpmdStateRecord state : states) copy.states.add(state.copy());
        return copy;
    }

    static void validatePointerOrNoneForModel(int pointer, @NonNull String name) throws IOException {
        validatePointerOrNone(pointer, name);
    }

    private static int stateArrayUnitsForStateCount(int stateCount) {
        // RAR PPMd stores two 6-byte states per 12-byte allocator unit. The Java scaffold keeps
        // states as objects, but allocator ownership should still follow the packed unit count so
        // a full 256-symbol reset root fits into 128 units.
        return Math.max(1, (Math.max(0, stateCount) + 1) >>> 1);
    }

    private static void validatePointerOrNone(int pointer, @NonNull String name) throws IOException {
        if (pointer < NO_CONTEXT) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd context " + name + " pointer is invalid: " + pointer);
        }
    }
}
