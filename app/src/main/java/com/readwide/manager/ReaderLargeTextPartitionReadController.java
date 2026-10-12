package com.readwide.manager;

import androidx.annotation.NonNull;

import com.readwide.manager.model.LargeTextLinePartitionResult;
import com.readwide.manager.util.LargeTextPartitionReader;
import com.readwide.manager.util.LargeTextCoordinateMath;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.TextDisplayRuleManager;
import com.readwide.manager.util.TxtBlankLineCollapser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;

final class ReaderLargeTextPartitionReadController {
    private final ReaderActivity activity;

    /**
     * Forward read cursor for sequential partition loads. Foreground navigation
     * and prefetch share the priority worker; initial loads can use the file-load
     * worker, so cursor-aware reads still serialize in this session. Prefetch can
     * resume where a completed foreground load stopped. readForChar deliberately
     * bypasses cursor ownership; queued navigation receives foreground priority
     * and running prefetch yields at a cooperative read checkpoint.
     */
    private final LargeTextPartitionReader.ForwardCursorSession forwardSession =
            new LargeTextPartitionReader.ForwardCursorSession();

    ReaderLargeTextPartitionReadController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    /** Retires this session permanently without waiting for worker I/O on the UI. */
    void closeForwardCursor() {
        forwardSession.closeAsync();
    }

    float estimateBytesPerChar(@NonNull File file) {
        return LargeTextPartitionReader.estimateBytesPerChar(file, ReaderActivity.LARGE_TEXT_PREVIEW_BYTES);
    }

    BufferedReader openReader(@NonNull File file) throws IOException {
        return LargeTextPartitionReader.openReader(file, activity.resolveTextEncodingForFile(file));
    }

    int findCharForDisplayLine(File file, int targetLine,
                              java.util.function.BooleanSupplier keepReading) throws IOException {
        long length = file.length(), modified = file.lastModified();
        TextDisplayRuleManager.CompiledRules rules = TextDisplayRuleManager.captureActive(
                activity.getApplicationContext(), file.getAbsolutePath()).compiled;
        TxtBlankLineCollapser.Filter filter =
                new TxtBlankLineCollapser.Filter(activity.largeTextActiveCollapseBlankLines);
        int position;
        try (BufferedReader reader = openReader(file)) {
            position = findCharForDisplayLine(reader, targetLine,
                    raw -> filter.accept(TextDisplayRuleManager.apply(
                            FileUtils.enforceTextPresentationSelectors(raw), rules)), keepReading);
        }
        if (!file.isFile() || file.length() != length || file.lastModified() != modified) {
            throw new IOException("TXT changed while resolving line position");
        }
        return position;
    }

    /** Same displayed newline coordinates as a resident TXT; no full-book buffer. */
    static int findCharForDisplayLine(BufferedReader reader, int targetLine,
                                     java.util.function.Function<String, String> transform,
                                     java.util.function.BooleanSupplier keepReading) throws IOException {
        int remaining = Math.max(1, targetLine);
        long end = 0L;
        boolean emittedAny = false;
        while (true) {
            if (Thread.currentThread().isInterrupted() || !keepReading.getAsBoolean()) {
                throw new java.io.InterruptedIOException("TXT line jump superseded");
            }
            String raw = reader.readLine();
            if (raw == null) return LargeTextCoordinateMath.checkedPosition(end);
            String emitted = transform.apply(raw);
            if (emitted == null) continue;
            long start = end + (emittedAny ? 1L : 0L);
            if (remaining <= 1) return LargeTextCoordinateMath.checkedPosition(start);
            // Rules can insert newlines within a source line. Count those too,
            // rather than interpreting a partition's source-line index as UI lines.
            for (int i = 0; i < emitted.length(); i++) {
                if ((i & 4095) == 0 && (Thread.currentThread().isInterrupted()
                        || !keepReading.getAsBoolean())) {
                    throw new java.io.InterruptedIOException("TXT line jump superseded");
                }
                if (emitted.charAt(i) == '\n' && --remaining == 1) {
                    return LargeTextCoordinateMath.checkedPosition(start + i + 1L);
                }
            }
            end = LargeTextCoordinateMath.checkedPosition(start + emitted.length());
            emittedAny = true;
            remaining--; // separator before the next emitted source line
        }
    }

    int resolveCharPositionForAnchor(@NonNull File file,
                                     int fallbackPosition,
                                     String anchorBefore,
                                     String anchorAfter) throws IOException {
        return LargeTextPartitionReader.findCharPositionForAnchor(
                activity.getApplicationContext(),
                file,
                activity.resolveTextEncodingForFile(file),
                activity.largeTextActiveCollapseBlankLines,
                fallbackPosition,
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
        return forwardSession.read(cursor -> LargeTextPartitionReader.readPartitionAtStartLine(
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
                    cursor));
    }
}
