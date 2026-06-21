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

        // Collapse-tolerant fallback: the "collapse repeated blank lines" reader
        // option changes how many newlines sit between paragraphs, so an anchor
        // captured in one collapse state will not match verbatim in the other.
        // Re-try on a blank-run-normalized view of both sides and map the hit back
        // to a real content offset. This only runs after the exact passes fail, so
        // it never changes behavior when no blank-line collapse is involved.
        if (after.length() >= 8 || before.length() >= 8) {
            int[] srcMap = new int[content.length() + 2];
            String cContent = collapseBlankRunsWithMap(content, srcMap);
            String cAfter = collapseBlankRuns(after);
            String cBefore = collapseBlankRuns(before);
            if (cAfter.length() >= 8) {
                int bestIndex = -1;
                long bestScore = Long.MAX_VALUE;
                int searchFrom = 0;
                while (searchFrom <= cContent.length()) {
                    int idx = cContent.indexOf(cAfter, searchFrom);
                    if (idx < 0) break;
                    int realIdx = srcMap[idx];
                    long score = Math.abs((long) realIdx - fallbackLocalPosition);
                    if (!cBefore.isEmpty()) {
                        int beforeStart = Math.max(0, idx - cBefore.length());
                        if (cContent.substring(beforeStart, idx).equals(cBefore)) {
                            score -= 1_000_000L;
                        }
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        bestIndex = idx;
                    }
                    searchFrom = idx + Math.max(1, cAfter.length());
                }
                if (bestIndex >= 0) return srcMap[bestIndex];
            }
            if (cBefore.length() >= 8) {
                int bestIndex = -1;
                long bestScore = Long.MAX_VALUE;
                int searchFrom = 0;
                while (searchFrom <= cContent.length()) {
                    int idx = cContent.indexOf(cBefore, searchFrom);
                    if (idx < 0) break;
                    int candidate = Math.min(cContent.length(), idx + cBefore.length());
                    int realIdx = srcMap[candidate];
                    long score = Math.abs((long) realIdx - fallbackLocalPosition);
                    if (score < bestScore) {
                        bestScore = score;
                        bestIndex = candidate;
                    }
                    searchFrom = idx + Math.max(1, cBefore.length());
                }
                if (bestIndex >= 0) return srcMap[bestIndex];
            }
        }

        return -1;
    }

    /**
     * Blank-run-normalizes {@code s} (runs of two or more blank/whitespace-only
     * lines become a single blank line, mirroring the reader's collapse option)
     * and records, for each character index in the normalized result, the source
     * index in {@code s}. {@code srcMap} must be at least {@code s.length() + 2}
     * entries long. Returns the normalized text.
     */
    static String collapseBlankRunsWithMap(String s, int[] srcMap) {
        int n = s.length();
        StringBuilder out = new StringBuilder(n);
        boolean firstEmitted = false;
        boolean blankEmittedInRun = false;
        int i = 0;
        while (i <= n) {
            int lineStart = i;
            int j = i;
            while (j < n && s.charAt(j) != '\n') j++;
            boolean blank = isBlankLineRange(s, lineStart, j);
            boolean isLast = j >= n;
            if (blank) {
                if (!blankEmittedInRun) {
                    if (firstEmitted) {
                        srcMap[out.length()] = lineStart;
                        out.append('\n');
                    }
                    firstEmitted = true;
                    blankEmittedInRun = true;
                }
            } else {
                if (firstEmitted) {
                    srcMap[out.length()] = lineStart;
                    out.append('\n');
                }
                for (int k = lineStart; k < j; k++) {
                    srcMap[out.length()] = k;
                    out.append(s.charAt(k));
                }
                firstEmitted = true;
                blankEmittedInRun = false;
            }
            if (isLast) break;
            i = j + 1;
        }
        srcMap[out.length()] = n;
        return out.toString();
    }

    private static String collapseBlankRuns(String s) {
        if (s == null || s.isEmpty()) return "";
        return collapseBlankRunsWithMap(s, new int[s.length() + 2]);
    }

    private static boolean isBlankLineRange(String s, int start, int end) {
        for (int k = start; k < end; k++) {
            if (!Character.isWhitespace(s.charAt(k))) return false;
        }
        return true;
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
