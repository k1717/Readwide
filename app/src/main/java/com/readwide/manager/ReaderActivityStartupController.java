package com.readwide.manager;

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

import com.readwide.manager.controller.AutoPageTurnController;
import com.readwide.manager.search.LargeTextSearchEngine;
import com.readwide.manager.util.BookmarkManager;
import com.readwide.manager.util.PrefsManager;
import com.readwide.manager.util.ThemeManager;
import com.readwide.manager.view.CustomReaderView;

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
        // Save the previous file's state first (a background trim has already
        // saved it and made this a no-op in that case), then drop every
        // transient restore artifact - pending trim, released-memory flag,
        // stored restore intent, loaded text snapshot, in-flight partition
        // switch - so nothing recorded for the previous file can fire after
        // the new file loads.
        activity.saveReadingState();
        activity.discardTransientRestoreStateForNewLoad();
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
        activity.ttsFloatingCard = activity.findViewById(R.id.tts_floating_card);
        activity.ttsFloatingPlayPause = activity.findViewById(R.id.tts_floating_play_pause);
        activity.ttsFloatingStop = activity.findViewById(R.id.tts_floating_stop);
        setupTtsFloatingCard(activity);
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

    /**
     * Wires up the floating TTS control card over the text reader: per-button
     * click listeners for accessibility/normal taps, plus a card-level touch
     * listener that lets the whole card be dragged anywhere on screen and routes
     * a non-drag press to whichever button it landed on. The card-level listener
     * takes the gesture on ACTION_DOWN so a drag can start from any point on the
     * card, including over a button.
     */
    private void setupTtsFloatingCard(@NonNull ReaderActivity activity) {
        if (activity.ttsFloatingCard == null) return;

        if (activity.ttsFloatingPlayPause != null) {
            activity.ttsFloatingPlayPause.setOnClickListener(v ->
                    activity.readerTtsFloatingTogglePlayPause());
        }
        if (activity.ttsFloatingStop != null) {
            activity.ttsFloatingStop.setOnClickListener(v ->
                    activity.readerTtsFloatingStop());
        }

        final View card = activity.ttsFloatingCard;
        final android.view.View playPause = activity.ttsFloatingPlayPause;
        final android.view.View stopBtn = activity.ttsFloatingStop;
        final int touchSlop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
        final float[] downRaw = new float[2];
        final float[] startXY = new float[2];
        final boolean[] dragging = {false};
        card.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downRaw[0] = event.getRawX();
                    downRaw[1] = event.getRawY();
                    startXY[0] = card.getTranslationX();
                    startXY[1] = card.getTranslationY();
                    dragging[0] = false;
                    return true; // take the whole gesture so dragging works anywhere
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downRaw[0];
                    float dy = event.getRawY() - downRaw[1];
                    if (!dragging[0] && Math.hypot(dx, dy) > touchSlop) {
                        dragging[0] = true;
                    }
                    if (dragging[0]) {
                        float nx = startXY[0] + dx;
                        float ny = startXY[1] + dy;
                        View parent = (View) card.getParent();
                        if (parent != null) {
                            float maxX = (parent.getWidth() - card.getWidth()) / 2f;
                            float maxY = parent.getHeight() - card.getHeight() - card.getTop();
                            float minY = -card.getTop();
                            if (maxX < 0) maxX = 0;
                            nx = Math.max(-maxX, Math.min(maxX, nx));
                            ny = Math.max(minY, Math.min(maxY, ny));
                        }
                        card.setTranslationX(nx);
                        card.setTranslationY(ny);
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                    if (!dragging[0]) {
                        // Treat as a tap: route to the button under the finger.
                        if (playPause != null && isPointInsideView(event.getRawX(), event.getRawY(), playPause)) {
                            activity.readerTtsFloatingTogglePlayPause();
                        } else if (stopBtn != null && isPointInsideView(event.getRawX(), event.getRawY(), stopBtn)) {
                            activity.readerTtsFloatingStop();
                        }
                    }
                    dragging[0] = false;
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    dragging[0] = false;
                    return true;
            }
            return false;
        });
    }

    private static boolean isPointInsideView(float rawX, float rawY, @NonNull View view) {
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= loc[0] + view.getWidth()
                && rawY >= loc[1] && rawY <= loc[1] + view.getHeight();
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

    private int lastLayoutReindexWidth = -1;
    private int lastLayoutReindexHeight = -1;

    private void bindReaderViewCallbacks() {
        activity.readerView.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                       oldLeft, oldTop, oldRight, oldBottom) -> {
            if (!activity.largeTextEstimateActive) return;
            int width = right - left;
            int height = bottom - top;
            // Returning from the home screen or another app reattaches the view and
            // reports old bounds of 0, which is not a real geometry change. Only
            // restart indexing when the size actually differs from the last indexed one.
            if (width <= 0 || height <= 0) return;
            if (width == lastLayoutReindexWidth && height == lastLayoutReindexHeight) return;
            lastLayoutReindexWidth = width;
            lastLayoutReindexHeight = height;
            activity.scheduleLargeTextExactPageIndexingRestart();
        });
        activity.readerView.setReaderListener(new CustomReaderView.ReaderListener() {
            @Override public void onSingleTap(float x, float y) {
                // A single tap is never a selection gesture. If a floating
                // selection ActionMode bubble is still showing (e.g. the
                // selection range was already cleared by an earlier event but
                // the bubble outlived it), dismiss it here instead of paging,
                // so an empty-area tap reliably removes the bubble.
                if (activity.dismissLingeringTxtSelectionBubble()) {
                    return;
                }
                activity.tapNavigation().handleSingleTap(x, y);
            }
            @Override public void onTextLongPress(String selectedText, int startPosition,
                                                  int endPosition, float x, float y) {
                activity.showTxtSelectedTextActionDialog(selectedText, startPosition, endPosition);
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
            @Override public void onTextSelectionCleared() {
                activity.onReaderTextSelectionCleared();
            }
        });
    }
}
