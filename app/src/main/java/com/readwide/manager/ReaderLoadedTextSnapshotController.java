package com.readwide.manager;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.readwide.manager.model.LargeTextLinePartitionResult;
import com.readwide.manager.model.LoadedTextSnapshot;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.TextDisplayRuleManager;

import java.io.File;

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
        File sourceFile = new File(activity.filePath);
        long sourceFileLength = sourceFile.isFile() ? sourceFile.length() : -1L;
        long sourceLastModified = sourceFile.isFile() ? sourceFile.lastModified() : -1L;
        lastLoadedTextSnapshot = new LoadedTextSnapshot(
                intent != null ? intent.getStringExtra(ReaderActivity.EXTRA_FILE_PATH) : null,
                intent != null ? intent.getStringExtra(ReaderActivity.EXTRA_FILE_URI) : null,
                activity.filePath,
                activity.fileName,
                activity.fileContent,
                activity.totalChars,
                activity.totalLines,
                activity.getBookmarkSaveCharPosition(),
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
                activity.largeTextTotalLogicalLines,
                activity.textContentTransformSignatureForPath(activity.filePath),
                activity.currentSearchOptions().signature(),
                activity.activeSearchOrdinal,
                sourceFileLength,
                sourceLastModified,
                activity.largeTextActivePartitionUsesLookbehind);
    }

    void clearLoadedTextSnapshot() {
        LoadedTextSnapshot snapshot = lastLoadedTextSnapshot;
        if (snapshot == null) return;
        if (activity.filePath == null || activity.filePath.equals(snapshot.filePath)) {
            lastLoadedTextSnapshot = null;
        }
    }

    void clearAnyLoadedTextSnapshot() {
        lastLoadedTextSnapshot = null;
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
        // Only reuse the cached content if it was produced under the same content-transform
        // (display rules + blank-line collapse). Otherwise the cached fileContent is stale, so
        // fall through to a fresh load that rebuilds it under the current settings.
        String currentContentSignature =
                activity.textContentTransformSignatureForPath(snapshot.filePath);
        if (!currentContentSignature.equals(snapshot.textContentTransformSignature)) {
            return false;
        }
        // Also drop the cached content if the underlying file changed on disk (size or mtime)
        // while the activity was gone, so an external edit is not masked by the static snapshot.
        File snapshotFile = snapshot.filePath != null ? new File(snapshot.filePath) : null;
        if (snapshotFile != null && snapshotFile.isFile()) {
            if (snapshot.sourceFileLength >= 0 && snapshotFile.length() != snapshot.sourceFileLength) {
                return false;
            }
            if (snapshot.sourceLastModified > 0 && snapshotFile.lastModified() != snapshot.sourceLastModified) {
                return false;
            }
        }

        activity.activityDestroyed = false;
        activity.hideLoadingWindow();

        activity.filePath = snapshot.filePath;
        activity.appliedTextDisplayRuleSignature =
                activity.textContentTransformSignatureForPath(activity.filePath);
        activity.fileName = snapshot.fileName != null
                ? snapshot.fileName
                : activity.getString(R.string.app_name);
        activity.fileContent = snapshot.fileContent;
        activity.totalChars = snapshot.totalChars;
        activity.totalLines = snapshot.totalLines;
        if (activity.currentSearchOptions().signature().equals(snapshot.searchOptionsSignature)) {
            activity.activeSearchQuery = snapshot.activeSearchQuery;
            activity.activeSearchIndex = snapshot.activeSearchIndex;
            activity.activeSearchOrdinal = snapshot.activeSearchOrdinal;
        } else {
            activity.activeSearchQuery = "";
            activity.activeSearchIndex = -1;
            activity.activeSearchOrdinal = 0;
        }
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
        activity.largeTextActivePartitionUsesLookbehind = snapshot.largeTextActivePartitionUsesLookbehind;
        activity.largeTextPartitionBodyCharCount = snapshot.largeTextPartitionBodyCharCount;
        activity.largeTextPartitionWindowStartLine = snapshot.largeTextPartitionWindowStartLine;
        activity.largeTextPartitionStartLine = snapshot.largeTextPartitionStartLine;
        activity.largeTextPartitionEndLine = snapshot.largeTextPartitionEndLine;
        activity.largeTextTotalLogicalLines = snapshot.largeTextTotalLogicalLines;

        activity.updateReaderFileTitle();
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle(activity.fileName);
        }

        // The normal load path configures the reader view by file type; this restore bypasses
        // it, so reapply paging overlap and markdown highlighting before setting the content.
        activity.readerView.setOverlapLines(activity.prefs.getPagingOverlapLines());
        String restoredName = activity.filePath != null && !activity.filePath.isEmpty()
                ? activity.filePath
                : activity.fileName;
        activity.readerView.setMarkdownHighlightingEnabled(FileUtils.isMarkdownFile(restoredName));

        activity.readerView.setTextContent(activity.fileContent);
        activity.applySearchHighlight();
        activity.readerView.post(() -> {
            if (activity.activityDestroyed) return;
            activity.scrollToCharPosition(snapshot.charPosition);
            activity.updatePositionLabel();
        });
        // A normal open registers the active partition, schedules exact-page indexing, and
        // prefetches neighbors during load; this restore bypasses that path, so mirror the
        // initial load-apply mapping here, or large-file page labels stay on the estimate and
        // navigating right after a restore misses the partition cache.
        if (activity.largeTextEstimateActive) {
            activity.cacheLargeTextPartition(new LargeTextLinePartitionResult(
                    activity.fileContent,
                    activity.totalLines,
                    activity.largeTextPartitionStartLine,
                    activity.largeTextPartitionEndLine,
                    activity.largeTextTotalLogicalLines,
                    activity.largeTextPreviewBaseCharOffset,
                    activity.largeTextPartitionBodyStartCharCount,
                    activity.largeTextPartitionBodyCharCount,
                    activity.largeTextPartitionWindowStartLine,
                    activity.largeTextActivePartitionUsesLookbehind,
                    activity.largeTextEstimatedTotalChars));
            activity.scheduleLargeTextExactPageIndexingRestart();
            activity.prefetchNeighborLargeTextPartitions();
        }
        return true;
    }
}
