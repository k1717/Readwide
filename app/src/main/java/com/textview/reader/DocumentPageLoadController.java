package com.textview.reader;

import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.textview.reader.model.ReaderState;
import com.textview.reader.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

final class DocumentPageLoadController {
    private final DocumentPageActivity activity;

    DocumentPageLoadController(@NonNull DocumentPageActivity activity) {
        this.activity = activity;
    }

    void loadFromIntent(Intent intent) {
        final int generation = ++activity.loadGeneration;
        activity.updateLoadingIndicatorTheme();
        activity.progressBar.setVisibility(View.VISIBLE);
        activity.webView.setVisibility(View.INVISIBLE);
        activity.closeResourceZip();
        activity.pages.clear();
        activity.wordRelationships.clear();
        activity.epubHasDocumentFont = false;
        activity.epubFixedLayoutLike = false;
        activity.wordHasDocumentFont = false;
        activity.wordDefaultFontFamily = null;
        activity.documentFontOverride = null;
        activity.hideDocumentSearchPanel(false, false);
        activity.clearDocumentSearchState(false);

        activity.executor.execute(() -> {
            try {
                resolveLocalFile(intent);
                activity.filePath = activity.localFile.getAbsolutePath();
                activity.fileName = activity.localFile.getName();
                String lower = activity.fileName.toLowerCase(Locale.ROOT);
                activity.pages.clear();

                if (lower.endsWith(".epub")) {
                    activity.docType = "EPUB";
                    activity.loadEpubPages(activity.localFile);
                } else if (FileUtils.isWordFile(activity.fileName)) {
                    activity.docType = "Word";
                    activity.loadWordPages(activity.localFile);
                } else {
                    throw new IOException("Unsupported document type: " + activity.fileName);
                }

                if (activity.pages.isEmpty()) throw new IOException("No renderable pages found");
                activity.currentPage = resolveInitialPage(intent);

                if (activity.activityDestroyed || generation != activity.loadGeneration) return;
                activity.runOnUiThread(() -> {
                    if (activity.activityDestroyed || generation != activity.loadGeneration) return;
                    if (activity.getSupportActionBar() != null) {
                        activity.getSupportActionBar().setTitle(activity.fileName);
                    }
                    if (activity.progressBar != null) activity.progressBar.setVisibility(View.GONE);
                    if (activity.webView != null) activity.webView.setVisibility(View.VISIBLE);
                    activity.showPage(activity.currentPage, 0);
                });
            } catch (Exception e) {
                if (activity.activityDestroyed || generation != activity.loadGeneration) return;
                activity.runOnUiThread(() -> {
                    if (!activity.activityDestroyed) showLoadError(e);
                });
            }
        });
    }

    private void resolveLocalFile(Intent intent) throws IOException {
        String path = intent.getStringExtra(DocumentPageActivity.EXTRA_FILE_PATH);
        String uriString = intent.getStringExtra(DocumentPageActivity.EXTRA_FILE_URI);
        if (path != null && !path.isEmpty()) {
            activity.localFile = new File(path);
        } else if (uriString != null && !uriString.isEmpty()) {
            Uri uri = Uri.parse(uriString);
            String displayName = FileUtils.getFileNameFromUri(activity, uri);
            if (displayName == null || displayName.trim().isEmpty()) displayName = "document";
            activity.localFile = FileUtils.copyUriToLocal(activity, uri, displayName);
        } else {
            throw new IOException("No file path or URI supplied");
        }
    }

    private int resolveInitialPage(Intent intent) {
        int jump = intent.getIntExtra(DocumentPageActivity.EXTRA_JUMP_TO_PAGE, -1);
        if (jump >= 0 && jump < activity.pages.size()) return jump;

        ReaderState state = activity.bookmarkManager.getReadingState(activity.filePath);
        if (state != null
                && state.getCharPosition() >= 0
                && state.getCharPosition() < activity.pages.size()) {
            return state.getCharPosition();
        }
        return 0;
    }

    private void showLoadError(Exception e) {
        if (activity.activityDestroyed) return;
        if (activity.progressBar != null) activity.progressBar.setVisibility(View.GONE);
        ShortToast.show(activity, activity.getString(R.string.error_prefix) + e.getMessage());
        activity.finish();
    }
}
