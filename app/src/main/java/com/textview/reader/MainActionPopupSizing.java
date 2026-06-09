package com.textview.reader;

import android.util.DisplayMetrics;

import androidx.annotation.NonNull;

/**
 * Width policy for compact main-screen action menus.
 *
 * Long-hold sheets intentionally keep the old stable dialog width as the base
 * and shrink it by a fixed ratio. The multi-selection dropdown intentionally
 * uses a fixed compact width.
 */
final class MainActionPopupSizing {
    private static final float LONG_HOLD_WIDTH_RATIO = 0.85f;
    private static final int SELECTION_DROPDOWN_DP = 168;

    private MainActionPopupSizing() {}

    static int longHoldSheetWidth(@NonNull MainActivity activity) {
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int baseWidth = Math.max(activity.dpToPx(220), Math.min(Math.round(dm.widthPixels * 0.85f), activity.dpToPx(460)));
        int reduced = Math.round(baseWidth * LONG_HOLD_WIDTH_RATIO);
        int min = activity.dpToPx(196);
        return Math.max(min, reduced);
    }

    static int selectionDropdownWidth(@NonNull MainActivity activity) {
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int fixedWidth = activity.dpToPx(SELECTION_DROPDOWN_DP);
        int maxByScreen = Math.round(dm.widthPixels * 0.76f);
        return Math.min(fixedWidth, maxByScreen);
    }
}
