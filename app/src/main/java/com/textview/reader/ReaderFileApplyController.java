package com.textview.reader;

import androidx.annotation.NonNull;

import com.textview.reader.model.LargeTextLinePartitionResult;
import com.textview.reader.model.ReaderState;
import com.textview.reader.util.TextDisplayRuleManager;

final class ReaderFileApplyController {
    private final ReaderActivity activity;

    ReaderFileApplyController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void onLargeTextPreviewLoaded(String previewContent,
                                  int previewLineCount,
                                  String loadedFilePath,
                                  String loadedFileName,
                                  int jumpPosition,
                                  long fullByteLength,
                                  long previewStartByte,
                                  long partitionEndByte,
                                  int previewBaseCharOffset,
                                  int estimatedTotalChars,
                                  boolean previewOnly,
                                  int cachedDisplayPage,
                                  int cachedTotalPages,
                                  String jumpAnchorBefore,
                                  String jumpAnchorAfter,
                                  float estimatedBytesPerChar,
                                  int partitionBodyStartCharCount,
                                  int partitionBodyCharCount,
                                  int partitionWindowStartLine,
                                  int partitionStartLine,
                                  int partitionEndLine,
                                  int partitionTotalLines) {
        applyDocumentIdentity(previewContent, previewLineCount, loadedFilePath, loadedFileName);
        applyLargeTextPreviewState(
                fullByteLength,
                previewStartByte,
                partitionEndByte,
                previewBaseCharOffset,
                estimatedTotalChars,
                previewOnly,
                cachedDisplayPage,
                cachedTotalPages,
                estimatedBytesPerChar,
                partitionBodyStartCharCount,
                partitionBodyCharCount,
                partitionWindowStartLine,
                partitionStartLine,
                partitionEndLine,
                partitionTotalLines);
        renderLoadedContent(true);

        activity.readerView.post(() -> finishLargeTextPreviewLayout(
                jumpPosition,
                cachedDisplayPage,
                cachedTotalPages,
                jumpAnchorBefore,
                jumpAnchorAfter));
    }

    void onFileLoaded(String content,
                      int lineCount,
                      String loadedFilePath,
                      String loadedFileName,
                      int jumpPosition,
                      String jumpAnchorBefore,
                      String jumpAnchorAfter) {
        boolean replacingLargePreview = activity.largeTextEstimateActive;
        int preservePosition = replacingLargePreview ? activity.getCurrentCharPosition() : -1;
        int deferredRestorePosition = activity.pendingLargeTextRestorePosition;

        applyDocumentIdentity(content, lineCount, loadedFilePath, loadedFileName);
        applyNormalTextState();
        renderLoadedContent(false);

        activity.readerView.post(() -> finishNormalTextLayout(
                replacingLargePreview,
                preservePosition,
                deferredRestorePosition,
                jumpPosition,
                jumpAnchorBefore,
                jumpAnchorAfter));
    }

    private void applyDocumentIdentity(String content,
                                       int lineCount,
                                       String loadedFilePath,
                                       String loadedFileName) {
        activity.filePath = loadedFilePath;
        activity.appliedTextDisplayRuleSignature = TextDisplayRuleManager.getSignature(
                activity.getApplicationContext(), activity.filePath);
        activity.fileName = loadedFileName != null
                ? loadedFileName
                : activity.getString(R.string.app_name);
        activity.updateReaderFileTitle();
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle(activity.fileName);
        }
        activity.fileContent = content != null ? content : "";
        activity.totalChars = activity.fileContent.length();
        activity.totalLines = lineCount;
    }

    private void applyLargeTextPreviewState(long fullByteLength,
                                            long previewStartByte,
                                            long partitionEndByte,
                                            int previewBaseCharOffset,
                                            int estimatedTotalChars,
                                            boolean previewOnly,
                                            int cachedDisplayPage,
                                            int cachedTotalPages,
                                            float estimatedBytesPerChar,
                                            int partitionBodyStartCharCount,
                                            int partitionBodyCharCount,
                                            int partitionWindowStartLine,
                                            int partitionStartLine,
                                            int partitionEndLine,
                                            int partitionTotalLines) {
        activity.largeTextEstimateActive = true;
        activity.largeTextEstimatedTotalPages = 0;
        activity.clearLargeTextPartitionSwitchPending();
        activity.pendingLargeTextRestorePosition = -1;
        activity.largeTextPreviewBaseCharOffset = Math.max(0, previewBaseCharOffset);
        activity.largeTextEstimatedBasePageOffset = 0;
        activity.largeTextEstimatedTotalChars = Math.max(activity.fileContent.length(), estimatedTotalChars);
        activity.hugeTextPreviewOnly = previewOnly;
        activity.pendingLargeTextCachedDisplayPage = Math.max(0, cachedDisplayPage);
        activity.pendingLargeTextCachedTotalPages = Math.max(0, cachedTotalPages);
        activity.largeTextPartitionStartByte = Math.max(0L, previewStartByte);
        activity.largeTextPartitionEndByte = Math.max(activity.largeTextPartitionStartByte, partitionEndByte);
        activity.largeTextFileByteLength = Math.max(1L, fullByteLength);
        activity.largeTextEstimatedBytesPerChar = Math.max(1f, estimatedBytesPerChar);
        activity.largeTextPartitionBodyStartCharCount = Math.max(0,
                Math.min(activity.fileContent.length(), partitionBodyStartCharCount));
        activity.largeTextPartitionBodyCharCount = Math.max(activity.largeTextPartitionBodyStartCharCount,
                Math.min(activity.fileContent.length(), partitionBodyCharCount));
        activity.largeTextPartitionWindowStartLine = Math.max(1, partitionWindowStartLine);
        activity.largeTextActivePartitionUsesLookbehind = false;
        activity.largeTextPartitionStartLine = Math.max(1, partitionStartLine);
        activity.largeTextPartitionEndLine = Math.max(activity.largeTextPartitionStartLine, partitionEndLine);
        activity.largeTextTotalLogicalLines = Math.max(1, partitionTotalLines);
        activity.cacheLargeTextPartition(new LargeTextLinePartitionResult(
                activity.fileContent,
                activity.totalLines,
                activity.largeTextPartitionStartLine,
                activity.largeTextPartitionEndLine,
                activity.largeTextTotalLogicalLines,
                activity.largeTextPreviewBaseCharOffset,
                activity.largeTextPartitionBodyStartCharCount,
                activity.largeTextPartitionBodyCharCount,
                activity.largeTextPartitionWindowStartLine,
                false,
                activity.largeTextEstimatedTotalChars));
    }

    private void applyNormalTextState() {
        activity.largeTextEstimateActive = false;
        activity.largeTextEstimatedTotalPages = 0;
        activity.clearLargeTextPartitionSwitchPending();
        activity.pendingLargeTextRestorePosition = -1;
        activity.largeTextPreviewBaseCharOffset = 0;
        activity.largeTextEstimatedBasePageOffset = 0;
        activity.largeTextEstimatedTotalChars = 0;
        activity.hugeTextPreviewOnly = false;
        activity.pendingLargeTextCachedDisplayPage = 0;
        activity.pendingLargeTextCachedTotalPages = 0;
        activity.largeTextPartitionStartByte = 0L;
        activity.largeTextPartitionEndByte = 0L;
        activity.largeTextFileByteLength = 0L;
        activity.largeTextEstimatedBytesPerChar = 1f;
        activity.largeTextPartitionBodyStartCharCount = 0;
        activity.largeTextPartitionBodyCharCount = 0;
        activity.largeTextPartitionWindowStartLine = 1;
        activity.largeTextPartitionStartLine = 1;
        activity.largeTextPartitionEndLine = 1;
        activity.largeTextTotalLogicalLines = 1;
        activity.resetLargeTextExactPageIndex();
        activity.clearLargeTextPartitionCache();
    }

    private void renderLoadedContent(boolean largeTextPartitionMode) {
        activity.readerView.setLargeTextPartitionMode(largeTextPartitionMode);
        activity.readerView.setOverlapLines(activity.prefs.getPagingOverlapLines());
        activity.readerView.setTextContent(activity.fileContent);
        activity.applySearchHighlight();
    }

    private void finishLargeTextPreviewLayout(int jumpPosition,
                                              int cachedDisplayPage,
                                              int cachedTotalPages,
                                              String jumpAnchorBefore,
                                              String jumpAnchorAfter) {
        if (activity.activityDestroyed) return;

        int restorePosition = resolveInitialRestorePosition(jumpPosition);
        if (restorePosition >= 0 && !restoreLargeTextPreviewPosition(
                restorePosition,
                cachedDisplayPage,
                cachedTotalPages,
                jumpAnchorBefore,
                jumpAnchorAfter)) {
            return;
        }

        updateLargeTextEstimatedPageModel();
        activity.updatePositionLabel();
        activity.scheduleLargeTextExactPageIndexingRestart();
        activity.prefetchNeighborLargeTextPartitions();
        activity.readerView.setAlpha(1f);
        activity.hideLoadingWindow();
    }

    private int resolveInitialRestorePosition(int jumpPosition) {
        if (jumpPosition >= 0) return jumpPosition;
        if (activity.prefs.getAutoSavePosition() && activity.filePath != null) {
            ReaderState state = activity.bookmarkManager.getReadingState(activity.filePath);
            if (state != null) return state.getCharPosition();
        }
        return -1;
    }

    private boolean restoreLargeTextPreviewPosition(int restorePosition,
                                                    int cachedDisplayPage,
                                                    int cachedTotalPages,
                                                    String jumpAnchorBefore,
                                                    String jumpAnchorAfter) {
        int anchoredRestorePosition = activity.resolveAnchoredAbsolutePosition(
                activity.fileContent,
                activity.largeTextPreviewBaseCharOffset,
                restorePosition,
                jumpAnchorBefore,
                jumpAnchorAfter);
        int localRestorePosition = anchoredRestorePosition - activity.largeTextPreviewBaseCharOffset;
        if (localRestorePosition >= 0 && localRestorePosition < activity.fileContent.length()) {
            activity.scrollToCharPosition(anchoredRestorePosition);
            return true;
        }
        if (localRestorePosition < 0 && activity.hasPreviousLargeTextPartition()) {
            activity.reloadLargeTextPreviewAround(
                    anchoredRestorePosition,
                    cachedDisplayPage,
                    cachedTotalPages,
                    jumpAnchorBefore,
                    jumpAnchorAfter,
                    activity.getLargeTextPartitionStartLineForLine(
                            activity.largeTextPartitionStartLine - activity.getLargeTextPartitionLines()));
            return false;
        }
        if (localRestorePosition >= activity.fileContent.length() && activity.hasNextLargeTextPartition()) {
            activity.reloadLargeTextPreviewAround(
                    anchoredRestorePosition,
                    cachedDisplayPage,
                    cachedTotalPages,
                    jumpAnchorBefore,
                    jumpAnchorAfter,
                    activity.getLargeTextPartitionStartLineForLine(activity.largeTextPartitionEndLine + 1));
            return false;
        }
        activity.pendingLargeTextRestorePosition = anchoredRestorePosition;
        return true;
    }

    private void updateLargeTextEstimatedPageModel() {
        int bodyPages = activity.getLastLocalPageStartingInsideLargeTextPartition();
        float ratio = activity.largeTextTotalLogicalLines
                / (float) Math.max(1, activity.getLargeTextPartitionLines());
        activity.largeTextEstimatedTotalPages = Math.max(bodyPages,
                Math.round(Math.max(1, bodyPages) * ratio));
        activity.largeTextEstimatedBasePageOffset = Math.max(0, Math.min(
                Math.max(0, activity.largeTextEstimatedTotalPages - 1),
                Math.round(((activity.largeTextPartitionStartLine - 1)
                        / (float) Math.max(1, activity.largeTextTotalLogicalLines))
                        * activity.largeTextEstimatedTotalPages)));

        if (activity.pendingLargeTextCachedDisplayPage > 0) {
            int localPage = Math.max(1, activity.readerView.getCurrentPageNumber());
            int cachedTotal = activity.pendingLargeTextCachedTotalPages > 0
                    ? activity.pendingLargeTextCachedTotalPages
                    : activity.largeTextEstimatedTotalPages;
            activity.largeTextEstimatedTotalPages = Math.max(localPage, cachedTotal);
            activity.largeTextEstimatedBasePageOffset = Math.max(0,
                    Math.min(Math.max(0, activity.largeTextEstimatedTotalPages - localPage),
                            activity.pendingLargeTextCachedDisplayPage - localPage));
        }
    }

    private void finishNormalTextLayout(boolean replacingLargePreview,
                                        int preservePosition,
                                        int deferredRestorePosition,
                                        int jumpPosition,
                                        String jumpAnchorBefore,
                                        String jumpAnchorAfter) {
        if (jumpPosition >= 0) {
            int anchoredJumpPosition = activity.resolveAnchoredAbsolutePosition(
                    activity.fileContent, 0, jumpPosition, jumpAnchorBefore, jumpAnchorAfter);
            activity.scrollToCharPosition(anchoredJumpPosition);
        } else if (deferredRestorePosition >= 0) {
            activity.scrollToCharPosition(deferredRestorePosition);
        } else if (replacingLargePreview && preservePosition >= 0) {
            activity.scrollToCharPosition(preservePosition);
        } else if (activity.prefs.getAutoSavePosition()) {
            ReaderState state = activity.bookmarkManager.getReadingState(activity.filePath);
            if (state != null) activity.scrollToCharPosition(state.getCharPosition());
        }
        activity.updatePositionLabel();
        activity.readerView.setAlpha(1f);
        activity.hideLoadingWindow();
    }
}
