package com.readwide.manager;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.readwide.manager.model.LargeTextLinePartitionResult;
import com.readwide.manager.util.LargeTextContinuityMath;
import com.readwide.manager.util.LargeTextScrollPrefetchState;

final class ReaderLargeTextBoundaryHandoffController {
    private static final long HANDOFF_DELAY_MS = 140L;

    private final ReaderActivity activity;
    private Runnable pendingOverscroll;
    private final LargeTextScrollPrefetchState scrollPrefetch = new LargeTextScrollPrefetchState();
    private String prefetchContent;
    private int prefetchWindowBase;
    private long prefetchCacheGeneration = Long.MIN_VALUE;

    ReaderLargeTextBoundaryHandoffController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void cancelPendingHandoffs() {
        clearScrollPrefetchTracking();
        activity.handler.removeCallbacks(activity.largeTextManualScrollBoundaryHandoffRunnable);
        if (pendingOverscroll != null) {
            activity.handler.removeCallbacks(pendingOverscroll);
            pendingOverscroll = null;
        }
    }

    void scheduleManualScrollBoundaryHandoff() {
        if (!activity.largeTextEstimateActive
                || activity.readerView == null
                || activity.largeTextPartitionSwitchState.isInProgress()
                || activity.activityDestroyed) {
            clearScrollPrefetchTracking();
            activity.handler.removeCallbacks(activity.largeTextManualScrollBoundaryHandoffRunnable);
            return;
        }
        // Sampling is immediate even during a continuous drag/fling. Only replacing
        // the visible window waits for idle; disk preparation must not be debounced.
        prefetchApproachingForwardBoundary();
        activity.handler.removeCallbacks(activity.largeTextManualScrollBoundaryHandoffRunnable);
        activity.handler.postDelayed(activity.largeTextManualScrollBoundaryHandoffRunnable, HANDOFF_DELAY_MS);
    }

    private void clearScrollPrefetchTracking() {
        prefetchContent = null;
        prefetchCacheGeneration = Long.MIN_VALUE;
        scrollPrefetch.reset();
    }

    private void prefetchApproachingForwardBoundary() {
        if (activity.filePath == null || activity.fileContent == null
                || !activity.readerView.hasCurrentTextLayout()
                || !activity.hasNextLargeTextPartition()) return;
        int contentLength = activity.fileContent.length();
        int bodyEnd = Math.min(contentLength, activity.largeTextPartitionBodyCharCount);
        if (contentLength <= 0 || bodyEnd <= 0) return;

        long cacheGeneration = activity.largeTextPartitionCache.generation();
        if (prefetchContent != activity.fileContent
                || prefetchWindowBase != activity.largeTextPreviewBaseCharOffset
                || prefetchCacheGeneration != cacheGeneration) {
            scrollPrefetch.reset();
            prefetchContent = activity.fileContent;
            prefetchWindowBase = activity.largeTextPreviewBaseCharOffset;
            prefetchCacheGeneration = cacheGeneration;
        }
        long now = SystemClock.uptimeMillis();
        int screens = scrollPrefetch.observeScroll(activity.readerView.getReaderScrollY(), now,
                activity.readerView.getViewportHeight());
        int projectedChar = activity.readerView.getCharPositionAfterViewportScreens(screens);
        if (projectedChar < bodyEnd) return;
        int nextStartLine = activity.getLargeTextPartitionStartLineForLine(
                activity.largeTextPartitionEndLine + 1);
        if (activity.getCachedLargeTextManualHandoffPartitionByStartLine(nextStartLine) != null) return;
        if (scrollPrefetch.shouldRequest(projectedChar, bodyEnd, now)) {
            // At most the next window. The existing cache ticket deduplicates reads,
            // and its worker yields to explicit navigation instead of competing.
            activity.prefetchLargeTextManualHandoffPartitionByStartLine(nextStartLine);
        }
    }

    void handleManualScrollBoundaryHandoff() {
        if (!canHandleBoundaryHandoff()) {
            return;
        }

        if (activity.readerView.isUserDraggingOrFlinging()) {
            scheduleManualScrollBoundaryHandoff();
            return;
        }

        int contentLength = activity.fileContent.length();
        int bodyEnd = activity.largeTextPartitionBodyCharCount > 0
                ? Math.min(contentLength, activity.largeTextPartitionBodyCharCount)
                : contentLength;
        if (contentLength <= 0
                || bodyEnd <= 0
                || bodyEnd >= contentLength
                || !activity.hasNextLargeTextPartition()) {
            return;
        }

        int currentLocalStart = Math.max(0, Math.min(contentLength,
                activity.readerView.getCurrentPageStartCharPositionForCoverage()));
        int visibleEndLocal = Math.max(currentLocalStart, Math.min(contentLength,
                activity.readerView.getCharPositionAfterCurrentVisibleContent()));

        int nextStartLine = activity.getLargeTextPartitionStartLineForLine(
                activity.largeTextPartitionEndLine + 1);
        LargeTextLinePartitionResult nextCached =
                activity.getCachedLargeTextManualHandoffPartitionByStartLine(nextStartLine);

        if (currentLocalStart < bodyEnd && visibleEndLocal > bodyEnd) {
            handleEarlyLookaheadHandoff(currentLocalStart, nextStartLine, nextCached);
            return;
        }

        if (currentLocalStart >= bodyEnd) {
            handoffToNextPartition(currentLocalStart, bodyEnd, nextStartLine, nextCached);
        }
    }

    void handleManualOverscroll(int direction) {
        if (!activity.largeTextEstimateActive
                || activity.readerView == null
                || activity.filePath == null
                || activity.largeTextPartitionSwitchState.isInProgress()
                || activity.activityDestroyed
                || direction >= 0
                || !activity.hasPreviousLargeTextPartition()
                || pendingOverscroll != null) {
            return;
        }

        final int generation = activity.loadGeneration.get();
        final int switchGeneration = activity.largeTextPartitionSwitchGeneration.get();
        final int windowBase = activity.largeTextPreviewBaseCharOffset;
        pendingOverscroll = () -> {
            if (!activity.largeTextEstimateActive
                    || activity.readerView == null
                    || activity.filePath == null
                    || activity.largeTextPartitionSwitchState.isInProgress()
                    || activity.activityDestroyed
                    || activity.readerView.isTextSelectionActive()
                    || generation != activity.loadGeneration.get()
                    || switchGeneration != activity.largeTextPartitionSwitchGeneration.get()
                    || windowBase != activity.largeTextPreviewBaseCharOffset
                    || !activity.readerView.isAtStartOfText()
                    || !activity.hasPreviousLargeTextPartition()) {
                pendingOverscroll = null;
                return;
            }
            if (activity.readerView.isUserDraggingOrFlinging()) {
                activity.handler.postDelayed(pendingOverscroll, HANDOFF_DELAY_MS);
                return;
            }
            pendingOverscroll = null;
            int total = Math.max(1, activity.getDisplayedTotalPageCount());
            int displayedTarget = Math.max(1,
                    Math.min(total, activity.getDisplayedCurrentPageNumber()));
            int previousStart = activity.getLargeTextPartitionStartLineForLine(
                    activity.largeTextPartitionStartLine - activity.getLargeTextPartitionLines());
            activity.recordLargeTextPageDirection(-1);
            activity.handoffLargeTextViewport(displayedTarget, total, previousStart, false);
        };
        activity.handler.post(pendingOverscroll);
    }

    private boolean canHandleBoundaryHandoff() {
        return activity.largeTextEstimateActive
                && activity.readerView != null
                && activity.filePath != null
                && activity.fileContent != null
                && !activity.readerView.isTextSelectionActive()
                && !activity.largeTextPartitionSwitchState.isInProgress()
                && !activity.activityDestroyed;
    }

    private void handleEarlyLookaheadHandoff(int currentLocalStart,
                                             int nextStartLine,
                                             LargeTextLinePartitionResult nextCached) {
        if (nextCached == null) {
            activity.prefetchLargeTextManualHandoffPartitionByStartLine(nextStartLine);
            return;
        }
        int currentAbsTop = activity.largeTextPreviewBaseCharOffset + currentLocalStart;
        int nextWindowStart = nextCached.baseCharOffset;
        int nextWindowEnd = nextWindowStart + Math.max(0, nextCached.content.length());
        if (currentAbsTop >= nextWindowStart && currentAbsTop < nextWindowEnd) {
            int total = Math.max(1, activity.getDisplayedTotalPageCount());
            int displayPage = activity.isLargeTextExactPageIndexReady()
                    ? Math.max(1, Math.min(total, activity.findExactLargeTextPageForChar(currentAbsTop)))
                    : Math.max(1, Math.min(total, activity.getDisplayedCurrentPageNumber()));
            activity.recordLargeTextPageDirection(+1);
            activity.handoffLargeTextViewport(displayPage, total, nextStartLine, true);
        }
    }

    private void handoffToNextPartition(int currentLocalStart, int bodyEnd, int nextStartLine,
                                        LargeTextLinePartitionResult nextCached) {
        int targetAbs = LargeTextContinuityMath.clampAbsolutePositionToKnownText(
                LargeTextContinuityMath.forwardHandoffTargetAbs(
                        activity.largeTextPreviewBaseCharOffset,
                        currentLocalStart,
                        bodyEnd),
                activity.largeTextEstimatedTotalChars);

        int total = Math.max(1, activity.getDisplayedTotalPageCount());
        int displayPage = activity.isLargeTextExactPageIndexReady()
                ? Math.max(1, Math.min(total, activity.findExactLargeTextPageForChar(targetAbs)))
                : Math.max(1, Math.min(total, activity.getDisplayedCurrentPageNumber()));
        activity.recordLargeTextPageDirection(+1);
        long currentAbsTop = (long) activity.largeTextPreviewBaseCharOffset + currentLocalStart;
        boolean cachedViewportContainsTop = nextCached != null
                && currentAbsTop >= nextCached.baseCharOffset
                && currentAbsTop < (long) nextCached.baseCharOffset + nextCached.content.length();
        // High-speed scrolling can pass the body seam before becoming idle. Reuse
        // the manual window prepared above rather than loading its normal twin.
        activity.handoffLargeTextViewport(displayPage, total, nextStartLine, cachedViewportContainsTop);
    }
}
