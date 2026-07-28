package com.readwide.manager;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.readwide.manager.util.FileUtils;

import java.util.Locale;

/** Shared URI-to-viewer routing for the system picker and SAF tree browser. */
final class UriOpenRequest {
    @NonNull final Intent intent;
    final boolean image;

    private UriOpenRequest(@NonNull Intent intent, boolean image) {
        this.intent = intent;
        this.image = image;
    }

    @NonNull
    static UriOpenRequest create(@NonNull Context context, @NonNull Uri uri) {
        String displayName;
        try {
            displayName = FileUtils.getFileNameFromUri(context, uri);
        } catch (Exception ignored) {
            displayName = FileUtils.normalizeDisplayFileName(uri.getLastPathSegment());
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = FileUtils.normalizeDisplayFileName(uri.getLastPathSegment());
        }

        String mime = null;
        try {
            mime = context.getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        boolean pdf = FileUtils.isPdfFile(displayName)
                || "application/pdf".equalsIgnoreCase(mime);
        boolean epub = FileUtils.isEpubFile(displayName)
                || "application/epub+zip".equalsIgnoreCase(mime);
        boolean markdown = FileUtils.isMarkdownFile(displayName)
                || "text/markdown".equalsIgnoreCase(mime)
                || "text/x-markdown".equalsIgnoreCase(mime);
        boolean word = FileUtils.isWordOrHwpFile(displayName)
                || "application/x-hwp".equalsIgnoreCase(mime)
                || "application/haansofthwp".equalsIgnoreCase(mime)
                || "application/vnd.hancom.hwp".equalsIgnoreCase(mime)
                || "application/vnd.hancom.hwpx".equalsIgnoreCase(mime)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        .equalsIgnoreCase(mime)
                || "application/vnd.ms-word.document.macroEnabled.12".equalsIgnoreCase(mime)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.template"
                        .equalsIgnoreCase(mime)
                || "application/vnd.ms-word.template.macroEnabled.12".equalsIgnoreCase(mime);
        boolean image = FileUtils.isImageFile(displayName)
                || (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/"));

        Intent intent;
        if (pdf) {
            intent = new Intent(context, PdfReaderActivity.class);
            intent.putExtra(PdfReaderActivity.EXTRA_FILE_URI, uri.toString());
        } else if (epub || markdown || word) {
            intent = new Intent(context, DocumentPageActivity.class);
            intent.putExtra(DocumentPageActivity.EXTRA_FILE_URI, uri.toString());
        } else if (image) {
            intent = new Intent(context, ImageReaderActivity.class);
            intent.putExtra(ImageReaderActivity.EXTRA_FILE_URI, uri.toString());
            intent.putExtra(ImageReaderActivity.EXTRA_ALLOW_FILE_OPS, false);
        } else {
            intent = new Intent(context, ReaderActivity.class);
            intent.putExtra(ReaderActivity.EXTRA_FILE_URI, uri.toString());
        }
        return new UriOpenRequest(intent, image);
    }
}
