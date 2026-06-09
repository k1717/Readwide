package com.textview.reader;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.textview.reader.controller.AutoPageTurnController;
import com.textview.reader.search.LargeTextSearchEngine;
import com.textview.reader.util.BookmarkManager;
import com.textview.reader.util.PrefsManager;
import com.textview.reader.util.ThemeManager;
import com.textview.reader.view.CustomReaderView;

final class ReaderActivityStartupController {
    private final ReaderActivity activity;

    ReaderActivityStartupController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void onCreateAfterSuper(Bundle savedInstanceState) {
        ViewerRegistry.activate(activity);
        TtsPlaybackBridge.register(activity);
        configureWindow();
        bindViews();
        bindServicesAndControllers();
        bindReaderViewCallbacks();

        activity.applyPreferences();
        activity.applyTheme();
        activity.applyReaderInsets();
        activity.setupBottomControls();
        activity.readerSeek().setupSeekBar();
        activity.getOnBackPressedDispatcher().addCallback(activity, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                activity.handleViewerBackPressed();
            }
        });

        if (activity.prefs.getBrightnessOverride()) {
            activity.applyReaderBrightnessOverride(activity.prefs.getBrightnessValue());
        } else {
            activity.clearReaderBrightnessOverride();
        }

        if (!activity.restoreLoadedTextSnapshotIfAvailable(activity.getIntent(), savedInstanceState)) {
            activity.loadFileFromIntent(activity.getIntent());
        }
    }

    void onResume() {
        TtsPlaybackBridge.register(activity);
        activity.cancelBackgroundMemoryTrim();
        if (activity.themeManager != null) {
            activity.themeManager.reloadFromStorage();
        }
        if (activity.readerView != null && activity.prefs != null && activity.themeManager != null) {
            activity.applyTheme();
            ButtonOrderManager.applyOrder(activity, activity.prefs, ButtonOrderManager.GROUP_TXT_READER);
            if (activity.restoreReaderAfterBackgroundMemoryTrimIfNeeded()) return;
            if (activity.maybeReloadForPhysicallyEditedOriginalTxtFile()) return;
            if (activity.maybeReloadForLargeTextPartitionModeChange()) return;
            activity.maybeReloadForTextDisplayRuleChange();
            activity.updatePositionLabel();
        }
    }

    void onNewIntent(@NonNull android.content.Intent intent) {
        activity.saveReadingState();
        activity.setIntent(intent);
        activity.clearPendingToolbarSeekJump();
        activity.activeSearchQuery = "";
        activity.activeSearchIndex = -1;
        activity.activeSearchOrdinal = 0;
        activity.applySearchHighlight();
        activity.clearLargeTextSearchTotalCache();
        activity.clearLoadedTextSnapshot();
        activity.loadFileFromIntent(intent);
    }

    private void configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        activity.getWindow().setStatusBarColor(Color.BLACK);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(
                        activity.getWindow(), activity.getWindow().getDecorView());
        controller.setAppearanceLightNavigationBars(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.getWindow().setNavigationBarContrastEnforced(false);
        }

        activity.setContentView(R.layout.activity_reader);
    }

    private void bindViews() {
        activity.readerRoot = activity.findViewById(R.id.reader_root);
        activity.toolbar = activity.findViewById(R.id.toolbar);
        activity.setSupportActionBar(activity.toolbar);
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        activity.toolbar.setVisibility(View.GONE);

        activity.readerView = activity.findViewById(R.id.reader_view);
        activity.loadingBox = activity.findViewById(R.id.loading_box);
        activity.progressBar = activity.findViewById(R.id.loading_progress);
        activity.progressText = activity.findViewById(R.id.loading_text);
        activity.bottomBar = activity.findViewById(R.id.bottom_bar);
        if (activity.bottomBar != null) {
            activity.bottomBar.setElevation(0f);
            activity.bottomBar.setTranslationZ(0f);
            ViewCompat.setElevation(activity.bottomBar, 0f);
        }
        activity.navBarSpacer = activity.findViewById(R.id.nav_bar_spacer);
        activity.pageDragPanel = activity.findViewById(R.id.page_drag_panel);
        activity.seekBar = activity.findViewById(R.id.seek_bar);
        activity.positionLabel = activity.findViewById(R.id.position_label);
        activity.readerPageStatus = activity.findViewById(R.id.reader_page_status);
        activity.readerFileTitle = activity.findViewById(R.id.reader_file_title);
        activity.updateReaderFileTitle();
        activity.updateReaderFileTitleVisibility();
        activity.updateLoadingIndicatorColors(activity.currentReaderBackgroundColor);
    }

    private void bindServicesAndControllers() {
        activity.bookmarkManager = BookmarkManager.getInstance(activity);
        activity.themeManager = ThemeManager.getInstance(activity);
        activity.largeTextSearchEngine = new LargeTextSearchEngine(
                activity.getApplicationContext(), activity::openLargeTextReader);
        activity.autoPageTurnController = new AutoPageTurnController(
                activity.handler,
                new AutoPageTurnController.Callback() {
                    @Override public boolean isDestroyed() { return activity.activityDestroyed; }
                    @Override public int getDisplayedTotalPageCount() {
                        return activity.getDisplayedTotalPageCount();
                    }
                    @Override public int getDisplayedCurrentPageNumber() {
                        return activity.getDisplayedCurrentPageNumber();
                    }
                    @Override public int getIntervalSeconds() {
                        return activity.prefs != null
                                ? activity.prefs.getAutoPageTurnIntervalSeconds()
                                : 8;
                    }
                    @Override public void pageForwardFromAutoPageTurn() {
                        activity.pageBy(+1, true);
                    }
                    @Override public void onAutoPageTurnStarted() {
                        ShortToast.show(activity, R.string.auto_page_turn_started);
                    }
                    @Override public void onAutoPageTurnStopped() {
                        ShortToast.show(activity, R.string.auto_page_turn_stopped);
                    }
                    @Override public void onAutoPageTurnEndReached() {
                        ShortToast.show(activity, R.string.auto_page_turn_end_reached);
                    }
                });
    }

    private void bindReaderViewCallbacks() {
        activity.readerView.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                       oldLeft, oldTop, oldRight, oldBottom) -> {
            if (!activity.largeTextEstimateActive) return;
            boolean widthChanged = (right - left) != (oldRight - oldLeft);
            boolean heightChanged = (bottom - top) != (oldBottom - oldTop);
            if (widthChanged || heightChanged) {
                activity.scheduleLargeTextExactPageIndexingRestart();
            }
        });
        activity.readerView.setReaderListener(new CustomReaderView.ReaderListener() {
            @Override public void onSingleTap(float x, float y) {
                activity.tapNavigation().handleSingleTap(x, y);
            }
            @Override public void onTextLongPress(String selectedText, int charPosition, float x, float y) {
                activity.showQuickTextDisplayRuleDialog(selectedText, true);
            }
            @Override public void onReaderScrollChanged() {
                activity.onScrollChanged();
            }
            @Override public void onReaderManualScroll() {
                activity.stopAutoPageTurnForManualNavigation();
            }
            @Override public void onReaderManualOverscroll(int direction) {
                activity.handleLargeTextManualOverscroll(direction);
            }
        });
    }
}
