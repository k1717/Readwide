package com.textview.reader;

import androidx.annotation.NonNull;

import com.textview.reader.model.LargeTextLinePartitionResult;

import java.io.File;

final class ReaderLargeTextPartitionPrefetchController {
    private final ReaderActivity activity;

    ReaderLargeTextPartitionPrefetchController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void reloadPartitionByStartLine(int startLine, int displayPage, int totalPages) {
        if (!activity.largeTextEstimateActive || activity.filePath == null) return;
        int partitionStartLine = activity.getLargeTextPartitionStartLineForLine(startLine);
        int targetChar = activity.largeTextPreviewBaseCharOffset;
        LargeTextLinePartitionResult cached =
                activity.getCachedLargeTextPartitionByStartLine(partitionStartLine);
        if (partitionStartLine > activity.largeTextPartitionStartLine) {
            targetChar = cached != null
                    ? cached.baseCharOffset + Math.max(0, cached.bodyStartCharCount)
                    : activity.largeTextPreviewBaseCharOffset
                            + Math.max(0, activity.largeTextPartitionBodyCharCount) + 1;
        } else if (partitionStartLine < activity.largeTextPartitionStartLine) {
            targetChar = cached != null
                    ? cached.baseCharOffset + Math.max(0, cached.bodyCharCount - 1)
                    : Math.max(0, activity.largeTextPreviewBaseCharOffset
                            + Math.max(0, activity.largeTextPartitionBodyStartCharCount) - 1);
        }

        int preservedDisplayPage = displayPage;
        int preservedTotalPages = totalPages;
        if (preservedDisplayPage <= 0) {
            int currentDisplayPage = activity.getDisplayedCurrentPageNumber();
            if (partitionStartLine > activity.largeTextPartitionStartLine) {
                preservedDisplayPage = currentDisplayPage + 1;
            } else if (partitionStartLine < activity.largeTextPartitionStartLine) {
                preservedDisplayPage = currentDisplayPage - 1;
            } else {
                preservedDisplayPage = currentDisplayPage;
            }
        }
        if (preservedTotalPages <= 0) {
            preservedTotalPages = activity.getDisplayedTotalPageCount();
        }
        preservedTotalPages = Math.max(1, preservedTotalPages);
        preservedDisplayPage = Math.max(1, Math.min(preservedTotalPages, preservedDisplayPage));

        activity.switchLargeTextPartitionInPlace(
                targetChar, preservedDisplayPage, preservedTotalPages,
                null, null, partitionStartLine, false, false);
    }

    void prefetchNeighborPartitions() {
        if (!activity.largeTextEstimateActive || activity.filePath == null) return;

        int nextStart = activity.getLargeTextPartitionStartLineForLine(
                activity.largeTextPartitionEndLine + 1);
        int previousStart = activity.getLargeTextPartitionStartLineForLine(
                activity.largeTextPartitionStartLine - activity.getLargeTextPartitionLines());
        boolean preferPrevious = activity.largeTextPageDirectionState.preferPrevious();

        if (preferPrevious) {
            prefetchPreviousThenNext(previousStart, nextStart);
        } else {
            prefetchNextThenPrevious(previousStart, nextStart);
        }
    }

    void prefetchManualHandoffPartitionByStartLine(int requestedStartLine) {
        prefetchPartitionByStartLine(requestedStartLine, true);
    }

    private void prefetchPreviousThenNext(int previousStart, int nextStart) {
        if (activity.hasPreviousLargeTextPartition()) {
            prefetchPartitionByStartLine(previousStart, false);
            if (activity.largeTextPageDirectionState.shouldPrefetchSecondNeighbor()) {
                int previousPrevious = activity.getLargeTextPartitionStartLineForLine(
                        previousStart - activity.getLargeTextPartitionLines());
                if (previousPrevious >= 1 && previousPrevious < previousStart) {
                    prefetchPartitionByStartLine(previousPrevious, false);
                }
            }
        }
        if (activity.hasNextLargeTextPartition()) {
            prefetchPartitionByStartLine(nextStart, false);
        }
    }

    private void prefetchNextThenPrevious(int previousStart, int nextStart) {
        if (activity.hasNextLargeTextPartition()) {
            prefetchPartitionByStartLine(nextStart, false);
            if (activity.largeTextPageDirectionState.shouldPrefetchSecondNeighbor()) {
                int nextNext = activity.getLargeTextPartitionStartLineForLine(
                        nextStart + activity.getLargeTextPartitionLines());
                if (nextNext <= activity.largeTextTotalLogicalLines && nextNext > nextStart) {
                    prefetchPartitionByStartLine(nextNext, false);
                }
            }
        }
        if (activity.hasPreviousLargeTextPartition()) {
            prefetchPartitionByStartLine(previousStart, false);
        }
    }

    private void prefetchPartitionByStartLine(int requestedStartLine, boolean manualHandoff) {
        if (activity.filePath == null || !activity.largeTextEstimateActive) return;
        final int startLine = activity.getLargeTextPartitionStartLineForLine(requestedStartLine);
        boolean marked = manualHandoff
                ? activity.markLargeTextManualHandoffPartitionPrefetchPending(startLine)
                : activity.markLargeTextPartitionPrefetchPending(startLine);
        if (!marked) return;

        final int generation = activity.loadGeneration.get();
        final String expectedPath = activity.filePath;
        final int knownTotalLines = Math.max(1, activity.largeTextTotalLogicalLines);
        final int knownTotalChars = Math.max(0, activity.largeTextEstimatedTotalChars);

        activity.largeTextPartitionExecutor.execute(() -> {
            try {
                File source = new File(expectedPath);
                LargeTextLinePartitionResult partition = activity.readLargeTextLinePartitionAtStartLine(
                        source, startLine, knownTotalLines, knownTotalChars, manualHandoff);
                if (!activity.activityDestroyed
                        && generation == activity.loadGeneration.get()
                        && expectedPath.equals(activity.filePath)) {
                    activity.cacheLargeTextPartition(partition);
                } else {
                    unmarkPrefetchPending(startLine, manualHandoff);
                }
            } catch (Throwable ignored) {
                unmarkPrefetchPending(startLine, manualHandoff);
            }
        });
    }

    private void unmarkPrefetchPending(int startLine, boolean manualHandoff) {
        if (manualHandoff) {
            activity.unmarkLargeTextManualHandoffPartitionPrefetchPending(startLine);
        } else {
            activity.unmarkLargeTextPartitionPrefetchPending(startLine);
        }
    }
}
