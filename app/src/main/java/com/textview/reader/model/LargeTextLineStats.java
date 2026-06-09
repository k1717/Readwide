package com.textview.reader.model;

public final class LargeTextLineStats {
    public final int targetLine;
    public final int totalLines;
    public final int totalChars;

    public LargeTextLineStats(int targetLine, int totalLines, int totalChars) {
        this.targetLine = Math.max(1, targetLine);
        this.totalLines = Math.max(1, totalLines);
        this.totalChars = Math.max(0, totalChars);
    }
}
