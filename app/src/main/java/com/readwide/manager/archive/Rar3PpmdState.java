package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

/** Maintains RAR3/RAR4 PPMd control/model metadata across blocks/solid entries. */
final class Rar3PpmdState {
    static final int DEFAULT_ESCAPE_CHAR = 2;

    private int escapeChar = DEFAULT_ESCAPE_CHAR;
    private boolean modelInitialized;
    private boolean lastKeepOldTable;
    private int lastRawFlags = -1;
    private int maxOrderHint = -1;
    private int memoryMbHint = -1;
    private int blockSequence;
    @Nullable private RarPpmdModel model;

    int escapeChar() {
        return escapeChar;
    }

    void setEscapeChar(int value) {
        if (value < 0 || value > 255) throw new IllegalArgumentException("Invalid RAR3 PPMd escape character");
        escapeChar = value;
    }

    boolean modelInitialized() {
        return modelInitialized;
    }

    boolean hasModel() {
        return model != null;
    }

    boolean lastKeepOldTable() {
        return lastKeepOldTable;
    }

    int lastRawFlags() {
        return lastRawFlags;
    }

    int maxOrderHint() {
        return maxOrderHint;
    }

    int memoryMbHint() {
        return memoryMbHint;
    }

    int blockSequence() {
        return blockSequence;
    }

    @NonNull
    RarPpmdModel requireModel() throws IOException {
        if (model == null) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd model state was requested before initialization: "
                            + diagnostic());
        }
        return model;
    }

    @Nullable
    RarPpmdModel modelForTest() {
        return model;
    }

    /**
     * Applies the visible PPMd block header to the cross-entry state.
     *
     * <p>This prepares the first-party path for real PPMd model construction without claiming
     * that the statistical model is implemented. It also prevents solid PPMd continuation blocks
     * from being decoded without an initialized model in the current solid sequence.</p>
     */
    void applyHeader(@NonNull Rar3PpmdBlockHeader header) throws IOException {
        header.requirePpmd();
        if (header.escapeCharHint() >= 0) setEscapeChar(header.escapeCharHint());
        if (header.keepOldTable() && (!modelInitialized || model == null)) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd continuation block appeared before an initialized PPMd model: "
                            + header.diagnostic());
        }
        lastKeepOldTable = header.keepOldTable();
        lastRawFlags = header.rawFlags();
        blockSequence++;

        if (header.resetModel() || !modelInitialized || model == null) {
            header.requireResetParameters();
            modelInitialized = true;
            maxOrderHint = header.maxOrderHint();
            memoryMbHint = header.memoryMbHint();
            model = new RarPpmdModel(maxOrderHint, memoryMbHint, escapeChar);
        } else {
            model.markSolidContinuation();
        }
    }

    @NonNull
    String diagnostic() {
        return "initialized=" + modelInitialized
                + "; hasModel=" + (model != null)
                + "; lastKeepOldTable=" + lastKeepOldTable
                + "; lastRawFlags=" + lastRawFlags
                + "; maxOrderHint=" + maxOrderHint
                + "; memoryMbHint=" + memoryMbHint
                + "; escapeChar=" + escapeChar
                + "; blockSequence=" + blockSequence;
    }

    void resetNonSolid() {
        escapeChar = DEFAULT_ESCAPE_CHAR;
        modelInitialized = false;
        lastKeepOldTable = false;
        lastRawFlags = -1;
        maxOrderHint = -1;
        memoryMbHint = -1;
        blockSequence = 0;
        model = null;
    }
}
