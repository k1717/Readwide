package com.readwide.manager.document.render;

import java.util.Locale;

/** Converts the shared L3 RenderedDocument model to WebView-safe fixed-page HTML. */
public final class FixedHtmlRenderer {
    private FixedHtmlRenderer() {}

    public static String render(RenderedDocument document) {
        RenderedDocumentLimits.validate(document);
        StringBuilder out = new StringBuilder(8192);
        out.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=yes\">");
        out.append("<style id=\"readwide-rendered-document\">");
        out.append("html,body{margin:0;padding:0;width:100%;min-height:100%;background:var(--reader-canvas,#202124);color:var(--paper-fg,#111);}");
        out.append(".rw-rendered-doc{box-sizing:border-box;width:100%;min-height:100vh;padding:0;overflow-x:hidden;overflow-y:auto;}");
        out.append(".rw-rendered-doc,.rw-rendered-doc *{word-break:normal;overflow-wrap:normal;}");
        out.append(".rw-page{box-sizing:border-box;width:100vw;max-width:100vw;min-height:100vh;margin:0 auto;background:var(--paper-bg,#fff);color:var(--paper-fg,#111);box-shadow:none;overflow:hidden;page-break-after:always;}");
        out.append(".rw-page-inner{box-sizing:border-box;min-height:100vh;display:flex;flex-direction:column;}");
        out.append(".rw-page-header,.rw-page-footer{font-size:.9em;color:#333;}");
        out.append(".rw-page-header{flex:0 0 auto;margin-bottom:.7em;}");
        out.append(".rw-page-body{flex:1 1 auto;}");
        out.append(".rw-page-footer{flex:0 0 auto;margin-top:auto;padding-top:.7em;}");
        out.append(".rw-p{margin:0 0 .55em 0;line-height:1.35;white-space:pre-wrap;}");
        out.append(".rw-p,.rw-list-content{overflow-wrap:break-word;}");
        out.append(".rw-list-p{display:flex;gap:.45em;align-items:baseline;break-inside:avoid;}");
        out.append(".rw-list-marker{flex:0 0 auto;min-width:1.6em;text-align:right;}");
        out.append(".rw-list-content{flex:1 1 auto;}");
        out.append(".rw-table{border-collapse:collapse;width:100%;margin:.65em 0;table-layout:fixed;break-inside:avoid;}");
        out.append(".rw-table td{border:1px solid #777;padding:.35em .45em;vertical-align:top;min-width:0;overflow:visible;overflow-wrap:break-word;word-break:normal;}");
        out.append(".rw-table td *{max-width:100%;overflow-wrap:break-word;word-break:normal;}");
        out.append(".rw-table .rw-p{max-width:100%;white-space:normal;overflow-wrap:break-word;word-break:normal;}");
        out.append(".rw-inline-math{white-space:normal;font-family:inherit;}");
        out.append(".rw-display-math{display:block;margin:.45em 0;white-space:normal;overflow-wrap:anywhere;word-break:break-word;font-family:inherit;}");
        out.append(".rw-display-math .frac{display:inline-flex;flex-direction:column;align-items:center;vertical-align:middle;margin:0 .12em;line-height:1.05;}");
        out.append(".rw-display-math .frac .num{border-bottom:1px solid currentColor;padding:0 .12em;}");
        out.append(".rw-display-math .frac .den{padding:0 .12em;}");
        out.append(".rw-inline-math sup,.rw-inline-math sub{font-size:.72em;line-height:0;}");
        out.append(".rw-image{display:block;max-width:100%;height:auto;margin:.6em auto;break-inside:avoid;}");
        out.append(".rw-placeholder{border:1px dashed currentColor;padding:.5em;margin:.6em 0;opacity:.75;}");
        out.append(".rw-floating-downgraded{opacity:.92;}");
        out.append(".rw-note-ref{text-decoration:none;vertical-align:super;font-size:smaller;}");
        out.append(".rw-note-heading{margin-top:1.2em;border-top:1px solid currentColor;padding-top:.7em;font-weight:bold;}");
        out.append(".rw-hr{height:0;border:0;border-top:0.5pt solid currentColor;width:100%;margin:.45em 0 .9em 0;opacity:.85;box-sizing:border-box;flex:0 0 auto;overflow:visible;}");
        out.append("body[data-format=\"hwp-official-letter\"]{--paper-bg:#fff;--paper-fg:#111;}");
        out.append("body[data-format=\"hwp-official-letter\"] .rw-page{font-family:serif;background:#fff;color:#111;}");
        out.append("body[data-format=\"hwp-official-letter\"] .rw-page-inner{padding:clamp(18px,6.2vh,62px) clamp(22px,9.5vw,86px) clamp(14px,4.5vh,44px) clamp(22px,9.5vw,86px)!important;}");
        out.append("body[data-format=\"hwp-official-letter\"] .rw-p{line-height:1.22;margin-left:0;}");
        out.append("body[data-format=\"hwp-official-letter\"] .rw-table{margin:.35em 0 .3em 0;border-collapse:collapse;table-layout:fixed;}");
        out.append("body[data-format=\"hwp-official-letter\"] .rw-table td{border:none!important;padding:.05em 0!important;}");
        out.append("</style></head><body class=\"rw-rendered-doc\" data-format=\"")
                .append(escapeAttribute(document.sourceFormat)).append("\">");
        for (RenderedPage page : document.pages) renderPage(page, out);
        out.append("</body></html>");
        return out.toString();
    }

    private static void renderPage(RenderedPage page, StringBuilder out) {
        out.append("<section class=\"rw-page\" data-page=\"").append(page.pageIndex + 1).append("\" style=\"");
        appendNumberCustomProperty(out, "--rw-page-width-pt", page.widthPt);
        appendNumberCustomProperty(out, "--rw-page-height-pt", page.heightPt);
        out.append("\">");
        out.append("<div class=\"rw-page-inner\" style=\"");
        appendViewportPadding(out, "padding-top", page.marginTopPt, page.heightPt, "vh", 6f, 24f);
        appendViewportPadding(out, "padding-right", page.marginRightPt, page.widthPt, "vw", 6f, 22f);
        appendViewportPadding(out, "padding-bottom", page.marginBottomPt, page.heightPt, "vh", 6f, 24f);
        appendViewportPadding(out, "padding-left", page.marginLeftPt, page.widthPt, "vw", 6f, 22f);
        out.append("\">");
        if (!page.headerBlocks.isEmpty()) {
            out.append("<div class=\"rw-page-header\">");
            for (RenderedBlock block : page.headerBlocks) renderBlock(block, out);
            out.append("</div>");
        }
        out.append("<div class=\"rw-page-body\">");
        for (RenderedBlock block : page.blocks) renderBlock(block, out);
        out.append("</div>");
        if (!page.footerBlocks.isEmpty()) {
            out.append("<div class=\"rw-page-footer\">");
            for (RenderedBlock block : page.footerBlocks) renderBlock(block, out);
            out.append("</div>");
        }
        out.append("</div></section>");
    }

    private static void renderBlock(RenderedBlock block, StringBuilder out) {
        if (block == null) return;
        switch (block.type) {
            case PARAGRAPH:
                renderParagraph(block.paragraph, out);
                break;
            case TABLE:
                renderTable(block.table, out);
                break;
            case IMAGE:
                renderImage(block.image, out);
                break;
            case HORIZONTAL_LINE:
                renderHorizontalLine(block.horizontalLine, out);
                break;
            case UNSUPPORTED_PLACEHOLDER:
                if ("__RW_HWP_OFFICIAL_HR__".equals(block.placeholderText)) {
                    out.append("<div class=\"rw-hr\"></div>");
                } else {
                    out.append("<div class=\"rw-placeholder\">")
                            .append(escapeText(block.placeholderText)).append("</div>");
                }
                break;
        }
    }

    private static void renderParagraph(RenderedParagraph p, StringBuilder out) {
        if (p == null) return;
        boolean listItem = p.style != null && p.style.isListItem();
        out.append("<p class=\"rw-p");
        if (listItem) out.append(" rw-list-p");
        out.append("\" style=\"");
        appendParagraphStyle(p.style, out, !listItem);
        out.append("\">");
        if (listItem) {
            out.append("<span class=\"rw-list-marker\" data-list-level=\"")
                    .append(p.style.listLevel != null ? p.style.listLevel : 0)
                    .append("\">").append(escapeText(p.style.listLabel)).append("</span>");
            out.append("<span class=\"rw-list-content\">");
        }
        if (mathSpansRuns(p.runs)) {
            // LaTeX delimiters can straddle run boundaries (DOCX frequently splits
            // a single $...$ across several runs). Per-run math detection would
            // miss those, leaving raw LaTeX visible, so when a span crosses runs
            // we render the joined run text as one unit. Uniform run styling is
            // dropped for the joined text, which is acceptable because math spans
            // carry no meaningful intra-run styling.
            StringBuilder joined = new StringBuilder();
            for (RenderedRun run : p.runs) {
                if (run != null && run.text != null) joined.append(run.text);
            }
            appendRunText(joined.toString(), out);
        } else {
            for (RenderedRun run : p.runs) renderRun(run, out);
        }
        if (listItem) out.append("</span>");
        // An empty paragraph in the source is a deliberate vertical spacer; with
        // no content it would collapse to just its bottom margin. Emit a
        // zero-width non-breaking space so it preserves a blank line's height.
        boolean hasText = false;
        for (RenderedRun run : p.runs) {
            if (run != null && run.text != null && !run.text.isEmpty()) { hasText = true; break; }
        }
        if (!hasText && !listItem) out.append("&#8203;");
        out.append("</p>");
    }

    private static boolean mathSpansRuns(java.util.List<RenderedRun> runs) {
        if (runs == null || runs.size() < 2) return false;
        StringBuilder joined = new StringBuilder();
        boolean anyDollar = false;
        int perRunSpans = 0;
        for (RenderedRun run : runs) {
            if (run != null && run.text != null) {
                if (run.text.indexOf('$') >= 0) anyDollar = true;
                perRunSpans += countCompleteMath(run.text);
                joined.append(run.text);
            }
        }
        if (!anyDollar) return false;
        // If joining the runs reveals more renderable math spans than the runs
        // expose individually, at least one span straddles a run boundary and the
        // per-run path would leave it as raw LaTeX. Re-render the joined text in
        // that case. (DOCX commonly splits a single $...$ across runs when only
        // part of it is italic/bold.)
        return countCompleteMath(joined.toString()) > perRunSpans;
    }

    private static int countCompleteMath(String text) {
        if (text == null) return 0;
        int count = 0;
        int cursor = 0;
        while (cursor < text.length()) {
            int start = text.indexOf('$', cursor);
            if (start < 0) break;
            boolean display = start + 1 < text.length() && text.charAt(start + 1) == '$';
            int end = display ? findDisplayMathEnd(text, start + 2) : findInlineMathEnd(text, start + 1);
            if (end < 0) { cursor = start + 1; continue; }
            String math = display ? text.substring(start + 2, end) : text.substring(start + 1, end);
            if (looksLikeReadableInlineMath(math)) count++;
            cursor = end + (display ? 2 : 1);
        }
        return count;
    }

    private static void renderRun(RenderedRun run, StringBuilder out) {
        if (run == null) return;
        boolean hasStyle = run.style != null && !run.style.isEmpty();
        boolean hasAnchor = run.hasAnchorRange();
        boolean hasLink = run.hasLink();
        boolean hasId = run.hasElementId();
        if (hasStyle || hasAnchor || hasLink || hasId) {
            out.append(hasLink ? "<a" : "<span");
            if (hasId) out.append(" id=\"").append(escapeAttribute(run.elementId)).append("\"");
            if (hasLink) {
                out.append(" href=\"").append(escapeAttribute(run.linkHref)).append("\"")
                        .append(" class=\"rw-note-ref\"");
            }
            if (hasAnchor) {
                out.append(" data-anchor-start=\"").append(run.anchorStart).append("\"")
                        .append(" data-anchor-end=\"").append(run.anchorEnd).append("\"");
            }
            if (hasStyle) {
                out.append(" style=\"");
                appendTextStyle(run.style, out);
                out.append("\"");
            }
            out.append(">");
            appendRunText(run.text, out);
            out.append(hasLink ? "</a>" : "</span>");
        } else {
            appendRunText(run.text, out);
        }
    }

    private static void renderTable(RenderedTable table, StringBuilder out) {
        if (table == null) return;
        out.append("<table class=\"rw-table\" style=\"");
        if (table.widthPercent != null) appendPercent(out, "width", table.widthPercent);
        out.append("\">");
        if (table.columnWidthPercents != null && !table.columnWidthPercents.isEmpty()) {
            out.append("<colgroup>");
            for (Float width : table.columnWidthPercents) {
                out.append("<col");
                if (width != null && width > 0) {
                    out.append(" style=\"");
                    appendPercent(out, "width", width);
                    out.append("\"");
                }
                out.append(">");
            }
            out.append("</colgroup>");
        }
        for (java.util.List<RenderedTableCell> row : table.rows) {
            out.append("<tr>");
            for (RenderedTableCell cell : row) renderCell(cell, out);
            out.append("</tr>");
        }
        out.append("</table>");
    }

    private static void renderCell(RenderedTableCell cell, StringBuilder out) {
        if (cell == null) return;
        out.append("<td");
        if (cell.colSpan > 1) out.append(" colspan=\"").append(cell.colSpan).append("\"");
        if (cell.rowSpan > 1) out.append(" rowspan=\"").append(cell.rowSpan).append("\"");
        out.append(" style=\"");
        boolean hasEdges = cell.borderTop != null || cell.borderRight != null
                || cell.borderBottom != null || cell.borderLeft != null;
        if (hasEdges) {
            // Explicit per-side borders fully override the default .rw-table td box.
            appendEdge(out, "border-top", cell.borderTop);
            appendEdge(out, "border-right", cell.borderRight);
            appendEdge(out, "border-bottom", cell.borderBottom);
            appendEdge(out, "border-left", cell.borderLeft);
        } else if (Boolean.FALSE.equals(cell.borderVisible)) {
            out.append("border:none;");
        } else if (Boolean.TRUE.equals(cell.borderVisible)) {
            out.append("border-style:solid;");
        }
        if (cell.borderColor != null) out.append("border-color:").append(escapeCssToken(cell.borderColor)).append(';');
        if (cell.backgroundColor != null) out.append("background-color:").append(escapeCssToken(cell.backgroundColor)).append(';');
        if (cell.widthPercent != null) appendPercent(out, "width", cell.widthPercent);
        if (cell.minHeightPt != null) appendPt(out, "min-height", cell.minHeightPt);
        if (cell.paddingTopPt != null) appendPt(out, "padding-top", cell.paddingTopPt);
        if (cell.paddingRightPt != null) appendPt(out, "padding-right", cell.paddingRightPt);
        if (cell.paddingBottomPt != null) appendPt(out, "padding-bottom", cell.paddingBottomPt);
        if (cell.paddingLeftPt != null) appendPt(out, "padding-left", cell.paddingLeftPt);
        out.append("\">");
        for (RenderedBlock block : cell.blocks) renderBlock(block, out);
        out.append("</td>");
    }

    private static void appendEdge(StringBuilder out, String prop, Boolean visible) {
        if (Boolean.TRUE.equals(visible)) out.append(prop).append(":1px solid #777;");
        else out.append(prop).append(":none;");
    }

    private static void renderImage(RenderedImage image, StringBuilder out) {
        if (image == null) return;
        out.append("<img class=\"rw-image");
        if (image.downgradedFromFloating) out.append(" rw-floating-downgraded");
        out.append("\" src=\"").append(escapeAttribute(image.src)).append("\" alt=\"")
                .append(escapeAttribute(image.altText)).append("\" style=\"");
        if (image.widthPt != null) appendPt(out, "width", image.widthPt);
        if (image.heightPt != null) appendPt(out, "height", image.heightPt);
        out.append("\">");
    }

    private static void renderHorizontalLine(RenderedHorizontalLine line, StringBuilder out) {
        if (line == null) {
            out.append("<div class=\"rw-hr\"></div>");
            return;
        }
        StringBuilder style = new StringBuilder();
        // Reproduce the stroke weight but keep it a thin screen rule: clamp to a
        // hairline..1pt range so a heavier source weight (e.g. .99pt at high
        // density) does not render as a thick bar. Always set the full border-top
        // shorthand so it fully overrides the base .rw-hr rule.
        float t = line.hasThickness() ? Math.max(0.4f, Math.min(line.thicknessPt, 0.75f)) : 0.5f;
        String color = line.color != null ? line.color : "currentColor";
        style.append("border-top:").append(trimFloat(t)).append("pt solid ").append(color).append(";");
        if (line.hasExtent()) {
            // Keep the line's original horizontal proportion, but anchor its left
            // edge to the body text column rather than reproducing a small source
            // inset: in a reflow layout the body text starts at the column edge,
            // so a separately-indented rule reads as misaligned. A meaningful
            // left inset (> ~15% of the page) is kept; a small one is dropped so
            // the rule lines up with the text above and below it.
            float widthPct = clampPct((line.rightPt - line.leftPt) / line.pageWidthPt * 100f);
            float leftPct = clampPct(line.leftPt / line.pageWidthPt * 100f);
            if (leftPct < 15f) {
                widthPct = clampPct(widthPct + leftPct);
                leftPct = 0f;
            }
            if (leftPct + widthPct > 100f) leftPct = Math.max(0f, 100f - widthPct);
            style.append("width:").append(trimFloat(widthPct)).append("%;");
            style.append("margin-left:").append(trimFloat(leftPct)).append("%;");
            style.append("margin-right:0;");
        }
        out.append("<div class=\"rw-hr\"");
        if (style.length() > 0) out.append(" style=\"").append(style).append("\"");
        out.append("></div>");
    }

    private static float clampPct(float v) {
        if (v < 0f) return 0f;
        if (v > 100f) return 100f;
        return v;
    }

    private static String trimFloat(float v) {
        if (v == Math.rint(v)) return Integer.toString((int) v);
        return String.format(Locale.US, "%.2f", v);
    }

    private static void appendParagraphStyle(ParagraphStyle style, StringBuilder out) {
        appendParagraphStyle(style, out, true);
    }

    private static void appendParagraphStyle(ParagraphStyle style, StringBuilder out, boolean includeTextIndent) {
        if (style == null) return;
        if (style.alignment != null) out.append("text-align:").append(style.alignment.name().toLowerCase(Locale.US)).append(';');
        if (style.marginTopPt != null) appendPt(out, "margin-top", style.marginTopPt);
        if (style.marginBottomPt != null) appendPt(out, "margin-bottom", style.marginBottomPt);
        if (style.marginLeftPt != null) appendPt(out, "margin-left", style.marginLeftPt);
        if (style.marginRightPt != null) appendPt(out, "margin-right", style.marginRightPt);
        if (includeTextIndent && style.textIndentPt != null) appendPt(out, "text-indent", style.textIndentPt);
        if (style.lineHeightPt != null) appendPt(out, "line-height", style.lineHeightPt);
        else if (style.lineHeightMultiplier != null) out.append("line-height:").append(cssNumber(style.lineHeightMultiplier)).append(';');
        if (style.backgroundColor != null) out.append("background-color:").append(escapeCssToken(style.backgroundColor)).append(';');
    }

    private static void appendTextStyle(TextStyle style, StringBuilder out) {
        if (style.fontFamily != null) out.append("font-family:'").append(escapeCssString(style.fontFamily)).append("';");
        if (style.fontSizePt != null) appendPt(out, "font-size", style.fontSizePt);
        if (Boolean.TRUE.equals(style.bold)) out.append("font-weight:bold;");
        if (Boolean.TRUE.equals(style.italic)) out.append("font-style:italic;");
        if (Boolean.TRUE.equals(style.underline) || Boolean.TRUE.equals(style.strike)) {
            out.append("text-decoration:");
            if (Boolean.TRUE.equals(style.underline)) out.append("underline ");
            if (Boolean.TRUE.equals(style.strike)) out.append("line-through ");
            out.append(';');
        }
        if (style.color != null) out.append("color:").append(escapeCssToken(style.color)).append(';');
        if (style.backgroundColor != null) out.append("background-color:").append(escapeCssToken(style.backgroundColor)).append(';');
        if (style.verticalAlign == TextStyle.VerticalAlign.SUPERSCRIPT) out.append("vertical-align:super;font-size:smaller;");
        else if (style.verticalAlign == TextStyle.VerticalAlign.SUBSCRIPT) out.append("vertical-align:sub;font-size:smaller;");
    }

    private static void appendNumberCustomProperty(StringBuilder out, String name, float value) {
        out.append(name).append(':').append(cssNumber(value > 0 ? value : 0)).append(';');
    }

    private static void appendViewportPadding(StringBuilder out, String name, float marginPt, float pagePt,
                                              String viewportUnit, float minPx, float maxPx) {
        if (marginPt <= 0f || pagePt <= 0f) {
            out.append(name).append(":0;");
            return;
        }
        float pct = (marginPt / pagePt) * 100f;
        if (Float.isNaN(pct) || Float.isInfinite(pct)) pct = 0f;
        pct = Math.max(0f, Math.min(18f, pct));
        if (pct <= 0.1f) {
            out.append(name).append(":0;");
        } else {
            out.append(name).append(":clamp(")
                    .append(cssNumber(minPx)).append("px,")
                    .append(cssNumber(pct)).append(viewportUnit).append(',')
                    .append(cssNumber(maxPx)).append("px);");
        }
    }

    private static void appendPt(StringBuilder out, String name, float pt) {
        out.append(name).append(':').append(cssNumber(pt)).append("pt;");
    }

    private static void appendPercent(StringBuilder out, String name, float pct) {
        out.append(name).append(':').append(cssNumber(pct)).append("%;");
    }

    private static String cssNumber(float value) {
        return String.format(Locale.US, "%.2f", value).replaceAll("\\.00$", "");
    }

    private static String escapeText(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private static void appendRunText(String text, StringBuilder out) {
        if (text == null || text.isEmpty()) return;
        int cursor = 0;
        while (cursor < text.length()) {
            int start = text.indexOf('$', cursor);
            if (start < 0) {
                out.append(escapeText(text.substring(cursor)));
                return;
            }
            boolean display = start + 1 < text.length() && text.charAt(start + 1) == '$';
            int end = display ? findDisplayMathEnd(text, start + 2) : findInlineMathEnd(text, start + 1);
            if (end < 0) {
                out.append(escapeText(text.substring(cursor)));
                return;
            }
            out.append(escapeText(text.substring(cursor, start)));
            String math = display ? text.substring(start + 2, end) : text.substring(start + 1, end);
            if (looksLikeReadableInlineMath(math)) {
                out.append(display ? "<span class=\"rw-display-math\">" : "<span class=\"rw-inline-math\">");
                appendInlineMath(math, out);
                out.append("</span>");
            } else {
                out.append(escapeText(text.substring(start, end + (display ? 2 : 1))));
            }
            cursor = end + (display ? 2 : 1);
        }
    }

    private static int findDisplayMathEnd(String text, int from) {
        return text.indexOf("$$", from);
    }

    private static int findInlineMathEnd(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '$') return i;
            if (c == '\n' || c == '\r') return -1;
        }
        return -1;
    }

    private static boolean looksLikeReadableInlineMath(String math) {
        if (math == null) return false;
        String trimmed = math.trim();
        if (trimmed.isEmpty() || trimmed.length() > 200) return false;
        // Structural math markers are an immediate yes.
        if (trimmed.indexOf('_') >= 0 || trimmed.indexOf('^') >= 0 || trimmed.indexOf('\\') >= 0) {
            return true;
        }
        // Otherwise accept compact expressions built only from letters, digits,
        // and the handful of operators that show up in inline math (e.g. "2Dt",
        // "L/W", "C(x,t)"). This catches delimiter-only math that carries no
        // LaTeX command but is still clearly a formula, while rejecting ordinary
        // prose or currency that happened to sit between dollar signs.
        boolean sawLetterOrDigit = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c)) { sawLetterOrDigit = true; continue; }
            if (c == ' ' || c == '/' || c == '(' || c == ')' || c == '+' || c == '-'
                    || c == '*' || c == '=' || c == ',' || c == '.' || c == '|'
                    || c == '<' || c == '>') {
                continue;
            }
            return false; // unexpected character -> treat as plain text
        }
        return sawLetterOrDigit;
    }

    private static void appendInlineMath(String math, StringBuilder out) {
        String src = math != null ? math.trim() : "";
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if ((c == '^' || c == '_') && i + 1 < src.length()) {
                GroupToken token = readMathGroup(src, i + 1);
                if (token != null) {
                    out.append(c == '^' ? "<sup>" : "<sub>");
                    appendInlineMath(token.text, out);
                    out.append(c == '^' ? "</sup>" : "</sub>");
                    i = token.endIndex;
                    continue;
                }
            }
            if (c == '\\') {
                String command = readMathCommand(src, i + 1);
                if (command.length() > 0) {
                    int commandEnd = i + command.length();
                    if ("text".equals(command) && commandEnd + 1 < src.length() && src.charAt(commandEnd + 1) == '{') {
                        GroupToken token = readMathGroup(src, commandEnd + 1);
                        if (token != null) {
                            out.append(escapeText(token.text));
                            i = token.endIndex;
                            continue;
                        }
                    }
                    if ("frac".equals(command) && commandEnd + 1 < src.length() && src.charAt(commandEnd + 1) == '{') {
                        GroupToken numerator = readMathGroup(src, commandEnd + 1);
                        if (numerator != null && numerator.endIndex + 1 < src.length() && src.charAt(numerator.endIndex + 1) == '{') {
                            GroupToken denominator = readMathGroup(src, numerator.endIndex + 1);
                            if (denominator != null) {
                                out.append("<span class=\"frac\"><span class=\"num\">");
                                appendInlineMath(numerator.text, out);
                                out.append("</span><span class=\"den\">");
                                appendInlineMath(denominator.text, out);
                                out.append("</span></span>");
                                i = denominator.endIndex;
                                continue;
                            }
                        }
                    }
                    if ("sqrt".equals(command) && commandEnd + 1 < src.length() && src.charAt(commandEnd + 1) == '{') {
                        GroupToken token = readMathGroup(src, commandEnd + 1);
                        if (token != null) {
                            out.append("\u221a(");
                            appendInlineMath(token.text, out);
                            out.append(")");
                            i = token.endIndex;
                            continue;
                        }
                    }
                    String mapped = mapMathCommand(command);
                    if (mapped != null) {
                        out.append(escapeText(mapped));
                        i = commandEnd;
                        continue;
                    }
                }
                continue;
            }
            if (c == '{' || c == '}') continue;
            out.append(escapeText(String.valueOf(c)));
        }
    }

    private static GroupToken readMathGroup(String src, int start) {
        if (start >= src.length()) return null;
        if (src.charAt(start) != '{') return new GroupToken(String.valueOf(src.charAt(start)), start);
        int depth = 1;
        StringBuilder text = new StringBuilder();
        for (int i = start + 1; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
                text.append(c);
            } else if (c == '}') {
                depth--;
                if (depth == 0) return new GroupToken(text.toString(), i);
                text.append(c);
            } else {
                text.append(c);
            }
        }
        return null;
    }

    private static String readMathCommand(String src, int start) {
        StringBuilder command = new StringBuilder();
        for (int i = start; i < src.length(); i++) {
            char c = src.charAt(i);
            if (!Character.isLetter(c)) break;
            command.append(c);
        }
        return command.toString();
    }

    private static String mapMathCommand(String command) {
        if ("nu".equals(command)) return "\u03bd";
        if ("mu".equals(command)) return "\u03bc";
        if ("alpha".equals(command)) return "\u03b1";
        if ("beta".equals(command)) return "\u03b2";
        if ("gamma".equals(command)) return "\u03b3";
        if ("Gamma".equals(command)) return "\u0393";
        if ("delta".equals(command)) return "\u03b4";
        if ("Delta".equals(command)) return "\u0394";
        if ("epsilon".equals(command) || "varepsilon".equals(command)) return "\u03b5";
        if ("zeta".equals(command)) return "\u03b6";
        if ("eta".equals(command)) return "\u03b7";
        if ("lambda".equals(command)) return "\u03bb";
        if ("Lambda".equals(command)) return "\u039b";
        if ("theta".equals(command)) return "\u03b8";
        if ("Theta".equals(command)) return "\u0398";
        if ("kappa".equals(command)) return "\u03ba";
        if ("rho".equals(command)) return "\u03c1";
        if ("phi".equals(command) || "varphi".equals(command)) return "\u03c6";
        if ("Phi".equals(command)) return "\u03a6";
        if ("psi".equals(command)) return "\u03c8";
        if ("omega".equals(command)) return "\u03c9";
        if ("Omega".equals(command)) return "\u03a9";
        if ("tau".equals(command)) return "\u03c4";
        if ("chi".equals(command)) return "\u03c7";
        if ("xi".equals(command)) return "\u03be";
        if ("pi".equals(command)) return "\u03c0";
        if ("Pi".equals(command)) return "\u03a0";
        if ("sigma".equals(command)) return "\u03c3";
        if ("Sigma".equals(command)) return "\u03a3";
        if ("times".equals(command)) return "\u00d7";
        if ("cdot".equals(command)) return "\u00b7";
        if ("pm".equals(command)) return "\u00b1";
        if ("mp".equals(command)) return "\u2213";
        if ("approx".equals(command)) return "\u2248";
        if ("neq".equals(command) || "ne".equals(command)) return "\u2260";
        if ("propto".equals(command)) return "\u221d";
        if ("infty".equals(command)) return "\u221e";
        if ("rightarrow".equals(command) || "to".equals(command)) return "\u2192";
        if ("leftarrow".equals(command)) return "\u2190";
        if ("le".equals(command) || "leq".equals(command)) return "\u2264";
        if ("ge".equals(command) || "geq".equals(command)) return "\u2265";
        if ("partial".equals(command)) return "\u2202";
        if ("nabla".equals(command)) return "\u2207";
        if ("sum".equals(command)) return "\u2211";
        if ("int".equals(command)) return "\u222b";
        if ("left".equals(command) || "right".equals(command)) return "";
        return null;
    }

    private static final class GroupToken {
        final String text;
        final int endIndex;

        GroupToken(String text, int endIndex) {
            this.text = text != null ? text : "";
            this.endIndex = endIndex;
        }
    }

    private static String escapeAttribute(String text) {
        return escapeText(text).replace("'", "&#39;");
    }

    private static String escapeCssString(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ");
    }

    private static String escapeCssToken(String text) {
        if (text == null) return "";
        return text.replaceAll("[^#A-Za-z0-9.,%()_\\- ]", "");
    }
}
