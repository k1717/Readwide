package com.readwide.manager;

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
        resetDocumentPageTransform();
        activity.wordSelectionActive = false;
        activity.webView.removeCallbacks(activity.checkWordSelectionAfterScrollRunnable);
        activity.webView.getSettings().setJavaScriptEnabled("Word".equals(activity.docType)
                || ("EPUB".equals(activity.docType) && activity.epubFixedLayoutLike));
        if (activity.rightWebView != null) {
            activity.rightWebView.getSettings().setJavaScriptEnabled(
                    "EPUB".equals(activity.docType) && activity.epubFixedLayoutLike);
        }
        activity.configureWebViewForCurrentPage();
        activity.applyEpubBoundaryMarginsIfNeeded();
        activity.lastAppliedDocumentThemeSignature = activity.documentThemeSignature();
        activity.updateDocumentSpreadVisibility();
        activity.beginDocumentFastScrollContentChange();
        activity.webView.loadDataWithBaseURL(
                activity.documentBaseUrlForPage(p),
                activity.documentHtmlForDisplay(p, page),
                "text/html",
                "UTF-8",
                null);
        activity.markCurrentEpubBoundaryRenderLoaded();
        activity.loadDocumentRightSpreadPageIfNeeded();
        activity.scheduleDocumentFastScrollUpdate();
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
