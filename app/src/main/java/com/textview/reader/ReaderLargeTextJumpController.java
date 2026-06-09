package com.textview.reader;

import android.content.Intent;

import androidx.annotation.NonNull;

import com.textview.reader.util.LargeTextContinuityMath;

final class ReaderLargeTextJumpController {
    private final ReaderActivity activity;

    ReaderLargeTextJumpController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void jumpToAbsoluteCharPosition(int charPosition,
                                    int displayPage,
                                    int totalPages,
                                    String anchorBefore,
                                    String anchorAfter,
                                    boolean showLoadingForAsyncPartitionJump) {
        if (!activity.largeTextEstimateActive) {
            activity.scrollToCharPosition(charPosition);
            return;
        }

        if (activity.largeTextActivePartitionUsesLookbehind) {
            activity.reloadLargeTextPreviewAround(
                    charPosition, displayPage, totalPages, anchorBefore, anchorAfter,
                    showLoadingForAsyncPartitionJump);
            return;
        }

        if (isAbsoluteCharPositionInCurrentLargeTextBody(charPosition)) {
            int resolvedPosition = activity.resolveAnchoredAbsolutePosition(
                    activity.fileContent,
                    activity.largeTextPreviewBaseCharOffset,
                    charPosition,
                    anchorBefore,
                    anchorAfter);

            if (!isAbsoluteCharPositionInCurrentLargeTextBody(resolvedPosition)) {
                activity.reloadLargeTextPreviewAround(
                        resolvedPosition, displayPage, totalPages, anchorBefore, anchorAfter,
                        showLoadingForAsyncPartitionJump);
                return;
            }

            if (activity.isActiveSearchTarget(resolvedPosition)) {
                activity.scrollToSearchResultPosition(resolvedPosition);
            } else {
                activity.scrollToCharPosition(resolvedPosition);
            }
            activity.recomputeLargeTextDisplayPageOffset(displayPage, totalPages);
            activity.updatePositionLabel();
            return;
        }

        activity.reloadLargeTextPreviewAround(
                charPosition, displayPage, totalPages, anchorBefore, anchorAfter,
                showLoadingForAsyncPartitionJump);
    }

    boolean isAbsoluteCharPositionInCurrentLargeTextBody(int absolutePosition) {
        return activity.largeTextEstimateActive
                && activity.fileContent != null
                && LargeTextContinuityMath.isAbsoluteCharInsideCurrentBody(
                absolutePosition,
                activity.largeTextPreviewBaseCharOffset,
                activity.fileContent.length(),
                activity.largeTextPartitionBodyStartCharCount,
                activity.largeTextPartitionBodyCharCount);
    }

    void reloadLargeTextPreviewAround(int charPosition,
                                      int displayPage,
                                      int totalPages,
                                      String anchorBefore,
                                      String anchorAfter,
                                      int partitionStartLine,
                                      boolean showLoadingForAsyncPartitionJump) {
        if (activity.filePath == null) return;
        if (activity.largeTextEstimateActive) {
            activity.switchLargeTextPartitionInPlace(
                    charPosition,
                    displayPage,
                    totalPages,
                    anchorBefore,
                    anchorAfter,
                    partitionStartLine,
                    showLoadingForAsyncPartitionJump,
                    false);
            return;
        }

        Intent intent = new Intent(activity, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_FILE_PATH, activity.filePath);
        intent.putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, Math.max(0, charPosition));
        intent.putExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, Math.max(0, displayPage));
        intent.putExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, Math.max(0, totalPages));
        if (partitionStartLine > 0) {
            intent.putExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE, partitionStartLine);
        }
        if (anchorBefore != null) {
            intent.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE, anchorBefore);
        }
        if (anchorAfter != null) {
            intent.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER, anchorAfter);
        }
        activity.loadFileFromIntent(intent);
    }
}
