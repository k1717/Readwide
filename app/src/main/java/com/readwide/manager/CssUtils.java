package com.readwide.manager;

import java.util.Locale;

/**
 * Small shared helpers for building CSS fragments injected into the document
 * WebView. Previously duplicated across DocumentPageActivity and
 * DocumentFontDialogController.
 */
final class CssUtils {
    private CssUtils() {}

    /** Escape a string for safe use inside a single-quoted CSS value. */
    static String cssQuote(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** Format an ARGB color int as a CSS #RRGGBB hex string. */
    static String cssColor(int color) {
        return String.format(Locale.US, "#%06X", 0xFFFFFF & color);
    }
}
