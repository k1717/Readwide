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
        activity.documentLoadAccepted = false;
        activity.beginDocumentViewportLoad();
        final String host = activity.documentLoadHost();
        // Invalidate old document captures/settling before the new book is parsed.
        activity.documentAnchorPageGeneration++;
        activity.invalidateDocumentPageLoad(activity.webView);
        activity.invalidateDocumentPageLoad(activity.rightWebView);
        activity.primaryDocumentPageReadyGeneration = -1;
        activity.showLoadingWindow();
        activity.webView.setVisibility(View.INVISIBLE);
        activity.closeResourceZip();
        activity.pages.clear();
        activity.wordRelationships.clear();
        activity.epubHasDocumentFont = false;
        activity.epubFixedLayoutLike = false;
        activity.epubImagePageLike = false;
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
            DocumentPageActivity.ParsedDocument parsed = new DocumentPageActivity.ParsedDocument(host);
            boolean delivered = false;
            try {
                if (activity.activityDestroyed || generation != activity.loadGeneration) return;
                File file = resolveLocalFile(intent);
                String name = file.getName();
                String lower = name.toLowerCase(Locale.ROOT);

                if (lower.endsWith(".epub")) {
                    parsed.docType = "EPUB";
                    parsed.loadEpubPages(file);
                } else if (FileUtils.isMarkdownFile(name)) {
                    parsed.docType = "Markdown";
                    parsed.loadMarkdownPage(file);
                } else if (FileUtils.isHwpFile(name)) {
                    parsed.docType = "HWP";
                    parsed.loadHwpPages(file);
                } else if (FileUtils.isWordFile(name)) {
                    parsed.docType = "Word";
                    parsed.loadWordPages(file);
                } else {
                    throw new IOException("Unsupported document type: " + name);
                }

                if (parsed.pages.isEmpty()) throw new IOException("No renderable pages found");

                if (activity.activityDestroyed || generation != activity.loadGeneration) return;
                activity.runOnUiThread(() -> acceptParsedDocument(generation, parsed, file, intent));
                delivered = true;
            } catch (Exception e) {
                reportLoadFailure(generation, e);
            } catch (OutOfMemoryError | StackOverflowError e) {
                // A fallback parse would repeat heavy work after resource exhaustion.
                // Unwind once, close the unadopted ParsedDocument in finally, and use
                // the existing load-error UI instead of leaving its spinner active.
                reportLoadFailure(generation, new IOException("Document exceeds available memory or nesting depth", e));
            } finally {
                if (!delivered) parsed.close();
            }
        });
    }

    /** UI adoption can allocate again while copying metadata and preparing display HTML. */
    void acceptParsedDocument(int generation, DocumentPageActivity.ParsedDocument parsed,
                              File file, Intent intent) {
        try {
            if (activity.activityDestroyed || generation != activity.loadGeneration) return;
            activity.adoptParsedDocument(parsed, file);
            if (activity.activityDestroyed || generation != activity.loadGeneration) return;
            activity.currentPage = resolveInitialPage(intent);
            activity.documentLoadAccepted = true;
            activity.rememberOpeningDocumentViewport();
            if (activity.getSupportActionBar() != null) {
                activity.getSupportActionBar().setTitle(activity.fileName);
            }
            activity.hideLoadingWindow();
            if (activity.webView != null) activity.webView.setVisibility(View.VISIBLE);
            // docType is resolved off-thread. Apply its stable safe frame before display.
            activity.applyDocumentTopPageStatusVisibility();
            activity.applyDocumentSystemBarColors();
            androidx.core.view.ViewCompat.requestApplyInsets(activity.findViewById(R.id.document_root));
            androidx.core.view.ViewCompat.requestApplyInsets(activity.findViewById(R.id.document_content_column));
            activity.showPage(activity.currentPage, 0);
        } catch (RuntimeException failure) {
            failAcceptedDocument(generation, failure);
        } catch (OutOfMemoryError | StackOverflowError failure) {
            failAcceptedDocument(generation,
                    new IOException("Document exceeds available memory or nesting depth", failure));
        } finally {
            // Successful adoption transferred its ZIP and set resourceZip=null.
            // Otherwise this closes only the untransferred parse-owned handle.
            parsed.close();
        }
    }

    private void failAcceptedDocument(int generation, Exception failure) {
        if (activity.activityDestroyed || generation != activity.loadGeneration) return;
        // Revoke saves before retiring any partially published content. Failure
        // must never overwrite a previously persisted bookmark/reading position.
        activity.documentLoadAccepted = false;
        activity.beginDocumentViewportLoad();
        activity.documentAnchorPageGeneration++;
        activity.invalidateDocumentPageLoad(activity.webView);
        activity.invalidateDocumentPageLoad(activity.rightWebView);
        activity.primaryDocumentPageReadyGeneration = -1;
        activity.rightDocumentPageReadyGeneration = -1;
        activity.closeResourceZip();
        activity.pages.clear();
        activity.wordRelationships.clear();
        activity.pendingDocumentRestoreAnchorJson = "";
        activity.lastDocumentContentAnchorJson = "";
        if (activity.webView != null) activity.webView.setVisibility(View.INVISIBLE);
        showLoadError(failure);
    }

    private void reportLoadFailure(int generation, Exception failure) {
        if (activity.activityDestroyed || generation != activity.loadGeneration) return;
        activity.runOnUiThread(() -> {
            if (!activity.activityDestroyed && generation == activity.loadGeneration) showLoadError(failure);
        });
    }

    private File resolveLocalFile(Intent intent) throws IOException {
        String path = intent.getStringExtra(DocumentPageActivity.EXTRA_FILE_PATH);
        String uriString = intent.getStringExtra(DocumentPageActivity.EXTRA_FILE_URI);
        if (path != null && !path.isEmpty()) {
            return new File(path);
        } else if (uriString != null && !uriString.isEmpty()) {
            Uri uri = Uri.parse(uriString);
            String displayName = FileUtils.getFileNameFromUri(activity, uri);
            if (displayName == null || displayName.trim().isEmpty()) displayName = "document";
            String mime = null;
            try { mime = activity.getContentResolver().getType(uri); }
            catch (RuntimeException unavailable) { /* A recognized filename remains usable. */ }
            displayName = UriOpenRequest.documentFileName(displayName, mime);
            return FileUtils.copyUriToLocal(activity, uri, displayName);
        } else {
            throw new IOException("No file path or URI supplied");
        }
    }

    private int resolveInitialPage(Intent intent) {
        int jump = intent.getIntExtra(DocumentPageActivity.EXTRA_JUMP_TO_PAGE, -1);
        if (activity.isMarkdownDocument()) {
            int sourceJump = intent.getIntExtra(DocumentPageActivity.EXTRA_MARKDOWN_SOURCE_OFFSET, -1);
            int scrollJump = intent.getIntExtra(DocumentPageActivity.EXTRA_MARKDOWN_SCROLL_Y, -1);
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
            } else if (scrollJump >= 0) {
                activity.pendingMarkdownRestoreScrollY = scrollJump;
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
