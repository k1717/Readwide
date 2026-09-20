package com.readwide.manager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

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

    /** Same UTF-16, single-character lowercase and whitespace policy as the DOM helper. */
    static String squeezeForDomSearch(@NonNull String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isDomWhitespace(c)) continue;
            if (Character.toLowerCase(c) == c) {
                out.append(c);
                continue;
            }
            String lower = String.valueOf(c).toLowerCase(Locale.ROOT);
            out.append(lower.length() == 1 ? lower.charAt(0) : c);
        }
        return out.toString();
    }

    private static boolean isDomWhitespace(char c) {
        // ECMAScript \s, including NBSP/BOM but excluding Java-only U+001C..U+001F.
        return c == ' ' || (c >= '\t' && c <= '\r') || c == '\u00a0'
                || c == '\u1680' || (c >= '\u2000' && c <= '\u200a')
                || c == '\u2028' || c == '\u2029' || c == '\u202f'
                || c == '\u205f' || c == '\u3000' || c == '\ufeff';
    }

    static final class TextLocation {
        final int start, pageLength, pageHash;
        final String before, after;

        TextLocation(int start, int pageLength, int pageHash, String before, String after) {
            this.start = start;
            this.pageLength = pageLength;
            this.pageHash = pageHash;
            this.before = before;
            this.after = after;
        }

        String toJavascript() {
            return "{start:" + start + ",pageLength:" + pageLength + ",pageHash:" + pageHash
                    + ",before:" + toJsStringLiteral(before) + ",after:" + toJsStringLiteral(after) + "}";
        }
    }

    static TextLocation sourceLocation(String text, int pageStart, int start, int end,
                                       int pageEnd, int normalizedPageLength, int normalizedPageHash) {
        int normalizedStart = 0;
        for (int i = pageStart; i < start; i++) {
            if (!isDomWhitespace(text.charAt(i))) normalizedStart++;
        }
        int beforeStart = start, count = 0;
        while (beforeStart > pageStart && count < 64) {
            if (!isDomWhitespace(text.charAt(--beforeStart))) count++;
        }
        int afterEnd = end;
        count = 0;
        while (afterEnd < pageEnd && count < 64) {
            if (!isDomWhitespace(text.charAt(afterEnd++))) count++;
        }
        return new TextLocation(normalizedStart, normalizedPageLength, normalizedPageHash,
                squeezeForDomSearch(text.substring(beforeStart, start)),
                squeezeForDomSearch(text.substring(end, afterEnd)));
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
