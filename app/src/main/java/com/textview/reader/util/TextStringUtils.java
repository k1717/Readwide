package com.textview.reader.util;

/**
 * Text normalization and surrogate-safe substring helpers shared by readers,
 * bookmark/search logic, and text decoding.
 */
final class TextStringUtils {
    private TextStringUtils() {}

    static int clampToSurrogateSafeStart(String text, int index) {
        if (text == null || text.isEmpty()) return 0;
        int safe = Math.max(0, Math.min(text.length(), index));
        if (safe > 0 && safe < text.length()
                && Character.isLowSurrogate(text.charAt(safe))
                && Character.isHighSurrogate(text.charAt(safe - 1))) {
            return safe - 1;
        }
        return safe;
    }

    static int clampToSurrogateSafeEnd(String text, int index) {
        if (text == null || text.isEmpty()) return 0;
        int safe = Math.max(0, Math.min(text.length(), index));
        if (safe > 0 && safe < text.length()
                && Character.isLowSurrogate(text.charAt(safe))
                && Character.isHighSurrogate(text.charAt(safe - 1))) {
            return safe + 1;
        }
        return safe;
    }

    static String safeSubstring(String text, int start, int end) {
        if (text == null || text.isEmpty()) return "";
        int safeStart = clampToSurrogateSafeStart(text, start);
        int safeEnd = clampToSurrogateSafeEnd(text, end);
        safeStart = Math.max(0, Math.min(text.length(), safeStart));
        safeEnd = Math.max(0, Math.min(text.length(), safeEnd));
        if (safeEnd < safeStart) {
            int tmp = safeStart;
            safeStart = safeEnd;
            safeEnd = tmp;
        }
        return text.substring(safeStart, safeEnd);
    }



    static String enforceTextPresentationSelectors(String text) {
        return sanitizeDecodedText(text);
    }


    private static boolean isBadControl(char ch) {
        if (ch == '\n' || ch == '\r' || ch == '\t') return false;
        return ch < 0x20 || (ch >= 0x7F && ch <= 0x9F);
    }

    private static boolean needsTextPresentationSelector(char ch) {
        switch (ch) {
            case '\u2605': // ★
            case '\u2606': // ☆
            case '\u2665': // ♥
            case '\u2661': // ♡
            case '\u2660': // ♠
            case '\u2663': // ♣
            case '\u2666': // ♦
            case '\u263A': // ☺
            case '\u2639': // ☹
            case '\u2764': // ❤
            case '\u2714': // ✔
            case '\u2716': // ✖
                return true;
            default:
                return false;
        }
    }

    static String sanitizeDecodedText(String text) {
        if (text == null || text.isEmpty()) return "";

        StringBuilder out = new StringBuilder(text.length());
        boolean first = true;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (first && ch == '\uFEFF') {
                first = false;
                continue;
            }
            first = false;

            if (ch == '\u0000') {
                out.append('\uFFFD');
                continue;
            }

            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    out.append(ch);
                    out.append(text.charAt(i + 1));
                    i++;
                } else {
                    out.append('\uFFFD');
                }
                continue;
            }

            if (Character.isLowSurrogate(ch)) {
                out.append('\uFFFD');
                continue;
            }

            if (isBadControl(ch)) {
                out.append(' ');
                continue;
            }

            // U+2728 SPARKLES is emoji-default on many Samsung/Android builds.
            // Even with U+FE0E, some devices still route it through the color emoji font.
            // Use the closest monochrome text-star fallback instead.
            if (ch == '\u2728') {
                out.append('\u2727'); // ✧
                if (i + 1 < text.length()) {
                    char next = text.charAt(i + 1);
                    if (next == '\uFE0E' || next == '\uFE0F') {
                        i++;
                    }
                }
                continue;
            }

            if (needsTextPresentationSelector(ch)) {
                out.append(ch);
                if (i + 1 < text.length()) {
                    char next = text.charAt(i + 1);
                    if (next == '\uFE0E') {
                        out.append(next);
                        i++;
                    } else if (next == '\uFE0F') {
                        out.append('\uFE0E');
                        i++;
                    } else {
                        out.append('\uFE0E');
                    }
                } else {
                    out.append('\uFE0E');
                }
                continue;
            }

            out.append(ch);
        }

        return out.toString()
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }
}
