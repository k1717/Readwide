package com.textview.reader.util;

import com.textview.reader.view.CustomReaderView;

import java.util.ArrayList;

/**
 * Pure anchor math used by the TXT large-text exact page model.
 *
 * <p>This class intentionally has no Activity/View lifecycle dependencies so the
 * arithmetic can be regression-tested independently from ReaderActivity.</p>
 */
public final class LargeTextAnchorMath {
    private static final int EXACT_TAP_EPS_CHARS = 8;

    private LargeTextAnchorMath() {
    }

    public static int findExactPageForChar(ArrayList<CustomReaderView.PageTextAnchor> anchors,
                                           int charPosition) {
        if (anchors == null || anchors.isEmpty()) return 1;
        int target = Math.max(0, charPosition);
        int lo = 0;
        int hi = anchors.size() - 1;
        int best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int anchor = anchors.get(mid).charPosition;
            if (target >= anchor) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return Math.max(1, Math.min(anchors.size(), best + 1));
    }

    public static int findTapTargetAnchorIndex(ArrayList<CustomReaderView.PageTextAnchor> anchors,
                                               int currentAbs,
                                               int direction) {
        if (anchors == null || anchors.isEmpty() || direction == 0) return -1;

        int currentIndex = findAnchorIndexAtOrBefore(anchors, Math.max(0, currentAbs));
        if (currentIndex < 0) {
            return direction > 0 ? 0 : -1;
        }

        if (direction > 0) {
            // Forward tap should advance to the immediate next exact page anchor.
            // Do not add an epsilon to currentAbs before searching: when the user
            // manually scrolls close to the next anchor, that epsilon can jump over
            // the next page and create a one-page skip.  The current interval already
            // tells us which exact page owns the top row.
            int target = currentIndex + 1;
            return target < anchors.size() ? target : -1;
        }

        int currentAnchor = Math.max(0, anchors.get(currentIndex).charPosition);
        if (Math.max(0, currentAbs) > currentAnchor + EXACT_TAP_EPS_CHARS) {
            // If the user is inside the current page, previous tap first snaps back
            // to that page's exact start.  Only a second previous tap moves to the
            // previous page.  This prevents skipping page N when the viewport is a
            // few characters/one line below page N's anchor.
            return currentIndex;
        }

        int target = currentIndex - 1;
        return target >= 0 ? target : -1;
    }

    public static int findAnchorIndexAtOrBefore(ArrayList<CustomReaderView.PageTextAnchor> anchors,
                                                int charPosition) {
        if (anchors == null || anchors.isEmpty()) return -1;
        int target = Math.max(0, charPosition);
        int lo = 0;
        int hi = anchors.size() - 1;
        int best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int anchor = Math.max(0, anchors.get(mid).charPosition);
            if (anchor <= target) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }
}
