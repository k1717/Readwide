package com.textview.reader.util;

import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;

import com.textview.reader.view.CustomReaderView;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Random;

public class ExtractedReaderMathTest {
    @Test
    public void largeTextAnchorMath_matchesOriginalLogic() {
        Random random = new Random(0x545652L);
        for (int round = 0; round < 5000; round++) {
            ArrayList<CustomReaderView.PageTextAnchor> anchors = randomAnchors(random);
            for (int i = 0; i < 40; i++) {
                int charPosition = random.nextInt(2000) - 250;
                int currentAbs = random.nextInt(2000) - 250;
                int direction = random.nextInt(3) - 1;

                assertEquals(refFindExactPageForChar(anchors, charPosition),
                        LargeTextAnchorMath.findExactPageForChar(anchors, charPosition));
                assertEquals(refFindAnchorIndexAtOrBefore(anchors, charPosition),
                        LargeTextAnchorMath.findAnchorIndexAtOrBefore(anchors, charPosition));
                assertEquals(refFindTapTargetAnchorIndex(anchors, currentAbs, direction),
                        LargeTextAnchorMath.findTapTargetAnchorIndex(anchors, currentAbs, direction));
            }
        }
    }

    @Test
    public void largeTextTapAnchorNavigation_doesNotSkipOrDuplicatePages() {
        ArrayList<CustomReaderView.PageTextAnchor> anchors = new ArrayList<>();
        anchors.add(new CustomReaderView.PageTextAnchor(0, "", ""));
        anchors.add(new CustomReaderView.PageTextAnchor(100, "", ""));
        anchors.add(new CustomReaderView.PageTextAnchor(200, "", ""));
        anchors.add(new CustomReaderView.PageTextAnchor(300, "", ""));

        int currentAbs = anchors.get(0).charPosition;
        for (int expectedIndex = 1; expectedIndex < anchors.size(); expectedIndex++) {
            int targetIndex = LargeTextAnchorMath.findTapTargetAnchorIndex(anchors, currentAbs, +1);
            assertEquals(expectedIndex, targetIndex);
            currentAbs = anchors.get(targetIndex).charPosition;
        }
        assertEquals(-1, LargeTextAnchorMath.findTapTargetAnchorIndex(anchors, currentAbs, +1));

        currentAbs = anchors.get(anchors.size() - 1).charPosition;
        for (int expectedIndex = anchors.size() - 2; expectedIndex >= 0; expectedIndex--) {
            int targetIndex = LargeTextAnchorMath.findTapTargetAnchorIndex(anchors, currentAbs, -1);
            assertEquals(expectedIndex, targetIndex);
            currentAbs = anchors.get(targetIndex).charPosition;
        }
        assertEquals(-1, LargeTextAnchorMath.findTapTargetAnchorIndex(anchors, currentAbs, -1));
    }

    @Test
    public void largeTextTapAnchorNavigation_handlesManualScrollBetweenAnchors() {
        ArrayList<CustomReaderView.PageTextAnchor> anchors = new ArrayList<>();
        anchors.add(new CustomReaderView.PageTextAnchor(0, "", ""));
        anchors.add(new CustomReaderView.PageTextAnchor(100, "", ""));
        anchors.add(new CustomReaderView.PageTextAnchor(200, "", ""));

        assertEquals(1, LargeTextAnchorMath.findTapTargetAnchorIndex(anchors, 99, +1));
        assertEquals(1, LargeTextAnchorMath.findTapTargetAnchorIndex(anchors, 109, -1));
        assertEquals(0, LargeTextAnchorMath.findTapTargetAnchorIndex(anchors, 108, -1));
    }

    @Test
    public void textSearchMath_matchesOriginalLogic() {
        Random random = new Random(0x20220530L);
        String[] queries = {null, "", "a", "ab", "line", "한글", "\n", "z"};
        for (int round = 0; round < 5000; round++) {
            String content = randomContent(random);
            int totalChars = content == null ? 0 : content.length();
            for (String query : queries) {
                int pos = random.nextInt(260) - 40;
                int occurrence = random.nextInt(12) - 3;
                assertEquals(refFindText(content, query, pos),
                        TextSearchMath.findText(content, query, pos));
                assertEquals(refFindTextBackward(content, query, pos),
                        TextSearchMath.findTextBackward(content, query, pos));
                assertEquals(refCountTextMatches(content, query),
                        TextSearchMath.countTextMatches(content, query));
                assertEquals(refFindNthText(content, query, occurrence),
                        TextSearchMath.findNthText(content, query, occurrence));
                assertEquals(refMatchIndexForPosition(content, query, pos),
                        TextSearchMath.matchIndexForPosition(content, query, pos));
                assertEquals(refFindCharForLine(content, totalChars, occurrence),
                        TextSearchMath.findCharForLine(content, totalChars, occurrence));
            }
        }
    }

    @Test
    public void textAnchorHelpers_matchOriginalLogic() {
        Random random = new Random(0xA11CE5L);
        for (int round = 0; round < 5000; round++) {
            String content = randomContent(random);
            int base = random.nextInt(80);
            int fallback = random.nextInt(220) - 20;
            String before = randomSnippet(content, random);
            String after = randomSnippet(content, random);
            boolean active = random.nextBoolean();
            int startLine = random.nextInt(50) + 1;
            int pos = random.nextInt(220) - 20;

            assertEquals(refResolveAnchoredAbsolutePosition(content, base, fallback, before, after),
                    TextSearchMath.resolveAnchoredAbsolutePosition(content, base, fallback, before, after));
            assertEquals(refFindBestAnchorPosition(content, Math.max(0, fallback - base), before, after),
                    TextSearchMath.findBestAnchorPosition(content, Math.max(0, fallback - base), before, after));
            assertEquals(refLastChars(before, Math.min(24, before == null ? 0 : before.length())),
                    TextSearchMath.lastChars(before, Math.min(24, before == null ? 0 : before.length())));
            assertEquals(refCountLines(content), TextSearchMath.countLines(content));
            assertEquals(refCountLinesUntilChar(content, pos, active, base, startLine),
                    TextSearchMath.countLinesUntilChar(content, pos, active, base, startLine));
            assertEquals(refGetExcerpt(content, pos, active, base),
                    TextSearchMath.getExcerpt(content, pos, active, base));
            assertEquals(refGetAnchorTextBefore(content, pos, active, base),
                    TextSearchMath.getAnchorTextBefore(content, pos, active, base));
            assertEquals(refGetAnchorTextAfter(content, pos, active, base),
                    TextSearchMath.getAnchorTextAfter(content, pos, active, base));
        }
    }

    @Test
    public void readerKeyMap_mapsPagingKeys() {
        assertEquals(1, ReaderKeyMap.pageTurnDirectionForKey(KeyEvent.KEYCODE_VOLUME_DOWN));
        assertEquals(1, ReaderKeyMap.pageTurnDirectionForKey(KeyEvent.KEYCODE_PAGE_DOWN));
        assertEquals(1, ReaderKeyMap.pageTurnDirectionForKey(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(-1, ReaderKeyMap.pageTurnDirectionForKey(KeyEvent.KEYCODE_VOLUME_UP));
        assertEquals(-1, ReaderKeyMap.pageTurnDirectionForKey(KeyEvent.KEYCODE_PAGE_UP));
        assertEquals(-1, ReaderKeyMap.pageTurnDirectionForKey(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(0, ReaderKeyMap.pageTurnDirectionForKey(KeyEvent.KEYCODE_A));
    }

    private static ArrayList<CustomReaderView.PageTextAnchor> randomAnchors(Random random) {
        ArrayList<CustomReaderView.PageTextAnchor> anchors = new ArrayList<>();
        int count = random.nextInt(24);
        int pos = random.nextInt(4) - 2;
        for (int i = 0; i < count; i++) {
            pos += random.nextInt(60);
            anchors.add(new CustomReaderView.PageTextAnchor(pos, "", ""));
        }
        return anchors;
    }

    private static String randomContent(Random random) {
        if (random.nextInt(40) == 0) return null;
        int len = random.nextInt(160);
        String alphabet = "aaabbbbcline\n xyz한글";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String randomSnippet(String content, Random random) {
        if (content == null || content.isEmpty() || random.nextInt(8) == 0) return "";
        int start = random.nextInt(content.length());
        int end = Math.min(content.length(), start + random.nextInt(24));
        return content.substring(start, end);
    }

    private static int refFindExactPageForChar(ArrayList<CustomReaderView.PageTextAnchor> anchors,
                                               int charPosition) {
        if (anchors == null || anchors.isEmpty()) return 1;
        int target = Math.max(0, charPosition);
        int lo = 0;
        int hi = anchors.size() - 1;
        int best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int anchor = anchors.get(mid).charPosition;
            if (target >= anchor) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return Math.max(1, Math.min(anchors.size(), best + 1));
    }

    private static int refFindTapTargetAnchorIndex(ArrayList<CustomReaderView.PageTextAnchor> anchors,
                                                   int currentAbs,
                                                   int direction) {
        if (anchors == null || anchors.isEmpty() || direction == 0) return -1;
        int currentIndex = refFindAnchorIndexAtOrBefore(anchors, Math.max(0, currentAbs));
        if (currentIndex < 0) return direction > 0 ? 0 : -1;
        if (direction > 0) {
            int target = currentIndex + 1;
            return target < anchors.size() ? target : -1;
        }
        int currentAnchor = Math.max(0, anchors.get(currentIndex).charPosition);
        if (Math.max(0, currentAbs) > currentAnchor + 8) return currentIndex;
        int target = currentIndex - 1;
        return target >= 0 ? target : -1;
    }

    private static int refFindAnchorIndexAtOrBefore(ArrayList<CustomReaderView.PageTextAnchor> anchors,
                                                    int charPosition) {
        if (anchors == null || anchors.isEmpty()) return -1;
        int target = Math.max(0, charPosition);
        int lo = 0;
        int hi = anchors.size() - 1;
        int best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int anchor = Math.max(0, anchors.get(mid).charPosition);
            if (anchor <= target) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    private static int refFindText(String content, String query, int startPosition) {
        if (query == null || query.isEmpty() || content == null) return -1;
        int start = Math.max(0, Math.min(content.length(), startPosition));
        int idx = content.indexOf(query, start);
        if (idx >= 0) return idx;
        return content.indexOf(query);
    }

    private static int refFindTextBackward(String content, String query, int startPosition) {
        if (query == null || query.isEmpty() || content == null) return -1;
        if (content.isEmpty()) return -1;
        int start = Math.max(0, Math.min(content.length() - 1, startPosition));
        int idx = content.lastIndexOf(query, start);
        if (idx >= 0) return idx;
        return content.lastIndexOf(query);
    }

    private static int refCountTextMatches(String content, String query) {
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

    private static int refFindNthText(String content, String query, int occurrence) {
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

    private static int refMatchIndexForPosition(String content, String query, int position) {
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

    private static int refFindCharForLine(String content, int totalChars, int targetLine) {
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

    private static int refResolveAnchoredAbsolutePosition(String content,
                                                          int baseCharOffset,
                                                          int fallbackAbsolutePosition,
                                                          String anchorBefore,
                                                          String anchorAfter) {
        if (content == null || content.isEmpty()) return Math.max(0, fallbackAbsolutePosition);
        int fallbackLocal = Math.max(0, Math.min(content.length(),
                fallbackAbsolutePosition - Math.max(0, baseCharOffset)));
        int resolvedLocal = refFindBestAnchorPosition(content, fallbackLocal, anchorBefore, anchorAfter);
        if (resolvedLocal < 0) resolvedLocal = fallbackLocal;
        return Math.max(0, baseCharOffset) + Math.max(0, Math.min(content.length(), resolvedLocal));
    }

    private static int refFindBestAnchorPosition(String content,
                                                 int fallbackLocalPosition,
                                                 String anchorBefore,
                                                 String anchorAfter) {
        if (content == null) return -1;
        String before = anchorBefore != null ? anchorBefore : "";
        String after = anchorAfter != null ? anchorAfter : "";
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
                    String actualBefore = content.substring(beforeStart, idx);
                    if (actualBefore.equals(before)) {
                        score -= 1_000_000;
                    } else if (!actualBefore.endsWith(refLastChars(before, Math.min(24, before.length())))) {
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

    private static String refLastChars(String value, int count) {
        if (value == null || value.isEmpty() || count <= 0) return "";
        int start = Math.max(0, value.length() - count);
        return value.substring(start);
    }

    private static int refCountLines(String s) {
        if (s == null || s.isEmpty()) return 1;
        int lines = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') lines++;
        return lines;
    }

    private static int refCountLinesUntilChar(String content,
                                              int charPosition,
                                              boolean largeTextEstimateActive,
                                              int largeTextPreviewBaseCharOffset,
                                              int largeTextPartitionWindowStartLine) {
        if (content == null || content.isEmpty()) return 1;
        int localPosition = largeTextEstimateActive ? charPosition - largeTextPreviewBaseCharOffset : charPosition;
        int end = Math.max(0, Math.min(content.length(), localPosition));
        int lines = 1;
        for (int i = 0; i < end; i++) if (content.charAt(i) == '\n') lines++;
        if (largeTextEstimateActive) return Math.max(1, largeTextPartitionWindowStartLine + lines - 1);
        return lines;
    }

    private static String refGetExcerpt(String content,
                                        int charPosition,
                                        boolean largeTextEstimateActive,
                                        int largeTextPreviewBaseCharOffset) {
        if (content == null || content.isEmpty()) return "";
        int localPosition = largeTextEstimateActive ? charPosition - largeTextPreviewBaseCharOffset : charPosition;
        int start = Math.max(0, Math.min(content.length(), localPosition));
        int end = Math.min(content.length(), start + 90);
        return content.substring(start, end).trim().replaceAll("[\\r\\n]+", " ");
    }

    private static String refGetAnchorTextBefore(String content,
                                                 int charPosition,
                                                 boolean largeTextEstimateActive,
                                                 int largeTextPreviewBaseCharOffset) {
        if (content == null || content.isEmpty()) return "";
        int localPosition = largeTextEstimateActive ? charPosition - largeTextPreviewBaseCharOffset : charPosition;
        int pos = Math.max(0, Math.min(content.length(), localPosition));
        int start = Math.max(0, pos - 80);
        return content.substring(start, pos);
    }

    private static String refGetAnchorTextAfter(String content,
                                                int charPosition,
                                                boolean largeTextEstimateActive,
                                                int largeTextPreviewBaseCharOffset) {
        if (content == null || content.isEmpty()) return "";
        int localPosition = largeTextEstimateActive ? charPosition - largeTextPreviewBaseCharOffset : charPosition;
        int pos = Math.max(0, Math.min(content.length(), localPosition));
        int end = Math.min(content.length(), pos + 120);
        return content.substring(pos, end);
    }
}
