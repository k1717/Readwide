package com.textview.reader;

import android.content.Intent;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.textview.reader.model.Bookmark;
import com.textview.reader.util.FileUtils;

import java.io.File;

final class ReaderBookmarkOpenController {
    private final ReaderActivity activity;

    ReaderBookmarkOpenController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    boolean open(@NonNull Bookmark bookmark) {
        String filePath = bookmark.getFilePath();
        if (filePath != null && filePath.equals(activity.filePath)) {
            activity.jumpToBookmark(bookmark);
            return true;
        }

        if (filePath == null || filePath.trim().isEmpty()) {
            ShortToast.show(activity, activity.getString(R.string.file_not_found_prefix) + "");
            return false;
        }

        File targetFile = new File(filePath);
        if (!targetFile.exists()) {
            ShortToast.show(activity, activity.getString(R.string.file_not_found_prefix) + filePath);
            return false;
        }

        Intent intent;
        if (FileUtils.isPdfFile(targetFile.getName())) {
            intent = new Intent(activity, PdfReaderActivity.class);
            intent.putExtra(PdfReaderActivity.EXTRA_FILE_PATH, filePath);
            intent.putExtra(PdfReaderActivity.EXTRA_JUMP_TO_PAGE, bookmark.getCharPosition());
        } else if (FileUtils.isEpubFile(targetFile.getName()) || FileUtils.isWordFile(targetFile.getName())) {
            intent = new Intent(activity, DocumentPageActivity.class);
            intent.putExtra(DocumentPageActivity.EXTRA_FILE_PATH, filePath);
            intent.putExtra(DocumentPageActivity.EXTRA_JUMP_TO_PAGE, bookmark.getCharPosition());
        } else {
            intent = new Intent(activity, ReaderActivity.class);
            intent.putExtra(ReaderActivity.EXTRA_FILE_PATH, filePath);
            intent.putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, bookmark.getCharPosition());
            intent.putExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, bookmark.getPageNumber());
            intent.putExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, bookmark.getTotalPages());
            intent.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE, bookmark.getAnchorTextBefore());
            intent.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER, bookmark.getAnchorTextAfter());
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
        return true;
    }
}
