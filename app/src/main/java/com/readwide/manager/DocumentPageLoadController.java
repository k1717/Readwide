package com.readwide.manager;

import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.readwide.manager.model.ReaderState;
import com.readwide.manager.util.FileUtils;

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
        activity.showLoadingWindow();
        activity.webView.setVisibility(View.INVISIBLE);
        activity.closeResourceZip();
        activity.pages.clear();
        activity.wordRelationships.clear();
        activity.epubHasDocumentFont = false;
        activity.epubFixedLayoutLike = false;
        activity.wordHasDocumentFont = false;
        activity.wordDefaultFontFamily = null;
        activity.documentFontOverride = null;
        activity.markdownVisualCurrentPage = 0;
        activity.markdownVisualTotalPages = 1;
        activity.pendingMarkdownRestoreScrollY = -1;
        activity.pendingMarkdownRestorePage = -1;
        activity.pendingMarkdownRestoreSourceOffset = -1;
        activity.markdownSourceText = "";
        activity.lastMarkdownSourceOffset = 0;
        activity.lastMarkdownSourceLine = 1;
        activity.lastMarkdownAnchorText = "";
        activity.pendingDocumentRestoreAnchorJson = "";
        activity.lastDocumentContentAnchorJson = "";
        activity.hideDocumentSearchPanel(false, false);
        activity.clearDocumentSearchState(false);

        activity.submitDocumentTask(() -> {
            try {
                resolveLocalFile(intent);
                activity.filePath = activity.localFile.getAbsolutePath();
                activity.fileName = activity.localFile.getName();
                String lower = activity.fileName.toLowerCase(Locale.ROOT);
                activity.pages.clear();

                if (lower.endsWith(".epub")) {
                    activity.docType = "EPUB";
                    activity.loadEpubPages(activity.localFile);
                } else if (FileUtils.isMarkdownFile(activity.fileName)) {
                    activity.docType = "Markdown";
                    activity.loadMarkdownPage(activity.localFile);
                } else if (FileUtils.isHwpFile(activity.fileName)) {
                    activity.docType = "HWP";
                    activity.loadHwpPages(activity.localFile);
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
                    activity.hideLoadingWindow();
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
        if (activity.isMarkdownDocument()) {
            int sourceJump = intent.getIntExtra(DocumentPageActivity.EXTRA_MARKDOWN_SOURCE_OFFSET, -1);
            String contentAnchorJson = intent.getStringExtra(DocumentPageActivity.EXTRA_CONTENT_ANCHOR_JSON);
            if (sourceJump < 0 && contentAnchorJson != null && !contentAnchorJson.trim().isEmpty()) {
                try {
                    org.json.JSONObject obj = new org.json.JSONObject(contentAnchorJson);
                    sourceJump = obj.optInt("sourceOffset", -1);
                    int visualPage = obj.optInt("visualPage", 0);
                    if (visualPage > 0 && jump < 0) jump = visualPage - 1;
                } catch (Exception ignored) {}
            }
            if (sourceJump >= 0) {
                activity.pendingMarkdownRestoreSourceOffset = sourceJump;
                if (jump >= 0) activity.pendingMarkdownRestorePage = jump;
            } else if (jump >= 0) {
                // Legacy Markdown bookmarks from the early visual-page implementation
                // used EXTRA_JUMP_TO_PAGE directly. Keep that as a visual fallback.
                activity.pendingMarkdownRestorePage = jump;
            } else {
                ReaderState state = activity.bookmarkManager.getReadingState(activity.filePath);
                if (state != null) {
                    String stateAnchor = state.getContentAnchorJson();
                    if (stateAnchor != null && !stateAnchor.trim().isEmpty()) {
                        try {
                            org.json.JSONObject obj = new org.json.JSONObject(stateAnchor);
                            int offset = obj.optInt("sourceOffset", -1);
                            if (offset >= 0) {
                                activity.pendingMarkdownRestoreSourceOffset = offset;
                                int visualPage = obj.optInt("visualPage", state.getPageNumber());
                                if (visualPage > 0) activity.pendingMarkdownRestorePage = visualPage - 1;
                            }
                        } catch (Exception ignored) {}
                    }
                    String encoding = state.getEncoding();
                    if (activity.pendingMarkdownRestoreSourceOffset < 0
                            && encoding != null && encoding.startsWith("Markdown_SOURCE_ANCHOR")) {
                        activity.pendingMarkdownRestoreSourceOffset = state.getCharPosition();
                        if (state.getPageNumber() > 0) activity.pendingMarkdownRestorePage = state.getPageNumber() - 1;
                    } else if (activity.pendingMarkdownRestoreSourceOffset < 0 && state.getScrollY() > 0) {
                        activity.pendingMarkdownRestoreScrollY = state.getScrollY();
                    } else if (activity.pendingMarkdownRestoreSourceOffset < 0 && state.getCharPosition() >= 0) {
                        activity.pendingMarkdownRestorePage = state.getCharPosition();
                    }
                }
            }
            return 0;
        }
        String contentAnchorJson = intent.getStringExtra(DocumentPageActivity.EXTRA_CONTENT_ANCHOR_JSON);
        if (contentAnchorJson != null && !contentAnchorJson.trim().isEmpty()) {
            activity.pendingDocumentRestoreAnchorJson = contentAnchorJson;
        }
        if (jump >= 0 && jump < activity.pages.size()) return jump;

        ReaderState state = activity.bookmarkManager.getReadingState(activity.filePath);
        if (state != null
                && state.getCharPosition() >= 0
                && state.getCharPosition() < activity.pages.size()) {
            String stateAnchor = state.getContentAnchorJson();
            if (stateAnchor != null && !stateAnchor.trim().isEmpty()) {
                activity.pendingDocumentRestoreAnchorJson = stateAnchor;
            }
            return state.getCharPosition();
        }
        return 0;
    }

    private void showLoadError(Exception e) {
        if (activity.activityDestroyed) return;
        activity.hideLoadingWindow();
        ShortToast.show(activity, activity.getString(R.string.error_prefix) + e.getMessage());
        activity.finish();
    }
}
