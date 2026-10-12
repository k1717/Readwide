package com.readwide.manager;

import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.LargeTextCoordinateMath;
import com.readwide.manager.util.LargeTextExactAnchorBuilder;
import com.readwide.manager.util.TextDisplayRule;
import com.readwide.manager.util.TextDisplayRuleManager;
import com.readwide.manager.util.TxtBlankLineCollapser;
import com.readwide.manager.view.CustomReaderView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

final class ReaderLargeTextExactAnchorBuildController {
    private final ReaderActivity activity;

    ReaderLargeTextExactAnchorBuildController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    ArrayList<CustomReaderView.PageTextAnchor> buildChunked(@NonNull File source,
                                                            @NonNull String loadedFilePath,
                                                            @NonNull TextPaint paintSnapshot,
                                                            int layoutWidth,
                                                            int viewportHeight,
                                                            int marginVertical,
                                                            int overlap,
                                                            float lineSpacing,
                                                            boolean markdownHighlightingEnabled,
                                                            int readerTextColor,
                                                            int readerBackgroundColor,
                                                            int expectedTotalLines,
                                                            int expectedTotalChars,
                                                            int partitionLines,
                                                            int lookaheadLines,
                                                            int indexGeneration,
                                                            boolean collapseBlankLines,
                                                            @NonNull String encoding,
                                                            @NonNull List<TextDisplayRule> activeRules,
                                                            @Nullable MessageDigest sourceDigest) throws IOException {
        ArrayList<CustomReaderView.PageTextAnchor> result = new ArrayList<>();
        partitionLines = Math.max(1, partitionLines);
        lookaheadLines = Math.max(0, lookaheadLines);

        ArrayList<String> windowLines = new ArrayList<>(partitionLines + lookaheadLines + 8);
        ArrayList<Integer> windowStarts = new ArrayList<>(partitionLines + lookaheadLines + 8);
        long globalChar = 0L;
        long minAllowedExactAnchorChar = 0L;
        boolean sawAnyLine = false;

        TextDisplayRuleManager.CompiledRules compiledRules =
                TextDisplayRuleManager.compile(activeRules);
        TxtBlankLineCollapser.Filter collapseFilter = new TxtBlankLineCollapser.Filter(
                collapseBlankLines);
        Charset charset = Charset.forName(encoding);
        // Same decoder/buffer as openReader; hash the bytes already being consumed.
        try (InputStream input = new FileInputStream(source);
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                sourceDigest != null ? new DigestInputStream(input, sourceDigest) : input,
                charset), 64 * 1024)) {
            String lineText;
            while ((lineText = reader.readLine()) != null) {
                throwIfCancelled(loadedFilePath, indexGeneration);

                sawAnyLine = true;
                String normalized = FileUtils.enforceTextPresentationSelectors(lineText);
                normalized = TextDisplayRuleManager.apply(normalized, compiledRules);
                String emitted = collapseFilter.accept(normalized);
                if (emitted == null) continue;
                normalized = emitted;
                int lineStart = LargeTextCoordinateMath.checkedPosition(globalChar);
                long nextLineStart = LargeTextCoordinateMath.advancePastLine(
                        globalChar, normalized.length());

                // This next emitted line proves that the buffered window is not
                // the document tail. Flush before adding it so the canonical
                // body/lookahead layout input remains exactly the same size.
                while (windowLines.size() >= partitionLines + lookaheadLines) {
                    minAllowedExactAnchorChar = appendPartitionAnchors(
                            result,
                            windowLines,
                            windowStarts,
                            partitionLines,
                            paintSnapshot,
                            layoutWidth,
                            viewportHeight,
                            marginVertical,
                            overlap,
                            lineSpacing,
                            markdownHighlightingEnabled,
                            readerTextColor,
                            readerBackgroundColor,
                            minAllowedExactAnchorChar,
                            false);
                    int removeCount = Math.min(partitionLines, windowLines.size());
                    windowLines.subList(0, removeCount).clear();
                    windowStarts.subList(0, removeCount).clear();
                }
                windowStarts.add(lineStart);
                windowLines.add(normalized);
                globalChar = nextLineStart;
            }
        }

        while (!windowLines.isEmpty()) {
            throwIfCancelled(loadedFilePath, indexGeneration);

            int bodyLineCount = Math.min(partitionLines, windowLines.size());
            minAllowedExactAnchorChar = appendPartitionAnchors(
                    result,
                    windowLines,
                    windowStarts,
                    bodyLineCount,
                    paintSnapshot,
                    layoutWidth,
                    viewportHeight,
                    marginVertical,
                    overlap,
                    lineSpacing,
                    markdownHighlightingEnabled,
                    readerTextColor,
                    readerBackgroundColor,
                    minAllowedExactAnchorChar,
                    true);
            int removeCount = Math.min(partitionLines, windowLines.size());
            windowLines.subList(0, removeCount).clear();
            windowStarts.subList(0, removeCount).clear();
        }

        if (!sawAnyLine || result.isEmpty()) {
            result.add(new CustomReaderView.PageTextAnchor(0, "", ""));
        }
        return result;
    }

    private void throwIfCancelled(@NonNull String loadedFilePath, int indexGeneration) throws IOException {
        if (Thread.currentThread().isInterrupted()
                || !activity.largeTextExactPageIndexState.isGenerationCurrent(indexGeneration)
                || activity.activityDestroyed
                || !loadedFilePath.equals(activity.filePath)) {
            throw new java.io.InterruptedIOException("cancelled");
        }
    }

    private long appendPartitionAnchors(@NonNull ArrayList<CustomReaderView.PageTextAnchor> result,
                                       @NonNull ArrayList<String> windowLines,
                                       @NonNull ArrayList<Integer> windowStarts,
                                       int bodyLineCount,
                                       @NonNull TextPaint paintSnapshot,
                                       int layoutWidth,
                                       int viewportHeight,
                                       int marginVertical,
                                       int overlap,
                                       float lineSpacing,
                                       boolean markdownHighlightingEnabled,
                                       int readerTextColor,
                                       int readerBackgroundColor,
                                       long minAllowedGlobalCharPosition,
                                       boolean reachesDocumentEnd) throws IOException {
        return LargeTextExactAnchorBuilder.appendPartitionAnchors(
                result,
                windowLines,
                windowStarts,
                bodyLineCount,
                paintSnapshot,
                layoutWidth,
                viewportHeight,
                marginVertical,
                overlap,
                lineSpacing,
                markdownHighlightingEnabled,
                readerTextColor,
                readerBackgroundColor,
                minAllowedGlobalCharPosition,
                reachesDocumentEnd);
    }
}
