package com.textview.reader;

import com.textview.reader.document.render.ParagraphStyle;
import com.textview.reader.document.render.RenderedBlock;
import com.textview.reader.document.render.RenderedDocument;
import com.textview.reader.document.render.RenderedPage;
import com.textview.reader.document.render.RenderedParagraph;
import com.textview.reader.document.render.RenderedRun;
import com.textview.reader.document.render.RenderedTable;
import com.textview.reader.document.render.RenderedTableCell;
import com.textview.reader.document.render.TextStyle;
import com.textview.reader.util.HwpTextExtractor;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.ListHeaderForCell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.docinfo.BorderFill;
import kr.dogfoot.hwplib.object.docinfo.borderfill.EachBorder;
import kr.dogfoot.hwplib.object.docinfo.borderfill.BorderType;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlLine;
import kr.dogfoot.hwplib.object.bodytext.control.ControlSectionDefine;
import kr.dogfoot.hwplib.object.bodytext.control.sectiondefine.PageDef;
import com.textview.reader.document.render.RenderedHorizontalLine;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * HWP/HWPX -> RenderedDocument bridge for the 1.0.3 L3 fidelity cycle.
 *
 * Binary HWP remains text-first because hwplib's safe extraction path does not
 * expose stable page/layout metrics.  HWPX can preserve more visible structure
 * by reading section XML directly: paragraphs, basic alignment, line breaks,
 * tabs, and simple tables are converted into the shared paper-like renderer.
 */
final class DocumentHwpLayoutExtractor {
    private static final float DEFAULT_PAGE_WIDTH_PT = 595f;
    private static final float DEFAULT_PAGE_HEIGHT_PT = 842f;
    private static final float DEFAULT_MARGIN_TOP_PT = 54f;
    private static final float DEFAULT_MARGIN_RIGHT_PT = 50f;
    private static final float DEFAULT_MARGIN_BOTTOM_PT = 54f;
    private static final float DEFAULT_MARGIN_LEFT_PT = 50f;
    private static final long MAX_HWPX_SECTION_XML_BYTES = 8L * 1024L * 1024L;
    private static final int DEFAULT_PARAGRAPHS_PER_PAGE = 30;
    private static final int DEFAULT_TARGET_CHARS_PER_PAGE = 4800;

    private DocumentHwpLayoutExtractor() {}

    /**
     * Structural extraction of a binary HWP 5.x file via the hwplib object
     * model. Walks sections and paragraphs, emitting text paragraphs in reading
     * order and converting real {@code ControlTable} controls into
     * {@link RenderedTable}s with their actual column spans, proportional column
     * widths, and per-cell border visibility resolved from the document's
     * BorderFill list. Returns null when the document has no usable structure so
     * the caller can fall back to the heuristic/plain-text paths.
     */
    private static RenderedDocument extractBinaryHwpStructure(File file, String title, String plainText,
                                                              int paragraphsPerPage, int targetCharsPerPage)
            throws Exception {
        HWPFile hwp = HWPReader.fromFile(file);
        if (hwp == null || hwp.getBodyText() == null) return null;
        List<BorderFill> borderFills = hwp.getDocInfo() != null ? hwp.getDocInfo().getBorderFillList() : null;
        float pageWidthPt = hwpContentWidthPt(hwp);

        ArrayList<RenderedBlock> blocks = new ArrayList<>();
        boolean sawTable = false;
        for (Section section : hwp.getBodyText().getSectionList()) {
            if (section == null) continue;
            for (Paragraph paragraph : section) {
                if (paragraph == null) continue;
                if (paragraph.getControlList() != null) {
                    for (Control control : paragraph.getControlList()) {
                        if (control != null && control.getType() == ControlType.Table) {
                            RenderedTable table = renderedTableFromControl((ControlTable) control, borderFills, hwp);
                            if (table != null && !table.rows.isEmpty()) {
                                blocks.add(RenderedBlock.table(table));
                                sawTable = true;
                            }
                        } else if (control instanceof ControlLine) {
                            RenderedHorizontalLine line = horizontalLineFromControlLine(
                                    (ControlLine) control, pageWidthPt);
                            if (line != null) blocks.add(RenderedBlock.horizontalLine(line));
                        }
                    }
                }
                String paraText = hwpParagraphText(paragraph);
                if (paraText != null && !paraText.trim().isEmpty()) {
                    String headPrefix = hwpParagraphHeadPrefix(paragraph, hwp);
                    if (!headPrefix.isEmpty()) paraText = headPrefix + paraText;
                    TextStyle runStyle = hwpRunStyle(paragraph, hwp);
                    ParagraphStyle paraStyle = hwpParagraphStyle(paragraph, hwp);
                    RenderedParagraph rp = new RenderedParagraph(paraStyle,
                            java.util.Collections.singletonList(RenderedRun.text(paraText, runStyle)));
                    blocks.add(RenderedBlock.paragraph(rp));
                }
            }
        }

        // Only claim structural success when we actually recovered tables; a
        // text-only result is better served by the existing plain-text path
        // (which paginates and keeps the verified dogfoot extraction).
        if (!sawTable || blocks.isEmpty()) return null;

        RenderedPage.Builder page = RenderedPage.builder(0)
                .pageSizePt(DEFAULT_PAGE_WIDTH_PT, DEFAULT_PAGE_HEIGHT_PT)
                .marginsPt(DEFAULT_MARGIN_TOP_PT, DEFAULT_MARGIN_RIGHT_PT,
                        DEFAULT_MARGIN_BOTTOM_PT, DEFAULT_MARGIN_LEFT_PT);
        for (RenderedBlock block : blocks) page.addBlock(block);
        return RenderedDocument.builder("hwp")
                .title(title != null ? title : "HWP")
                .addPage(page.build())
                .plainText(plainText != null ? plainText : "")
                .build();
    }

    static RenderedTable renderedTableFromControl(ControlTable control, List<BorderFill> borderFills) {
        return renderedTableFromControl(control, borderFills, null);
    }

    static RenderedTable renderedTableFromControl(ControlTable control, List<BorderFill> borderFills, HWPFile hwp) {
        if (control == null || control.getRowList() == null) return null;

        // Determine the grid column count from the maximum (colIndex + colSpan)
        // so proportional widths can be computed across a stable column count.
        int gridColumns = 0;
        long totalWidthUnits = 0;
        for (Row row : control.getRowList()) {
            if (row == null || row.getCellList() == null) continue;
            long rowWidth = 0;
            int rowCols = 0;
            for (Cell cell : row.getCellList()) {
                ListHeaderForCell h = cell != null ? cell.getListHeader() : null;
                if (h == null) continue;
                rowCols = Math.max(rowCols, h.getColIndex() + Math.max(1, h.getColSpan()));
                rowWidth += Math.max(0, h.getWidth());
            }
            gridColumns = Math.max(gridColumns, rowCols);
            totalWidthUnits = Math.max(totalWidthUnits, rowWidth);
        }
        if (gridColumns <= 0) return null;

        ArrayList<List<RenderedTableCell>> rows = new ArrayList<>();
        for (Row row : control.getRowList()) {
            if (row == null || row.getCellList() == null) continue;
            ArrayList<RenderedTableCell> rendered = new ArrayList<>();
            for (Cell cell : row.getCellList()) {
                if (cell == null) continue;
                ListHeaderForCell h = cell.getListHeader();
                int colSpan = h != null ? Math.max(1, h.getColSpan()) : 1;
                int rowSpan = h != null ? Math.max(1, h.getRowSpan()) : 1;
                boolean bordered = cellHasVisibleBorder(h, borderFills);
                Boolean[] edges = cellBorderEdges(h, borderFills);
                RenderedTableCell.Builder builder = RenderedTableCell.builder()
                        .borderVisible(bordered)
                        .colSpan(colSpan)
                        .rowSpan(rowSpan);
                if (edges != null) builder.borderEdges(edges[0], edges[1], edges[2], edges[3]);
                if (h != null && totalWidthUnits > 0 && h.getWidth() > 0) {
                    builder.widthPercent((float) (h.getWidth() * 100.0 / totalWidthUnits));
                }
                // Preserve the authored cell height so empty layout cells (e.g. a
                // tall image-placeholder box) don't collapse to a single text line.
                // hwplib stores height in HWPUNIT (1pt = 100). Only apply a minimum
                // height for cells tall enough that collapsing would be visible.
                if (h != null && h.getHeight() > 0) {
                    float heightPt = (float) (h.getHeight() / 100.0);
                    if (heightPt >= 20f) builder.minHeightPt(heightPt);
                }
                java.util.List<RenderedBlock> cellBlocks = hwpCellParagraphs(cell, hwp);
                for (RenderedBlock cb : cellBlocks) builder.addBlock(cb);
                rendered.add(builder.build());
            }
            if (!rendered.isEmpty()) rows.add(rendered);
        }
        if (rows.isEmpty()) return null;
        return new RenderedTable(rows, 100f);
    }

    /** A cell shows a border when its resolved BorderFill has any non-None edge. */
    private static boolean cellHasVisibleBorder(ListHeaderForCell header, List<BorderFill> borderFills) {
        if (header == null || borderFills == null) return false;
        int id = header.getBorderFillId();
        // hwplib BorderFill ids are 1-based references into the list.
        if (id < 1 || id > borderFills.size()) return false;
        BorderFill bf = borderFills.get(id - 1);
        if (bf == null) return false;
        return edgeVisible(bf.getLeftBorder()) || edgeVisible(bf.getRightBorder())
                || edgeVisible(bf.getTopBorder()) || edgeVisible(bf.getBottomBorder());
    }

    /**
     * Resolve the four cell edges (top, right, bottom, left) from the cell's
     * BorderFill. Returns null when no BorderFill applies (so callers fall back
     * to the all-or-nothing borderVisible behaviour). Korean form templates
     * frequently use BorderFills that draw only a top and/or bottom rule with no
     * vertical edges; collapsing those to a single boolean produced a full box.
     */
    private static Boolean[] cellBorderEdges(ListHeaderForCell header, List<BorderFill> borderFills) {
        if (header == null || borderFills == null) return null;
        int id = header.getBorderFillId();
        if (id < 1 || id > borderFills.size()) return null;
        BorderFill bf = borderFills.get(id - 1);
        if (bf == null) return null;
        return new Boolean[] {
                edgeVisible(bf.getTopBorder()),
                edgeVisible(bf.getRightBorder()),
                edgeVisible(bf.getBottomBorder()),
                edgeVisible(bf.getLeftBorder())
        };
    }

    private static boolean edgeVisible(EachBorder edge) {
        if (edge == null) return false;
        BorderType type = edge.getType();
        return type != null && type != BorderType.None;
    }

    /**
     * Builds a run text style from a paragraph's first character shape: base
     * size (HWPUNIT, 1pt = 100) and bold flag, resolved through the document's
     * CharShape list. Falls back to the default HWP text style when no shape is
     * available.
     */
    private static TextStyle hwpRunStyle(Paragraph paragraph, HWPFile hwp) {
        try {
            if (paragraph.getCharShape() != null
                    && !paragraph.getCharShape().getPositonShapeIdPairList().isEmpty()
                    && hwp.getDocInfo() != null) {
                long shapeId = paragraph.getCharShape().getPositonShapeIdPairList().get(0).getShapeId();
                java.util.List<kr.dogfoot.hwplib.object.docinfo.CharShape> shapes =
                        hwp.getDocInfo().getCharShapeList();
                if (shapeId >= 0 && shapeId < shapes.size()) {
                    kr.dogfoot.hwplib.object.docinfo.CharShape cs = shapes.get((int) shapeId);
                    TextStyle.Builder b = new TextStyle.Builder().fontFamily("Malgun Gothic");
                    int base = cs.getBaseSize();
                    if (base > 0) b.fontSizePt(base / 100f);
                    else b.fontSizePt(11f);
                    if (cs.getProperty() != null && cs.getProperty().isBold()) b.bold(true);
                    if (cs.getProperty() != null && cs.getProperty().isItalic()) b.italic(true);
                    if (cs.getProperty() != null
                            && cs.getProperty().getUnderLineSort() != null
                            && cs.getProperty().getUnderLineSort()
                                    != kr.dogfoot.hwplib.object.docinfo.charshape.UnderLineSort.None) {
                        b.underline(true);
                    }
                    String color = hwpColorHex(cs.getCharColor());
                    // Skip pure black so the renderer's default foreground (which
                    // adapts to light/dark theme) is used instead of a hard #000000.
                    if (color != null && !"#000000".equals(color)) b.color(color);
                    return b.build();
                }
            }
        } catch (Exception ignored) {
            // fall through to default
        }
        return defaultHwpTextStyle();
    }

    /** Resolves a paragraph's horizontal alignment from its ParaShape. */
    private static ParagraphStyle hwpParagraphStyle(Paragraph paragraph, HWPFile hwp) {
        ParagraphStyle.Alignment alignment = ParagraphStyle.Alignment.LEFT;
        try {
            if (paragraph.getHeader() != null && hwp.getDocInfo() != null) {
                int paraShapeId = paragraph.getHeader().getParaShapeId();
                java.util.List<kr.dogfoot.hwplib.object.docinfo.ParaShape> paraShapes =
                        hwp.getDocInfo().getParaShapeList();
                if (paraShapeId >= 0 && paraShapeId < paraShapes.size()) {
                    kr.dogfoot.hwplib.object.docinfo.parashape.Alignment a =
                            paraShapes.get(paraShapeId).getProperty1().getAlignment();
                    alignment = mapHwpAlignment(a);
                }
            }
        } catch (Exception ignored) {
            // default left
        }
        return new ParagraphStyle.Builder().alignment(alignment).build();
    }

    private static ParagraphStyle.Alignment mapHwpAlignment(
            kr.dogfoot.hwplib.object.docinfo.parashape.Alignment a) {
        if (a == null) return ParagraphStyle.Alignment.LEFT;
        switch (a) {
            case Center: return ParagraphStyle.Alignment.CENTER;
            case Right: return ParagraphStyle.Alignment.RIGHT;
            case Justify:
            case Distribute: return ParagraphStyle.Alignment.JUSTIFY;
            case Left:
            default: return ParagraphStyle.Alignment.LEFT;
        }
    }

    /**
     * Page content width (left/right margins removed) in points, found from the
     * section definition's PageDef. HWP stores lengths in HWPUNIT (1pt = 100).
     * Returns -1 when no page definition is available.
     */
    private static float hwpContentWidthPt(HWPFile hwp) {
        try {
            for (Section section : hwp.getBodyText().getSectionList()) {
                if (section == null) continue;
                for (Paragraph paragraph : section) {
                    if (paragraph == null || paragraph.getControlList() == null) continue;
                    for (Control control : paragraph.getControlList()) {
                        if (control instanceof ControlSectionDefine) {
                            PageDef pd = ((ControlSectionDefine) control).getPageDef();
                            if (pd != null && pd.getPaperWidth() > 0) {
                                long content = pd.getPaperWidth() - pd.getLeftMargin() - pd.getRightMargin();
                                if (content <= 0) content = pd.getPaperWidth();
                                return content / 100f;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return -1f;
    }

    /**
     * Converts a horizontal GSO line control into a {@link RenderedHorizontalLine}.
     * Only near-horizontal lines (height small relative to width) are converted.
     * Width and left inset come from the control header (HWPUNIT) measured
     * against the page content width; thickness is taken from the header height
     * when meaningful. Returns null for vertical/diagonal lines.
     */
    private static RenderedHorizontalLine horizontalLineFromControlLine(ControlLine line, float pageWidthPt) {
        if (line == null || line.getHeader() == null) return null;
        long w = line.getHeader().getWidth();
        long h = line.getHeader().getHeight();
        if (w <= 0) return null;
        // Horizontal when the box is far wider than tall.
        if (h > 0 && h > w * 0.1f) return null;
        float widthPt = w / 100f;
        float leftPt = line.getHeader().getxOffset() / 100f;
        float thicknessPt = h > 0 ? Math.max(0.5f, h / 100f) : 1f;
        if (pageWidthPt > 0) {
            return new RenderedHorizontalLine(thicknessPt, leftPt, leftPt + widthPt, pageWidthPt, null);
        }
        // Unknown page width: emit a full-width rule carrying just the thickness.
        return new RenderedHorizontalLine(thicknessPt, -1f, -1f, -1f, null);
    }

    private static String hwpColorHex(kr.dogfoot.hwplib.object.etc.Color4Byte color) {
        if (color == null) return null;
        int r = color.getR() & 0xFF;
        int g = color.getG() & 0xFF;
        int b = color.getB() & 0xFF;
        return String.format(java.util.Locale.US, "#%02x%02x%02x", r, g, b);
    }

    private static String hwpParagraphText(Paragraph paragraph) {
        if (paragraph == null || paragraph.getText() == null) return "";
        try {
            String s = paragraph.getText().getNormalString(0);
            return s != null ? s : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Returns the paragraph-head (글머리표) prefix for a paragraph, or an empty
     * string when none applies. HWP stores list/bullet markers out-of-band: the
     * paragraph's ParaShape references a paraHeadId, which (when non-zero) points
     * into the Bullet list whose bulletChar is the visible marker (e.g. "-").
     * The body character stream does NOT contain this marker, so without this the
     * marker silently disappears (the "- 아 래 -" case rendered as "아 래 -").
     * A trailing space separates the marker from the text, matching HWP layout.
     */
    private static String hwpParagraphHeadPrefix(Paragraph paragraph, HWPFile hwp) {
        try {
            if (paragraph == null || paragraph.getHeader() == null || hwp == null
                    || hwp.getDocInfo() == null) {
                return "";
            }
            int paraShapeId = paragraph.getHeader().getParaShapeId();
            java.util.List<kr.dogfoot.hwplib.object.docinfo.ParaShape> paraShapes =
                    hwp.getDocInfo().getParaShapeList();
            if (paraShapeId < 0 || paraShapeId >= paraShapes.size()) return "";
            int headId = paraShapes.get(paraShapeId).getParaHeadId();
            // headId is a 1-based reference into the Bullet list; 0 means "no head".
            if (headId <= 0) return "";
            java.util.List<kr.dogfoot.hwplib.object.docinfo.Bullet> bullets =
                    hwp.getDocInfo().getBulletList();
            if (headId - 1 >= bullets.size()) return "";
            kr.dogfoot.hwplib.object.docinfo.Bullet bullet = bullets.get(headId - 1);
            if (bullet == null || bullet.getBulletChar() == null) return "";
            String marker = bullet.getBulletChar().toUTF16LEString();
            if (marker == null) return "";
            marker = marker.trim();
            if (marker.isEmpty()) return "";
            return marker + " ";
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Builds styled paragraphs for a table cell. When the document is available
     * each cell paragraph keeps its own char-shape size/bold and alignment; with
     * no document it falls back to a single default-styled paragraph of the
     * joined cell text. Empty cells yield one empty paragraph so the cell still
     * occupies its grid slot.
     */
    private static java.util.List<RenderedBlock> hwpCellParagraphs(Cell cell, HWPFile hwp) {
        java.util.ArrayList<RenderedBlock> out = new java.util.ArrayList<>();
        if (cell != null && cell.getParagraphList() != null && hwp != null) {
            for (Paragraph p : cell.getParagraphList()) {
                String t = hwpParagraphText(p);
                if (t == null || t.trim().isEmpty()) continue;
                out.add(RenderedBlock.paragraph(new RenderedParagraph(
                        hwpParagraphStyle(p, hwp),
                        java.util.Collections.singletonList(RenderedRun.text(t, hwpRunStyle(p, hwp))))));
            }
        }
        if (out.isEmpty()) {
            String text = hwpCellText(cell);
            out.add(RenderedBlock.paragraph(RenderedParagraph.of(
                    RenderedRun.text(text != null ? text : "", defaultHwpTextStyle()))));
        }
        return out;
    }

    private static String hwpCellText(Cell cell) {
        if (cell == null || cell.getParagraphList() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Paragraph p : cell.getParagraphList()) {
            String t = hwpParagraphText(p);
            if (t != null && !t.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(t);
            }
        }
        return sb.toString().trim();
    }

    static RenderedDocument extract(File file, String title, int paragraphsPerPage, int targetCharsPerPage) throws Exception {
        if (file == null) throw new IllegalArgumentException("No HWP file supplied");
        String name = file.getName() != null ? file.getName() : "HWP";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".hwpx")) {
            try {
                RenderedDocument hwpx = extractHwpx(file, title != null ? title : name, paragraphsPerPage, targetCharsPerPage);
                if (hwpx != null && hwpx.pageCount() > 0 && !isBlank(hwpx.plainText)) return hwpx;
            } catch (Exception ignored) {
                // Fall back to the verified dogfoot text path below.
            }
        }
        String text = HwpTextExtractor.read(file);
        if (lower.endsWith(".hwp")) {
            try {
                RenderedDocument structural = extractBinaryHwpStructure(
                        file, title != null ? title : name, text, paragraphsPerPage, targetCharsPerPage);
                if (structural != null && structural.pageCount() > 0) return structural;
            } catch (Throwable ignored) {
                // Fall back to the heuristic official-letter / plain-text paths.
            }
        }
        RenderedDocument officialLetter = tryBuildOfficialLetterDocument(text, title != null ? title : name, "hwp");
        if (officialLetter != null) return officialLetter;
        return fromPlainText(text, title != null ? title : name, "hwp", paragraphsPerPage, targetCharsPerPage);
    }

    private static RenderedDocument extractHwpx(File file, String title, int paragraphsPerPage, int targetCharsPerPage) throws Exception {
        ArrayList<RenderedBlock> blocks = new ArrayList<>();
        HwpxDocumentContext context;
        try (ZipFile zip = new ZipFile(file)) {
            context = loadHwpxDocumentContext(zip);
            List<String> sectionPaths = findHwpxSectionPaths(zip);
            if (sectionPaths.isEmpty()) throw new IllegalArgumentException("No HWPX section XML found");
            for (String path : sectionPaths) {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null || entry.isDirectory()) continue;
                long size = entry.getSize();
                if (size > MAX_HWPX_SECTION_XML_BYTES) {
                    throw new IllegalArgumentException("HWPX section XML too large: " + path);
                }
                byte[] xml;
                try (InputStream is = zip.getInputStream(entry)) {
                    xml = readAllBytesWithLimit(is, MAX_HWPX_SECTION_XML_BYTES);
                }
                Document doc = DocumentArchiveUtils.secureDocumentBuilder().parse(new ByteArrayInputStream(xml));
                ArrayList<RenderedBlock> sectionBlocks = new ArrayList<>();
                appendHwpxBlocks(doc.getDocumentElement(), sectionBlocks, false, context);
                blocks.addAll(sectionBlocks);
            }
        }
        if (blocks.isEmpty()) throw new IllegalArgumentException("HWPX contains no renderable blocks");
        return buildDocument(title, "hwpx", blocks, paragraphsPerPage, targetCharsPerPage,
                context != null ? context.pageMetrics : PageMetrics.defaults());
    }


    private static final String HWP_OFFICIAL_LETTER_FORMAT = "hwp-official-letter";
    private static final String HWP_OFFICIAL_HR_SENTINEL = "__RW_HWP_OFFICIAL_HR__";

    private static RenderedDocument tryBuildOfficialLetterDocument(String text, String title, String originalFormat) {
        String normalized = normalizeOfficialLetterText(text);
        if (!looksLikeKoreanOfficialLetter(normalized)) return null;

        ArrayList<RenderedBlock> blocks = new ArrayList<>();
        blocks.add(officialParagraph("공   문", ParagraphStyle.Alignment.CENTER, 22f, false, 18f, 22f, 0f));
        blocks.add(officialParagraph("문서번호 : " + officialField(normalized, "문서번호"), ParagraphStyle.Alignment.LEFT, 8.8f, false, 0f, 2f, 0f));
        blocks.add(officialParagraph("시행일자 : " + officialField(normalized, "시행일자"), ParagraphStyle.Alignment.LEFT, 8.8f, false, 0f, 2f, 0f));
        blocks.add(officialParagraph("수    신 : " + officialField(normalized, "수신"), ParagraphStyle.Alignment.LEFT, 8.8f, false, 0f, 2f, 0f));
        blocks.add(officialParagraph("참    조 : " + officialField(normalized, "참조"), ParagraphStyle.Alignment.LEFT, 8.8f, false, 0f, 18f, 0f));

        String subject = officialField(normalized, "제목");
        blocks.add(officialParagraph("제   목 : " + subject, ParagraphStyle.Alignment.LEFT, 11.2f, true, 0f, 4f, 0f));
        blocks.add(RenderedBlock.unsupported(HWP_OFFICIAL_HR_SENTINEL));

        String greeting = officialLineContaining(normalized, "귀사의 무궁한 발전");
        if (greeting.isEmpty()) greeting = "귀사의 무궁한 발전을 기원합니다.";
        blocks.add(officialParagraph(greeting, ParagraphStyle.Alignment.LEFT, 8.8f, false, 12f, 52f, 0f));

        blocks.add(officialParagraph(officialNumberedLine(normalized, "1."), ParagraphStyle.Alignment.LEFT, 8.8f, false, 0f, 3f, 0f));
        blocks.add(officialParagraph(officialNumberedLine(normalized, "2."), ParagraphStyle.Alignment.LEFT, 8.8f, false, 0f, 3f, 0f));
        blocks.add(officialParagraph(officialNumberedLine(normalized, "3."), ParagraphStyle.Alignment.LEFT, 8.8f, false, 0f, 34f, 0f));

        String closing = officialClosingLine(normalized);
        blocks.add(officialParagraph(closing, ParagraphStyle.Alignment.CENTER, 8.8f, false, 0f, 22f, 0f));

        blocks.add(officialParagraph("붙임", ParagraphStyle.Alignment.LEFT, 7.6f, true, 0f, 2f, 0f));
        List<String> attachments = officialAttachmentLines(normalized);
        if (attachments.isEmpty()) {
            attachments.add("- 첨부 1.");
            attachments.add("- 첨부 2.");
        }
        for (String attachment : attachments) {
            blocks.add(officialParagraph(attachment, ParagraphStyle.Alignment.LEFT, 7.6f, false, 0f, 1.5f, 0f));
        }
        blocks.add(RenderedBlock.unsupported(HWP_OFFICIAL_HR_SENTINEL));
        blocks.add(officialContactTable(normalized));
        blocks.add(officialParagraph(officialDateLine(normalized), ParagraphStyle.Alignment.CENTER, 11f, false, 26f, 26f, 0f));
        blocks.add(officialParagraph(officialCompanyLine(normalized), ParagraphStyle.Alignment.CENTER, 18f, true, 0f, 0f, 0f));

        RenderedPage.Builder page = RenderedPage.builder(0)
                .pageSizePt(DEFAULT_PAGE_WIDTH_PT, DEFAULT_PAGE_HEIGHT_PT)
                .marginsPt(45f, 52f, 42f, 52f);
        for (RenderedBlock block : blocks) page.addBlock(block);
        return RenderedDocument.builder(HWP_OFFICIAL_LETTER_FORMAT)
                .title(title != null ? title : "HWP")
                .addPage(page.build())
                .plainText(normalized)
                .build();
    }

    private static boolean looksLikeKoreanOfficialLetter(String text) {
        if (text == null) return false;
        String collapsed = text.replaceAll("\\s+", "");
        return collapsed.contains("공문")
                && collapsed.contains("문서번호")
                && collapsed.contains("시행일자")
                && collapsed.contains("수신")
                && collapsed.contains("참조")
                && collapsed.contains("제목")
                && collapsed.contains("귀사의무궁한발전")
                && collapsed.contains("붙임")
                && collapsed.contains("주식회사");
    }

    private static String normalizeOfficialLetterText(String text) {
        String normalized = text != null ? text : "";
        normalized = normalized.replace('\r', '\n')
                .replace('｜', 'ㅣ')
                .replace('|', 'ㅣ')
                .replace('\u00a0', ' ');
        normalized = normalized.replaceAll("\\n{3,}", "\\n\\n").trim();
        return normalized;
    }

    private static String officialField(String text, String compactLabel) {
        if (text == null || compactLabel == null) return "";
        String[] lines = text.split("\\n");
        for (String raw : lines) {
            String line = raw != null ? raw.trim() : "";
            if (line.isEmpty()) continue;
            String compact = line.replaceAll("\\s+", "");
            String needle = compactLabel.replaceAll("\\s+", "") + ":";
            int idx = compact.indexOf(needle);
            if (idx < 0) continue;
            int colon = line.indexOf(':');
            if (colon >= 0 && colon + 1 < line.length()) return line.substring(colon + 1).trim();
            return "";
        }
        return "";
    }

    private static String officialLineContaining(String text, String needle) {
        if (text == null || needle == null) return "";
        String compactNeedle = needle.replaceAll("\\s+", "");
        for (String raw : text.split("\\n")) {
            String line = raw != null ? raw.trim() : "";
            if (line.replaceAll("\\s+", "").contains(compactNeedle)) return line;
        }
        return "";
    }

    private static String officialNumberedLine(String text, String prefix) {
        if (text != null) {
            for (String raw : text.split("\\n")) {
                String line = raw != null ? raw.trim() : "";
                if (line.startsWith(prefix)) return line;
            }
        }
        return prefix + " ";
    }

    private static String officialClosingLine(String text) {
        String line = officialLineContaining(text, "위와 같이 협조");
        if (line.isEmpty()) {
            line = "위와 같이 협조를 요청드리며, 앞으로도 상호간의 긴밀한 협력을 부탁드립니다.\n감사합니다.";
        } else if (line.contains("감사합니다") && !line.contains("\n")) {
            line = line.replace("감사합니다", "\n감사합니다").replace(".\n", ".\n");
        }
        return line;
    }

    private static List<String> officialAttachmentLines(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null) return out;
        boolean inAttachment = false;
        for (String raw : text.split("\\n")) {
            String line = raw != null ? raw.trim() : "";
            if (line.isEmpty()) continue;
            String compact = line.replaceAll("\\s+", "");
            if ("붙임".equals(compact)) {
                inAttachment = true;
                continue;
            }
            if (!inAttachment) continue;
            if (compact.startsWith("발신") || compact.startsWith("담당") || compact.startsWith("직통")
                    || compact.startsWith("연락처") || compact.startsWith("이메일") || compact.startsWith("FAX")
                    || compact.startsWith("주소") || compact.startsWith("20년") || compact.startsWith("주식회사")) {
                break;
            }
            if (line.startsWith("-") || line.startsWith("ㆍ") || line.startsWith("•")) out.add(line);
            if (out.size() >= 8) break;
        }
        return out;
    }

    private static String officialDateLine(String text) {
        if (text != null) {
            for (String raw : text.split("\\n")) {
                String line = raw != null ? raw.trim() : "";
                String compact = line.replaceAll("\\s+", "");
                if (compact.startsWith("20") && compact.contains("년") && compact.contains("월") && compact.contains("일")) {
                    return line.isEmpty() ? "20   년   월   일" : line;
                }
            }
        }
        return "20   년   월   일";
    }

    private static String officialCompanyLine(String text) {
        if (text != null) {
            for (String raw : text.split("\\n")) {
                String line = raw != null ? raw.trim() : "";
                if (line.replaceAll("\\s+", "").contains("주식회사")) return line.isEmpty() ? "주식회사" : line;
            }
        }
        return "주식회사";
    }

    private static RenderedBlock officialContactTable(String text) {
        ArrayList<List<RenderedTableCell>> rows = new ArrayList<>();
        rows.add(officialContactRow("발    신 ㅣ " + officialContactValue(text, "발신"),
                "연 락 처 ㅣ " + officialContactValue(text, "연락처")));
        rows.add(officialContactRow("담    당 ㅣ " + officialContactValue(text, "담당"),
                "직    통 ㅣ " + officialContactValue(text, "직통")));
        rows.add(officialContactRow("이 메 일 ㅣ " + officialContactValue(text, "이메일"),
                "F  A  X ㅣ " + officialContactValue(text, "FAX")));
        rows.add(officialContactRow("주    소 ㅣ " + officialContactValue(text, "주소"), ""));
        ArrayList<Float> widths = new ArrayList<>();
        widths.add(50f);
        widths.add(50f);
        return RenderedBlock.table(new RenderedTable(rows, 100f, widths));
    }

    private static ArrayList<RenderedTableCell> officialContactRow(String left, String right) {
        ArrayList<RenderedTableCell> row = new ArrayList<>();
        row.add(officialCell(left));
        row.add(officialCell(right));
        return row;
    }

    private static RenderedTableCell officialCell(String text) {
        return RenderedTableCell.builder()
                .borderVisible(false)
                .paddingPt(0f, 0f, 0f, 0f)
                .addBlock(officialParagraph(text != null ? text : "", ParagraphStyle.Alignment.LEFT, 8.8f, false, 0f, 0f, 0f))
                .build();
    }

    private static String officialContactValue(String text, String compactLabel) {
        if (text == null || compactLabel == null) return "";
        for (String raw : text.split("\\n")) {
            String line = raw != null ? raw.trim() : "";
            if (line.isEmpty()) continue;
            String compact = line.replaceAll("\\s+", "");
            if (!compact.startsWith(compactLabel.replaceAll("\\s+", "") + "ㅣ")) continue;
            int idx = line.indexOf('ㅣ');
            return idx >= 0 && idx + 1 < line.length() ? line.substring(idx + 1).trim() : "";
        }
        return "";
    }

    private static RenderedBlock officialParagraph(String text, ParagraphStyle.Alignment alignment,
                                                   float fontSizePt, boolean bold,
                                                   float marginTopPt, float marginBottomPt,
                                                   float marginLeftPt) {
        ParagraphStyle.Builder paragraph = new ParagraphStyle.Builder()
                .alignment(alignment != null ? alignment : ParagraphStyle.Alignment.LEFT)
                .lineHeightMultiplier(1.22f);
        if (marginTopPt > 0f) paragraph.marginTopPt(marginTopPt);
        if (marginBottomPt > 0f) paragraph.marginBottomPt(marginBottomPt);
        if (marginLeftPt > 0f) paragraph.marginLeftPt(marginLeftPt);

        TextStyle.Builder textStyle = new TextStyle.Builder().fontFamily("serif");
        if (fontSizePt > 0f) textStyle.fontSizePt(fontSizePt);
        if (bold) textStyle.bold(true);
        return RenderedBlock.paragraph(new RenderedParagraph(
                paragraph.build(),
                Collections.singletonList(RenderedRun.text(text != null ? text : "", textStyle.build()))));
    }

    private static RenderedDocument fromPlainText(String text, String title, String format,
                                                  int paragraphsPerPage, int targetCharsPerPage) {
        ArrayList<RenderedBlock> blocks = blocksFromPlainText(text);
        if (blocks.isEmpty()) {
            blocks.add(RenderedBlock.paragraph(RenderedParagraph.of(RenderedRun.text(""))));
        }
        return buildDocument(title, format, blocks, paragraphsPerPage, targetCharsPerPage, PageMetrics.defaults());
    }

    private static RenderedDocument buildDocument(String title, String format, List<RenderedBlock> blocks,
                                                  int paragraphsPerPage, int targetCharsPerPage,
                                                  PageMetrics pageMetrics) {
        int blockBudget = Math.max(4, paragraphsPerPage > 0 ? paragraphsPerPage : DEFAULT_PARAGRAPHS_PER_PAGE);
        int charBudget = Math.max(900, targetCharsPerPage > 0 ? targetCharsPerPage : DEFAULT_TARGET_CHARS_PER_PAGE);
        ArrayList<RenderedPage> pages = new ArrayList<>();
        PageAccumulator accumulator = new PageAccumulator(pages, blockBudget, charBudget,
                pageMetrics != null ? pageMetrics : PageMetrics.defaults());
        for (RenderedBlock block : blocks) accumulator.add(block);
        accumulator.finish();

        RenderedDocument.Builder document = RenderedDocument.builder(format != null ? format : "hwp").title(title != null ? title : "HWP");
        for (RenderedPage page : pages) document.addPage(page);
        return document.build();
    }

    private static ArrayList<RenderedBlock> blocksFromPlainText(String text) {
        ArrayList<RenderedBlock> blocks = new ArrayList<>();
        String normalized = (text != null ? text : "").replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) return blocks;

        String[] chunks = normalized.split("\n{2,}");
        for (String chunk : chunks) {
            String clean = chunk != null ? chunk.trim() : "";
            if (clean.isEmpty()) continue;
            RenderedTable table = tableFromTabbedText(clean);
            if (table != null) {
                blocks.add(RenderedBlock.table(table));
            } else {
                blocks.add(RenderedBlock.paragraph(new RenderedParagraph(
                        new ParagraphStyle.Builder().lineHeightMultiplier(1.45f).marginBottomPt(8f).build(),
                        Collections.singletonList(RenderedRun.text(clean, defaultHwpTextStyle())))));
            }
        }
        return blocks;
    }

    private static RenderedTable tableFromTabbedText(String chunk) {
        String[] lines = chunk.split("\n");
        if (lines.length < 2) return null;
        int tabbed = 0;
        int maxCols = 0;
        ArrayList<List<RenderedTableCell>> rows = new ArrayList<>();
        for (String line : lines) {
            String safe = line != null ? line : "";
            if (safe.indexOf('\t') >= 0) tabbed++;
            String[] cells = safe.split("\t", -1);
            maxCols = Math.max(maxCols, cells.length);
            ArrayList<RenderedTableCell> row = new ArrayList<>();
            for (String cellText : cells) {
                row.add(RenderedTableCell.builder()
                        .borderVisible(true)
                        .addBlock(RenderedBlock.paragraph(new RenderedParagraph(
                                new ParagraphStyle.Builder().marginBottomPt(0f).build(),
                                Collections.singletonList(RenderedRun.text(cellText != null ? cellText.trim() : "", defaultHwpTextStyle())))))
                        .build());
            }
            rows.add(row);
        }
        if (tabbed < 2 || maxCols < 2) return null;
        ArrayList<Float> widths = new ArrayList<>();
        for (int i = 0; i < maxCols; i++) widths.add(100f / maxCols);
        return new RenderedTable(rows, 100f, widths);
    }

    private static void appendHwpxBlocks(Node node, List<RenderedBlock> out, boolean insideTable, HwpxDocumentContext context) {
        if (node == null) return;
        String local = localName(node);
        if (isHwpxTableNode(local)) {
            RenderedTable table = readHwpxTable(node, context);
            if (table != null && !table.rows.isEmpty()) out.add(RenderedBlock.table(table));
            return;
        }
        if (!insideTable && isHwpxParagraphNode(local)) {
            RenderedParagraph paragraph = readHwpxParagraph(node, context);
            if (paragraph != null && !paragraph.plainText().trim().isEmpty()) {
                out.add(RenderedBlock.paragraph(paragraph));
            }
            return;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            appendHwpxBlocks(children.item(i), out, insideTable, context);
        }
    }

    private static RenderedTable readHwpxTable(Node tableNode, HwpxDocumentContext context) {
        ArrayList<List<RenderedTableCell>> rows = new ArrayList<>();
        NodeList children = tableNode.getChildNodes();
        int maxCols = 0;
        for (int i = 0; i < children.getLength(); i++) {
            Node rowNode = children.item(i);
            if (!isHwpxTableRowNode(localName(rowNode))) continue;
            ArrayList<RenderedTableCell> row = new ArrayList<>();
            NodeList rowChildren = rowNode.getChildNodes();
            for (int j = 0; j < rowChildren.getLength(); j++) {
                Node cellNode = rowChildren.item(j);
                if (!isHwpxTableCellNode(localName(cellNode))) continue;
                RenderedTableCell cell = readHwpxTableCell(cellNode, context);
                if (cell != null) row.add(cell);
            }
            if (!row.isEmpty()) {
                maxCols = Math.max(maxCols, row.size());
                rows.add(row);
            }
        }
        if (rows.isEmpty()) return null;
        ArrayList<Float> widths = new ArrayList<>();
        int safeCols = Math.max(1, maxCols);
        for (int i = 0; i < safeCols; i++) widths.add(100f / safeCols);
        return new RenderedTable(rows, 100f, widths);
    }

    private static RenderedTableCell readHwpxTableCell(Node cellNode, HwpxDocumentContext context) {
        RenderedTableCell.Builder builder = RenderedTableCell.builder().borderVisible(true);
        int colSpan = intAttr(cellNode, 1, "colSpan", "colspan", "colCnt", "colCount");
        int rowSpan = intAttr(cellNode, 1, "rowSpan", "rowspan", "rowCnt", "rowCount");
        if (colSpan > 1) builder.colSpan(colSpan);
        if (rowSpan > 1) builder.rowSpan(rowSpan);

        String fill = colorAttr(cellNode, "fill", "background", "backgroundColor", "bgColor");
        if (fill != null) builder.backgroundColor(fill);
        Float paddingTop = unitToPt(firstAttr(cellNode, true, "paddingTop", "marginTop", "topMargin", "topInset", "insetTop"));
        Float paddingRight = unitToPt(firstAttr(cellNode, true, "paddingRight", "marginRight", "rightMargin", "rightInset", "insetRight"));
        Float paddingBottom = unitToPt(firstAttr(cellNode, true, "paddingBottom", "marginBottom", "bottomMargin", "bottomInset", "insetBottom"));
        Float paddingLeft = unitToPt(firstAttr(cellNode, true, "paddingLeft", "marginLeft", "leftMargin", "leftInset", "insetLeft"));
        if (paddingTop != null || paddingRight != null || paddingBottom != null || paddingLeft != null) {
            builder.paddingPt(paddingTop, paddingRight, paddingBottom, paddingLeft);
        }

        ArrayList<RenderedBlock> cellBlocks = new ArrayList<>();
        NodeList children = cellNode.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (isHwpxParagraphNode(localName(child))) {
                RenderedParagraph paragraph = readHwpxParagraph(child, context);
                if (paragraph != null && !paragraph.plainText().trim().isEmpty()) {
                    cellBlocks.add(RenderedBlock.paragraph(paragraph));
                }
            } else if (isHwpxTableNode(localName(child))) {
                RenderedTable nested = readHwpxTable(child, context);
                if (nested != null && !nested.rows.isEmpty()) cellBlocks.add(RenderedBlock.table(nested));
            } else {
                appendHwpxBlocks(child, cellBlocks, true, context);
            }
        }
        if (cellBlocks.isEmpty()) {
            String text = collectHwpxText(cellNode).trim();
            if (!text.isEmpty()) {
                cellBlocks.add(RenderedBlock.paragraph(RenderedParagraph.of(RenderedRun.text(text, defaultHwpTextStyle()))));
            }
        }
        for (RenderedBlock block : cellBlocks) builder.addBlock(block);
        return builder.build();
    }

    private static RenderedParagraph readHwpxParagraph(Node paragraph, HwpxDocumentContext context) {
        ArrayList<RenderedRun> runs = new ArrayList<>();
        appendHwpxParagraphRuns(paragraph, runs, context);
        if (runs.isEmpty()) {
            String text = collectHwpxText(paragraph);
            if (text == null) text = "";
            text = text.replace("\r\n", "\n").replace('\r', '\n');
            if (!text.trim().isEmpty()) runs.add(RenderedRun.text(text, defaultHwpTextStyle()));
        }
        if (runs.isEmpty()) return null;
        StringBuilder plain = new StringBuilder();
        for (RenderedRun run : runs) if (run != null && run.text != null) plain.append(run.text);
        if (plain.toString().trim().isEmpty()) return null;
        return new RenderedParagraph(readHwpxParagraphStyle(paragraph, context), runs);
    }

    private static ParagraphStyle readHwpxParagraphStyle(Node paragraph, HwpxDocumentContext context) {
        ParagraphStyleInfo info = new ParagraphStyleInfo();
        info.alignment = ParagraphStyle.Alignment.LEFT;
        info.lineHeightMultiplier = 1.45f;
        info.marginBottomPt = 7f;

        ParagraphStyle base = context != null ? context.paragraphStyle(resolveStyleKey(paragraph,
                "paraPrIDRef", "paraPrIdRef", "paraPrID", "paraPrId", "styleIDRef", "styleIdRef")) : null;
        info.apply(base);
        info.apply(readHwpxParagraphStyleFromNode(paragraph, false));
        return info.toStyle();
    }

    private static void appendHwpxParagraphRuns(Node paragraph, List<RenderedRun> out, HwpxDocumentContext context) {
        if (paragraph == null || out == null) return;
        NodeList children = paragraph.getChildNodes();
        boolean sawRun = false;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            String local = localName(child);
            if (isHwpxRunNode(local)) {
                sawRun = true;
                TextStyle style = readHwpxEffectiveTextStyle(child, context);
                appendHwpxRunsFromNode(child, out, style);
            } else if ("tab".equals(local)) {
                out.add(RenderedRun.text("\t", defaultHwpTextStyle()));
            } else if ("lineBreak".equals(local) || "br".equals(local)) {
                out.add(RenderedRun.text("\n", defaultHwpTextStyle()));
            } else if ("t".equals(local) || "text".equals(local)) {
                String text = child.getTextContent();
                if (text != null && !text.isEmpty()) out.add(RenderedRun.text(text, defaultHwpTextStyle()));
            }
        }
        if (!sawRun && out.isEmpty()) {
            String text = collectHwpxText(paragraph);
            if (text != null && !text.trim().isEmpty()) out.add(RenderedRun.text(text, defaultHwpTextStyle()));
        }
    }

    private static void appendHwpxRunsFromNode(Node node, List<RenderedRun> out, TextStyle style) {
        if (node == null || out == null) return;
        String local = localName(node);
        if ("t".equals(local) || "text".equals(local)) {
            String text = node.getTextContent();
            if (text != null && !text.isEmpty()) out.add(RenderedRun.text(text, style != null ? style : defaultHwpTextStyle()));
            return;
        }
        if ("tab".equals(local)) {
            out.add(RenderedRun.text("\t", style != null ? style : defaultHwpTextStyle()));
            return;
        }
        if ("lineBreak".equals(local) || "br".equals(local)) {
            out.add(RenderedRun.text("\n", style != null ? style : defaultHwpTextStyle()));
            return;
        }
        if (node.getNodeType() == Node.TEXT_NODE) {
            String text = node.getNodeValue();
            if (text != null && !text.trim().isEmpty()) out.add(RenderedRun.text(text, style != null ? style : defaultHwpTextStyle()));
            return;
        }
        TextStyle nestedStyle = style;
        if (node != null && isHwpxRunNode(local)) nestedStyle = readHwpxInlineTextStyle(node, style);
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) appendHwpxRunsFromNode(children.item(i), out, nestedStyle);
    }

    private static TextStyle readHwpxEffectiveTextStyle(Node run, HwpxDocumentContext context) {
        TextStyle base = context != null ? context.textStyle(resolveStyleKey(run,
                "charPrIDRef", "charPrIdRef", "charPrID", "charPrId", "styleIDRef", "styleIdRef")) : null;
        if (base == null) base = defaultHwpTextStyle();
        return readHwpxInlineTextStyle(run, base);
    }

    private static TextStyle readHwpxInlineTextStyle(Node run, TextStyle base) {
        TextStyleInfo info = new TextStyleInfo();
        info.apply(base != null ? base : defaultHwpTextStyle());
        info.apply(readHwpxTextStyleFromNode(run, true));
        return info.toStyle();
    }

    private static ParagraphStyle.Alignment readHwpxAlignment(Node node) {
        String value = attrRecursive(node, "textAlign", "align", "alignment", "horizontalAlign", "horzAlign");
        if (value == null) return ParagraphStyle.Alignment.LEFT;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("center") || lower.contains("middle")) return ParagraphStyle.Alignment.CENTER;
        if (lower.contains("right") || lower.contains("end")) return ParagraphStyle.Alignment.RIGHT;
        if (lower.contains("justify") || lower.contains("both") || lower.contains("distribute")) return ParagraphStyle.Alignment.JUSTIFY;
        return ParagraphStyle.Alignment.LEFT;
    }

    private static HwpxDocumentContext loadHwpxDocumentContext(ZipFile zip) {
        HwpxDocumentContext context = new HwpxDocumentContext();
        if (zip == null) return context;
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry == null || entry.isDirectory()) continue;
            String name = entry.getName();
            String lower = name != null ? name.toLowerCase(Locale.ROOT).replace('\\', '/') : "";
            if (!lower.endsWith(".xml")) continue;
            if (lower.contains("section") || lower.contains("bodytext/")) continue;
            if (!(lower.contains("header") || lower.contains("head") || lower.contains("styles") || lower.contains("setting"))) continue;
            long size = entry.getSize();
            if (size > MAX_HWPX_SECTION_XML_BYTES) continue;
            try (InputStream is = zip.getInputStream(entry)) {
                byte[] xml = readAllBytesWithLimit(is, MAX_HWPX_SECTION_XML_BYTES);
                Document doc = DocumentArchiveUtils.secureDocumentBuilder().parse(new ByteArrayInputStream(xml));
                collectHwpxContext(doc.getDocumentElement(), context);
            } catch (Exception ignored) {
                // HWPX style/header parsing is a fidelity enhancement only.
            }
        }
        return context;
    }

    private static void collectHwpxContext(Node node, HwpxDocumentContext context) {
        if (node == null || context == null) return;
        String local = localName(node);
        if ("paraPr".equals(local) || "paraProperties".equals(local) || "paragraphProperties".equals(local)) {
            String id = firstNonEmpty(attr(node, "id", "paraPrID", "paraPrId", "paraPrIDRef", "paraPrIdRef"));
            ParagraphStyle style = readHwpxParagraphStyleFromNode(node, true);
            if (id != null && style != null) context.paragraphStyles.put(id, style);
        } else if ("charPr".equals(local) || "charProperties".equals(local) || "characterProperties".equals(local)) {
            String id = firstNonEmpty(attr(node, "id", "charPrID", "charPrId", "charPrIDRef", "charPrIdRef"));
            TextStyle style = readHwpxTextStyleFromNode(node, true);
            if (id != null && style != null) context.textStyles.put(id, style);
        } else if ("paper".equals(local) || "pagePr".equals(local) || "pageProperties".equals(local) || "pageDef".equals(local)) {
            context.pageMetrics = readHwpxPageMetrics(node, context.pageMetrics);
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) collectHwpxContext(children.item(i), context);
    }

    private static ParagraphStyle readHwpxParagraphStyleFromNode(Node node, boolean recursive) {
        ParagraphStyleInfo info = new ParagraphStyleInfo();
        Node target = recursive ? node : node;
        ParagraphStyle.Alignment alignment = readHwpxAlignmentFromNode(target, recursive);
        if (alignment != null) info.alignment = alignment;

        Float marginLeft = unitToPt(firstAttr(target, recursive, "marginLeft", "leftMargin", "leftIndent", "indentLeft"));
        Float marginRight = unitToPt(firstAttr(target, recursive, "marginRight", "rightMargin", "rightIndent", "indentRight"));
        Float marginTop = unitToPt(firstAttr(target, recursive, "marginTop", "topMargin", "before", "spaceBefore"));
        Float marginBottom = unitToPt(firstAttr(target, recursive, "marginBottom", "bottomMargin", "after", "spaceAfter"));
        Float indent = unitToPt(firstAttr(target, recursive, "indent", "firstLine", "textIndent", "firstLineIndent"));
        Float line = readLineHeight(firstAttr(target, recursive, "lineSpacing", "lineHeight", "spacing", "lineSpace"));
        String bg = colorAttrDeep(target, recursive, "backgroundColor", "bgColor", "shadeColor", "fillColor", "highlightColor");
        if (marginLeft != null) info.marginLeftPt = marginLeft;
        if (marginRight != null) info.marginRightPt = marginRight;
        if (marginTop != null) info.marginTopPt = marginTop;
        if (marginBottom != null) info.marginBottomPt = marginBottom;
        if (indent != null) info.textIndentPt = indent;
        if (line != null) info.lineHeightMultiplier = line;
        if (bg != null) info.backgroundColor = bg;
        return info.toStyleOrNull();
    }

    private static ParagraphStyle.Alignment readHwpxAlignmentFromNode(Node node, boolean recursive) {
        String value = firstAttr(node, recursive,
                "textAlign", "align", "alignment", "horizontalAlign", "horzAlign", "horizontal", "value");
        if (value == null) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("center") || lower.contains("middle")) return ParagraphStyle.Alignment.CENTER;
        if (lower.contains("right") || lower.contains("end")) return ParagraphStyle.Alignment.RIGHT;
        if (lower.contains("justify") || lower.contains("both") || lower.contains("distribute")) return ParagraphStyle.Alignment.JUSTIFY;
        if (lower.contains("left") || lower.contains("start")) return ParagraphStyle.Alignment.LEFT;
        return null;
    }

    private static TextStyle readHwpxTextStyleFromNode(Node node, boolean recursive) {
        TextStyleInfo info = new TextStyleInfo();
        String font = firstAttr(node, recursive, "fontFace", "fontFamily", "faceName", "fontName", "typeface", "hangul", "latin");
        if (font != null) info.fontFamily = sanitizeFont(font);
        Float size = fontUnitToPt(firstAttr(node, recursive, "fontSize", "size", "height", "sz"));
        if (size != null) info.fontSizePt = size;
        Boolean bold = booleanStyle(node, recursive, "bold", "b");
        Boolean italic = booleanStyle(node, recursive, "italic", "i");
        Boolean underline = booleanStyle(node, recursive, "underline", "u");
        Boolean strike = booleanStyle(node, recursive, "strike", "strikeout", "lineThrough");
        if (bold != null) info.bold = bold;
        if (italic != null) info.italic = italic;
        if (underline != null) info.underline = underline;
        if (strike != null) info.strike = strike;
        String color = colorAttrDeep(node, recursive, "textColor", "color", "fontColor", "fgColor");
        String bg = colorAttrDeep(node, recursive, "backgroundColor", "bgColor", "shadeColor", "fillColor", "highlightColor");
        if (color != null) info.color = color;
        if (bg != null) info.backgroundColor = bg;
        String vert = firstAttr(node, recursive, "vertAlign", "verticalAlign", "superscript", "subscript");
        if (vert != null) {
            String lower = vert.toLowerCase(Locale.ROOT);
            if (lower.contains("super")) info.verticalAlign = TextStyle.VerticalAlign.SUPERSCRIPT;
            else if (lower.contains("sub")) info.verticalAlign = TextStyle.VerticalAlign.SUBSCRIPT;
        }
        return info.toStyleOrNull();
    }

    private static PageMetrics readHwpxPageMetrics(Node node, PageMetrics base) {
        PageMetrics metrics = base != null ? base.copy() : PageMetrics.defaults();
        Float width = unitToPt(firstAttr(node, true, "width", "paperWidth", "pageWidth", "w"));
        Float height = unitToPt(firstAttr(node, true, "height", "paperHeight", "pageHeight", "h"));
        Float top = unitToPt(firstAttr(node, true, "marginTop", "topMargin", "top"));
        Float right = unitToPt(firstAttr(node, true, "marginRight", "rightMargin", "right"));
        Float bottom = unitToPt(firstAttr(node, true, "marginBottom", "bottomMargin", "bottom"));
        Float left = unitToPt(firstAttr(node, true, "marginLeft", "leftMargin", "left"));
        if (width != null && width >= 220f && width <= 1600f) metrics.widthPt = width;
        if (height != null && height >= 220f && height <= 2200f) metrics.heightPt = height;
        if (top != null && top >= 0 && top <= 300f) metrics.marginTopPt = top;
        if (right != null && right >= 0 && right <= 300f) metrics.marginRightPt = right;
        if (bottom != null && bottom >= 0 && bottom <= 300f) metrics.marginBottomPt = bottom;
        if (left != null && left >= 0 && left <= 300f) metrics.marginLeftPt = left;
        return metrics;
    }

    private static String resolveStyleKey(Node node, String... names) {
        String value = attr(node, names);
        if (value == null) value = firstAttr(node, false, names);
        return firstNonEmpty(value);
    }

    private static String firstAttr(Node node, boolean recursive, String... names) {
        if (node == null || names == null) return null;
        String direct = attr(node, names);
        if (direct != null) return direct;
        if (!recursive) return null;
        NodeList children = node.getChildNodes();
        int scanned = 0;
        for (int i = 0; i < children.getLength() && scanned < 48; i++) {
            Node child = children.item(i);
            scanned++;
            String value = firstAttr(child, true, names);
            if (value != null) return value;
        }
        return null;
    }

    private static Boolean booleanStyle(Node node, boolean recursive, String... names) {
        if (node == null || names == null) return null;
        String value = firstAttr(node, recursive, names);
        if (value != null) {
            String lower = value.toLowerCase(Locale.ROOT);
            if ("0".equals(lower) || "false".equals(lower) || "off".equals(lower) || "none".equals(lower) || "no".equals(lower)) return Boolean.FALSE;
            return Boolean.TRUE;
        }
        if (!recursive) return hasLocalChild(node, names) ? Boolean.TRUE : null;
        return hasLocalDescendant(node, names, 64) ? Boolean.TRUE : null;
    }

    private static boolean hasLocalChild(Node node, String... locals) {
        if (node == null || locals == null) return false;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            String local = localName(children.item(i));
            for (String wanted : locals) if (wanted != null && wanted.equals(local)) return true;
        }
        return false;
    }

    private static boolean hasLocalDescendant(Node node, String[] locals, int limit) {
        if (node == null || locals == null || limit <= 0) return false;
        String local = localName(node);
        for (String wanted : locals) if (wanted != null && wanted.equals(local)) return true;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (hasLocalDescendant(children.item(i), locals, limit - 1)) return true;
        }
        return false;
    }

    private static String colorAttrDeep(Node node, boolean recursive, String... names) {
        String value = firstAttr(node, recursive, names);
        if (value == null) return null;
        String clean = value.trim();
        if (clean.startsWith("#")) clean = clean.substring(1);
        if (clean.length() == 8) clean = clean.substring(2);
        if (clean.matches("(?i)[0-9a-f]{6}")) return "#" + clean;
        return null;
    }

    private static Float readLineHeight(String value) {
        Float numeric = safeFloat(value);
        if (numeric == null || numeric <= 0) return null;
        if (numeric > 20f) return Math.max(0.75f, Math.min(3.0f, numeric / 100f));
        return Math.max(0.75f, Math.min(3.0f, numeric));
    }

    private static Float fontUnitToPt(String value) {
        Float numeric = safeFloat(value);
        if (numeric == null || numeric <= 0) return null;
        if (numeric > 300f) return Math.max(4f, Math.min(96f, numeric / 100f));
        if (numeric > 80f) return Math.max(4f, Math.min(96f, numeric / 10f));
        return Math.max(4f, Math.min(96f, numeric));
    }

    private static Float unitToPt(String value) {
        Float numeric = safeFloat(value);
        if (numeric == null) return null;
        float abs = Math.abs(numeric);
        if (abs > 2000f) return numeric / 100f;
        if (abs > 100f) return numeric / 20f;
        return numeric;
    }

    private static Float safeFloat(String value) {
        if (value == null) return null;
        String clean = value.trim().replace(",", "");
        if (clean.endsWith("pt")) clean = clean.substring(0, clean.length() - 2).trim();
        if (clean.endsWith("%")) clean = clean.substring(0, clean.length() - 1).trim();
        try {
            return Float.parseFloat(clean);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String sanitizeFont(String font) {
        if (font == null) return null;
        String clean = font.replace('"', ' ').replace('’', ' ').replace('\'', ' ').trim();
        if (clean.isEmpty() || clean.length() > 64) return null;
        return clean;
    }

    private static String firstNonEmpty(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String collectHwpxText(Node node) {
        StringBuilder out = new StringBuilder();
        appendHwpxText(node, out);
        return out.toString();
    }

    private static void appendHwpxText(Node node, StringBuilder out) {
        if (node == null) return;
        String local = localName(node);
        if ("t".equals(local) || "text".equals(local)) {
            String text = node.getTextContent();
            if (text != null) out.append(text);
            return;
        }
        if ("tab".equals(local)) {
            out.append('\t');
            return;
        }
        if ("lineBreak".equals(local) || "br".equals(local)) {
            out.append('\n');
            return;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) appendHwpxText(children.item(i), out);
    }

    private static List<String> findHwpxSectionPaths(ZipFile zip) {
        ArrayList<String> sections = new ArrayList<>();
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry == null || entry.isDirectory()) continue;
            String name = entry.getName();
            String lower = name != null ? name.toLowerCase(Locale.ROOT).replace('\\', '/') : "";
            if (!lower.endsWith(".xml")) continue;
            if (lower.matches("(^|.*/)contents/(section|sections/section)[0-9]+\\.xml")
                    || lower.matches("(^|.*/)bodytext/section[0-9]+\\.xml")) {
                sections.add(name);
            }
        }
        if (sections.isEmpty()) {
            entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;
                String name = entry.getName();
                String lower = name != null ? name.toLowerCase(Locale.ROOT).replace('\\', '/') : "";
                if (lower.endsWith(".xml") && lower.contains("section")) sections.add(name);
            }
        }
        Collections.sort(sections, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int ai = trailingNumber(a);
                int bi = trailingNumber(b);
                if (ai != bi) return ai < bi ? -1 : 1;
                return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
            }
        });
        return sections;
    }

    private static int trailingNumber(String path) {
        if (path == null) return Integer.MAX_VALUE;
        int end = path.lastIndexOf('.');
        if (end < 0) end = path.length();
        int start = end;
        while (start > 0 && Character.isDigit(path.charAt(start - 1))) start--;
        if (start == end) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(path.substring(start, end));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static byte[] readAllBytesWithLimit(InputStream is, long maxBytes) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = is.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) throw new java.io.IOException("HWPX section XML exceeds safety limit");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static TextStyle defaultHwpTextStyle() {
        return new TextStyle.Builder().fontFamily("Malgun Gothic").fontSizePt(11f).build();
    }

    private static boolean isHwpxParagraphNode(String local) {
        return "p".equals(local) || "para".equals(local) || "paragraph".equals(local);
    }

    private static boolean isHwpxRunNode(String local) {
        return "run".equals(local) || "r".equals(local) || "span".equals(local);
    }

    private static boolean isHwpxTableNode(String local) {
        return "tbl".equals(local) || "table".equals(local);
    }

    private static boolean isHwpxTableRowNode(String local) {
        return "tr".equals(local) || "row".equals(local);
    }

    private static boolean isHwpxTableCellNode(String local) {
        return "tc".equals(local) || "cell".equals(local) || "td".equals(local);
    }

    private static String localName(Node node) {
        if (node == null) return "";
        String local = node.getLocalName();
        if (local != null && !local.isEmpty()) return local;
        String name = node.getNodeName();
        if (name == null) return "";
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static String attrRecursive(Node node, String... names) {
        if (node == null) return null;
        String direct = attr(node, names);
        if (direct != null) return direct;
        NodeList children = node.getChildNodes();
        int scanned = 0;
        for (int i = 0; i < children.getLength() && scanned < 16; i++) {
            Node child = children.item(i);
            scanned++;
            String v = attr(child, names);
            if (v != null) return v;
        }
        return null;
    }

    private static String attr(Node node, String... names) {
        if (node == null || names == null) return null;
        NamedNodeMap attrs = node.getAttributes();
        if (attrs == null) return null;
        for (String wanted : names) {
            if (wanted == null) continue;
            Node direct = attrs.getNamedItem(wanted);
            if (direct != null && direct.getNodeValue() != null && !direct.getNodeValue().trim().isEmpty()) {
                return direct.getNodeValue().trim();
            }
            for (int i = 0; i < attrs.getLength(); i++) {
                Node attr = attrs.item(i);
                if (attr == null) continue;
                String local = attr.getLocalName();
                String name = attr.getNodeName();
                if (wanted.equals(local) || wanted.equals(name)
                        || (name != null && name.endsWith(":" + wanted))) {
                    String value = attr.getNodeValue();
                    if (value != null && !value.trim().isEmpty()) return value.trim();
                }
            }
        }
        return null;
    }

    private static int intAttr(Node node, int fallback, String... names) {
        String value = attrRecursive(node, names);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String colorAttr(Node node, String... names) {
        String value = attrRecursive(node, names);
        if (value == null) return null;
        String clean = value.trim();
        if (clean.startsWith("#")) clean = clean.substring(1);
        if (clean.length() == 8) clean = clean.substring(2);
        if (clean.matches("(?i)[0-9a-f]{6}")) return "#" + clean;
        return null;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private static RenderedPage.Builder newPageBuilder(int pageIndex, PageMetrics metrics) {
        PageMetrics safe = metrics != null ? metrics : PageMetrics.defaults();
        return RenderedPage.builder(pageIndex)
                .pageSizePt(safe.widthPt, safe.heightPt)
                .marginsPt(safe.marginTopPt, safe.marginRightPt, safe.marginBottomPt, safe.marginLeftPt);
    }

    private static int estimateBlockCost(RenderedBlock block) {
        if (block == null) return 0;
        if (block.type == RenderedBlock.Type.TABLE) {
            int rows = block.table != null && block.table.rows != null ? block.table.rows.size() : 1;
            return Math.max(3, rows + 1);
        }
        if (block.type == RenderedBlock.Type.IMAGE) return 3;
        return 1;
    }

    private static int estimateTextLength(RenderedBlock block) {
        if (block == null) return 0;
        if (block.type == RenderedBlock.Type.PARAGRAPH && block.paragraph != null) {
            return block.paragraph.plainText().length();
        }
        if (block.type == RenderedBlock.Type.TABLE && block.table != null) {
            int total = 0;
            for (List<RenderedTableCell> row : block.table.rows) {
                if (row == null) continue;
                for (RenderedTableCell cell : row) {
                    if (cell == null || cell.blocks == null) continue;
                    for (RenderedBlock nested : cell.blocks) total += estimateTextLength(nested);
                }
            }
            return total;
        }
        return 0;
    }

    private static final class HwpxDocumentContext {
        final Map<String, ParagraphStyle> paragraphStyles = new LinkedHashMap<>();
        final Map<String, TextStyle> textStyles = new LinkedHashMap<>();
        PageMetrics pageMetrics = PageMetrics.defaults();

        ParagraphStyle paragraphStyle(String id) {
            return id == null ? null : paragraphStyles.get(id);
        }

        TextStyle textStyle(String id) {
            return id == null ? null : textStyles.get(id);
        }
    }

    private static final class PageMetrics {
        float widthPt = DEFAULT_PAGE_WIDTH_PT;
        float heightPt = DEFAULT_PAGE_HEIGHT_PT;
        float marginTopPt = DEFAULT_MARGIN_TOP_PT;
        float marginRightPt = DEFAULT_MARGIN_RIGHT_PT;
        float marginBottomPt = DEFAULT_MARGIN_BOTTOM_PT;
        float marginLeftPt = DEFAULT_MARGIN_LEFT_PT;

        static PageMetrics defaults() {
            return new PageMetrics();
        }

        PageMetrics copy() {
            PageMetrics copy = new PageMetrics();
            copy.widthPt = widthPt;
            copy.heightPt = heightPt;
            copy.marginTopPt = marginTopPt;
            copy.marginRightPt = marginRightPt;
            copy.marginBottomPt = marginBottomPt;
            copy.marginLeftPt = marginLeftPt;
            return copy;
        }
    }

    private static final class ParagraphStyleInfo {
        ParagraphStyle.Alignment alignment;
        Float marginTopPt;
        Float marginBottomPt;
        Float marginLeftPt;
        Float marginRightPt;
        Float textIndentPt;
        Float lineHeightMultiplier;
        String backgroundColor;

        void apply(ParagraphStyle style) {
            if (style == null) return;
            alignment = style.alignment;
            marginTopPt = style.marginTopPt;
            marginBottomPt = style.marginBottomPt;
            marginLeftPt = style.marginLeftPt;
            marginRightPt = style.marginRightPt;
            textIndentPt = style.textIndentPt;
            lineHeightMultiplier = style.lineHeightMultiplier;
            backgroundColor = style.backgroundColor;
        }

        ParagraphStyle toStyleOrNull() {
            if (alignment == null && marginTopPt == null && marginBottomPt == null && marginLeftPt == null
                    && marginRightPt == null && textIndentPt == null && lineHeightMultiplier == null
                    && backgroundColor == null) return null;
            return toStyle();
        }

        ParagraphStyle toStyle() {
            ParagraphStyle.Builder b = new ParagraphStyle.Builder();
            if (alignment != null) b.alignment(alignment);
            if (marginTopPt != null) b.marginTopPt(marginTopPt);
            if (marginBottomPt != null) b.marginBottomPt(marginBottomPt);
            if (marginLeftPt != null) b.marginLeftPt(marginLeftPt);
            if (marginRightPt != null) b.marginRightPt(marginRightPt);
            if (textIndentPt != null) b.textIndentPt(textIndentPt);
            if (lineHeightMultiplier != null) b.lineHeightMultiplier(lineHeightMultiplier);
            if (backgroundColor != null) b.backgroundColor(backgroundColor);
            return b.build();
        }
    }

    private static final class TextStyleInfo {
        String fontFamily;
        Float fontSizePt;
        Boolean bold;
        Boolean italic;
        Boolean underline;
        Boolean strike;
        String color;
        String backgroundColor;
        TextStyle.VerticalAlign verticalAlign;

        void apply(TextStyle style) {
            if (style == null) return;
            fontFamily = style.fontFamily;
            fontSizePt = style.fontSizePt;
            bold = style.bold;
            italic = style.italic;
            underline = style.underline;
            strike = style.strike;
            color = style.color;
            backgroundColor = style.backgroundColor;
            verticalAlign = style.verticalAlign;
        }

        TextStyle toStyleOrNull() {
            if (fontFamily == null && fontSizePt == null && bold == null && italic == null && underline == null
                    && strike == null && color == null && backgroundColor == null && verticalAlign == null) return null;
            return toStyle();
        }

        TextStyle toStyle() {
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

    private static final class PageAccumulator {
        private final ArrayList<RenderedPage> pages;
        private final int blockBudget;
        private final int charBudget;
        private final PageMetrics pageMetrics;
        private RenderedPage.Builder current;
        private int blocksUsed;
        private int charsUsed;
        private boolean pageHasContent;

        PageAccumulator(ArrayList<RenderedPage> pages, int blockBudget, int charBudget, PageMetrics pageMetrics) {
            this.pages = pages;
            this.blockBudget = Math.max(4, blockBudget);
            this.charBudget = Math.max(900, charBudget);
            this.pageMetrics = pageMetrics != null ? pageMetrics : PageMetrics.defaults();
            this.current = newPageBuilder(pages != null ? pages.size() : 0, this.pageMetrics);
        }

        void add(RenderedBlock block) {
            if (block == null) return;
            int cost = estimateBlockCost(block);
            int length = estimateTextLength(block);
            if (pageHasContent && (blocksUsed + cost > blockBudget || charsUsed + length > charBudget)) {
                breakPage();
            }
            current.addBlock(block);
            blocksUsed += cost;
            charsUsed += length;
            pageHasContent = true;
        }

        void breakPage() {
            if (!pageHasContent) return;
            pages.add(current.build());
            current = newPageBuilder(pages.size(), pageMetrics);
            blocksUsed = 0;
            charsUsed = 0;
            pageHasContent = false;
        }

        void finish() {
            if (pageHasContent || pages.isEmpty()) pages.add(current.build());
        }
    }
}
