package com.readwide.manager;

import java.util.Locale;
import java.util.regex.Pattern;

/** WebView aliases for legacy EPUB CSS properties. */
final class EpubCssCompatibility {
    private static final String EPUB_WRITING_MODE = "-epub-writing-mode";
    private static final String EPUB_TEXT_COMBINE = "-epub-text-combine";
    private static final String EPUB_TEXT_EMPHASIS_STYLE = "-epub-text-emphasis-style";
    private static final String EPUB_TEXT_EMPHASIS_COLOR = "-epub-text-emphasis-color";

    private static final Pattern VERTICAL_WRITING = Pattern.compile(
            "(?i)(?:^|[;{])\\s*(?:-epub-|-webkit-)?writing-mode\\s*:\\s*vertical-(?:rl|lr)\\b");

    private EpubCssCompatibility() {}

    /**
     * Retains the publisher's declarations and appends equivalent standard and
     * WebKit declarations. Comments, quoted strings, selectors, and unrelated
     * declarations are copied byte-for-character.
     */
    static String addWebViewAliases(String css) {
        if (css == null || css.isEmpty()) return css == null ? "" : css;

        StringBuilder out = new StringBuilder(css.length() + 128);
        boolean inlineDeclarations = css.indexOf('{') < 0;
        boolean expectProperty = inlineDeclarations;
        int braceDepth = 0;
        int i = 0;
        while (i < css.length()) {
            if (startsComment(css, i)) {
                int end = commentEnd(css, i + 2);
                out.append(css, i, end);
                i = end;
                continue;
            }

            char ch = css.charAt(i);
            if (ch == '\'' || ch == '"') {
                int end = quotedEnd(css, i, ch);
                out.append(css, i, end);
                i = end;
                continue;
            }
            if (ch == '{') {
                braceDepth++;
                expectProperty = true;
                out.append(ch);
                i++;
                continue;
            }
            if (ch == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
                expectProperty = false;
                out.append(ch);
                i++;
                continue;
            }
            if (ch == ';') {
                expectProperty = braceDepth > 0 || inlineDeclarations;
                out.append(ch);
                i++;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                out.append(ch);
                i++;
                continue;
            }

            String property = expectProperty ? legacyPropertyAt(css, i) : null;
            int colon = property != null ? colonAfterProperty(css, i + property.length()) : -1;
            if (colon < 0) {
                expectProperty = false;
                out.append(ch);
                i++;
                continue;
            }

            int end = declarationEnd(css, colon + 1);
            char delimiter = end < css.length() ? css.charAt(end) : '\0';
            String value = cleanValue(css.substring(colon + 1, end));

            if (delimiter == ';') {
                out.append(css, i, end + 1);
                out.append(aliasDeclarations(property, value));
                i = end + 1;
                expectProperty = true;
            } else {
                out.append(css, i, end);
                appendSemicolonIfNeeded(out);
                out.append(aliasDeclarations(property, value));
                i = end;
                expectProperty = false;
            }
        }
        return out.toString();
    }

    /** Detects an active vertical writing-mode declaration in CSS text. */
    static boolean detectsVerticalWriting(String css) {
        if (css == null || css.isEmpty()) return false;
        return VERTICAL_WRITING.matcher(stripCommentsAndStrings(css)).find();
    }

    private static String aliasDeclarations(String property, String value) {
        if (property.equalsIgnoreCase(EPUB_WRITING_MODE)) {
            return "writing-mode:" + value + ";-webkit-writing-mode:" + value + ";";
        }
        if (property.equalsIgnoreCase(EPUB_TEXT_COMBINE)) {
            String standard = standardTextCombineValue(value);
            return "text-combine-upright:" + standard + ";-webkit-text-combine:" + value + ";";
        }
        if (property.equalsIgnoreCase(EPUB_TEXT_EMPHASIS_STYLE)) {
            return "text-emphasis-style:" + value + ";-webkit-text-emphasis-style:" + value + ";";
        }
        if (property.equalsIgnoreCase(EPUB_TEXT_EMPHASIS_COLOR)) {
            return "text-emphasis-color:" + value + ";-webkit-text-emphasis-color:" + value + ";";
        }
        return "";
    }

    private static String standardTextCombineValue(String value) {
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        int important = lower.lastIndexOf("!important");
        String suffix = important >= 0 ? trimmed.substring(important).trim() : "";
        String core = important >= 0 ? trimmed.substring(0, important).trim() : trimmed;
        if ("horizontal".equalsIgnoreCase(core)) {
            return suffix.isEmpty() ? "all" : "all " + suffix;
        }
        return trimmed;
    }

    private static String legacyPropertyAt(String css, int offset) {
        if (matchesProperty(css, offset, EPUB_WRITING_MODE)) return EPUB_WRITING_MODE;
        if (matchesProperty(css, offset, EPUB_TEXT_COMBINE)) return EPUB_TEXT_COMBINE;
        if (matchesProperty(css, offset, EPUB_TEXT_EMPHASIS_STYLE)) return EPUB_TEXT_EMPHASIS_STYLE;
        if (matchesProperty(css, offset, EPUB_TEXT_EMPHASIS_COLOR)) return EPUB_TEXT_EMPHASIS_COLOR;
        return null;
    }

    private static boolean matchesProperty(String css, int offset, String property) {
        if (offset < 0 || offset + property.length() > css.length()) return false;
        return css.regionMatches(true, offset, property, 0, property.length());
    }

    private static int colonAfterProperty(String css, int offset) {
        int i = offset;
        while (i < css.length() && Character.isWhitespace(css.charAt(i))) i++;
        return i < css.length() && css.charAt(i) == ':' ? i : -1;
    }

    private static int declarationEnd(String css, int offset) {
        int parens = 0;
        int i = offset;
        while (i < css.length()) {
            if (startsComment(css, i)) {
                i = commentEnd(css, i + 2);
                continue;
            }
            char ch = css.charAt(i);
            if (ch == '\'' || ch == '"') {
                i = quotedEnd(css, i, ch);
                continue;
            }
            if (ch == '(') parens++;
            else if (ch == ')' && parens > 0) parens--;
            else if (parens == 0 && (ch == ';' || ch == '}')) return i;
            i++;
        }
        return css.length();
    }

    private static int quotedEnd(String text, int quoteOffset, char quote) {
        int i = quoteOffset + 1;
        while (i < text.length()) {
            char ch = text.charAt(i++);
            if (ch == '\\' && i < text.length()) i++;
            else if (ch == quote) break;
        }
        return i;
    }

    private static boolean startsComment(String text, int offset) {
        return offset + 1 < text.length()
                && text.charAt(offset) == '/'
                && text.charAt(offset + 1) == '*';
    }

    private static int commentEnd(String text, int offset) {
        int close = text.indexOf("*/", offset);
        return close >= 0 ? close + 2 : text.length();
    }

    private static String cleanValue(String value) {
        return stripComments(value).trim();
    }

    private static String stripComments(String value) {
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            if (startsComment(value, i)) {
                i = commentEnd(value, i + 2);
                out.append(' ');
            } else {
                out.append(value.charAt(i++));
            }
        }
        return out.toString();
    }

    private static String stripCommentsAndStrings(String value) {
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            if (startsComment(value, i)) {
                i = commentEnd(value, i + 2);
                out.append(' ');
                continue;
            }
            char ch = value.charAt(i);
            if (ch == '\'' || ch == '"') {
                i = quotedEnd(value, i, ch);
                out.append(' ');
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    private static void appendSemicolonIfNeeded(StringBuilder out) {
        int i = out.length() - 1;
        while (i >= 0 && Character.isWhitespace(out.charAt(i))) i--;
        if (i >= 0 && out.charAt(i) != ';') out.append(';');
    }
}
