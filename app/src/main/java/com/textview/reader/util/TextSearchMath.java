package com.textview.reader.util;

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
        if (query == null || query.isEmpty() || content == null) return -1;
        int start = Math.max(0, Math.min(content.length(), startPosition));
        int idx = content.indexOf(query, start);
        if (idx >= 0) return idx;
        return content.indexOf(query);
    }

    public static int findTextBackward(String content, String query, int startPosition) {
        if (query == null || query.isEmpty() || content == null) return -1;
        if (content.isEmpty()) return -1;

        int start = Math.max(0, Math.min(content.length() - 1, startPosition));
        int idx = content.lastIndexOf(query, start);
        if (idx >= 0) return idx;
        return content.lastIndexOf(query);
    }

    public static int countTextMatches(String content, String query) {
        if (query == null || query.isEmpty() || content == null || content.isEmpty()) return 0;

        int count = 0;
        int idx = 0;
        int step = Math.max(1, query.length());

        while (idx >= 0 && idx < content.length()) {
            idx = content.indexOf(query, idx);
            if (idx < 0) break;
            count++;
            idx += step;
        }

        return count;
    }

    public static int findNthText(String content, String query, int occurrence) {
        if (query == null || query.isEmpty() || content == null || content.isEmpty()) return -1;
        int target = Math.max(1, occurrence);
        int count = 0;
        int idx = 0;
        int step = Math.max(1, query.length());

        while (idx >= 0 && idx < content.length()) {
            idx = content.indexOf(query, idx);
            if (idx < 0) break;
            count++;
            if (count == target) return idx;
            idx += step;
        }
        return -1;
    }

    public static int matchIndexForPosition(String content, String query, int position) {
        if (query == null || query.isEmpty() || content == null || content.isEmpty()) return 0;

        int count = 0;
        int idx = 0;
        int step = Math.max(1, query.length());

        while (idx >= 0 && idx < content.length()) {
            idx = content.indexOf(query, idx);
            if (idx < 0) break;
            count++;
            if (idx >= position) return count;
            idx += step;
        }

        return count;
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
