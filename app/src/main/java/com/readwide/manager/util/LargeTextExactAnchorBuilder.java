package com.readwide.manager.util;

import android.text.TextPaint;

import androidx.annotation.NonNull;

import com.readwide.manager.view.CustomReaderView;
import com.readwide.manager.view.MarkdownSyntaxHighlighter;

import java.util.ArrayList;
import java.io.IOException;

/**
 * Partition-local exact page anchor construction for large TXT files.
 *
 * The builder deliberately clamps anchors to the canonical body of each runtime
 * partition. Lookahead may help the live renderer make the seam smooth, but it
 * must not create extra exact-page anchors that would duplicate content during
 * tap/page navigation.
 */
public final class LargeTextExactAnchorBuilder {
    private LargeTextExactAnchorBuilder() {}

    public static long appendPartitionAnchors(
            @NonNull ArrayList<CustomReaderView.PageTextAnchor> result,
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
            long minAllowedGlobalCharPosition) throws IOException {
        return appendPartitionAnchors(result, windowLines, windowStarts, bodyLineCount,
                paintSnapshot, layoutWidth, viewportHeight, marginVertical, overlap, lineSpacing,
                markdownHighlightingEnabled, readerTextColor, readerBackgroundColor,
                minAllowedGlobalCharPosition, true);
    }

    /** A loaded window ends the document only after the source reader confirms EOF. */
    public static long appendPartitionAnchors(
            @NonNull ArrayList<CustomReaderView.PageTextAnchor> result,
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
        if (windowLines.isEmpty() || windowStarts.isEmpty() || bodyLineCount <= 0) {
            return Math.max(0, minAllowedGlobalCharPosition);
        }

        int clampedBodyLines = Math.max(1, Math.min(bodyLineCount, windowLines.size()));
        int baseCharOffset = LargeTextCoordinateMath.checkedPosition(windowStarts.get(0));
        String partitionContent = LargeTextPartitionReader.joinWindow(windowLines);
        long minAllowed = Math.max(0L, minAllowedGlobalCharPosition);
        int windowEndGlobal = LargeTextCoordinateMath.checkedPosition(
                (long) baseCharOffset + partitionContent.length());
        // Keep the consumed marker distinct from a legitimate final blank-row
        // anchor even when the document end is Integer.MAX_VALUE.
        long consumedPastEnd = (long) windowEndGlobal + 1L;
        // A preceding terminal window may already have shown this entire tail.
        // Check before the empty-window case: one remaining blank line is also
        // consumed, but an entirely empty document must retain its first anchor.
        // Equality can be a required anchor at a final empty layout row; only
        // a position past the end means that row was already fully visible.
        if (reachesDocumentEnd && !result.isEmpty() && minAllowed > windowEndGlobal) {
            return minAllowed;
        }
        if (partitionContent.isEmpty()) {
            if (result.isEmpty() || (reachesDocumentEnd && minAllowed <= baseCharOffset)) {
                addAnchor(result, baseCharOffset, partitionContent, baseCharOffset);
            }
            return reachesDocumentEnd ? Math.max(minAllowed, consumedPastEnd) : minAllowed;
        }

        int bodyCharCount;
        if (clampedBodyLines < windowStarts.size()) {
            // The first lookahead line starts after the body separator newline, but
            // live partition bodyCharCount excludes that synthetic seam separator.
            bodyCharCount = Math.max(0, windowStarts.get(clampedBodyLines) - baseCharOffset - 1);
        } else {
            bodyCharCount = partitionContent.length();
        }
        bodyCharCount = Math.max(0, Math.min(partitionContent.length(), bodyCharCount));

        CharSequence partitionLayoutContent = markdownHighlightingEnabled
                ? MarkdownSyntaxHighlighter.apply(partitionContent, readerTextColor, readerBackgroundColor)
                : partitionContent;

        int[] localOffsets = CustomReaderView.buildPageStartOffsets(
                partitionLayoutContent,
                partitionContent,
                paintSnapshot,
                layoutWidth,
                viewportHeight,
                marginVertical,
                overlap,
                lineSpacing,
                reachesDocumentEnd);

        long nextMinAllowed = minAllowed;
        int bodyEndGlobal = baseCharOffset + Math.max(0, bodyCharCount);
        boolean retainedBodyAnchor = false;
        boolean hasOutgoingAnchor = false;

        // If the previous partition's real next-page start landed inside this
        // partition body, make that handoff point an explicit page anchor. This
        // keeps the exact page map equal to the sequential tap-reading path.
        boolean ownsWindowEnd = clampedBodyLines == windowLines.size();
        boolean incomingTerminalBlank = reachesDocumentEnd && ownsWindowEnd
                && minAllowed == windowEndGlobal && partitionContent.endsWith("\n");
        if ((minAllowed > baseCharOffset && minAllowed < bodyEndGlobal) || incomingTerminalBlank) {
            addAnchor(result, (int) minAllowed, partitionContent, baseCharOffset);
            retainedBodyAnchor = true;
        }

        for (int localOffset : localOffsets) {
            int localPos = Math.max(0, Math.min(partitionContent.length(), localOffset));
            int globalPos = baseCharOffset + localPos;
            if (globalPos < minAllowed) {
                continue;
            }
            boolean terminalBlankAnchor = reachesDocumentEnd && ownsWindowEnd
                    && localPos == partitionContent.length();
            if (localPos >= bodyCharCount && bodyCharCount > 0 && !terminalBlankAnchor) {
                int handoffGlobal = globalPos;
                if (localPos == bodyCharCount && globalPos < baseCharOffset + partitionContent.length()) {
                    // The body edge itself is the separator newline between the
                    // current body and the first lookahead line. Runtime handoff
                    // steps over this invisible separator, so exact anchors should
                    // do the same.
                    handoffGlobal += 1;
                }
                nextMinAllowed = Math.max(nextMinAllowed, handoffGlobal);
                hasOutgoingAnchor = true;
                break;
            }
            addAnchor(result, globalPos, partitionContent, baseCharOffset);
            retainedBodyAnchor = true;
        }
        if (reachesDocumentEnd && retainedBodyAnchor && !hasOutgoingAnchor) {
            // The final body-owned page already covers the terminal lookahead.
            // Draining those remaining source lines must not manufacture a new
            // page at the next canonical body's start. Nonterminal windows keep
            // their usual overlap/handoff arithmetic instead.
            nextMinAllowed = Math.max(nextMinAllowed, consumedPastEnd);
        }
        return nextMinAllowed;
    }

    private static void addAnchor(@NonNull ArrayList<CustomReaderView.PageTextAnchor> result,
                                  int globalCharPosition,
                                  @NonNull String partitionContent,
                                  int baseCharOffset) {
        int globalPos = Math.max(0, globalCharPosition);
        if (!result.isEmpty() && globalPos <= result.get(result.size() - 1).charPosition) {
            return;
        }
        int localPos = Math.max(0, Math.min(partitionContent.length(), globalPos - Math.max(0, baseCharOffset)));
        localPos = FileUtils.clampToSurrogateSafeStart(partitionContent, localPos);
        int beforeStart = Math.max(0, localPos - 80);
        int afterEnd = (int) Math.min(partitionContent.length(), (long) localPos + 120L);
        result.add(new CustomReaderView.PageTextAnchor(
                globalPos,
                FileUtils.safeSubstring(partitionContent, beforeStart, localPos),
                FileUtils.safeSubstring(partitionContent, localPos, afterEnd)));
    }
}
