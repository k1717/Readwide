package com.readwide.manager;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import com.readwide.manager.util.BookmarkManager;

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
        com.readwide.manager.util.EdgeToEdgeUtil.applyFoldableChromeInsetsImeFixed(
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
        activity.documentToolbarController = new com.readwide.manager.controller.ReaderToolbarController(
                activity, activity.findViewById(R.id.document_bottom_bar));
        activity.documentToolbarController.setupScrollableActionStrip(
                R.id.document_toolbar_action_scroll,
                R.id.document_bottom_bar,
                5,
                0);
        ButtonOrderManager.applyOrder(activity, activity.prefs, ButtonOrderManager.GROUP_DOCUMENT_VIEWER);
        activity.bookmarkManager = BookmarkManager.getInstance(activity);
        activity.applyDocumentThemeToViews();
        activity.setupWebView();
        activity.installDocumentFastScroll();
        activity.setupButtons();
        activity.installSwipePaging();
        activity.loadFromIntent(activity.getIntent());
    }

    void onNewIntent(@NonNull android.content.Intent intent) {
        activity.setIntent(intent);
        activity.loadFromIntent(intent);
    }

    void onResume() {
        com.readwide.manager.util.ThemeManager.getInstance(activity).reloadFromStorage();
        String currentThemeSignature = activity.documentThemeSignature();
        boolean pageThemeChanged = activity.lastAppliedDocumentThemeSignature != null
                && !activity.lastAppliedDocumentThemeSignature.equals(currentThemeSignature);
        if (activity.webView != null) {
            activity.webView.onResume();
            activity.webView.resumeTimers();
        }
        if (activity.rightWebView != null) {
            activity.rightWebView.onResume();
            activity.rightWebView.resumeTimers();
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
        if (activity.rightWebView != null) {
            activity.rightWebView.onPause();
            activity.rightWebView.pauseTimers();
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
            androidx.core.graphics.Insets cutout = insets.getInsetsIgnoringVisibility(
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            int stableTop = Math.max(0, cutout.top);
            if (activity.prefs != null && activity.prefs.getShowStatusBar()) {
                androidx.core.graphics.Insets status = insets.getInsetsIgnoringVisibility(
                        androidx.core.view.WindowInsetsCompat.Type.statusBars());
                stableTop = Math.max(stableTop, status.top);
            }
            // This view lives in normal layout flow above the WebView.  Keep its
            // height tied only to the configured status-bar policy and physical
            // cutout. A transient immersive-bar reveal must overlay rather than
            // resize the WebView/EPUB canvas.
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            int targetHeight = baseHeight + stableTop;
            if (lp != null && lp.height != targetHeight) {
                lp.height = targetHeight;
                v.setLayoutParams(lp);
            }
            v.setMinimumHeight(targetHeight);
            v.setPadding(baseLeft, baseTop + stableTop, baseRight, baseBottom);
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(topPageStatus);
    }


    private void installNavigationBarSpacerInsets() {
        View spacer = activity.findViewById(R.id.document_nav_bar_spacer);
        if (spacer == null) return;
        spacer.setBackgroundColor(activity.readerBg);
        android.view.ViewGroup.LayoutParams lp = spacer.getLayoutParams();
        if (lp != null && lp.height != 0) {
            lp.height = 0;
            spacer.setLayoutParams(lp);
        }
        spacer.setVisibility(View.GONE);
    }

    private void bindViews() {
        activity.documentAppBar = activity.findViewById(R.id.document_appbar);
        activity.documentBottomChrome = activity.findViewById(R.id.document_bottom_scroller);
        activity.documentNavBarSpacer = activity.findViewById(R.id.document_nav_bar_spacer);
        activity.ttsFloatingCard = activity.findViewById(R.id.tts_floating_card);
        activity.ttsFloatingPlayPause = activity.findViewById(R.id.tts_floating_play_pause);
        activity.ttsFloatingStop = activity.findViewById(R.id.tts_floating_stop);
        activity.setupTtsFloatingCard();
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

        activity.documentSpreadContainer = activity.findViewById(R.id.document_spread_container);
        activity.webView = activity.findViewById(R.id.document_webview);
        activity.rightWebView = activity.findViewById(R.id.document_webview_right);
        activity.documentFastScrollRail = activity.findViewById(R.id.document_fast_scroll_rail);
        activity.documentFastScrollThumb = activity.findViewById(R.id.document_fast_scroll_thumb);
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
