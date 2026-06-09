package com.textview.reader;

import android.graphics.Color;

/**
 * Shared color helpers for reader/main/document UI surfaces.
 *
 * Keeping these calculations in one place avoids the same luminance/blend math
 * drifting across Activity, controller, adapter, and utility classes.
 */
public final class UiColorUtils {
    private UiColorUtils() {}

    public static double luminance255(int color) {
        return 0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color);
    }

    public static boolean isLightColor(int color) {
        return luminance255(color) > 160;
    }

    public static boolean isDarkColor(int color) {
        return (luminance255(color) / 255.0) < 0.5;
    }

    public static boolean isHalfLightColor(int color) {
        return (luminance255(color) / 255.0) >= 0.5;
    }

    public static int blendColors(int base, int overlay, float amount) {
        float clamped = Math.max(0f, Math.min(1f, amount));
        float inv = 1f - clamped;
        return Color.rgb(
                Math.round(Color.red(base) * inv + Color.red(overlay) * clamped),
                Math.round(Color.green(base) * inv + Color.green(overlay) * clamped),
                Math.round(Color.blue(base) * inv + Color.blue(overlay) * clamped));
    }

    public static int readableTextColorForBackground(int backgroundColor) {
        return isLightColor(backgroundColor) ? Color.rgb(32, 32, 32) : Color.rgb(224, 224, 224);
    }

    public static int readableChipTextColorForBackground(int backgroundColor) {
        return luminance255(backgroundColor) > 150 ? Color.rgb(32, 33, 36) : Color.WHITE;
    }
}
