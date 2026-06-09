package com.textview.reader;

import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;

import com.textview.reader.util.ReaderKeyMap;

final class ReaderShellController {
    private static final long VIEWER_DOUBLE_BACK_TIMEOUT_MS = 1000L;
    private static final long VIEWER_BACK_TOAST_DURATION_MS = ShortToast.DURATION_MS;

    private final ReaderActivity activity;
    private long lastViewerBackPressedTime = 0L;
    private Toast viewerBackToast;

    ReaderShellController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void toggleToolbar() {
        setToolbarVisible(!activity.toolbarVisible);
    }

    void showToolbar() {
        setToolbarVisible(true);
    }

    private void setToolbarVisible(boolean visible) {
        activity.toolbarVisible = visible;
        activity.toolbar.setVisibility(View.GONE);
        activity.bottomBar.setVisibility(activity.toolbarVisible ? View.VISIBLE : View.GONE);
        if (activity.toolbarVisible && activity.bottomBar != null) {
            activity.bottomBar.bringToFront();
            activity.bottomBar.post(activity.bottomBar::bringToFront);
            if (activity.readerToolbarController != null) {
                activity.readerToolbarController.resetScrollToDefault();
            }
        }
        if (activity.readerPageStatus != null) activity.readerPageStatus.setVisibility(View.VISIBLE);
        activity.updateReaderFileTitleVisibility();
        activity.updateBottomMenuBackground();
        activity.handler.post(() -> {
            if (activity.activityDestroyed) return;
            if (activity.readerRoot != null) ViewCompat.requestApplyInsets(activity.readerRoot);
            activity.updateNavigationBarForBottomMenu();
        });
        activity.handler.postDelayed(activity::updateNavigationBarForBottomMenu, 60);
    }

    boolean handleReaderPageTurnKey(KeyEvent event) {
        if (event == null || activity.prefs == null || !activity.prefs.getVolumeKeyScroll()) return false;

        int direction = ReaderKeyMap.pageTurnDirectionForKey(event.getKeyCode());
        if (direction == 0) return false;

        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                activity.pageBy(direction);
            }
            return true;
        }

        return action == KeyEvent.ACTION_UP;
    }

    void handleViewerBackPressed() {
        long now = System.currentTimeMillis();

        if (now - lastViewerBackPressedTime <= VIEWER_DOUBLE_BACK_TIMEOUT_MS) {
            cancelViewerBackToast();
            activity.finish();
            return;
        }

        lastViewerBackPressedTime = now;
        showShortViewerBackToast();
    }

    void cancelViewerBackToast() {
        if (viewerBackToast != null) {
            viewerBackToast.cancel();
            viewerBackToast = null;
        }
    }

    private void showShortViewerBackToast() {
        cancelViewerBackToast();

        viewerBackToast = ShortToast.showTracked(
                activity,
                activity.getString(R.string.press_back_again_exit));

        activity.handler.postDelayed(() -> {
            if (viewerBackToast != null) {
                viewerBackToast.cancel();
                viewerBackToast = null;
            }
        }, VIEWER_BACK_TOAST_DURATION_MS);
    }
}
