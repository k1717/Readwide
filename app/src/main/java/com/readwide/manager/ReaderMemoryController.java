package com.readwide.manager;

import com.readwide.manager.util.ReaderSaveAnchorMath;

import android.content.Intent;

import androidx.annotation.NonNull;

import com.readwide.manager.model.ReaderState;
import com.readwide.manager.util.ReaderRestoreTargetMath;

final class ReaderMemoryController {
    private final ReaderActivity activity;
    private static final String EXTRA_BACKGROUND_LAYOUT_SIGNATURE = "txt_background_layout_signature";
    private static final String UNAPPLIED_LAYOUT_SIGNATURE = "txt-layout-unapplied-v1";

    ReaderMemoryController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void cancelBackgroundMemoryTrim() {
        activity.handler.removeCallbacks(activity.backgroundMemoryTrimRunnable);
    }

    void scheduleBackgroundMemoryTrim() {
        if (!activity.textContentReadyForPersistence || activity.activityDestroyed || activity.filePath == null || activity.readerView == null || activity.backgroundTextMemoryReleased) return;
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
        // The source/policy may change after memory was released. The old
        // context is still useful, but its page hints no longer describe it.
        if (!activity.loadedTextMatchesCurrentSourceAndTransform()
                || !activity.hasCurrentReaderLayoutPreferences()
                || !activity.readerPageLayoutSignatureForPath(activity.filePath).equals(
                        restoreIntent.getStringExtra(EXTRA_BACKGROUND_LAYOUT_SIGNATURE))) {
            discardRestorePageHints(restoreIntent);
        }
        restoreIntent.removeExtra(EXTRA_BACKGROUND_LAYOUT_SIGNATURE);
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
        activity.cancelLargeTextBoundaryHandoffs();
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
        if (!activity.textContentReadyForPersistence || activity.activityDestroyed || activity.backgroundTextMemoryReleased || activity.filePath == null || activity.readerView == null) return;
        if (!force && !activity.isFinishing() && !activity.isChangingConfigurations() && activity.hasWindowFocus()) return;

        // Persist and restore one coherent target. A pending explicit jump can
        // point outside the old window; a viewport handoff still owns its top row.
        ReaderState state = withoutStaleLayoutHints(captureReadingState());
        if (state == null) return;
        boolean currentContent = activity.loadedTextMatchesCurrentSourceAndTransform();
        activity.backgroundTextRestoreIntent = backgroundRestoreIntent(activity.getIntent(), state);
        if (!currentContent) discardRestorePageHints(activity.backgroundTextRestoreIntent);
        if (currentContent && activity.prefs != null && activity.prefs.getAutoSavePosition()) {
            activity.bookmarkManager.saveReadingState(state);
        }
        activity.textContentReadyForPersistence = false;
        activity.clearLoadedTextSnapshot();

        // Cancel outstanding readers/indexers/searches before dropping their backing data.
        activity.cancelLargeTextBoundaryHandoffs();
        activity.clearTextSearchWork();
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
        activity.cancelPendingFileLayout();
        activity.cancelPendingSnapshotRestore();
        activity.cancelLargeTextBoundaryHandoffs();
        activity.textContentReadyForPersistence = false;
        activity.clearTextSearchWork();
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
        if (activity.prefs == null || !activity.prefs.getAutoSavePosition()) return;
        ReaderState state = captureReadingStateForPersistence();
        if (state != null) activity.bookmarkManager.saveReadingState(state);
    }

    ReaderState captureReadingStateForPersistence() {
        // Unlike recreation/reload targets, durable state must not label old
        // text with a changed file/decoding policy. Never start a reload here:
        // this method also runs from onDestroy after workers are cancelled.
        if (!activity.loadedTextMatchesCurrentSourceAndTransform()) return null;
        return withoutStaleLayoutHints(captureReadingState());
    }

    private ReaderState withoutStaleLayoutHints(ReaderState state) {
        if (state != null && !activity.hasCurrentReaderLayoutPreferences()) {
            // Character/context still belongs to the loaded text. Only the page
            // and pixel hints depend on rendering settings not yet on screen.
            state.setPageNumber(0);
            state.setTotalPages(0);
            state.setScrollY(0);
            state.setPresentationSignature(UNAPPLIED_LAYOUT_SIGNATURE);
        }
        return state;
    }

    private static void discardRestorePageHints(Intent restored) {
        restored.removeExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE);
        restored.removeExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES);
        restored.putExtra(ReaderActivity.EXTRA_JUMP_PREFER_ANCHOR_PARTITION, true);
    }

    static Intent backgroundRestoreIntent(Intent launch, ReaderState state) {
        Intent restored = new Intent(launch);
        restored.putExtra(ReaderActivity.EXTRA_FILE_PATH, state.getFilePath());
        // Keep the launch URI as ownership evidence for SAF/picker opens. The
        // loader prefers the resolved local path, but the still-current launch
        // may name only the URI; removing it makes our own restore look stale.
        // The saved character may be in lookbehind/lookahead or in a queued
        // destination. Never force it into the old canonical partition.
        restored.removeExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE);
        restored.removeExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE);
        restored.putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, state.getCharPosition());
        restored.putExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, state.getPageNumber());
        restored.putExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, state.getTotalPages());
        restored.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE, state.getAnchorTextBefore());
        restored.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER, state.getAnchorTextAfter());
        restored.putExtra(EXTRA_BACKGROUND_LAYOUT_SIGNATURE, state.getPresentationSignature());
        return restored;
    }

    /**
     * Instance-state capture may retain an owned destination before layout. This
     * must not weaken normal autosave's guard against mixed/failed-load content.
     */
    ReaderState captureReadingStateForRecreation() {
        ReaderState ready = captureReadingState();
        if (ready != null || activity.backgroundTextMemoryReleased) return ready;
        int destination = activity.largeTextPartitionSwitchState.destinationChar();
        if (!activity.largeTextEstimateActive || activity.filePath == null
                || !activity.largeTextPartitionSwitchState.isInProgress() || destination < 0) {
            return null;
        }
        // The applier has retained this absolute target while waiting for layout.
        // A queued explicit jump remains authoritative, even outside this window.
        ReaderState pending = new ReaderState(activity.filePath);
        pending.setCharPosition(destination);
        long local = (long) destination - activity.largeTextPreviewBaseCharOffset;
        boolean inWindow = activity.fileContent != null
                && local >= 0 && local < activity.fileContent.length();
        pending.setAnchorTextBefore(inWindow ? activity.getAnchorTextBefore(destination) : "");
        pending.setAnchorTextAfter(inWindow ? activity.getAnchorTextAfter(destination) : "");
        return pending;
    }

    ReaderState captureReadingState() {
        // A pending/failed load can retain the previous view and path after
        // clearing its text and partition base. Only save after this load's
        // position is restored, or those mixed coordinates overwrite the real
        // reading state. A completed empty file is still safe to save.
        if (!activity.textContentReadyForPersistence || activity.backgroundTextMemoryReleased
                || activity.readerView == null || !activity.readerView.hasCurrentTextLayout()) return null;
        if (activity.filePath != null) {
            ReaderState state = new ReaderState(activity.filePath);
            int savePosition = activity.getBookmarkSaveCharPosition();
            String anchorBefore = activity.getAnchorTextBefore(savePosition);
            String anchorAfter = activity.getAnchorTextAfter(savePosition);

            // Save the request's character, not its old estimated page number
            // interpreted against anchors that may have just become ready.
            // Automatic window replacement still saves the visible row.
            int destination = activity.largeTextPartitionSwitchState.destinationChar();
            if (ReaderSaveAnchorMath.shouldUsePendingTarget(activity.largeTextEstimateActive,
                    activity.largeTextPartitionSwitchState.isInProgress(),
                    activity.largeTextPartitionSwitchState.shouldPreserveVisiblePosition(), destination)) {
                savePosition = destination;
                long local = (long) destination - activity.largeTextPreviewBaseCharOffset;
                boolean inWindow = activity.fileContent != null
                        && local >= 0 && local < activity.fileContent.length();
                anchorBefore = inWindow ? activity.getAnchorTextBefore(destination) : "";
                anchorAfter = inWindow ? activity.getAnchorTextAfter(destination) : "";
            }

            state.setCharPosition(savePosition);
            state.setScrollY(activity.readerView != null ? activity.readerView.getReaderScrollY() : 0);
            state.setPageNumber(activity.getStatusCurrentPageNumber());
            state.setTotalPages(activity.getDisplayedTotalPageCount());
            state.setAnchorTextBefore(anchorBefore);
            state.setAnchorTextAfter(anchorAfter);
            // This coordinate belongs to the loaded bytes, not a newer file
            // that may now exist at the same path.
            if (activity.contentsSourceLength >= 0L) state.setFileLength(activity.contentsSourceLength);
            state.setPresentationSignature(activity.readerPageLayoutSignatureForPath(activity.filePath));
            return state;
        }
        return null;
    }
}
