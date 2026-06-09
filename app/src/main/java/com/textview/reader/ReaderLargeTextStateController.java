package com.textview.reader;

import androidx.annotation.NonNull;

import com.textview.reader.util.LargeTextContinuityMath;
import com.textview.reader.util.PrefsManager;

final class ReaderLargeTextStateController {
    private final ReaderActivity activity;

    ReaderLargeTextStateController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    int partitionLines() {
        return activity.prefs != null
                ? activity.prefs.getLargeTextPartitionLines()
                : PrefsManager.LARGE_TEXT_PARTITION_LINES_STANDARD;
    }

    int partitionBufferLines() {
        return activity.prefs != null
                ? activity.prefs.getLargeTextPartitionBufferLines()
                : PrefsManager.LARGE_TEXT_PARTITION_BUFFER_LINES_STANDARD;
    }

    int partitionLookaheadLines() {
        return partitionBufferLines();
    }

    int partitionLookbehindLines() {
        return partitionBufferLines();
    }

    int partitionMode() {
        return activity.prefs != null
                ? activity.prefs.getLargeTextPartitionMode()
                : PrefsManager.LARGE_TEXT_PARTITION_MODE_STANDARD;
    }

    int partitionStartLineForLine(int line) {
        return LargeTextContinuityMath.partitionStartLineForLine(line, partitionLines());
    }

    int estimateDisplayedPageForLine(int lineNumber, int totalPages) {
        return LargeTextContinuityMath.estimateDisplayedPageForLine(
                lineNumber, totalPages, activity.largeTextTotalLogicalLines);
    }

    boolean hasNextPartition() {
        return activity.largeTextEstimateActive
                && activity.largeTextTotalLogicalLines > 0
                && activity.largeTextPartitionEndLine < activity.largeTextTotalLogicalLines;
    }

    boolean hasPreviousPartition() {
        return activity.largeTextEstimateActive && activity.largeTextPartitionStartLine > 1;
    }

    int lastLocalPageStartingInsidePartition() {
        if (activity.readerView == null
                || !activity.largeTextEstimateActive
                || activity.fileContent == null
                || activity.fileContent.isEmpty()) {
            return 1;
        }
        LargeTextContinuityMath.BodyCharRange bodyRange = LargeTextContinuityMath.localBodyRange(
                activity.fileContent.length(),
                activity.largeTextPartitionBodyStartCharCount,
                activity.largeTextPartitionBodyCharCount);
        int firstBodyPage = Math.max(1, activity.readerView.getPageNumberForCharPosition(bodyRange.start));
        int lastBodyChar = Math.max(bodyRange.start, Math.max(0, bodyRange.endExclusive - 1));
        int lastBodyPage = Math.max(firstBodyPage, activity.readerView.getPageNumberForCharPosition(lastBodyChar));
        return Math.max(1, lastBodyPage - firstBodyPage + 1);
    }

    void beginPartitionSwitchPending(int displayPage, int totalPages) {
        activity.largeTextPartitionSwitchState.begin(
                displayPage,
                totalPages,
                activity.getDisplayedCurrentPageNumber(),
                activity.getDisplayedTotalPageCount());
    }

    void clearPartitionSwitchPending() {
        activity.largeTextPartitionSwitchState.clearPending();
    }
}
