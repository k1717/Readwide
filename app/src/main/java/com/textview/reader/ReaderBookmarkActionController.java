package com.textview.reader;

import android.widget.Toast;

import androidx.annotation.NonNull;

import com.textview.reader.model.Bookmark;

import java.util.List;

final class ReaderBookmarkActionController {
    private final ReaderActivity activity;

    ReaderBookmarkActionController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void addBookmark() {
        saveCurrentBookmarkTekStyle(null);
    }

    void saveCurrentBookmarkTekStyle(Runnable afterSave) {
        if (activity.filePath == null || activity.fileContent == null || activity.fileContent.isEmpty()) {
            ShortToast.show(activity, activity.getString(R.string.file_not_loaded));
            return;
        }

        int charPos = activity.getBookmarkSaveCharPosition();
        int lineNum = Math.max(1, activity.countLinesUntilChar(charPos));
        String excerpt = activity.getExcerpt(charPos);
        String anchorBefore = activity.getAnchorTextBefore(charPos);
        String anchorAfter = activity.getAnchorTextAfter(charPos);
        int bookmarkEndPosition = activity.largeTextEstimateActive
                ? charPos + Math.max(1, excerpt.length())
                : Math.min(activity.totalChars, charPos + Math.max(1, excerpt.length()));

        // Avoid stacking multiple identical bookmarks when the user taps save repeatedly
        // at almost the same location. Original Tek View-style bookmarks are position-based.
        List<Bookmark> existing = activity.bookmarkManager.getBookmarksForFile(activity.filePath);
        for (Bookmark b : existing) {
            if (Math.abs(b.getCharPosition() - charPos) <= 3) {
                b.setLineNumber(lineNum);
                b.setExcerpt(excerpt);
                b.setAnchorTextBefore(anchorBefore);
                b.setAnchorTextAfter(anchorAfter);
                b.setEndPosition(bookmarkEndPosition);
                activity.bookmarkPageModel().applyCurrentSavePageModel(b);
                activity.bookmarkManager.updateBookmark(b);
                ShortToast.show(activity, activity.getString(R.string.bookmark_updated));
                if (afterSave != null) afterSave.run();
                return;
            }
        }

        Bookmark bookmark = new Bookmark(activity.filePath, activity.fileName, charPos, lineNum, excerpt);
        bookmark.setAnchorTextBefore(anchorBefore);
        bookmark.setAnchorTextAfter(anchorAfter);
        bookmark.setEndPosition(bookmarkEndPosition);
        activity.bookmarkPageModel().applyCurrentSavePageModel(bookmark);
        // No label prompt here. Original behavior is excerpt/position based.
        // Optional memo editing remains available by long-pressing a bookmark.
        activity.bookmarkManager.addBookmark(bookmark);
        ShortToast.show(activity, activity.getString(R.string.bookmark_saved));

        if (afterSave != null) afterSave.run();
    }
}
