package com.readwide.manager;

import android.widget.Toast;

import androidx.annotation.NonNull;

import com.readwide.manager.model.LargeTextLinePartitionResult;

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

    void switchInPlace(int charPosition,
                       int displayPage,
                       int totalPages,
                       String anchorBefore,
                       String anchorAfter,
                       int partitionStartLine,
                       boolean showLoadingForAsyncPartitionJump,
                       boolean useManualLookbehind,
                       boolean preferAnchorPartition) {
        if (activity.filePath == null || !activity.largeTextEstimateActive) return;

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
        final int switchGeneration = activity.largeTextPartitionSwitchGeneration.incrementAndGet();
        activity.beginLargeTextPartitionSwitchPending(displayPage, totalPages);

        LargeTextLinePartitionResult cached = anchorSelect
                ? null
                : getCachedPartition(
                        targetChar,
                        targetStartLine,
                        useManualLookbehind);
        if (cached != null) {
            activity.applyLargeTextPartitionInPlace(
                    cached, targetChar, displayPage, totalPages, anchorBefore, anchorAfter,
                    false, switchGeneration);
            return;
        }

        final int generation = activity.loadGeneration.get();
        final String expectedPath = activity.filePath;
        final int knownTotalLines = Math.max(1, activity.largeTextTotalLogicalLines);
        final int knownTotalChars = Math.max(0, activity.largeTextEstimatedTotalChars);

        if (showLoadingForAsyncPartitionJump) {
            activity.showLoadingWindowForPartitionJump(switchGeneration);
        }

        activity.largeTextPartitionExecutor.execute(() -> {
            try {
                File source = new File(expectedPath);
                int selectChar = targetChar;
                if (anchorSelect) {
                    int anchoredChar = activity.resolveLargeTextCharPositionForAnchor(
                            source, anchorBefore, anchorAfter);
                    if (anchoredChar >= 0) selectChar = anchoredChar;
                }
                final int appliedChar = selectChar;
                LargeTextLinePartitionResult partition = targetStartLine > 0
                        ? activity.readLargeTextLinePartitionAtStartLine(
                                source, targetStartLine, knownTotalLines, knownTotalChars, useManualLookbehind)
                        : activity.readLargeTextLinePartitionForChar(source, appliedChar);
                activity.cacheLargeTextPartition(partition);

                activity.handler.post(() -> {
                    if (isStale(generation, switchGeneration, expectedPath)) {
                        clearIfCurrent(switchGeneration);
                        activity.hideLoadingWindowForPartitionJumpIfCurrent(
                                showLoadingForAsyncPartitionJump,
                                switchGeneration);
                        return;
                    }
                    activity.applyLargeTextPartitionInPlace(
                            partition, appliedChar, displayPage, totalPages, anchorBefore, anchorAfter,
                            showLoadingForAsyncPartitionJump, switchGeneration);
                });
            } catch (Throwable t) {
                activity.handler.post(() -> {
                    clearIfCurrent(switchGeneration);
                    activity.hideLoadingWindowForPartitionJumpIfCurrent(
                            showLoadingForAsyncPartitionJump,
                            switchGeneration);
                    if (!activity.activityDestroyed
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
