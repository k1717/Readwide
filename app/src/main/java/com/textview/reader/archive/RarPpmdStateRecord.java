package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Small mutable PPMd state-record skeleton for the first-party RAR3/RAR4 model work.
 *
 * <p>Real RAR PPMd stores compact model states in a custom suballocator. This class keeps the
 * representation explicit and validated so future passes can map it onto the allocator layout
 * without leaking unchecked symbol/frequency/successor handling through the decoder.</p>
 */
final class RarPpmdStateRecord {
    static final int NO_SUCCESSOR = -1;
    static final int SUCCESSOR_KIND_NONE = 0;
    static final int SUCCESSOR_KIND_CONTEXT = 1;
    static final int SUCCESSOR_KIND_PENDING_TEXT = 2;
    private static final int MAX_FREQUENCY = 0xffff;

    private final int symbol;
    private int frequency;
    private int successorPointer;
    private int successorKind;
    private int pendingSuccessorSymbol = -1;

    RarPpmdStateRecord(int symbol, int frequency) throws IOException {
        this(symbol, frequency, NO_SUCCESSOR);
    }

    RarPpmdStateRecord(int symbol, int frequency, int successorPointer) throws IOException {
        validateSymbol(symbol);
        validateFrequency(frequency);
        validatePointer(successorPointer);
        this.symbol = symbol;
        this.frequency = frequency;
        this.successorPointer = successorPointer;
        this.successorKind = successorPointer == NO_SUCCESSOR
                ? SUCCESSOR_KIND_NONE : SUCCESSOR_KIND_CONTEXT;
    }

    int symbol() {
        return symbol;
    }

    int frequency() {
        return frequency;
    }

    int successorPointer() {
        return successorPointer;
    }

    int successorKind() {
        return successorKind;
    }

    int pendingSuccessorSymbol() {
        return pendingSuccessorSymbol;
    }

    boolean hasSuccessor() {
        return successorKind != SUCCESSOR_KIND_NONE;
    }

    boolean hasContextSuccessor() {
        return successorKind == SUCCESSOR_KIND_CONTEXT && successorPointer != NO_SUCCESSOR;
    }

    boolean hasPendingTextSuccessor() {
        return successorKind == SUCCESSOR_KIND_PENDING_TEXT;
    }

    void incrementFrequency(int delta) throws IOException {
        if (delta <= 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd state frequency increment is invalid: " + delta);
        }
        if (frequency > MAX_FREQUENCY - delta) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd state frequency overflow: symbol=" + symbol
                            + ", frequency=" + frequency + ", delta=" + delta);
        }
        frequency += delta;
    }

    void halveFrequencyButKeepAlive() {
        frequency = (frequency + 1) >>> 1;
        if (frequency <= 0) frequency = 1;
    }

    void setSuccessorPointer(int successorPointer) throws IOException {
        validatePointer(successorPointer);
        if (successorPointer == NO_SUCCESSOR) {
            this.successorPointer = NO_SUCCESSOR;
            this.successorKind = SUCCESSOR_KIND_NONE;
            this.pendingSuccessorSymbol = -1;
        } else {
            this.successorPointer = successorPointer;
            this.successorKind = SUCCESSOR_KIND_CONTEXT;
            this.pendingSuccessorSymbol = -1;
        }
    }

    void setPendingTextSuccessor(int successorSymbol) throws IOException {
        validateSymbol(successorSymbol);
        this.successorPointer = NO_SUCCESSOR;
        this.successorKind = SUCCESSOR_KIND_PENDING_TEXT;
        this.pendingSuccessorSymbol = successorSymbol & 0xff;
    }

    @NonNull
    RarPpmdStateRecord copy() throws IOException {
        RarPpmdStateRecord copy = new RarPpmdStateRecord(symbol, frequency, successorPointer);
        if (hasPendingTextSuccessor()) copy.setPendingTextSuccessor(pendingSuccessorSymbol);
        return copy;
    }

    private static void validateSymbol(int symbol) throws IOException {
        if (symbol < 0 || symbol > 255) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd state symbol is out of byte range: " + symbol);
        }
    }

    private static void validateFrequency(int frequency) throws IOException {
        if (frequency <= 0 || frequency > MAX_FREQUENCY) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd state frequency is invalid: " + frequency);
        }
    }

    private static void validatePointer(int pointer) throws IOException {
        if (pointer < NO_SUCCESSOR) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd state successor pointer is invalid: " + pointer);
        }
    }
}
