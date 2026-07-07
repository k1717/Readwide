package com.readwide.manager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Pure text helpers for {@link DocumentTtsHighlightController}: normalizing a
 * spoken sentence for DOM search and escaping it as a JavaScript string literal.
 * Separated so the string handling can be unit-tested off-device.
 */
final class DocumentTtsHighlightMath {

    private DocumentTtsHighlightMath() {
    }

    /**
     * Collapses all whitespace runs to single spaces and trims, matching how the
     * injected script collapses the DOM text it searches. Returns "" for null or
     * whitespace-only input.
     */
    @NonNull
    static String normalizeForDomSearch(@Nullable String sentence) {
        if (sentence == null) return "";
        String collapsed = sentence.replaceAll("\\s+", " ").trim();
        return collapsed;
    }

    /**
     * Escapes a string as a single-quoted JavaScript string literal, including
     * the surrounding quotes. Handles backslash, quotes, and the line
     * terminators that would otherwise break the script (including U+2028/U+2029,
     * which are newlines in JavaScript source).
     */
    @NonNull
    static String toJsStringLiteral(@NonNull String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default: sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }
}
