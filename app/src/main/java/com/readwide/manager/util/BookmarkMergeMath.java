package com.readwide.manager.util;

/** Pure duplicate-location policy used by bookmark backup merge. */
public final class BookmarkMergeMath {
    private BookmarkMergeMath() {}

    /**
     * A character/page position is sufficient only for legacy anchors.
     * Rendered readers reuse that integer for a spine/PDF page, so distinct
     * non-empty content anchors on the same page must remain separate rows.
     */
    public static boolean isSameLogicalPosition(long firstPosition,
                                                String firstAnchor,
                                                long secondPosition,
                                                String secondAnchor) {
        if (firstPosition != secondPosition) return false;
        String first = normalizedAnchor(firstAnchor);
        String second = normalizedAnchor(secondAnchor);
        if (first.isEmpty() || second.isEmpty()) {
            return first.isEmpty() && second.isEmpty();
        }
        return first.equals(second);
    }

    private static String normalizedAnchor(String anchor) {
        String normalized = anchor == null ? "" : anchor.trim();
        return stripTopLevelPresentationField(normalized, "columnStartText");
    }

    /**
     * Removes one top-level, presentation-only JSON member without depending on
     * Android's JSONObject implementation. BookmarkMergeMath deliberately stays
     * usable in the off-device JVM test suite.
     *
     * <p>The rest of the anchor text is retained in its original member order, so
     * this changes only the new bookmark label metadata. Malformed or non-object
     * anchors fall back to the previous exact-string behavior.</p>
     */
    private static String stripTopLevelPresentationField(String json,
                                                         String fieldName) {
        if (json.length() < 2 || json.charAt(0) != '{'
                || json.charAt(json.length() - 1) != '}') {
            return json;
        }
        int end = json.length() - 1;
        int index = 1;
        boolean wroteMember = false;
        StringBuilder result = new StringBuilder(json.length()).append('{');
        while (true) {
            index = skipWhitespace(json, index, end);
            if (index == end) {
                return result.append('}').toString();
            }
            if (index > end || json.charAt(index) != '"') return json;

            int memberStart = index;
            int keyEnd = jsonStringEnd(json, index, end);
            if (keyEnd < 0) return json;
            String key = json.substring(index + 1, keyEnd - 1);
            index = skipWhitespace(json, keyEnd, end);
            if (index >= end || json.charAt(index) != ':') return json;
            index = skipWhitespace(json, index + 1, end);

            int valueEnd = jsonValueEnd(json, index, end);
            if (valueEnd < 0) return json;
            if (!fieldName.equals(key)) {
                if (wroteMember) result.append(',');
                result.append(json, memberStart, valueEnd);
                wroteMember = true;
            }

            index = skipWhitespace(json, valueEnd, end);
            if (index == end) {
                return result.append('}').toString();
            }
            if (json.charAt(index) != ',') return json;
            index++;
        }
    }

    private static int skipWhitespace(String value, int index, int end) {
        while (index < end && Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }

    /** Returns the first index after the closing quote, or -1. */
    private static int jsonStringEnd(String value, int start, int end) {
        boolean escaped = false;
        for (int i = start + 1; i < end; i++) {
            char c = value.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i + 1;
            }
        }
        return -1;
    }

    /** Returns the comma/outer-brace index after a complete top-level value. */
    private static int jsonValueEnd(String value, int start, int end) {
        boolean inString = false;
        boolean escaped = false;
        int objectDepth = 0;
        int arrayDepth = 0;
        for (int i = start; i < end; i++) {
            char c = value.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                objectDepth++;
            } else if (c == '}') {
                if (objectDepth == 0) return -1;
                objectDepth--;
            } else if (c == '[') {
                arrayDepth++;
            } else if (c == ']') {
                if (arrayDepth == 0) return -1;
                arrayDepth--;
            } else if (c == ',' && objectDepth == 0 && arrayDepth == 0) {
                return i;
            }
        }
        return !inString && !escaped && objectDepth == 0 && arrayDepth == 0
                ? end : -1;
    }
}
