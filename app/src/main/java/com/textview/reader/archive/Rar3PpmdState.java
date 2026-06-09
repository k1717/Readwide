package com.textview.reader.archive;

/** Maintains RAR3/RAR4 PPMd control-mode state across blocks/solid entries. */
final class Rar3PpmdState {
    static final int DEFAULT_ESCAPE_CHAR = 2;

    private int escapeChar = DEFAULT_ESCAPE_CHAR;

    int escapeChar() {
        return escapeChar;
    }

    void setEscapeChar(int value) {
        if (value < 0 || value > 255) throw new IllegalArgumentException("Invalid RAR3 PPMd escape character");
        escapeChar = value;
    }

    void resetNonSolid() {
        escapeChar = DEFAULT_ESCAPE_CHAR;
    }
}
