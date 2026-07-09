package com.readwide.manager.util;

/**
 * Pure string math for locating and normalizing read-aloud anchors in a
 * rendered text buffer. Used by the Markdown fresh-start path (the
 * viewport-top anchor text is located here, then snapped to a natural reading
 * start); kept Android-free so it stays JVM-testable off-device.
 */
public final class TtsAnchorTextMath {

    private TtsAnchorTextMath() {
    }

    /**
     * Snaps a mid-line/mid-word buffer position back to a natural reading
     * start: the beginning of the current line or sentence when one lies within
     * a short window behind it, else the start of the current word. Keeps the
     * spoken opening from beginning in the middle of a word when the viewport
     * probe landed inside the top line.
     */
    public static int snapToNaturalStart(String text, int pos) {
        int p = Math.max(0, Math.min(text.length(), pos));
        int window = Math.max(0, p - 160);
        int best = -1;
        for (int i = p - 1; i >= window; i--) {
            char c = text.charAt(i);
            if (c == '\n') { best = i + 1; break; }
            if ((c == '.' || c == '!' || c == '?') && i + 1 < text.length()
                    && Character.isWhitespace(text.charAt(i + 1))) {
                best = i + 2;
                break;
            }
        }
        if (best >= 0 && best <= p) return best;
        while (p > 0 && !Character.isWhitespace(text.charAt(p - 1))) p--;
        return p;
    }

    /**
     * Finds {@code needle} in {@code hay} comparing with all whitespace ignored
     * on both sides, returning the index in {@code hay} of the first matched
     * non-whitespace character, or -1. Case-sensitive: both strings come from
     * the same rendering.
     */
    public static int indexOfCollapsed(String hay, String needle) {
        int nStart = 0;
        while (nStart < needle.length() && Character.isWhitespace(needle.charAt(nStart))) nStart++;
        if (nStart >= needle.length()) return -1;
        char first = needle.charAt(nStart);
        for (int i = 0; i < hay.length(); i++) {
            if (hay.charAt(i) != first) continue;
            int h = i, n = nStart;
            while (n < needle.length()) {
                while (n < needle.length() && Character.isWhitespace(needle.charAt(n))) n++;
                if (n >= needle.length()) break;
                while (h < hay.length() && Character.isWhitespace(hay.charAt(h))) h++;
                if (h >= hay.length() || hay.charAt(h) != needle.charAt(n)) { n = -1; break; }
                h++; n++;
            }
            if (n != -1) return i;
        }
        return -1;
    }
}
