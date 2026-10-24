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
        String documentName = documentFileName(displayName, mime);
        boolean pdf = FileUtils.isPdfFile(displayName)
                || "application/pdf".equalsIgnoreCase(mime);
        boolean epub = FileUtils.isEpubFile(documentName);
        boolean markdown = FileUtils.isMarkdownFile(documentName);
        boolean word = FileUtils.isWordOrHwpFile(documentName);
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

    /** Keep recognized document names; supply only a type suffix for MIME-only documents. */
    static String documentFileName(String displayName, String mime) {
        String name = displayName == null || displayName.trim().isEmpty() ? "document" : displayName;
        if (FileUtils.isEpubFile(name) || FileUtils.isMarkdownFile(name)
                || FileUtils.isWordOrHwpFile(name)) return name;
        String type = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
        String suffix;
        switch (type) {
            case "application/epub+zip": suffix = ".epub"; break;
            case "text/markdown":
            case "text/x-markdown": suffix = ".md"; break;
            case "application/msword": suffix = ".doc"; break;
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": suffix = ".docx"; break;
            case "application/vnd.ms-word.document.macroenabled.12": suffix = ".docm"; break;
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.template": suffix = ".dotx"; break;
            case "application/vnd.ms-word.template.macroenabled.12": suffix = ".dotm"; break;
            case "application/x-hwp":
            case "application/haansofthwp":
            case "application/vnd.hancom.hwp": suffix = ".hwp"; break;
            case "application/vnd.hancom.hwpx": suffix = ".hwpx"; break;
            default: return name;
        }
        return name + suffix;
    }
}
