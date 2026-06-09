package com.textview.reader;

import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.textview.reader.model.CachedRestoreTarget;
import com.textview.reader.model.LargeTextLinePartitionResult;
import com.textview.reader.model.ReaderState;
import com.textview.reader.util.FileUtils;
import com.textview.reader.util.TextDisplayRuleManager;

import java.io.File;
import java.util.Locale;

final class ReaderFileLoadController {
    private final ReaderActivity activity;

    ReaderFileLoadController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void loadFileFromIntent(@NonNull Intent sourceIntent) {
        activity.clearPendingToolbarSeekJump();
        String path = sourceIntent.getStringExtra(ReaderActivity.EXTRA_FILE_PATH);
        String uriStr = sourceIntent.getStringExtra(ReaderActivity.EXTRA_FILE_URI);
        boolean samePathReload = path != null && activity.filePath != null
                && new File(path).getAbsolutePath().equals(activity.filePath);

        final int generation = activity.loadGeneration.incrementAndGet();
        activity.largeTextPartitionSwitchGeneration.incrementAndGet();
        activity.loadingWindowPartitionJumpGeneration = -1;
        activity.clearLargeTextPartitionCache();
        activity.activityDestroyed = false;
        if (activity.readerView != null && !samePathReload) {
            activity.readerView.setAlpha(0f);
        }
        activity.showLoadingWindow();

        clearViewerStateForLoad(path, samePathReload);

        int jumpPosition = sourceIntent.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1);
        int jumpDisplayPage = sourceIntent.getIntExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, 0);
        int jumpTotalPages = sourceIntent.getIntExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, 0);
        long requestedPartitionStartByte =
                sourceIntent.getLongExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE, -1L);
        int requestedPartitionStartLine =
                sourceIntent.getIntExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE, -1);
        String jumpAnchorBefore = sourceIntent.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE);
        String jumpAnchorAfter = sourceIntent.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER);
        final android.content.Context appContext = activity.getApplicationContext();

        activity.executor.execute(() -> {
            if (activity.activityDestroyed || generation != activity.loadGeneration.get()) return;
            try {
                LoadedTarget target = resolveTarget(appContext, path, uriStr);
                final File fileToRead = target.file;
                final String finalFilePath = target.path;
                final String finalFileName = target.name;
                final boolean useLargeTextFastOpen = activity.shouldUseLargeTextFastOpen(fileToRead);
                if (useLargeTextFastOpen) {
                    activity.recordLargeTextCacheAccess(fileToRead);
                    openLargeTextPreview(
                            fileToRead,
                            finalFilePath,
                            finalFileName,
                            generation,
                            jumpPosition,
                            jumpDisplayPage,
                            jumpTotalPages,
                            requestedPartitionStartByte,
                            requestedPartitionStartLine,
                            jumpAnchorBefore,
                            jumpAnchorAfter);
                    return;
                }

                openNormalTextFile(
                        appContext,
                        fileToRead,
                        finalFilePath,
                        finalFileName,
                        generation,
                        jumpPosition,
                        jumpAnchorBefore,
                        jumpAnchorAfter);
            } catch (Exception e) {
                postLoadError(generation, e);
            }
        });
    }

    private void clearViewerStateForLoad(String path, boolean samePathReload) {
        activity.fileContent = "";
        activity.appliedTextDisplayRuleSignature = TextDisplayRuleManager.getSignature(
                activity.getApplicationContext(),
                path != null ? new File(path).getAbsolutePath() : activity.filePath);
        activity.activeSearchQuery = "";
        activity.activeSearchIndex = -1;
        activity.activeSearchOrdinal = 0;
        activity.clearLargeTextSearchTotalCache();
        activity.largeTextEstimateActive = false;
        activity.largeTextEstimatedTotalPages = 0;
        activity.clearLargeTextPartitionSwitchPending();
        activity.clearLargeTextQueuedPageDelta();
        activity.resetLargeTextPageDirectionTracking();
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
        if (!samePathReload) {
            activity.resetLargeTextExactPageIndex();
        }
        activity.applySearchHighlight();
        activity.updatePositionLabel();
    }

    @NonNull
    private LoadedTarget resolveTarget(@NonNull android.content.Context appContext,
                                       String path,
                                       String uriStr) throws Exception {
        if (path != null) {
            File file = new File(path);
            return new LoadedTarget(file, file.getAbsolutePath(), file.getName());
        }
        if (uriStr != null) {
            Uri uri = Uri.parse(uriStr);
            String loadedFileName = FileUtils.getFileNameFromUri(appContext, uri);
            File localFile = FileUtils.copyUriToLocal(appContext, uri,
                    loadedFileName != null ? loadedFileName : "opened_file.txt");
            return new LoadedTarget(localFile, localFile.getAbsolutePath(), loadedFileName);
        }
        throw new IllegalArgumentException("No file selected");
    }

    private void openLargeTextPreview(@NonNull File fileToRead,
                                      @NonNull String finalFilePath,
                                      String finalFileName,
                                      int generation,
                                      int jumpPosition,
                                      int jumpDisplayPage,
                                      int jumpTotalPages,
                                      long requestedPartitionStartByte,
                                      int requestedPartitionStartLine,
                                      String jumpAnchorBefore,
                                      String jumpAnchorAfter) throws Exception {
        CachedRestoreTarget restoreTarget = resolveInitialRestoreTarget(
                finalFilePath, jumpPosition, jumpDisplayPage, jumpTotalPages, fileToRead.length());
        int initialRestorePosition = restoreTarget.charPosition;
        float estimatedBytesPerChar = activity.estimateBytesPerChar(fileToRead);
        long fullByteLength = Math.max(1L, fileToRead.length());
        LargeTextLinePartitionResult partition = requestedPartitionStartLine > 0
                ? activity.readLargeTextLinePartitionAtStartLine(fileToRead, requestedPartitionStartLine)
                : activity.readLargeTextLinePartitionForChar(fileToRead, initialRestorePosition);

        String previewContent = partition.content;
        int previewLineCount = partition.lineCount;
        int previewBaseCharOffset = partition.baseCharOffset;
        int partitionBodyStartCharCount = partition.bodyStartCharCount;
        int partitionBodyCharCount = partition.bodyCharCount;
        int estimatedTotalChars = Math.max(partition.totalChars,
                previewBaseCharOffset + previewContent.length());
        long previewStartByte = requestedPartitionStartByte >= 0L ? requestedPartitionStartByte : 0L;
        long partitionEndByte = fullByteLength;

        activity.handler.post(() -> {
            if (!activity.activityDestroyed && generation == activity.loadGeneration.get()) {
                activity.fileApplier().onLargeTextPreviewLoaded(previewContent, previewLineCount,
                        finalFilePath, finalFileName, jumpPosition,
                        fullByteLength,
                        previewStartByte, partitionEndByte, previewBaseCharOffset,
                        estimatedTotalChars, true,
                        restoreTarget.displayPage, restoreTarget.totalPages,
                        jumpAnchorBefore, jumpAnchorAfter,
                        estimatedBytesPerChar, partitionBodyStartCharCount, partitionBodyCharCount,
                        partition.windowStartLine, partition.startLine, partition.endLine, partition.totalLines);
            }
        });
    }

    private void openNormalTextFile(@NonNull android.content.Context appContext,
                                    @NonNull File fileToRead,
                                    @NonNull String finalFilePath,
                                    String finalFileName,
                                    int generation,
                                    int jumpPosition,
                                    String jumpAnchorBefore,
                                    String jumpAnchorAfter) throws Exception {
        String rawContent;
        if (FileUtils.isTextFile(fileToRead.getName())) {
            rawContent = FileUtils.readTextFile(fileToRead, activity.resolveTextEncodingForFile(fileToRead));
        } else {
            rawContent = FileUtils.readReadableFile(appContext, fileToRead);
        }
        final String content = TextDisplayRuleManager.apply(appContext, rawContent, finalFilePath);
        final int lineCount = activity.countLines(content);

        activity.handler.post(() -> {
            if (!activity.activityDestroyed && generation == activity.loadGeneration.get()) {
                activity.fileApplier().onFileLoaded(content, lineCount, finalFilePath, finalFileName, jumpPosition,
                        jumpAnchorBefore, jumpAnchorAfter);
            }
        });
    }

    private void postLoadError(int generation, @NonNull Exception e) {
        activity.handler.post(() -> {
            if (activity.activityDestroyed || generation != activity.loadGeneration.get()) return;
            activity.progressText.setText(String.format(Locale.getDefault(), "%s%s",
                    activity.getString(R.string.error_prefix), e.getMessage()));
            ShortToast.show(activity, activity.getString(R.string.error_prefix) + e.getMessage());
        });
    }

    private CachedRestoreTarget resolveInitialRestoreTarget(String loadedFilePath,
                                                            int jumpPosition,
                                                            int jumpDisplayPage,
                                                            int jumpTotalPages,
                                                            long fileLength) {
        if (jumpPosition >= 0) {
            return new CachedRestoreTarget(jumpPosition, jumpDisplayPage, jumpTotalPages);
        }

        if (activity.prefs != null && activity.prefs.getAutoSavePosition() && loadedFilePath != null) {
            ReaderState state = activity.bookmarkManager != null
                    ? activity.bookmarkManager.getReadingState(loadedFilePath)
                    : null;
            if (state != null) {
                boolean sameLength = state.getFileLength() <= 0L
                        || fileLength <= 0L
                        || state.getFileLength() == fileLength;
                int page = sameLength ? state.getPageNumber() : 0;
                int total = sameLength ? state.getTotalPages() : 0;
                return new CachedRestoreTarget(state.getCharPosition(), page, total);
            }
        }

        return new CachedRestoreTarget(0, 0, 0);
    }

    private static final class LoadedTarget {
        final File file;
        final String path;
        final String name;

        LoadedTarget(@NonNull File file, @NonNull String path, String name) {
            this.file = file;
            this.path = path;
            this.name = name;
        }
    }
}
