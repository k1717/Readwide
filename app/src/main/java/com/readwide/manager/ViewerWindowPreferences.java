package com.readwide.manager;

import android.view.Window;
import android.view.WindowManager;

/** Shared window preferences for document, PDF and image readers. */
final class ViewerWindowPreferences {
    private ViewerWindowPreferences() {}

    static void applyKeepScreenOn(Window window, boolean enabled) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }
}
