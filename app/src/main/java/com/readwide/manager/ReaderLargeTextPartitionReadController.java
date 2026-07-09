package com.readwide.manager;

import androidx.annotation.NonNull;

import com.readwide.manager.model.LargeTextLinePartitionResult;
import com.readwide.manager.util.LargeTextPartitionReader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;

final class ReaderLargeTextPartitionReadController {
    private final ReaderActivity activity;

    /**
     * Forward read cursor for sequential partition loads. Reads can arrive
     * from different single-thread executors (main partition loads vs the
     * prefetch executor), so every cursor-aware read serializes on this lock;
     * that also lets a prefetch continue exactly where the foreground load
     * stopped. readForChar (jumps) deliberately bypasses the cursor and the
     * lock, keeping jump latency independent of an in-flight prefetch.
     */
    private final Object forwardCursorLock = new Object();
    private LargeTextPartitionReader.ForwardCursor forwardCursor;

    ReaderLargeTextPartitionReadController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    /** Closes the session's forward cursor (activity teardown / file switch). */
    void closeForwardCursor() {
        synchronized (forwardCursorLock) {
            if (forwardCursor != null) {
                forwardCursor.closeQuietly();
                forwardCursor = null;
            }
        }
    }

    private LargeTextPartitionReader.ForwardCursor cursorLocked() {
        if (forwardCursor == null) {
            forwardCursor = new LargeTextPartitionReader.ForwardCursor();
        }
        return forwardCursor;
    }

    float estimateBytesPerChar(@NonNull File file) {
        return LargeTextPartitionReader.estimateBytesPerChar(file, ReaderActivity.LARGE_TEXT_PREVIEW_BYTES);
    }

    BufferedReader openReader(@NonNull File file) throws IOException {
        return LargeTextPartitionReader.openReader(file, activity.resolveTextEncodingForFile(file));
    }

    int resolveCharPositionForAnchor(@NonNull File file,
                                     String anchorBefore,
                                     String anchorAfter) throws IOException {
        return LargeTextPartitionReader.findCharPositionForAnchor(
                activity.getApplicationContext(),
                file,
                activity.resolveTextEncodingForFile(file),
                activity.largeTextActiveCollapseBlankLines,
                anchorBefore,
                anchorAfter);
    }

    LargeTextLinePartitionResult readForChar(@NonNull File file,
                                             int targetCharPosition) throws IOException {
        return LargeTextPartitionReader.readPartitionForChar(
                activity.getApplicationContext(),
                file,
                activity.resolveTextEncodingForFile(file),
                activity.largeTextActiveCollapseBlankLines,
                targetCharPosition,
                activity.getLargeTextPartitionLines(),
                activity.getLargeTextPartitionLookaheadLines(),
                activity.getLargeTextPartitionLookbehindLines());
    }

    LargeTextLinePartitionResult readAtStartLine(@NonNull File file,
                                                 int requestedStartLine) throws IOException {
        // Mirrors the reader's stats+clamp convenience overload, but routes the
        // partition read itself through the forward cursor.
        com.readwide.manager.model.LargeTextLineStats stats =
                LargeTextPartitionReader.scanLineStats(
                        activity.getApplicationContext(),
                        file,
                        activity.resolveTextEncodingForFile(file),
                        activity.largeTextActiveCollapseBlankLines,
                        0);
        int startLine = com.readwide.manager.util.LargeTextContinuityMath
                .partitionStartLineForLine(requestedStartLine, activity.getLargeTextPartitionLines());
        if (startLine > stats.totalLines) {
            startLine = com.readwide.manager.util.LargeTextContinuityMath
                    .partitionStartLineForLine(stats.totalLines, activity.getLargeTextPartitionLines());
        }
        return readAtStartLine(file, startLine, stats.totalLines, stats.totalChars, false);
    }

    LargeTextLinePartitionResult readAtStartLine(@NonNull File file,
                                                 int requestedStartLine,
                                                 int knownTotalLines,
                                                 int knownTotalChars,
                                                 boolean includeLookbehind) throws IOException {
        synchronized (forwardCursorLock) {
            return LargeTextPartitionReader.readPartitionAtStartLine(
                    activity.getApplicationContext(),
                    file,
                    activity.resolveTextEncodingForFile(file),
                    activity.largeTextActiveCollapseBlankLines,
                    requestedStartLine,
                    knownTotalLines,
                    knownTotalChars,
                    activity.getLargeTextPartitionLines(),
                    activity.getLargeTextPartitionLookaheadLines(),
                    activity.getLargeTextPartitionLookbehindLines(),
                    includeLookbehind,
                    cursorLocked());
        }
    }
}
