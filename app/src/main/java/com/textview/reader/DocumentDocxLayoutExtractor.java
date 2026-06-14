package com.textview.reader;

import com.textview.reader.document.render.ParagraphStyle;
import com.textview.reader.document.render.RenderedBlock;
import com.textview.reader.document.render.RenderedDocument;
import com.textview.reader.document.render.RenderedHorizontalLine;
import com.textview.reader.document.render.RenderedImage;
import com.textview.reader.document.render.RenderedPage;
import com.textview.reader.document.render.RenderedParagraph;
import com.textview.reader.document.render.RenderedRun;
import com.textview.reader.document.render.RenderedTable;
import com.textview.reader.document.render.RenderedTableCell;
import com.textview.reader.document.render.TextStyle;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * DOCX -> RenderedDocument bridge for the 1.0.3 L3 fidelity cycle.
 *
 * This is intentionally conservative: it converts the already-verified subset
 * (paragraphs, run styling, page margins, basic tables, inline images) into the
 * shared RenderedDocument model, and DocumentPageActivity still falls back to
 * the legacy semantic HTML converter if this bridge fails.
 */
final class DocumentDocxLayoutExtractor {
    private static final float DEFAULT_PAGE_WIDTH_PT = 595f;
    private static final float DEFAULT_PAGE_HEIGHT_PT = 842f;
    private static final float DEFAULT_MARGIN_PT = 54f;
    private static final int DEFAULT_BLOCKS_PER_PAGE = 28;
    private static final float DOCX_EMU_PER_POINT = 12700f;
    private static final float MAX_DOCX_IMAGE_WIDTH_PT = 540f;
    private static final float MAX_DOCX_IMAGE_HEIGHT_PT = 720f;
    private static final String HORIZONTAL_LINE_PLACEHOLDER = "__RW_HWP_OFFICIAL_HR__";

    private DocumentDocxLayoutExtractor() {}

    static RenderedDocument extract(ZipFile zip, String title, String localHost, int blocksPerPage) throws Exception {
        ZipEntry documentXml = zip != null ? zip.getEntry("word/document.xml") : null;
        if (documentXml == null) throw new IllegalArgumentException("word/document.xml is missing");

        Document doc;
        try (InputStream is = zip.getInputStream(documentXml)) {
            doc = DocumentArchiveUtils.secureDocumentBuilder().parse(is);
        }

        Node body = DocumentArchiveUtils.firstNodeByLocalName(doc, "body");
        if (body == null) body = doc.getDocumentElement();
        if (body == null) throw new IllegalArgumentException("DOCX document body is missing");

        Map<String, String> relationships = DocumentWordUtils.loadRelationships(zip);
        NumberingDefinitions numbering = loadNumberingDefinitions(zip);
        DocxStyles styles = loadStyleDefinitions(zip);
        NoteDefinitions notes = loadNoteDefinitions(zip, relationships, localHost, numbering, styles);
        HeaderFooterDefinitions headerFooters = loadHeaderFooterDefinitions(zip, body, relationships, localHost, numbering, styles, notes);
        ListCounterState listCounters = new ListCounterState();
        PageMetrics metrics = readPageMetrics(body);
        currentDocumentPageWidthPt.set(metrics.widthPt > 0 ? metrics.widthPt : null);
        try {
            return extractPagesInternal(body, relationships, numbering, styles, notes,
                    headerFooters, listCounters, metrics, blocksPerPage, localHost, title);
        } finally {
            currentDocumentPageWidthPt.remove();
        }
    }

    /** Page width (pt) of the document currently being extracted, for VML line extent. */
    private static final ThreadLocal<Float> currentDocumentPageWidthPt = new ThreadLocal<>();

    private static RenderedDocument extractPagesInternal(
            Node body, Map<String, String> relationships, NumberingDefinitions numbering,
            DocxStyles styles, NoteDefinitions notes, HeaderFooterDefinitions headerFooters,
            ListCounterState listCounters, PageMetrics metrics, int blocksPerPage,
            String localHost, String title) {
        ArrayList<RenderedPage> pages = new ArrayList<>();
        int blockBudget = Math.max(4, blocksPerPage > 0 ? blocksPerPage : DEFAULT_BLOCKS_PER_PAGE);
        PageAccumulator accumulator = new PageAccumulator(pages, metrics, blockBudget, headerFooters);

        NodeList children = body.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            String local = child.getLocalName();
            String name = child.getNodeName();
            boolean paragraph = "p".equals(local) || "w:p".equals(name);
            boolean table = "tbl".equals(local) || "w:tbl".equals(name);
            if (!paragraph && !table) continue;

            if (table) {
                RenderedTable renderedTable = readTable(child, relationships, localHost, numbering, listCounters, styles, notes);
                if (renderedTable != null && !renderedTable.rows.isEmpty()) {
                    accumulator.add(RenderedBlock.table(renderedTable));
                }
            } else {
                if (hasPageBreakBefore(child)) accumulator.breakPage();
                List<RenderedBlock> blocks = readParagraphBlocks(child, relationships, localHost, numbering, listCounters, styles, notes);
                for (RenderedBlock block : blocks) accumulator.add(block);
            }

            if (DocumentWordUtils.containsPageBreak(child)) accumulator.breakPage();
        }

        List<RenderedBlock> referencedNoteBlocks = notes != null ? notes.referencedBlocks() : new ArrayList<RenderedBlock>();
        for (RenderedBlock block : referencedNoteBlocks) accumulator.add(block);

        accumulator.finish();

        RenderedDocument.Builder document = RenderedDocument.builder("docx").title(title != null ? title : "Word");
        for (RenderedPage page : pages) document.addPage(page);
        return document.build();
    }

    private static RenderedPage.Builder pageBuilder(int pageIndex, PageMetrics metrics) {
        return pageBuilder(pageIndex, metrics, null);
    }

    private static RenderedPage.Builder pageBuilder(int pageIndex, PageMetrics metrics, HeaderFooterDefinitions headerFooters) {
        RenderedPage.Builder builder = RenderedPage.builder(pageIndex)
                .pageSizePt(metrics.widthPt, metrics.heightPt)
                .marginsPt(metrics.marginTopPt, metrics.marginRightPt, metrics.marginBottomPt, metrics.marginLeftPt);
        if (headerFooters != null) {
            for (RenderedBlock block : headerFooters.headerBlocks()) builder.addHeaderBlock(block);
            for (RenderedBlock block : headerFooters.footerBlocks()) builder.addFooterBlock(block);
        }
        return builder;
    }

    private static PageMetrics readPageMetrics(Node body) {
        PageMetrics metrics = new PageMetrics();
        Node sectPr = directChildByLocalName(body, "sectPr");
        if (sectPr == null) sectPr = lastDescendantByLocalName(body, "sectPr");
        if (sectPr == null) return metrics;

        Node pgSz = directChildByLocalName(sectPr, "pgSz");
        if (pgSz != null) {
            Float w = twipsToPt(DocumentArchiveUtils.attr(pgSz, "w:w", "w"));
            Float h = twipsToPt(DocumentArchiveUtils.attr(pgSz, "w:h", "h"));
            if (w != null && h != null && w > 0 && h > 0) {
                metrics.widthPt = w;
                metrics.heightPt = h;
            }
        }
        Node pgMar = directChildByLocalName(sectPr, "pgMar");
        if (pgMar != null) {
            Float top = twipsToPt(DocumentArchiveUtils.attr(pgMar, "w:top", "top"));
            Float right = twipsToPt(DocumentArchiveUtils.attr(pgMar, "w:right", "right"));
            Float bottom = twipsToPt(DocumentArchiveUtils.attr(pgMar, "w:bottom", "bottom"));
            Float left = twipsToPt(DocumentArchiveUtils.attr(pgMar, "w:left", "left"));
            if (top != null && top >= 0) metrics.marginTopPt = top;
            if (right != null && right >= 0) metrics.marginRightPt = right;
            if (bottom != null && bottom >= 0) metrics.marginBottomPt = bottom;
            if (left != null && left >= 0) metrics.marginLeftPt = left;
        }
        return metrics;
    }

    private static List<RenderedBlock> readParagraphBlocks(Node paragraph, Map<String, String> relationships,
                                                            String localHost, NumberingDefinitions numbering,
                                                            ListCounterState listCounters, DocxStyles styles,
                                                            NoteDefinitions notes) {
        ArrayList<RenderedBlock> blocks = new ArrayList<>();
        ArrayList<RenderedRun> runs = new ArrayList<>();
        TextStyle paragraphRunStyle = paragraphRunStyle(paragraph, styles);
        collectParagraphRuns(paragraph, paragraph, relationships, localHost, runs, blocks, styles, paragraphRunStyle, notes);
        runs = coalesceAdjacentTextRuns(runs);
        ListMarker marker = listCounters != null ? listCounters.next(paragraph, numbering) : null;
        if (!runs.isEmpty()) {
            RenderedParagraph rendered = new RenderedParagraph(readParagraphStyle(paragraph, marker, styles), runs);
            if (!rendered.plainText().trim().isEmpty()) blocks.add(0, RenderedBlock.paragraph(rendered));
        }
        return blocks;
    }

    private static void collectParagraphRuns(Node rootParagraph, Node node, Map<String, String> relationships,
                                             String localHost, List<RenderedRun> runs, List<RenderedBlock> sideBlocks,
                                             DocxStyles styles, TextStyle paragraphRunStyle, NoteDefinitions notes) {
        if (node == null) return;
        String local = node.getLocalName();
        String name = node.getNodeName();

        if (node != rootParagraph && ("p".equals(local) || "w:p".equals(name)
                || "tbl".equals(local) || "w:tbl".equals(name))) {
            return;
        }

        if ("t".equals(local) || "w:t".equals(name)) {
            String text = node.getTextContent();
            if (text != null && !text.isEmpty()) runs.add(RenderedRun.text(text, currentRunStyle(node, styles, paragraphRunStyle)));
            return;
        }
        if ("tab".equals(local) || "w:tab".equals(name)) {
            runs.add(RenderedRun.text("\t", currentRunStyle(node, styles, paragraphRunStyle)));
            return;
        }
        if ("br".equals(local) || "w:br".equals(name) || "cr".equals(local) || "w:cr".equals(name)) {
            if (!isPageBreak(node)) runs.add(RenderedRun.text("\n", currentRunStyle(node, styles, paragraphRunStyle)));
            return;
        }
        if ("footnoteReference".equals(local) || "w:footnoteReference".equals(name)) {
            RenderedRun ref = notes != null ? notes.reference("footnote", DocumentArchiveUtils.attr(node, "w:id", "id")) : null;
            if (ref != null) runs.add(ref);
            return;
        }
        if ("endnoteReference".equals(local) || "w:endnoteReference".equals(name)) {
            RenderedRun ref = notes != null ? notes.reference("endnote", DocumentArchiveUtils.attr(node, "w:id", "id")) : null;
            if (ref != null) runs.add(ref);
            return;
        }
        if (isVmlLine(node)) {
            RenderedHorizontalLine line = horizontalLineFromVml(node);
            if (line != null) {
                sideBlocks.add(RenderedBlock.horizontalLine(line));
            } else {
                sideBlocks.add(RenderedBlock.unsupported(HORIZONTAL_LINE_PLACEHOLDER));
            }
            return;
        }
        if (isDrawingInline(node)) {
            RenderedImage image = imageForDrawingContainer(node, relationships, localHost, false);
            if (image != null) sideBlocks.add(RenderedBlock.image(image));
            return;
        }
        if (isDrawingAnchor(node)) {
            RenderedImage image = imageForDrawingContainer(node, relationships, localHost, true);
            if (image != null) sideBlocks.add(RenderedBlock.image(image));
            else sideBlocks.add(RenderedBlock.unsupported("Floating drawing is shown in reading order when supported."));
            return;
        }
        if ("blip".equals(local) || "a:blip".equals(name)) {
            RenderedImage image = imageForBlip(node, relationships, localHost, isInsideDrawingAnchor(node));
            if (image != null) sideBlocks.add(RenderedBlock.image(image));
            return;
        }
        if ("txbxContent".equals(local) || "w:txbxContent".equals(name)) {
            sideBlocks.add(RenderedBlock.unsupported("Text box content is shown in reading order when supported."));
            return;
        }

        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            collectParagraphRuns(rootParagraph, children.item(i), relationships, localHost, runs, sideBlocks, styles, paragraphRunStyle, notes);
        }
    }

    private static ArrayList<RenderedRun> coalesceAdjacentTextRuns(List<RenderedRun> source) {
        ArrayList<RenderedRun> out = new ArrayList<>();
        if (source == null || source.isEmpty()) return out;
        for (RenderedRun run : source) {
            if (run == null) continue;
            int lastIndex = out.size() - 1;
            if (lastIndex >= 0 && canCoalesceRuns(out.get(lastIndex), run)) {
                RenderedRun previous = out.remove(lastIndex);
                out.add(RenderedRun.text(previous.text + run.text, previous.style));
            } else {
                out.add(run);
            }
        }
        return out;
    }

    private static boolean canCoalesceRuns(RenderedRun a, RenderedRun b) {
        if (a == null || b == null) return false;
        if (a.hasAnchorRange() || b.hasAnchorRange() || a.hasLink() || b.hasLink()
                || a.hasElementId() || b.hasElementId()) return false;
        return sameTextStyle(a.style, b.style);
    }

    private static boolean sameTextStyle(TextStyle a, TextStyle b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return same(a.fontFamily, b.fontFamily)
                && same(a.fontSizePt, b.fontSizePt)
                && same(a.bold, b.bold)
                && same(a.italic, b.italic)
                && same(a.underline, b.underline)
                && same(a.strike, b.strike)
                && same(a.color, b.color)
                && same(a.backgroundColor, b.backgroundColor)
                && a.verticalAlign == b.verticalAlign;
    }

    private static boolean same(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    private static RenderedTable readTable(Node table, Map<String, String> relationships, String localHost,
                                           NumberingDefinitions numbering, ListCounterState listCounters, DocxStyles styles,
                                           NoteDefinitions notes) {
        ArrayList<List<MutableTableCell>> mutableRows = new ArrayList<>();
        Map<Integer, MutableTableCell> activeVerticalMerges = new LinkedHashMap<>();
        List<Float> columnWidths = readGridWidthPercents(table);
        float totalGridPt = readGridTotalPt(table);
        Float tableWidthPercent = readTableWidthPercent(table);
        BorderSpec tableBorder = readTableBorders(table);

        NodeList rows = table.getChildNodes();
        for (int i = 0; i < rows.getLength(); i++) {
            Node row = rows.item(i);
            String local = row.getLocalName();
            String name = row.getNodeName();
            if (!"tr".equals(local) && !"w:tr".equals(name)) continue;
            ArrayList<MutableTableCell> rowOut = new ArrayList<>();
            int gridColumn = 0;
            NodeList cells = row.getChildNodes();
            for (int j = 0; j < cells.getLength(); j++) {
                Node cell = cells.item(j);
                String cl = cell.getLocalName();
                String cn = cell.getNodeName();
                if (!"tc".equals(cl) && !"w:tc".equals(cn)) continue;
                Node tcPr = directChildByLocalName(cell, "tcPr");
                int colSpan = readGridSpan(tcPr);
                VerticalMerge vMerge = readVerticalMerge(tcPr);

                if (vMerge == VerticalMerge.CONTINUE) {
                    MutableTableCell active = activeVerticalMerges.get(gridColumn);
                    if (active != null) {
                        active.rowSpan += 1;
                        gridColumn += Math.max(1, colSpan);
                        continue;
                    }
                    // Malformed continuation without a restart: keep its content rather than dropping it.
                } else {
                    clearActiveVerticalMerges(activeVerticalMerges, gridColumn, colSpan);
                }

                MutableTableCell renderedCell = readTableCell(cell, relationships, localHost, numbering, listCounters, styles,
                        colSpan, gridColumn, columnWidths, totalGridPt, tableBorder, notes);
                rowOut.add(renderedCell);
                if (vMerge == VerticalMerge.RESTART) {
                    for (int c = 0; c < renderedCell.colSpan; c++) activeVerticalMerges.put(gridColumn + c, renderedCell);
                }
                gridColumn += renderedCell.colSpan;
            }
            if (!rowOut.isEmpty()) mutableRows.add(rowOut);
        }

        ArrayList<List<RenderedTableCell>> rowsOut = new ArrayList<>();
        for (List<MutableTableCell> row : mutableRows) {
            ArrayList<RenderedTableCell> converted = new ArrayList<>();
            for (MutableTableCell cell : row) converted.add(cell.toRendered());
            rowsOut.add(converted);
        }
        return new RenderedTable(rowsOut, tableWidthPercent != null ? tableWidthPercent : 100f, columnWidths);
    }

    private static MutableTableCell readTableCell(Node cell, Map<String, String> relationships, String localHost,
                                                   NumberingDefinitions numbering, ListCounterState listCounters, DocxStyles styles,
                                                   int colSpan, int gridColumn, List<Float> columnWidths,
                                                   float totalGridPt, BorderSpec tableBorder, NoteDefinitions notes) {
        MutableTableCell out = new MutableTableCell();
        out.colSpan = Math.max(1, colSpan);
        Node tcPr = directChildByLocalName(cell, "tcPr");
        if (tcPr != null) {
            Node shd = directChildByLocalName(tcPr, "shd");
            String fill = DocumentArchiveUtils.attr(shd, "w:fill", "fill");
            if (isHexColor(fill)) out.backgroundColor = "#" + fill;
            BorderSpec cellBorder = readCellBorders(tcPr);
            BorderSpec effectiveBorder = cellBorder != null ? cellBorder : tableBorder;
            if (effectiveBorder != null) {
                out.borderVisible = effectiveBorder.visible;
                out.borderColor = effectiveBorder.color;
            } else {
                // OOXML semantics: with neither tblBorders nor tcBorders defined,
                // the table has no borders. Make that explicit so the renderer's
                // default cell border (a visible hairline) is not applied — this
                // is how Korean official-letter templates use an invisible table
                // as a layout grid for the sender/contact block.
                out.borderVisible = false;
            }
            out.widthPercent = readCellWidthPercent(tcPr, gridColumn, out.colSpan, columnWidths, totalGridPt);
            PaddingSpec padding = readTableCellPadding(tcPr);
            if (padding != null) {
                out.paddingTopPt = padding.topPt;
                out.paddingRightPt = padding.rightPt;
                out.paddingBottomPt = padding.bottomPt;
                out.paddingLeftPt = padding.leftPt;
            }
        }

        NodeList children = cell.getChildNodes();
        boolean hasBlock = false;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            String local = child.getLocalName();
            String name = child.getNodeName();
            if ("p".equals(local) || "w:p".equals(name)) {
                for (RenderedBlock block : readParagraphBlocks(child, relationships, localHost, numbering, listCounters, styles, notes)) {
                    out.blocks.add(block);
                    hasBlock = true;
                }
            } else if ("tbl".equals(local) || "w:tbl".equals(name)) {
                RenderedTable nested = readTable(child, relationships, localHost, numbering, listCounters, styles, notes);
                if (nested != null && !nested.rows.isEmpty()) {
                    out.blocks.add(RenderedBlock.table(nested));
                    hasBlock = true;
                }
            }
        }
        if (!hasBlock) out.blocks.add(RenderedBlock.paragraph(RenderedParagraph.of(RenderedRun.text(""))));
        return out;
    }

    private static int readGridSpan(Node tcPr) {
        Node gridSpan = directChildByLocalName(tcPr, "gridSpan");
        Integer span = intAttr(gridSpan, "w:val", "val");
        return span != null && span > 1 ? span : 1;
    }

    private static VerticalMerge readVerticalMerge(Node tcPr) {
        Node vMerge = directChildByLocalName(tcPr, "vMerge");
        if (vMerge == null) return VerticalMerge.NONE;
        String val = DocumentArchiveUtils.attr(vMerge, "w:val", "val");
        if (val == null || val.trim().isEmpty() || "continue".equalsIgnoreCase(val)) return VerticalMerge.CONTINUE;
        if ("restart".equalsIgnoreCase(val)) return VerticalMerge.RESTART;
        return VerticalMerge.NONE;
    }

    private static void clearActiveVerticalMerges(Map<Integer, MutableTableCell> active, int startColumn, int colSpan) {
        if (active == null || active.isEmpty()) return;
        int safeSpan = Math.max(1, colSpan);
        for (int c = 0; c < safeSpan; c++) active.remove(startColumn + c);
    }

    private static Float readTableWidthPercent(Node table) {
        Node tblPr = directChildByLocalName(table, "tblPr");
        Node tblW = directChildByLocalName(tblPr, "tblW");
        return readWidthPercent(tblW, -1f);
    }

    private static Float readCellWidthPercent(Node tcPr, int gridColumn, int colSpan, List<Float> columnWidths, float totalGridPt) {
        Node tcW = directChildByLocalName(tcPr, "tcW");
        Float explicit = readWidthPercent(tcW, totalGridPt);
        if (explicit != null) return explicit;
        if (columnWidths == null || columnWidths.isEmpty()) return null;
        float total = 0f;
        for (int i = 0; i < Math.max(1, colSpan); i++) {
            int index = gridColumn + i;
            if (index >= 0 && index < columnWidths.size() && columnWidths.get(index) != null) total += columnWidths.get(index);
        }
        return total > 0 ? total : null;
    }

    private static PaddingSpec readTableCellPadding(Node tcPr) {
        Node tcMar = directChildByLocalName(tcPr, "tcMar");
        if (tcMar == null) return null;
        Float top = tableMarginSidePt(tcMar, "top");
        Float right = tableMarginSidePt(tcMar, "right");
        Float bottom = tableMarginSidePt(tcMar, "bottom");
        Float left = tableMarginSidePt(tcMar, "left");
        return top != null || right != null || bottom != null || left != null
                ? new PaddingSpec(top, right, bottom, left)
                : null;
    }

    private static Float tableMarginSidePt(Node tcMar, String side) {
        Node node = directChildByLocalName(tcMar, side);
        if (node == null) return null;
        return twipsToPt(DocumentArchiveUtils.attr(node, "w:w", "w"));
    }

    private static Float readWidthPercent(Node widthNode, float totalGridPt) {
        if (widthNode == null) return null;
        String type = DocumentArchiveUtils.attr(widthNode, "w:type", "type");
        String value = DocumentArchiveUtils.attr(widthNode, "w:w", "w");
        if (value == null) return null;
        if ("pct".equalsIgnoreCase(type)) return pctWidthToPercent(value);
        if ((type == null || "dxa".equalsIgnoreCase(type)) && totalGridPt > 0) {
            Float pt = twipsToPt(value);
            if (pt != null && pt > 0) return Math.max(1f, Math.min(100f, (pt / totalGridPt) * 100f));
        }
        return null;
    }

    private static Float pctWidthToPercent(String value) {
        if (value == null) return null;
        String v = value.trim();
        try {
            if (v.endsWith("%")) return Math.max(1f, Math.min(100f, Float.parseFloat(v.substring(0, v.length() - 1))));
            return Math.max(1f, Math.min(100f, Float.parseFloat(v) / 50f));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<Float> readGridWidthPercents(Node table) {
        ArrayList<Float> widthsPt = new ArrayList<>();
        Node grid = directChildByLocalName(table, "tblGrid");
        for (Node gridCol : directChildrenByLocalName(grid, "gridCol")) {
            Float width = twipsToPt(DocumentArchiveUtils.attr(gridCol, "w:w", "w"));
            if (width != null && width > 0) widthsPt.add(width);
        }
        float total = 0f;
        for (Float width : widthsPt) total += width != null ? width : 0f;
        if (total <= 0f || widthsPt.isEmpty()) return new ArrayList<>();
        ArrayList<Float> pct = new ArrayList<>();
        for (Float width : widthsPt) pct.add(width != null && width > 0 ? (width / total) * 100f : null);
        return pct;
    }

    private static float readGridTotalPt(Node table) {
        float total = 0f;
        Node grid = directChildByLocalName(table, "tblGrid");
        for (Node gridCol : directChildrenByLocalName(grid, "gridCol")) {
            Float width = twipsToPt(DocumentArchiveUtils.attr(gridCol, "w:w", "w"));
            if (width != null && width > 0) total += width;
        }
        return total;
    }

    private static BorderSpec readTableBorders(Node table) {
        Node tblPr = directChildByLocalName(table, "tblPr");
        return readBorders(directChildByLocalName(tblPr, "tblBorders"));
    }

    private static BorderSpec readCellBorders(Node tcPr) {
        return readBorders(directChildByLocalName(tcPr, "tcBorders"));
    }

    private static BorderSpec readBorders(Node borders) {
        if (borders == null) return null;
        String[] sides = {"top", "left", "bottom", "right", "insideH", "insideV"};
        boolean sawExplicitNone = false;
        boolean sawVisible = false;
        String color = null;
        for (String side : sides) {
            Node border = directChildByLocalName(borders, side);
            if (border == null) continue;
            String val = DocumentArchiveUtils.attr(border, "w:val", "val");
            if ("nil".equalsIgnoreCase(val) || "none".equalsIgnoreCase(val)) {
                sawExplicitNone = true;
                continue;
            }
            sawVisible = true;
            String c = DocumentArchiveUtils.attr(border, "w:color", "color");
            if (isHexColor(c)) color = "#" + c;
        }
        if (sawVisible) return new BorderSpec(true, color);
        if (sawExplicitNone) return new BorderSpec(false, null);
        return null;
    }

    private static ParagraphStyle readParagraphStyle(Node paragraph, ListMarker marker, DocxStyles styles) {
        MutableParagraphStyle out = new MutableParagraphStyle();
        if (styles != null) {
            out.apply(readParagraphProperties(styles.defaultParagraphPr));
            String styleId = paragraphStyleId(paragraph);
            StyleDefinition style = styles.paragraphStyle(styleId);
            if (style != null) out.apply(style.paragraphStyle);
        }
        Node pPr = directChildByLocalName(paragraph, "pPr");
        out.apply(readParagraphProperties(pPr));
        if (marker != null) {
            if (marker.leftPt != null) out.marginLeftPt = marker.leftPt;
            if (marker.hangingPt != null) out.textIndentPt = -marker.hangingPt;
            out.listType = marker.type;
            out.listLevel = marker.level;
            out.listLabel = marker.label;
        }
        return out.toParagraphStyle();
    }

    private static ParagraphStyle readParagraphProperties(Node pPr) {
        ParagraphStyle.Builder style = new ParagraphStyle.Builder();
        if (pPr == null) return style.build();

        Node jc = directChildByLocalName(pPr, "jc");
        String align = DocumentArchiveUtils.attr(jc, "w:val", "val");
        if (align != null) {
            if ("center".equalsIgnoreCase(align)) style.alignment(ParagraphStyle.Alignment.CENTER);
            else if ("right".equalsIgnoreCase(align) || "end".equalsIgnoreCase(align)) style.alignment(ParagraphStyle.Alignment.RIGHT);
            else if ("both".equalsIgnoreCase(align) || "distribute".equalsIgnoreCase(align)) style.alignment(ParagraphStyle.Alignment.JUSTIFY);
        }

        Node ind = directChildByLocalName(pPr, "ind");
        Float left = twipsToPt(DocumentArchiveUtils.attr(ind, "w:left", "left"));
        Float right = twipsToPt(DocumentArchiveUtils.attr(ind, "w:right", "right"));
        Float firstLine = twipsToPt(DocumentArchiveUtils.attr(ind, "w:firstLine", "firstLine"));
        Float hanging = twipsToPt(DocumentArchiveUtils.attr(ind, "w:hanging", "hanging"));
        if (left != null) style.marginLeftPt(left);
        if (right != null) style.marginRightPt(right);
        if (firstLine != null) style.textIndentPt(firstLine);
        else if (hanging != null) style.textIndentPt(-hanging);

        Node spacing = directChildByLocalName(pPr, "spacing");
        Float before = twipsToPt(DocumentArchiveUtils.attr(spacing, "w:before", "before"));
        Float after = twipsToPt(DocumentArchiveUtils.attr(spacing, "w:after", "after"));
        if (before != null) style.marginTopPt(before);
        if (after != null) style.marginBottomPt(after);
        String line = DocumentArchiveUtils.attr(spacing, "w:line", "line");
        String lineRule = DocumentArchiveUtils.attr(spacing, "w:lineRule", "lineRule");
        if (line != null) {
            if (lineRule == null || "auto".equalsIgnoreCase(lineRule)) {
                try {
                    float v = Float.parseFloat(line);
                    if (v > 0) style.lineHeightMultiplier(v / 240f);
                } catch (Exception ignored) {}
            } else if ("exact".equalsIgnoreCase(lineRule) || "atLeast".equalsIgnoreCase(lineRule)) {
                Float linePt = twipsToPt(line);
                if (linePt != null && linePt > 0) style.lineHeightPt(linePt);
            }
        }

        Node shd = directChildByLocalName(pPr, "shd");
        String fill = DocumentArchiveUtils.attr(shd, "w:fill", "fill");
        if (isHexColor(fill)) style.backgroundColor("#" + fill);
        return style.build();
    }

    private static TextStyle paragraphRunStyle(Node paragraph, DocxStyles styles) {
        MutableTextStyle out = new MutableTextStyle();
        if (styles != null) {
            out.apply(readRunProperties(styles.defaultRunPr));
            StyleDefinition pStyle = styles.paragraphStyle(paragraphStyleId(paragraph));
            if (pStyle != null) out.apply(pStyle.runStyle);
        }
        return out.toTextStyle();
    }

    private static TextStyle currentRunStyle(Node node, DocxStyles styles, TextStyle paragraphRunStyle) {
        Node run = nearestAncestor(node, "r");
        return readRunStyle(run, styles, paragraphRunStyle);
    }

    private static TextStyle readRunStyle(Node run, DocxStyles styles, TextStyle paragraphRunStyle) {
        MutableTextStyle out = new MutableTextStyle();
        if (styles != null) out.apply(readRunProperties(styles.defaultRunPr));
        out.apply(paragraphRunStyle);
        Node props = directChildByLocalName(run, "rPr");
        String characterStyleId = runStyleId(props);
        if (styles != null) {
            StyleDefinition charStyle = styles.characterStyle(characterStyleId);
            if (charStyle != null) out.apply(charStyle.runStyle);
        }
        out.apply(readRunProperties(props));
        return out.toTextStyle();
    }

    private static TextStyle readRunProperties(Node props) {
        TextStyle.Builder style = new TextStyle.Builder();
        if (props == null) return style.build();

        Boolean bold = onOff(directChildByLocalName(props, "b"));
        Boolean italic = onOff(directChildByLocalName(props, "i"));
        Boolean underline = underlineOnOff(directChildByLocalName(props, "u"));
        Boolean strike = onOff(directChildByLocalName(props, "strike"));
        Boolean dstrike = onOff(directChildByLocalName(props, "dstrike"));
        if (bold != null) style.bold(bold);
        if (italic != null) style.italic(italic);
        if (underline != null) style.underline(underline);
        if (strike != null || dstrike != null) style.strike(Boolean.TRUE.equals(strike) || Boolean.TRUE.equals(dstrike));

        Node color = directChildByLocalName(props, "color");
        String colorVal = DocumentArchiveUtils.attr(color, "w:val", "val");
        if (isHexColor(colorVal)) style.color("#" + colorVal);

        Node highlight = directChildByLocalName(props, "highlight");
        String highlightVal = DocumentArchiveUtils.attr(highlight, "w:val", "val");
        String highlightCss = highlightColor(highlightVal);
        if (highlightCss != null) style.backgroundColor(highlightCss);

        Node sz = directChildByLocalName(props, "sz");
        Float size = halfPointsToPt(DocumentArchiveUtils.attr(sz, "w:val", "val"));
        if (size != null) style.fontSizePt(size);

        Node vertAlign = directChildByLocalName(props, "vertAlign");
        String valign = DocumentArchiveUtils.attr(vertAlign, "w:val", "val");
        if ("superscript".equalsIgnoreCase(valign)) style.verticalAlign(TextStyle.VerticalAlign.SUPERSCRIPT);
        else if ("subscript".equalsIgnoreCase(valign)) style.verticalAlign(TextStyle.VerticalAlign.SUBSCRIPT);

        Node rFonts = directChildByLocalName(props, "rFonts");
        String font = sanitizeFont(DocumentArchiveUtils.attr(rFonts, "w:ascii", "ascii"));
        if (font == null) font = sanitizeFont(DocumentArchiveUtils.attr(rFonts, "w:hAnsi", "hAnsi"));
        if (font == null) font = sanitizeFont(DocumentArchiveUtils.attr(rFonts, "w:eastAsia", "eastAsia"));
        if (font != null) style.fontFamily(font);
        return style.build();
    }

    private static RenderedImage imageForDrawingContainer(Node container, Map<String, String> relationships,
                                                          String localHost, boolean floating) {
        Node blip = firstDescendantByLocalName(container, "blip");
        return imageForBlip(blip, relationships, localHost, floating);
    }

    private static RenderedImage imageForBlip(Node blip, Map<String, String> relationships, String localHost, boolean floating) {
        if (blip == null) return null;
        String rid = DocumentArchiveUtils.attr(blip, "r:embed", "embed");
        if (rid == null) rid = DocumentArchiveUtils.attr(blip, "r:link", "link");
        if (rid == null) return null;
        String target = relationships.get(rid);
        if (target == null) return null;
        String src = "https://" + localHost + "/" + target;
        ImageSize size = readImageSizePt(blip);
        return new RenderedImage(src, DocumentArchiveUtils.fileNameFromPath(target), size.widthPt, size.heightPt, floating);
    }

    private static ImageSize readImageSizePt(Node blip) {
        Node inline = nearestAncestor(blip, "inline");
        Node anchor = nearestAncestor(blip, "anchor");
        Node container = inline != null ? inline : anchor;
        Node extent = container != null ? directChildByLocalName(container, "extent") : null;
        ImageSize size = imageSizeFromExtent(extent);
        if (size.isKnown()) return size.scaledToFit(MAX_DOCX_IMAGE_WIDTH_PT, MAX_DOCX_IMAGE_HEIGHT_PT);

        Node drawingExt = container != null ? firstDescendantByLocalName(container, "ext") : null;
        size = imageSizeFromExtent(drawingExt);
        if (size.isKnown()) return size.scaledToFit(MAX_DOCX_IMAGE_WIDTH_PT, MAX_DOCX_IMAGE_HEIGHT_PT);
        return ImageSize.unknown();
    }

    private static ImageSize imageSizeFromExtent(Node extent) {
        if (extent == null) return ImageSize.unknown();
        Float cx = emuToPt(DocumentArchiveUtils.attr(extent, "cx", "cx"));
        Float cy = emuToPt(DocumentArchiveUtils.attr(extent, "cy", "cy"));
        if (cx == null || cy == null || cx <= 0 || cy <= 0) return ImageSize.unknown();
        return new ImageSize(cx, cy);
    }

    private static boolean isVmlLine(Node node) {
        if (node == null) return false;
        String name = node.getNodeName();
        String local = node.getLocalName();
        if ("v:line".equals(name)) return true;
        String namespace = node.getNamespaceURI();
        return "line".equals(local) && namespace != null && namespace.toLowerCase(Locale.US).contains("vml");
    }

    /**
     * Parses a VML {@code v:line} into a {@link RenderedHorizontalLine}. Only
     * (near-)horizontal lines are converted; a non-horizontal line returns null
     * so the caller falls back to a generic separator. {@code from}/{@code to}
     * are "x,y" point pairs from the page origin and {@code strokeweight} is a
     * point measure (e.g. ".99pt"). The page width comes from the current
     * document's section metrics so the line's width and side margins can be
     * expressed proportionally in the reflow layout.
     */
    private static RenderedHorizontalLine horizontalLineFromVml(Node node) {
        String from = DocumentArchiveUtils.attr(node, "from", "from");
        String to = DocumentArchiveUtils.attr(node, "to", "to");
        float[] fromXy = parsePointPair(from);
        float[] toXy = parsePointPair(to);

        float thickness = parsePtMeasure(DocumentArchiveUtils.attr(node, "strokeweight", "strokeweight"));
        String color = normalizeVmlColor(DocumentArchiveUtils.attr(node, "strokecolor", "strokecolor"));

        if (fromXy != null && toXy != null) {
            float dy = Math.abs(fromXy[1] - toXy[1]);
            float dx = Math.abs(fromXy[0] - toXy[0]);
            // Treat as horizontal when the vertical drift is small relative to
            // the horizontal span (and the span is meaningful). A vertical or
            // diagonal line is not representable as a horizontal rule here.
            if (dx > 1f && dy <= Math.max(2f, dx * 0.05f)) {
                float left = Math.min(fromXy[0], toXy[0]);
                float right = Math.max(fromXy[0], toXy[0]);
                Float pageWidth = currentDocumentPageWidthPt.get();
                float pageWidthPt = pageWidth != null ? pageWidth : -1f;
                return new RenderedHorizontalLine(thickness, left, right, pageWidthPt, color);
            }
            // Coordinates present but not horizontal: not a horizontal rule.
            return null;
        }
        // No usable coordinates: emit a generic full-width rule carrying only
        // the stroke weight/color hints (extent unknown).
        if (thickness > 0 || color != null) {
            return new RenderedHorizontalLine(thickness, -1f, -1f, -1f, color);
        }
        return null;
    }

    /** Parses a VML "x,y" point pair like "57.4pt,234.5pt" into {x, y} points. */
    private static float[] parsePointPair(String value) {
        if (value == null) return null;
        int comma = value.indexOf(',');
        if (comma <= 0 || comma >= value.length() - 1) return null;
        Float x = parsePtMeasureBoxed(value.substring(0, comma).trim());
        Float y = parsePtMeasureBoxed(value.substring(comma + 1).trim());
        if (x == null || y == null) return null;
        return new float[]{x, y};
    }

    /** Parses a VML length such as ".99pt", "57.4pt", or a bare number (points). */
    private static float parsePtMeasure(String value) {
        Float v = parsePtMeasureBoxed(value);
        return v != null ? v : -1f;
    }

    private static Float parsePtMeasureBoxed(String value) {
        if (value == null) return null;
        String s = value.trim().toLowerCase(Locale.US);
        if (s.isEmpty()) return null;
        float unit = 1f; // default: points
        if (s.endsWith("pt")) { s = s.substring(0, s.length() - 2).trim(); unit = 1f; }
        else if (s.endsWith("px")) { s = s.substring(0, s.length() - 2).trim(); unit = 0.75f; }
        else if (s.endsWith("in")) { s = s.substring(0, s.length() - 2).trim(); unit = 72f; }
        else if (s.endsWith("mm")) { s = s.substring(0, s.length() - 2).trim(); unit = 72f / 25.4f; }
        else if (s.endsWith("cm")) { s = s.substring(0, s.length() - 2).trim(); unit = 72f / 2.54f; }
        try {
            return Float.parseFloat(s) * unit;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeVmlColor(String value) {
        if (value == null) return null;
        String s = value.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("#")) {
            return s.length() == 7 ? s : null;
        }
        // Named/hex-without-hash colors: accept 6-hex-digit forms only.
        if (s.matches("[0-9a-fA-F]{6}")) return "#" + s;
        return null;
    }

    private static boolean isDrawingInline(Node node) {
        if (node == null) return false;
        return "inline".equals(node.getLocalName()) || "wp:inline".equals(node.getNodeName());
    }

    private static boolean isDrawingAnchor(Node node) {
        if (node == null) return false;
        return "anchor".equals(node.getLocalName()) || "wp:anchor".equals(node.getNodeName());
    }

    private static boolean isInsideDrawingAnchor(Node node) {
        return nearestAncestor(node, "anchor") != null;
    }

    private static boolean isPageBreak(Node node) {
        if (node == null) return false;
        if (!"br".equals(node.getLocalName()) && !"w:br".equals(node.getNodeName())) return false;
        String type = DocumentArchiveUtils.attr(node, "w:type", "type");
        return "page".equalsIgnoreCase(type);
    }

    private static boolean hasPageBreakBefore(Node paragraph) {
        Node pPr = directChildByLocalName(paragraph, "pPr");
        Node pageBreakBefore = directChildByLocalName(pPr, "pageBreakBefore");
        return Boolean.TRUE.equals(onOff(pageBreakBefore));
    }

    private static Node directChildByLocalName(Node node, String localName) {
        return DocumentArchiveUtils.firstDirectChildByLocalName(node, localName);
    }

    private static Node lastDescendantByLocalName(Node node, String localName) {
        if (node == null) return null;
        Node found = null;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (localName.equals(child.getLocalName()) || ("w:" + localName).equals(child.getNodeName())) found = child;
            Node nested = lastDescendantByLocalName(child, localName);
            if (nested != null) found = nested;
        }
        return found;
    }

    private static Node nearestAncestor(Node node, String localName) {
        Node cur = node;
        while (cur != null) {
            String nodeName = cur.getNodeName();
            if (localName.equals(cur.getLocalName()) || nodeName.endsWith(":" + localName)) return cur;
            cur = cur.getParentNode();
        }
        return null;
    }

    private static Node firstDescendantByLocalName(Node node, String localName) {
        if (node == null) return null;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            String nodeName = child.getNodeName();
            if (localName.equals(child.getLocalName()) || nodeName.endsWith(":" + localName)) return child;
            Node nested = firstDescendantByLocalName(child, localName);
            if (nested != null) return nested;
        }
        return null;
    }

    private static Float twipsToPt(String value) {
        if (value == null) return null;
        try { return Float.parseFloat(value) / 20f; } catch (Exception ignored) { return null; }
    }

    private static Float halfPointsToPt(String value) {
        if (value == null) return null;
        try { return Float.parseFloat(value) / 2f; } catch (Exception ignored) { return null; }
    }

    private static Float emuToPt(String value) {
        if (value == null) return null;
        try { return Float.parseFloat(value) / DOCX_EMU_PER_POINT; } catch (Exception ignored) { return null; }
    }

    private static Integer intAttr(Node node, String qualified, String local) {
        String value = DocumentArchiveUtils.attr(node, qualified, local);
        if (value == null) return null;
        try { return Integer.parseInt(value); } catch (Exception ignored) { return null; }
    }

    private static boolean isHexColor(String v) {
        return v != null && v.matches("[0-9A-Fa-f]{6}") && !"auto".equalsIgnoreCase(v);
    }

    private static String highlightColor(String v) {
        if (v == null) return null;
        String key = v.toLowerCase(Locale.ROOT);
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("yellow", "#ffff00");
        colors.put("green", "#00ff00");
        colors.put("cyan", "#00ffff");
        colors.put("magenta", "#ff00ff");
        colors.put("blue", "#0000ff");
        colors.put("red", "#ff0000");
        colors.put("darkyellow", "#808000");
        colors.put("darkgreen", "#008000");
        colors.put("darkcyan", "#008080");
        colors.put("darkmagenta", "#800080");
        colors.put("darkblue", "#000080");
        colors.put("darkred", "#800000");
        colors.put("lightgray", "#d9d9d9");
        colors.put("darkgray", "#808080");
        return colors.get(key);
    }

    private static String sanitizeFont(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) return null;
        return trimmed.replace('\n', ' ').replace('\r', ' ');
    }




    private static HeaderFooterDefinitions loadHeaderFooterDefinitions(ZipFile zip, Node body,
                                                                       Map<String, String> documentRelationships,
                                                                       String localHost, NumberingDefinitions numbering,
                                                                       DocxStyles styles, NoteDefinitions notes) {
        HeaderFooterDefinitions out = new HeaderFooterDefinitions();
        if (zip == null || body == null || documentRelationships == null || documentRelationships.isEmpty()) return out;
        ArrayList<Node> sections = new ArrayList<>();
        collectDescendantsByLocalName(body, "sectPr", sections);
        if (sections.isEmpty()) return out;
        ArrayList<HeaderFooterRef> headerRefs = new ArrayList<>();
        ArrayList<HeaderFooterRef> footerRefs = new ArrayList<>();
        for (Node section : sections) {
            for (Node ref : directChildrenByLocalName(section, "headerReference")) {
                String rid = DocumentArchiveUtils.attr(ref, "r:id", "id");
                String type = DocumentArchiveUtils.attr(ref, "w:type", "type");
                if (rid != null) headerRefs.add(new HeaderFooterRef(rid, type));
            }
            for (Node ref : directChildrenByLocalName(section, "footerReference")) {
                String rid = DocumentArchiveUtils.attr(ref, "r:id", "id");
                String type = DocumentArchiveUtils.attr(ref, "w:type", "type");
                if (rid != null) footerRefs.add(new HeaderFooterRef(rid, type));
            }
        }
        readHeaderFooterRefs(zip, headerRefs, documentRelationships, localHost, numbering, styles, notes, out.headers);
        readHeaderFooterRefs(zip, footerRefs, documentRelationships, localHost, numbering, styles, notes, out.footers);
        return out;
    }

    private static void readHeaderFooterRefs(ZipFile zip, List<HeaderFooterRef> refs,
                                             Map<String, String> documentRelationships, String localHost,
                                             NumberingDefinitions numbering, DocxStyles styles, NoteDefinitions notes,
                                             ArrayList<RenderedBlock> out) {
        if (refs == null || refs.isEmpty() || out == null) return;
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (HeaderFooterRef ref : refs) {
            String path = documentRelationships.get(ref.relationshipId);
            if (path == null || seen.containsKey(path)) continue;
            seen.put(path, Boolean.TRUE);
            List<RenderedBlock> blocks = readHeaderFooterPart(zip, path, documentRelationships, localHost, numbering, styles, notes);
            if (blocks.isEmpty()) continue;
            out.addAll(blocks);
        }
    }

    private static List<RenderedBlock> readHeaderFooterPart(ZipFile zip, String path,
                                                            Map<String, String> documentRelationships,
                                                            String localHost, NumberingDefinitions numbering,
                                                            DocxStyles styles, NoteDefinitions notes) {
        ArrayList<RenderedBlock> blocks = new ArrayList<>();
        if (zip == null || path == null) return blocks;
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) return blocks;
        Map<String, String> relationships = new LinkedHashMap<>();
        if (documentRelationships != null) relationships.putAll(documentRelationships);
        relationships.putAll(loadRelationshipsForPart(zip, path));
        try (InputStream is = zip.getInputStream(entry)) {
            Document doc = DocumentArchiveUtils.secureDocumentBuilder().parse(is);
            Node root = doc.getDocumentElement();
            if (root == null) return blocks;
            ListCounterState counters = new ListCounterState();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                String local = child.getLocalName();
                String name = child.getNodeName();
                if ("p".equals(local) || "w:p".equals(name)) {
                    blocks.addAll(readParagraphBlocks(child, relationships, localHost, numbering, counters, styles, notes));
                } else if ("tbl".equals(local) || "w:tbl".equals(name)) {
                    RenderedTable table = readTable(child, relationships, localHost, numbering, counters, styles, notes);
                    if (table != null && !table.rows.isEmpty()) blocks.add(RenderedBlock.table(table));
                }
            }
        } catch (Exception ignored) {
            // Headers/footers are L3 fidelity enhancements. Broken parts must not block DOCX rendering.
        }
        return blocks;
    }

    private static Map<String, String> loadRelationshipsForPart(ZipFile zip, String partPath) {
        Map<String, String> relationships = new LinkedHashMap<>();
        if (zip == null || partPath == null) return relationships;
        String relsPath = relationshipsPathForPart(partPath);
        ZipEntry rels = relsPath != null ? zip.getEntry(relsPath) : null;
        if (rels == null) return relationships;
        try (InputStream is = zip.getInputStream(rels)) {
            Document relDoc = DocumentArchiveUtils.secureDocumentBuilder().parse(is);
            NodeList relsList = relDoc.getElementsByTagName("Relationship");
            if (relsList.getLength() == 0) relsList = relDoc.getElementsByTagNameNS("*", "Relationship");
            for (int i = 0; i < relsList.getLength(); i++) {
                Node r = relsList.item(i);
                String id = DocumentArchiveUtils.attr(r, "Id", "Id");
                String target = DocumentArchiveUtils.attr(r, "Target", "Target");
                String mode = DocumentArchiveUtils.attr(r, "TargetMode", "TargetMode");
                if (id == null || target == null || "External".equalsIgnoreCase(mode)) continue;
                relationships.put(id, resolveRelativeZipTarget(partPath, target));
            }
        } catch (Exception ignored) {}
        return relationships;
    }

    private static String relationshipsPathForPart(String partPath) {
        if (partPath == null) return null;
        int slash = partPath.lastIndexOf('/');
        if (slash < 0) return "_rels/" + partPath + ".rels";
        String dir = partPath.substring(0, slash);
        String file = partPath.substring(slash + 1);
        return dir + "/_rels/" + file + ".rels";
    }

    private static String resolveRelativeZipTarget(String partPath, String target) {
        if (target == null) return null;
        String path;
        if (target.startsWith("/")) {
            path = target.substring(1);
        } else {
            int slash = partPath != null ? partPath.lastIndexOf('/') : -1;
            String dir = slash >= 0 ? partPath.substring(0, slash + 1) : "";
            path = dir + target;
        }
        return DocumentArchiveUtils.normalizeZipPath(path);
    }

    private static String headerFooterTypeLabel(String type) {
        if (type == null || type.trim().isEmpty() || "default".equalsIgnoreCase(type)) return null;
        String key = type.trim().toLowerCase(Locale.ROOT);
        if ("first".equals(key)) return "First page";
        if ("even".equals(key)) return "Even pages";
        return null;
    }

    private static NoteDefinitions loadNoteDefinitions(ZipFile zip, Map<String, String> relationships,
                                                       String localHost, NumberingDefinitions numbering,
                                                       DocxStyles styles) {
        NoteDefinitions defs = new NoteDefinitions();
        readNotePart(zip, "word/footnotes.xml", "footnote", "footnote", defs, relationships, localHost, numbering, styles);
        readNotePart(zip, "word/endnotes.xml", "endnote", "endnote", defs, relationships, localHost, numbering, styles);
        return defs;
    }

    private static void readNotePart(ZipFile zip, String path, String elementName, String kind,
                                     NoteDefinitions defs, Map<String, String> relationships,
                                     String localHost, NumberingDefinitions numbering, DocxStyles styles) {
        ZipEntry entry = zip != null ? zip.getEntry(path) : null;
        if (entry == null || defs == null) return;
        try (InputStream is = zip.getInputStream(entry)) {
            Document doc = DocumentArchiveUtils.secureDocumentBuilder().parse(is);
            Node root = doc.getDocumentElement();
            if (root == null) return;
            for (Node noteNode : directChildrenByLocalName(root, elementName)) {
                String id = DocumentArchiveUtils.attr(noteNode, "w:id", "id");
                if (!isVisibleNoteId(id)) continue;
                ArrayList<RenderedBlock> blocks = new ArrayList<>();
                NodeList children = noteNode.getChildNodes();
                ListCounterState noteCounters = new ListCounterState();
                for (int i = 0; i < children.getLength(); i++) {
                    Node child = children.item(i);
                    String local = child.getLocalName();
                    String name = child.getNodeName();
                    if ("p".equals(local) || "w:p".equals(name)) {
                        blocks.addAll(readParagraphBlocks(child, relationships, localHost, numbering, noteCounters, styles, null));
                    } else if ("tbl".equals(local) || "w:tbl".equals(name)) {
                        RenderedTable table = readTable(child, relationships, localHost, numbering, noteCounters, styles, null);
                        if (table != null && !table.rows.isEmpty()) blocks.add(RenderedBlock.table(table));
                    }
                }
                if (blocks.isEmpty()) blocks.add(RenderedBlock.paragraph(RenderedParagraph.of(RenderedRun.text(""))));
                defs.add(new NoteEntry(kind, id, blocks));
            }
        } catch (Exception ignored) {
            // Footnotes/endnotes are L3 fidelity enhancements. Broken note parts must not block DOCX rendering.
        }
    }

    private static boolean isVisibleNoteId(String id) {
        if (id == null || id.trim().isEmpty()) return false;
        try {
            return Integer.parseInt(id.trim()) > 0;
        } catch (Exception ignored) {
            return true;
        }
    }

    private static DocxStyles loadStyleDefinitions(ZipFile zip) {
        DocxStyles styles = new DocxStyles();
        ZipEntry entry = zip != null ? zip.getEntry("word/styles.xml") : null;
        if (entry == null) return styles;
        try (InputStream is = zip.getInputStream(entry)) {
            Document doc = DocumentArchiveUtils.secureDocumentBuilder().parse(is);
            Node root = doc.getDocumentElement();
            if (root == null) return styles;

            Node docDefaults = directChildByLocalName(root, "docDefaults");
            Node rPrDefault = directChildByLocalName(docDefaults, "rPrDefault");
            styles.defaultRunPr = directChildByLocalName(rPrDefault, "rPr");
            Node pPrDefault = directChildByLocalName(docDefaults, "pPrDefault");
            styles.defaultParagraphPr = directChildByLocalName(pPrDefault, "pPr");

            for (Node styleNode : directChildrenByLocalName(root, "style")) {
                String id = DocumentArchiveUtils.attr(styleNode, "w:styleId", "styleId");
                String type = DocumentArchiveUtils.attr(styleNode, "w:type", "type");
                if (id == null || type == null) continue;
                Node basedOn = directChildByLocalName(styleNode, "basedOn");
                StyleDefinition def = new StyleDefinition(
                        id,
                        type,
                        DocumentArchiveUtils.attr(basedOn, "w:val", "val"),
                        directChildByLocalName(styleNode, "pPr"),
                        directChildByLocalName(styleNode, "rPr"));
                styles.add(def);
            }
            styles.resolveInheritance();
        } catch (Exception ignored) {
            // styles.xml is a fidelity enhancement. Broken styles must not block DOCX rendering.
        }
        return styles;
    }

    private static String paragraphStyleId(Node paragraph) {
        Node pPr = directChildByLocalName(paragraph, "pPr");
        Node pStyle = directChildByLocalName(pPr, "pStyle");
        return DocumentArchiveUtils.attr(pStyle, "w:val", "val");
    }

    private static String runStyleId(Node rPr) {
        Node rStyle = directChildByLocalName(rPr, "rStyle");
        return DocumentArchiveUtils.attr(rStyle, "w:val", "val");
    }

    private static Boolean onOff(Node node) {
        if (node == null) return null;
        String val = DocumentArchiveUtils.attr(node, "w:val", "val");
        if (val == null || val.trim().isEmpty()) return Boolean.TRUE;
        String v = val.trim().toLowerCase(Locale.ROOT);
        if ("0".equals(v) || "false".equals(v) || "off".equals(v) || "no".equals(v)) return Boolean.FALSE;
        return Boolean.TRUE;
    }

    private static Boolean underlineOnOff(Node node) {
        if (node == null) return null;
        String val = DocumentArchiveUtils.attr(node, "w:val", "val");
        if (val == null || val.trim().isEmpty()) return Boolean.TRUE;
        String v = val.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(v) || "0".equals(v) || "false".equals(v) || "off".equals(v)) return Boolean.FALSE;
        return Boolean.TRUE;
    }

    private static NumberingDefinitions loadNumberingDefinitions(ZipFile zip) {
        NumberingDefinitions defs = new NumberingDefinitions();
        ZipEntry entry = zip != null ? zip.getEntry("word/numbering.xml") : null;
        if (entry == null) return defs;
        try (InputStream is = zip.getInputStream(entry)) {
            Document doc = DocumentArchiveUtils.secureDocumentBuilder().parse(is);
            Node root = doc.getDocumentElement();
            if (root == null) return defs;

            for (Node abstractNum : directChildrenByLocalName(root, "abstractNum")) {
                String abstractId = DocumentArchiveUtils.attr(abstractNum, "w:abstractNumId", "abstractNumId");
                if (abstractId == null) continue;
                for (Node lvl : directChildrenByLocalName(abstractNum, "lvl")) {
                    Integer ilvl = intAttr(lvl, "w:ilvl", "ilvl");
                    if (ilvl == null || ilvl < 0) ilvl = 0;
                    Node fmtNode = directChildByLocalName(lvl, "numFmt");
                    Node textNode = directChildByLocalName(lvl, "lvlText");
                    Node pPr = directChildByLocalName(lvl, "pPr");
                    Node ind = directChildByLocalName(pPr, "ind");
                    Node rPr = directChildByLocalName(lvl, "rPr");
                    Node rFonts = directChildByLocalName(rPr, "rFonts");
                    String markerFont = sanitizeFont(DocumentArchiveUtils.attr(rFonts, "w:ascii", "ascii"));
                    if (markerFont == null) markerFont = sanitizeFont(DocumentArchiveUtils.attr(rFonts, "w:hAnsi", "hAnsi"));
                    if (markerFont == null) markerFont = sanitizeFont(DocumentArchiveUtils.attr(rFonts, "w:eastAsia", "eastAsia"));
                    NumberingLevel level = new NumberingLevel(
                            ilvl,
                            DocumentArchiveUtils.attr(fmtNode, "w:val", "val"),
                            DocumentArchiveUtils.attr(textNode, "w:val", "val"),
                            markerFont,
                            twipsToPt(DocumentArchiveUtils.attr(ind, "w:left", "left")),
                            twipsToPt(DocumentArchiveUtils.attr(ind, "w:hanging", "hanging")));
                    defs.addLevel(abstractId, level);
                }
            }

            for (Node num : directChildrenByLocalName(root, "num")) {
                String numId = DocumentArchiveUtils.attr(num, "w:numId", "numId");
                Node abstractIdNode = directChildByLocalName(num, "abstractNumId");
                String abstractId = DocumentArchiveUtils.attr(abstractIdNode, "w:val", "val");
                if (numId != null && abstractId != null) defs.bind(numId, abstractId);
            }
        } catch (Exception ignored) {
            // Numbering is a fidelity enhancement. Broken numbering.xml must not block DOCX fallback/rendering.
        }
        return defs;
    }

    private static List<Node> directChildrenByLocalName(Node node, String localName) {
        ArrayList<Node> out = new ArrayList<>();
        if (node == null) return out;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (localName.equals(child.getLocalName()) || ("w:" + localName).equals(child.getNodeName())) out.add(child);
        }
        return out;
    }


    private static void collectDescendantsByLocalName(Node node, String localName, List<Node> out) {
        if (node == null || out == null) return;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            String nodeName = child.getNodeName();
            if (localName.equals(child.getLocalName()) || nodeName.endsWith(":" + localName)) out.add(child);
            collectDescendantsByLocalName(child, localName, out);
        }
    }

    private static String paragraphNumId(Node paragraph) {
        Node pPr = directChildByLocalName(paragraph, "pPr");
        Node numPr = directChildByLocalName(pPr, "numPr");
        Node numId = directChildByLocalName(numPr, "numId");
        return DocumentArchiveUtils.attr(numId, "w:val", "val");
    }

    private static int paragraphLevel(Node paragraph) {
        Node pPr = directChildByLocalName(paragraph, "pPr");
        Node numPr = directChildByLocalName(pPr, "numPr");
        Node ilvl = directChildByLocalName(numPr, "ilvl");
        Integer level = intAttr(ilvl, "w:val", "val");
        if (level == null || level < 0) return 0;
        return Math.min(8, level);
    }

    private static String markerLabel(String pattern, String numId, NumberingDefinitions defs,
                                      int[] values, int level, NumberingLevel current) {
        String fallback = formattedCounter(values[level], current.format) + ".";
        if (pattern == null || pattern.trim().isEmpty()) return fallback;
        String label = pattern;
        for (int i = 0; i < values.length; i++) {
            if (!label.contains("%" + (i + 1))) continue;
            NumberingLevel levelDef = defs != null ? defs.level(numId, i) : null;
            String fmt = levelDef != null ? levelDef.format : current.format;
            int value = values[i] > 0 ? values[i] : 1;
            label = label.replace("%" + (i + 1), formattedCounter(value, fmt));
        }
        if (label.indexOf('%') >= 0) label = label.replaceAll("%[0-9]+", "").trim();
        return label.trim().isEmpty() ? fallback : label;
    }

    private static String formattedCounter(int value, String format) {
        int safe = Math.max(1, value);
        String fmt = format == null ? "decimal" : format.toLowerCase(Locale.ROOT);
        if ("lowerletter".equals(fmt) || "loweralpha".equals(fmt)) return alphaCounter(safe, false);
        if ("upperletter".equals(fmt) || "upperalpha".equals(fmt)) return alphaCounter(safe, true);
        if ("lowerroman".equals(fmt)) return romanCounter(safe).toLowerCase(Locale.ROOT);
        if ("upperroman".equals(fmt)) return romanCounter(safe);
        return Integer.toString(safe);
    }

    private static String alphaCounter(int value, boolean upper) {
        StringBuilder sb = new StringBuilder();
        int n = Math.max(1, value);
        while (n > 0) {
            n--;
            sb.insert(0, (char) ((upper ? 'A' : 'a') + (n % 26)));
            n /= 26;
        }
        return sb.toString();
    }

    private static String romanCounter(int value) {
        int n = Math.max(1, Math.min(3999, value));
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            while (n >= values[i]) {
                sb.append(numerals[i]);
                n -= values[i];
            }
        }
        return sb.toString();
    }

    private enum VerticalMerge { NONE, RESTART, CONTINUE }

    private static final class BorderSpec {
        final Boolean visible;
        final String color;
        BorderSpec(Boolean visible, String color) {
            this.visible = visible;
            this.color = color;
        }
    }

    private static final class PaddingSpec {
        final Float topPt;
        final Float rightPt;
        final Float bottomPt;
        final Float leftPt;

        PaddingSpec(Float topPt, Float rightPt, Float bottomPt, Float leftPt) {
            this.topPt = topPt;
            this.rightPt = rightPt;
            this.bottomPt = bottomPt;
            this.leftPt = leftPt;
        }
    }

    private static final class MutableTableCell {
        final ArrayList<RenderedBlock> blocks = new ArrayList<>();
        int colSpan = 1;
        int rowSpan = 1;
        String borderColor;
        Boolean borderVisible;
        String backgroundColor;
        Float widthPercent;
        Float paddingTopPt;
        Float paddingRightPt;
        Float paddingBottomPt;
        Float paddingLeftPt;

        RenderedTableCell toRendered() {
            RenderedTableCell.Builder builder = RenderedTableCell.builder()
                    .colSpan(colSpan)
                    .rowSpan(rowSpan);
            if (borderColor != null) builder.borderColor(borderColor);
            if (borderVisible != null) builder.borderVisible(borderVisible);
            if (backgroundColor != null) builder.backgroundColor(backgroundColor);
            if (widthPercent != null) builder.widthPercent(widthPercent);
            builder.paddingPt(paddingTopPt, paddingRightPt, paddingBottomPt, paddingLeftPt);
            for (RenderedBlock block : blocks) builder.addBlock(block);
            return builder.build();
        }
    }

    private static final class MutableParagraphStyle {
        ParagraphStyle.Alignment alignment;
        Float marginTopPt;
        Float marginBottomPt;
        Float marginLeftPt;
        Float marginRightPt;
        Float textIndentPt;
        Float lineHeightMultiplier;
        Float lineHeightPt;
        ParagraphStyle.ListType listType;
        Integer listLevel;
        String listLabel;
        String backgroundColor;

        void apply(ParagraphStyle s) {
            if (s == null || s.isDefault()) return;
            if (s.alignment != null && s.alignment != ParagraphStyle.Alignment.LEFT) alignment = s.alignment;
            if (s.marginTopPt != null) marginTopPt = s.marginTopPt;
            if (s.marginBottomPt != null) marginBottomPt = s.marginBottomPt;
            if (s.marginLeftPt != null) marginLeftPt = s.marginLeftPt;
            if (s.marginRightPt != null) marginRightPt = s.marginRightPt;
            if (s.textIndentPt != null) textIndentPt = s.textIndentPt;
            if (s.lineHeightMultiplier != null) lineHeightMultiplier = s.lineHeightMultiplier;
            if (s.lineHeightPt != null) lineHeightPt = s.lineHeightPt;
            if (s.backgroundColor != null) backgroundColor = s.backgroundColor;
            if (s.listType != null && s.listType != ParagraphStyle.ListType.NONE) {
                listType = s.listType;
                listLevel = s.listLevel;
                listLabel = s.listLabel;
            }
        }

        ParagraphStyle toParagraphStyle() {
            ParagraphStyle.Builder b = new ParagraphStyle.Builder();
            if (alignment != null) b.alignment(alignment);
            if (marginTopPt != null) b.marginTopPt(marginTopPt);
            if (marginBottomPt != null) b.marginBottomPt(marginBottomPt);
            if (marginLeftPt != null) b.marginLeftPt(marginLeftPt);
            if (marginRightPt != null) b.marginRightPt(marginRightPt);
            if (textIndentPt != null) b.textIndentPt(textIndentPt);
            if (lineHeightMultiplier != null) b.lineHeightMultiplier(lineHeightMultiplier);
            if (lineHeightPt != null) b.lineHeightPt(lineHeightPt);
            if (backgroundColor != null) b.backgroundColor(backgroundColor);
            if (listType != null && listType != ParagraphStyle.ListType.NONE && listLabel != null) {
                b.list(listType, listLevel != null ? listLevel : 0, listLabel);
            }
            return b.build();
        }
    }

    private static final class MutableTextStyle {
        String fontFamily;
        Float fontSizePt;
        Boolean bold;
        Boolean italic;
        Boolean underline;
        Boolean strike;
        String color;
        String backgroundColor;
        TextStyle.VerticalAlign verticalAlign;

        void apply(TextStyle s) {
            if (s == null || s.isEmpty()) return;
            if (s.fontFamily != null) fontFamily = s.fontFamily;
            if (s.fontSizePt != null) fontSizePt = s.fontSizePt;
            if (s.bold != null) bold = s.bold;
            if (s.italic != null) italic = s.italic;
            if (s.underline != null) underline = s.underline;
            if (s.strike != null) strike = s.strike;
            if (s.color != null) color = s.color;
            if (s.backgroundColor != null) backgroundColor = s.backgroundColor;
            if (s.verticalAlign != null && s.verticalAlign != TextStyle.VerticalAlign.BASELINE) verticalAlign = s.verticalAlign;
        }

        TextStyle toTextStyle() {
            TextStyle.Builder b = new TextStyle.Builder();
            if (fontFamily != null) b.fontFamily(fontFamily);
            if (fontSizePt != null) b.fontSizePt(fontSizePt);
            if (bold != null) b.bold(bold);
            if (italic != null) b.italic(italic);
            if (underline != null) b.underline(underline);
            if (strike != null) b.strike(strike);
            if (color != null) b.color(color);
            if (backgroundColor != null) b.backgroundColor(backgroundColor);
            if (verticalAlign != null) b.verticalAlign(verticalAlign);
            return b.build();
        }
    }

    private static final class DocxStyles {
        Node defaultParagraphPr;
        Node defaultRunPr;
        private final Map<String, StyleDefinition> paragraphStyles = new LinkedHashMap<>();
        private final Map<String, StyleDefinition> characterStyles = new LinkedHashMap<>();

        void add(StyleDefinition def) {
            if (def == null || def.id == null) return;
            if ("paragraph".equalsIgnoreCase(def.type)) paragraphStyles.put(def.id, def);
            else if ("character".equalsIgnoreCase(def.type)) characterStyles.put(def.id, def);
        }

        StyleDefinition paragraphStyle(String id) { return id != null ? paragraphStyles.get(id) : null; }
        StyleDefinition characterStyle(String id) { return id != null ? characterStyles.get(id) : null; }

        void resolveInheritance() {
            for (StyleDefinition def : paragraphStyles.values()) resolve(def, paragraphStyles);
            for (StyleDefinition def : characterStyles.values()) resolve(def, characterStyles);
        }

        private void resolve(StyleDefinition def, Map<String, StyleDefinition> family) {
            if (def == null || def.resolved) return;
            if (def.resolving) {
                def.paragraphStyle = readParagraphProperties(def.pPr);
                def.runStyle = readRunProperties(def.rPr);
                def.resolved = true;
                def.resolving = false;
                return;
            }
            def.resolving = true;
            MutableParagraphStyle paragraph = new MutableParagraphStyle();
            MutableTextStyle run = new MutableTextStyle();
            StyleDefinition parent = def.basedOn != null ? family.get(def.basedOn) : null;
            if (parent != null && parent != def) {
                resolve(parent, family);
                paragraph.apply(parent.paragraphStyle);
                run.apply(parent.runStyle);
            }
            paragraph.apply(readParagraphProperties(def.pPr));
            run.apply(readRunProperties(def.rPr));
            def.paragraphStyle = paragraph.toParagraphStyle();
            def.runStyle = run.toTextStyle();
            def.resolved = true;
            def.resolving = false;
        }
    }

    private static final class StyleDefinition {
        final String id;
        final String type;
        final String basedOn;
        final Node pPr;
        final Node rPr;
        ParagraphStyle paragraphStyle;
        TextStyle runStyle;
        boolean resolving;
        boolean resolved;

        StyleDefinition(String id, String type, String basedOn, Node pPr, Node rPr) {
            this.id = id;
            this.type = type;
            this.basedOn = basedOn;
            this.pPr = pPr;
            this.rPr = rPr;
            this.paragraphStyle = ParagraphStyle.normal();
            this.runStyle = TextStyle.plain();
        }
    }

    private static final class NumberingDefinitions {
        private final Map<String, String> numToAbstract = new LinkedHashMap<>();
        private final Map<String, Map<Integer, NumberingLevel>> levelsByAbstract = new LinkedHashMap<>();

        void bind(String numId, String abstractId) { numToAbstract.put(numId, abstractId); }
        void addLevel(String abstractId, NumberingLevel level) {
            Map<Integer, NumberingLevel> levels = levelsByAbstract.get(abstractId);
            if (levels == null) {
                levels = new LinkedHashMap<>();
                levelsByAbstract.put(abstractId, levels);
            }
            levels.put(level.level, level);
        }
        NumberingLevel level(String numId, int level) {
            if (numId == null) return null;
            String abstractId = numToAbstract.get(numId);
            if (abstractId == null && levelsByAbstract.containsKey(numId)) abstractId = numId;
            Map<Integer, NumberingLevel> levels = levelsByAbstract.get(abstractId);
            if (levels == null) return null;
            NumberingLevel out = levels.get(level);
            if (out == null) out = levels.get(0);
            return out;
        }
    }

    private static final class NumberingLevel {
        final int level;
        final String format;
        final String text;
        final String markerFont;
        final Float leftPt;
        final Float hangingPt;
        NumberingLevel(int level, String format, String text, String markerFont, Float leftPt, Float hangingPt) {
            this.level = Math.max(0, level);
            this.format = format != null ? format : "decimal";
            this.text = text;
            this.markerFont = markerFont;
            this.leftPt = leftPt;
            this.hangingPt = hangingPt;
        }
        boolean bullet() { return "bullet".equalsIgnoreCase(format); }
    }

    private static final class ListMarker {
        final ParagraphStyle.ListType type;
        final int level;
        final String label;
        final Float leftPt;
        final Float hangingPt;
        ListMarker(ParagraphStyle.ListType type, int level, String label, Float leftPt, Float hangingPt) {
            this.type = type;
            this.level = Math.max(0, level);
            this.label = label;
            this.leftPt = leftPt;
            this.hangingPt = hangingPt;
        }
    }

    private static final class ListCounterState {
        private final Map<String, int[]> counters = new LinkedHashMap<>();
        ListMarker next(Node paragraph, NumberingDefinitions defs) {
            String numId = paragraphNumId(paragraph);
            if (numId == null || defs == null) return null;
            int level = paragraphLevel(paragraph);
            NumberingLevel def = defs.level(numId, level);
            if (def == null) return null;
            if (def.bullet()) {
                String label = normalizedBulletLabel(def.text, def.markerFont, level);
                return new ListMarker(ParagraphStyle.ListType.BULLET, level, label, def.leftPt, def.hangingPt);
            }
            int[] values = counters.get(numId);
            if (values == null) {
                values = new int[9];
                counters.put(numId, values);
            }
            values[level] = Math.max(0, values[level]) + 1;
            for (int i = level + 1; i < values.length; i++) values[i] = 0;
            String label = markerLabel(def.text, numId, defs, values, level, def);
            return new ListMarker(ParagraphStyle.ListType.ORDERED, level, label, def.leftPt, def.hangingPt);
        }
    }

    private static String normalizedBulletLabel(String raw, String markerFont, int level) {
        String text = raw != null ? raw.trim() : "";
        String font = markerFont != null ? markerFont.toLowerCase(Locale.ROOT) : "";
        if (text.isEmpty()) return standardBulletForLevel(level);

        int cp = text.codePointAt(0);
        boolean privateUse = cp >= 0xE000 && cp <= 0xF8FF;
        boolean symbolFont = font.contains("symbol") || font.contains("wingdings") || font.contains("webdings");
        if (privateUse || symbolFont) {
            if (cp == 0xF0B7 || cp == 0x00B7) return "\u2022";
            if (cp == 0xF0A7) return "\u25AA";
            if (cp == 0xF0D8) return "\u25C6";
            return standardBulletForLevel(level);
        }
        if ("o".equalsIgnoreCase(text)) return "\u25E6";
        if ("\u00B7".equals(text)) return "\u2022";
        return text;
    }

    private static String standardBulletForLevel(int level) {
        int safe = Math.max(0, level);
        if (safe % 3 == 1) return "\u25E6";
        if (safe % 3 == 2) return "\u25AA";
        return "\u2022";
    }



    private static final class HeaderFooterDefinitions {
        final ArrayList<RenderedBlock> headers = new ArrayList<>();
        final ArrayList<RenderedBlock> footers = new ArrayList<>();
        List<RenderedBlock> headerBlocks() { return headers; }
        List<RenderedBlock> footerBlocks() { return footers; }
    }

    private static final class HeaderFooterRef {
        final String relationshipId;
        final String type;
        HeaderFooterRef(String relationshipId, String type) {
            this.relationshipId = relationshipId;
            this.type = type;
        }
    }

    private static final class PageAccumulator {
        private final ArrayList<RenderedPage> pages;
        private final PageMetrics metrics;
        private final int blockBudget;
        private final HeaderFooterDefinitions headerFooters;
        private RenderedPage.Builder current;
        private int budgetUsed;
        private boolean pageHasContent;

        PageAccumulator(ArrayList<RenderedPage> pages, PageMetrics metrics, int blockBudget,
                        HeaderFooterDefinitions headerFooters) {
            this.pages = pages;
            this.metrics = metrics;
            this.blockBudget = Math.max(4, blockBudget);
            this.headerFooters = headerFooters;
            this.current = pageBuilder(pages != null ? pages.size() : 0, metrics, headerFooters);
        }

        void add(RenderedBlock block) {
            if (block == null) return;
            current.addBlock(block);
            budgetUsed += estimateBlockCost(block);
            pageHasContent = true;
            if (budgetUsed >= blockBudget) breakPage();
        }

        void breakPage() {
            if (!pageHasContent) return;
            pages.add(current.build());
            current = pageBuilder(pages.size(), metrics, headerFooters);
            budgetUsed = 0;
            pageHasContent = false;
        }

        void finish() {
            if (pageHasContent || pages.isEmpty()) pages.add(current.build());
        }

        private int estimateBlockCost(RenderedBlock block) {
            if (block == null) return 0;
            if (block.type == RenderedBlock.Type.TABLE) return 4;
            if (block.type == RenderedBlock.Type.IMAGE) return 3;
            return 1;
        }
    }

    private static final class NoteDefinitions {
        private final Map<String, NoteEntry> entries = new LinkedHashMap<>();
        private final ArrayList<NoteReference> references = new ArrayList<>();
        private final Map<String, String> labelsByKey = new LinkedHashMap<>();

        void add(NoteEntry entry) {
            if (entry == null) return;
            entries.put(entry.key(), entry);
        }

        RenderedRun reference(String kind, String id) {
            if (!isVisibleNoteId(id)) return null;
            String key = NoteEntry.key(kind, id);
            NoteEntry entry = entries.get(key);
            if (entry == null) return null;
            String label = labelsByKey.get(key);
            if (label == null) {
                label = Integer.toString(labelsByKey.size() + 1);
                labelsByKey.put(key, label);
                references.add(new NoteReference(key, label));
            }
            return RenderedRun.link("[" + label + "]", superscriptStyle(), "#rw-" + kind + "-" + safeHtmlId(id),
                    "rw-" + kind + "-ref-" + safeHtmlId(id));
        }

        List<RenderedBlock> referencedBlocks() {
            ArrayList<RenderedBlock> footnotes = new ArrayList<>();
            ArrayList<RenderedBlock> endnotes = new ArrayList<>();
            for (NoteReference ref : references) {
                NoteEntry entry = entries.get(ref.key);
                if (entry == null) continue;
                List<RenderedBlock> target = "endnote".equals(entry.kind) ? endnotes : footnotes;
                target.addAll(entry.renderedWithMarker(ref.label));
            }
            ArrayList<RenderedBlock> out = new ArrayList<>();
            if (!footnotes.isEmpty()) {
                out.add(noteHeading("Footnotes"));
                out.addAll(footnotes);
            }
            if (!endnotes.isEmpty()) {
                out.add(noteHeading("Endnotes"));
                out.addAll(endnotes);
            }
            return out;
        }
    }

    private static final class NoteReference {
        final String key;
        final String label;
        NoteReference(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    private static final class NoteEntry {
        final String kind;
        final String id;
        final List<RenderedBlock> blocks;

        NoteEntry(String kind, String id, List<RenderedBlock> blocks) {
            this.kind = kind != null ? kind : "footnote";
            this.id = id != null ? id : "0";
            this.blocks = blocks != null ? blocks : new ArrayList<RenderedBlock>();
        }

        String key() { return key(kind, id); }
        static String key(String kind, String id) { return (kind != null ? kind : "footnote") + ":" + (id != null ? id : "0"); }

        List<RenderedBlock> renderedWithMarker(String label) {
            ArrayList<RenderedBlock> out = new ArrayList<>();
            RenderedRun marker = RenderedRun.link(label + ". ", TextStyle.plain(),
                    "#rw-" + kind + "-ref-" + safeHtmlId(id), "rw-" + kind + "-" + safeHtmlId(id));
            boolean markerPlaced = false;
            for (RenderedBlock block : blocks) {
                if (!markerPlaced && block != null && block.type == RenderedBlock.Type.PARAGRAPH && block.paragraph != null) {
                    ArrayList<RenderedRun> runs = new ArrayList<>();
                    runs.add(marker);
                    runs.addAll(block.paragraph.runs);
                    out.add(RenderedBlock.paragraph(new RenderedParagraph(block.paragraph.style, runs)));
                    markerPlaced = true;
                } else if (block != null) {
                    out.add(block);
                }
            }
            if (!markerPlaced) out.add(0, RenderedBlock.paragraph(RenderedParagraph.of(marker)));
            return out;
        }
    }

    private static RenderedBlock noteHeading(String text) {
        return sectionHeading(text);
    }

    private static RenderedBlock sectionHeading(String text) {
        TextStyle headingStyle = new TextStyle.Builder().bold(true).build();
        ParagraphStyle headingParagraph = new ParagraphStyle.Builder().marginTopPt(14f).marginBottomPt(6f).build();
        return RenderedBlock.paragraph(new RenderedParagraph(headingParagraph,
                java.util.Collections.singletonList(RenderedRun.text(text, headingStyle))));
    }

    private static RenderedBlock sectionSubheading(String text) {
        TextStyle style = new TextStyle.Builder().italic(true).build();
        ParagraphStyle paragraph = new ParagraphStyle.Builder().marginTopPt(6f).marginBottomPt(4f).build();
        return RenderedBlock.paragraph(new RenderedParagraph(paragraph,
                java.util.Collections.singletonList(RenderedRun.text(text, style))));
    }

    private static TextStyle superscriptStyle() {
        return new TextStyle.Builder().verticalAlign(TextStyle.VerticalAlign.SUPERSCRIPT).build();
    }

    private static String safeHtmlId(String id) {
        if (id == null) return "0";
        String safe = id.trim().replaceAll("[^A-Za-z0-9_:.-]", "-");
        return safe.isEmpty() ? "0" : safe;
    }

    private static final class ImageSize {
        final Float widthPt;
        final Float heightPt;

        ImageSize(Float widthPt, Float heightPt) {
            this.widthPt = widthPt;
            this.heightPt = heightPt;
        }

        static ImageSize unknown() { return new ImageSize(null, null); }

        boolean isKnown() { return widthPt != null && heightPt != null && widthPt > 0 && heightPt > 0; }

        ImageSize scaledToFit(float maxWidthPt, float maxHeightPt) {
            if (!isKnown()) return this;
            float scale = 1f;
            if (maxWidthPt > 0 && widthPt > maxWidthPt) scale = Math.min(scale, maxWidthPt / widthPt);
            if (maxHeightPt > 0 && heightPt > maxHeightPt) scale = Math.min(scale, maxHeightPt / heightPt);
            if (scale >= 1f) return this;
            return new ImageSize(widthPt * scale, heightPt * scale);
        }
    }
    private static final class PageMetrics {
        float widthPt = DEFAULT_PAGE_WIDTH_PT;
        float heightPt = DEFAULT_PAGE_HEIGHT_PT;
        float marginTopPt = DEFAULT_MARGIN_PT;
        float marginRightPt = DEFAULT_MARGIN_PT;
        float marginBottomPt = DEFAULT_MARGIN_PT;
        float marginLeftPt = DEFAULT_MARGIN_PT;
    }
}
