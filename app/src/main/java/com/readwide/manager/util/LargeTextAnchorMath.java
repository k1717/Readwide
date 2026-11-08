package com.readwide.manager.util;

import com.readwide.manager.view.CustomReaderView;

import java.util.ArrayList;

/**
 * Anchor math for exact pages in large TXT files.
 *
 * <p>No Activity/View lifecycle dependencies; the arithmetic can be tested
 * independently from ReaderActivity.</p>
 */
public final class LargeTextAnchorMath {
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
            // The current interval identifies the top row's page. Move to its next
            // anchor. Adding an epsilon to currentAbs near a boundary can skip a page.
            int target = currentIndex + 1;
            return target < anchors.size() ? target : -1;
        }

        int currentAnchor = Math.max(0, anchors.get(currentIndex).charPosition);
        if (Math.max(0, currentAbs) > currentAnchor) {
            // Any row after the anchor, including a blank or short row, belongs to
            // this page. As in the normal reader, return to its start first;
            // a tap at the anchor moves to the previous page.
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
