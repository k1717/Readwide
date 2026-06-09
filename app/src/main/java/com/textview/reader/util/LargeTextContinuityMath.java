package com.textview.reader.util;

import androidx.annotation.NonNull;

import com.textview.reader.model.LargeTextLinePartitionResult;

/**
 * Pure continuity math for large TXT runtime partitions.
 *
 * This class deliberately contains only stateless calculations.  ReaderActivity keeps
 * the lifecycle, async loading, and view mutation code; this helper defines the exact
 * canonical partition/window/body rules that protect TXT continuity:
 *
 * - tap/page/bookmark jumps resolve against canonical body ownership only;
 * - lookahead/lookbehind may exist for manual-scroll smoothness, but does not own pages;
 * - estimated line/page fallback clamps to stable, non-zero ranges.
 */
public final class LargeTextContinuityMath {
    private LargeTextContinuityMath() {}

    public static final class PartitionWindow {
        public final int startLine;
        public final int bodyEndLine;
        public final int windowStartLine;
        public final int captureEndLine;

        public PartitionWindow(int startLine,
                               int bodyEndLine,
                               int windowStartLine,
                               int captureEndLine) {
            this.startLine = Math.max(1, startLine);
            this.bodyEndLine = Math.max(this.startLine, bodyEndLine);
            this.windowStartLine = Math.max(1, Math.min(this.startLine, windowStartLine));
            this.captureEndLine = Math.max(this.bodyEndLine, captureEndLine);
        }
    }

    public static final class BodyCharRange {
        public final int start;
        public final int endExclusive;

        public BodyCharRange(int start, int endExclusive) {
            this.start = Math.max(0, start);
            this.endExclusive = Math.max(this.start, endExclusive);
        }

        public boolean isValid() {
            return endExclusive > start;
        }

        public boolean contains(int position) {
            return isValid() && position >= start && position < endExclusive;
        }
    }

    public static int partitionStartLineForLine(int line, int partitionLines) {
        int clampedLine = Math.max(1, line);
        int stablePartitionLines = Math.max(1, partitionLines);
        return ((clampedLine - 1) / stablePartitionLines) * stablePartitionLines + 1;
    }

    @NonNull
    public static PartitionWindow partitionWindowForStartLine(int requestedStartLine,
                                                              int totalLines,
                                                              int partitionLines,
                                                              int lookaheadLines,
                                                              int lookbehindLines,
                                                              boolean includeLookbehind) {
        int stableTotalLines = Math.max(1, totalLines);
        int stablePartitionLines = Math.max(1, partitionLines);
        int startLine = partitionStartLineForLine(requestedStartLine, stablePartitionLines);
        int bodyEndLine = Math.min(stableTotalLines, startLine + stablePartitionLines - 1);
        int windowStartLine = includeLookbehind
                ? Math.max(1, startLine - Math.max(0, lookbehindLines))
                : startLine;
        int captureEndLine = Math.min(stableTotalLines, bodyEndLine + Math.max(0, lookaheadLines));
        return new PartitionWindow(startLine, bodyEndLine, windowStartLine, captureEndLine);
    }

    @NonNull
    public static BodyCharRange localBodyRange(int contentLength,
                                               int bodyStartCharCount,
                                               int bodyCharCount) {
        int length = Math.max(0, contentLength);
        int bodyStart = Math.max(0, Math.min(length, bodyStartCharCount));
        int bodyEnd = bodyCharCount > 0 ? Math.min(length, bodyCharCount) : length;
        bodyEnd = Math.max(bodyStart, Math.min(length, bodyEnd));
        return new BodyCharRange(bodyStart, bodyEnd);
    }

    @NonNull
    public static BodyCharRange absoluteBodyRange(@NonNull LargeTextLinePartitionResult partition) {
        BodyCharRange local = localBodyRange(
                partition.content.length(),
                partition.bodyStartCharCount,
                partition.bodyCharCount);
        return new BodyCharRange(
                partition.baseCharOffset + local.start,
                partition.baseCharOffset + local.endExclusive);
    }

    public static boolean ownsAbsoluteChar(@NonNull LargeTextLinePartitionResult partition,
                                           int absoluteCharPosition) {
        return absoluteBodyRange(partition).contains(Math.max(0, absoluteCharPosition));
    }

    public static boolean isAbsoluteCharInsideCurrentBody(int absolutePosition,
                                                          int previewBaseCharOffset,
                                                          int contentLength,
                                                          int bodyStartCharCount,
                                                          int bodyCharCount) {
        int localPosition = absolutePosition - Math.max(0, previewBaseCharOffset);
        return localBodyRange(contentLength, bodyStartCharCount, bodyCharCount).contains(localPosition);
    }

    public static int forwardHandoffTargetAbs(int previewBaseCharOffset,
                                              int nextPageStartLocal,
                                              int bodyEndExclusive) {
        int localTarget = Math.max(0, nextPageStartLocal);
        int targetAbs = Math.max(0, previewBaseCharOffset) + localTarget;
        if (localTarget == Math.max(0, bodyEndExclusive)) {
            targetAbs += 1;
        }
        return Math.max(0, targetAbs);
    }

    public static int clampAbsolutePositionToKnownText(int absolutePosition,
                                                       int estimatedTotalChars) {
        int max = Math.max(0, estimatedTotalChars - 1);
        return Math.max(0, Math.min(max, absolutePosition));
    }

    public static int estimateCharPositionForLine(int targetLine,
                                                  int totalLogicalLines,
                                                  int estimatedTotalChars) {
        int totalLinesForEstimate = Math.max(1, totalLogicalLines);
        int totalCharsForEstimate = Math.max(1, estimatedTotalChars);
        int clampedLine = Math.max(1, Math.min(totalLinesForEstimate, targetLine));
        float ratio = (clampedLine - 1f) / (float) Math.max(1, totalLinesForEstimate - 1);
        return Math.max(0, Math.min(totalCharsForEstimate - 1,
                Math.round(ratio * Math.max(0, totalCharsForEstimate - 1))));
    }

    public static int estimateDisplayedPageForLine(int lineNumber,
                                                   int totalPages,
                                                   int totalLogicalLines) {
        int stableTotal = Math.max(1, totalPages);
        int totalLinesForEstimate = Math.max(1, totalLogicalLines);
        int line = Math.max(1, Math.min(totalLinesForEstimate, lineNumber));
        float ratio = (line - 1f) / (float) Math.max(1, totalLinesForEstimate - 1);
        return Math.max(1, Math.min(stableTotal,
                1 + Math.round(ratio * Math.max(0, stableTotal - 1))));
    }

    public static int targetLineForEstimatedPage(int page,
                                                 int totalPages,
                                                 int totalLogicalLines) {
        int stableTotalPages = Math.max(1, totalPages);
        int targetPage = Math.max(1, Math.min(stableTotalPages, page));
        int totalLinesForEstimate = Math.max(1, totalLogicalLines);
        float pageRatio = (targetPage - 1f) / (float) Math.max(1, stableTotalPages - 1);
        int targetLine = 1 + Math.round(pageRatio * Math.max(0, totalLinesForEstimate - 1));
        return Math.max(1, Math.min(totalLinesForEstimate, targetLine));
    }
}
