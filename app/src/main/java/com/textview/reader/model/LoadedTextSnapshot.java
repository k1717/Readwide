package com.textview.reader.model;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class LoadedTextSnapshot {
    public final String sourcePath;
    public final String sourceUri;
    public final String filePath;
    public final String fileName;
    public final String fileContent;
    public final int totalChars;
    public final int totalLines;
    public final int charPosition;
    public final String activeSearchQuery;
    public final int activeSearchIndex;
    public final boolean largeTextEstimateActive;
    public final int largeTextEstimatedTotalPages;
    public final int pendingLargeTextRestorePosition;
    public final int largeTextPreviewBaseCharOffset;
    public final int largeTextEstimatedBasePageOffset;
    public final int largeTextEstimatedTotalChars;
    public final boolean hugeTextPreviewOnly;
    public final int pendingLargeTextCachedDisplayPage;
    public final int pendingLargeTextCachedTotalPages;
    public final long largeTextPartitionStartByte;
    public final long largeTextPartitionEndByte;
    public final long largeTextFileByteLength;
    public final float largeTextEstimatedBytesPerChar;
    public final int largeTextPartitionBodyStartCharCount;
    public final int largeTextPartitionBodyCharCount;
    public final int largeTextPartitionWindowStartLine;
    public final int largeTextPartitionStartLine;
    public final int largeTextPartitionEndLine;
    public final int largeTextTotalLogicalLines;

    public LoadedTextSnapshot(String sourcePath,
                              String sourceUri,
                              String filePath,
                              String fileName,
                              String fileContent,
                              int totalChars,
                              int totalLines,
                              int charPosition,
                              String activeSearchQuery,
                              int activeSearchIndex,
                              boolean largeTextEstimateActive,
                              int largeTextEstimatedTotalPages,
                              int pendingLargeTextRestorePosition,
                              int largeTextPreviewBaseCharOffset,
                              int largeTextEstimatedBasePageOffset,
                              int largeTextEstimatedTotalChars,
                              boolean hugeTextPreviewOnly,
                              int pendingLargeTextCachedDisplayPage,
                              int pendingLargeTextCachedTotalPages,
                              long largeTextPartitionStartByte,
                              long largeTextPartitionEndByte,
                              long largeTextFileByteLength,
                              float largeTextEstimatedBytesPerChar,
                              int largeTextPartitionBodyStartCharCount,
                              int largeTextPartitionBodyCharCount,
                              int largeTextPartitionWindowStartLine,
                              int largeTextPartitionStartLine,
                              int largeTextPartitionEndLine,
                              int largeTextTotalLogicalLines) {
        this.sourcePath = sourcePath;
        this.sourceUri = sourceUri;
        this.filePath = filePath;
        this.fileName = fileName;
        this.fileContent = fileContent != null ? fileContent : "";
        this.totalChars = totalChars;
        this.totalLines = totalLines;
        this.charPosition = Math.max(0, charPosition);
        this.activeSearchQuery = activeSearchQuery != null ? activeSearchQuery : "";
        this.activeSearchIndex = activeSearchIndex;
        this.largeTextEstimateActive = largeTextEstimateActive;
        this.largeTextEstimatedTotalPages = largeTextEstimatedTotalPages;
        this.pendingLargeTextRestorePosition = pendingLargeTextRestorePosition;
        this.largeTextPreviewBaseCharOffset = largeTextPreviewBaseCharOffset;
        this.largeTextEstimatedBasePageOffset = largeTextEstimatedBasePageOffset;
        this.largeTextEstimatedTotalChars = largeTextEstimatedTotalChars;
        this.hugeTextPreviewOnly = hugeTextPreviewOnly;
        this.pendingLargeTextCachedDisplayPage = pendingLargeTextCachedDisplayPage;
        this.pendingLargeTextCachedTotalPages = pendingLargeTextCachedTotalPages;
        this.largeTextPartitionStartByte = Math.max(0L, largeTextPartitionStartByte);
        this.largeTextPartitionEndByte = Math.max(0L, largeTextPartitionEndByte);
        this.largeTextFileByteLength = Math.max(0L, largeTextFileByteLength);
        this.largeTextEstimatedBytesPerChar = Math.max(1f, largeTextEstimatedBytesPerChar);
        this.largeTextPartitionBodyStartCharCount = Math.max(0, largeTextPartitionBodyStartCharCount);
        this.largeTextPartitionBodyCharCount = Math.max(0, largeTextPartitionBodyCharCount);
        this.largeTextPartitionWindowStartLine = Math.max(1, largeTextPartitionWindowStartLine);
        this.largeTextPartitionStartLine = Math.max(1, largeTextPartitionStartLine);
        this.largeTextPartitionEndLine = Math.max(this.largeTextPartitionStartLine, largeTextPartitionEndLine);
        this.largeTextTotalLogicalLines = Math.max(1, largeTextTotalLogicalLines);
    }

    public boolean matches(@NonNull Intent intent,
                           @NonNull String pathExtraKey,
                           @NonNull String uriExtraKey) {
        String path = intent.getStringExtra(pathExtraKey);
        String uri = intent.getStringExtra(uriExtraKey);

        if (path != null && sourcePath != null) {
            return path.equals(sourcePath) || path.equals(filePath);
        }
        if (uri != null && sourceUri != null) {
            return uri.equals(sourceUri);
        }
        if (path != null && filePath != null) {
            return path.equals(filePath);
        }
        return false;
    }
}
