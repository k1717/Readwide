package com.readwide.manager.util;

/**
 * Pure text-search, line-count, and bookmark-anchor calculations extracted from
 * ReaderActivity.  All stateful TXT-reader fields are passed in explicitly so
 * these routines remain deterministic and unit-testable.
 */
public final class TextSearchMath {
    private TextSearchMath() {
    }

    public static String getExcerpt(String content,
                                    int charPosition,
                                    boolean largeTextEstimateActive,
                                    int largeTextPreviewBaseCharOffset) {
        if (content == null || content.isEmpty()) return "";
        int localPosition = largeTextEstimateActive
                ? charPosition - largeTextPreviewBaseCharOffset
                : charPosition;
        int start = FileUtils.clampToSurrogateSafeStart(content,
                Math.max(0, Math.min(content.length(), localPosition)));
        int end = Math.min(content.length(), start + 90);
        return FileUtils.safeSubstring(content, start, end).trim().replaceAll("[\\r\\n]+", " ");
    }

    public static String getAnchorTextBefore(String content,
                                             int charPosition,
                                             boolean largeTextEstimateActive,
                                             int largeTextPreviewBaseCharOffset) {
        if (content == null || content.isEmpty()) return "";
        int localPosition = largeTextEstimateActive
                ? charPosition - largeTextPreviewBaseCharOffset
                : charPosition;
        int pos = FileUtils.clampToSurrogateSafeStart(content,
                Math.max(0, Math.min(content.length(), localPosition)));
        int start = Math.max(0, pos - 80);
        return FileUtils.safeSubstring(content, start, pos);
    }

    public static String getAnchorTextAfter(String content,
                                            int charPosition,
                                            boolean largeTextEstimateActive,
                                            int largeTextPreviewBaseCharOffset) {
        if (content == null || content.isEmpty()) return "";
        int localPosition = largeTextEstimateActive
                ? charPosition - largeTextPreviewBaseCharOffset
                : charPosition;
        int pos = FileUtils.clampToSurrogateSafeStart(content,
                Math.max(0, Math.min(content.length(), localPosition)));
        int end = Math.min(content.length(), pos + 120);
        return FileUtils.safeSubstring(content, pos, end);
    }

    public static int resolveAnchoredAbsolutePosition(String content,
                                                      int baseCharOffset,
                                                      int fallbackAbsolutePosition,
                                                      String anchorBefore,
                                                      String anchorAfter) {
        if (content == null || content.isEmpty()) {
            return Math.max(0, fallbackAbsolutePosition);
        }

        int fallbackLocal = Math.max(0, Math.min(content.length(),
                fallbackAbsolutePosition - Math.max(0, baseCharOffset)));

        int resolvedLocal = findBestAnchorPosition(content, fallbackLocal, anchorBefore, anchorAfter);
        if (resolvedLocal < 0) {
            resolvedLocal = fallbackLocal;
        }

        return Math.max(0, baseCharOffset) + Math.max(0, Math.min(content.length(), resolvedLocal));
    }

    public static int findBestAnchorPosition(String content,
                                             int fallbackLocalPosition,
                                             String anchorBefore,
                                             String anchorAfter) {
        if (content == null) return -1;
        String before = anchorBefore != null ? anchorBefore : "";
        String after = anchorAfter != null ? anchorAfter : "";

        // Prefer the exact text starting at the saved bookmark. This keeps the
        // bookmark tied to the same character/passage even when font size,
        // wrapping width, boundary offsets, or line spacing change.
        if (after.length() >= 8) {
            int bestIndex = -1;
            int bestScore = Integer.MAX_VALUE;
            int searchFrom = 0;
            while (searchFrom <= content.length()) {
                int idx = content.indexOf(after, searchFrom);
                if (idx < 0) break;

                int score = Math.abs(idx - fallbackLocalPosition);
                if (!before.isEmpty()) {
                    int beforeStart = Math.max(0, idx - before.length());
                    String actualBefore = FileUtils.safeSubstring(content, beforeStart, idx);
                    if (actualBefore.equals(before)) {
                        score -= 1_000_000;
                    } else if (!actualBefore.endsWith(lastChars(before, Math.min(24, before.length())))) {
                        score += 250_000;
                    }
                }

                if (score < bestScore) {
                    bestScore = score;
                    bestIndex = idx;
                }
                searchFrom = idx + Math.max(1, after.length());
            }
            if (bestIndex >= 0) return bestIndex;
        }

        // If the text after the bookmark cannot be found, fall back to the
        // preceding anchor and restore immediately after it. This is useful if
        // the file was slightly edited at the bookmarked text.
        if (before.length() >= 8) {
            int bestIndex = -1;
            int bestScore = Integer.MAX_VALUE;
            int searchFrom = 0;
            while (searchFrom <= content.length()) {
                int idx = content.indexOf(before, searchFrom);
                if (idx < 0) break;
                int candidate = Math.min(content.length(), idx + before.length());
                int score = Math.abs(candidate - fallbackLocalPosition);
                if (score < bestScore) {
                    bestScore = score;
                    bestIndex = candidate;
                }
                searchFrom = idx + Math.max(1, before.length());
            }
            if (bestIndex >= 0) return bestIndex;
        }

        return -1;
    }

    public static String lastChars(String value, int count) {
        if (value == null || value.isEmpty() || count <= 0) return "";
        int start = Math.max(0, value.length() - count);
        return FileUtils.safeSubstring(value, start, value.length());
    }

    public static int countLines(String s) {
        if (s == null || s.isEmpty()) return 1;
        int lines = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') lines++;
        return lines;
    }

    public static int countLinesUntilChar(String content,
                                          int charPosition,
                                          boolean largeTextEstimateActive,
                                          int largeTextPreviewBaseCharOffset,
                                          int largeTextPartitionWindowStartLine) {
        if (content == null || content.isEmpty()) return 1;
        int localPosition = largeTextEstimateActive
                ? charPosition - largeTextPreviewBaseCharOffset
                : charPosition;
        int end = Math.max(0, Math.min(content.length(), localPosition));
        int lines = 1;
        for (int i = 0; i < end; i++) if (content.charAt(i) == '\n') lines++;
        if (largeTextEstimateActive) {
            return Math.max(1, largeTextPartitionWindowStartLine + lines - 1);
        }
        return lines;
    }

    public static int findText(String content, String query, int startPosition) {
        return findText(content, query, startPosition, SearchOptions.literal());
    }

    public static int findText(String content, String query, int startPosition, SearchOptions options) {
        SearchMatcher m = SearchMatcher.compile(query, options);
        if (m == null || content == null) return -1;
        int start = Math.max(0, Math.min(content.length(), startPosition));
        SearchMatcher.Match hit = m.firstFrom(content, start);
        if (hit != null) return hit.start;
        // Wrap-around to the beginning, preserving legacy behavior.
        SearchMatcher.Match wrapped = m.firstFrom(content, 0);
        return wrapped != null ? wrapped.start : -1;
    }

    public static int findTextBackward(String content, String query, int startPosition) {
        return findTextBackward(content, query, startPosition, SearchOptions.literal());
    }

    public static int findTextBackward(String content, String query, int startPosition, SearchOptions options) {
        SearchMatcher m = SearchMatcher.compile(query, options);
        if (m == null || content == null || content.isEmpty()) return -1;
        int start = Math.max(0, Math.min(content.length() - 1, startPosition));
        SearchMatcher.Match hit = m.lastUpTo(content, start);
        if (hit != null) return hit.start;
        // Wrap-around to the last match in the file.
        SearchMatcher.Match wrapped = m.lastUpTo(content, content.length() - 1);
        return wrapped != null ? wrapped.start : -1;
    }

    public static int countTextMatches(String content, String query) {
        return countTextMatches(content, query, SearchOptions.literal());
    }

    public static int countTextMatches(String content, String query, SearchOptions options) {
        SearchMatcher m = SearchMatcher.compile(query, options);
        if (m == null) return 0;
        return m.count(content);
    }

    public static int findNthText(String content, String query, int occurrence) {
        return findNthText(content, query, occurrence, SearchOptions.literal());
    }

    public static int findNthText(String content, String query, int occurrence, SearchOptions options) {
        SearchMatcher m = SearchMatcher.compile(query, options);
        if (m == null) return -1;
        return m.nthStart(content, occurrence);
    }

    public static int matchIndexForPosition(String content, String query, int position) {
        return matchIndexForPosition(content, query, position, SearchOptions.literal());
    }

    public static int matchIndexForPosition(String content, String query, int position, SearchOptions options) {
        SearchMatcher m = SearchMatcher.compile(query, options);
        if (m == null) return 0;
        return m.ordinalForPosition(content, position);
    }

    public static int findCharForLine(String content, int totalChars, int targetLine) {
        if (targetLine <= 1 || content == null) return 0;
        int line = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
                if (line >= targetLine) return i + 1;
            }
        }
        return totalChars;
    }
}
