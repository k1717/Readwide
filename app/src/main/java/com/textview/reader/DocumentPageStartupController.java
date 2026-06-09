package com.textview.reader;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import com.textview.reader.util.BookmarkManager;

final class DocumentPageStartupController {
    private final DocumentPageActivity activity;

    DocumentPageStartupController(@NonNull DocumentPageActivity activity) {
        this.activity = activity;
    }

    void onCreateAfterSuper(Bundle savedInstanceState) {
        ViewerRegistry.activate(activity);
        activity.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);

        activity.resolveReaderThemeColors();
        activity.setContentView(R.layout.activity_document_page);
        activity.applyDocumentSystemBarColors();
        com.textview.reader.util.EdgeToEdgeUtil.applyFoldableChromeInsetsImeFixed(
                activity,
                activity.findViewById(R.id.document_root),
                activity.findViewById(R.id.document_appbar),
                activity.findViewById(R.id.document_bottom_scroller),
                activity.findViewById(R.id.document_viewport),
                () -> activity.documentChromeVisible);
        activity.applyDocumentSystemBarColors();

        bindViews();
        ButtonOrderManager.applyOrder(activity, activity.prefs, ButtonOrderManager.GROUP_DOCUMENT_VIEWER);
        activity.bookmarkManager = BookmarkManager.getInstance(activity);
        activity.applyDocumentThemeToViews();
        activity.setupWebView();
        activity.setupButtons();
        activity.installSwipePaging();
        activity.loadFromIntent(activity.getIntent());
    }

    void onNewIntent(@NonNull android.content.Intent intent) {
        activity.setIntent(intent);
        activity.loadFromIntent(intent);
    }

    void onResume() {
        com.textview.reader.util.ThemeManager.getInstance(activity).reloadFromStorage();
        String currentThemeSignature = activity.documentThemeSignature();
        boolean pageThemeChanged = activity.lastAppliedDocumentThemeSignature != null
                && !activity.lastAppliedDocumentThemeSignature.equals(currentThemeSignature);
        if (activity.webView != null) {
            activity.webView.onResume();
            activity.webView.resumeTimers();
        }
        activity.applyDocumentSystemBarColors();
        ButtonOrderManager.applyOrder(activity, activity.prefs, ButtonOrderManager.GROUP_DOCUMENT_VIEWER);
        activity.applyDocumentThemeToViews();
        activity.refreshEpubSpacingIfNeeded();
        activity.refreshDocumentPageThemeIfNeeded(currentThemeSignature, pageThemeChanged);
    }

    void onPause() {
        activity.saveReadingState();
        if (activity.webView != null) {
            activity.webView.removeCallbacks(activity.checkWordSelectionAfterScrollRunnable);
            activity.webView.removeCallbacks(activity.releasePageTurnRunnable);
            activity.webView.onPause();
            activity.webView.pauseTimers();
        }
    }

    private void bindViews() {
        activity.documentAppBar = activity.findViewById(R.id.document_appbar);
        activity.documentBottomChrome = activity.findViewById(R.id.document_bottom_scroller);
        if (activity.documentBottomChrome != null) {
            activity.documentBottomChrome.addOnLayoutChangeListener((v, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> {
                if ("EPUB".equals(activity.docType) && (bottom - top) != (oldBottom - oldTop)) {
                    activity.applyEpubBoundaryMarginsIfNeeded();
                }
            });
        }
        activity.toolbar = activity.findViewById(R.id.toolbar);
        activity.setSupportActionBar(activity.toolbar);
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        activity.toolbar.setTitleTextColor(Color.WHITE);
        activity.toolbar.setBackgroundColor(Color.BLACK);

        activity.webView = activity.findViewById(R.id.document_webview);
        activity.progressBar = activity.findViewById(R.id.loading_progress);
        activity.pageStatus = activity.findViewById(R.id.document_page_status);
        activity.prevButton = activity.findViewById(R.id.btn_prev_page);
        activity.nextButton = activity.findViewById(R.id.btn_next_page);
        activity.searchButton = activity.findViewById(R.id.btn_document_search);
        activity.pageButton = activity.findViewById(R.id.btn_page_move);
        activity.bookmarkButton = activity.findViewById(R.id.btn_bookmarks);
        activity.moreButton = activity.findViewById(R.id.btn_more);
        activity.documentSearchPanelContainer = activity.findViewById(R.id.document_search_panel_container);
        activity.documentSearchOverlayContainer = activity.findViewById(R.id.document_search_overlay_container);
    }
}
