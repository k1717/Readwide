package com.readwide.manager;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;

import androidx.annotation.NonNull;

import com.readwide.manager.widget.SelectionContrastWebView;

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
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(false);
        cookies.setAcceptThirdPartyCookies(view, false);

        view.setBackgroundColor(activity.readerBg);
        view.setLongClickable(primary);
        view.setHapticFeedbackEnabled(primary);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (primary && (scrollX != oldScrollX || scrollY != oldScrollY)) {
                activity.notifyDocumentFastScrollActivity();
            }
            if (!primary) return;
            if (activity.isMarkdownDocument() && Math.abs(scrollY - oldScrollY) > activity.dpToPx(1)) {
                activity.updateMarkdownVisualPageModel(false);
                activity.scheduleMarkdownSourceAnchorUpdate();
            }
            if (activity.isRenderedContentAnchorDocument()
                    && (Math.abs(scrollX - oldScrollX) > activity.dpToPx(1)
                    || Math.abs(scrollY - oldScrollY) > activity.dpToPx(1))) {
                activity.scheduleDocumentContentAnchorUpdate();
            }
            if ("Word".equals(activity.docType)
                    && Math.abs(scrollY - oldScrollY) > activity.dpToPx(1)) {
                activity.webView.removeCallbacks(activity.checkWordSelectionAfterScrollRunnable);
                activity.webView.postDelayed(activity.checkWordSelectionAfterScrollRunnable, 90);
            }
        });
        if (primary) {
            view.addJavascriptInterface(activity.new WordSelectionBridge(), "ReadwideSelectionBridge");
            if (view instanceof SelectionContrastWebView) {
                ((SelectionContrastWebView) view).setAnnotationActionListener(
                        new SelectionContrastWebView.AnnotationActionListener() {
                            @Override
                            public boolean isAnnotationActionAvailable() {
                                return activity.isMarkdownDocument();
                            }

                            @Override
                            public void onAnnotationAction(boolean highlight,
                                                           android.view.ActionMode mode) {
                                activity.captureMarkdownSelectionForAnnotation(highlight, mode);
                            }
                        });
            }
        }

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                return activity.handleEpubInternalNavigation(view, request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(@NonNull WebView view, String url) {
                return activity.handleEpubInternalNavigation(view, Uri.parse(url));
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(
                    @NonNull WebView view,
                    @NonNull WebResourceRequest request) {
                return activity.interceptLocalResource(request.getUrl());
            }

            @Override
            public void onScaleChanged(@NonNull WebView view, float oldScale, float newScale) {
                super.onScaleChanged(view, oldScale, newScale);
                if (primary) activity.scheduleDocumentFastScrollUpdate();
            }

            @Override
            public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                super.onPageFinished(view, url);
                if (activity.activityDestroyed
                        || !activity.isCurrentDocumentPageLoad(view, url)) return;
                activity.applyEpubBoundaryCssToLoadedWebView(view);
                activity.scheduleDocumentFastScrollUpdate();
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
                activity.applyMarkdownAnnotationHighlights();
                activity.restoreMarkdownVisualPositionAfterLoadIfNeeded(view);
                activity.scheduleMarkdownVisualPageModelUpdate();
                activity.scheduleMarkdownSourceAnchorUpdate();
                activity.installDocumentContentAnchorScript();
                activity.restoreDocumentContentAnchorAfterLoadIfNeeded(view);
                activity.documentTtsHighlight().installScript();
                activity.applyPendingEpubAnchorAfterPageLoad(view);
                activity.onEpubPrimaryPageLoaded(view);
                if (activity.isRenderedContentAnchorDocument()) {
                    activity.webView.postDelayed(activity::saveReadingState, 180);
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
        int pageIndex = view == activity.rightWebView
                ? activity.documentRightSpreadPageIndex() : activity.currentPage;
        boolean fixedLayout = activity.epubPageKeepsOriginalLayout(pageIndex);
        if (fixedLayout) {
            settings.setUseWideViewPort(false);
            settings.setLoadWithOverviewMode(false);
            settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
            settings.setTextZoom(100);
            view.setInitialScale(100);
        } else {
            settings.setLoadWithOverviewMode(false);
            settings.setUseWideViewPort(false);
            settings.setLayoutAlgorithm("EPUB".equals(activity.docType)
                    && activity.epubVerticalWritingLike
                    ? WebSettings.LayoutAlgorithm.NORMAL
                    : WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);
            settings.setTextZoom(activity.documentTextZoomPercent());
            view.setInitialScale(0);
        }
    }
}
