package com.textview.reader;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

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
                null,
                () -> activity.documentChromeVisible);
        activity.applyDocumentSystemBarColors();
        installStandaloneTopPageStatusInsets();
        installNavigationBarSpacerInsets();

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

    private void installStandaloneTopPageStatusInsets() {
        View topPageStatus = activity.findViewById(R.id.document_top_page_status);
        if (topPageStatus == null) return;
        final int baseLeft = topPageStatus.getPaddingLeft();
        final int baseTop = topPageStatus.getPaddingTop();
        final int baseRight = topPageStatus.getPaddingRight();
        final int baseBottom = topPageStatus.getPaddingBottom();
        final int baseHeight = activity.dpToPx(32f);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(topPageStatus, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            // This view lives in normal layout flow above the WebView.  Keep its
            // height stable and let it own the status-bar inset; do not add padding
            // to the WebView/viewport itself, because WebView padding clips EPUB
            // pages and fixed-layout scaling.
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            int targetHeight = baseHeight + bars.top;
            if (lp != null && lp.height != targetHeight) {
                lp.height = targetHeight;
                v.setLayoutParams(lp);
            }
            v.setMinimumHeight(targetHeight);
            v.setPadding(baseLeft, baseTop + bars.top, baseRight, baseBottom);
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(topPageStatus);
    }


    private void installNavigationBarSpacerInsets() {
        View spacer = activity.findViewById(R.id.document_nav_bar_spacer);
        if (spacer == null) return;
        spacer.setBackgroundColor(activity.readerBg);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(spacer, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.height != bars.bottom) {
                lp.height = bars.bottom;
                v.setLayoutParams(lp);
            }
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(spacer);
    }

    private void bindViews() {
        activity.documentAppBar = activity.findViewById(R.id.document_appbar);
        activity.documentBottomChrome = activity.findViewById(R.id.document_bottom_scroller);
        activity.documentNavBarSpacer = activity.findViewById(R.id.document_nav_bar_spacer);
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
        activity.loadingBox = activity.findViewById(R.id.loading_box);
        activity.progressBar = activity.findViewById(R.id.loading_progress);
        activity.progressText = activity.findViewById(R.id.loading_text);
        activity.pageStatus = activity.findViewById(R.id.document_page_status);
        activity.topPageStatus = activity.findViewById(R.id.document_top_page_status);
        activity.documentPageSeekBar = activity.findViewById(R.id.document_page_seek_bar);
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
