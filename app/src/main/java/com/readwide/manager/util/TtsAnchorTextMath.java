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
        char[] pattern = new char[needle.length()];
        int length = 0;
        for (int i = 0; i < needle.length(); i++) {
            char c = needle.charAt(i);
            if (!Character.isWhitespace(c)) pattern[length++] = c;
        }
        if (length == 0) return -1;

        // Prefix fallback avoids restarting a long partial match at every
        // repeated character in the document (KMP, O(hay + needle)).
        int[] prefix = new int[length];
        for (int i = 1, matched = 0; i < length; i++) {
            while (matched > 0 && pattern[i] != pattern[matched]) matched = prefix[matched - 1];
            if (pattern[i] == pattern[matched]) matched++;
            prefix[i] = matched;
        }
        int matched = 0;
        for (int i = 0; i < hay.length(); i++) {
            char c = hay.charAt(i);
            if (Character.isWhitespace(c)) continue;
            while (matched > 0 && c != pattern[matched]) matched = prefix[matched - 1];
            if (c == pattern[matched]) matched++;
            if (matched == length) {
                // Recover the raw UTF-16 start only for the first full match.
                // This final backward walk avoids an O(hay) offset map.
                int start = i;
                for (int remaining = length; remaining > 0; start--) {
                    if (!Character.isWhitespace(hay.charAt(start))) remaining--;
                }
                return start + 1;
            }
        }
        return -1;
    }
}
