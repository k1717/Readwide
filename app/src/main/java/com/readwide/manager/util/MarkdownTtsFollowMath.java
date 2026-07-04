package com.readwide.manager.util;

import androidx.annotation.NonNull;

/**
 * Approximate mapping from a read-aloud character position (in the rendered
 * plain-text buffer the TTS controller speaks from) back to an offset in the raw
 * Markdown source, so the Markdown viewer can scroll to roughly follow playback.
 *
 * <p>The two coordinate spaces differ: the TTS buffer is
 * {@code htmlToPlainText(renderedHtml)} (no {@code #}, {@code *}, link syntax,
 * etc.), while the viewer scrolls by offsets into {@code markdownSourceText} (the
 * raw {@code .md}). There is no exact bijection without instrumenting the
 * renderer, so this does a best-effort search: it pulls a short, distinctive
 * word run from around the spoken position and finds it in the source. When the
 * run can't be located (heavily transformed text, or a duplicated phrase), it
 * falls back to a proportional estimate. "Approximate following" by design - the
 * viewer lands near the spoken paragraph, not on the exact glyph.</p>
 */
public final class MarkdownTtsFollowMath {

    private MarkdownTtsFollowMath() {
    }

    /** Number of chars around the spoken position to build the search probe from. */
    private static final int PROBE_RADIUS = 24;
    /** Minimum probe length worth searching; shorter is too ambiguous. */
    private static final int MIN_PROBE = 6;
    /**
     * Half-width of the source window searched around the expected position.
     * Bounding the search keeps the per-segment cost O(window), not O(source):
     * a full scan of a large source on every spoken segment would jank the UI
     * thread, and always taking the first global match would snap the view
     * backward whenever a phrase repeats earlier in the document.
     */
    private static final int SEARCH_WINDOW_RADIUS = 16384;

    /** Backward-compatible entry: no position hint (windows around the proportional estimate). */
    public static int approximateSourceOffset(@NonNull String plainText,
                                              int ttsCharPos,
                                              @NonNull String markdownSource) {
        return approximateSourceOffset(plainText, ttsCharPos, markdownSource, -1);
    }

    /**
     * @param plainText   the rendered plain-text buffer TTS speaks from
     * @param ttsCharPos  the current absolute char position within {@code plainText}
     * @param markdownSource the raw Markdown source to map into
     * @param hintSourceOffset the last known source offset for this playback (or a
     *                         negative value for none); the search is windowed
     *                         around it so sequential reading stays fast and
     *                         repeated phrases resolve to the nearby occurrence
     * @return a best-effort offset into {@code markdownSource}, clamped to its range
     */
    public static int approximateSourceOffset(@NonNull String plainText,
                                              int ttsCharPos,
                                              @NonNull String markdownSource,
                                              int hintSourceOffset) {
        int srcLen = markdownSource.length();
        if (srcLen == 0) return 0;
        int plainLen = plainText.length();
        if (plainLen == 0) return 0;

        int pos = Math.max(0, Math.min(plainLen, ttsCharPos));
        double ratio = (double) pos / (double) plainLen;
        int proportional = Math.max(0, Math.min(srcLen, (int) Math.round(ratio * srcLen)));

        String probe = buildProbe(plainText, pos);
        if (probe.length() >= MIN_PROBE) {
            // Search a bounded window around the best expectation first: the last
            // known offset if the caller has one (sequential reading), otherwise
            // the proportional estimate.
            int center = hintSourceOffset >= 0
                    ? Math.min(srcLen, hintSourceOffset) : proportional;
            int from = Math.max(0, center - SEARCH_WINDOW_RADIUS);
            int to = Math.min(srcLen, center + SEARCH_WINDOW_RADIUS);
            int found = indexOfNormalized(markdownSource, probe, from, to);
            if (found < 0 && hintSourceOffset >= 0 && Math.abs(proportional - center) > SEARCH_WINDOW_RADIUS) {
                // The hint window missed and the proportional estimate lies well
                // outside it (e.g. after a large seek) - try one more window there.
                from = Math.max(0, proportional - SEARCH_WINDOW_RADIUS);
                to = Math.min(srcLen, proportional + SEARCH_WINDOW_RADIUS);
                found = indexOfNormalized(markdownSource, probe, from, to);
            }
            if (found >= 0) {
                return Math.max(0, Math.min(srcLen, found));
            }
        }

        // Fallback: proportional estimate. Where we are in the plain text is
        // roughly where we are in the source.
        return proportional;
    }

    /**
     * Extracts a compact probe string of visible word characters around
     * {@code pos}. Collapses internal whitespace to single spaces so it can be
     * matched against source that may wrap differently.
     */
    @NonNull
    static String buildProbe(@NonNull String plainText, int pos) {
        int len = plainText.length();
        if (len == 0) return "";
        int start = Math.max(0, pos - PROBE_RADIUS);
        int end = Math.min(len, pos + PROBE_RADIUS);
        // Expand to word boundaries so we don't cut a word in half.
        while (start > 0 && !Character.isWhitespace(plainText.charAt(start - 1))) start--;
        while (end < len && !Character.isWhitespace(plainText.charAt(end))) end++;

        StringBuilder sb = new StringBuilder(end - start);
        boolean lastWasSpace = false;
        for (int i = start; i < end; i++) {
            char c = plainText.charAt(i);
            if (Character.isWhitespace(c)) {
                if (sb.length() > 0 && !lastWasSpace) {
                    sb.append(' ');
                    lastWasSpace = true;
                }
            } else {
                sb.append(c);
                lastWasSpace = false;
            }
        }
        return sb.toString().trim();
    }

    /**
     * Finds {@code probe} in {@code source} ignoring case and treating any run of
     * whitespace in the source as a single space (so a probe built from wrapped
     * plain text still matches source with different line breaks). Returns the
     * source index of the match start, or -1.
     */
    static int indexOfNormalized(@NonNull String source, @NonNull String probe) {
        return indexOfNormalized(source, probe, 0, source.length());
    }

    /** Range-bounded variant: match starts are considered in [from, to). */
    static int indexOfNormalized(@NonNull String source, @NonNull String probe,
                                 int from, int to) {
        if (probe.isEmpty()) return -1;
        int sLen = source.length();
        int pLen = probe.length();
        int start = Math.max(0, from);
        int end = Math.min(sLen, to);

        for (int i = start; i < end; i++) {
            int si = i;
            int pi = 0;
            int matchStart = i;
            boolean ok = true;
            while (pi < pLen) {
                if (si >= sLen) { ok = false; break; }
                char pc = probe.charAt(pi);
                char sc = source.charAt(si);
                if (pc == ' ') {
                    // Probe space matches one or more source whitespace chars.
                    if (!Character.isWhitespace(sc)) { ok = false; break; }
                    while (si < sLen && Character.isWhitespace(source.charAt(si))) si++;
                    pi++;
                } else {
                    if (Character.toLowerCase(sc) != Character.toLowerCase(pc)) { ok = false; break; }
                    si++;
                    pi++;
                }
            }
            if (ok && pi == pLen) {
                return matchStart;
            }
        }
        return -1;
    }
}
