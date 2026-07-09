package com.readwide.manager;

import android.net.Uri;
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
        setupOneWebView(activity.webView, true);
        setupOneWebView(activity.rightWebView, false);
    }

    private void setupOneWebView(WebView view, boolean primary) {
        if (view == null) return;
        WebSettings settings = view.getSettings();
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

        view.setBackgroundColor(activity.readerBg);
        view.setLongClickable(primary);
        view.setHapticFeedbackEnabled(primary);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (primary) {
            view.addJavascriptInterface(activity.new WordSelectionBridge(), "ReadwideSelectionBridge");
            view.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (activity.isMarkdownDocument() && Math.abs(scrollY - oldScrollY) > activity.dpToPx(1)) {
                    activity.updateMarkdownVisualPageModel(false);
                    activity.scheduleMarkdownSourceAnchorUpdate();
                }
                if (activity.isRenderedContentAnchorDocument() && Math.abs(scrollY - oldScrollY) > activity.dpToPx(1)) {
                    activity.scheduleDocumentContentAnchorUpdate();
                }
                if ("Word".equals(activity.docType)
                        && Math.abs(scrollY - oldScrollY) > activity.dpToPx(1)) {
                    activity.webView.removeCallbacks(activity.checkWordSelectionAfterScrollRunnable);
                    activity.webView.postDelayed(activity.checkWordSelectionAfterScrollRunnable, 90);
                }
            });
        }

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WebResourceRequest request) {
                return activity.handleEpubInternalNavigation(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(@NonNull WebView view, String url) {
                return activity.handleEpubInternalNavigation(Uri.parse(url));
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(
                    @NonNull WebView view,
                    @NonNull WebResourceRequest request) {
                return activity.interceptLocalResource(request.getUrl());
            }

            @Override
            public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                super.onPageFinished(view, url);
                if (activity.activityDestroyed) return;
                if (!primary) {
                    return;
                }
                if (activity.webView == null) return;
                if (activity.progressBar != null) activity.progressBar.setVisibility(View.GONE);
                activity.installWordSelectionCleanupScript();
                activity.applyFixedLayoutFindOffsetCssIfNeeded();
                activity.applyDocumentSearchHighlightAfterPageLoad();
                activity.runDocumentSlideInAnimation();
                activity.snapDocumentWebViewToPageTopIfNeeded(view);
                activity.restoreDocumentScrollAfterThemeRefreshIfNeeded(view);
                activity.installMarkdownSourceAnchorScript();
                activity.restoreMarkdownVisualPositionAfterLoadIfNeeded(view);
                activity.scheduleMarkdownVisualPageModelUpdate();
                activity.scheduleMarkdownSourceAnchorUpdate();
                activity.installDocumentContentAnchorScript();
                activity.restoreDocumentContentAnchorAfterLoadIfNeeded(view);
                activity.documentTtsHighlight().installScript();
                if (activity.isRenderedContentAnchorDocument()) {
                    activity.webView.postDelayed(() -> activity.updateDocumentContentAnchorFromWebView(activity::saveReadingState), 180);
                }
            }
        });
    }

    void configureForCurrentPage() {
        configureOneWebView(activity.webView);
        configureOneWebView(activity.rightWebView);
    }

    private void configureOneWebView(WebView view) {
        if (view == null) return;
        WebSettings settings = view.getSettings();
        boolean fixedLayout = "EPUB".equals(activity.docType) && activity.epubFixedLayoutLike;
        if (fixedLayout) {
            settings.setUseWideViewPort(false);
            settings.setLoadWithOverviewMode(false);
            settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
            settings.setTextZoom(100);
            view.setInitialScale(100);
        } else {
            settings.setLoadWithOverviewMode(false);
            settings.setUseWideViewPort(false);
            settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);
            settings.setTextZoom(activity.documentTextZoomPercent());
        }
    }
}
