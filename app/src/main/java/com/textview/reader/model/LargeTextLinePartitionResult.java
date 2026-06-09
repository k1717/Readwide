package com.textview.reader.model;

public final class LargeTextLinePartitionResult {
    public final String content;
    public final int lineCount;
    public final int startLine;
    public final int endLine;
    public final int totalLines;
    public final int baseCharOffset;
    public final int bodyStartCharCount;
    public final int bodyCharCount;
    public final int windowStartLine;
    public final boolean includesLookbehind;
    public final int totalChars;

    public LargeTextLinePartitionResult(String content,
                                        int lineCount,
                                        int startLine,
                                        int endLine,
                                        int totalLines,
                                        int baseCharOffset,
                                        int bodyStartCharCount,
                                        int bodyCharCount,
                                        int windowStartLine,
                                        boolean includesLookbehind,
                                        int totalChars) {
        this.content = content != null ? content : "";
        this.lineCount = Math.max(1, lineCount);
        this.startLine = Math.max(1, startLine);
        this.endLine = Math.max(this.startLine, endLine);
        this.totalLines = Math.max(1, totalLines);
        this.baseCharOffset = Math.max(0, baseCharOffset);
        this.bodyStartCharCount = Math.max(0, Math.min(this.content.length(), bodyStartCharCount));
        this.bodyCharCount = Math.max(this.bodyStartCharCount, Math.min(this.content.length(), bodyCharCount));
        this.windowStartLine = Math.max(1, windowStartLine);
        this.includesLookbehind = includesLookbehind;
        this.totalChars = Math.max(this.baseCharOffset + this.content.length(), totalChars);
    }
}
