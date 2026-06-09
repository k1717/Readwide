package com.textview.reader;

import android.graphics.Typeface;
import android.text.TextPaint;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileUtils;
import com.textview.reader.util.FontManager;
import com.textview.reader.util.LargeTextAnchorMath;
import com.textview.reader.util.LargeTextContinuityMath;
import com.textview.reader.util.LargeTextExactPageIndexState;
import com.textview.reader.util.TextDisplayRuleManager;
import com.textview.reader.view.CustomReaderView;

import java.io.File;
import java.util.ArrayList;

final class ReaderLargeTextExactPageIndexController {
    private static final long INDEX_RESTART_DEBOUNCE_MS = 200L;

    private final ReaderActivity activity;
    private String lastSeenStableLayoutSignature = "";

    ReaderLargeTextExactPageIndexController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void reset() {
        activity.largeTextExactPageIndexState.reset();
    }

    void invalidateBuild() {
        activity.largeTextExactPageIndexState.invalidate();
    }

    boolean isReady() {
        return activity.largeTextExactPageIndexState.isReady();
    }

    boolean isFailed() {
        return activity.largeTextExactPageIndexState.isFailed();
    }

    int readyPageCount() {
        return activity.largeTextExactPageIndexState.readyPageCount();
    }

    @NonNull
    ArrayList<CustomReaderView.PageTextAnchor> copyAnchors() {
        return activity.largeTextExactPageIndexState.copyAnchors();
    }

    @Nullable
    ArrayList<CustomReaderView.PageTextAnchor> copyAnchorsIfUsable(@NonNull String currentSignature) {
        return activity.largeTextExactPageIndexState.copyAnchorsIfUsable(currentSignature);
    }

    int findPageForChar(int charPosition) {
        return LargeTextAnchorMath.findExactPageForChar(copyAnchors(), charPosition);
    }

    int findPageForChar(@NonNull ArrayList<CustomReaderView.PageTextAnchor> anchors, int charPosition) {
        return LargeTextAnchorMath.findExactPageForChar(anchors, charPosition);
    }

    @Nullable
    CustomReaderView.PageTextAnchor getAnchorForPage(int page) {
        ArrayList<CustomReaderView.PageTextAnchor> anchors = copyAnchors();
        if (anchors.isEmpty()) return null;
        int index = Math.max(0, Math.min(anchors.size() - 1, page - 1));
        return anchors.get(index);
    }

    int estimateCharPositionForLine(int targetLine) {
        return LargeTextContinuityMath.estimateCharPositionForLine(
                targetLine,
                activity.largeTextTotalLogicalLines,
                activity.largeTextEstimatedTotalChars);
    }

    @NonNull
    String buildCurrentSignature(@NonNull String loadedFilePath) {
        if (activity.readerView == null) return "";
        return buildSignature(
                loadedFilePath,
                activity.readerView.getTextLayoutWidthForIndex(),
                activity.readerView.getViewportHeight(),
                activity.readerView.getMarginVerticalPxForIndex(),
                activity.readerView.getOverlapLinesForIndex(),
                activity.readerView.getLineSpacingMultiplierForIndex(),
                activity.readerView.copyTextPaintForIndex(),
                activity.getLargeTextPartitionLines(),
                activity.getLargeTextPartitionBufferLines());
    }

    void handleRestartIndexingTick() {
        if (activity.activityDestroyed) return;
        if (!activity.largeTextEstimateActive) return;
        String snapshot = activity.filePath;
        if (snapshot == null || activity.readerView == null) return;

        String currentLayoutSig = buildLayoutOnlySignature();
        if (currentLayoutSig.isEmpty()) {
            lastSeenStableLayoutSignature = "";
            activity.handler.removeCallbacks(activity.largeTextRestartIndexingRunnable);
            activity.handler.postDelayed(activity.largeTextRestartIndexingRunnable, INDEX_RESTART_DEBOUNCE_MS);
            return;
        }
        if (!currentLayoutSig.equals(lastSeenStableLayoutSignature)) {
            lastSeenStableLayoutSignature = currentLayoutSig;
            activity.handler.removeCallbacks(activity.largeTextRestartIndexingRunnable);
            activity.handler.postDelayed(activity.largeTextRestartIndexingRunnable, INDEX_RESTART_DEBOUNCE_MS);
            return;
        }
        reset();
        startIndexing(snapshot);
    }

    void scheduleRestart() {
        if (activity.activityDestroyed) return;
        if (!activity.largeTextEstimateActive) return;
        if (activity.handler == null) return;
        lastSeenStableLayoutSignature = "";
        activity.handler.removeCallbacks(activity.largeTextRestartIndexingRunnable);
        activity.handler.postDelayed(activity.largeTextRestartIndexingRunnable, INDEX_RESTART_DEBOUNCE_MS);
    }

    void scheduleRestartForUserPageModelChange() {
        if (activity.activityDestroyed) return;
        if (!activity.largeTextEstimateActive) return;
        if (activity.handler == null) return;
        reset();
        recomputeEstimatedTotalForCurrentViewport();
        activity.updatePositionLabel();
        scheduleRestart();
    }

    private void recomputeEstimatedTotalForCurrentViewport() {
        if (!activity.largeTextEstimateActive || activity.readerView == null) return;
        if (activity.largeTextTotalLogicalLines <= 0) return;
        int bodyPages = activity.getLastLocalPageStartingInsideLargeTextPartition();
        if (bodyPages <= 0) return;

        float ratio = activity.largeTextTotalLogicalLines
                / (float) Math.max(1, activity.getLargeTextPartitionLines());
        int newEstimate = Math.max(bodyPages, Math.round(Math.max(1, bodyPages) * ratio));
        if (newEstimate <= 0) return;

        activity.largeTextEstimatedTotalPages = newEstimate;
        activity.largeTextEstimatedBasePageOffset = Math.max(0, Math.min(
                Math.max(0, activity.largeTextEstimatedTotalPages - 1),
                Math.round(((activity.largeTextPartitionStartLine - 1)
                        / (float) Math.max(1, activity.largeTextTotalLogicalLines))
                        * activity.largeTextEstimatedTotalPages)));
    }

    private String buildLayoutOnlySignature() {
        if (activity.readerView == null) return "";
        int width = activity.readerView.getTextLayoutWidthForIndex();
        int height = activity.readerView.getViewportHeight();
        if (width <= 0 || height <= 0) return "";
        return "txt-v5-px-boundary"
                + "|w=" + width
                + "|h=" + height
                + "|mv=" + activity.readerView.getMarginVerticalPxForIndex()
                + "|ol=" + activity.readerView.getOverlapLinesForIndex()
                + "|ls=" + quantizeFloatForSignature(activity.readerView.getLineSpacingMultiplierForIndex());
    }

    private String buildSignature(@NonNull String loadedFilePath,
                                  int layoutWidth,
                                  int viewportHeight,
                                  int marginVertical,
                                  int overlap,
                                  float lineSpacing,
                                  @NonNull TextPaint paintSnapshot,
                                  int partitionLines,
                                  int partitionBufferLines) {
        File source = new File(loadedFilePath);
        String stableTypefaceKey;
        try {
            stableTypefaceKey = FontManager.getInstance().getStableTypefaceKey(
                    activity.prefs != null ? activity.prefs.getFontFamily() : "default");
        } catch (RuntimeException ignored) {
            Typeface typeface = paintSnapshot.getTypeface();
            stableTypefaceKey = typeface == null ? "default" : ("style:" + typeface.getStyle());
        }

        return "txt-v5-px-boundary"
                + "|" + source.getAbsolutePath()
                + "|len=" + source.length()
                + "|mod=" + source.lastModified()
                + "|w=" + layoutWidth
                + "|h=" + viewportHeight
                + "|mv=" + marginVertical
                + "|ol=" + overlap
                + "|partitionMode=" + activity.getLargeTextPartitionMode()
                + "|partitionLines=" + Math.max(1, partitionLines)
                + "|partitionBuffer=" + Math.max(0, partitionBufferLines)
                + "|ls=" + quantizeFloatForSignature(lineSpacing)
                + "|ts=" + quantizeFloatForSignature(paintSnapshot.getTextSize())
                + "|sx=" + quantizeFloatForSignature(paintSnapshot.getTextScaleX())
                + "|tf=" + stableTypefaceKey
                + "|displayRules=" + TextDisplayRuleManager.getSignature(activity.getApplicationContext(), loadedFilePath);
    }

    private static String quantizeFloatForSignature(float value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    void startIndexing(@NonNull String loadedFilePath) {
        if (activity.readerView == null) return;

        final int layoutWidth = activity.readerView.getTextLayoutWidthForIndex();
        final int viewportHeight = activity.readerView.getViewportHeight();
        final int marginVertical = activity.readerView.getMarginVerticalPxForIndex();
        final int overlap = activity.readerView.getOverlapLinesForIndex();
        final float lineSpacing = activity.readerView.getLineSpacingMultiplierForIndex();
        final TextPaint paintSnapshot = activity.readerView.copyTextPaintForIndex();
        final int partitionLines = activity.getLargeTextPartitionLines();
        final int partitionBufferLines = activity.getLargeTextPartitionBufferLines();
        final int lookaheadLines = partitionBufferLines;
        final File source = new File(loadedFilePath);
        final String indexSignature = buildSignature(
                loadedFilePath, layoutWidth, viewportHeight, marginVertical, overlap, lineSpacing, paintSnapshot,
                partitionLines, partitionBufferLines);
        final int indexGeneration = activity.largeTextExactPageIndexState.beginBuild(indexSignature);
        if (indexGeneration < 0) return;

        final int expectedTotalLines = Math.max(1, activity.largeTextTotalLogicalLines);
        final int expectedTotalChars = Math.max(0, activity.largeTextEstimatedTotalChars);

        activity.executor.execute(() -> {
            ArrayList<CustomReaderView.PageTextAnchor> builtAnchors = new ArrayList<>();
            boolean buildFailed = false;
            String failureReason = "";
            try {
                builtAnchors = activity.exactAnchorBuilder().buildChunked(
                        source,
                        loadedFilePath,
                        paintSnapshot,
                        layoutWidth,
                        viewportHeight,
                        marginVertical,
                        overlap,
                        lineSpacing,
                        expectedTotalLines,
                        expectedTotalChars,
                        partitionLines,
                        lookaheadLines,
                        indexGeneration);
            } catch (Throwable t) {
                buildFailed = true;
                failureReason = t.getClass().getSimpleName();
                builtAnchors.clear();
            }

            final ArrayList<CustomReaderView.PageTextAnchor> finalAnchors = builtAnchors;
            final boolean finalBuildFailed = buildFailed;
            final String finalFailureReason = failureReason;
            activity.handler.post(() -> finishBuild(
                    loadedFilePath,
                    indexSignature,
                    indexGeneration,
                    finalAnchors,
                    finalBuildFailed,
                    finalFailureReason));
        });
    }

    private void finishBuild(@NonNull String loadedFilePath,
                             @NonNull String indexSignature,
                             int indexGeneration,
                             @NonNull ArrayList<CustomReaderView.PageTextAnchor> finalAnchors,
                             boolean finalBuildFailed,
                             @NonNull String finalFailureReason) {
        String currentSignature = loadedFilePath.equals(activity.filePath)
                ? buildCurrentSignature(loadedFilePath)
                : "";
        boolean shouldUpdatePosition = false;
        boolean shouldRescheduleForStableLayout = false;
        LargeTextExactPageIndexState state = activity.largeTextExactPageIndexState;
        if (!state.isCurrent(indexGeneration, indexSignature)) return;
        if (activity.activityDestroyed
                || !loadedFilePath.equals(activity.filePath)
                || !indexSignature.equals(currentSignature)) {
            state.discardCurrent(indexGeneration, indexSignature);
            shouldRescheduleForStableLayout = !activity.activityDestroyed
                    && loadedFilePath.equals(activity.filePath);
        } else {
            LargeTextExactPageIndexState.CommitResult commit =
                    state.completeCurrent(
                            indexGeneration,
                            indexSignature,
                            finalAnchors,
                            finalBuildFailed,
                            finalFailureReason);
            if (!commit.current) return;
            if (commit.ready) {
                activity.largeTextEstimatedTotalPages = Math.max(1, commit.pageCount);
            }
            shouldUpdatePosition = true;
        }
        if (shouldRescheduleForStableLayout) {
            scheduleRestart();
            return;
        }
        if (shouldUpdatePosition) {
            activity.syncCurrentFileBookmarksToLargeTextExactPageModel();
            activity.updatePositionLabel();
            activity.prefetchNeighborLargeTextPartitions();
            activity.processQueuedLargeTextPageDeltaAfterPartitionApply();
        }
    }

    void showEstimatedJumpToast() {
        boolean failed = isFailed();
        ShortToast.show(activity, activity.getString(failed
                        ? R.string.large_text_exact_page_index_failed_estimated_jump
                        : R.string.large_text_exact_page_index_not_ready_estimated_jump));
    }
}
