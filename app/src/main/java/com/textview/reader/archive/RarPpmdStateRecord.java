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
    private static final int MAX_FREQUENCY = 0xffff;

    private final int symbol;
    private int frequency;
    private int successorPointer;

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
        this.successorPointer = successorPointer;
    }

    @NonNull
    RarPpmdStateRecord copy() throws IOException {
        return new RarPpmdStateRecord(symbol, frequency, successorPointer);
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
