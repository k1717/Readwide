package com.textview.reader;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;

import androidx.annotation.NonNull;

final class DocumentWebViewController {
    private final DocumentPageActivity activity;

    DocumentWebViewController(@NonNull DocumentPageActivity activity) {
        this.activity = activity;
    }

    void setupWebView() {
        WebSettings settings = activity.webView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setTextZoom(100);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setDomStorageEnabled(false);

        activity.webView.setBackgroundColor(activity.readerBg);
        activity.webView.setLongClickable(true);
        activity.webView.setHapticFeedbackEnabled(true);
        activity.webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        activity.webView.addJavascriptInterface(activity.new WordSelectionBridge(), "TextViewSelectionBridge");
        activity.webView.setFindListener((activeMatchOrdinal, numberOfMatches, isDoneCounting) -> {
            if (!isDoneCounting) return;
            activity.activeDocumentSearchPage = activity.currentPage;
            activity.activeDocumentSearchCountOnPage = Math.max(0, numberOfMatches);
            activity.activeDocumentSearchOrdinal = numberOfMatches > 0
                    ? Math.max(1, activeMatchOrdinal + 1)
                    : 0;

            if (activity.documentSearchSelectLastAfterCount
                    && numberOfMatches > 0
                    && activity.webView != null) {
                activity.documentSearchSelectLastAfterCount = false;
                activity.webView.post(() -> {
                    if (!activity.activityDestroyed && activity.webView != null) {
                        activity.webView.findNext(false);
                        activity.scheduleDocumentSearchReveal();
                    }
                });
                return;
            }

            activity.updateDocumentSearchStatus(activity.documentSearchStatusView);
            activity.scheduleDocumentSearchReveal();
        });
        activity.webView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if ("Word".equals(activity.docType)
                    && Math.abs(scrollY - oldScrollY) > activity.dpToPx(1)) {
                activity.webView.removeCallbacks(activity.checkWordSelectionAfterScrollRunnable);
                activity.webView.postDelayed(activity.checkWordSelectionAfterScrollRunnable, 90);
            }
        });

        activity.webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    @NonNull WebView view,
                    @NonNull WebResourceRequest request) {
                return activity.interceptLocalResource(request.getUrl());
            }

            @Override
            public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                super.onPageFinished(view, url);
                if (activity.progressBar != null) activity.progressBar.setVisibility(View.GONE);
                activity.installWordSelectionCleanupScript();
                activity.applyFixedLayoutFindOffsetCssIfNeeded();
                activity.applyDocumentSearchHighlightAfterPageLoad();
                activity.runDocumentSlideInAnimation();
                activity.restoreDocumentScrollAfterThemeRefreshIfNeeded(view);
            }
        });
    }

    void configureForCurrentPage() {
        if (activity.webView == null) return;
        WebSettings settings = activity.webView.getSettings();
        if ("EPUB".equals(activity.docType) && activity.epubFixedLayoutLike) {
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
            settings.setTextZoom(100);
            activity.webView.setInitialScale(0);
        } else {
            settings.setLoadWithOverviewMode(false);
            settings.setUseWideViewPort(false);
            settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);
            settings.setTextZoom(activity.documentTextZoomPercent());
        }
    }
}
