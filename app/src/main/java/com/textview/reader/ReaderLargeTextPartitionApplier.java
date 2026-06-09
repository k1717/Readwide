package com.textview.reader;

import androidx.annotation.NonNull;

import com.textview.reader.model.LargeTextLinePartitionResult;

/**
 * Applies an already-loaded large-TXT partition to the reader view/state.
 *
 * The navigator owns loading; this applier owns the state mutation, anchor
 * resolution, scroll landing, label sync, and queued page-delta follow-up.
 */
final class ReaderLargeTextPartitionApplier {
    private final ReaderActivity activity;

    ReaderLargeTextPartitionApplier(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void apply(@NonNull LargeTextLinePartitionResult partition,
               int targetCharPosition,
               int displayPage,
               int totalPages,
               String anchorBefore,
               String anchorAfter,
               boolean hideLoadingAfterApply,
               int switchGeneration) {
        if (activity.readerView == null || activity.filePath == null || activity.activityDestroyed) {
            if (switchGeneration == activity.largeTextPartitionSwitchGeneration.get()) {
                activity.clearLargeTextPartitionSwitchPending();
            }
            activity.hideLoadingWindowForPartitionJumpIfCurrent(hideLoadingAfterApply, switchGeneration);
            return;
        }

        applyPartitionState(partition);
        activity.readerView.setLargeTextPartitionMode(true);
        activity.readerView.setOverlapLines(activity.prefs.getPagingOverlapLines());
        activity.readerView.setTextContent(activity.fileContent);
        activity.applySearchHighlight();

        activity.readerView.post(() -> finishAfterLayout(
                targetCharPosition,
                displayPage,
                totalPages,
                anchorBefore,
                anchorAfter,
                hideLoadingAfterApply,
                switchGeneration));
    }

    private void applyPartitionState(@NonNull LargeTextLinePartitionResult partition) {
        activity.cacheLargeTextPartition(partition);

        activity.fileContent = partition.content != null ? partition.content : "";
        activity.totalChars = activity.fileContent.length();
        activity.totalLines = partition.lineCount;
        activity.largeTextEstimateActive = true;
        activity.pendingLargeTextRestorePosition = -1;
        activity.largeTextPreviewBaseCharOffset = Math.max(0, partition.baseCharOffset);
        activity.largeTextEstimatedTotalChars = Math.max(activity.fileContent.length(), partition.totalChars);
        activity.hugeTextPreviewOnly = true;
        activity.largeTextPartitionStartByte = 0L;
        activity.largeTextPartitionEndByte = Math.max(
                activity.largeTextPartitionStartByte,
                activity.largeTextFileByteLength);
        activity.largeTextPartitionBodyStartCharCount = Math.max(0,
                Math.min(activity.fileContent.length(), partition.bodyStartCharCount));
        activity.largeTextPartitionBodyCharCount = Math.max(
                activity.largeTextPartitionBodyStartCharCount,
                Math.min(activity.fileContent.length(), partition.bodyCharCount));
        activity.largeTextPartitionWindowStartLine = Math.max(1, partition.windowStartLine);
        activity.largeTextActivePartitionUsesLookbehind = partition.includesLookbehind;
        activity.largeTextPartitionStartLine = Math.max(1, partition.startLine);
        activity.largeTextPartitionEndLine = Math.max(
                activity.largeTextPartitionStartLine,
                partition.endLine);
        activity.largeTextTotalLogicalLines = Math.max(1, partition.totalLines);
    }

    private void finishAfterLayout(int targetCharPosition,
                                   int displayPage,
                                   int totalPages,
                                   String anchorBefore,
                                   String anchorAfter,
                                   boolean hideLoadingAfterApply,
                                   int switchGeneration) {
        if (activity.activityDestroyed || activity.readerView == null) {
            if (switchGeneration == activity.largeTextPartitionSwitchGeneration.get()) {
                activity.clearLargeTextPartitionSwitchPending();
            }
            activity.hideLoadingWindowForPartitionJumpIfCurrent(hideLoadingAfterApply, switchGeneration);
            return;
        }

        int resolvedPosition = activity.resolveAnchoredAbsolutePosition(
                activity.fileContent,
                activity.largeTextPreviewBaseCharOffset,
                Math.max(0, targetCharPosition),
                anchorBefore,
                anchorAfter);
        resolvedPosition = clampResolvedPosition(resolvedPosition);

        if (activity.isActiveSearchTarget(resolvedPosition)) {
            activity.scrollToSearchResultPosition(resolvedPosition);
        } else {
            activity.scrollToCharPosition(resolvedPosition);
        }
        activity.recomputeLargeTextDisplayPageOffset(displayPage, totalPages);
        if (switchGeneration == activity.largeTextPartitionSwitchGeneration.get()) {
            activity.clearLargeTextPartitionSwitchPending();
        }
        activity.updatePositionLabel();
        activity.hideLoadingWindowForPartitionJumpIfCurrent(hideLoadingAfterApply, switchGeneration);
        activity.prefetchNeighborLargeTextPartitions();
        activity.processQueuedLargeTextPageDeltaAfterPartitionApply();
    }

    private int clampResolvedPosition(int resolvedPosition) {
        int localPosition = resolvedPosition - activity.largeTextPreviewBaseCharOffset;
        if (localPosition >= 0 && localPosition < activity.fileContent.length()) {
            return resolvedPosition;
        }
        int clampedLocal = Math.max(0,
                Math.min(Math.max(0, activity.fileContent.length() - 1), localPosition));
        return activity.largeTextPreviewBaseCharOffset + clampedLocal;
    }
}
