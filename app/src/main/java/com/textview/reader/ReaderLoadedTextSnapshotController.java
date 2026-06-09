package com.textview.reader;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.textview.reader.model.LoadedTextSnapshot;
import com.textview.reader.util.TextDisplayRuleManager;

final class ReaderLoadedTextSnapshotController {
    private static volatile LoadedTextSnapshot lastLoadedTextSnapshot;

    private final ReaderActivity activity;

    ReaderLoadedTextSnapshotController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void cacheLoadedTextSnapshot() {
        if (activity.readerView == null
                || activity.fileContent == null
                || activity.fileContent.isEmpty()
                || activity.filePath == null) {
            return;
        }

        Intent intent = activity.getIntent();
        lastLoadedTextSnapshot = new LoadedTextSnapshot(
                intent != null ? intent.getStringExtra(ReaderActivity.EXTRA_FILE_PATH) : null,
                intent != null ? intent.getStringExtra(ReaderActivity.EXTRA_FILE_URI) : null,
                activity.filePath,
                activity.fileName,
                activity.fileContent,
                activity.totalChars,
                activity.totalLines,
                activity.getCurrentCharPosition(),
                activity.activeSearchQuery,
                activity.activeSearchIndex,
                activity.largeTextEstimateActive,
                activity.largeTextEstimatedTotalPages,
                activity.pendingLargeTextRestorePosition,
                activity.largeTextPreviewBaseCharOffset,
                activity.largeTextEstimatedBasePageOffset,
                activity.largeTextEstimatedTotalChars,
                activity.hugeTextPreviewOnly,
                activity.pendingLargeTextCachedDisplayPage,
                activity.pendingLargeTextCachedTotalPages,
                activity.largeTextPartitionStartByte,
                activity.largeTextPartitionEndByte,
                activity.largeTextFileByteLength,
                activity.largeTextEstimatedBytesPerChar,
                activity.largeTextPartitionBodyStartCharCount,
                activity.largeTextPartitionBodyCharCount,
                activity.largeTextPartitionWindowStartLine,
                activity.largeTextPartitionStartLine,
                activity.largeTextPartitionEndLine,
                activity.largeTextTotalLogicalLines);
    }

    void clearLoadedTextSnapshot() {
        LoadedTextSnapshot snapshot = lastLoadedTextSnapshot;
        if (snapshot == null) return;
        if (activity.filePath == null || activity.filePath.equals(snapshot.filePath)) {
            lastLoadedTextSnapshot = null;
        }
    }

    boolean restoreLoadedTextSnapshotIfAvailable(@NonNull Intent intent, Bundle savedInstanceState) {
        if (savedInstanceState == null
                || !savedInstanceState.getBoolean(ReaderActivity.STATE_RESTORE_FROM_MEMORY, false)) {
            return false;
        }

        LoadedTextSnapshot snapshot = lastLoadedTextSnapshot;
        if (snapshot == null
                || !snapshot.matches(intent, ReaderActivity.EXTRA_FILE_PATH, ReaderActivity.EXTRA_FILE_URI)) {
            return false;
        }

        activity.activityDestroyed = false;
        activity.hideLoadingWindow();

        activity.filePath = snapshot.filePath;
        activity.appliedTextDisplayRuleSignature = TextDisplayRuleManager.getSignature(
                activity.getApplicationContext(), activity.filePath);
        activity.fileName = snapshot.fileName != null
                ? snapshot.fileName
                : activity.getString(R.string.app_name);
        activity.fileContent = snapshot.fileContent;
        activity.totalChars = snapshot.totalChars;
        activity.totalLines = snapshot.totalLines;
        activity.activeSearchQuery = snapshot.activeSearchQuery;
        activity.activeSearchIndex = snapshot.activeSearchIndex;
        activity.largeTextEstimateActive = snapshot.largeTextEstimateActive;
        activity.largeTextEstimatedTotalPages = snapshot.largeTextEstimatedTotalPages;
        activity.pendingLargeTextRestorePosition = snapshot.pendingLargeTextRestorePosition;
        activity.largeTextPreviewBaseCharOffset = snapshot.largeTextPreviewBaseCharOffset;
        activity.largeTextEstimatedBasePageOffset = snapshot.largeTextEstimatedBasePageOffset;
        activity.largeTextEstimatedTotalChars = snapshot.largeTextEstimatedTotalChars;
        activity.hugeTextPreviewOnly = snapshot.hugeTextPreviewOnly;
        activity.pendingLargeTextCachedDisplayPage = snapshot.pendingLargeTextCachedDisplayPage;
        activity.pendingLargeTextCachedTotalPages = snapshot.pendingLargeTextCachedTotalPages;
        activity.largeTextPartitionStartByte = snapshot.largeTextPartitionStartByte;
        activity.largeTextPartitionEndByte = snapshot.largeTextPartitionEndByte;
        activity.largeTextFileByteLength = snapshot.largeTextFileByteLength;
        activity.largeTextEstimatedBytesPerChar = snapshot.largeTextEstimatedBytesPerChar;
        activity.largeTextPartitionBodyStartCharCount = snapshot.largeTextPartitionBodyStartCharCount;
        activity.largeTextPartitionBodyCharCount = snapshot.largeTextPartitionBodyCharCount;
        activity.largeTextPartitionWindowStartLine = snapshot.largeTextPartitionWindowStartLine;
        activity.largeTextPartitionStartLine = snapshot.largeTextPartitionStartLine;
        activity.largeTextPartitionEndLine = snapshot.largeTextPartitionEndLine;
        activity.largeTextTotalLogicalLines = snapshot.largeTextTotalLogicalLines;

        activity.updateReaderFileTitle();
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle(activity.fileName);
        }

        activity.readerView.setTextContent(activity.fileContent);
        activity.applySearchHighlight();
        activity.readerView.post(() -> {
            if (activity.activityDestroyed) return;
            activity.scrollToCharPosition(snapshot.charPosition);
            activity.updatePositionLabel();
        });
        return true;
    }
}
