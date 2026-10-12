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
        out.append(".rw-page{box-sizing:border-box;width:100%;max-width:100%;margin:0 auto;background:var(--paper-bg,#fff);color:var(--paper-fg,#111);box-shadow:none;overflow:hidden;page-break-after:always;font-size:calc(12 * var(--rw-point,1pt));-webkit-text-size-adjust:none;text-size-adjust:none;}");
        out.append(".rw-page-inner{box-sizing:border-box;min-height:var(--rw-page-min-height,100vh);display:flex;flex-direction:column;}");
        out.append(".rw-page-header,.rw-page-footer{font-size:.9em;color:#333;}");
        out.append(".rw-page-header{flex:0 0 auto;margin-bottom:.7em;}");
        out.append(".rw-page-body{flex:1 1 auto;}");
        out.append(".rw-page-footer{flex:0 0 auto;margin-top:auto;padding-top:.7em;}");
        out.append(".rw-p{margin:0 0 .55em 0;line-height:1.35;white-space:pre-wrap;}");
        out.append(".rw-p,.rw-list-content{overflow-wrap:break-word;}");
        out.append(".rw-list-p{display:flex;gap:.45em;align-items:baseline;break-inside:avoid;}");
        out.append(".rw-list-marker{flex:0 0 auto;min-width:1.6em;text-align:right;}");
        out.append(".rw-list-content{flex:1 1 auto;min-width:0;}");
        out.append(".rw-table{border-collapse:collapse;width:100%;margin:.65em 0;table-layout:fixed;break-inside:avoid;}");
        out.append(".rw-table td{border:1px solid #777;padding:.35em .45em;vertical-align:top;min-width:0;overflow:visible;overflow-wrap:break-word;word-break:normal;}");
        out.append(".rw-table td *{max-width:100%;overflow-wrap:break-word;word-break:normal;}");
        out.append(".rw-table .rw-p{max-width:100%;white-space:pre-wrap;overflow-wrap:break-word;word-break:normal;}");
        out.append(".rw-inline-math{white-space:normal;font-family:inherit;}");
        out.append(".rw-display-math{display:block;margin:.45em 0;white-space:normal;overflow-wrap:anywhere;word-break:break-word;font-family:inherit;}");
        out.append(".rw-inline-math .frac,.rw-display-math .frac{display:inline-flex;flex-direction:column;align-items:center;vertical-align:middle;margin:0 .12em;line-height:1.05;}");
        out.append(".rw-inline-math .frac .num,.rw-display-math .frac .num{border-bottom:1px solid currentColor;padding:0 .12em;}");
        out.append(".rw-inline-math .frac .den,.rw-display-math .frac .den{padding:0 .12em;}");
        out.append(".rw-inline-math sup,.rw-inline-math sub,.rw-display-math sup,.rw-display-math sub{font-size:.72em;line-height:0;}");
        out.append(".rw-image{display:block;max-width:100%;height:auto;object-fit:contain;margin:.6em auto;break-inside:avoid;}");
        out.append(".rw-image-missing{display:flex;align-items:center;justify-content:center;box-sizing:border-box;"
                + "max-width:100%;min-width:2.2em;min-height:2.2em;margin:.6em auto;border:1px dashed currentColor;"
                + "border-radius:3px;opacity:.55;break-inside:avoid;}");
        // Language-neutral framed-picture glyph (U+1F5BC). CSS content avoids any
        // translatable string for the WMF/EMF/OLE placeholder.
        out.append(".rw-image-missing::after{content:\"\\1F5BC\";font-size:1.6em;line-height:1;}");
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
        boolean authoredGeometry = !"hwp-official-letter".equals(document.sourceFormat);
        for (RenderedPage page : document.pages) renderPage(page, out, authoredGeometry);
        out.append("</body></html>");
        return out.toString();
    }

    private static void renderPage(RenderedPage page, StringBuilder out, boolean authoredGeometry) {
        out.append("<section class=\"rw-page\" data-page=\"").append(page.pageIndex + 1).append("\" style=\"");
        appendNumberCustomProperty(out, "--rw-page-width-pt", page.widthPt);
        appendNumberCustomProperty(out, "--rw-page-height-pt", page.heightPt);
        if (authoredGeometry) {
            // One width-derived point scales text, margins, tables and images together.
            // Minimum height preserves paper proportions without clipping overflow content.
            out.append("--rw-point:calc(100vw / ").append(cssNumber(page.widthPt)).append(");");
            out.append("--rw-page-min-height:calc(var(--rw-point) * ").append(cssNumber(page.heightPt)).append(");");
        }
        out.append("\">");
        out.append("<div class=\"rw-page-inner\" style=\"");
        appendPt(out, "padding-top", page.marginTopPt);
        appendPt(out, "padding-right", page.marginRightPt);
        appendPt(out, "padding-bottom", page.marginBottomPt);
        appendPt(out, "padding-left", page.marginLeftPt);
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
        renderParagraphRuns(p.runs, out);
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

    private static void renderParagraphRuns(java.util.List<RenderedRun> runs, StringBuilder out) {
        for (int start = 0; start < runs.size();) {
            RenderedRun first = runs.get(start);
            if (first == null || first.hasLink() || first.hasElementId() || first.hasAnchorRange()) {
                // Never join across navigation/anchor identities.
                renderRun(first, out);
                start++;
                continue;
            }
            int end = start;
            boolean hasDelimiter = false;
            while (end < runs.size()) {
                RenderedRun run = runs.get(end);
                if (run == null || run.hasLink() || run.hasElementId() || run.hasAnchorRange()) break;
                hasDelimiter |= run.text.indexOf('$') >= 0 || run.text.indexOf('\\') >= 0;
                end++;
            }
            if (!hasDelimiter || end == start + 1) {
                for (int i = start; i < end; i++) renderRun(runs.get(i), out);
            } else {
                StringBuilder joined = new StringBuilder();
                for (int i = start; i < end; i++) joined.append(runs.get(i).text);
                String text = joined.toString();
                RunCursor cursor = new RunCursor(runs, start, end);
                int position = 0;
                MathSpan span;
                while ((span = findMathSpan(text, position)) != null) {
                    cursor.advance(span.start, out);
                    TextStyle style = cursor.style();
                    boolean styled = style != null && !style.isEmpty();
                    if (styled) { out.append("<span style=\""); appendTextStyle(style, out); out.append("\">"); }
                    out.append(span.html);
                    if (styled) out.append("</span>");
                    cursor.advance(span.end, null);
                    position = span.end;
                }
                cursor.advance(text.length(), out);
            }
            start = end;
        }
    }

    /** Forward-only slices preserve surrounding styles without reparsing fragments. */
    private static final class RunCursor {
        final java.util.List<RenderedRun> runs;
        final int end;
        int index, offset, position;
        RunCursor(java.util.List<RenderedRun> runs, int start, int end) {
            this.runs = runs; this.index = start; this.end = end;
        }
        TextStyle style() {
            while (index < end && offset == runs.get(index).text.length()) { index++; offset = 0; }
            return index < end ? runs.get(index).style : null;
        }
        void advance(int target, StringBuilder out) {
            while (position < target && index < end) {
                TextStyle style = style();
                if (index == end) break;
                RenderedRun run = runs.get(index);
                int count = Math.min(target - position, run.text.length() - offset);
                if (out != null) {
                    boolean styled = style != null && !style.isEmpty();
                    if (styled) { out.append("<span style=\""); appendTextStyle(style, out); out.append("\">"); }
                    out.append(escapeText(run.text.substring(offset, offset + count)));
                    if (styled) out.append("</span>");
                }
                offset += count;
                position += count;
            }
        }
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
        if (table.widthPt != null) appendPt(out, "width", table.widthPt);
        if (table.alignment == ParagraphStyle.Alignment.CENTER) out.append("margin-left:auto;margin-right:auto;");
        else if (table.alignment == ParagraphStyle.Alignment.RIGHT) out.append("margin-left:auto;margin-right:0;");
        else if (table.indentPt != null) appendPt(out, "margin-left", table.indentPt);
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
        if (cell.minHeightPt != null) {
            appendPt(out, "min-height", cell.minHeightPt);
            // CSS table cells use height as a minimum; min-height alone is not reliable.
            appendPt(out, "height", cell.minHeightPt);
        }
        if (cell.verticalAlignment != null) out.append("vertical-align:")
                .append(cell.verticalAlignment.name().toLowerCase(Locale.US)).append(';');
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
        if (image.unrenderablePlaceholder) {
            // A picture existed but has no WebView-decodable raster form (HWP
            // WMF/EMF/OLE). Draw a language-neutral framed box at the authored
            // size instead of dropping the image silently. The glyph comes from
            // CSS content so no translatable string is introduced.
            out.append("<span class=\"rw-image-missing\" style=\"");
            if (image.widthPt != null) appendPt(out, "width", image.widthPt);
            if (image.heightPt != null) appendPt(out, "height", image.heightPt);
            out.append("\"></span>");
            return;
        }
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
        String background = style.highlightColor != null && !"none".equals(style.highlightColor)
                ? style.highlightColor : style.backgroundColor;
        if (background != null) out.append("background-color:").append(escapeCssToken(background)).append(';');
        if (style.verticalAlign == TextStyle.VerticalAlign.SUPERSCRIPT
                || style.verticalAlign == TextStyle.VerticalAlign.SUBSCRIPT) {
            out.append(style.verticalAlign == TextStyle.VerticalAlign.SUPERSCRIPT ? "vertical-align:super;" : "vertical-align:sub;");
            // "smaller" alone scales the paragraph font, ignoring an explicit run size.
            if (style.fontSizePt != null) appendPt(out, "font-size", style.fontSizePt * .72f);
            else out.append("font-size:smaller;");
        } else if (style.verticalAlignSpecified) out.append("vertical-align:baseline;");
    }

    private static void appendNumberCustomProperty(StringBuilder out, String name, float value) {
        out.append(name).append(':').append(cssNumber(value > 0 ? value : 0)).append(';');
    }

    private static void appendPt(StringBuilder out, String name, float pt) {
        if (Float.isNaN(pt) || Float.isInfinite(pt)) return;
        out.append(name).append(':').append(cssNumber(pt)).append("pt;");
        out.append(name).append(":calc(var(--rw-point,1pt) * ").append(cssNumber(pt)).append(");");
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
        MathSpan span;
        while ((span = findMathSpan(text, cursor)) != null) {
            out.append(escapeText(text.substring(cursor, span.start))).append(span.html);
            cursor = span.end;
        }
        out.append(escapeText(text.substring(cursor)));
    }

    private static MathSpan findMathSpan(String text, int from) {
        // Retain the existing conservative fragment-size policy. Bound each scan,
        // too, so many unmatched openers cannot cause quadratic paragraph scans.
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()
                    && text.charAt(i + 1) != '(' && text.charAt(i + 1) != '[') { i++; continue; }
            boolean dollar = c == '$';
            boolean bracket = c == '\\' && i + 1 < text.length()
                    && (text.charAt(i + 1) == '(' || text.charAt(i + 1) == '[');
            if (!dollar && !bracket) continue;
            boolean display = bracket ? text.charAt(i + 1) == '['
                    : i + 1 < text.length() && text.charAt(i + 1) == '$';
            int width = bracket || display ? 2 : 1;
            String close = bracket ? (display ? "\\]" : "\\)") : (display ? "$$" : "$");
            int body = i + width;
            int limit = (int) Math.min((long) text.length(), (long) body + 201);
            boolean foundClose = false;
            for (int end = body; end < limit; end++) {
                if (!display && (text.charAt(end) == '\n' || text.charAt(end) == '\r')) break;
                if (text.startsWith(close, end)) {
                    foundClose = true;
                    String math = text.substring(body, end);
                    // Dollar-delimited prose/currency must not be swallowed.
                    boolean readable = bracket || ((display || math.equals(math.trim())) && looksLikeReadableInlineMath(math));
                    StringBuilder rendered = new StringBuilder();
                    if (readable && !math.trim().isEmpty() && appendInlineMath(math, rendered, 0)) {
                        String html = (display ? "<span class=\"rw-display-math\">" : "<span class=\"rw-inline-math\">")
                                + rendered + "</span>";
                        return new MathSpan(i, end + width, html);
                    }
                    i = end + width - 1; // Unsupported formulas remain whole, literal text.
                    break;
                }
                if (text.charAt(end) == '\\') end++;
            }
            if (!foundClose) i += width - 1;
        }
        return null;
    }

    private static final class MathSpan {
        final int start, end;
        final String html;
        MathSpan(int start, int end, String html) { this.start = start; this.end = end; this.html = html; }
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
        // Multiple bare words are prose, not a compact formula. Operators or
        // structural markers still admit expressions such as x + y and \text{}.
        return sawLetterOrDigit && (trimmed.indexOf(' ') < 0
                || trimmed.matches(".*[=+*/(),<>|\\-].*"));
    }

    private static boolean appendInlineMath(String math, StringBuilder out, int depth) {
        if (depth > 24) return false;
        String src = math != null ? math.trim() : "";
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '^' || c == '_') {
                GroupToken token = readMathGroup(src, i + 1);
                if (token == null) return false;
                out.append(c == '^' ? "<sup>" : "<sub>");
                if (!appendInlineMath(token.text, out, depth + 1)) return false;
                out.append(c == '^' ? "</sup>" : "</sub>");
                i = token.endIndex;
                continue;
            }
            if (c == '\\') {
                if (i + 1 >= src.length()) return false;
                char escaped = src.charAt(i + 1);
                if ("_^{ }$%&#\\".indexOf(escaped) >= 0) {
                    out.append(escapeText(String.valueOf(escaped)));
                    i++;
                    continue;
                }
                if (escaped == ',' || escaped == ';' || escaped == ':') {
                    out.append(' '); i++; continue;
                }
                String command = readMathCommand(src, i + 1);
                if (command.length() > 0) {
                    int commandEnd = i + command.length();
                    int argument = skipMathSpace(src, commandEnd + 1);
                    if (("text".equals(command) || "mathrm".equals(command)) && argument < src.length() && src.charAt(argument) == '{') {
                        GroupToken token = readMathGroup(src, argument);
                        if (token != null) {
                            out.append(escapeText(token.text));
                            i = token.endIndex;
                            continue;
                        }
                    }
                    if ("frac".equals(command) && argument < src.length() && src.charAt(argument) == '{') {
                        GroupToken numerator = readMathGroup(src, argument);
                        int denominatorStart = numerator != null ? skipMathSpace(src, numerator.endIndex + 1) : src.length();
                        if (denominatorStart < src.length() && src.charAt(denominatorStart) == '{') {
                            GroupToken denominator = readMathGroup(src, denominatorStart);
                            if (denominator != null) {
                                out.append("<span class=\"frac\"><span class=\"num\">");
                                if (!appendInlineMath(numerator.text, out, depth + 1)) return false;
                                out.append("</span><span class=\"den\">");
                                if (!appendInlineMath(denominator.text, out, depth + 1)) return false;
                                out.append("</span></span>");
                                i = denominator.endIndex;
                                continue;
                            }
                        }
                    }
                    if ("sqrt".equals(command) && argument < src.length() && src.charAt(argument) == '{') {
                        GroupToken token = readMathGroup(src, argument);
                        if (token != null) {
                            out.append("\u221a(");
                            if (!appendInlineMath(token.text, out, depth + 1)) return false;
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
                return false; // Never erase an unknown command's backslash.
            }
            if (c == '{') {
                GroupToken group = readMathGroup(src, i);
                if (group == null || !appendInlineMath(group.text, out, depth + 1)) return false;
                i = group.endIndex;
                continue;
            }
            if (c == '}') return false;
            out.append(escapeText(String.valueOf(c)));
        }
        return true;
    }

    private static GroupToken readMathGroup(String src, int start) {
        start = skipMathSpace(src, start);
        if (start >= src.length()) return null;
        if (src.charAt(start) == '\\') {
            if (start + 1 >= src.length()) return null;
            String command = readMathCommand(src, start + 1);
            int end = start + Math.max(1, command.length());
            // A command with arguments is one script token, not a bare slash.
            int groups = "frac".equals(command) ? 2
                    : ("sqrt".equals(command) || "text".equals(command) || "mathrm".equals(command) ? 1 : 0);
            // Argument parsing here is non-recursive: each required argument must be braced.
            for (int n = 0; n < groups; n++) {
                int next = skipMathSpace(src, end + 1);
                if (next >= src.length() || src.charAt(next) != '{') return null;
                GroupToken group = readMathGroup(src, next);
                if (group == null) return null;
                end = group.endIndex;
            }
            return new GroupToken(src.substring(start, end + 1), end);
        }
        if (src.charAt(start) != '{') {
            if ("}^_".indexOf(src.charAt(start)) >= 0) return null;
            int end = start + Character.charCount(src.codePointAt(start));
            return new GroupToken(src.substring(start, end), end - 1);
        }
        int depth = 1;
        StringBuilder text = new StringBuilder();
        for (int i = start + 1; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < src.length()) {
                text.append(c).append(src.charAt(++i));
            } else if (c == '{') {
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

    private static int skipMathSpace(String src, int start) {
        while (start < src.length() && Character.isWhitespace(src.charAt(start))) start++;
        return start;
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
