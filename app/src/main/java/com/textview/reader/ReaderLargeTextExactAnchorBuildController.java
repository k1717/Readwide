package com.textview.reader;

import android.text.TextPaint;

import androidx.annotation.NonNull;

import com.textview.reader.util.FileUtils;
import com.textview.reader.util.LargeTextExactAnchorBuilder;
import com.textview.reader.util.LargeTextPartitionReader;
import com.textview.reader.util.TextDisplayRule;
import com.textview.reader.util.TextDisplayRuleManager;
import com.textview.reader.view.CustomReaderView;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
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
                                                            int expectedTotalLines,
                                                            int expectedTotalChars,
                                                            int partitionLines,
                                                            int lookaheadLines,
                                                            int indexGeneration) throws IOException {
        ArrayList<CustomReaderView.PageTextAnchor> result = new ArrayList<>();
        partitionLines = Math.max(1, partitionLines);
        lookaheadLines = Math.max(0, lookaheadLines);

        ArrayList<String> windowLines = new ArrayList<>(partitionLines + lookaheadLines + 8);
        ArrayList<Integer> windowStarts = new ArrayList<>(partitionLines + lookaheadLines + 8);
        int globalChar = 0;
        int minAllowedExactAnchorChar = 0;
        boolean sawAnyLine = false;

        List<TextDisplayRule> activeRules =
                TextDisplayRuleManager.getActiveRules(activity.getApplicationContext(), loadedFilePath);
        try (BufferedReader reader = LargeTextPartitionReader.openReader(
                source, activity.resolveTextEncodingForFile(source))) {
            String lineText;
            while ((lineText = reader.readLine()) != null) {
                throwIfCancelled(loadedFilePath, indexGeneration);

                sawAnyLine = true;
                String normalized = FileUtils.enforceTextPresentationSelectors(lineText);
                normalized = TextDisplayRuleManager.apply(normalized, activeRules);
                windowStarts.add(globalChar);
                windowLines.add(normalized);
                globalChar += normalized.length() + 1;

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
                            minAllowedExactAnchorChar);
                    int removeCount = Math.min(partitionLines, windowLines.size());
                    windowLines.subList(0, removeCount).clear();
                    windowStarts.subList(0, removeCount).clear();
                }
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
                    minAllowedExactAnchorChar);
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
        if (!activity.largeTextExactPageIndexState.isGenerationCurrent(indexGeneration)
                || activity.activityDestroyed
                || !loadedFilePath.equals(activity.filePath)) {
            throw new IOException("cancelled");
        }
    }

    private int appendPartitionAnchors(@NonNull ArrayList<CustomReaderView.PageTextAnchor> result,
                                       @NonNull ArrayList<String> windowLines,
                                       @NonNull ArrayList<Integer> windowStarts,
                                       int bodyLineCount,
                                       @NonNull TextPaint paintSnapshot,
                                       int layoutWidth,
                                       int viewportHeight,
                                       int marginVertical,
                                       int overlap,
                                       float lineSpacing,
                                       int minAllowedGlobalCharPosition) {
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
                minAllowedGlobalCharPosition);
    }
}
