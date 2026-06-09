package com.textview.reader;

import androidx.annotation.NonNull;

import com.textview.reader.util.FileUtils;
import com.textview.reader.util.TextSearchMath;

final class ReaderTextLocator {
    private final ReaderActivity activity;

    ReaderTextLocator(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    int getCurrentCharPosition() {
        int localPosition = activity.readerView != null ? activity.readerView.getCurrentCharPosition() : 0;
        return activity.largeTextEstimateActive
                ? Math.max(0, activity.largeTextPreviewBaseCharOffset + localPosition)
                : localPosition;
    }

    int getBookmarkSaveCharPosition() {
        if (activity.readerView == null) return 0;

        int localPosition = activity.readerView.getCharPositionAtTitleCoveredRow();
        if (activity.fileContent != null && !activity.fileContent.isEmpty()) {
            localPosition = FileUtils.clampToSurrogateSafeStart(activity.fileContent, localPosition);
        }

        return activity.largeTextEstimateActive
                ? Math.max(0, activity.largeTextPreviewBaseCharOffset + localPosition)
                : localPosition;
    }

    String getExcerpt(int charPosition) {
        return TextSearchMath.getExcerpt(activity.fileContent, charPosition,
                activity.largeTextEstimateActive, activity.largeTextPreviewBaseCharOffset);
    }

    String getAnchorTextBefore(int charPosition) {
        return TextSearchMath.getAnchorTextBefore(activity.fileContent, charPosition,
                activity.largeTextEstimateActive, activity.largeTextPreviewBaseCharOffset);
    }

    String getAnchorTextAfter(int charPosition) {
        return TextSearchMath.getAnchorTextAfter(activity.fileContent, charPosition,
                activity.largeTextEstimateActive, activity.largeTextPreviewBaseCharOffset);
    }

    int resolveAnchoredAbsolutePosition(String content,
                                        int baseCharOffset,
                                        int fallbackAbsolutePosition,
                                        String anchorBefore,
                                        String anchorAfter) {
        return TextSearchMath.resolveAnchoredAbsolutePosition(content, baseCharOffset,
                fallbackAbsolutePosition, anchorBefore, anchorAfter);
    }

    int countLines(String s) {
        return TextSearchMath.countLines(s);
    }

    int countLinesUntilChar(int charPosition) {
        return TextSearchMath.countLinesUntilChar(activity.fileContent, charPosition,
                activity.largeTextEstimateActive, activity.largeTextPreviewBaseCharOffset,
                activity.largeTextPartitionWindowStartLine);
    }

    int findText(String query, int startPosition) {
        return TextSearchMath.findText(activity.fileContent, query, startPosition);
    }

    int findTextBackward(String query, int startPosition) {
        return TextSearchMath.findTextBackward(activity.fileContent, query, startPosition);
    }

    int countTextMatches(String query) {
        return TextSearchMath.countTextMatches(activity.fileContent, query);
    }

    int findNthText(String query, int occurrence) {
        return TextSearchMath.findNthText(activity.fileContent, query, occurrence);
    }

    int matchIndexForPosition(String query, int position) {
        return TextSearchMath.matchIndexForPosition(activity.fileContent, query, position);
    }

    int findCharForLine(int targetLine) {
        return TextSearchMath.findCharForLine(activity.fileContent, activity.totalChars, targetLine);
    }
}
