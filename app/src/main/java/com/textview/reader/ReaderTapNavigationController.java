package com.textview.reader;

import androidx.annotation.NonNull;

import com.textview.reader.util.PrefsManager;
import com.textview.reader.util.TapZoneMath;

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
}
