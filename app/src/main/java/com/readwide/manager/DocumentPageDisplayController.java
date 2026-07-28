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
        // docType is resolved off-thread. Apply the EPUB-only no-strip policy
        // before the first page load as well as on later chrome toggles/singleTop
        // document replacements.
        activity.applyDocumentTopPageStatusVisibility();
        if (activity.epubPageIsFixedLayout(page)
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
        activity.webView.removeCallbacks(activity.documentContentAnchorUpdateRunnable);
        int previousPage = activity.currentPage;
        activity.currentPage = page;
        activity.documentAnchorPageGeneration++;
        if (previousPage != page) {
            // Never let a late capture from the previous spine page become the
            // bookmark/state anchor for this page.
            activity.lastDocumentContentAnchorJson = "";
        }
        DocumentPageActivity.Page p = activity.pages.get(page);
        // Normal page turns snap after load. Vertical-rl uses a logical block
        // alignment in DocumentPageActivity; initial/anchor loads keep their
        // natural or restored position.
        activity.snapDocumentPageTopAfterLoad = direction != 0;
        resetDocumentPageTransform();
        activity.wordSelectionActive = false;
        activity.webView.removeCallbacks(activity.checkWordSelectionAfterScrollRunnable);
        activity.webView.getSettings().setJavaScriptEnabled(
                activity.documentPageRequiresJavaScript(page));
        if (activity.rightWebView != null) {
            int rightPage = activity.documentRightSpreadPageIndex();
            activity.rightWebView.getSettings().setJavaScriptEnabled(
                    activity.documentPageRequiresJavaScript(rightPage));
        }
        activity.configureWebViewForCurrentPage();
        activity.applyEpubBoundaryMarginsIfNeeded();
        activity.lastAppliedDocumentThemeSignature = activity.documentThemeSignature();
        activity.updateDocumentSpreadVisibility();
        activity.beginDocumentFastScrollContentChange();
        activity.webView.loadDataWithBaseURL(
                activity.documentBaseUrlForPageLoad(p, activity.webView, page),
                activity.documentHtmlForDisplay(p, page),
                "text/html",
                "UTF-8",
                null);
        activity.markCurrentEpubBoundaryRenderLoaded();
        activity.loadDocumentRightSpreadPageIfNeeded();
        activity.onEpubDisplayedPageChanged(previousPage, page);
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
