package com.textview.reader;

import androidx.annotation.NonNull;


final class DocumentPageDisplayController {
    private final DocumentPageActivity activity;

    DocumentPageDisplayController(@NonNull DocumentPageActivity activity) {
        this.activity = activity;
    }

    void showPage(int page, int direction) {
        if (activity.activityDestroyed
                || activity.webView == null
                || page < 0
                || page >= activity.pages.size()) {
            return;
        }
        if ("EPUB".equals(activity.docType)
                && activity.epubFixedLayoutLike
                && (activity.webView.getWidth() <= 0 || activity.webView.getHeight() <= 0)) {
            final int requestedPage = page;
            activity.webView.post(() -> {
                if (!activity.activityDestroyed && activity.webView != null) {
                    activity.showPage(requestedPage, 0);
                }
            });
            return;
        }
        if (direction != 0 && activity.pageTurnInFlight) return;
        if (direction != 0) {
            activity.pageTurnInFlight = true;
            activity.webView.removeCallbacks(activity.releasePageTurnRunnable);
            activity.webView.postDelayed(activity.releasePageTurnRunnable, 190);
        }
        activity.pendingSlideDirection = 0;
        activity.snapDocumentPageTopAfterLoad = direction != 0;
        activity.currentPage = page;
        DocumentPageActivity.Page p = activity.pages.get(page);
        String baseUrl = "https://" + DocumentPageActivity.LOCAL_HOST + "/";
        if ("EPUB".equals(activity.docType) && p.sourcePath != null) {
            String parent = activity.parentPath(p.sourcePath);
            baseUrl = "https://" + DocumentPageActivity.LOCAL_HOST + DocumentPageActivity.EPUB_PREFIX + parent;
            if (!baseUrl.endsWith("/")) baseUrl += "/";
        }
        resetDocumentPageTransform();
        activity.wordSelectionActive = false;
        activity.webView.removeCallbacks(activity.checkWordSelectionAfterScrollRunnable);
        activity.webView.getSettings().setJavaScriptEnabled("Word".equals(activity.docType));
        activity.configureWebViewForCurrentPage();
        activity.applyEpubBoundaryMarginsIfNeeded();
        activity.lastAppliedDocumentThemeSignature = activity.documentThemeSignature();
        String htmlForDisplay = p.html;
        if ("EPUB".equals(activity.docType) && activity.epubFixedLayoutLike) {
            htmlForDisplay = activity.prepareFixedLayoutEpubHtml(htmlForDisplay);
        }
        activity.webView.loadDataWithBaseURL(
                baseUrl,
                activity.applyReaderThemeCss(htmlForDisplay),
                "text/html",
                "UTF-8",
                null);
        updateStatus();
        if (!activity.isMarkdownDocument() && !activity.isRenderedContentAnchorDocument()) {
            activity.saveReadingState();
        }
    }

    void runSlideInAnimation() {
        resetDocumentPageTransform();
        activity.pageTurnInFlight = false;
    }

    private void resetDocumentPageTransform() {
        if (activity.webView == null) return;
        activity.webView.animate().cancel();
        activity.webView.setTranslationX(0f);
        activity.webView.setTranslationY(0f);
        activity.webView.setAlpha(1.0f);
    }

    private void updateStatus() {
        activity.updateDocumentPageStatusViews();
    }
}
