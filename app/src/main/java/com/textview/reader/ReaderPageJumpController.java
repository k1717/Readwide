package com.textview.reader;

import android.widget.Toast;

import androidx.annotation.NonNull;

import com.textview.reader.util.LargeTextContinuityMath;
import com.textview.reader.view.CustomReaderView;

/**
 * Owns explicit page jumps from the toolbar slider, go-to-page controls, and
 * code paths that need the canonical final large-TXT page target.
 */
final class ReaderPageJumpController {
    private final ReaderActivity activity;

    ReaderPageJumpController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    boolean scrollToPageNumber(int page) {
        return scrollToPageNumber(page, false, true);
    }

    boolean scrollToPageNumber(int page, boolean showLoadingForAsyncPartitionJump) {
        return scrollToPageNumber(page, showLoadingForAsyncPartitionJump, true);
    }

    boolean scrollToPageNumber(int page, boolean showLoadingForAsyncPartitionJump, boolean manualNavigation) {
        if (manualNavigation) activity.stopAutoPageTurnForManualNavigation();
        if (activity.readerView == null) return false;

        if (activity.largeTextEstimateActive) {
            int total = Math.max(1, activity.getDisplayedTotalPageCount());
            int target = Math.max(1, Math.min(total, page));

            if (target >= total && total > 1) {
                return jumpToFinalLargeTextPage(total, showLoadingForAsyncPartitionJump);
            }

            if (activity.isLargeTextExactPageIndexReady()) {
                CustomReaderView.PageTextAnchor anchor = activity.getExactLargeTextAnchorForPage(target);
                if (anchor != null) {
                    activity.jumpToAbsoluteCharPosition(anchor.charPosition, target, total,
                            null, null,
                            showLoadingForAsyncPartitionJump);
                    return true;
                }
            }

            boolean jumped = jumpToEstimatedLargeTextPage(
                    target,
                    total,
                    showLoadingForAsyncPartitionJump);
            if (jumped && manualNavigation) {
                showLargeTextEstimatedJumpToast();
            }
            return jumped;
        }

        int total = Math.max(1, activity.getTotalPageCount());
        activity.readerView.scrollToPage(Math.max(1, Math.min(total, page)));
        return true;
    }

    boolean jumpToEstimatedLargeTextPage(int page,
                                         int totalPages,
                                         boolean showLoadingForAsyncPartitionJump) {
        if (!activity.largeTextEstimateActive || activity.filePath == null) return false;

        int stableTotalPages = Math.max(1, totalPages);
        int targetPage = Math.max(1, Math.min(stableTotalPages, page));
        int targetLine = LargeTextContinuityMath.targetLineForEstimatedPage(
                targetPage,
                stableTotalPages,
                activity.largeTextTotalLogicalLines);

        int partitionStartLine = activity.getLargeTextPartitionStartLineForLine(targetLine);
        int targetCharPosition = activity.estimateCharPositionForLargeTextLine(targetLine);
        activity.reloadLargeTextPreviewAround(
                targetCharPosition,
                targetPage,
                stableTotalPages,
                null,
                null,
                partitionStartLine,
                showLoadingForAsyncPartitionJump);
        return true;
    }

    boolean jumpToFinalLargeTextPage(int totalPages, boolean showLoadingForAsyncPartitionJump) {
        if (!activity.largeTextEstimateActive || activity.filePath == null) return false;

        int stableTotalPages = Math.max(1, totalPages);

        CustomReaderView.PageTextAnchor finalAnchor =
                activity.getExactLargeTextAnchorForPage(stableTotalPages);
        if (finalAnchor != null) {
            activity.jumpToAbsoluteCharPosition(
                    finalAnchor.charPosition,
                    stableTotalPages,
                    stableTotalPages,
                    finalAnchor.anchorTextBefore,
                    finalAnchor.anchorTextAfter,
                    showLoadingForAsyncPartitionJump);
            return true;
        }

        return jumpToEstimatedLargeTextPage(
                stableTotalPages,
                stableTotalPages,
                showLoadingForAsyncPartitionJump);
    }

    private void showLargeTextEstimatedJumpToast() {
        boolean failed = activity.isLargeTextExactPageIndexFailed();
        ShortToast.show(activity, activity.getString(failed
                        ? R.string.large_text_exact_page_index_failed_estimated_jump
                        : R.string.large_text_exact_page_index_not_ready_estimated_jump));
    }
}
