package com.readwide.manager.util;

/** EPUB-only numeric preferences; unrelated to TXT pagination and line spacing. */
public final class EpubTypography {
    public static final float DEFAULT_FONT_SIZE = 16f;
    public static final int BOOK_LINE_HEIGHT = 0;
    public static final int LINE_HEIGHT_CHOICE_COUNT = 6;

    private EpubTypography() {}

    public static float normalizeFontSize(float size) {
        if (Float.isNaN(size) || Float.isInfinite(size)) return DEFAULT_FONT_SIZE;
        return Math.max(8f, Math.min(48f, size));
    }

    public static int textZoomPercent(float size) {
        // Preserve the zoom range used before the EPUB/TXT preference split.
        return Math.max(50, Math.min(267, Math.round(normalizeFontSize(size) / DEFAULT_FONT_SIZE * 100f)));
    }

    public static int normalizeLineHeight(int choice) {
        return choice >= 0 && choice < LINE_HEIGHT_CHOICE_COUNT ? choice : BOOK_LINE_HEIGHT;
    }

    public static int lineHeightPercent(int choice) {
        int normalized = normalizeLineHeight(choice);
        return normalized == BOOK_LINE_HEIGHT ? 0 : 100 + normalized * 20;
    }

    public static String lineHeightCss(int choice) {
        int percent = lineHeightPercent(choice);
        // Unitless CSS keeps the chosen multiplier when descendants change font size.
        return percent == 0 ? "" : (percent / 100) + "." + ((percent % 100) / 10);
    }
}
