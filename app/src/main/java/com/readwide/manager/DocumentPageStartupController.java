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
        installEpubContentSafeInsets();
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

    /**
     * EPUB removes the compact normal-flow page counter, so its content column
     * must own the Android top/bottom safe frame directly. Keep this padding
     * stable while Readwide's overlay controls toggle; otherwise the WebView
     * extends below a punch hole or navigation bar when the overlays are GONE.
     *
     * The root inset helper already owns physical left/right cutouts. This view
     * therefore adds only any remaining side system-bar inset (for example a
     * landscape three-button navigation rail) plus the stable top/bottom edge.
     */
    private void installEpubContentSafeInsets() {
        View content = activity.findViewById(R.id.document_content_column);
        if (content == null) return;
        View topScrim = activity.findViewById(R.id.document_system_bar_scrim_top);
        View bottomScrim = activity.findViewById(R.id.document_system_bar_scrim_bottom);
        View startScrim = activity.findViewById(R.id.document_system_bar_scrim_start);
        View endScrim = activity.findViewById(R.id.document_system_bar_scrim_end);
        final int baseLeft = content.getPaddingLeft();
        final int baseTop = content.getPaddingTop();
        final int baseRight = content.getPaddingRight();
        final int baseBottom = content.getPaddingBottom();

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            int extraLeft = 0;
            int extraTop = 0;
            int extraRight = 0;
            int extraBottom = 0;
            int stableLeft = 0;
            int stableTop = 0;
            int stableRight = 0;
            int stableBottom = 0;
            if ("EPUB".equals(activity.docType)) {
                androidx.core.graphics.Insets bars = insets.getInsetsIgnoringVisibility(
                        androidx.core.view.WindowInsetsCompat.Type.systemBars());
                androidx.core.graphics.Insets cutout = insets.getInsetsIgnoringVisibility(
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout());
                stableLeft = Math.max(bars.left, cutout.left);
                stableTop = Math.max(bars.top, cutout.top);
                stableRight = Math.max(bars.right, cutout.right);
                stableBottom = Math.max(bars.bottom, cutout.bottom);
                extraLeft = Math.max(0, stableLeft - cutout.left);
                extraRight = Math.max(0, stableRight - cutout.right);
                extraTop = stableTop;
                extraBottom = stableBottom;
            }
            setPaddingIfChanged(v,
                    baseLeft + extraLeft,
                    baseTop + extraTop,
                    baseRight + extraRight,
                    baseBottom + extraBottom);
            boolean epub = "EPUB".equals(activity.docType);
            setSystemBarScrim(topScrim, android.view.Gravity.TOP,
                    ViewGroup.LayoutParams.MATCH_PARENT, stableTop, epub && stableTop > 0);
            setSystemBarScrim(bottomScrim, android.view.Gravity.BOTTOM,
                    ViewGroup.LayoutParams.MATCH_PARENT, stableBottom, epub && stableBottom > 0);
            setSystemBarScrim(startScrim, android.view.Gravity.START,
                    stableLeft, ViewGroup.LayoutParams.MATCH_PARENT, epub && stableLeft > 0);
            setSystemBarScrim(endScrim, android.view.Gravity.END,
                    stableRight, ViewGroup.LayoutParams.MATCH_PARENT, epub && stableRight > 0);
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(content);
    }

    private static void setSystemBarScrim(View scrim,
                                          int gravity,
                                          int width,
                                          int height,
                                          boolean visible) {
        if (scrim == null) return;
        ViewGroup.LayoutParams raw = scrim.getLayoutParams();
        if (raw instanceof android.widget.FrameLayout.LayoutParams) {
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) raw;
            if (lp.width != width || lp.height != height || lp.gravity != gravity) {
                lp.width = width;
                lp.height = height;
                lp.gravity = gravity;
                scrim.setLayoutParams(lp);
            }
        }
        int targetVisibility = visible ? View.VISIBLE : View.GONE;
        if (scrim.getVisibility() != targetVisibility) {
            scrim.setVisibility(targetVisibility);
        }
    }

    private static void setPaddingIfChanged(@NonNull View view,
                                            int left,
                                            int top,
                                            int right,
                                            int bottom) {
        if (view.getPaddingLeft() == left
                && view.getPaddingTop() == top
                && view.getPaddingRight() == right
                && view.getPaddingBottom() == bottom) {
            return;
        }
        view.setPadding(left, top, right, bottom);
    }

    private void bindViews() {
        activity.documentAppBar = activity.findViewById(R.id.document_appbar);
        activity.documentBottomChrome = activity.findViewById(R.id.document_bottom_scroller);
        activity.documentNavBarSpacer = activity.findViewById(R.id.document_nav_bar_spacer);
        activity.ttsFloatingCard = activity.findViewById(R.id.tts_floating_card);
        activity.ttsFloatingPlayPause = activity.findViewById(R.id.tts_floating_play_pause);
        activity.ttsFloatingStop = activity.findViewById(R.id.tts_floating_stop);
        activity.setupTtsFloatingCard();
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
