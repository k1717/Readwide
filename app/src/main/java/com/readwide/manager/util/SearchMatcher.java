package com.readwide.manager.util;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Shared find-in-page matcher for the TXT reader. Both the in-memory and
 * large-file search paths route through this class so case-folding, whole-word,
 * regex, and Unicode-normalization behavior is identical everywhere.
 *
 * <p><b>Index coordinates.</b> Match spans always use original UTF-16 offsets.
 * Literal NFC search retains a normalization map; transformed/reordered source
 * segments are highlighted as complete covering spans. Regex operates on the
 * original text: Android does not implement Pattern.CANON_EQ, so the Unicode
 * normalization option intentionally applies to literal search only.
 */
public final class SearchMatcher {

    /** A single match expressed in original-text coordinates. */
    public static final class Match {
        public final int start;
        public final int end; // exclusive
        public Match(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private final SearchOptions options;
    private final String query;
    private final Pattern regexPattern; // non-null only in regex mode

    private final String comparableQuery;
    private final int[] literalFallback;

    private SearchMatcher(SearchOptions options, String query, Pattern regexPattern) {
        this.options = options;
        this.query = query;
        this.regexPattern = regexPattern;
        // Precompute once; reused across all match stepping for literal mode.
        this.comparableQuery = regexPattern == null
                ? foldCase(options.normalizeUnicode ? Normalizer.normalize(query, Normalizer.Form.NFC) : query)
                : query;
        this.literalFallback = regexPattern == null ? buildLiteralFallback(comparableQuery) : null;
    }

    /**
     * Compiles a matcher for the given query/options. Returns {@code null} when
     * the query is empty or, in regex mode, fails to compile — callers treat a
     * null matcher as "no matches".
     */
    public static SearchMatcher compile(String query, SearchOptions options) {
        if (query == null || query.isEmpty()) return null;
        SearchOptions opt = options != null ? options : SearchOptions.literal();
        if (opt.regex) {
            int flags = Pattern.UNICODE_CASE | Pattern.MULTILINE;
            if (!opt.caseSensitive) flags |= Pattern.CASE_INSENSITIVE;
            // CANON_EQ is unsupported on Android. Never normalize regex syntax:
            // doing so can change character classes, quantifiers and escapes.
            try {
                return new SearchMatcher(opt, query, Pattern.compile(query, flags));
            } catch (PatternSyntaxException e) {
                return null;
            }
        }
        return new SearchMatcher(opt, query, null);
    }

    /** True if the query compiled cleanly (used to validate regex input). */
    public boolean isValid() {
        return options.regex ? regexPattern != null : (query != null && !query.isEmpty());
    }

    /**
     * Finds the first match at or after {@code from} in original-text
     * coordinates, or {@code null} if none.
     */
    public Match firstFrom(String text, int from) {
        if (text == null || text.isEmpty()) return null;
        Object ctx = prepare(text);
        if (regexPattern == null) return firstPrepared(ctx, text, from);
        // Keep the same non-overlapping sequence as count/nth/highlighting.
        // Starting find() inside an earlier hit would invent a suffix match.
        Match hit = firstPrepared(ctx, text, 0);
        while (hit != null && hit.start < from && !Thread.currentThread().isInterrupted()) {
            hit = firstPrepared(ctx, text, nextStart(hit));
        }
        return hit;
    }

    /** Finds the last match at or before {@code from}, or {@code null}. */
    public Match lastUpTo(String text, int from) {
        if (text == null || text.isEmpty() || from < 0) return null;
        int cap = Math.min(text.length() - 1, from);
        int[] best = {-1, -1};
        prepareText(text).forEachInRange(0, cap + 1, (start, end) -> {
            best[0] = start; best[1] = end; return true;
        });
        return best[0] < 0 ? null : new Match(best[0], best[1]);
    }

    /**
     * Next scan position after a match. Literal searches advance by one to allow
     * overlapping matches (e.g. "aa" occurs 3 times in "aaaa"); regex searches
     * advance past the match end for standard non-overlapping iteration, which
     * also prevents a multi-char pattern from re-matching its own tail.
     */
    private int nextStart(Match m) {
        if (regexPattern != null) return Math.max(m.end, m.start + 1);
        return m.start + 1;
    }

    /** Total number of matches in the text. */
    public int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        int[] count = {0};
        forEachMatch(text, (start, end) -> { count[0]++; return true; });
        return count[0];
    }

    /** Start offset of the 1-based nth match, or -1. */
    public int nthStart(String text, int occurrence) {
        if (text == null || text.isEmpty()) return -1;
        int target = Math.max(1, occurrence);
        int[] n = {0}, result = {-1};
        forEachMatch(text, (start, end) -> {
            if (++n[0] != target) return true;
            result[0] = start; return false;
        });
        return result[0];
    }

    /** 1-based ordinal of the match whose start is the first &gt;= position. */
    public int ordinalForPosition(String text, int position) {
        if (text == null || text.isEmpty()) return 0;
        int[] n = {0};
        forEachMatch(text, (start, end) -> { n[0]++; return start < position; });
        return n[0];
    }

    // --- prepared (single normalization/compile) scanning -------------------

    /** Callback for {@link #forEachMatch}. Return false to stop early. */
    public interface MatchConsumer {
        boolean accept(int start, int end);
    }

    /**
     * Visits every match in {@code text} in order, preparing the comparison view
     * / regex engine only once. Use this instead of repeatedly calling
     * {@link #firstFrom} in a loop, which re-normalizes the text each call and is
     * O(matches × length) for common words on long lines or large buffers.
     */
    public void forEachMatch(String text, MatchConsumer consumer) {
        if (text == null || text.isEmpty() || consumer == null) return;
        prepareText(text).forEachInRange(0, text.length(), consumer);
    }

    /**
     * Like {@link #forEachMatch} but limited to matches whose start is below
     * {@code toExclusive}, beginning the scan at {@code from}. The comparison
     * view / regex engine is still prepared only once. Used by the renderer to
     * highlight matches near the visible region without rescanning the whole
     * buffer on every frame.
     */
    public void forEachMatchInRange(String text, int from, int toExclusive, MatchConsumer consumer) {
        prepareText(text).forEachInRange(from, toExclusive, consumer);
    }

    /** Reusable comparison snapshot. Confine each instance to one worker thread. */
    public PreparedText prepareText(String text) { return new PreparedText(text == null ? "" : text); }

    public final class PreparedText {
        private final String text;
        private final Object context;
        private PreparedText(String text) { this.text = text; context = prepare(text); }

        public void forEachInRange(int from, int toExclusive, MatchConsumer consumer) {
            int start = Math.max(0, from), end = Math.min(text.length(), toExclusive);
            if (consumer == null || start >= end) return;
            if (regexPattern == null) {
                // Bounded KMP scan over the prepared view: no band substring or
                // per-hit Match object. The tail preserves cross-band matches;
                // whole-word boundaries still come from the original input.
                NormalizedSearchText view = (NormalizedSearchText) context;
                String hay = view.value;
                int length = comparableQuery.length(), matched = 0;
                int scanStart = view.comparisonStart(start);
                int scanEnd = view.comparisonStart(end);
                int limit = (int) Math.min(hay.length(), (long) scanEnd + length - 1L);
                int lastStart = -1, lastEnd = -1;
                for (int i = scanStart; i < limit; i++) {
                    if (((i - scanStart) & 1023) == 0 && Thread.currentThread().isInterrupted()) return;
                    char c = hay.charAt(i);
                    while (matched > 0 && c != comparableQuery.charAt(matched)) matched = literalFallback[matched - 1];
                    if (c == comparableQuery.charAt(matched)) matched++;
                    if (matched == length) {
                        int hit = view.originalStart(i - length + 1);
                        int stop = view.originalEnd(i + 1);
                        if (Thread.currentThread().isInterrupted()) return;
                        if (hit >= start && hit < end && (hit != lastStart || stop != lastEnd)
                                && passesWholeWord(text, hit, stop)) {
                            lastStart = hit; lastEnd = stop;
                            if (!consumer.accept(hit, stop)) return;
                        }
                        matched = literalFallback[length - 1]; // preserve overlapping literal matches
                    }
                }
                return;
            }
            // Keep whole-input regex context (lookbehind/lookahead/anchors).
            // Iterating from an arbitrary band start can manufacture a suffix
            // match inside an earlier regex hit. Preserve whole-input ordinals.
            Match match = firstPrepared(context, text, 0);
            while (match != null && match.start < end && !Thread.currentThread().isInterrupted()) {
                if (match.start >= start && !consumer.accept(match.start, match.end)) return;
                match = firstPrepared(context, text, nextStart(match));
            }
        }
    }


    /**
     * Precomputes the per-text scan context once so repeated match stepping does
     * not re-normalize the whole text (literal) or rebuild the engine (regex).
     * Returns a mapped comparison view for literal mode, or a java Matcher for
     * regex mode.
     */
    private Object prepare(String text) {
        if (regexPattern != null) return regexPattern.matcher(text);
        NormalizedSearchText view = options.normalizeUnicode
                ? NormalizedSearchText.nfc(text) : NormalizedSearchText.identity(text);
        return view.withValue(foldCase(view.value));
    }

    private static int[] buildLiteralFallback(String needle) {
        int[] fallback = new int[needle.length()];
        int matched = 0;
        for (int i = 1; i < needle.length(); i++) {
            while (matched > 0 && needle.charAt(i) != needle.charAt(matched)) matched = fallback[matched - 1];
            if (needle.charAt(i) == needle.charAt(matched)) matched++;
            fallback[i] = matched;
        }
        return fallback;
    }

    private Match firstPrepared(Object ctx, String text, int from) {
        if (text == null || text.isEmpty()) return null;
        int start = Math.max(0, Math.min(text.length(), from));
        if (regexPattern != null) {
            Matcher m = (Matcher) ctx;
            int at = start;
            while (at <= text.length() && !Thread.currentThread().isInterrupted() && m.find(at)) {
                if (m.end() == m.start()) { at = m.start() + 1; continue; }
                if (passesWholeWord(text, m.start(), m.end())) return new Match(m.start(), m.end());
                at = m.start() + 1;
            }
            return null;
        }
        NormalizedSearchText view = (NormalizedSearchText) ctx;
        String hay = view.value;
        String needle = comparableQuery;
        int idx = hay.indexOf(needle, view.comparisonStart(start));
        while (idx >= 0 && !Thread.currentThread().isInterrupted()) {
            int hit = view.originalStart(idx);
            int end = view.originalEnd(idx + needle.length());
            if (hit >= start && passesWholeWord(text, hit, end)) return new Match(hit, end);
            idx = hay.indexOf(needle, idx + 1);
        }
        return null;
    }

    private boolean passesWholeWord(String text, int start, int end) {
        if (!options.wholeWord) return true;
        boolean leftOk = start == 0 || !isWordChar(text.codePointBefore(start));
        boolean rightOk = end >= text.length() || !isWordChar(text.codePointAt(end));
        return leftOk && rightOk;
    }

    private static boolean isWordChar(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isLetterOrDigit(codePoint) || codePoint == '_'
                || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    // --- width-preserving case comparison -----------------------------------

    private String foldCase(String s) {
        String out = s;
        if (!options.caseSensitive) {
            // Locale-independent case comparison: upper-then-lower unifies final
            // sigma and long-s. Never expand a character or move UTF-16 offsets.
            char[] cs = null;
            for (int i = 0; i < out.length(); ) {
                int original = out.codePointAt(i);
                int width = Character.charCount(original);
                int folded = Character.toLowerCase(Character.toUpperCase(original));
                if (folded != original && Character.charCount(folded) == width) {
                    if (cs == null) cs = out.toCharArray();
                    if (width == 1) cs[i] = (char) folded;
                    else {
                        cs[i] = Character.highSurrogate(folded);
                        cs[i + 1] = Character.lowSurrogate(folded);
                    }
                }
                i += width;
            }
            if (cs != null) out = new String(cs);
        }
        return out;
    }
}
