package com.readwide.manager;

import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** WebView aliases for legacy EPUB CSS properties. */
final class EpubCssCompatibility {
    private static final String EPUB_WRITING_MODE = "-epub-writing-mode";
    private static final String EPUB_TEXT_COMBINE = "-epub-text-combine";
    private static final String EPUB_TEXT_EMPHASIS_STYLE = "-epub-text-emphasis-style";
    private static final String EPUB_TEXT_EMPHASIS_COLOR = "-epub-text-emphasis-color";

    private static final Pattern IMPORTANT = Pattern.compile("(?i)!\\s*important\\s*$");
    private static final Pattern LEGACY_PREFIX = Pattern.compile("(?i)-epub-");
    private static final Pattern TEXT_ALIGN = Pattern.compile("(?i)text-align");
    private static final Pattern LINE_HEIGHT = Pattern.compile("(?i)line-height");
    private static final Pattern SIMPLE_LINE_HEIGHT = Pattern.compile(
            "(?i)(?:normal|(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:%|em|rem|ex|ch|px|pt|pc|in|cm|mm|q|vh|vw|vmin|vmax)?)");

    private EpubCssCompatibility() {}

    /**
     * Wrap only literal prose alignment values. The original value is the fallback
     * when a page has no override (including fixed/image/vertical pages). This also
     * makes shared/imported stylesheets independent of the page requesting them.
     * Explicit center/right/end, expressions, custom-property bodies and priorities
     * are preserved; no document scripts or selector-specific !important override
     * are needed.
     */
    static String prepareParagraphAlignment(String css) {
        if (css == null || css.isEmpty()) return css == null ? "" : css;
        if (!TEXT_ALIGN.matcher(css).find()) return css;
        StringBuilder out = new StringBuilder(css.length());
        int[] copied = {0};
        scanDeclarations(css, (property, valueStart, end, scope) -> {
            if (!scope.screen) return;
            boolean alignment = "text-align".equalsIgnoreCase(property);
            boolean lastLine = "text-align-last".equalsIgnoreCase(property);
            if (!alignment && !lastLine) return;
            String value = cleanValue(css.substring(valueStart, end));
            Matcher important = IMPORTANT.matcher(value);
            boolean priority = important.find();
            if (priority) value = value.substring(0, important.start()).trim();
            if (!("justify".equalsIgnoreCase(value)
                    || (alignment && ("left".equalsIgnoreCase(value) || "start".equalsIgnoreCase(value))))) return;
            out.append(css, copied[0], valueStart).append("var(")
                    .append(alignment ? "--readwide-epub-align" : "--readwide-epub-align-last")
                    .append(',').append(value).append(')');
            if (priority) out.append(" !important");
            copied[0] = end;
        });
        return copied[0] == 0 ? css : out.append(css, copied[0], css.length()).toString();
    }

    /** Insert before book styles so explicit book centering still wins the cascade. */
    static String paragraphAlignmentStyle(int alignment) {
        int mode = com.readwide.manager.util.EpubTextAlignment.normalize(alignment);
        if (mode == com.readwide.manager.util.EpubTextAlignment.BOOK_DEFAULT) return "";
        String value = mode == com.readwide.manager.util.EpubTextAlignment.START ? "start" : "justify";
        return "<style id=\"readwide-epub-paragraph-alignment\">"
                + "html{--readwide-epub-align:" + value + " !important;"
                + "--readwide-epub-align-last:auto !important;text-align:" + value + ";}"
                // Invalid custom properties activate the original declaration's fallback.
                // Do not add per-paragraph rules: they would override an inherited center.
                + "h1,h2,h3,h4,h5,h6,table,pre,code,figure,figcaption,caption,svg,math,nav,blockquote,"
                + "[epub\\:type~=\"z3998:poem\"],[epub\\:type~=\"z3998:verse\"]"
                + "{--readwide-epub-align:initial !important;--readwide-epub-align-last:initial !important;}"
                + "</style>";
    }

    /** Keep complex expressions and font shorthands intact; literal values retain a fallback. */
    static String prepareLineHeight(String css) {
        if (css == null || css.isEmpty()) return css == null ? "" : css;
        if (!LINE_HEIGHT.matcher(css).find()) return css;
        StringBuilder out = new StringBuilder(css.length());
        int[] copied = {0};
        scanDeclarations(css, (property, valueStart, end, scope) -> {
            if (!scope.screen || !"line-height".equalsIgnoreCase(property)) return;
            String value = cleanValue(css.substring(valueStart, end));
            Matcher important = IMPORTANT.matcher(value);
            boolean priority = important.find();
            if (priority) value = value.substring(0, important.start()).trim();
            if (!SIMPLE_LINE_HEIGHT.matcher(value).matches()) return;
            out.append(css, copied[0], valueStart).append("var(--readwide-epub-line-height,")
                    .append(value).append(')');
            if (priority) out.append(" !important");
            copied[0] = end;
        });
        return copied[0] == 0 ? css : out.append(css, copied[0], css.length()).toString();
    }

    static String lineHeightStyle(int choice) {
        String value = com.readwide.manager.util.EpubTypography.lineHeightCss(choice);
        if (value.isEmpty()) return "";
        return "<style id=\"readwide-epub-line-height\">"
                + "html{--readwide-epub-line-height:" + value + " !important;line-height:" + value + ";}"
                + "h1,h2,h3,h4,h5,h6,table,pre,code,figure,figcaption,caption,svg,math,nav,blockquote,"
                + "[epub\\:type~=\"z3998:poem\"],[epub\\:type~=\"z3998:verse\"]"
                + "{--readwide-epub-line-height:initial !important;}"
                + "</style>";
    }

    static String addHtmlWebViewAliases(String html) {
        if (html == null || html.isEmpty()) return html == null ? "" : html;
        if (!LEGACY_PREFIX.matcher(html).find()) return html;
        return com.readwide.manager.util.EpubBindingRewriter.transformEmbeddedCss(
                html, EpubCssCompatibility::addWebViewAliases);
    }

    static boolean detectsVerticalWritingInHtml(String html) {
        boolean[] found = {false};
        com.readwide.manager.util.EpubBindingRewriter.inspectDocumentStyles(html, css -> {
            if (!found[0]) found[0] = detectsVerticalWriting(css);
        }, href -> {});
        return found[0];
    }

    /** Common top-level @import forms, with comments/strings kept out of scanning. */
    static List<String> importedStylesheets(String css) {
        List<String> out = new ArrayList<>();
        if (css == null) return out;
        int i = 0;
        while (i < css.length() && out.size() < 200) {
            i = skipCssSpace(css, i);
            if (i >= css.length()) break;
            if (atKeyword(css, i, "@import")) {
                int end = importRuleEnd(css, i + 7);
                if (end >= css.length() || css.charAt(end) != ';') break;
                String value = css.substring(i + 7, end);
                int start = skipCssSpace(value, 0);
                boolean url = value.regionMatches(true, start, "url(", 0, 4);
                if (url) start = skipCssSpace(value, start + 4);
                int after = start;
                String href = null;
                if (start < value.length() && (value.charAt(start) == '\'' || value.charAt(start) == '"')) {
                    after = quotedEnd(value, start, value.charAt(start));
                    if (after > start + 1 && value.charAt(after - 1) == value.charAt(start)) {
                        href = value.substring(start + 1, after - 1);
                    }
                } else if (url) {
                    while (after < value.length() && value.charAt(after) != ')') {
                        if (value.charAt(after) == '\\' && after + 1 < value.length()) after++;
                        after++;
                    }
                    href = value.substring(start, after).trim();
                }
                after = skipCssSpace(value, after);
                if (url) {
                    if (after >= value.length() || value.charAt(after) != ')') href = null;
                    else after = skipCssSpace(value, after + 1);
                }
                if (href != null && !href.isEmpty()
                        && com.readwide.manager.util.EpubBindingRewriter.mayApplyToScreen(value.substring(after))) {
                    out.add(unescapeCssReference(href));
                }
                i = end + 1;
            } else if (atKeyword(css, i, "@charset") || atKeyword(css, i, "@layer")) {
                int end = importRuleEnd(css, i);
                if (end >= css.length() || css.charAt(end) != ';') break;
                i = end + 1;
            } else {
                // Imports after ordinary rules are invalid and must not affect hints.
                break;
            }
        }
        return out;
    }

    private static boolean atKeyword(String text, int start, String keyword) {
        if (!text.regionMatches(true, start, keyword, 0, keyword.length())) return false;
        int end = start + keyword.length();
        if (end == text.length()) return true;
        char next = text.charAt(end);
        return !Character.isLetterOrDigit(next) && next != '-' && next != '_' && next != '\\';
    }

    private static int importRuleEnd(String text, int start) {
        int parens = 0;
        for (int i = start; i < text.length();) {
            if (startsComment(text, i)) { i = commentEnd(text, i + 2); continue; }
            char ch = text.charAt(i);
            if (ch == '\'' || ch == '"') { i = quotedEnd(text, i, ch); continue; }
            if (ch == '\\' && i + 1 < text.length()) { i += 2; continue; }
            if (ch == '(') parens++;
            else if (ch == ')') parens = Math.max(0, parens - 1);
            else if (parens == 0 && (ch == ';' || ch == '{' || ch == '}')) return i;
            i++;
        }
        return text.length();
    }

    private static int skipCssSpace(String text, int i) {
        while (i < text.length()) {
            if (Character.isWhitespace(text.charAt(i))) i++;
            else if (startsComment(text, i)) i = commentEnd(text, i + 2);
            else if (wrapperLength(text, i) > 0) i += wrapperLength(text, i);
            else break;
        }
        return i;
    }

    private static String unescapeCssReference(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            char ch = text.charAt(i++);
            if (ch != '\\' || i == text.length()) { out.append(ch); continue; }
            int value = 0, digits = 0;
            while (i < text.length() && digits < 6) {
                int digit = Character.digit(text.charAt(i), 16);
                if (digit < 0) break;
                value = value * 16 + digit; digits++; i++;
            }
            if (digits > 0) {
                if (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                    char space = text.charAt(i++);
                    if (space == '\r' && i < text.length() && text.charAt(i) == '\n') i++;
                }
                out.appendCodePoint(value > 0 && Character.isValidCodePoint(value)
                        && !(value >= 0xd800 && value <= 0xdfff) ? value : 0xfffd);
            } else {
                ch = text.charAt(i++);
                if (ch == '\r' && i < text.length() && text.charAt(i) == '\n') i++;
                else if (ch != '\n' && ch != '\r' && ch != '\f') out.append(ch);
            }
        }
        return out.toString();
    }

    /**
     * Retains the publisher's declarations and appends equivalent standard and
     * WebKit declarations. Comments, quoted strings, selectors, and unrelated
     * declarations are copied byte-for-character.
     */
    static String addWebViewAliases(String css) {
        if (css == null || css.isEmpty()) return css == null ? "" : css;
        if (!LEGACY_PREFIX.matcher(css).find()) return css;

        StringBuilder out = new StringBuilder(css.length() + 128);
        int[] copied = {0};
        scanDeclarations(css, (property, valueStart, end, scope) -> {
            if (!(EPUB_WRITING_MODE.equalsIgnoreCase(property) || EPUB_TEXT_COMBINE.equalsIgnoreCase(property)
                    || EPUB_TEXT_EMPHASIS_STYLE.equalsIgnoreCase(property)
                    || EPUB_TEXT_EMPHASIS_COLOR.equalsIgnoreCase(property))) return;
            String value = cleanValue(css.substring(valueStart, end));
            String aliases = value.isEmpty() ? "" : aliasDeclarations(property, value);
            if (aliases.isEmpty()) return;
            boolean semicolon = end < css.length() && css.charAt(end) == ';';
            int insertion = semicolon ? end + 1 : end;
            if (semicolon && css.startsWith(aliases, insertion)) return; // repeated preparation
            out.append(css, copied[0], insertion);
            if (!semicolon) out.append(';');
            out.append(aliases);
            copied[0] = insertion;
        });
        if (copied[0] == 0) return css;
        return out.append(css, copied[0], css.length()).toString();
    }

    /** Conservative hint with same-block order/importance, not selector matching. */
    static boolean detectsVerticalWriting(String css) {
        if (css == null || css.isEmpty()) return false;
        return scanDeclarations(css, (property, valueStart, end, scope) -> {
            if (!scope.screen || !("writing-mode".equalsIgnoreCase(property)
                    || "-webkit-writing-mode".equalsIgnoreCase(property)
                    || EPUB_WRITING_MODE.equalsIgnoreCase(property))) return;
            String value = cleanValue(css.substring(valueStart, end));
            Matcher important = IMPORTANT.matcher(value);
            boolean priority = important.find();
            if (priority) value = value.substring(0, important.start()).trim();
            boolean vertical = "vertical-rl".equalsIgnoreCase(value) || "vertical-lr".equalsIgnoreCase(value);
            if (!vertical && !"horizontal-tb".equalsIgnoreCase(value) && !"initial".equalsIgnoreCase(value)) return;
            if (!scope.important || priority) {
                scope.vertical = vertical;
                scope.important = priority;
            }
        });
    }

    private interface DeclarationVisitor {
        void accept(String property, int valueStart, int end, CssScope scope);
    }

    private static final class CssScope {
        final boolean screen;
        boolean vertical, important;
        CssScope(boolean screen) { this.screen = screen; }
    }

    /** One lexical walk shared by compatibility rewriting and writing-mode hints. */
    private static boolean scanDeclarations(String css, DeclarationVisitor visitor) {
        ArrayDeque<CssScope> stack = new ArrayDeque<>();
        CssScope root = new CssScope(true);
        stack.push(root);
        boolean vertical = false;
        boolean expectProperty = true;
        int statementStart = 0, parens = 0, brackets = 0;
        for (int i = 0; i < css.length();) {
            if (startsComment(css, i)) { i = commentEnd(css, i + 2); continue; }
            int wrapper = wrapperLength(css, i);
            if (wrapper > 0) { i += wrapper; statementStart = i; continue; }
            char ch = css.charAt(i);
            if (ch == '\'' || ch == '"') { i = quotedEnd(css, i, ch); continue; }
            if (ch == '\\' && i + 1 < css.length()) { i += 2; expectProperty = false; continue; }
            if (ch == '(') parens++;
            else if (ch == ')') parens = Math.max(0, parens - 1);
            else if (ch == '[') brackets++;
            else if (ch == ']') brackets = Math.max(0, brackets - 1);
            if (parens > 0 || brackets > 0) { i++; continue; }
            if (ch == '{') {
                String prelude = stripComments(css.substring(statementStart, i)).trim();
                boolean screen = stack.peek().screen;
                if (atKeyword(prelude, 0, "@media")) {
                    screen &= com.readwide.manager.util.EpubBindingRewriter.mayApplyToScreen(prelude.substring(6));
                } else if (atKeyword(prelude, 0, "@keyframes") || atKeyword(prelude, 0, "@-webkit-keyframes")
                        || atKeyword(prelude, 0, "@font-face") || atKeyword(prelude, 0, "@page")) screen = false;
                CssScope scope = new CssScope(screen);
                stack.push(scope);
                expectProperty = true; statementStart = ++i; continue;
            }
            if (ch == '}') {
                if (stack.size() > 1) {
                    CssScope finished = stack.pop();
                    vertical |= finished.screen && finished.vertical;
                }
                expectProperty = true; statementStart = ++i; continue;
            }
            if (ch == ';') { expectProperty = true; statementStart = ++i; continue; }
            if (Character.isWhitespace(ch)) { i++; continue; }
            if (expectProperty) {
                int nameEnd = i;
                while (nameEnd < css.length() && (Character.isLetterOrDigit(css.charAt(nameEnd))
                        || css.charAt(nameEnd) == '-' || css.charAt(nameEnd) == '_')) nameEnd++;
                int colon = skipCssSpace(css, nameEnd);
                if (nameEnd > i && colon < css.length() && css.charAt(colon) == ':') {
                    String property = css.substring(i, nameEnd);
                    int end = declarationEnd(css, colon + 1, property.startsWith("--"));
                    if (end == css.length() || css.charAt(end) != '{') {
                        visitor.accept(property, colon + 1, end, stack.peek());
                        i = end;
                        if (i < css.length() && css.charAt(i) == ';') i++;
                        statementStart = i; expectProperty = true; continue;
                    }
                }
            }
            expectProperty = false;
            i++;
        }
        for (CssScope scope : stack) vertical |= scope.screen && scope.vertical;
        return vertical;
    }

    // XHTML style CDATA and legacy HTML style wrappers are markup, not CSS brackets.
    private static int wrapperLength(String text, int offset) {
        if (text.startsWith("<![CDATA[", offset)) return 9;
        if (text.startsWith("]]>", offset)) return 3;
        if (text.startsWith("<!--", offset)) return 4;
        if (text.startsWith("-->", offset)) return 3;
        return 0;
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
        Matcher important = IMPORTANT.matcher(trimmed);
        boolean hasPriority = important.find();
        String suffix = hasPriority ? trimmed.substring(important.start()).trim() : "";
        String core = hasPriority ? trimmed.substring(0, important.start()).trim() : trimmed;
        if ("horizontal".equalsIgnoreCase(core)) {
            return suffix.isEmpty() ? "all" : "all " + suffix;
        }
        return trimmed;
    }

    private static int declarationEnd(String css, int offset, boolean customProperty) {
        int parens = 0, brackets = 0, braces = 0;
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
            if (ch == '\\' && i + 1 < css.length()) { i += 2; continue; }
            if (ch == '(') parens++;
            else if (ch == ')' && parens > 0) parens--;
            else if (ch == '[') brackets++;
            else if (ch == ']' && brackets > 0) brackets--;
            else if (parens == 0 && brackets == 0) {
                if (ch == '{') {
                    if (!customProperty && braces == 0) return i;
                    braces++;
                } else if (ch == '}') {
                    if (braces == 0) return i;
                    braces--;
                } else if (ch == ';' && braces == 0) return i;
            }
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
            char ch = value.charAt(i);
            if (ch == '\'' || ch == '"') {
                int end = quotedEnd(value, i, ch);
                out.append(value, i, end); i = end; continue;
            }
            if (startsComment(value, i)) {
                i = commentEnd(value, i + 2);
                out.append(' ');
            } else {
                out.append(value.charAt(i++));
            }
        }
        return out.toString();
    }

}
