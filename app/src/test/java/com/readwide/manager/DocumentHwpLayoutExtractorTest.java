package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.readwide.manager.document.render.FixedHtmlRenderer;
import com.readwide.manager.document.render.RenderedBlock;
import com.readwide.manager.document.render.RenderedDocument;
import com.readwide.manager.document.render.RenderedPage;
import com.readwide.manager.document.render.RenderedTable;
import com.readwide.manager.document.render.RenderedTableCell;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DocumentHwpLayoutExtractorTest {
    @Test public void hwpxScriptElementsSurviveInlineColorOverrides() throws Exception {
        RenderedDocument doc = scriptFixture("textColor=\"0000cc\"");
        java.util.List<com.readwide.manager.document.render.RenderedRun> runs = doc.pages.get(0).blocks.get(0).paragraph.runs;
        assertEquals(com.readwide.manager.document.render.TextStyle.VerticalAlign.SUPERSCRIPT, runs.get(0).style.verticalAlign);
        assertEquals(com.readwide.manager.document.render.TextStyle.VerticalAlign.SUBSCRIPT, runs.get(1).style.verticalAlign);
        assertEquals(14f, runs.get(0).style.fontSizePt, 0f);
        assertEquals(Boolean.TRUE, runs.get(0).style.bold);
        assertEquals("#0000cc", runs.get(0).style.color);
        assertTrue(FixedHtmlRenderer.render(doc).contains("vertical-align:sub;"));
    }

    @Test public void hwpxExplicitBaselineCanClearInheritedScripts() throws Exception {
        RenderedDocument doc = scriptFixture("supscript=\"false\"");
        java.util.List<com.readwide.manager.document.render.RenderedRun> runs = doc.pages.get(0).blocks.get(0).paragraph.runs;
        assertEquals(com.readwide.manager.document.render.TextStyle.VerticalAlign.BASELINE, runs.get(0).style.verticalAlign);
        assertEquals(com.readwide.manager.document.render.TextStyle.VerticalAlign.SUBSCRIPT, runs.get(1).style.verticalAlign);
        assertEquals(Boolean.TRUE, runs.get(0).style.bold);
    }

    private static RenderedDocument scriptFixture(String inlineAttributes) throws Exception {
        File file = File.createTempFile("readwide-hwpx-scripts", ".hwpx");
        try {
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
                put(out, "Contents/header.xml", "<root xmlns:hh=\"http://www.hancom.co.kr/hwpml/2011/head\">"
                        + "<hh:charPr id=\"1\" height=\"1400\"><hh:bold/><hh:supscript/></hh:charPr>"
                        + "<hh:charPr id=\"2\" height=\"1400\"><hh:subscript/></hh:charPr></root>");
                put(out, "Contents/section0.xml", "<root xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\">"
                        + "<hp:p><hp:run charPrIDRef=\"1\" " + inlineAttributes + "><hp:t>2</hp:t></hp:run>"
                        + "<hp:run charPrIDRef=\"2\"><hp:t>i</hp:t></hp:run></hp:p></root>");
            }
            return DocumentHwpLayoutExtractor.extract(file, "scripts.hwpx", 30, 4800);
        } finally { file.delete(); }
    }

    @Test public void binaryPaperDefinitionPreservesLandscapeAndMargins() throws Exception {
        kr.dogfoot.hwplib.object.bodytext.control.sectiondefine.PageDef definition =
                new kr.dogfoot.hwplib.object.bodytext.control.sectiondefine.PageDef();
        definition.setPaperWidth(59500); definition.setPaperHeight(84200);
        definition.setLeftMargin(5000); definition.setRightMargin(3000);
        definition.setTopMargin(2000); definition.setBottomMargin(4000);
        definition.getProperty().setPaperDirection(kr.dogfoot.hwplib.object.bodytext.control.sectiondefine.PaperDirection.Landscape);
        java.lang.reflect.Method read = DocumentHwpLayoutExtractor.class.getDeclaredMethod("readBinaryPageMetrics", definition.getClass());
        read.setAccessible(true);
        java.lang.reflect.Method build = DocumentHwpLayoutExtractor.class.getDeclaredMethod("newPageBuilder", int.class, read.getReturnType());
        build.setAccessible(true);
        RenderedPage page = ((RenderedPage.Builder) build.invoke(null, 0, read.invoke(null, definition))).build();
        assertEquals(842f, page.widthPt, 0f);
        assertEquals(595f, page.heightPt, 0f);
        assertEquals(20f, page.marginTopPt, 0f);
        assertEquals(50f, page.marginLeftPt, 0f);
        RenderedPage fallback = ((RenderedPage.Builder) build.invoke(null, 0, read.invoke(null, new Object[]{null}))).build();
        assertEquals(595f, fallback.widthPt, 0f);
    }

    @Test public void binaryTableKeepsWidthVerticalAlignmentAndExplicitInsets() throws Exception {
        kr.dogfoot.hwplib.object.bodytext.control.ControlTable table = new kr.dogfoot.hwplib.object.bodytext.control.ControlTable();
        kr.dogfoot.hwplib.object.bodytext.control.table.Row row = table.addNewRow();
        addCell(row, "Cell", 0, 0, 1, 24000, 0);
        kr.dogfoot.hwplib.object.bodytext.control.table.ListHeaderForCell header = row.getCellList().get(0).getListHeader();
        header.getProperty().setTextVerticalAlignment(kr.dogfoot.hwplib.object.bodytext.control.gso.textbox.TextVerticalAlignment.Bottom);
        header.getProperty().setApplyInnerMagin(true);
        header.setTopMargin(200); header.setLeftMargin(400);
        RenderedTable rendered = DocumentHwpLayoutExtractor.renderedTableFromControl(table, null);
        assertEquals(240f, rendered.widthPt, 0f);
        assertEquals(RenderedTableCell.VerticalAlignment.BOTTOM, rendered.rows.get(0).get(0).verticalAlignment);
        assertEquals(2f, rendered.rows.get(0).get(0).paddingTopPt, 0f);
        assertEquals(4f, rendered.rows.get(0).get(0).paddingLeftPt, 0f);
    }

    @Test public void hwpxCellAlignmentDoesNotLeakFromNestedParagraphs() throws Exception {
        String xml = "<hp:tc xmlns:hp=\"urn:hp\"><hp:subList vertAlign=\"CENTER\">"
                + "<hp:p><hp:run><hp:t>Cell</hp:t></hp:run></hp:p></hp:subList></hp:tc>";
        org.w3c.dom.Document doc = DocumentArchiveUtils.secureDocumentBuilder().parse(
                new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Class<?> context = Class.forName("com.readwide.manager.DocumentHwpLayoutExtractor$HwpxDocumentContext");
        java.lang.reflect.Method read = DocumentHwpLayoutExtractor.class.getDeclaredMethod("readHwpxTableCell", org.w3c.dom.Node.class, context);
        read.setAccessible(true);
        RenderedTableCell cell = (RenderedTableCell) read.invoke(null, doc.getDocumentElement(), null);
        assertEquals(RenderedTableCell.VerticalAlignment.MIDDLE, cell.verticalAlignment);
        doc = DocumentArchiveUtils.secureDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                "<tc><p vertAlign=\"BOTTOM\"><t>Text</t></p></tc>".getBytes(StandardCharsets.UTF_8)));
        cell = (RenderedTableCell) read.invoke(null, doc.getDocumentElement(), null);
        assertTrue(cell.verticalAlignment == null);
    }

    @Test public void binaryStructuralBlocksUsePageBudgetsAndKeepSourceText() throws Exception {
        java.util.List<RenderedBlock> blocks = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) blocks.add(RenderedBlock.paragraph(
                com.readwide.manager.document.render.RenderedParagraph.of(
                        com.readwide.manager.document.render.RenderedRun.text("Paragraph " + i))));
        blocks.add(2, RenderedBlock.table(RenderedTable.ofRows(java.util.Collections.singletonList(
                java.util.Collections.singletonList(RenderedTableCell.text("Cell"))))));
        RenderedDocument doc = paginateStructuralBlocks(blocks, "Full extracted source text", 4, 4800);
        assertTrue(doc.pageCount() >= 3);
        assertEquals("Full extracted source text", doc.plainText);
        java.util.List<RenderedBlock> flattened = new java.util.ArrayList<>();
        for (int i = 0; i < doc.pages.size(); i++) {
            assertEquals(i, doc.pages.get(i).pageIndex);
            flattened.addAll(doc.pages.get(i).blocks);
        }
        assertEquals(blocks, flattened);
    }

    @Test public void structuralCharacterBudgetAlsoStartsANewPage() throws Exception {
        char[] text = new char[600];
        java.util.Arrays.fill(text, 'a');
        RenderedBlock block = RenderedBlock.paragraph(com.readwide.manager.document.render.RenderedParagraph.of(
                com.readwide.manager.document.render.RenderedRun.text(new String(text))));
        RenderedDocument doc = paginateStructuralBlocks(java.util.Arrays.asList(block, block), null, 30, 900);
        assertEquals(2, doc.pageCount());
        assertEquals(1, doc.pages.get(0).blocks.size());
        assertEquals(1, doc.pages.get(1).blocks.size());
    }

    private static RenderedDocument paginateStructuralBlocks(java.util.List<RenderedBlock> blocks,
                                                               String plainText, int paragraphs, int chars) throws Exception {
        Class<?> metrics = Class.forName("com.readwide.manager.DocumentHwpLayoutExtractor$PageMetrics");
        java.lang.reflect.Method build = DocumentHwpLayoutExtractor.class.getDeclaredMethod("buildDocument",
                String.class, String.class, java.util.List.class, int.class, int.class, metrics, String.class);
        build.setAccessible(true);
        return (RenderedDocument) build.invoke(null, "HWP fixture", "hwp", blocks, paragraphs, chars, null, plainText);
    }

    @Test
    public void extractsHwpxHeaderStylesRunStylesPageMetricsAndTableColor() throws Exception {
        File hwpx = File.createTempFile("readwide-hwpx-style", ".hwpx");
        hwpx.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(hwpx))) {
            put(out, "Contents/header.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<root xmlns:hh=\"http://www.hancom.co.kr/hwpml/2011/head\">" +
                    "<hh:paraProperties id=\"1\" bgColor=\"eeffee\"><hh:align horizontal=\"CENTER\"/>" +
                    "<hh:spacing lineSpacing=\"160\"/><hh:margin marginBottom=\"120\"/></hh:paraProperties>" +
                    "<hh:charProperties id=\"2\" height=\"1400\" textColor=\"cc0000\"><hh:bold/></hh:charProperties>" +
                    "<hh:paper width=\"59500\" height=\"84200\" marginLeft=\"5000\" marginRight=\"5000\" marginTop=\"5400\" marginBottom=\"5400\"/>" +
                    "</root>");
            put(out, "Contents/section0.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<root xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\">" +
                    "<hp:p paraPrIDRef=\"1\"><hp:run charPrIDRef=\"2\"><hp:t>Styled heading</hp:t></hp:run>" +
                    "<hp:run><hp:t> body</hp:t></hp:run></hp:p>" +
                    "<hp:tbl><hp:tr><hp:tc fill=\"ffeeaa\" paddingLeft=\"300\" paddingRight=\"400\" paddingTop=\"200\" paddingBottom=\"500\"><hp:p><hp:run><hp:t>Cell</hp:t></hp:run></hp:p></hp:tc></hp:tr></hp:tbl>" +
                    "</root>");
        }

        RenderedDocument document = DocumentHwpLayoutExtractor.extract(hwpx, "fixture.hwpx", 30, 4800);
        assertEquals("hwpx", document.sourceFormat);
        assertTrue(document.plainText.contains("Styled heading"));
        assertTrue(document.plainText.contains("Cell"));

        String html = FixedHtmlRenderer.render(document);
        assertTrue(html.contains("text-align:center"));
        assertTrue(html.contains("line-height:1.60"));
        assertTrue(html.contains("background-color:#eeffee"));
        assertTrue(html.contains("font-weight:bold"));
        assertTrue(html.contains("font-size:14pt"));
        assertTrue(html.contains("color:#cc0000"));
        assertTrue(html.contains("background-color:#ffeeaa"));
        assertTrue(html.contains("padding-top:10pt"));
        assertTrue(html.contains("padding-right:20pt"));
        assertTrue(html.contains("padding-bottom:25pt"));
        assertTrue(html.contains("padding-left:15pt"));
        assertTrue("page side margin should share the cell/text scale, html=" + html,
                html.contains("padding-left:calc(var(--rw-point,1pt) * 50);"));
    }

    @Test
    public void convertsBinaryHwpControlTableWithSpansWidthsAndBorders() throws Exception {
        // Two BorderFills: id 1 has no visible edges (layout grid), id 2 is solid.
        java.util.ArrayList<kr.dogfoot.hwplib.object.docinfo.BorderFill> borderFills =
                new java.util.ArrayList<>();
        kr.dogfoot.hwplib.object.docinfo.BorderFill noneBf =
                new kr.dogfoot.hwplib.object.docinfo.BorderFill();
        borderFills.add(noneBf); // id 1 -> all edges default to None
        kr.dogfoot.hwplib.object.docinfo.BorderFill solidBf =
                new kr.dogfoot.hwplib.object.docinfo.BorderFill();
        solidBf.getTopBorder().setType(kr.dogfoot.hwplib.object.docinfo.borderfill.BorderType.Solid);
        borderFills.add(solidBf); // id 2 -> visible

        kr.dogfoot.hwplib.object.bodytext.control.ControlTable table =
                new kr.dogfoot.hwplib.object.bodytext.control.ControlTable();
        // Row 0: two equal-width borderless cells.
        kr.dogfoot.hwplib.object.bodytext.control.table.Row row0 = table.addNewRow();
        addCell(row0, "발신", 0, 0, 1, 24590, 1);
        addCell(row0, "직통", 1, 0, 1, 24590, 1);
        // Row 1: a single colspan=2 cell with a visible border.
        kr.dogfoot.hwplib.object.bodytext.control.table.Row row1 = table.addNewRow();
        addCell(row1, "주소", 0, 1, 2, 49180, 2);

        RenderedTable rendered = DocumentHwpLayoutExtractor.renderedTableFromControl(table, borderFills);
        assertEquals(2, rendered.rows.size());
        assertEquals(2, rendered.rows.get(0).size());
        assertEquals(1, rendered.rows.get(1).size());

        // Row 0 cells: borderless, ~50% width each.
        assertEquals(Boolean.FALSE, rendered.rows.get(0).get(0).borderVisible);
        assertTrue(rendered.rows.get(0).get(0).widthPercent > 49f
                && rendered.rows.get(0).get(0).widthPercent < 51f);
        // Row 1 cell: colspan 2, visible border, full width.
        assertEquals(2, rendered.rows.get(1).get(0).colSpan);
        assertEquals(Boolean.TRUE, rendered.rows.get(1).get(0).borderVisible);

        // Cell text is preserved through to the rendered HTML.
        RenderedDocument doc = RenderedDocument.builder("hwp")
                .addPage(RenderedPage.builder(0).addBlock(RenderedBlock.table(rendered)).build())
                .build();
        String html = FixedHtmlRenderer.render(doc);
        assertTrue(html.contains("발신"));
        assertTrue(html.contains("주소"));
        assertTrue(html.contains("colspan=\"2\""));
    }

    @Test
    public void partiallyRuledBorderFillsRenderPerSideRulesNotFullBox() throws Exception {
        // Korean form templates frequently use BorderFills that draw only some
        // edges (e.g. a header band with top+bottom rules and no verticals, or a
        // section divider with a single top rule). These must NOT collapse into a
        // full box.
        java.util.ArrayList<kr.dogfoot.hwplib.object.docinfo.BorderFill> borderFills =
                new java.util.ArrayList<>();
        // id 1: top + bottom only (header band).
        kr.dogfoot.hwplib.object.docinfo.BorderFill topBottom =
                new kr.dogfoot.hwplib.object.docinfo.BorderFill();
        topBottom.getTopBorder().setType(kr.dogfoot.hwplib.object.docinfo.borderfill.BorderType.Solid);
        topBottom.getBottomBorder().setType(kr.dogfoot.hwplib.object.docinfo.borderfill.BorderType.Solid);
        borderFills.add(topBottom);
        // id 2: top only (single divider rule).
        kr.dogfoot.hwplib.object.docinfo.BorderFill topOnly =
                new kr.dogfoot.hwplib.object.docinfo.BorderFill();
        topOnly.getTopBorder().setType(kr.dogfoot.hwplib.object.docinfo.borderfill.BorderType.Solid);
        borderFills.add(topOnly);

        kr.dogfoot.hwplib.object.bodytext.control.ControlTable table =
                new kr.dogfoot.hwplib.object.bodytext.control.ControlTable();
        kr.dogfoot.hwplib.object.bodytext.control.table.Row row0 = table.addNewRow();
        addCell(row0, "제목", 0, 0, 1, 50000, 1);
        kr.dogfoot.hwplib.object.bodytext.control.table.Row row1 = table.addNewRow();
        addCell(row1, "구분선", 0, 1, 1, 50000, 2);

        RenderedTable rendered = DocumentHwpLayoutExtractor.renderedTableFromControl(table, borderFills);
        RenderedTableCell band = rendered.rows.get(0).get(0);
        assertEquals(Boolean.TRUE, band.borderTop);
        assertEquals(Boolean.TRUE, band.borderBottom);
        assertEquals(Boolean.FALSE, band.borderLeft);
        assertEquals(Boolean.FALSE, band.borderRight);
        RenderedTableCell divider = rendered.rows.get(1).get(0);
        assertEquals(Boolean.TRUE, divider.borderTop);
        assertEquals(Boolean.FALSE, divider.borderBottom);
        assertEquals(Boolean.FALSE, divider.borderLeft);
        assertEquals(Boolean.FALSE, divider.borderRight);

        RenderedDocument doc = RenderedDocument.builder("hwp")
                .addPage(RenderedPage.builder(0).addBlock(RenderedBlock.table(rendered)).build())
                .build();
        String html = FixedHtmlRenderer.render(doc);
        // The band must emit top+bottom rules but suppress side borders, and the
        // full-box "border-style:solid" path must not be used for these cells.
        assertTrue("band should keep top/bottom rules: " + html,
                html.contains("border-top:1px solid") && html.contains("border-bottom:1px solid"));
        assertTrue("side borders must be suppressed: " + html,
                html.contains("border-left:none") && html.contains("border-right:none"));
    }

    @Test
    public void tallLayoutCellPreservesMinHeightInsteadOfCollapsing() throws Exception {
        // An empty layout cell with a large authored height (e.g. an image
        // placeholder box) must not collapse to a single text line.
        java.util.ArrayList<kr.dogfoot.hwplib.object.docinfo.BorderFill> borderFills =
                new java.util.ArrayList<>();
        borderFills.add(new kr.dogfoot.hwplib.object.docinfo.BorderFill()); // id 1: borderless

        kr.dogfoot.hwplib.object.bodytext.control.ControlTable table =
                new kr.dogfoot.hwplib.object.bodytext.control.ControlTable();
        kr.dogfoot.hwplib.object.bodytext.control.table.Row row0 = table.addNewRow();
        // height 21000 HWPUNIT = 210pt; empty text.
        addCellWithHeight(row0, "", 0, 0, 1, 24590, 1, 21000);
        addCellWithHeight(row0, "", 1, 0, 1, 24590, 1, 21000);

        RenderedTable rendered = DocumentHwpLayoutExtractor.renderedTableFromControl(table, borderFills);
        RenderedTableCell c = rendered.rows.get(0).get(0);
        assertTrue("expected a min-height", c.minHeightPt != null);
        assertTrue("expected ~210pt min-height, was " + c.minHeightPt,
                c.minHeightPt > 209f && c.minHeightPt < 212f);

        RenderedDocument doc = RenderedDocument.builder("hwp")
                .addPage(RenderedPage.builder(0).addBlock(RenderedBlock.table(rendered)).build())
                .build();
        String html = FixedHtmlRenderer.render(doc);
        assertTrue("min-height should reach the rendered cell: " + html,
                html.contains("min-height:210"));
    }

    @Test
    public void shortCellDoesNotGetSpuriousMinHeight() throws Exception {
        // A normal short cell (below the 20pt threshold) should not carry a
        // min-height, so ordinary tables keep their natural line height.
        java.util.ArrayList<kr.dogfoot.hwplib.object.docinfo.BorderFill> borderFills =
                new java.util.ArrayList<>();
        borderFills.add(new kr.dogfoot.hwplib.object.docinfo.BorderFill());
        kr.dogfoot.hwplib.object.bodytext.control.ControlTable table =
                new kr.dogfoot.hwplib.object.bodytext.control.ControlTable();
        kr.dogfoot.hwplib.object.bodytext.control.table.Row row0 = table.addNewRow();
        addCellWithHeight(row0, "x", 0, 0, 1, 24590, 1, 1500); // 15pt < 20pt threshold
        RenderedTable rendered = DocumentHwpLayoutExtractor.renderedTableFromControl(table, borderFills);
        assertTrue("short cell must not carry min-height",
                rendered.rows.get(0).get(0).minHeightPt == null);
    }

    private static void addCellWithHeight(kr.dogfoot.hwplib.object.bodytext.control.table.Row row,
                                          String text, int colIndex, int rowIndex, int colSpan,
                                          long width, int borderFillId, long height) throws Exception {
        kr.dogfoot.hwplib.object.bodytext.control.table.Cell cell = row.addNewCell();
        kr.dogfoot.hwplib.object.bodytext.control.table.ListHeaderForCell h = cell.getListHeader();
        h.setColIndex(colIndex);
        h.setRowIndex(rowIndex);
        h.setColSpan(colSpan);
        h.setRowSpan(1);
        h.setWidth(width);
        h.setHeight(height);
        h.setBorderFillId(borderFillId);
        kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph p =
                cell.getParagraphList().addNewParagraph();
        p.createText();
        if (text != null && !text.isEmpty()) p.getText().addString(text);
    }

    private static void addCell(kr.dogfoot.hwplib.object.bodytext.control.table.Row row,
                                String text, int colIndex, int rowIndex, int colSpan,
                                long width, int borderFillId) throws Exception {
        kr.dogfoot.hwplib.object.bodytext.control.table.Cell cell = row.addNewCell();
        kr.dogfoot.hwplib.object.bodytext.control.table.ListHeaderForCell h = cell.getListHeader();
        h.setColIndex(colIndex);
        h.setRowIndex(rowIndex);
        h.setColSpan(colSpan);
        h.setRowSpan(1);
        h.setWidth(width);
        h.setBorderFillId(borderFillId);
        kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph p =
                cell.getParagraphList().addNewParagraph();
        p.createText();
        p.getText().addString(text);
    }

    @Test
    public void binaryHwpCellParagraphsCarryCharShapeSizeAndBold() throws Exception {
        // A cell whose paragraph references a 30pt bold char shape must render
        // with that size and weight, not the flat default, so the official-letter
        // title (which lives inside a single-cell table) keeps its hierarchy.
        kr.dogfoot.hwplib.object.HWPFile hwp = new kr.dogfoot.hwplib.object.HWPFile();
        kr.dogfoot.hwplib.object.docinfo.CharShape cs = hwp.getDocInfo().addNewCharShape();
        cs.setBaseSize(3000); // 30pt
        cs.getProperty().setBold(true);
        hwp.getDocInfo().addNewParaShape(); // paraShapeId 0

        java.util.ArrayList<kr.dogfoot.hwplib.object.docinfo.BorderFill> borderFills =
                new java.util.ArrayList<>();
        borderFills.add(new kr.dogfoot.hwplib.object.docinfo.BorderFill()); // id1 none

        kr.dogfoot.hwplib.object.bodytext.control.ControlTable table =
                new kr.dogfoot.hwplib.object.bodytext.control.ControlTable();
        kr.dogfoot.hwplib.object.bodytext.control.table.Row row = table.addNewRow();
        kr.dogfoot.hwplib.object.bodytext.control.table.Cell cell = row.addNewCell();
        kr.dogfoot.hwplib.object.bodytext.control.table.ListHeaderForCell h = cell.getListHeader();
        h.setColIndex(0);
        h.setRowIndex(0);
        h.setColSpan(1);
        h.setRowSpan(1);
        h.setWidth(48000);
        h.setBorderFillId(1);
        kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph cp =
                cell.getParagraphList().addNewParagraph();
        cp.getHeader().setParaShapeId(0);
        cp.createText();
        cp.getText().addString("공   문");
        cp.createCharShape();
        cp.getCharShape().addParaCharShape(0, 0); // position 0 -> charShapeId 0

        RenderedTable rendered = DocumentHwpLayoutExtractor.renderedTableFromControl(table, borderFills, hwp);
        RenderedDocument doc = RenderedDocument.builder("hwp")
                .addPage(RenderedPage.builder(0).addBlock(RenderedBlock.table(rendered)).build())
                .build();
        String html = FixedHtmlRenderer.render(doc);
        assertTrue("title size missing, html=" + html, html.contains("font-size:30pt"));
        assertTrue("title bold missing, html=" + html, html.contains("font-weight:bold"));
        assertTrue(html.contains("공   문"));
    }


    @Test
    public void binaryHwpRunStyleCarriesColorAndUnderline() throws Exception {
        kr.dogfoot.hwplib.object.HWPFile hwp = new kr.dogfoot.hwplib.object.HWPFile();
        kr.dogfoot.hwplib.object.docinfo.CharShape cs = hwp.getDocInfo().addNewCharShape();
        cs.setBaseSize(1100);
        cs.getProperty().setUnderLineSort(
                kr.dogfoot.hwplib.object.docinfo.charshape.UnderLineSort.Bottom);
        cs.getCharColor().setValue(0x0000FF); // R=0 G=0 B=255 packed little-endian style
        hwp.getDocInfo().addNewParaShape();

        java.util.ArrayList<kr.dogfoot.hwplib.object.docinfo.BorderFill> bf = new java.util.ArrayList<>();
        bf.add(new kr.dogfoot.hwplib.object.docinfo.BorderFill());
        kr.dogfoot.hwplib.object.bodytext.control.ControlTable table =
                new kr.dogfoot.hwplib.object.bodytext.control.ControlTable();
        kr.dogfoot.hwplib.object.bodytext.control.table.Row row = table.addNewRow();
        kr.dogfoot.hwplib.object.bodytext.control.table.Cell cell = row.addNewCell();
        kr.dogfoot.hwplib.object.bodytext.control.table.ListHeaderForCell h = cell.getListHeader();
        h.setColIndex(0); h.setRowIndex(0); h.setColSpan(1); h.setRowSpan(1);
        h.setWidth(10000); h.setBorderFillId(1);
        kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph cp = cell.getParagraphList().addNewParagraph();
        cp.getHeader().setParaShapeId(0);
        cp.createText(); cp.getText().addString("링크");
        cp.createCharShape(); cp.getCharShape().addParaCharShape(0, 0);

        RenderedTable rendered = DocumentHwpLayoutExtractor.renderedTableFromControl(table, bf, hwp);
        RenderedDocument doc = RenderedDocument.builder("hwp")
                .addPage(RenderedPage.builder(0).addBlock(RenderedBlock.table(rendered)).build())
                .build();
        String html = FixedHtmlRenderer.render(doc);
        assertTrue("underline missing, html=" + html, html.contains("text-decoration:underline"));
        assertTrue("non-black color missing, html=" + html, html.contains("color:#"));
    }

    @Test
    public void base64EncodeMatchesJdkEncoder() {
        java.util.Random random = new java.util.Random(42);
        for (int len = 0; len <= 64; len++) {
            byte[] data = new byte[len];
            random.nextBytes(data);
            assertEquals("length " + len,
                    java.util.Base64.getEncoder().encodeToString(data),
                    DocumentHwpLayoutExtractor.base64Encode(data));
        }
    }

    @Test
    public void extractsHwpxEmbeddedImageAsDataUri() throws Exception {
        // Minimal valid 1x1 PNG.
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC");
        File hwpx = File.createTempFile("readwide-hwpx-image", ".hwpx");
        hwpx.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(hwpx))) {
            put(out, "Contents/content.hpf",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<opf:package xmlns:opf=\"http://www.idpf.org/2007/opf/\">" +
                    "<opf:manifest><opf:item id=\"picref\" href=\"BinData/photo.png\" media-type=\"image/png\"/>" +
                    "</opf:manifest></opf:package>");
            out.putNextEntry(new ZipEntry("BinData/photo.png"));
            out.write(png);
            out.closeEntry();
            put(out, "Contents/section0.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<root xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\"" +
                    " xmlns:hc=\"http://www.hancom.co.kr/hwpml/2011/core\">" +
                    "<hp:p><hp:run><hp:t>Before image</hp:t>" +
                    "<hp:pic><hp:curSz width=\"20000\" height=\"10000\"/>" +
                    "<hc:img binaryItemIDRef=\"picref\"/></hp:pic>" +
                    "</hp:run></hp:p>" +
                    "</root>");
        }

        RenderedDocument document = DocumentHwpLayoutExtractor.extract(hwpx, "fixture.hwpx", 30, 4800);
        assertEquals("hwpx", document.sourceFormat);
        assertTrue(document.plainText.contains("Before image"));

        String html = FixedHtmlRenderer.render(document);
        assertTrue("data URI image missing, html=" + html.substring(0, Math.min(400, html.length())),
                html.contains("data:image/png;base64,"));
        // 20000/10000 HWPUNIT -> 200pt x 100pt.
        assertTrue("image width missing", html.contains("width:200pt"));
        assertTrue("image height missing", html.contains("height:100pt"));
    }

    @Test
    public void hwpxEmfImageRendersPlaceholderInsteadOfVanishing() throws Exception {
        // An EMF byte stream (ENHMETA magic " EMF" at offset 40, record type 1)
        // is a real embedded picture with no WebView-decodable raster form. It
        // must render as a placeholder frame at the authored size, not silently
        // disappear.
        byte[] emf = new byte[128];
        emf[0] = 0x01;                 // EMR_HEADER record type (LE)
        emf[40] = 0x20; emf[41] = 0x45; // " E"
        emf[42] = 0x4D; emf[43] = 0x46; // "MF"  -> " EMF" signature
        File hwpx = File.createTempFile("readwide-hwpx-emf", ".hwpx");
        hwpx.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(hwpx))) {
            put(out, "Contents/content.hpf",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<opf:package xmlns:opf=\"http://www.idpf.org/2007/opf/\">" +
                    "<opf:manifest><opf:item id=\"emfref\" href=\"BinData/diagram.emf\" media-type=\"application/octet-stream\"/>" +
                    "</opf:manifest></opf:package>");
            out.putNextEntry(new ZipEntry("BinData/diagram.emf"));
            out.write(emf);
            out.closeEntry();
            put(out, "Contents/section0.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<root xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\"" +
                    " xmlns:hc=\"http://www.hancom.co.kr/hwpml/2011/core\">" +
                    "<hp:p><hp:run><hp:t>Diagram below</hp:t>" +
                    "<hp:pic><hp:curSz width=\"18000\" height=\"12000\"/>" +
                    "<hc:img binaryItemIDRef=\"emfref\"/></hp:pic>" +
                    "</hp:run></hp:p>" +
                    "</root>");
        }

        RenderedDocument document = DocumentHwpLayoutExtractor.extract(hwpx, "fixture.hwpx", 30, 4800);
        assertEquals("hwpx", document.sourceFormat);
        assertTrue(document.plainText.contains("Diagram below"));

        String html = FixedHtmlRenderer.render(document);
        assertTrue("placeholder frame missing, html=" + html.substring(0, Math.min(500, html.length())),
                html.contains("rw-image-missing"));
        // The unrenderable binary must not have leaked in as a data URI.
        assertTrue("EMF should not become a raster data URI", !html.contains("data:image"));
        // 18000/12000 HWPUNIT -> 180pt x 120pt authored size on the placeholder.
        assertTrue("placeholder width missing", html.contains("width:180pt"));
        assertTrue("placeholder height missing", html.contains("height:120pt"));
    }

    private static void put(ZipOutputStream out, String name, String text) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
