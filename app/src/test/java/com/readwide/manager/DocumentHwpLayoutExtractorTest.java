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
        // The page side margin (marginLeft 5000 / paper width 59500) is a
        // responsive viewport clamp, not a fixed 50pt; cell padding above stays
        // in fixed points.
        assertTrue("page side margin should be a responsive clamp, html=" + html,
                html.contains("padding-left:clamp("));
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

    private static void put(ZipOutputStream out, String name, String text) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
