package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * Mutable cross-entry state for the unfinished first-party RAR3/RAR4 solid decoder path.
 *
 * <p>Normal compressed RAR is still libarchive-primary. This state holder only gives the
 * Java decoder a controlled place to keep the LZ dictionary, old Huffman table lengths, and
 * repeated distance history between consecutive solid entries, without adding more state to
 * {@link RarArchiveReader}.</p>
 */
final class Rar3SolidState {
    private static final int DEFAULT_WINDOW_SIZE = 4 * 1024 * 1024;

    @NonNull private final byte[] window = new byte[DEFAULT_WINDOW_SIZE];
    @NonNull private final int[] oldTableLengths = new int[Rar3HuffmanTables.TABLE_SIZE];
    @NonNull private final Rar3UnpackState unpackState = new Rar3UnpackState();
    @NonNull private final Rar3PpmdState ppmdState = new Rar3PpmdState();
    private int writePosition;
    private boolean initialized;

    int windowSize() {
        return window.length;
    }

    @NonNull
    byte[] window() {
        return window;
    }

    int writePosition() {
        return writePosition;
    }

    void updateWritePosition(int position) {
        writePosition = position & (window.length - 1);
        initialized = true;
    }

    boolean initialized() {
        return initialized;
    }

    @NonNull
    int[] oldTableLengths() {
        return oldTableLengths;
    }

    @NonNull
    Rar3UnpackState unpackState() {
        return unpackState;
    }

    @NonNull
    Rar3PpmdState ppmdState() {
        return ppmdState;
    }

    void reset() {
        Arrays.fill(window, (byte) 0);
        Arrays.fill(oldTableLengths, 0);
        unpackState.resetNonSolid();
        ppmdState.resetNonSolid();
        writePosition = 0;
        initialized = false;
    }
}
