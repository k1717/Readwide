package com.textview.reader.util;

import android.text.TextPaint;

import androidx.annotation.NonNull;

import com.textview.reader.view.CustomReaderView;

import java.util.ArrayList;

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

    public static int appendPartitionAnchors(
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
            int minAllowedGlobalCharPosition) {
        if (windowLines.isEmpty() || windowStarts.isEmpty() || bodyLineCount <= 0) {
            return Math.max(0, minAllowedGlobalCharPosition);
        }

        int clampedBodyLines = Math.max(1, Math.min(bodyLineCount, windowLines.size()));
        int baseCharOffset = Math.max(0, windowStarts.get(0));
        String partitionContent = LargeTextPartitionReader.joinWindow(windowLines);
        if (partitionContent.isEmpty()) {
            if (result.isEmpty()) result.add(new CustomReaderView.PageTextAnchor(baseCharOffset, "", ""));
            return Math.max(0, minAllowedGlobalCharPosition);
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

        ArrayList<CustomReaderView.PageTextAnchor> localAnchors = CustomReaderView.buildPageTextAnchors(
                partitionContent,
                paintSnapshot,
                layoutWidth,
                viewportHeight,
                marginVertical,
                overlap,
                lineSpacing);

        int minAllowed = Math.max(0, minAllowedGlobalCharPosition);
        int nextMinAllowed = minAllowed;
        int bodyEndGlobal = baseCharOffset + Math.max(0, bodyCharCount);

        // If the previous partition's real next-page start landed inside this
        // partition body, make that handoff point an explicit page anchor. This
        // keeps the exact page map equal to the sequential tap-reading path.
        if (minAllowed > baseCharOffset && minAllowed < bodyEndGlobal) {
            addAnchor(result, minAllowed, partitionContent, baseCharOffset);
        }

        for (CustomReaderView.PageTextAnchor local : localAnchors) {
            int localPos = Math.max(0, Math.min(partitionContent.length(), local.charPosition));
            int globalPos = baseCharOffset + localPos;
            if (globalPos < minAllowed) {
                continue;
            }
            if (localPos >= bodyCharCount && bodyCharCount > 0) {
                int handoffGlobal = globalPos;
                if (localPos == bodyCharCount && globalPos < baseCharOffset + partitionContent.length()) {
                    // The body edge itself is the separator newline between the
                    // current body and the first lookahead line. Runtime handoff
                    // steps over this invisible separator, so exact anchors should
                    // do the same.
                    handoffGlobal += 1;
                }
                nextMinAllowed = Math.max(nextMinAllowed, handoffGlobal);
                break;
            }
            addAnchor(result, globalPos, partitionContent, baseCharOffset);
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
        int afterEnd = Math.min(partitionContent.length(), localPos + 120);
        result.add(new CustomReaderView.PageTextAnchor(
                globalPos,
                FileUtils.safeSubstring(partitionContent, beforeStart, localPos),
                FileUtils.safeSubstring(partitionContent, localPos, afterEnd)));
    }
}
