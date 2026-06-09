package com.textview.reader;

import androidx.annotation.NonNull;

import com.textview.reader.model.Bookmark;

final class ReaderBookmarkNavigator {
    private final ReaderActivity activity;

    ReaderBookmarkNavigator(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void jumpToBookmark(Bookmark bookmark) {
        if (bookmark == null) return;

        if (activity.largeTextEstimateActive
                && !activity.isAbsoluteCharPositionInCurrentLargeTextBody(bookmark.getCharPosition())) {
            int[] pageTarget = activity.resolveBookmarkDisplayPageTarget(bookmark, bookmark.getCharPosition());
            activity.jumpToAbsoluteCharPosition(
                    bookmark.getCharPosition(),
                    pageTarget[0],
                    pageTarget[1],
                    bookmark.getAnchorTextBefore(),
                    bookmark.getAnchorTextAfter(),
                    true);
            return;
        }

        int resolvedPosition = activity.resolveAnchoredAbsolutePosition(
                activity.fileContent,
                activity.largeTextEstimateActive ? activity.largeTextPreviewBaseCharOffset : 0,
                bookmark.getCharPosition(),
                bookmark.getAnchorTextBefore(),
                bookmark.getAnchorTextAfter());

        boolean cacheStillMatches = Math.abs(resolvedPosition - bookmark.getCharPosition()) <= 3;
        int[] pageTarget = activity.resolveBookmarkDisplayPageTarget(bookmark, resolvedPosition);
        if (!cacheStillMatches && !activity.isLargeTextExactPageIndexReady()) {
            pageTarget[0] = 0;
            pageTarget[1] = 0;
        }
        activity.jumpToAbsoluteCharPosition(
                resolvedPosition,
                pageTarget[0],
                pageTarget[1],
                bookmark.getAnchorTextBefore(),
                bookmark.getAnchorTextAfter(),
                true);
    }
}
