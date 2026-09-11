package com.readwide.manager;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** PDF search in original UTF-16 coordinates, independent of Android graphics. */
final class PdfSearchText {
    private PdfSearchText() { }

    interface RangeConsumer { void accept(int start, int end); }

    static final class Query {
        private final Pattern pattern;
        private final boolean wholeWord;

        private Query(Pattern pattern, boolean wholeWord) {
            this.pattern = pattern;
            this.wholeWord = wholeWord;
        }

        void forEach(String text, BooleanSupplier cancelled, RangeConsumer consumer) {
            if (text == null || text.isEmpty() || cancelled.getAsBoolean()) return;
            Matcher matcher = pattern.matcher(text);
            while (!cancelled.getAsBoolean() && matcher.find()) {
                if (cancelled.getAsBoolean()) return;
                int start = matcher.start(), end = matcher.end();
                // Matcher.find advances after an empty hit; don't lose later nonempty hits.
                if (start == end || (wholeWord && !isWholeWord(text, start, end))) continue;
                consumer.accept(start, end);
            }
        }
    }

    static Query compile(String query, boolean caseSensitive, boolean wholeWord, boolean regex) {
        if (query == null || query.isEmpty()) return null;
        int flags = Pattern.MULTILINE;
        if (!caseSensitive) flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        try {
            // Match the original string: whole-string lowercase can change its length.
            return new Query(Pattern.compile(regex ? query : Pattern.quote(query), flags), wholeWord);
        } catch (PatternSyntaxException invalidWhileTyping) {
            return null;
        }
    }

    private static boolean isWholeWord(String text, int start, int end) {
        return (start == 0 || !isWord(text.codePointBefore(start)))
                && (end == text.length() || !isWord(text.codePointAt(end)));
    }

    private static boolean isWord(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    /** Inferred whitespace has no glyph box, but must occupy matching text/box offsets. */
    static <T> void appendSeparator(StringBuilder text, List<T> boxes, String separator) {
        text.append(separator);
        for (int i = 0; i < separator.length(); i++) boxes.add(null);
    }
}
