package com.readwide.manager;

import android.view.View;
import androidx.annotation.NonNull;

import com.readwide.manager.model.LargeTextLinePartitionResult;
import com.readwide.manager.view.CustomReaderView;

/**
 * Applies an already-loaded large-TXT partition to the reader view/state.
 *
 * The navigator owns loading; this applier owns the state mutation, anchor
 * resolution, scroll landing, label sync, and queued page-delta follow-up.
 */
final class ReaderLargeTextPartitionApplier {
    private final ReaderActivity activity;
    private CustomReaderView pendingLayoutView;
    private View.OnLayoutChangeListener pendingLayoutListener;
    private Runnable pendingLayoutCheck;

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
               int switchGeneration,
               ReaderLargeTextPartitionNavigator.ViewportHandoff viewport) {
        if (switchGeneration != activity.largeTextPartitionSwitchGeneration.get()) return;
        if (!activity.ensureLoadedTextCurrent()) return;
        cancelPendingLayout();
        if (activity.readerView == null || activity.filePath == null || activity.activityDestroyed) {
            if (switchGeneration == activity.largeTextPartitionSwitchGeneration.get()) {
                activity.clearLargeTextPartitionSwitchPending();
            }
            activity.hideLoadingWindowForPartitionJumpIfCurrent(hideLoadingAfterApply, switchGeneration);
            return;
        }

        if (viewport != null && !viewport.stillOwnsViewport(activity)) {
            // A new contact, reflow or scroll won the race with this disk read.
            // Keep its current screen; a later idle pass can use the loaded window.
            activity.cacheLargeTextPartition(partition);
            activity.clearLargeTextPartitionSwitchPending();
            activity.clearLargeTextQueuedPageDelta();
            activity.hideLoadingWindowForPartitionJumpIfCurrent(hideLoadingAfterApply, switchGeneration);
            activity.scheduleLargeTextManualScrollBoundaryHandoff();
            return;
        }
        if (viewport != null && !viewport.isCoveredBy(partition)) {
            // Clamping would silently change the visible row. Leave the old
            // window intact when this buffer cannot represent the same target.
            activity.clearLargeTextPartitionSwitchPending();
            activity.clearLargeTextQueuedPageDelta();
            return;
        }

        final int layoutGeneration = activity.loadGeneration.get();
        final CustomReaderView targetView = activity.readerView;
        activity.largeTextPartitionSwitchState.setPendingTargetChar(targetCharPosition);
        activity.textContentReadyForPersistence = false;
        activity.prepareLargeTextPartitionRowOrigin(partition);
        applyPartitionState(partition);
        activity.readerView.setLargeTextPartitionMode(true);
        activity.readerView.setOverlapLines(activity.prefs.getPagingOverlapLines());
        activity.readerView.setTextContent(activity.fileContent,
                activity.loadedTextWindowReachesDocumentEnd());
        activity.refreshTxtAnnotationHighlights();
        activity.applySearchHighlight();
        final String expectedContent = activity.fileContent;

        Runnable finish = () -> {
            if (activity.activityDestroyed || layoutGeneration != activity.loadGeneration.get()
                    || switchGeneration != activity.largeTextPartitionSwitchGeneration.get()
                    || targetView != activity.readerView || expectedContent != activity.fileContent
                    || targetView.getTextContent() != expectedContent) return;
            finishAfterLayout(
                targetCharPosition,
                displayPage,
                totalPages,
                anchorBefore,
                anchorAfter,
                hideLoadingAfterApply,
                switchGeneration, viewport);
        };
        // setTextContent rebuilds StaticLayout synchronously when sized. Commit
        // the landing in the same UI turn, before a new touch can see position 0.
        if (targetView.hasCurrentTextLayout()) {
            finish.run();
        } else {
            // post() alone does not imply that Android has measured this view.
            // Own one actual layout callback, removed on replacement/cancel/release.
            pendingLayoutView = targetView;
            pendingLayoutCheck = () -> {
                if (activity.activityDestroyed || layoutGeneration != activity.loadGeneration.get()
                        || switchGeneration != activity.largeTextPartitionSwitchGeneration.get()
                        || targetView != activity.readerView || expectedContent != activity.fileContent
                        || targetView.getTextContent() != expectedContent) {
                    cancelPendingLayout();
                } else if (targetView.hasCurrentTextLayout()) {
                    cancelPendingLayout();
                    finish.run();
                }
            };
            pendingLayoutListener = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    finishPendingLayoutIfReady();
            targetView.addOnLayoutChangeListener(pendingLayoutListener);
        }
    }

    void finishPendingLayoutIfReady() {
        // Style/inset changes may build a layout without changing View bounds.
        if (pendingLayoutCheck != null) pendingLayoutCheck.run();
    }

    void cancelPendingLayout() {
        if (pendingLayoutView != null && pendingLayoutListener != null) {
            pendingLayoutView.removeOnLayoutChangeListener(pendingLayoutListener);
        }
        pendingLayoutView = null;
        pendingLayoutListener = null;
        pendingLayoutCheck = null;
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
                                   int switchGeneration,
                                   ReaderLargeTextPartitionNavigator.ViewportHandoff viewport) {
        if (activity.activityDestroyed || activity.readerView == null) {
            if (switchGeneration == activity.largeTextPartitionSwitchGeneration.get()) {
                activity.clearLargeTextPartitionSwitchPending();
            }
            activity.hideLoadingWindowForPartitionJumpIfCurrent(hideLoadingAfterApply, switchGeneration);
            return;
        }

        if (!activity.ensureLoadedTextCurrent()) return;
        int resolvedPosition = activity.resolveAnchoredAbsolutePosition(
                activity.fileContent,
                activity.largeTextPreviewBaseCharOffset,
                Math.max(0, targetCharPosition),
                anchorBefore,
                anchorAfter);
        resolvedPosition = clampResolvedPosition(resolvedPosition,
                activity.largeTextPreviewBaseCharOffset, activity.fileContent,
                activity.loadedTextWindowReachesDocumentEnd());
        activity.largeTextPartitionSwitchState.setPendingTargetChar(resolvedPosition);

        if (viewport != null) {
            activity.readerView.restoreScrollViewportAnchor(
                    resolvedPosition - activity.largeTextPreviewBaseCharOffset,
                    viewport.anchor.baselineOffsetPx);
        } else if (activity.isActiveSearchTarget(resolvedPosition)) {
            activity.scrollToSearchResultPosition(resolvedPosition);
        } else {
            activity.scrollToCharPosition(resolvedPosition);
        }
        activity.recomputeLargeTextDisplayPageOffset(displayPage, totalPages);
        if (switchGeneration == activity.largeTextPartitionSwitchGeneration.get()) {
            activity.clearLargeTextPartitionSwitchPending();
        }
        activity.updatePositionLabel();
        activity.textContentReadyForPersistence = true;
        activity.hideLoadingWindowForPartitionJumpIfCurrent(hideLoadingAfterApply, switchGeneration);
        activity.prefetchNeighborLargeTextPartitions();
        if (switchGeneration != activity.largeTextPartitionSwitchGeneration.get()) return;
        activity.processQueuedLargeTextPageDeltaAfterPartitionApply();
    }

    static int clampResolvedPosition(int resolvedPosition, int base, String content, boolean reachesEnd) {
        int localPosition = resolvedPosition - base;
        int lastPosition = Math.max(0, content.length() - 1);
        // A trailing newline has a real empty layout row at length(), not at
        // length()-1. Only document EOF may own this end-of-window anchor.
        if (reachesEnd && content.endsWith("\n")) {
            lastPosition = content.length();
        }
        if (localPosition >= 0 && localPosition <= lastPosition) {
            return resolvedPosition;
        }
        int clampedLocal = Math.max(0, Math.min(lastPosition, localPosition));
        return base + clampedLocal;
    }
}
