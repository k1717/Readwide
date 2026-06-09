package com.textview.reader;

import androidx.annotation.NonNull;

import java.util.Locale;

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
        int visualSlideDirection = activity.visualSlideDirectionForPageDelta(direction);
        activity.pendingSlideDirection = visualSlideDirection;
        activity.currentPage = page;
        DocumentPageActivity.Page p = activity.pages.get(page);
        String baseUrl = "https://" + DocumentPageActivity.LOCAL_HOST + "/";
        if ("EPUB".equals(activity.docType) && p.sourcePath != null) {
            String parent = activity.parentPath(p.sourcePath);
            baseUrl = "https://" + DocumentPageActivity.LOCAL_HOST + DocumentPageActivity.EPUB_PREFIX + parent;
            if (!baseUrl.endsWith("/")) baseUrl += "/";
        }
        prepareDocumentSlide(visualSlideDirection);
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
        activity.saveReadingState();
    }

    void runSlideInAnimation() {
        if (activity.webView == null) return;
        int direction = activity.pendingSlideDirection;
        activity.pendingSlideDirection = 0;
        if (direction == 0) {
            activity.webView.setTranslationX(0f);
            activity.webView.setAlpha(1.0f);
            activity.pageTurnInFlight = false;
            return;
        }
        activity.webView.animate()
                .translationX(0f)
                .alpha(1.0f)
                .setDuration(135)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> activity.pageTurnInFlight = false)
                .start();
    }

    private void prepareDocumentSlide(int direction) {
        if (activity.webView == null || direction == 0) return;
        activity.webView.animate().cancel();
        float distance = Math.max(activity.dpToPx(56), activity.webView.getWidth() * 0.18f);
        activity.webView.setTranslationX(direction > 0 ? distance : -distance);
        activity.webView.setAlpha(0.72f);
    }

    private void updateStatus() {
        activity.pageStatus.setText(String.format(
                Locale.getDefault(),
                "%s %d / %d",
                activity.docType,
                activity.currentPage + 1,
                activity.pages.size()));
        activity.prevButton.setEnabled(activity.currentPage > 0);
        activity.nextButton.setEnabled(activity.currentPage < activity.pages.size() - 1);
    }
}
