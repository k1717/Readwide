package com.readwide.manager.util;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

/**
 * Shared screen-orientation (rotation) toggle used by the viewer toolbars
 * (TXT, document, PDF, image). This is screen rotation only and is unrelated
 * to any page-slide direction setting a viewer may have.
 */
public final class ScreenOrientationToggle {

    private ScreenOrientationToggle() {}

    /** True when the activity is currently displayed in landscape. */
    public static boolean isLandscape(Activity activity) {
        if (activity == null) return false;
        return activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * Flips the requested orientation between sensor-landscape and
     * sensor-portrait. Returns the new "is landscape" state so callers can
     * update their button icon/label.
     */
    public static boolean toggle(Activity activity) {
        boolean switchToLandscape = !isLandscape(activity);
        if (activity != null) {
            activity.setRequestedOrientation(switchToLandscape
                    ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
        return switchToLandscape;
    }

    /**
     * Sets the bottom-bar rotation button's top drawable to reflect the current
     * orientation: the landscape (monitor) icon while in landscape, the portrait
     * icon while in portrait. Mirrors the image viewer's behavior. Pass the
     * landscape and portrait drawable resource ids.
     */
    public static void applyButtonIcon(Activity activity, android.widget.TextView button,
                                       int landscapeIconRes, int portraitIconRes) {
        if (button == null) return;
        int iconRes = isLandscape(activity) ? landscapeIconRes : portraitIconRes;
        button.setCompoundDrawablesWithIntrinsicBounds(0, iconRes, 0, 0);
    }
}
