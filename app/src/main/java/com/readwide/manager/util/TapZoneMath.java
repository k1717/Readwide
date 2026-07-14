package com.readwide.manager.util;

public final class TapZoneMath {
    public static final int ACTION_MENU = 0;
    public static final int ACTION_PREVIOUS = -1;
    public static final int ACTION_NEXT = 1;

    private TapZoneMath() {}

    public static int actionForTap(float x,
                                   float y,
                                   int width,
                                   int height,
                                   boolean hasContent,
                                   boolean tapPagingEnabled,
                                   int tapZoneMode,
                                   int leadingPercent,
                                   int trailingPercent) {
        if (!hasContent || !tapPagingEnabled) return ACTION_MENU;

        float leading = clampF(leadingPercent, 5f, 80f);
        float trailing = clampF(trailingPercent, 5f, 80f);
        if (leading + trailing > 90f) {
            trailing = Math.max(5f, 90f - leading);
        }

        float leadingRatio = leading / 100f;
        float trailingStartRatio = 1f - (trailing / 100f);

        if (tapZoneMode == PrefsManager.TAP_ZONE_HORIZONTAL) {
            if (width <= 0) return ACTION_MENU;
            float leftBoundary = width * leadingRatio;
            float rightBoundary = width * trailingStartRatio;
            if (x < leftBoundary) return ACTION_PREVIOUS;
            if (x > rightBoundary) return ACTION_NEXT;
            return ACTION_MENU;
        }

        if (height <= 0) return ACTION_MENU;
        float topBoundary = height * leadingRatio;
        float bottomBoundary = height * trailingStartRatio;
        if (y < topBoundary) return ACTION_PREVIOUS;
        if (y > bottomBoundary) return ACTION_NEXT;
        return ACTION_MENU;
    }

    /**
     * Returns true only for a short, single-pointer, stationary release that may
     * safely trigger a tap-zone action. Long presses belong to native text
     * selection and must never be converted into page turns on ACTION_UP.
     */
    public static boolean isShortTapRelease(float deltaX,
                                            float deltaY,
                                            float touchSlop,
                                            long durationMs,
                                            long longPressTimeoutMs,
                                            boolean selectionActive,
                                            boolean swipeTriggered,
                                            boolean hadMultiplePointers) {
        if (selectionActive || swipeTriggered || hadMultiplePointers) return false;
        if (touchSlop < 0f || durationMs < 0L || longPressTimeoutMs <= 0L) return false;
        if (durationMs >= longPressTimeoutMs) return false;
        return Math.abs(deltaX) <= touchSlop && Math.abs(deltaY) <= touchSlop;
    }

    private static float clampF(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
