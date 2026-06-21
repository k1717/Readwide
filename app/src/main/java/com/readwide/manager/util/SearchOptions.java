package com.readwide.manager.util;

/**
 * Immutable set of TXT find-in-page options shared by the in-memory
 * ({@link TextSearchMath}) and large-file ({@code LargeTextSearchEngine})
 * search paths so both honor exactly the same matching rules.
 *
 * <p>All matching ultimately runs through {@link SearchMatcher}. The flags are
 * intentionally length-preserving in effect: case folding and Unicode
 * normalization are applied to a comparison view of the text whose character
 * indices still line up 1:1 with the original content, so returned positions
 * remain valid for bookmarks and page anchors.
 */
public final class SearchOptions {
    public final boolean caseSensitive;
    public final boolean wholeWord;
    public final boolean regex;
    public final boolean normalizeUnicode;

    public SearchOptions(boolean caseSensitive,
                         boolean wholeWord,
                         boolean regex,
                         boolean normalizeUnicode) {
        this.caseSensitive = caseSensitive;
        this.wholeWord = wholeWord;
        this.regex = regex;
        this.normalizeUnicode = normalizeUnicode;
    }

    /** Plain literal substring search (legacy behavior), case-sensitive. */
    public static SearchOptions literal() {
        return new SearchOptions(true, false, false, false);
    }

    public boolean equalsOptions(SearchOptions other) {
        return other != null
                && caseSensitive == other.caseSensitive
                && wholeWord == other.wholeWord
                && regex == other.regex
                && normalizeUnicode == other.normalizeUnicode;
    }

    /**
     * Stable key for cache/identity comparisons. Distinct option sets produce
     * distinct strings so a cached match total is not reused after the user
     * toggles case sensitivity, whole-word, regex, or Unicode normalization.
     */
    public String signature() {
        return (caseSensitive ? "C" : "c")
                + (wholeWord ? "W" : "w")
                + (regex ? "R" : "r")
                + (normalizeUnicode ? "N" : "n");
    }
}
