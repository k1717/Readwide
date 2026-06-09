package com.textview.reader;

import android.graphics.Color;

final class MainColorUtils {
    private MainColorUtils() {}

    static int readableChipTextColorForBackground(int backgroundColor) {
        double luminance = 0.299 * Color.red(backgroundColor)
                + 0.587 * Color.green(backgroundColor)
                + 0.114 * Color.blue(backgroundColor);
        return luminance > 150 ? Color.rgb(32, 33, 36) : Color.WHITE;
    }

    static boolean isLightColor(int color) {
        double luminance = 0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color);
        return luminance > 160;
    }

    static int blendColors(int base, int overlay, float amount) {
        float clamped = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(Color.red(base) * (1f - clamped) + Color.red(overlay) * clamped);
        int g = Math.round(Color.green(base) * (1f - clamped) + Color.green(overlay) * clamped);
        int b = Math.round(Color.blue(base) * (1f - clamped) + Color.blue(overlay) * clamped);
        return Color.rgb(r, g, b);
    }
}
