package com.textview.reader;

import androidx.annotation.NonNull;

import com.textview.reader.model.LargeTextLinePartitionResult;
import com.textview.reader.util.FileUtils;
import com.textview.reader.util.PageIndexCacheManager;
import com.textview.reader.util.TextDisplayRuleManager;

import java.io.File;

final class ReaderLargeTextCacheController {
    private final ReaderActivity activity;

    ReaderLargeTextCacheController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void recordFileAccess(@NonNull File file) {
        try {
            PageIndexCacheManager.getInstance(activity)
                    .recordFileAccess(file, buildTextPageCacheLayoutSignature());
        } catch (RuntimeException ignored) {
            // Cache bookkeeping is best-effort and must never break file opening.
        }
    }

    boolean shouldUseFastOpen(@NonNull File file) {
        return file.isFile()
                && file.length() >= ReaderActivity.LARGE_TEXT_FAST_OPEN_THRESHOLD_BYTES
                && FileUtils.isTextFile(file.getName());
    }

    void clearPartitionCache() {
        activity.largeTextPartitionCache.clear();
    }

    void cachePartition(@NonNull LargeTextLinePartitionResult partition) {
        activity.largeTextPartitionCache.cache(partition);
    }

    LargeTextLinePartitionResult getPartitionByStartLine(int startLine) {
        return activity.largeTextPartitionCache.getNormalByStartLine(
                startLine, activity.getLargeTextPartitionLines());
    }

    LargeTextLinePartitionResult getManualHandoffPartitionByStartLine(int startLine) {
        return activity.largeTextPartitionCache.getManualHandoffByStartLine(
                startLine, activity.getLargeTextPartitionLines());
    }

    LargeTextLinePartitionResult getPartitionForChar(int absoluteCharPosition) {
        return activity.largeTextPartitionCache.getNormalOwnerForChar(absoluteCharPosition);
    }

    boolean markPartitionPrefetchPending(int startLine) {
        return activity.largeTextPartitionCache.markNormalPrefetchPending(
                startLine, activity.getLargeTextPartitionLines());
    }

    boolean markManualHandoffPartitionPrefetchPending(int startLine) {
        return activity.largeTextPartitionCache.markManualHandoffPrefetchPending(
                startLine, activity.getLargeTextPartitionLines());
    }

    void unmarkPartitionPrefetchPending(int startLine) {
        activity.largeTextPartitionCache.unmarkNormalPrefetchPending(
                startLine, activity.getLargeTextPartitionLines());
    }

    void unmarkManualHandoffPartitionPrefetchPending(int startLine) {
        activity.largeTextPartitionCache.unmarkManualHandoffPrefetchPending(
                startLine, activity.getLargeTextPartitionLines());
    }

    private String buildTextPageCacheLayoutSignature() {
        if (activity.prefs == null) return "unknown";

        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        String fontName = activity.prefs.getFontFamily();
        if (fontName == null) fontName = "default";

        int effectiveWidth = activity.readerView != null
                ? activity.readerView.getTextLayoutWidthForIndex()
                : -1;

        return "txt-v5-px-boundary"
                + "|fontSize=" + activity.prefs.getFontSize()
                + "|lineSpacing=" + activity.prefs.getLineSpacing()
                + "|marginH=" + activity.prefs.getMarginHorizontal()
                + "|marginV=" + activity.prefs.getMarginVertical()
                + "|layoutW=" + effectiveWidth
                + "|top=" + activity.prefs.getReaderTextTopOffsetPx()
                + "|bottom=" + activity.prefs.getReaderTextBottomOffsetPx()
                + "|left=" + activity.prefs.getReaderTextLeftInsetPx()
                + "|right=" + activity.prefs.getReaderTextRightInsetPx()
                + "|overlap=" + activity.prefs.getPagingOverlapLines()
                + "|partitionMode=" + activity.getLargeTextPartitionMode()
                + "|partitionLines=" + activity.getLargeTextPartitionLines()
                + "|partitionBuffer=" + activity.getLargeTextPartitionBufferLines()
                + "|font=" + fontName
                + "|displayRules=" + TextDisplayRuleManager.getSignature(
                        activity.getApplicationContext(), activity.filePath)
                + "|screen=" + dm.widthPixels + "x" + dm.heightPixels;
    }
}
