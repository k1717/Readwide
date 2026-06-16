package com.readwide.manager.view;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight Markdown presentation for the TXT reader.
 *
 * The source string is never deleted or rewritten. Rendered Markdown marker
 * characters are hidden only when they are confidently syntax, so search/bookmark
 * offsets remain anchored to the same original text while ordinary underscores or
 * filename-style words stay visible.
 */
public final class MarkdownSyntaxHighlighter {
    private static final Pattern BOLD_PATTERN = Pattern.compile("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern ITALIC_STAR_PATTERN = Pattern.compile("(?<!\\*)\\*(?!\\*)(?=\\S)(.+?)(?<=\\S)(?<!\\*)\\*(?!\\*)");
    private static final Pattern ITALIC_UNDERSCORE_PATTERN = Pattern.compile("(?<!_)_(?!_)(?=\\S)(.+?)(?<=\\S)(?<!_)_(?!_)");
    private static final Pattern STRIKE_PATTERN = Pattern.compile("~~(?=\\S)(.+?)(?<=\\S)~~");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`\\n]+?)`");
    private static final Pattern LINK_PATTERN = Pattern.compile("(!?)\\[([^\\]\\n]+)\\]\\(([^\\)\\n]+)\\)");

    private MarkdownSyntaxHighlighter() {}

    public static boolean isMarkdownFile(@Nullable String pathOrName) {
        if (pathOrName == null) return false;
        String lower = pathOrName.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    @NonNull
    public static CharSequence apply(@Nullable String source, int textColor, int backgroundColor) {
        String value = source != null ? source : "";
        if (value.isEmpty()) return "";

        SpannableString out = new SpannableString(value);
        Palette palette = Palette.from(textColor, backgroundColor);

        applyLineSpans(out, value, palette);
        applyInlineSpans(out, value, palette);
        return out;
    }

    private static void applyLineSpans(@NonNull SpannableString out,
                                       @NonNull String value,
                                       @NonNull Palette palette) {
        boolean inFence = false;
        int length = value.length();
        int lineStart = 0;
        while (lineStart <= length) {
            int lineEnd = value.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = length;
            int contentEnd = lineEnd;
            if (contentEnd > lineStart && value.charAt(contentEnd - 1) == '\r') contentEnd--;

            String line = value.substring(lineStart, contentEnd);
            int firstNonSpace = firstNonSpace(line);
            String trimmed = firstNonSpace >= 0 ? line.substring(firstNonSpace) : "";
            boolean fenceLine = trimmed.startsWith("```") || trimmed.startsWith("~~~");

            if (fenceLine) {
                int markerStart = lineStart + Math.max(0, firstNonSpace);
                addHiddenSpan(out, markerStart, contentEnd);
            } else if (inFence) {
                addSpan(out, new BackgroundColorSpan(palette.codeBackground), lineStart, contentEnd);
                addSpan(out, new ForegroundColorSpan(palette.codeForeground), lineStart, contentEnd);
                addSpan(out, new TypefaceSpan("monospace"), lineStart, contentEnd);
            } else {
                int tableNextStart = tryApplyTableBlock(out, value, lineStart, contentEnd, lineEnd, palette);
                if (tableNextStart >= 0) {
                    if (tableNextStart > length) break;
                    lineStart = tableNextStart;
                    continue;
                }

                if (trimmed.startsWith("#")) {
                    int hashes = countPrefix(trimmed, '#');
                    if (hashes >= 1 && hashes <= 6 && trimmed.length() > hashes && Character.isWhitespace(trimmed.charAt(hashes))) {
                        int markerStart = lineStart + Math.max(0, firstNonSpace);
                        int contentStart = markerStart + hashes;
                        while (contentStart < contentEnd && Character.isWhitespace(value.charAt(contentStart))) contentStart++;
                        addHiddenSpan(out, markerStart, markerStart + hashes);
                        addSpan(out, new ForegroundColorSpan(palette.headingForeground), contentStart, contentEnd);
                        addSpan(out, new StyleSpan(Typeface.BOLD), contentStart, contentEnd);
                        addSpan(out, new RelativeSizeSpan(relativeHeadingSize(hashes)), contentStart, contentEnd);
                    }
                } else if (trimmed.startsWith(">")) {
                    int markerStart = lineStart + Math.max(0, firstNonSpace);
                    int contentStart = markerStart + 1;
                    while (contentStart < contentEnd && Character.isWhitespace(value.charAt(contentStart))) contentStart++;
                    addHiddenSpan(out, markerStart, markerStart + 1);
                    addSpan(out, new ForegroundColorSpan(palette.quoteForeground), contentStart, contentEnd);
                    addSpan(out, new StyleSpan(Typeface.ITALIC), contentStart, contentEnd);
                } else if (isListLine(trimmed)) {
                    int markerStart = lineStart + Math.max(0, firstNonSpace);
                    int markerEnd = markerStart + listMarkerLength(trimmed);
                    int contentStart = markerEnd;
                    while (contentStart < contentEnd && Character.isWhitespace(value.charAt(contentStart))) contentStart++;
                    if (isUnorderedListLine(trimmed)) {
                        addHiddenSpan(out, markerStart, Math.min(markerEnd, contentEnd));
                        addSpan(out, new BulletSpan(), markerStart, contentEnd);
                    } else {
                        addSpan(out, new ForegroundColorSpan(palette.markerForeground), markerStart, Math.min(markerEnd, contentEnd));
                        addSpan(out, new StyleSpan(Typeface.BOLD), markerStart, Math.min(markerEnd, contentEnd));
                    }
                } else if (isHorizontalRule(trimmed)) {
                    int styleStart = lineStart + Math.max(0, firstNonSpace);
                    addHiddenSpan(out, styleStart, contentEnd);
                }
            }

            if (fenceLine) {
                inFence = !inFence;
            }
            if (lineEnd >= length) break;
            lineStart = lineEnd + 1;
        }
    }

    private static void applyInlineSpans(@NonNull SpannableString out,
                                         @NonNull String value,
                                         @NonNull Palette palette) {
        applyInlineCodeSpans(out, value, palette);
        applyWrappedInlineSpan(out, value, BOLD_PATTERN, 1, 2,
                new SpanFactory() {
                    @Override public Object[] create() {
                        return new Object[] { new StyleSpan(Typeface.BOLD) };
                    }
                });
        applySimpleWrappedInlineSpan(out, value, ITALIC_STAR_PATTERN, 1, 1,
                new SpanFactory() {
                    @Override public Object[] create() {
                        return new Object[] { new StyleSpan(Typeface.ITALIC) };
                    }
                });
        applySimpleWrappedInlineSpan(out, value, ITALIC_UNDERSCORE_PATTERN, 1, 1,
                new SpanFactory() {
                    @Override public Object[] create() {
                        return new Object[] { new StyleSpan(Typeface.ITALIC) };
                    }
                });
        applySimpleWrappedInlineSpan(out, value, STRIKE_PATTERN, 1, 2,
                new SpanFactory() {
                    @Override public Object[] create() {
                        return new Object[] { new StrikethroughSpan() };
                    }
                });
        applyLinkSpans(out, value, palette);
    }

    private static void applyInlineCodeSpans(@NonNull SpannableString out,
                                             @NonNull String value,
                                             @NonNull Palette palette) {
        Matcher matcher = INLINE_CODE_PATTERN.matcher(value);
        while (matcher.find()) {
            int contentStart = matcher.start(1);
            int contentEnd = matcher.end(1);
            addHiddenSpan(out, matcher.start(), contentStart);
            addHiddenSpan(out, contentEnd, matcher.end());
            addSpan(out, new BackgroundColorSpan(palette.codeBackground), contentStart, contentEnd);
            addSpan(out, new ForegroundColorSpan(palette.codeForeground), contentStart, contentEnd);
            addSpan(out, new TypefaceSpan("monospace"), contentStart, contentEnd);
        }
    }

    private static void applyLinkSpans(@NonNull SpannableString out,
                                       @NonNull String value,
                                       @NonNull Palette palette) {
        Matcher matcher = LINK_PATTERN.matcher(value);
        while (matcher.find()) {
            int labelStart = matcher.start(2);
            int labelEnd = matcher.end(2);
            addHiddenSpan(out, matcher.start(), labelStart);
            addHiddenSpan(out, labelEnd, matcher.end());
            addSpan(out, new ForegroundColorSpan(palette.linkForeground), labelStart, labelEnd);
            addSpan(out, new UnderlineSpan(), labelStart, labelEnd);
        }
    }

    private static void applyWrappedInlineSpan(@NonNull SpannableString out,
                                               @NonNull String value,
                                               @NonNull Pattern pattern,
                                               int markerGroup,
                                               int contentGroup,
                                               @NonNull SpanFactory factory) {
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            int contentStart = matcher.start(contentGroup);
            int contentEnd = matcher.end(contentGroup);
            if (contentStart < 0 || contentEnd <= contentStart) continue;
            int markerLength = matcher.group(markerGroup).length();
            String marker = matcher.group(markerGroup);
            if (isUnsafeIntraWordMarker(value, matcher.start(), matcher.end() - markerLength, markerLength, marker)) {
                continue;
            }
            addHiddenSpan(out, matcher.start(), matcher.start() + markerLength);
            addHiddenSpan(out, matcher.end() - markerLength, matcher.end());
            applyFactorySpans(out, factory, contentStart, contentEnd);
        }
    }

    private static void applySimpleWrappedInlineSpan(@NonNull SpannableString out,
                                                     @NonNull String value,
                                                     @NonNull Pattern pattern,
                                                     int contentGroup,
                                                     int markerLength,
                                                     @NonNull SpanFactory factory) {
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            int contentStart = matcher.start(contentGroup);
            int contentEnd = matcher.end(contentGroup);
            if (contentStart < 0 || contentEnd <= contentStart) continue;
            String marker = value.substring(matcher.start(), Math.min(value.length(), matcher.start() + markerLength));
            if (isUnsafeIntraWordMarker(value, matcher.start(), matcher.end() - markerLength, markerLength, marker)) {
                continue;
            }
            addHiddenSpan(out, matcher.start(), matcher.start() + markerLength);
            addHiddenSpan(out, matcher.end() - markerLength, matcher.end());
            applyFactorySpans(out, factory, contentStart, contentEnd);
        }
    }

    private static void applyFactorySpans(@NonNull SpannableString out,
                                          @NonNull SpanFactory factory,
                                          int start,
                                          int end) {
        Object[] spans = factory.create();
        for (Object span : spans) {
            addSpan(out, span, start, end);
        }
    }

    private interface SpanFactory {
        Object[] create();
    }


    private static boolean isUnsafeIntraWordMarker(@NonNull String value,
                                                   int openStart,
                                                   int closeStart,
                                                   int markerLength,
                                                   @Nullable String marker) {
        if (marker == null || marker.indexOf('_') < 0) return false;
        if (isBetweenWordCharacters(value, openStart, markerLength)
                || isBetweenWordCharacters(value, closeStart, markerLength)) {
            return true;
        }
        // A closing underscore immediately followed by another word character is usually
        // part of a filename, identifier, or snake_case token rather than Markdown.
        int closeEnd = closeStart + Math.max(1, markerLength);
        return closeStart > 0
                && closeEnd < value.length()
                && isWordCharacter(value.charAt(closeStart - 1))
                && isWordCharacter(value.charAt(closeEnd));
    }

    private static boolean isBetweenWordCharacters(@NonNull String value, int markerStart, int markerLength) {
        int markerEnd = markerStart + Math.max(1, markerLength);
        return markerStart > 0
                && markerEnd < value.length()
                && isWordCharacter(value.charAt(markerStart - 1))
                && isWordCharacter(value.charAt(markerEnd));
    }

    private static boolean isWordCharacter(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }
    private static void addSpan(@NonNull SpannableString out, @NonNull Object span, int start, int end) {
        int safeStart = Math.max(0, Math.min(out.length(), start));
        int safeEnd = Math.max(safeStart, Math.min(out.length(), end));
        if (safeEnd <= safeStart) return;
        out.setSpan(span, safeStart, safeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void addHiddenSpan(@NonNull SpannableString out, int start, int end) {
        addSpan(out, new HiddenMarkdownSyntaxSpan(), start, end);
    }

    private static int firstNonSpace(@NonNull String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') return i;
        }
        return -1;
    }

    private static int countPrefix(@NonNull String value, char c) {
        int count = 0;
        while (count < value.length() && value.charAt(count) == c) count++;
        return count;
    }

    private static float relativeHeadingSize(int hashes) {
        switch (hashes) {
            case 1: return 1.30f;
            case 2: return 1.20f;
            case 3: return 1.12f;
            default: return 1.06f;
        }
    }

    private static boolean isListLine(@NonNull String trimmed) {
        return isUnorderedListLine(trimmed) || isOrderedListLine(trimmed);
    }

    private static boolean isUnorderedListLine(@NonNull String trimmed) {
        if (trimmed.length() < 2) return false;
        char first = trimmed.charAt(0);
        return (first == '-' || first == '*' || first == '+') && Character.isWhitespace(trimmed.charAt(1));
    }

    private static boolean isOrderedListLine(@NonNull String trimmed) {
        int i = 0;
        while (i < trimmed.length() && Character.isDigit(trimmed.charAt(i))) i++;
        return i > 0 && i + 1 < trimmed.length() && trimmed.charAt(i) == '.' && Character.isWhitespace(trimmed.charAt(i + 1));
    }

    private static int listMarkerLength(@NonNull String trimmed) {
        if (isUnorderedListLine(trimmed)) return 1;
        int i = 0;
        while (i < trimmed.length() && Character.isDigit(trimmed.charAt(i))) i++;
        if (i > 0 && i < trimmed.length() && trimmed.charAt(i) == '.') return i + 1;
        return 0;
    }

    private static boolean isHorizontalRule(@NonNull String trimmed) {
        if (trimmed.length() < 3) return false;
        char marker = 0;
        int count = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == ' ' || c == '\t') continue;
            if (marker == 0) {
                if (c != '-' && c != '*' && c != '_') return false;
                marker = c;
            }
            if (c != marker) return false;
            count++;
        }
        return count >= 3;
    }

    private static int tryApplyTableBlock(@NonNull SpannableString out,
                                          @NonNull String value,
                                          int headerStart,
                                          int headerContentEnd,
                                          int headerLineEnd,
                                          @NonNull Palette palette) {
        if (headerLineEnd >= value.length()) return -1;
        String header = trimCarriage(value.substring(headerStart, headerContentEnd));
        if (!looksLikeTableRow(header)) return -1;

        int separatorStart = headerLineEnd + 1;
        if (separatorStart > value.length()) return -1;
        int separatorLineEnd = lineEndOf(value, separatorStart);
        int separatorContentEnd = trimLineContentEnd(value, separatorStart, separatorLineEnd);
        String separator = trimCarriage(value.substring(separatorStart, separatorContentEnd));
        if (!isTableSeparatorLine(separator)) return -1;

        int tableEnd = separatorContentEnd;
        int scanStart = separatorLineEnd < value.length() ? separatorLineEnd + 1 : value.length() + 1;
        while (scanStart <= value.length()) {
            int rowLineEnd = lineEndOf(value, scanStart);
            int rowContentEnd = trimLineContentEnd(value, scanStart, rowLineEnd);
            String row = trimCarriage(value.substring(scanStart, rowContentEnd));
            if (!looksLikeTableRow(row)) break;
            tableEnd = rowContentEnd;
            if (rowLineEnd >= value.length()) {
                scanStart = value.length() + 1;
                break;
            }
            scanStart = rowLineEnd + 1;
        }

        addSpan(out, new BackgroundColorSpan(palette.tableBackground), headerStart, tableEnd);
        addSpan(out, new TypefaceSpan("monospace"), headerStart, tableEnd);
        addSpan(out, new RelativeSizeSpan(0.98f), headerStart, tableEnd);
        addSpan(out, new StyleSpan(Typeface.BOLD), headerStart, headerContentEnd);
        addHiddenSpan(out, separatorStart, separatorContentEnd);
        addPipeSpans(out, value, headerStart, tableEnd, palette);
        return scanStart;
    }

    private static void addPipeSpans(@NonNull SpannableString out,
                                     @NonNull String value,
                                     int start,
                                     int end,
                                     @NonNull Palette palette) {
        int safeEnd = Math.min(value.length(), end);
        for (int i = Math.max(0, start); i < safeEnd; i++) {
            if (value.charAt(i) == '|') {
                addSpan(out, new ForegroundColorSpan(palette.tableBorderForeground), i, i + 1);
                addSpan(out, new StyleSpan(Typeface.BOLD), i, i + 1);
            }
        }
    }

    private static int lineEndOf(@NonNull String value, int lineStart) {
        int lineEnd = value.indexOf('\n', Math.max(0, lineStart));
        return lineEnd < 0 ? value.length() : lineEnd;
    }

    private static int trimLineContentEnd(@NonNull String value, int lineStart, int lineEnd) {
        int contentEnd = Math.max(lineStart, Math.min(value.length(), lineEnd));
        if (contentEnd > lineStart && value.charAt(contentEnd - 1) == '\r') contentEnd--;
        return contentEnd;
    }

    private static String trimCarriage(@NonNull String line) {
        if (line.endsWith("\r")) return line.substring(0, line.length() - 1);
        return line;
    }

    private static boolean looksLikeTableRow(@NonNull String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.indexOf('|') < 0) return false;
        return splitTableCells(trimmed).length >= 2;
    }

    private static boolean isTableSeparatorLine(@NonNull String line) {
        String trimmed = line.trim();
        if (trimmed.indexOf('|') < 0) return false;
        String[] cells = splitTableCells(trimmed);
        if (cells.length < 2) return false;
        for (String cell : cells) {
            String part = cell.trim();
            if (part.length() < 3) return false;
            if (part.charAt(0) == ':') part = part.substring(1).trim();
            if (part.endsWith(":")) part = part.substring(0, part.length() - 1).trim();
            if (part.length() < 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) != '-') return false;
            }
        }
        return true;
    }

    private static String[] splitTableCells(@NonNull String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.split("\\|", -1);
    }

    private static final class HiddenMarkdownSyntaxSpan extends ReplacementSpan {
        @Override
        public int getSize(@NonNull Paint paint,
                           CharSequence text,
                           int start,
                           int end,
                           @Nullable Paint.FontMetricsInt fm) {
            return 0;
        }

        @Override
        public void draw(@NonNull Canvas canvas,
                         CharSequence text,
                         int start,
                         int end,
                         float x,
                         int top,
                         int y,
                         int bottom,
                         @NonNull Paint paint) {
            // Intentionally empty: source characters stay in the text but are not drawn.
        }
    }

    private static final class Palette {
        final int headingForeground;
        final int markerForeground;
        final int quoteForeground;
        final int linkForeground;
        final int codeForeground;
        final int codeBackground;
        final int tableBorderForeground;
        final int tableBackground;

        private Palette(int headingForeground,
                        int markerForeground,
                        int quoteForeground,
                        int linkForeground,
                        int codeForeground,
                        int codeBackground,
                        int tableBorderForeground,
                        int tableBackground) {
            this.headingForeground = headingForeground;
            this.markerForeground = markerForeground;
            this.quoteForeground = quoteForeground;
            this.linkForeground = linkForeground;
            this.codeForeground = codeForeground;
            this.codeBackground = codeBackground;
            this.tableBorderForeground = tableBorderForeground;
            this.tableBackground = tableBackground;
        }

        static Palette from(int text, int bg) {
            boolean lightBg = luminance(bg) >= 0.58;
            int heading = blend(text, bg, lightBg ? 0.88f : 0.96f);
            int marker = blend(text, bg, lightBg ? 0.62f : 0.72f);
            int quote = blend(text, bg, lightBg ? 0.70f : 0.78f);
            int linkBase = lightBg ? Color.rgb(28, 88, 164) : Color.rgb(130, 184, 255);
            int link = blend(linkBase, text, 0.82f);
            int codeFg = blend(text, bg, lightBg ? 0.86f : 0.94f);
            int codeBg = blend(bg, text, lightBg ? 0.90f : 0.84f);
            int tableBorder = blend(text, bg, lightBg ? 0.54f : 0.66f);
            int tableBg = blend(bg, text, lightBg ? 0.96f : 0.91f);
            return new Palette(heading, marker, quote, link, codeFg, codeBg, tableBorder, tableBg);
        }

        private static int blend(int first, int second, float firstRatio) {
            float a = Math.max(0f, Math.min(1f, firstRatio));
            float b = 1f - a;
            return Color.rgb(
                    Math.round(Color.red(first) * a + Color.red(second) * b),
                    Math.round(Color.green(first) * a + Color.green(second) * b),
                    Math.round(Color.blue(first) * a + Color.blue(second) * b));
        }

        private static double luminance(int color) {
            return (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0;
        }
    }
}
