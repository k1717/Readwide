package com.readwide.manager;

import android.content.Intent;

import androidx.annotation.NonNull;

import com.readwide.manager.model.ReaderState;
import com.readwide.manager.util.ReaderRestoreTargetMath;
import com.readwide.manager.util.ReaderSaveAnchorMath;
import com.readwide.manager.view.CustomReaderView;

import java.io.File;

final class ReaderMemoryController {
    private final ReaderActivity activity;

    ReaderMemoryController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void cancelBackgroundMemoryTrim() {
        activity.handler.removeCallbacks(activity.backgroundMemoryTrimRunnable);
    }

    void scheduleBackgroundMemoryTrim() {
        if (activity.activityDestroyed || activity.filePath == null || activity.readerView == null || activity.backgroundTextMemoryReleased) return;
        activity.handler.removeCallbacks(activity.backgroundMemoryTrimRunnable);
        activity.handler.postDelayed(activity.backgroundMemoryTrimRunnable, ReaderActivity.BACKGROUND_MEMORY_TRIM_DELAY_MS);
    }

    boolean restoreReaderAfterBackgroundMemoryTrimIfNeeded() {
        cancelBackgroundMemoryTrim();
        if (!activity.backgroundTextMemoryReleased) return false;

        Intent restoreIntent = activity.backgroundTextRestoreIntent != null
                ? new Intent(activity.backgroundTextRestoreIntent)
                : new Intent(activity.getIntent());
        if (!restoreTargetMatchesCurrentReader(restoreIntent)) {
            // The stored restore intent belongs to a file this activity no
            // longer shows (e.g. onNewIntent switched files after a trim).
            // Executing it would reopen the previous file over the new one,
            // so drop the stale state instead of restoring.
            discardTransientRestoreStateForNewLoad();
            return false;
        }
        activity.backgroundTextMemoryReleased = false;
        activity.backgroundTextRestoreIntent = null;
        activity.clearLoadedTextSnapshot();
        activity.setIntent(restoreIntent);
        activity.loadFileFromIntent(restoreIntent);
        return true;
    }

    /**
     * Discards every piece of transient background-restore state before a new
     * file is loaded into this activity instance: the pending trim runnable,
     * the released-memory flag, the stored restore intent, the loaded text
     * snapshot, and any in-flight large-TXT partition switch. Called from
     * onNewIntent (after the previous file's state is saved) and when a stale
     * restore target is detected, so a restore intent recorded for file A can
     * never run after file B was loaded.
     */
    void discardTransientRestoreStateForNewLoad() {
        cancelBackgroundMemoryTrim();
        activity.backgroundTextMemoryReleased = false;
        activity.backgroundTextRestoreIntent = null;
        activity.clearLoadedTextSnapshot();
        activity.clearLargeTextPartitionSwitchPending();
        activity.clearLargeTextQueuedPageDelta();
    }

    private boolean restoreTargetMatchesCurrentReader(@NonNull Intent restoreIntent) {
        Intent current = activity.getIntent();
        return ReaderRestoreTargetMath.matchesRestoreTarget(
                restoreIntent.getStringExtra(ReaderActivity.EXTRA_FILE_PATH),
                restoreIntent.getStringExtra(ReaderActivity.EXTRA_FILE_URI),
                current != null ? current.getStringExtra(ReaderActivity.EXTRA_FILE_PATH) : null,
                current != null ? current.getStringExtra(ReaderActivity.EXTRA_FILE_URI) : null,
                activity.filePath);
    }

    void trimReaderMemoryForBackground(boolean force) {
        if (activity.activityDestroyed || activity.backgroundTextMemoryReleased || activity.filePath == null || activity.readerView == null) return;
        if (!force && !activity.isFinishing() && !activity.isChangingConfigurations() && activity.hasWindowFocus()) return;

        int currentPosition = Math.max(0, activity.getBookmarkSaveCharPosition());
        int currentDisplayPage = Math.max(1, activity.getDisplayedCurrentPageNumber());
        int currentTotalPages = Math.max(1, activity.getDisplayedTotalPageCount());
        String anchorBefore = activity.getAnchorTextBefore(currentPosition);
        String anchorAfter = activity.getAnchorTextAfter(currentPosition);

        Intent restoreIntent = new Intent(activity.getIntent());
        restoreIntent.putExtra(ReaderActivity.EXTRA_FILE_PATH, activity.filePath);
        restoreIntent.removeExtra(ReaderActivity.EXTRA_FILE_URI);
        restoreIntent.putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, currentPosition);
        restoreIntent.putExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, currentDisplayPage);
        restoreIntent.putExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, currentTotalPages);
        if (activity.largeTextEstimateActive) {
            restoreIntent.putExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE, activity.largeTextPartitionStartByte);
            restoreIntent.putExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE, activity.largeTextPartitionStartLine);
        }
        if (!anchorBefore.isEmpty()) restoreIntent.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE, anchorBefore);
        if (!anchorAfter.isEmpty()) restoreIntent.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER, anchorAfter);
        activity.backgroundTextRestoreIntent = restoreIntent;

        saveReadingState();
        activity.clearLoadedTextSnapshot();

        // Cancel outstanding readers/indexers/searches before dropping their backing data.
        activity.loadGeneration.incrementAndGet();
        activity.largeTextPartitionSwitchGeneration.incrementAndGet();
        activity.invalidateLargeTextExactPageIndexBuild();
        activity.largeTextSearchGeneration.incrementAndGet();
        activity.largeTextSearchCountGeneration.incrementAndGet();
        // Snapshot the blank-line collapse policy for this load so background partition
        // reads use one consistent value instead of re-reading the live preference.
        activity.largeTextActiveCollapseBlankLines =
                activity.prefs != null && activity.prefs.isCollapseBlankLinesEnabled();
        activity.handler.removeCallbacks(activity.largeTextRestartIndexingRunnable);
        activity.handler.removeCallbacks(activity.largeTextManualScrollBoundaryHandoffRunnable);
        activity.clearPendingToolbarSeekJump();
        activity.clearLargeTextPartitionSwitchPending();
        activity.clearLargeTextQueuedPageDelta();
        activity.resetLargeTextPageDirectionTracking();
        activity.clearLargeTextPartitionCache();
        activity.resetLargeTextExactPageIndex();
        activity.clearLargeTextSearchTotalCache();

        activity.activeSearchQuery = "";
        activity.activeSearchIndex = -1;
        activity.activeSearchOrdinal = 0;
        activity.fileContent = "";
        activity.totalChars = 0;
        activity.totalLines = 0;
        activity.largeTextEstimateActive = false;
        activity.largeTextEstimatedTotalPages = 0;
        activity.pendingLargeTextRestorePosition = -1;
        activity.pendingLargeTextCachedDisplayPage = 0;
        activity.pendingLargeTextCachedTotalPages = 0;
        activity.hugeTextPreviewOnly = false;
        activity.largeTextPreviewBaseCharOffset = 0;
        activity.largeTextEstimatedBasePageOffset = 0;
        activity.largeTextEstimatedTotalChars = 0;
        activity.largeTextPartitionStartByte = 0L;
        activity.largeTextPartitionEndByte = 0L;
        activity.largeTextFileByteLength = 0L;
        activity.largeTextEstimatedBytesPerChar = 1f;
        activity.largeTextPartitionBodyStartCharCount = 0;
        activity.largeTextPartitionBodyCharCount = 0;
        activity.largeTextPartitionWindowStartLine = 1;
        activity.largeTextPartitionStartLine = 1;
        activity.largeTextPartitionEndLine = 1;
        activity.largeTextTotalLogicalLines = 1;

        activity.readerView.setLargeTextPartitionMode(false);
        activity.readerView.setTextContent("");
        activity.applySearchHighlight();
        activity.updatePositionLabel();
        activity.backgroundTextMemoryReleased = true;
    }

    void releaseReaderMemory() {
        activity.activeSearchQuery = "";
        activity.activeSearchIndex = -1;
        activity.activeSearchOrdinal = 0;
        activity.largeTextSearchCountGeneration.incrementAndGet();
        activity.fileContent = "";
        activity.totalChars = 0;
        activity.totalLines = 0;
        activity.largeTextEstimateActive = false;
        activity.largeTextEstimatedTotalPages = 0;
        activity.clearLargeTextPartitionSwitchPending();
        activity.clearLargeTextQueuedPageDelta();
        activity.resetLargeTextPageDirectionTracking();
        activity.hugeTextPreviewOnly = false;
        activity.pendingLargeTextRestorePosition = -1;
        activity.pendingLargeTextCachedDisplayPage = 0;
        activity.pendingLargeTextCachedTotalPages = 0;
        activity.largeTextPreviewBaseCharOffset = 0;
        activity.largeTextEstimatedBasePageOffset = 0;
        activity.largeTextEstimatedTotalChars = 0;
        activity.largeTextPartitionStartByte = 0L;
        activity.largeTextPartitionEndByte = 0L;
        activity.largeTextFileByteLength = 0L;
        activity.largeTextEstimatedBytesPerChar = 1f;
        activity.largeTextPartitionBodyStartCharCount = 0;
        activity.largeTextPartitionBodyCharCount = 0;
        activity.largeTextPartitionWindowStartLine = 1;
        activity.largeTextPartitionStartLine = 1;
        activity.largeTextPartitionEndLine = 1;
        activity.largeTextTotalLogicalLines = 1;
        activity.loadingWindowPartitionJumpGeneration = -1;
        activity.clearLargeTextPartitionCache();
        activity.resetLargeTextExactPageIndex();

        if (activity.readerView != null) {
            activity.readerView.setLargeTextPartitionMode(false);
            activity.readerView.releaseTextResources();
        }
    }

    void saveReadingState() {
        // Do not persist position while the reader content is released for a
        // background memory trim. In that window fileContent is empty, so the
        // derived char position would collapse to 0 and overwrite the real
        // saved position. The correct position was already saved by the trim
        // itself just before it cleared the content, and the in-memory restore
        // intent recovers it on the next return to the reader.
        if (activity.backgroundTextMemoryReleased) return;
        if (activity.filePath != null && activity.prefs.getAutoSavePosition()) {
            ReaderState state = new ReaderState(activity.filePath);
            int savePosition = activity.getBookmarkSaveCharPosition();
            String anchorBefore = activity.getAnchorTextBefore(savePosition);
            String anchorAfter = activity.getAnchorTextAfter(savePosition);

            // During an in-flight large-TXT partition switch, getDisplayedCurrentPageNumber()
            // already returns the *pending* page (LargeTextPageModelMath.displayedCurrentPage
            // prefers pendingDisplayPage). If we saved the raw readerView char position and
            // anchors here, the persisted pageNumber (pending) and charPosition/anchors
            // (current view) would disagree, so a pause mid-switch could restore to the
            // wrong place. When the exact page index is ready, override all three from the
            // pending page's exact anchor so they stay consistent with the saved pageNumber.
            // The anchor carries its own before/after text built from the correct partition,
            // which is why we take them from the anchor rather than re-deriving from
            // fileContent (the pending page may live in a partition not currently loaded).
            if (activity.largeTextEstimateActive
                    && activity.largeTextPartitionSwitchState.isInProgress()
                    && activity.isLargeTextExactPageIndexReady()) {
                int pendingPage = activity.largeTextPartitionSwitchState.pendingDisplayPage();
                CustomReaderView.PageTextAnchor anchor = pendingPage > 0
                        ? activity.getExactLargeTextAnchorForPage(pendingPage) : null;
                if (ReaderSaveAnchorMath.shouldUsePendingPageAnchor(
                        activity.largeTextEstimateActive,
                        activity.largeTextPartitionSwitchState.isInProgress(),
                        activity.isLargeTextExactPageIndexReady(),
                        pendingPage,
                        anchor != null)) {
                    savePosition = anchor.charPosition;
                    anchorBefore = anchor.anchorTextBefore;
                    anchorAfter = anchor.anchorTextAfter;
                }
            }

            state.setCharPosition(savePosition);
            state.setScrollY(activity.readerView != null ? activity.readerView.getReaderScrollY() : 0);
            state.setPageNumber(activity.getDisplayedCurrentPageNumber());
            state.setTotalPages(activity.getDisplayedTotalPageCount());
            state.setAnchorTextBefore(anchorBefore);
            state.setAnchorTextAfter(anchorAfter);
            if (activity.filePath != null) {
                File f = new File(activity.filePath);
                if (f.exists()) state.setFileLength(f.length());
            }
            state.setPresentationSignature(activity.readerPageLayoutSignatureForPath(activity.filePath));
            activity.bookmarkManager.saveReadingState(state);
        }
    }
}
