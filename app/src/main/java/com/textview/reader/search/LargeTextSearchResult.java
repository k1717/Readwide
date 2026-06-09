package com.textview.reader.search;

/** Immutable result value used by large-TXT search code. */
public final class LargeTextSearchResult {
    public final int charPosition;
    public final int lineNumber;
    public final int ordinal;
    public final int total;

    public LargeTextSearchResult(int charPosition, int lineNumber, int ordinal, int total) {
        this.charPosition = charPosition;
        this.lineNumber = Math.max(1, lineNumber);
        this.ordinal = Math.max(0, ordinal);
        // total == -1 means the nearest-match search intentionally stopped early
        // for responsiveness, so the exact match count is not known yet.
        this.total = total < 0 ? -1 : Math.max(0, total);
    }

    public boolean found() {
        return charPosition >= 0;
    }

    public boolean totalKnown() {
        return total >= 0;
    }
}
