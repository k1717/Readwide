package com.readwide.manager.util;

import java.io.IOException;

/** Checked UTF-16 positions for the reader's existing signed-int coordinate model. */
public final class LargeTextCoordinateMath {
    private LargeTextCoordinateMath() {}

    public static int checkedPosition(long position) throws IOException {
        if (position < 0L || position > Integer.MAX_VALUE) {
            throw new IOException("TXT character position exceeds the supported coordinate range");
        }
        return (int) position;
    }

    public static int checkedLineNumber(long line) throws IOException {
        if (line < 1L || line > Integer.MAX_VALUE) {
            throw new IOException("TXT line number exceeds the supported coordinate range");
        }
        return (int) line;
    }

    /**
     * Counts the separator after a normalized line without pretending that the
     * final synthetic separator is document content. MAX_VALUE + 1 is allowed
     * only as this private traversal cursor; another emitted line is rejected.
     */
    public static long advancePastLine(long start, int length) throws IOException {
        checkedPosition(start);
        if (length < 0) throw new IOException("Negative TXT line length");
        long end = start + (long) length;
        checkedPosition(end);
        return end + 1L;
    }
}
