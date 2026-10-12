package com.readwide.manager;

import android.graphics.Typeface;
import android.text.TextPaint;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.FontManager;
import com.readwide.manager.util.LargeTextAnchorMath;
import com.readwide.manager.util.LargeTextContinuityMath;
import com.readwide.manager.util.LargeTextExactPageIndexState;
import com.readwide.manager.util.LargeTextPageIndexCache;
import com.readwide.manager.util.PageIndexCacheManager;
import com.readwide.manager.util.TextDisplayRule;
import com.readwide.manager.util.TextDisplayRuleManager;
import com.readwide.manager.util.TextPageFontIdentity;
import com.readwide.manager.view.CustomReaderView;
import com.readwide.manager.view.MarkdownSyntaxHighlighter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class ReaderLargeTextExactPageIndexController {
    private static final long INDEX_RESTART_DEBOUNCE_MS = 200L;
    // Retire cached anchors and bookmark page metadata made before the EOF overlap fix.
    private static final String PAGE_MODEL_REVISION = "txt-v6-eof-overlap";

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
        return hasUsableTextLayout() && activity.largeTextExactPageIndexState.isReady();
    }

    boolean isFailed() {
        return activity.largeTextExactPageIndexState.isFailed();
    }

    int readyPageCount() {
        return hasUsableTextLayout() ? activity.largeTextExactPageIndexState.readyPageCount() : 0;
    }

    @NonNull
    ArrayList<CustomReaderView.PageTextAnchor> copyAnchors() {
        return hasUsableTextLayout() ? activity.largeTextExactPageIndexState.copyAnchors() : new ArrayList<>();
    }

    @Nullable
    ArrayList<CustomReaderView.PageTextAnchor> copyAnchorsIfUsable(@NonNull String currentSignature) {
        return hasUsableTextLayout()
                ? activity.largeTextExactPageIndexState.copyAnchorsIfUsable(currentSignature) : null;
    }

    int findPageForChar(int charPosition) {
        return hasUsableTextLayout() ? activity.largeTextExactPageIndexState.findPageForChar(charPosition) : 1;
    }

    int findPageForChar(@NonNull ArrayList<CustomReaderView.PageTextAnchor> anchors, int charPosition) {
        return LargeTextAnchorMath.findExactPageForChar(anchors, charPosition);
    }

    @Nullable
    CustomReaderView.PageTextAnchor getAnchorForPage(int page) {
        return hasUsableTextLayout() ? activity.largeTextExactPageIndexState.anchorForPage(page) : null;
    }

    private boolean hasUsableTextLayout() {
        // The width getter used by builders is clamped to 1. It cannot prove
        // that the view actually has room for the current text and geometry.
        return activity.readerView != null && activity.readerView.hasCurrentTextLayout();
    }

    int estimateCharPositionForLine(int targetLine) {
        return LargeTextContinuityMath.estimateCharPositionForLine(
                targetLine,
                activity.largeTextTotalLogicalLines,
                activity.largeTextEstimatedTotalChars);
    }

    @NonNull
    String buildCurrentSignature(@NonNull String loadedFilePath) {
        if (!hasUsableTextLayout()) return "";
        return buildSignature(
                loadedFilePath,
                activity.readerView.getTextLayoutWidthForIndex(),
                activity.readerView.getViewportHeight(),
                activity.readerView.getMarginVerticalPxForIndex(),
                activity.readerView.getOverlapLinesForIndex(),
                activity.readerView.getLineSpacingMultiplierForIndex(),
                activity.readerView.copyTextPaintForIndex(),
                activity.readerView.isMarkdownHighlightingEnabledForIndex(),
                activity.readerView.getReaderTextColorForIndex(),
                activity.readerView.getReaderBackgroundColorForIndex(),
                activity.getLargeTextPartitionLines(),
                activity.getLargeTextPartitionBufferLines(),
                activity.largeTextActiveCollapseBlankLines,
                TextDisplayRuleManager.getExactSignature(activity.getApplicationContext(), loadedFilePath));
    }

    void handleRestartIndexingTick() {
        if (activity.activityDestroyed) return;
        if (!activity.largeTextEstimateActive) return;
        String snapshot = activity.filePath;
        if (snapshot == null) return;

        invalidateMismatchedPageModel();

        String currentLayoutSig = buildLayoutOnlySignature();
        if (currentLayoutSig.isEmpty()) {
            lastSeenStableLayoutSignature = "";
            activity.handler.removeCallbacks(activity.largeTextRestartIndexingRunnable);
            // A layout/preference event restarts indexing when usable. Polling
            // cannot repair zero space or a released/not-yet-rendered view.
            return;
        }
        if (!currentLayoutSig.equals(lastSeenStableLayoutSignature)) {
            lastSeenStableLayoutSignature = currentLayoutSig;
            activity.handler.removeCallbacks(activity.largeTextRestartIndexingRunnable);
            activity.handler.postDelayed(activity.largeTextRestartIndexingRunnable, INDEX_RESTART_DEBOUNCE_MS);
            return;
        }
        // beginBuild already reuses a matching ready/running index. Resetting here
        // would throw it away even when this was only a redundant layout callback.
        startIndexing(snapshot);
    }

    void scheduleRestart() {
        if (activity.activityDestroyed) return;
        if (!activity.largeTextEstimateActive) return;
        if (activity.handler == null) return;
        // Geometry/font callbacks run synchronously after the view changes. The
        // debounce delays expensive indexing, never retirement of old anchors:
        // slider/goto/labels must not navigate with a previous layout meanwhile.
        invalidateMismatchedPageModel();
        lastSeenStableLayoutSignature = "";
        activity.handler.removeCallbacks(activity.largeTextRestartIndexingRunnable);
        if (!hasUsableTextLayout()) return;
        activity.handler.postDelayed(activity.largeTextRestartIndexingRunnable, INDEX_RESTART_DEBOUNCE_MS);
    }

    void scheduleRestartForUserPageModelChange() {
        if (activity.activityDestroyed) return;
        if (!activity.largeTextEstimateActive) return;
        if (activity.handler == null) return;
        invalidateMismatchedPageModel();
        if (!isReady()) {
            recomputeEstimatedTotalForCurrentViewport();
            activity.updatePositionLabel();
        }
        scheduleRestart();
    }

    private boolean invalidateMismatchedPageModel() {
        if (activity.filePath == null) return false;
        boolean invalidated = activity.largeTextExactPageIndexState.invalidateIfSignatureChanged(
                buildCurrentSignature(activity.filePath));
        if (invalidated) activity.updatePositionLabel();
        return invalidated;
    }

    private void recomputeEstimatedTotalForCurrentViewport() {
        if (!activity.largeTextEstimateActive || !hasUsableTextLayout()) return;
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
        if (!hasUsableTextLayout()) return "";
        int width = activity.readerView.getTextLayoutWidthForIndex();
        int height = activity.readerView.getViewportHeight();
        if (width <= 0 || height <= 0) return "";
        return PAGE_MODEL_REVISION
                + "|w=" + width
                + "|h=" + height
                + "|mv=" + activity.readerView.getMarginVerticalPxForIndex()
                + "|ol=" + activity.readerView.getOverlapLinesForIndex()
                + "|markdown=" + (activity.readerView.isMarkdownHighlightingEnabledForIndex()
                    ? MarkdownSyntaxHighlighter.PAGE_LAYOUT_REVISION : "0")
                + "|ls=" + quantizeFloatForSignature(activity.readerView.getLineSpacingMultiplierForIndex());
    }

    private String buildSignature(@NonNull String loadedFilePath,
                                  int layoutWidth,
                                  int viewportHeight,
                                  int marginVertical,
                                  int overlap,
                                  float lineSpacing,
                                  @NonNull TextPaint paintSnapshot,
                                  boolean markdownHighlightingEnabled,
                                  int readerTextColor,
                                  int readerBackgroundColor,
                                  int partitionLines,
                                  int partitionBufferLines,
                                  boolean collapseBlankLines,
                                  @NonNull String displayRuleSignature) {
        File source = new File(loadedFilePath);
        String stableTypefaceKey;
        try {
            stableTypefaceKey = FontManager.getInstance().getStableTypefaceKey(
                    activity.prefs != null ? activity.prefs.getFontFamily() : "default");
        } catch (RuntimeException ignored) {
            Typeface typeface = paintSnapshot.getTypeface();
            stableTypefaceKey = typeface == null ? "default" : ("style:" + typeface.getStyle());
        }
        stableTypefaceKey += ":resolved:" + TextPageFontIdentity.key(paintSnapshot.getTypeface());

        return PAGE_MODEL_REVISION
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
                + "|collapseBlank=" + collapseBlankLines
                + "|ls=" + quantizeFloatForSignature(lineSpacing)
                + "|ts=" + quantizeFloatForSignature(paintSnapshot.getTextSize())
                + "|sx=" + quantizeFloatForSignature(paintSnapshot.getTextScaleX())
                + "|tf=" + stableTypefaceKey
                + "|markdown=" + (markdownHighlightingEnabled
                    ? MarkdownSyntaxHighlighter.pageLayoutCacheKey(readerTextColor, readerBackgroundColor) : "0")
                + "|encoding=" + cachedEncoding(source)
                + "|skew=" + quantizeFloatForSignature(paintSnapshot.getTextSkewX())
                + "|letterSpacing=" + quantizeFloatForSignature(paintSnapshot.getLetterSpacing())
                + "|flags=" + paintSnapshot.getFlags()
                + "|locales=" + paintSnapshot.getTextLocales().toLanguageTags()
                + "|features=" + paintSnapshot.getFontFeatureSettings()
                + "|displayRules=" + displayRuleSignature;
    }

    private static String quantizeFloatForSignature(float value) {
        // Do not merge distinct pixel metrics merely because they round to four decimals.
        return Float.toHexString(value);
    }

    private String cachedEncoding(File source) {
        if (activity.prefs == null) return "";
        String manual = FileUtils.normalizeManualEncodingName(activity.prefs.getManualTextEncodingForFile(source));
        if (manual != null) return manual;
        String automatic = FileUtils.normalizeManualEncodingName(activity.prefs.getCachedAutoTextEncodingForFile(source));
        return automatic != null ? automatic : "";
    }

    void startIndexing(@NonNull String loadedFilePath) {
        if (!activity.ensureLoadedTextCurrent()) return;
        if (!hasUsableTextLayout()) {
            invalidateMismatchedPageModel();
            return;
        }

        final int layoutWidth = activity.readerView.getTextLayoutWidthForIndex();
        final int viewportHeight = activity.readerView.getViewportHeight();
        final int marginVertical = activity.readerView.getMarginVerticalPxForIndex();
        final int overlap = activity.readerView.getOverlapLinesForIndex();
        final float lineSpacing = activity.readerView.getLineSpacingMultiplierForIndex();
        final TextPaint paintSnapshot = activity.readerView.copyTextPaintForIndex();
        final boolean markdownHighlightingEnabled = activity.readerView.isMarkdownHighlightingEnabledForIndex();
        final int readerTextColor = activity.readerView.getReaderTextColorForIndex();
        final int readerBackgroundColor = activity.readerView.getReaderBackgroundColorForIndex();
        final int partitionLines = activity.getLargeTextPartitionLines();
        final int partitionBufferLines = activity.getLargeTextPartitionBufferLines();
        final int lookaheadLines = partitionBufferLines;
        final File source = new File(loadedFilePath);
        final long sourceLength = source.length(), sourceModified = source.lastModified();
        final String encodingSnapshot = cachedEncoding(source);
        final List<TextDisplayRule> activeRules =
                TextDisplayRuleManager.getActiveRules(activity.getApplicationContext(), loadedFilePath);
        // Build and sign the exact page index against the collapse basis captured for the
        // loaded content, not live prefs, so the index anchors match the displayed text and
        // the stored signature stays consistent with buildCurrentSignature.
        final boolean collapseBlankLines = activity.largeTextActiveCollapseBlankLines;
        final String indexSignature = buildSignature(
                loadedFilePath, layoutWidth, viewportHeight, marginVertical, overlap, lineSpacing, paintSnapshot,
                markdownHighlightingEnabled, readerTextColor, readerBackgroundColor,
                partitionLines, partitionBufferLines, collapseBlankLines,
                TextDisplayRuleManager.getExactSignature(activeRules));
        final int indexGeneration = activity.largeTextExactPageIndexState.beginBuild(indexSignature);
        if (indexGeneration < 0) return;

        // Styled TXT uses the same exact builder on a miss. Unidentified/file-backed fonts
        // carry a process/actual-face token in indexSignature, so their on-disk
        // entries cannot be mistaken for reusable indexes after a process restart.
        final boolean persistIndex = !encodingSnapshot.isEmpty();
        final String diskSignature = indexSignature
                + "|android=" + android.os.Build.FINGERPRINT
                + "|configuration=" + LargeTextPageIndexCache.configurationKey(
                        activity.getResources().getConfiguration().toString());

        final int expectedTotalLines = Math.max(1, activity.largeTextTotalLogicalLines);
        final int expectedTotalChars = Math.max(0, activity.largeTextEstimatedTotalChars);

        activity.executor.execute(() -> {
            ArrayList<CustomReaderView.PageTextAnchor> builtAnchors = new ArrayList<>();
            boolean buildFailed = false;
            String failureReason = "";
            try {
                LargeTextPageIndexCache.Checkpoint checkpoint = () -> {
                    if (Thread.currentThread().isInterrupted() || activity.activityDestroyed
                            || !loadedFilePath.equals(activity.filePath)
                            || !activity.largeTextExactPageIndexState.isGenerationCurrent(indexGeneration)) {
                        throw new java.io.InterruptedIOException("TXT indexing cancelled");
                    }
                };
                checkpoint.check();
                File cacheFile = null;
                ArrayList<CustomReaderView.PageTextAnchor> cached = null;
                if (persistIndex) {
                    try {
                        cacheFile = PageIndexCacheManager.getInstance(activity)
                                .getExactPageCacheFile(source);
                        if (cacheFile.isFile()) {
                            cached = LargeTextPageIndexCache.load(cacheFile, diskSignature,
                                    expectedTotalChars, checkpoint,
                                    () -> LargeTextPageIndexCache.fingerprint(source, checkpoint));
                        }
                    } catch (IOException | RuntimeException cacheUnavailable) {
                        checkpoint.check(); // cancellation must not become an uncancelled rebuild
                    }
                }
                if (cached != null) {
                    builtAnchors = cached;
                } else {
                    checkpoint.check();
                    if (!activity.largeTextExactPageIndexState.beginCalculation(indexGeneration, indexSignature)) {
                        throw new java.io.InterruptedIOException("TXT index was replaced");
                    }
                    activity.handler.post(() -> {
                        if (!activity.activityDestroyed && loadedFilePath.equals(activity.filePath)
                                && activity.largeTextExactPageIndexState.isCurrent(indexGeneration, indexSignature)) {
                            activity.updatePositionLabel();
                        }
                    });
                    java.security.MessageDigest digest = cacheFile != null ? LargeTextPageIndexCache.newDigest() : null;
                    String encoding = encodingSnapshot.isEmpty()
                            ? activity.resolveTextEncodingForFile(source) : encodingSnapshot;
                    builtAnchors = activity.exactAnchorBuilder().buildChunked(
                            source,
                            loadedFilePath,
                            paintSnapshot,
                            layoutWidth,
                            viewportHeight,
                            marginVertical,
                            overlap,
                            lineSpacing,
                            markdownHighlightingEnabled,
                            readerTextColor,
                            readerBackgroundColor,
                            expectedTotalLines,
                            expectedTotalChars,
                            partitionLines,
                            lookaheadLines,
                            indexGeneration,
                            collapseBlankLines, encoding, activeRules, digest);
                    checkpoint.check();
                    requireUnchangedSource(source, sourceLength, sourceModified);
                    if (cacheFile != null) {
                        try {
                            LargeTextPageIndexCache.save(cacheFile, diskSignature, digest.digest(),
                                    expectedTotalChars, builtAnchors, checkpoint);
                        } catch (IOException | RuntimeException cacheUnavailable) {
                            checkpoint.check(); // cache failure alone never invalidates exact anchors
                        }
                    }
                }
                checkpoint.check();
                requireUnchangedSource(source, sourceLength, sourceModified);
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

    private static void requireUnchangedSource(File source, long length, long modified) throws IOException {
        if (!source.isFile() || source.length() != length || source.lastModified() != modified) {
            throw new IOException("TXT changed while indexing pages");
        }
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
        if (!activity.activityDestroyed && loadedFilePath.equals(activity.filePath)
                && !activity.ensureLoadedTextCurrent()) return;
        if (activity.activityDestroyed
                || !loadedFilePath.equals(activity.filePath)
                || !indexSignature.equals(currentSignature)) {
            state.discardCurrent(indexGeneration, indexSignature);
            shouldRescheduleForStableLayout = !activity.activityDestroyed
                    && loadedFilePath.equals(activity.filePath)
                    && hasUsableTextLayout();
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
