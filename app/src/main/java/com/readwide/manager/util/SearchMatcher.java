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
 * <p><b>Index stability.</b> Returned match start/end offsets are always
 * relative to the original {@code text} passed in, so callers can keep using
 * them for bookmarks and page anchors. To make that safe, literal matching
 * compares against a <em>length-preserving</em> view of the text: case folding
 * is done per-char and Unicode normalization is only applied when it does not
 * change the string length (the overwhelmingly common case for NFC). When a
 * normalization would change length, the matcher falls back to the un-normalized
 * (still length-stable) comparison so offsets never drift.
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

    private SearchMatcher(SearchOptions options, String query, Pattern regexPattern) {
        this.options = options;
        this.query = query;
        this.regexPattern = regexPattern;
        // Precompute once; reused across all match stepping for literal mode.
        this.comparableQuery = regexPattern == null ? comparable(query) : query;
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
            int flags = Pattern.UNICODE_CASE;
            if (!opt.caseSensitive) flags |= Pattern.CASE_INSENSITIVE;
            if (opt.normalizeUnicode) flags |= Pattern.CANON_EQ;
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
        return firstPrepared(prepare(text), text, from);
    }

    /** Finds the last match at or before {@code from}, or {@code null}. */
    public Match lastUpTo(String text, int from) {
        if (text == null || text.isEmpty()) return null;
        int cap = Math.max(0, Math.min(text.length() - 1, from));
        Match best = null;
        // Build the comparison view / matcher once, then scan.
        Object ctx = prepare(text);
        Match m = firstPrepared(ctx, text, 0);
        while (m != null && m.start <= cap) {
            best = m;
            m = firstPrepared(ctx, text, nextStart(m));
        }
        return best;
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
        int count = 0;
        Object ctx = prepare(text);
        Match m = firstPrepared(ctx, text, 0);
        while (m != null) {
            count++;
            m = firstPrepared(ctx, text, nextStart(m));
        }
        return count;
    }

    /** Start offset of the 1-based nth match, or -1. */
    public int nthStart(String text, int occurrence) {
        if (text == null || text.isEmpty()) return -1;
        int target = Math.max(1, occurrence);
        int n = 0;
        Object ctx = prepare(text);
        Match m = firstPrepared(ctx, text, 0);
        while (m != null) {
            n++;
            if (n == target) return m.start;
            m = firstPrepared(ctx, text, nextStart(m));
        }
        return -1;
    }

    /** 1-based ordinal of the match whose start is the first &gt;= position. */
    public int ordinalForPosition(String text, int position) {
        if (text == null || text.isEmpty()) return 0;
        int n = 0;
        Object ctx = prepare(text);
        Match m = firstPrepared(ctx, text, 0);
        while (m != null) {
            n++;
            if (m.start >= position) return n;
            m = firstPrepared(ctx, text, nextStart(m));
        }
        return n;
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
        Object ctx = prepare(text);
        Match m = firstPrepared(ctx, text, 0);
        while (m != null) {
            if (!consumer.accept(m.start, m.end)) return;
            m = firstPrepared(ctx, text, nextStart(m));
        }
    }


    /**
     * Precomputes the per-text scan context once so repeated match stepping does
     * not re-normalize the whole text (literal) or rebuild the engine (regex).
     * Returns the comparison-view String for literal mode, or a java Matcher for
     * regex mode.
     */
    private Object prepare(String text) {
        if (regexPattern != null) return regexPattern.matcher(text);
        return comparable(text);
    }

    private Match firstPrepared(Object ctx, String text, int from) {
        if (text == null || text.isEmpty()) return null;
        int start = Math.max(0, Math.min(text.length(), from));
        if (regexPattern != null) {
            Matcher m = (Matcher) ctx;
            int at = start;
            while (at <= text.length() && m.find(at)) {
                if (m.end() == m.start()) { at = m.start() + 1; continue; }
                if (passesWholeWord(text, m.start(), m.end())) return new Match(m.start(), m.end());
                at = m.start() + 1;
            }
            return null;
        }
        String hay = (String) ctx;
        String needle = comparableQuery;
        int idx = hay.indexOf(needle, start);
        while (idx >= 0) {
            int end = idx + needle.length();
            if (passesWholeWord(text, idx, end)) return new Match(idx, end);
            idx = hay.indexOf(needle, idx + 1);
        }
        return null;
    }

    private boolean passesWholeWord(String text, int start, int end) {
        if (!options.wholeWord) return true;
        boolean leftOk = start == 0 || !isWordChar(text.charAt(start - 1));
        boolean rightOk = end >= text.length() || !isWordChar(text.charAt(end));
        return leftOk && rightOk;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // --- length-preserving comparison view -----------------------------------

    private String comparable(String s) {
        String out = s;
        if (options.normalizeUnicode) {
            String n = Normalizer.normalize(out, Normalizer.Form.NFC);
            // Only adopt normalization if it preserves length so match offsets
            // still map onto the original text.
            if (n.length() == out.length()) out = n;
        }
        if (!options.caseSensitive) {
            // Per-char lowercasing keeps length stable (unlike String.toLowerCase
            // under some locales/characters, e.g. Turkish dotted I or ß).
            char[] cs = out.toCharArray();
            for (int i = 0; i < cs.length; i++) {
                cs[i] = Character.toLowerCase(cs[i]);
            }
            out = new String(cs);
        }
        return out;
    }
}
