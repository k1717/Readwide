package com.readwide.manager;

import android.util.DisplayMetrics;

import androidx.annotation.NonNull;

/**
 * Width policy for compact main-screen action menus.
 *
 * Long-hold sheets intentionally keep the old stable dialog width as the base
 * and shrink it by a fixed ratio. Toolbar dropdowns size themselves from their
 * localized labels in their owning controllers.
 */
final class MainActionPopupSizing {
    private static final float LONG_HOLD_WIDTH_RATIO = 0.85f;

    private MainActionPopupSizing() {}

    static int longHoldSheetWidth(@NonNull MainActivity activity) {
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int baseWidth = Math.max(activity.dpToPx(220), Math.min(Math.round(dm.widthPixels * 0.85f), activity.dpToPx(460)));
        int reduced = Math.round(baseWidth * LONG_HOLD_WIDTH_RATIO);
        int min = activity.dpToPx(196);
        return Math.max(min, reduced);
    }

}
