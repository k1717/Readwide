package com.readwide.manager.util;

/**
 * Display-only collapsing of repeated blank lines for the text reader.
 *
 * <p>When enabled, any run of two or more consecutive blank lines is reduced to
 * a single blank line; a lone blank line is left as-is. The original file is
 * never modified — this only changes what the reader lays out.
 *
 * <p>The large-TXT reader builds its page index and bookmark anchors by walking
 * lines and accumulating {@code line length + 1} as a character offset. Collapsing
 * removes lines, which shifts those offsets, so every reader path that walks the
 * file must apply the <em>same</em> collapsing and advance its line/char counters
 * only for lines that are actually emitted. {@link Filter} exists so the three
 * partition-walk loops (line-stat scan, partition assembly, exact-anchor build)
 * share one identical decision and stay on the same emitted-line numbering, and
 * the collapse state is folded into the page-layout signature so toggling it
 * recomputes the cached page model instead of reusing a stale one.
 */
public final class TxtBlankLineCollapser {
    private TxtBlankLineCollapser() {}

    /** A line counts as blank when it is empty or contains only whitespace. */
    public static boolean isBlankLine(String line) {
        if (line == null || line.isEmpty()) return true;
        for (int i = 0; i < line.length(); i++) {
            if (!Character.isWhitespace(line.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Stateful per-line filter. Feed each already-normalized line in order;
     * the return value is the text to emit, or {@code null} when the line should
     * be dropped (a blank line immediately following a blank line that was kept).
     * A kept blank line is normalized to the empty string. When {@code enabled}
     * is false the input is returned unchanged, so callers can construct it
     * unconditionally and keep a single code path.
     */
    public static final class Filter {
        private final boolean enabled;
        private boolean prevEmittedBlank;

        public Filter(boolean enabled) {
            this.enabled = enabled;
        }

        public String accept(String line) {
            if (!enabled) return line;
            boolean blank = isBlankLine(line);
            if (blank && prevEmittedBlank) return null;
            prevEmittedBlank = blank;
            return blank ? "" : line;
        }
    }

    /**
     * Full-string variant for the non-partitioned (small file) load path. Splits
     * on CR, LF, or CRLF, applies the same collapsing, and rejoins with '\n'
     * (the line break the reader/TextView uses). Only call this when collapsing
     * is enabled; it intentionally normalizes line breaks as part of the change.
     */
    public static String collapse(String text) {
        if (text == null || text.isEmpty()) return text != null ? text : "";
        Filter filter = new Filter(true);
        StringBuilder out = new StringBuilder(text.length());
        int n = text.length();
        int i = 0;
        boolean firstEmitted = true;
        while (i < n) {
            int lineStart = i;
            while (i < n) {
                char c = text.charAt(i);
                if (c == '\n' || c == '\r') break;
                i++;
            }
            String line = text.substring(lineStart, i);
            if (i < n && text.charAt(i) == '\r') i++;
            if (i < n && text.charAt(i) == '\n') i++;
            String emitted = filter.accept(line);
            if (emitted == null) continue;
            if (!firstEmitted) out.append('\n');
            firstEmitted = false;
            out.append(emitted);
        }
        return out.toString();
    }
}
