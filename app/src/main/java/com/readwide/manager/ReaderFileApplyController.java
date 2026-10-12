package com.readwide.manager;

import android.content.Intent;
import android.view.View;

import androidx.annotation.NonNull;

import com.readwide.manager.model.LargeTextLinePartitionResult;
import com.readwide.manager.model.ReaderState;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.TextDisplayRuleManager;
import com.readwide.manager.util.TextSearchMath;
import com.readwide.manager.view.CustomReaderView;

final class ReaderFileApplyController {
    private final ReaderActivity activity;
    private CustomReaderView pendingLayoutView;
    private View.OnLayoutChangeListener pendingLayoutListener;
    private Runnable pendingLayoutCheck;

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
        cancelPendingLayout();
        activity.textContentReadyForPersistence = false;
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

        finishWhenLayoutReady(() -> {
            finishLargeTextPreviewLayout(
                jumpPosition,
                cachedDisplayPage,
                cachedTotalPages,
                jumpAnchorBefore,
                jumpAnchorAfter);
        });
    }

    void onFileLoaded(String content,
                      int lineCount,
                      String loadedFilePath,
                      String loadedFileName,
                      int jumpPosition,
                      String jumpAnchorBefore,
                      String jumpAnchorAfter) {
        cancelPendingLayout();
        activity.textContentReadyForPersistence = false;
        boolean replacingLargePreview = activity.largeTextEstimateActive;
        int preservePosition = replacingLargePreview ? activity.getCurrentCharPosition() : -1;
        int deferredRestorePosition = activity.pendingLargeTextRestorePosition;

        applyDocumentIdentity(content, lineCount, loadedFilePath, loadedFileName);
        applyNormalTextState();
        renderLoadedContent(false);

        finishWhenLayoutReady(() -> {
            finishNormalTextLayout(
                replacingLargePreview,
                preservePosition,
                deferredRestorePosition,
                jumpPosition,
                jumpAnchorBefore,
                jumpAnchorAfter);
        });
    }

    /** Keep first-load completion owned by the adopted text, not a queued post. */
    private void finishWhenLayoutReady(Runnable finish) {
        final CustomReaderView targetView = activity.readerView;
        if (targetView == null) return;
        final int loadGeneration = activity.loadGeneration.get();
        final int switchGeneration = activity.largeTextPartitionSwitchGeneration.get();
        final String expectedPath = activity.filePath;
        final String expectedContent = activity.fileContent;
        pendingLayoutView = targetView;
        pendingLayoutCheck = () -> {
            if (activity.activityDestroyed || loadGeneration != activity.loadGeneration.get()
                    || switchGeneration != activity.largeTextPartitionSwitchGeneration.get()
                    || targetView != activity.readerView || expectedContent != activity.fileContent
                    || targetView.getTextContent() != expectedContent
                    || !java.util.Objects.equals(expectedPath, activity.filePath)) {
                cancelPendingLayout();
            } else if (targetView.hasCurrentTextLayout()) {
                // Landing can synchronously notify scrolling. Detach before reentry.
                cancelPendingLayout();
                if (!activity.ensureLoadedTextCurrent()) return;
                finish.run();
            }
        };
        pendingLayoutListener = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                finishPendingLayoutIfReady();
        targetView.addOnLayoutChangeListener(pendingLayoutListener);
        // Sized views rebuild synchronously in setTextContent; no frame delay needed.
        finishPendingLayoutIfReady();
    }

    void finishPendingLayoutIfReady() {
        // Style/inset changes can make geometry usable without changing View bounds.
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

    private void applyDocumentIdentity(String content,
                                       int lineCount,
                                       String loadedFilePath,
                                       String loadedFileName) {
        activity.filePath = loadedFilePath;
        // The loader has already published the transform and source stamps
        // captured for these bytes. Do not replace them with live preferences.
        activity.fileName = com.readwide.manager.util.FileUtils.displayNameOrBasename(
                loadedFileName, loadedFilePath, activity.getString(R.string.app_name));
        activity.updateReaderFileTitle();
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle(activity.fileName);
        }
        // Open with the controls (and the theme-colored file title) visible,
        // matching the document and PDF viewers - the text reader was the only
        // viewer that opened fully immersive, which read as "this file has no
        // title". Keyed by file path: a different file arriving via
        // onNewIntent (singleTop) reveals again; reloads and encoding changes
        // of the same file never force the controls back on.
        if (!java.util.Objects.equals(activity.chromeRevealedForPath, activity.filePath)) {
            activity.chromeRevealedForPath = activity.filePath;
            activity.showToolbar();
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
        String markdownName = activity.filePath != null && !activity.filePath.isEmpty()
                ? activity.filePath
                : activity.fileName;
        activity.readerView.setMarkdownHighlightingEnabled(FileUtils.isMarkdownFile(markdownName));
        activity.readerView.setTextContent(activity.fileContent,
                activity.loadedTextWindowReachesDocumentEnd());
        activity.refreshTxtAnnotationHighlights();
        activity.applySearchHighlight();
    }

    private void finishLargeTextPreviewLayout(int jumpPosition,
                                              int cachedDisplayPage,
                                              int cachedTotalPages,
                                              String jumpAnchorBefore,
                                              String jumpAnchorAfter) {
        if (activity.activityDestroyed) return;

        int restorePosition = resolveInitialRestorePosition(jumpPosition);
        String resumeAnchorBefore = jumpAnchorBefore;
        String resumeAnchorAfter = jumpAnchorAfter;
        // Auto-resume opens carry no explicit anchor. Fall back to the anchor saved with
        // the reading state so the position is recovered by passage text when the saved
        // char offset has drifted (collapse toggle, encoding, on-disk edit). The matcher
        // prefers the occurrence nearest the saved offset and falls back to it when the
        // anchor is missing, so this never moves an otherwise-exact restore.
        if (jumpPosition < 0
                && (resumeAnchorBefore == null || resumeAnchorBefore.isEmpty())
                && (resumeAnchorAfter == null || resumeAnchorAfter.isEmpty())
                && activity.prefs.getAutoSavePosition() && activity.filePath != null) {
            ReaderState resumeState = activity.bookmarkManager.getReadingState(activity.filePath);
            if (resumeState != null) {
                resumeAnchorBefore = resumeState.getAnchorTextBefore();
                resumeAnchorAfter = resumeState.getAnchorTextAfter();
            }
        }
        if (restorePosition >= 0 && !restoreLargeTextPreviewPosition(
                restorePosition,
                cachedDisplayPage,
                cachedTotalPages,
                resumeAnchorBefore,
                resumeAnchorAfter)) {
            return;
        }

        updateLargeTextEstimatedPageModel();
        int completedLoad = activity.loadGeneration.get();
        // Check saved exact anchors as soon as the first partition is laid out.
        // The debounced callback below still handles later inset/layout changes.
        if (activity.filePath != null) activity.startLargeTextExactPageIndexing(activity.filePath);
        if (completedLoad != activity.loadGeneration.get()) return;
        activity.updatePositionLabel();
        activity.scheduleLargeTextExactPageIndexingRestart();
        activity.prefetchNeighborLargeTextPartitions();
        if (completedLoad != activity.loadGeneration.get()) return;
        activity.textContentReadyForPersistence = true;
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
        // The general in-window resolver clamps an unmatched fallback to this
        // buffer. Initial restoration must retain an outside character so the
        // loader can select its real owner instead of saving a buffer-edge jump.
        int fallbackLocal = (int) Math.max(0L, Math.min(activity.fileContent.length(),
                (long) restorePosition - activity.largeTextPreviewBaseCharOffset));
        int matchedLocal = TextSearchMath.findBestAnchorPosition(activity.fileContent,
                fallbackLocal, jumpAnchorBefore, jumpAnchorAfter);
        int anchoredRestorePosition = matchedLocal >= 0
                ? activity.largeTextPreviewBaseCharOffset + matchedLocal
                : Math.max(0, restorePosition);
        int localRestorePosition = anchoredRestorePosition - activity.largeTextPreviewBaseCharOffset;
        if (localRestorePosition >= 0 && localRestorePosition < activity.fileContent.length()) {
            activity.scrollToCharPosition(anchoredRestorePosition);
            return true;
        }
        if ((localRestorePosition < 0 && activity.hasPreviousLargeTextPartition())
                || (localRestorePosition >= activity.fileContent.length()
                        && activity.hasNextLargeTextPartition())) {
            // This is still the initial load: an ordinary in-place switch does
            // not reveal its alpha-zero view, close its loading overlay or start
            // its exact index. Re-enter the owned load lifecycle at the resolved
            // character, without carrying a stale partition hint forward.
            Intent reload = new Intent(activity.getIntent());
            reload.putExtra(ReaderActivity.EXTRA_FILE_PATH, activity.filePath);
            reload.removeExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE);
            reload.removeExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE);
            reload.putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, anchoredRestorePosition);
            reload.putExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, cachedDisplayPage);
            reload.putExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, cachedTotalPages);
            reload.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE, jumpAnchorBefore);
            reload.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER, jumpAnchorAfter);
            activity.loadFileFromIntent(reload);
            return false;
        }
        // No neighboring window can own this coordinate (notably EOF after
        // truncation). Land now: large TXT never receives the normal/full-text
        // callback that would consume pendingLargeTextRestorePosition later.
        activity.scrollToCharPosition(ReaderLargeTextPartitionApplier.clampResolvedPosition(
                anchoredRestorePosition, activity.largeTextPreviewBaseCharOffset,
                activity.fileContent, activity.loadedTextWindowReachesDocumentEnd()));
        activity.pendingLargeTextRestorePosition = -1;
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
            if (state != null) {
                int resumePosition = activity.resolveAnchoredAbsolutePosition(
                        activity.fileContent, 0, state.getCharPosition(),
                        state.getAnchorTextBefore(), state.getAnchorTextAfter());
                activity.scrollToCharPosition(resumePosition);
            }
        }
        activity.updatePositionLabel();
        activity.textContentReadyForPersistence = true;
        activity.readerView.setAlpha(1f);
        activity.hideLoadingWindow();
    }
}
