package com.readwide.manager;

import androidx.annotation.NonNull;

import com.readwide.manager.util.PrefsManager;
import com.readwide.manager.util.TapZoneMath;

final class ReaderTapNavigationController {
    private final ReaderActivity activity;

    ReaderTapNavigationController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void handleSingleTap(float x, float y) {
        int width = activity.readerView != null ? activity.readerView.getWidth() : 0;
        int height = activity.readerView != null ? activity.readerView.getHeight() : 0;
        boolean hasContent = activity.fileContent != null && !activity.fileContent.isEmpty();
        boolean tapPagingEnabled = activity.prefs != null && activity.prefs.getTapPagingEnabled();
        int mode = activity.prefs != null
                ? activity.prefs.getTapZoneMode()
                : PrefsManager.TAP_ZONE_HORIZONTAL;
        int leading = activity.prefs != null ? activity.prefs.getTapLeadingZonePercent() : 35;
        int trailing = activity.prefs != null ? activity.prefs.getTapTrailingZonePercent() : 35;

        // The bottom bar floats over the full-screen reader view. A tap landing on
        // the visible bar must not also page the text underneath it.
        if (tapOnVisibleBottomBar(x, y)) {
            if (activity.toolbarVisible) activity.toggleToolbar();
            else activity.showToolbar();
            return;
        }

        int action = TapZoneMath.actionForTap(
                x,
                y,
                width,
                height,
                hasContent,
                tapPagingEnabled,
                mode,
                leading,
                trailing);
        if (action == TapZoneMath.ACTION_PREVIOUS) {
            activity.pageUp();
        } else if (action == TapZoneMath.ACTION_NEXT) {
            activity.pageDown();
        } else {
            if (activity.toolbarVisible) activity.toggleToolbar();
            else activity.showToolbar();
        }
    }

    private boolean tapOnVisibleBottomBar(float viewX, float viewY) {
        if (!activity.toolbarVisible || activity.readerView == null || activity.bottomBar == null) {
            return false;
        }
        if (activity.bottomBar.getVisibility() != android.view.View.VISIBLE
                || activity.bottomBar.getWidth() <= 0 || activity.bottomBar.getHeight() <= 0) {
            return false;
        }
        // Tap coordinates are reader-view-local; convert to screen space.
        int[] viewLoc = new int[2];
        activity.readerView.getLocationOnScreen(viewLoc);
        float screenX = viewX + viewLoc[0];
        float screenY = viewY + viewLoc[1];
        int[] barLoc = new int[2];
        activity.bottomBar.getLocationOnScreen(barLoc);
        return screenX >= barLoc[0] && screenX <= barLoc[0] + activity.bottomBar.getWidth()
                && screenY >= barLoc[1] && screenY <= barLoc[1] + activity.bottomBar.getHeight();
    }
}
