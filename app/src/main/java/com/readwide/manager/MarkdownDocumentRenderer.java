package com.readwide.manager;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight Markdown-to-HTML renderer used only by the dedicated Markdown
 * document viewer.  It intentionally does not touch the TXT exact page model.
 *
 * The renderer is deliberately conservative: it supports the common document
 * features Readwide users are likely to expect (headings, emphasis, links,
 * lists, quotes, code blocks, tables, and diagram/code placeholders) without
 * enabling WebView JavaScript or external network loading.
 */
final class MarkdownDocumentRenderer {
    private static final Pattern LINK_PATTERN = Pattern.compile("(!)?\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern STRONG_PATTERN = Pattern.compile("(\\*\\*|__)(.+?)\\1");
    private static final Pattern STRIKE_PATTERN = Pattern.compile("~~(.+?)~~");
    private static final Pattern EMPHASIS_STAR_PATTERN = Pattern.compile("(?<!\\*)\\*([^*\\s][^*]*?[^*\\s]|[^*\\s])\\*(?!\\*)");
    private static final Pattern EMPHASIS_UNDERSCORE_PATTERN = Pattern.compile("(?<![A-Za-z0-9_])_([^_\\s][^_]*?[^_\\s]|[^_\\s])_(?![A-Za-z0-9_])");

    private MarkdownDocumentRenderer() {}

    @NonNull
    static String render(@NonNull String markdown, @NonNull String title) {
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = splitLines(normalized);
        int[] lineOffsets = lineStartOffsets(lines);
        StringBuilder body = new StringBuilder(Math.max(1024, normalized.length() + 512));
        body.append("<main class=\"markdown-doc\">\n");

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String trimmed = line.trim();
            int sourceOffset = offsetForLine(lineOffsets, i);

            if (trimmed.isEmpty()) {
                i++;
                continue;
            }

            if (isFenceStart(trimmed)) {
                FenceBlock block = readFence(lines, lineOffsets, i);
                appendFenceBlock(body, block);
                i = block.nextIndex;
                continue;
            }

            if (isTableStart(lines, i)) {
                int next = appendTable(body, lines, lineOffsets, i);
                i = next;
                continue;
            }

            if (isHorizontalRule(trimmed)) {
                body.append("<hr").append(sourceAttrs(i, sourceOffset)).append(">\n");
                i++;
                continue;
            }

            int headingLevel = headingLevel(line);
            if (headingLevel > 0) {
                String text = line.trim().substring(headingLevel).trim();
                body.append("<h").append(headingLevel).append(sourceAttrs(i, sourceOffset)).append('>')
                        .append(renderInline(text))
                        .append("</h").append(headingLevel).append(">\n");
                i++;
                continue;
            }

            if (trimmed.startsWith(">")) {
                int blockLine = i;
                int blockOffset = sourceOffset;
                StringBuilder quote = new StringBuilder();
                while (i < lines.size()) {
                    String q = lines.get(i).trim();
                    if (!q.startsWith(">")) break;
                    if (quote.length() > 0) quote.append('\n');
                    quote.append(stripQuote(q));
                    i++;
                }
                body.append("<blockquote").append(sourceAttrs(blockLine, blockOffset)).append("><p>")
                        .append(renderInlineWithBreaks(quote.toString()))
                        .append("</p></blockquote>\n");
                continue;
            }

            if (isUnorderedList(trimmed) || isOrderedList(trimmed)) {
                boolean ordered = isOrderedList(trimmed);
                int blockLine = i;
                int blockOffset = sourceOffset;
                body.append(ordered ? "<ol" : "<ul").append(sourceAttrs(blockLine, blockOffset)).append(">\n");
                while (i < lines.size()) {
                    String item = lines.get(i).trim();
                    if (ordered != isOrderedList(item) || (!ordered && !isUnorderedList(item))) break;
                    body.append("<li").append(sourceAttrs(i, offsetForLine(lineOffsets, i))).append(">")
                            .append(renderInline(stripListMarker(item, ordered)))
                            .append("</li>\n");
                    i++;
                }
                body.append(ordered ? "</ol>\n" : "</ul>\n");
                continue;
            }

            int blockLine = i;
            int blockOffset = sourceOffset;
            StringBuilder paragraph = new StringBuilder(line.trim());
            i++;
            while (i < lines.size()) {
                String next = lines.get(i);
                String nextTrim = next.trim();
                if (nextTrim.isEmpty()
                        || isFenceStart(nextTrim)
                        || headingLevel(next) > 0
                        || isHorizontalRule(nextTrim)
                        || isTableStart(lines, i)
                        || nextTrim.startsWith(">")
                        || isUnorderedList(nextTrim)
                        || isOrderedList(nextTrim)) {
                    break;
                }
                paragraph.append('\n').append(next.trim());
                i++;
            }
            body.append("<p").append(sourceAttrs(blockLine, blockOffset)).append(">")
                    .append(renderInlineWithBreaks(paragraph.toString()))
                    .append("</p>\n");
        }

        body.append("</main>");
        return wrapDocument(title, body.toString());
    }

    @NonNull
    private static String wrapDocument(@NonNull String title, @NonNull String body) {
        return "<!doctype html><html><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                + "<title>" + escapeHtml(title) + "</title>"
                + "<style id=\"readwide-markdown-base\">"
                + "html{font-size:16px;}"
                + "body{margin:0;padding:22px 18px 34px 18px;line-height:1.62;font-family:system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;}"
                + ".markdown-doc{max-width:920px;margin:0 auto;}"
                + "h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.15em 0 .48em 0;font-weight:750;}"
                + "h1{font-size:1.78em;border-bottom:1px solid currentColor;padding-bottom:.28em;}"
                + "h2{font-size:1.46em;border-bottom:1px solid currentColor;padding-bottom:.22em;}"
                + "h3{font-size:1.22em;}h4,h5,h6{font-size:1.05em;}"
                + "p{margin:.78em 0;}"
                + "blockquote{margin:.9em 0;padding:.08em 0 .08em 1em;border-left:4px solid currentColor;opacity:.92;}"
                + "pre,code{font-family:'Roboto Mono','Droid Sans Mono','SFMono-Regular',monospace;}"
                + "code{font-size:.92em;border-radius:.32em;padding:.08em .32em;}"
                + "pre{font-size:.92em;border-radius:.55em;padding:.9em;overflow:auto;white-space:pre;line-height:1.45;}"
                + "pre code{padding:0;background:transparent!important;border-radius:0;}"
                + "table{width:100%;border-collapse:collapse;margin:1em 0;display:block;overflow-x:auto;}"
                + "thead{font-weight:700;}th,td{border:1px solid currentColor;padding:.45em .65em;vertical-align:top;}"
                + "ul,ol{padding-left:1.35em;margin:.72em 0;}li{margin:.18em 0;}"
                + "hr{border:0;border-top:1px solid currentColor;margin:1.4em 0;opacity:.55;}"
                + "a{text-decoration:underline;text-underline-offset:.14em;}"
                + "img{display:block;max-width:100%;height:auto;margin:1em auto;border-radius:.35em;}"
                + ".md-diagram{border:1px solid currentColor;border-radius:.7em;padding:.85em;margin:1em 0;}"
                + ".md-diagram-title{font-weight:700;margin-bottom:.55em;}"
                + ".md-diagram-note{font-size:.9em;opacity:.78;margin-bottom:.65em;}"
                + "</style></head><body>" + body + "</body></html>";
    }

    private static List<String> splitLines(@NonNull String text) {
        String[] raw = text.split("\n", -1);
        List<String> out = new ArrayList<>(raw.length);
        for (String s : raw) out.add(s);
        return out;
    }

    private static int[] lineStartOffsets(@NonNull List<String> lines) {
        int[] offsets = new int[lines.size()];
        int pos = 0;
        for (int i = 0; i < lines.size(); i++) {
            offsets[i] = pos;
            pos += lines.get(i).length();
            if (i < lines.size() - 1) pos += 1;
        }
        return offsets;
    }

    private static int offsetForLine(@NonNull int[] offsets, int index) {
        if (offsets.length == 0) return 0;
        if (index < 0) return offsets[0];
        if (index >= offsets.length) return offsets[offsets.length - 1];
        return Math.max(0, offsets[index]);
    }

    private static String sourceAttrs(int lineIndex, int sourceOffset) {
        int safeOffset = Math.max(0, sourceOffset);
        return " id=\"rw-md-src-" + safeOffset + "\" data-rw-src-line=\"" + Math.max(1, lineIndex + 1)
                + "\" data-rw-src-offset=\"" + safeOffset + "\"";
    }

    private static boolean isFenceStart(@NonNull String trimmed) {
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    private static FenceBlock readFence(@NonNull List<String> lines, @NonNull int[] lineOffsets, int start) {
        String first = lines.get(start).trim();
        String fence = first.startsWith("~~~") ? "~~~" : "```";
        String lang = first.length() > 3 ? first.substring(3).trim().toLowerCase(Locale.ROOT) : "";
        StringBuilder code = new StringBuilder();
        int i = start + 1;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.trim().startsWith(fence)) {
                i++;
                break;
            }
            if (code.length() > 0) code.append('\n');
            code.append(line);
            i++;
        }
        return new FenceBlock(lang, code.toString(), i, start, offsetForLine(lineOffsets, start));
    }

    private static void appendFenceBlock(@NonNull StringBuilder body, @NonNull FenceBlock block) {
        String lang = block.language;
        if ("mermaid".equals(lang) || "chart".equals(lang) || "chart-json".equals(lang)) {
            body.append("<figure class=\"md-diagram\"><div class=\"md-diagram-title\">")
                    .append("mermaid".equals(lang) ? "Mermaid diagram" : "Chart block")
                    .append("</div><div class=\"md-diagram-note\">")
                    .append("Diagram rendering is prepared as a Markdown document block. Full offline rendering will be added separately.")
                    .append("</div><pre><code>")
                    .append(escapeHtml(block.code))
                    .append("</code></pre></figure>\n");
            return;
        }
        body.append("<pre><code>").append(escapeHtml(block.code)).append("</code></pre>\n");
    }

    private static int headingLevel(@NonNull String line) {
        String s = line.trim();
        int n = 0;
        while (n < s.length() && n < 6 && s.charAt(n) == '#') n++;
        if (n == 0) return 0;
        if (n < s.length() && Character.isWhitespace(s.charAt(n))) return n;
        return 0;
    }

    private static boolean isHorizontalRule(@NonNull String trimmed) {
        if (trimmed.length() < 3) return false;
        String compact = trimmed.replace(" ", "").replace("\t", "");
        return compact.matches("[-*_]{3,}");
    }

    private static boolean isUnorderedList(@NonNull String trimmed) {
        return trimmed.matches("[-+*]\\s+.+");
    }

    private static boolean isOrderedList(@NonNull String trimmed) {
        return trimmed.matches("\\d+[.)]\\s+.+");
    }

    private static String stripListMarker(@NonNull String line, boolean ordered) {
        return ordered
                ? line.replaceFirst("^\\d+[.)]\\s+", "")
                : line.replaceFirst("^[-+*]\\s+", "");
    }

    private static String stripQuote(@NonNull String line) {
        String out = line.replaceFirst("^>\\s?", "");
        return out.trim();
    }

    private static boolean isTableStart(@NonNull List<String> lines, int index) {
        if (index + 1 >= lines.size()) return false;
        String header = lines.get(index).trim();
        String sep = lines.get(index + 1).trim();
        return header.contains("|") && isTableSeparator(sep);
    }

    private static boolean isTableSeparator(@NonNull String line) {
        if (!line.contains("|") || !line.contains("-")) return false;
        String[] cells = splitTableRow(line);
        if (cells.length == 0) return false;
        for (String c : cells) {
            String s = c.trim();
            if (s.isEmpty()) continue;
            if (!s.matches(":?-{3,}:?")) return false;
        }
        return true;
    }

    private static int appendTable(@NonNull StringBuilder body, @NonNull List<String> lines, @NonNull int[] lineOffsets, int index) {
        String[] header = splitTableRow(lines.get(index));
        int i = index + 2;
        body.append("<table").append(sourceAttrs(index, offsetForLine(lineOffsets, index))).append("><thead><tr>");
        for (String cell : header) body.append("<th>").append(renderInline(cell.trim())).append("</th>");
        body.append("</tr></thead><tbody>\n");
        while (i < lines.size()) {
            String line = lines.get(i).trim();
            if (!line.contains("|") || line.isEmpty()) break;
            String[] row = splitTableRow(line);
            body.append("<tr").append(sourceAttrs(i, offsetForLine(lineOffsets, i))).append(">");
            for (int c = 0; c < Math.max(header.length, row.length); c++) {
                String value = c < row.length ? row[c].trim() : "";
                body.append("<td>").append(renderInline(value)).append("</td>");
            }
            body.append("</tr>\n");
            i++;
        }
        body.append("</tbody></table>\n");
        return i;
    }

    private static String[] splitTableRow(@NonNull String line) {
        String s = line.trim();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        return s.split("\\|", -1);
    }

    private static String renderInlineWithBreaks(@NonNull String raw) {
        String[] parts = raw.split("\\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append("<br>");
            out.append(renderInline(parts[i]));
        }
        return out.toString();
    }

    private static String renderInline(@NonNull String raw) {
        String text = escapeHtml(raw);
        text = replaceCode(text);
        text = replaceLinksAndImages(text);
        text = STRIKE_PATTERN.matcher(text).replaceAll("<del>$1</del>");
        text = STRONG_PATTERN.matcher(text).replaceAll("<strong>$2</strong>");
        text = EMPHASIS_STAR_PATTERN.matcher(text).replaceAll("<em>$1</em>");
        text = EMPHASIS_UNDERSCORE_PATTERN.matcher(text).replaceAll("<em>$1</em>");
        return text;
    }

    private static String replaceCode(@NonNull String text) {
        Matcher m = CODE_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("<code>" + m.group(1) + "</code>"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceLinksAndImages(@NonNull String text) {
        Matcher m = LINK_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            boolean image = m.group(1) != null && !m.group(1).isEmpty();
            String label = m.group(2) != null ? m.group(2) : "";
            String url = safeUrl(m.group(3));
            String replacement;
            if (image) {
                replacement = url.isEmpty()
                        ? "<span class=\"md-image-alt\">" + label + "</span>"
                        : "<img src=\"" + escapeAttribute(url) + "\" alt=\"" + escapeAttribute(label) + "\">";
            } else {
                replacement = url.isEmpty()
                        ? label
                        : "<a href=\"" + escapeAttribute(url) + "\" rel=\"noreferrer\">" + label + "</a>";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String safeUrl(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        String lower = u.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
            return u;
        }
        // Keep relative image/link paths out of the first WebView implementation.
        // They need a dedicated local-resource resolver rooted at the source file's parent.
        return "";
    }

    private static String escapeHtml(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                default: out.append(ch);
            }
        }
        return out.toString();
    }

    private static String escapeAttribute(String text) {
        return escapeHtml(text).replace("'", "&#39;");
    }

    private static final class FenceBlock {
        final String language;
        final String code;
        final int nextIndex;
        final int startLine;
        final int sourceOffset;

        FenceBlock(String language, String code, int nextIndex, int startLine, int sourceOffset) {
            this.language = language != null ? language : "";
            this.code = code != null ? code : "";
            this.nextIndex = nextIndex;
            this.startLine = startLine;
            this.sourceOffset = Math.max(0, sourceOffset);
        }
    }
}
