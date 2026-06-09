package com.textview.reader;

import android.content.Intent;

import androidx.annotation.NonNull;

import com.textview.reader.model.ReaderState;

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
        activity.backgroundTextMemoryReleased = false;
        activity.backgroundTextRestoreIntent = null;
        activity.clearLoadedTextSnapshot();
        activity.setIntent(restoreIntent);
        activity.loadFileFromIntent(restoreIntent);
        return true;
    }

    void trimReaderMemoryForBackground(boolean force) {
        if (activity.activityDestroyed || activity.backgroundTextMemoryReleased || activity.filePath == null || activity.readerView == null) return;
        if (!force && !activity.isFinishing() && !activity.isChangingConfigurations() && activity.hasWindowFocus()) return;

        int currentPosition = Math.max(0, activity.getCurrentCharPosition());
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
        if (activity.filePath != null && activity.prefs.getAutoSavePosition()) {
            ReaderState state = new ReaderState(activity.filePath);
            state.setCharPosition(activity.getCurrentCharPosition());
            state.setScrollY(activity.readerView != null ? activity.readerView.getReaderScrollY() : 0);
            state.setPageNumber(activity.getDisplayedCurrentPageNumber());
            state.setTotalPages(activity.getDisplayedTotalPageCount());
            if (activity.filePath != null) {
                File f = new File(activity.filePath);
                if (f.exists()) state.setFileLength(f.length());
            }
            activity.bookmarkManager.saveReadingState(state);
        }
    }
}
