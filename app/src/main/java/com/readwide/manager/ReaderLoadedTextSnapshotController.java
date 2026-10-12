package com.readwide.manager;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;

import com.readwide.manager.model.LargeTextLinePartitionResult;
import com.readwide.manager.model.LoadedTextSnapshot;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.TextDisplayRuleManager;
import com.readwide.manager.view.CustomReaderView;

import java.io.File;

final class ReaderLoadedTextSnapshotController {
    private static volatile LoadedTextSnapshot lastLoadedTextSnapshot;

    private final ReaderActivity activity;
    private CustomReaderView pendingRestoreView;
    private View.OnLayoutChangeListener pendingRestoreListener;
    private Runnable pendingRestoreCheck;

    ReaderLoadedTextSnapshotController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void cacheLoadedTextSnapshot() {
        if (activity.largeTextEstimateActive && activity.largeTextPartitionSwitchState.isInProgress()) {
            // A pending destination and the still-visible window need not be
            // the same session position. Recreate from the owned load target.
            clearLoadedTextSnapshot();
            return;
        }
        if (!activity.textContentReadyForPersistence
                || activity.readerView == null
                || !activity.readerView.hasCurrentTextLayout()
                || activity.fileContent == null
                || activity.fileContent.isEmpty()
                || activity.filePath == null) {
            return;
        }

        Intent intent = activity.getIntent();
        File sourceFile = new File(activity.filePath);
        // These stamps describe the loaded content, not whichever bytes happen
        // to be on disk when the activity is paused or destroyed.
        long sourceFileLength = activity.contentsSourceLength;
        long sourceLastModified = activity.contentsSourceModified;
        if (!sourceMatchesContent(sourceFile, sourceFileLength, sourceLastModified)
                || !activity.textContentTransformSignatureForPath(activity.filePath)
                        .equals(activity.appliedTextDisplayRuleSignature)) {
            clearLoadedTextSnapshot();
            return;
        }
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
                activity.appliedTextDisplayRuleSignature,
                activity.currentSearchOptions().signature(),
                activity.activeSearchOrdinal,
                sourceFileLength,
                sourceLastModified,
                activity.largeTextActivePartitionUsesLookbehind,
                activity.appliedLargeTextPartitionMode);
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
                || !snapshot.matches(intent, ReaderActivity.EXTRA_FILE_PATH, ReaderActivity.EXTRA_FILE_URI)
                || (snapshot.largeTextEstimateActive
                    && snapshot.appliedLargeTextPartitionMode != activity.getLargeTextPartitionMode())
                || !ReaderLifecycleController.snapshotMatchesSavedViewport(
                        intent, savedInstanceState, snapshot.charPosition)) {
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
        if (!sourceMatchesContent(snapshotFile, snapshot.sourceFileLength, snapshot.sourceLastModified)) return false;

        cancelPendingRestore();
        activity.textContentReadyForPersistence = false;
        activity.activityDestroyed = false;
        activity.hideLoadingWindow();
        // Restoring an ongoing session: keep whatever chrome state the reader
        // was in - never force the controls open over a resumed read.
        activity.chromeRevealedForPath = snapshot.filePath;

        activity.filePath = snapshot.filePath;
        activity.appliedTextDisplayRuleSignature = snapshot.textContentTransformSignature;
        activity.fileName = com.readwide.manager.util.FileUtils.displayNameOrBasename(
                snapshot.fileName, snapshot.filePath, activity.getString(R.string.app_name));
        activity.fileContent = snapshot.fileContent;
        activity.totalChars = snapshot.totalChars;
        activity.totalLines = snapshot.totalLines;
        activity.contentsSourceLength = snapshot.sourceFileLength;
        activity.contentsSourceModified = snapshot.sourceLastModified;
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
        // A window's body/buffer boundaries belong to the policy that read it.
        // A foreign policy falls back to the saved viewport's normal file load.
        if (snapshot.largeTextEstimateActive) {
            activity.appliedLargeTextPartitionMode = snapshot.appliedLargeTextPartitionMode;
        }
        // The signature check above proves this is the same transform as the
        // cached text. Restore it before indexing, search or neighbor reads;
        // a recreated activity otherwise retains the field's false default.
        activity.largeTextActiveCollapseBlankLines = activity.prefs.isCollapseBlankLinesEnabled();
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
        activity.readerView.setLargeTextPartitionMode(activity.largeTextEstimateActive);
        activity.readerView.setOverlapLines(activity.prefs.getPagingOverlapLines());
        String restoredName = activity.filePath != null && !activity.filePath.isEmpty()
                ? activity.filePath
                : activity.fileName;
        activity.readerView.setMarkdownHighlightingEnabled(FileUtils.isMarkdownFile(restoredName));

        activity.readerView.setTextContent(activity.fileContent,
                activity.loadedTextWindowReachesDocumentEnd());
        activity.refreshTxtAnnotationHighlights();
        activity.applySearchHighlight();
        // Preserve this position even if another recreation happens before layout.
        Intent target = new Intent(intent);
        target.putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, snapshot.charPosition);
        target.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE, activity.getAnchorTextBefore(snapshot.charPosition));
        target.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER, activity.getAnchorTextAfter(snapshot.charPosition));
        activity.rememberReaderLoadTarget(target);
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
        restorePositionAfterLayout(snapshot.charPosition);
        return true;
    }

    static boolean sourceMatchesContent(File source, long length, long modified) {
        return source != null && length >= 0L && modified >= 0L && source.isFile()
                && source.length() == length && source.lastModified() == modified;
    }

    /** Own one actual layout completion, not a post assumed to follow measurement. */
    void restorePositionAfterLayout(int position) {
        cancelPendingRestore();
        final CustomReaderView targetView = activity.readerView;
        if (targetView == null) return;
        final int loadGeneration = activity.loadGeneration.get();
        final int switchGeneration = activity.largeTextPartitionSwitchGeneration.get();
        final String expectedPath = activity.filePath;
        final String expectedContent = activity.fileContent;
        pendingRestoreView = targetView;
        pendingRestoreCheck = () -> {
            if (activity.activityDestroyed || loadGeneration != activity.loadGeneration.get()
                    || switchGeneration != activity.largeTextPartitionSwitchGeneration.get()
                    || targetView != activity.readerView || expectedContent != activity.fileContent
                    || targetView.getTextContent() != expectedContent
                    || !java.util.Objects.equals(expectedPath, activity.filePath)) {
                cancelPendingRestore();
            } else if (targetView.hasCurrentTextLayout()) {
                // Remove first: scrolling can synchronously call onScrollChanged.
                cancelPendingRestore();
                if (!activity.ensureLoadedTextCurrent()) return;
                activity.scrollToCharPosition(position);
                activity.textContentReadyForPersistence = true;
                activity.updatePositionLabel();
            }
        };
        pendingRestoreListener = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                finishPendingRestoreIfReady();
        targetView.addOnLayoutChangeListener(pendingRestoreListener);
        finishPendingRestoreIfReady();
    }

    void finishPendingRestoreIfReady() {
        if (pendingRestoreCheck != null) pendingRestoreCheck.run();
    }

    boolean cancelPendingRestore() {
        boolean pending = pendingRestoreCheck != null;
        if (pendingRestoreView != null && pendingRestoreListener != null) {
            pendingRestoreView.removeOnLayoutChangeListener(pendingRestoreListener);
        }
        pendingRestoreView = null;
        pendingRestoreListener = null;
        pendingRestoreCheck = null;
        return pending;
    }
}
