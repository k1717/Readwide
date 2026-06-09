package com.textview.reader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.model.Bookmark;
import com.textview.reader.util.BookmarkPageModelMath;
import com.textview.reader.view.CustomReaderView;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps bookmark page metadata aligned with the current large-TXT exact page
 * model. This is the bridge that makes bookmark restore use the same final
 * total/page model as slider and explicit page jumps.
 */
final class ReaderBookmarkPageModelController {
    private final ReaderActivity activity;

    ReaderBookmarkPageModelController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    @NonNull
    String buildCurrentLargeTextBookmarkPageSignature() {
        if (!activity.largeTextEstimateActive || activity.filePath == null || activity.readerView == null) return "";
        return activity.buildCurrentLargeTextExactPageIndexSignature(activity.filePath);
    }

    @NonNull
    String trustedCurrentBookmarkPageSignature() {
        String currentSignature = buildCurrentLargeTextBookmarkPageSignature();
        return copyCurrentLargeTextExactPageAnchorsIfUsable(currentSignature) != null
                ? currentSignature
                : "";
    }

    void applyCurrentSavePageModel(@NonNull Bookmark bookmark) {
        String signature = activity.largeTextEstimateActive ? trustedCurrentBookmarkPageSignature() : "";
        updateBookmarkPageModelFieldsIfChanged(
                bookmark,
                activity.getDisplayedCurrentPageNumber(),
                activity.getDisplayedTotalPageCount(),
                signature);
    }

    void syncCurrentFileBookmarksToLargeTextExactPageModel() {
        if (!activity.largeTextEstimateActive
                || activity.bookmarkManager == null
                || activity.filePath == null
                || activity.readerView == null) return;

        String currentSignature = buildCurrentLargeTextBookmarkPageSignature();
        ArrayList<CustomReaderView.PageTextAnchor> anchors =
                copyCurrentLargeTextExactPageAnchorsIfUsable(currentSignature);
        if (anchors == null || anchors.isEmpty()) return;

        int total = Math.max(1, anchors.size());
        List<Bookmark> currentBookmarks = activity.bookmarkManager.getBookmarksForFile(activity.filePath);
        if (currentBookmarks.isEmpty()) return;

        List<Bookmark> changed = new ArrayList<>();
        for (Bookmark bookmark : currentBookmarks) {
            if (bookmark == null) continue;
            int page = activity.findExactLargeTextPageForChar(anchors, bookmark.getCharPosition());
            if (updateBookmarkPageModelFieldsIfChanged(bookmark, page, total, currentSignature)) {
                changed.add(bookmark);
            }
        }
        if (!changed.isEmpty()) {
            activity.bookmarkManager.saveBookmarkPageMetadataRefresh(changed);
        }
    }

    int[] resolveBookmarkDisplayPageTarget(@NonNull Bookmark bookmark, int absoluteCharPosition) {
        if (!activity.largeTextEstimateActive) {
            return new int[] {
                    Math.max(0, bookmark.getPageNumber()),
                    Math.max(0, bookmark.getTotalPages())
            };
        }

        String currentSignature = buildCurrentLargeTextBookmarkPageSignature();
        ArrayList<CustomReaderView.PageTextAnchor> anchors =
                copyCurrentLargeTextExactPageAnchorsIfUsable(currentSignature);
        if (anchors != null && !anchors.isEmpty()) {
            int total = Math.max(1, anchors.size());
            int page = activity.findExactLargeTextPageForChar(anchors, absoluteCharPosition);
            if (updateBookmarkPageModelFieldsIfChanged(bookmark, page, total, currentSignature)) {
                List<Bookmark> changed = new ArrayList<>();
                changed.add(bookmark);
                activity.bookmarkManager.saveBookmarkPageMetadataRefresh(changed);
            }
            return new int[] { page, total };
        }

        return BookmarkPageModelMath.resolveSavedPageTarget(
                currentSignature,
                bookmark.getPageLayoutSignature(),
                bookmark.getPageNumber(),
                bookmark.getTotalPages());
    }

    @Nullable
    private ArrayList<CustomReaderView.PageTextAnchor> copyCurrentLargeTextExactPageAnchorsIfUsable(
            @NonNull String currentSignature) {
        if (currentSignature.isEmpty()) return null;
        return activity.copyCurrentLargeTextExactPageAnchorsIfUsable(currentSignature);
    }

    private boolean updateBookmarkPageModelFieldsIfChanged(@NonNull Bookmark bookmark,
                                                           int pageNumber,
                                                           int totalPages,
                                                           @NonNull String pageLayoutSignature) {
        int page = BookmarkPageModelMath.normalizePage(pageNumber);
        int total = BookmarkPageModelMath.normalizeTotalPages(page, totalPages);
        String signature = pageLayoutSignature != null ? pageLayoutSignature : "";
        if (!BookmarkPageModelMath.pageModelFieldsChanged(
                bookmark.getPageNumber(),
                bookmark.getTotalPages(),
                bookmark.getPageLayoutSignature(),
                page,
                total,
                signature)) {
            return false;
        }
        bookmark.setPageNumber(page);
        bookmark.setTotalPages(total);
        bookmark.setPageLayoutSignature(signature);
        return true;
    }
}
