package com.readwide.manager;

import androidx.annotation.NonNull;

import com.readwide.manager.model.LargeTextLinePartitionResult;
import com.readwide.manager.util.LargeTextContinuityMath;
import com.readwide.manager.view.CustomReaderView;

import java.io.File;

/**
 * Executes large-TXT partition switches.
 *
 * ReaderActivity still owns the actual view mutation because it has the scroll,
 * search-highlight, and label side effects. This navigator owns the switch
 * request lifecycle: normalize target, choose cache, run async read, reject
 * stale generations, and route success/failure back to the activity.
 */
final class ReaderLargeTextPartitionNavigator {
    private final ReaderActivity activity;

    ReaderLargeTextPartitionNavigator(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    /** Immutable ownership and screen geometry for an automatic window replacement. */
    static final class ViewportHandoff {
        final String content;
        final int windowBase;
        final String layoutSignature;
        final CustomReaderView.ScrollViewportAnchor anchor;

        ViewportHandoff(ReaderActivity activity, CustomReaderView.ScrollViewportAnchor anchor) {
            this.content = activity.fileContent;
            this.windowBase = activity.largeTextPreviewBaseCharOffset;
            this.layoutSignature = activity.buildCurrentLargeTextExactPageIndexSignature(activity.filePath);
            this.anchor = anchor;
        }

        boolean stillOwnsViewport(ReaderActivity activity) {
            return activity.readerView != null && !activity.readerView.isUserDraggingOrFlinging()
                    && !activity.readerView.isTextSelectionActive()
                    && content == activity.fileContent
                    && windowBase == activity.largeTextPreviewBaseCharOffset
                    && layoutSignature.equals(activity.buildCurrentLargeTextExactPageIndexSignature(activity.filePath))
                    && matchesPosition(activity.readerView.captureScrollViewportAnchor());
        }

        private boolean matchesPosition(CustomReaderView.ScrollViewportAnchor current) {
            return current != null && current.charPosition == anchor.charPosition
                    && current.baselineOffsetPx == anchor.baselineOffsetPx;
        }

        boolean isCoveredBy(LargeTextLinePartitionResult partition) {
            long local = (long) windowBase + anchor.charPosition - partition.baseCharOffset;
            if (partition.content == null || local < 0 || local >= partition.content.length()) return false;
            int count = Math.min(128, Math.min(content.length() - anchor.charPosition,
                    partition.content.length() - (int) local));
            return count > 0 && content.regionMatches(anchor.charPosition, partition.content, (int) local, count);
        }
    }

    void handoffViewport(int displayPage, int totalPages, int partitionStartLine, boolean useLookbehind) {
        if (activity.filePath == null || activity.readerView == null
                || activity.readerView.isUserDraggingOrFlinging()
                || activity.readerView.isTextSelectionActive()) return;
        CustomReaderView.ScrollViewportAnchor anchor = activity.readerView.captureScrollViewportAnchor();
        if (anchor == null) return;
        ViewportHandoff viewport = new ViewportHandoff(activity, anchor);
        switchInPlace(viewport.windowBase + anchor.charPosition, displayPage, totalPages,
                null, null, partitionStartLine, false, useLookbehind, false, viewport);
    }

    void switchInPlace(int charPosition,
                       int displayPage,
                       int totalPages,
                       String anchorBefore,
                       String anchorAfter,
                       int partitionStartLine,
                       boolean showLoadingForAsyncPartitionJump,
                       boolean useManualLookbehind,
                       boolean preferAnchorPartition) {
        switchInPlace(charPosition, displayPage, totalPages, anchorBefore, anchorAfter,
                partitionStartLine, showLoadingForAsyncPartitionJump, useManualLookbehind,
                preferAnchorPartition, null);
    }

    private void switchInPlace(int charPosition, int displayPage, int totalPages,
                               String anchorBefore, String anchorAfter, int partitionStartLine,
                               boolean showLoadingForAsyncPartitionJump, boolean useManualLookbehind,
                               boolean preferAnchorPartition, ViewportHandoff viewport) {
        switchInPlace(charPosition, displayPage, totalPages, anchorBefore, anchorAfter,
                partitionStartLine, showLoadingForAsyncPartitionJump, useManualLookbehind,
                preferAnchorPartition, viewport, 0);
    }

    void switchToDisplayLine(int line) {
        switchInPlace(0, 0, 0, null, null, -1, true, false, false, null, Math.max(1, line));
    }

    private void switchInPlace(int charPosition, int displayPage, int totalPages,
                               String anchorBefore, String anchorAfter, int partitionStartLine,
                               boolean showLoadingForAsyncPartitionJump, boolean useManualLookbehind,
                               boolean preferAnchorPartition, ViewportHandoff viewport, int displayLine) {
        if (activity.filePath == null || !activity.largeTextEstimateActive) return;
        if (!activity.ensureLoadedTextCurrent()) return;

        final int targetChar = Math.max(0, charPosition);
        final int targetStartLine = partitionStartLine > 0
                ? activity.getLargeTextPartitionStartLineForLine(partitionStartLine)
                : -1;
        // When the saved character offset may belong to a previous coordinate space
        // (collapse or display-rule change), select the partition by re-locating the
        // anchor text instead of trusting the raw offset. Only relevant for char-based
        // selection; an explicit start line already identifies the partition.
        final boolean anchorSelect = preferAnchorPartition
                && targetStartLine <= 0
                && ((anchorBefore != null && !anchorBefore.isEmpty())
                    || (anchorAfter != null && !anchorAfter.isEmpty()));
        activity.hideLoadingWindowForPartitionJumpIfCurrent(
                true, activity.largeTextPartitionSwitchGeneration.get());
        final int switchGeneration = activity.largeTextPartitionSwitchGeneration.incrementAndGet();
        activity.cancelLargeTextBoundaryHandoffs();
        activity.clearLargeTextPartitionSwitchPending();
        if (activity.readerView != null) activity.readerView.stopScrollMotion();
        activity.beginLargeTextPartitionSwitchPending(displayPage, totalPages);
        // A line request has no known absolute coordinate until the worker
        // resolves it. Keep autosave on the old visible row during that scan.
        if (displayLine <= 0) activity.largeTextPartitionSwitchState.setPendingTargetChar(targetChar);
        if (viewport != null) activity.largeTextPartitionSwitchState.markViewportHandoff();

        LargeTextLinePartitionResult cached = anchorSelect || displayLine > 0
                ? null
                : getCachedPartition(
                        targetChar,
                        targetStartLine,
                        useManualLookbehind);
        if (cached != null) {
            activity.applyLargeTextPartitionInPlace(
                    cached, targetChar, displayPage, totalPages, anchorBefore, anchorAfter,
                    false, switchGeneration, viewport);
            return;
        }

        final int generation = activity.loadGeneration.get();
        final long cacheGeneration = activity.largeTextPartitionCache.generation();
        final String expectedPath = activity.filePath;
        final int knownTotalLines = Math.max(1, activity.largeTextTotalLogicalLines);
        final int knownTotalChars = Math.max(0, activity.largeTextEstimatedTotalChars);
        final String loadedContent = activity.fileContent;
        final int loadedBase = activity.largeTextPreviewBaseCharOffset;
        final int loadedStartLine = activity.largeTextPartitionWindowStartLine;
        final int partitionLines = activity.getLargeTextPartitionLines();
        final int loadedWindowLines = (int) Math.min((long) knownTotalLines,
                (long) activity.largeTextPartitionEndLine + activity.getLargeTextPartitionLookaheadLines())
                - loadedStartLine + 1;

        if (showLoadingForAsyncPartitionJump) {
            activity.showLoadingWindowForPartitionJump(switchGeneration);
        }

        activity.largeTextPartitionExecutor.executeForeground(() -> {
            try {
                java.util.function.BooleanSupplier keepReading = () ->
                        !isStale(generation, switchGeneration, expectedPath)
                                && activity.largeTextPartitionCache.isCurrent(cacheGeneration);
                File source = new File(expectedPath);
                int selectChar = targetChar;
                if (displayLine > 0) {
                    selectChar = activity.resolveLargeTextCharPositionForLine(source, displayLine, keepReading);
                } else if (anchorSelect) {
                    int anchoredChar = com.readwide.manager.util.LargeTextPartitionReader.readWhile(
                            keepReading, () -> activity.resolveLargeTextCharPositionForAnchor(
                                    source, targetChar, anchorBefore, anchorAfter));
                    if (anchoredChar >= 0) selectChar = anchoredChar;
                }
                final int appliedChar = selectChar;
                LargeTextLinePartitionResult partition =
                        com.readwide.manager.util.LargeTextPartitionReader.readWhile(keepReading,
                                () -> {
                                    // A line scan now supplies an absolute coordinate.
                                    // Recheck the thread-safe cache before doing another
                                    // file scan; the UI commit still validates its epoch.
                                    if (displayLine > 0) {
                                        LargeTextLinePartitionResult owner = activity.largeTextPartitionCache
                                                .getNormalOwnerForChar(appliedChar);
                                        if (owner != null) return owner;
                                    }
                                    // Only on a cache miss, and on the worker: adjacent
                                    // targets may already be in the immutable loaded text.
                                    // Unknown/newline-expanded windows keep the full scan.
                                    int readStartLine = targetStartLine;
                                    if (readStartLine <= 0 && !anchorSelect) {
                                        readStartLine = LargeTextContinuityMath.partitionStartLineForLoadedChar(
                                                loadedContent, loadedBase, loadedStartLine, appliedChar,
                                                partitionLines, loadedWindowLines);
                                    }
                                    return readStartLine > 0
                                            ? activity.readLargeTextLinePartitionAtStartLine(
                                                    source, readStartLine, knownTotalLines, knownTotalChars, useManualLookbehind)
                                            : activity.readLargeTextLinePartitionForChar(source, appliedChar);
                                });
                activity.handler.post(() -> {
                    if (isStale(generation, switchGeneration, expectedPath)
                            || !activity.largeTextPartitionCache.isCurrent(cacheGeneration)) {
                        clearIfCurrent(switchGeneration);
                        activity.hideLoadingWindowForPartitionJumpIfCurrent(
                                showLoadingForAsyncPartitionJump,
                                switchGeneration);
                        return;
                    }
                    // The applier caches only after this UI-thread ownership check.
                    activity.applyLargeTextPartitionInPlace(
                            partition, appliedChar, displayPage, totalPages, anchorBefore, anchorAfter,
                            showLoadingForAsyncPartitionJump, switchGeneration, viewport);
                });
            } catch (Throwable t) {
                activity.handler.post(() -> {
                    if (!isStale(generation, switchGeneration, expectedPath)
                            && !activity.ensureLoadedTextCurrent()) return;
                    clearIfCurrent(switchGeneration);
                    activity.hideLoadingWindowForPartitionJumpIfCurrent(
                            showLoadingForAsyncPartitionJump,
                            switchGeneration);
                    if (!(t instanceof java.io.InterruptedIOException)
                            && !activity.activityDestroyed
                            && generation == activity.loadGeneration.get()
                            && switchGeneration == activity.largeTextPartitionSwitchGeneration.get()) {
                        ShortToast.show(activity, activity.getString(R.string.error_prefix) + t.getMessage());
                    }
                });
            }
        });
    }

    private LargeTextLinePartitionResult getCachedPartition(int targetChar,
                                                            int targetStartLine,
                                                            boolean useManualLookbehind) {
        if (useManualLookbehind && targetStartLine > 0) {
            return activity.getCachedLargeTextManualHandoffPartitionByStartLine(targetStartLine);
        }
        if (targetStartLine > 0) {
            return activity.getCachedLargeTextPartitionByStartLine(targetStartLine);
        }
        return activity.getCachedLargeTextPartitionForChar(targetChar);
    }

    /** A synchronous jump must supersede an older read or posted landing too. */
    void cancelForLocalJump() {
        activity.cancelLargeTextBoundaryHandoffs();
        if (!activity.largeTextPartitionSwitchState.isInProgress()) return;
        int previousGeneration = activity.largeTextPartitionSwitchGeneration.getAndIncrement();
        activity.hideLoadingWindowForPartitionJumpIfCurrent(true, previousGeneration);
        activity.clearLargeTextPartitionSwitchPending();
        activity.clearLargeTextQueuedPageDelta();
    }

    private boolean isStale(int generation, int switchGeneration, @NonNull String expectedPath) {
        return activity.activityDestroyed
                || generation != activity.loadGeneration.get()
                || switchGeneration != activity.largeTextPartitionSwitchGeneration.get()
                || !expectedPath.equals(activity.filePath);
    }

    private void clearIfCurrent(int switchGeneration) {
        if (switchGeneration == activity.largeTextPartitionSwitchGeneration.get()) {
            activity.clearLargeTextPartitionSwitchPending();
            activity.clearLargeTextQueuedPageDelta();
        }
    }
}
