package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.readwide.manager.document.render.FixedHtmlRenderer;
import com.readwide.manager.document.render.RenderedDocument;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class DocumentDocxLayoutExtractorTest {
    @Test public void officeMathScriptsKeepPositionAndWordRunStyle() throws Exception {
        RenderedDocument doc = extractPreviewFixture("<w:p xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\">"
                + "<m:oMath><m:sSubSup><m:e><m:r><m:t>x</m:t></m:r></m:e>"
                + "<m:sub><m:r><m:rPr><m:sty m:val=\"p\"/></m:rPr><w:rPr><w:b/><w:color w:val=\"aa0000\"/></w:rPr>"
                + "<m:t>i</m:t></m:r></m:sub><m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSubSup></m:oMath>"
                + "<w:r><w:t> done</w:t></w:r></w:p>", "");
        java.util.List<com.readwide.manager.document.render.RenderedRun> runs = doc.pages.get(0).blocks.get(0).paragraph.runs;
        assertEquals("xi2 done", doc.plainText);
        assertEquals(com.readwide.manager.document.render.TextStyle.VerticalAlign.SUBSCRIPT, runs.get(1).style.verticalAlign);
        assertEquals(com.readwide.manager.document.render.TextStyle.VerticalAlign.SUPERSCRIPT, runs.get(2).style.verticalAlign);
        assertEquals(Boolean.TRUE, runs.get(1).style.bold);
        assertEquals("#aa0000", runs.get(1).style.color);
        assertFalse(runs.get(0).style.verticalAlignSpecified);
        assertFalse(runs.get(3).style.verticalAlignSpecified);
    }

    @Test public void scriptPositionsRequireOfficeMathNamespaceAndOwner() throws Exception {
        RenderedDocument doc = extractPreviewFixture("<w:p xmlns:m=\"urn:not-math\">"
                + "<m:sSub><m:sub><w:r><w:t>Plain</w:t></w:r></m:sub></m:sSub>"
                + "<w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr><w:t>2</w:t></w:r>"
                + "<w:r><w:rPr><w:vertAlign w:val=\"baseline\"/></w:rPr><w:t>Base</w:t></w:r></w:p>", "");
        java.util.List<com.readwide.manager.document.render.RenderedRun> runs = doc.pages.get(0).blocks.get(0).paragraph.runs;
        assertFalse(runs.get(0).style.verticalAlignSpecified);
        assertEquals(com.readwide.manager.document.render.TextStyle.VerticalAlign.SUPERSCRIPT, runs.get(1).style.verticalAlign);
        assertEquals(com.readwide.manager.document.render.TextStyle.VerticalAlign.BASELINE, runs.get(2).style.verticalAlign);
    }

    @Test public void tableKeepsFixedWidthCenteringRowHeightAndCellAlignment() throws Exception {
        RenderedDocument doc = extractPreviewFixture("<w:tbl><w:tblPr><w:tblW w:type=\"dxa\" w:w=\"6000\"/>"
                + "<w:jc w:val=\"center\"/><w:tblCellMar><w:top w:w=\"120\"/><w:left w:w=\"180\"/></w:tblCellMar></w:tblPr>"
                + "<w:tr><w:trPr><w:trHeight w:val=\"1600\" w:hRule=\"exact\"/></w:trPr>"
                + "<w:tc><w:tcPr><w:vAlign w:val=\"center\"/><w:tcMar><w:top w:w=\"0\"/></w:tcMar></w:tcPr>"
                + "<w:p><w:r><w:t>Form cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>", "");
        com.readwide.manager.document.render.RenderedTable table = doc.pages.get(0).blocks.get(0).table;
        com.readwide.manager.document.render.RenderedTableCell cell = table.rows.get(0).get(0);
        assertEquals(300f, table.widthPt, 0f);
        assertEquals(com.readwide.manager.document.render.ParagraphStyle.Alignment.CENTER, table.alignment);
        assertEquals(80f, cell.minHeightPt, 0f);
        assertEquals(0f, cell.paddingTopPt, 0f);
        assertEquals(9f, cell.paddingLeftPt, 0f);
        assertEquals(com.readwide.manager.document.render.RenderedTableCell.VerticalAlignment.MIDDLE, cell.verticalAlignment);
        assertTrue(FixedHtmlRenderer.render(doc).contains("margin-left:auto;margin-right:auto;"));
    }

    @Test public void leftTableKeepsIndentWithoutTurningPercentageWidthIntoPoints() throws Exception {
        RenderedDocument doc = extractPreviewFixture("<w:tbl><w:tblPr><w:tblW w:type=\"pct\" w:w=\"2500\"/>"
                + "<w:tblInd w:type=\"dxa\" w:w=\"400\"/></w:tblPr><w:tr><w:tc><w:p><w:r><w:t>Cell</w:t>"
                + "</w:r></w:p></w:tc></w:tr></w:tbl>", "");
        com.readwide.manager.document.render.RenderedTable table = doc.pages.get(0).blocks.get(0).table;
        assertEquals(50f, table.widthPercent, 0f);
        assertTrue(table.widthPt == null);
        assertEquals(20f, table.indentPt, 0f);
        assertTrue(FixedHtmlRenderer.render(doc).contains("margin-left:calc(var(--rw-point,1pt) * 20);"));
    }

    @Test public void cellsWithoutPropertiesDoNotInventGridLines() throws Exception {
        RenderedDocument doc = extractPreviewFixture("<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Borderless form</w:t>"
                + "</w:r></w:p></w:tc></w:tr></w:tbl>", "");
        assertEquals(Boolean.FALSE, doc.pages.get(0).blocks.get(0).table.rows.get(0).get(0).borderVisible);
        assertTrue(FixedHtmlRenderer.render(doc).contains("<td style=\"border:none;"));
    }

    @Test public void textBoxKeepsStyledParagraphsAndTablesWithoutFallbackDuplicates() throws Exception {
        RenderedDocument doc = extractPreviewFixture("<w:p><w:r><w:t>Before</w:t></w:r></w:p>"
                + "<w:p><w:r><mc:AlternateContent><mc:Choice Requires=\"wps\"><w:drawing><wp:anchor>"
                + "<wps:wsp><wps:txbx><w:txbxContent>"
                + "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Box text</w:t></w:r></w:p>"
                + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Box cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                + "</w:txbxContent></wps:txbx></wps:wsp></wp:anchor></w:drawing></mc:Choice>"
                + "<mc:Fallback><w:pict><v:shape><v:textbox><w:txbxContent>"
                + "<w:p><w:r><w:t>Box text</w:t><w:br w:type=\"page\"/></w:r></w:p>"
                + "</w:txbxContent></v:textbox></v:shape></w:pict></mc:Fallback></mc:AlternateContent></w:r></w:p>"
                + "<w:p><w:r><w:t>After</w:t></w:r></w:p>", "");
        assertEquals(1, doc.pageCount());
        assertEquals(4, doc.pages.get(0).blocks.size());
        assertEquals("Box text", doc.pages.get(0).blocks.get(1).paragraph.plainText());
        assertEquals(Boolean.TRUE, doc.pages.get(0).blocks.get(1).paragraph.runs.get(0).style.bold);
        assertEquals("Box cell", doc.pages.get(0).blocks.get(2).table.rows.get(0).get(0).blocks.get(0).paragraph.plainText());
        assertEquals(doc.plainText.indexOf("Box text"), doc.plainText.lastIndexOf("Box text"));
        assertFalse(FixedHtmlRenderer.render(doc).contains("Text box content is shown"));
    }

    @Test public void alternativesRequireEveryNamespaceAndRetainNestedNamespaceContext() throws Exception {
        RenderedDocument doc = extractPreviewFixture("<mc:AlternateContent xmlns:sh=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\""
                + " xmlns:future=\"urn:future-word-feature\">"
                + "<mc:Choice Requires=\"sh future\"><w:p><w:r><w:t>Unsupported</w:t></w:r></w:p></mc:Choice>"
                + "<mc:Fallback><mc:AlternateContent><mc:Choice Requires=\"sh\">"
                + "<w:p><w:r><w:pict><v:shape><v:textbox><w:txbxContent>"
                + "<w:p><w:r><w:t>Selected box</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>"
                + "</mc:Choice><mc:Fallback><w:p><w:r><w:t>Wrong fallback</w:t></w:r></w:p></mc:Fallback>"
                + "</mc:AlternateContent></mc:Fallback></mc:AlternateContent>", "");
        assertEquals("Selected box", doc.plainText);
    }

    @Test public void unsupportedImageChoiceUsesItsVmlFallbackOnce() throws Exception {
        RenderedDocument doc = extractPreviewFixture("<w:p><w:r><mc:AlternateContent xmlns:future=\"urn:future-image\">"
                + "<mc:Choice Requires=\"future\"><w:drawing><wp:inline><a:blip r:embed=\"img\"/></wp:inline></w:drawing></mc:Choice>"
                + "<mc:Fallback><w:pict><v:shape><v:imagedata r:id=\"img\"/></v:shape></w:pict></mc:Fallback>"
                + "</mc:AlternateContent></w:r></w:p>",
                "<Relationship Id=\"img\" Target=\"media/preview.png\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"/>");
        assertEquals(1, doc.pages.get(0).blocks.size());
        assertEquals("https://readwide.local/word/media/preview.png", doc.pages.get(0).blocks.get(0).image.src);
    }

    @Test public void drawingDoesNotDropPicturesAfterItsFirstBlip() throws Exception {
        RenderedDocument doc = extractPreviewFixture("<w:p><w:r><w:drawing><wp:inline>"
                + "<a:blip r:embed=\"one\"/><a:blip r:embed=\"two\"/></wp:inline></w:drawing></w:r></w:p>",
                "<Relationship Id=\"one\" Target=\"media/one.png\"/><Relationship Id=\"two\" Target=\"media/two.png\"/>");
        assertEquals(2, doc.pages.get(0).blocks.size());
        assertTrue(doc.pages.get(0).blocks.get(0).image.src.endsWith("/one.png"));
        assertTrue(doc.pages.get(0).blocks.get(1).image.src.endsWith("/two.png"));
    }

    private static RenderedDocument extractPreviewFixture(String body, String relationships) throws Exception {
        File file = File.createTempFile("readwide-word-preview", ".docx");
        try {
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
                put(out, "word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
                        + " xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" xmlns:v=\"urn:schemas-microsoft-com:vml\""
                        + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
                        + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
                        + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                        + " xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">"
                        + "<w:body>" + body + "</w:body></w:document>");
                put(out, "word/_rels/document.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + relationships + "</Relationships>");
            }
            try (ZipFile zip = new ZipFile(file)) {
                return DocumentDocxLayoutExtractor.extract(zip, "preview", "readwide.local", 28);
            }
        } finally { file.delete(); }
    }

    @Test public void preservesWrappedContentBlankParagraphsAndSpecialHyphens() throws Exception {
        File file = File.createTempFile("readwide-word-content", ".docx");
        try {
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
                put(out, "word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                        + "<w:sdt><w:sdtPr><w:p><w:r><w:t>Metadata</w:t></w:r></w:p></w:sdtPr><w:sdtContent>"
                        + "<w:customXml><w:p><w:r><w:t>First</w:t></w:r></w:p></w:customXml>"
                        + "<w:p/><w:p><w:r><w:t xml:space=\"preserve\">  </w:t></w:r></w:p>"
                        + "<w:tbl><w:sdt><w:sdtContent><w:tr><w:customXml><w:tc>"
                        + "<w:sdt><w:sdtContent><w:p><w:r><w:t>Cell</w:t></w:r></w:p></w:sdtContent></w:sdt>"
                        + "</w:tc></w:customXml></w:tr></w:sdtContent></w:sdt></w:tbl>"
                        + "</w:sdtContent></w:sdt>"
                        + "<w:p><w:sdt><w:sdtPr><w:r><w:t>Metadata</w:t></w:r></w:sdtPr><w:sdtContent>"
                        + "<w:r><w:t>A</w:t><w:noBreakHyphen/><w:t>B</w:t><w:softHyphen/><w:t>C</w:t></w:r>"
                        + "</w:sdtContent></w:sdt><w:moveFrom><w:r><w:t>Old</w:t></w:r></w:moveFrom>"
                        + "<w:del><w:r><w:delText>Deleted</w:delText></w:r></w:del>"
                        + "<w:moveTo><w:r><w:t>New</w:t></w:r></w:moveTo></w:p>"
                        + "</w:body></w:document>");
            }
            try (ZipFile zip = new ZipFile(file)) {
                RenderedDocument doc = DocumentDocxLayoutExtractor.extract(zip, "content", "readwide.local", 28);
                assertEquals(5, doc.pages.get(0).blocks.size());
                assertEquals("First", doc.pages.get(0).blocks.get(0).paragraph.plainText());
                assertEquals("", doc.pages.get(0).blocks.get(1).paragraph.plainText());
                assertEquals("  ", doc.pages.get(0).blocks.get(2).paragraph.plainText());
                assertEquals("Cell", doc.pages.get(0).blocks.get(3).table.rows.get(0).get(0).blocks.get(0).paragraph.plainText());
                assertEquals("A\u2011B\u00adCNew", doc.pages.get(0).blocks.get(4).paragraph.plainText());
                assertFalse(doc.plainText.contains("Metadata"));
                assertFalse(doc.plainText.contains("Old"));
                assertFalse(doc.plainText.contains("Deleted"));
                assertTrue(FixedHtmlRenderer.render(doc).contains("&#8203;"));
            }
        } finally { file.delete(); }
    }

    @Test public void unwrapsContentControlsInHeadersAndNotes() throws Exception {
        File file = File.createTempFile("readwide-word-parts", ".docx");
        String ns = " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"";
        try {
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
                put(out, "word/document.xml", "<w:document" + ns + "><w:body><w:p><w:r><w:t>Body</w:t>"
                        + "<w:footnoteReference w:id=\"1\"/></w:r></w:p><w:sectPr>"
                        + "<w:headerReference w:type=\"default\" r:id=\"h1\"/></w:sectPr></w:body></w:document>");
                put(out, "word/_rels/document.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"h1\" Target=\"header1.xml\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\"/></Relationships>");
                put(out, "word/header1.xml", "<w:hdr" + ns + "><w:sdt><w:sdtContent><w:p><w:r><w:t>Wrapped header</w:t>"
                        + "</w:r></w:p></w:sdtContent></w:sdt></w:hdr>");
                put(out, "word/footnotes.xml", "<w:footnotes" + ns + "><w:footnote w:id=\"1\"><w:sdt><w:sdtContent>"
                        + "<w:p><w:r><w:t>Wrapped note</w:t></w:r></w:p></w:sdtContent></w:sdt></w:footnote></w:footnotes>");
            }
            try (ZipFile zip = new ZipFile(file)) {
                RenderedDocument doc = DocumentDocxLayoutExtractor.extract(zip, "parts", "readwide.local", 28);
                assertEquals("Wrapped header", doc.pages.get(0).headerBlocks.get(0).paragraph.plainText());
                assertTrue(doc.plainText.contains("Wrapped note"));
            }
        } finally { file.delete(); }
    }

    @Test public void coalescesLongRunGroupsWithoutLosingLinksOrStyleBoundaries() throws Exception {
        java.util.List<com.readwide.manager.document.render.RenderedRun> runs = new java.util.ArrayList<>();
        for (int i = 0; i < 4096; i++) runs.add(com.readwide.manager.document.render.RenderedRun.text("x"));
        com.readwide.manager.document.render.RenderedRun styled = com.readwide.manager.document.render.RenderedRun.text(
                "bold", new com.readwide.manager.document.render.TextStyle.Builder().bold(true).build());
        runs.add(styled);
        com.readwide.manager.document.render.RenderedRun link = com.readwide.manager.document.render.RenderedRun.link(
                "note", com.readwide.manager.document.render.TextStyle.plain(), "#note-1", "ref-1");
        runs.add(link);
        runs.add(com.readwide.manager.document.render.RenderedRun.text("end"));
        java.lang.reflect.Method method = DocumentDocxLayoutExtractor.class.getDeclaredMethod("coalesceAdjacentTextRuns", java.util.List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked") java.util.List<com.readwide.manager.document.render.RenderedRun> result =
                (java.util.List<com.readwide.manager.document.render.RenderedRun>) method.invoke(null, runs);
        assertEquals(4, result.size());
        assertEquals(4096, result.get(0).text.length());
        org.junit.Assert.assertSame(styled, result.get(1));
        org.junit.Assert.assertSame(link, result.get(2));
        assertEquals("end", result.get(3).text);
    }

    @Test public void explicitStyleResetsAndHighlightPrecedenceSurviveInheritance() throws Exception {
        File file = File.createTempFile("readwide-word-styles", ".docx");
        try {
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
                String ns = " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";
                put(out, "word/styles.xml", "<w:styles" + ns + ">"
                        + "<w:style w:type=\"paragraph\" w:styleId=\"Base\">"
                        + "<w:pPr><w:jc w:val=\"center\"/><w:spacing w:line=\"400\" w:lineRule=\"exact\"/></w:pPr>"
                        + "<w:rPr><w:vertAlign w:val=\"superscript\"/><w:highlight w:val=\"yellow\"/></w:rPr></w:style>"
                        + "<w:style w:type=\"paragraph\" w:styleId=\"Reset\"><w:basedOn w:val=\"Base\"/>"
                        + "<w:pPr><w:jc w:val=\"left\"/><w:spacing w:line=\"360\" w:lineRule=\"auto\"/></w:pPr>"
                        + "<w:rPr><w:vertAlign w:val=\"baseline\"/></w:rPr></w:style></w:styles>");
                put(out, "word/document.xml", "<w:document" + ns + "><w:body>"
                        + "<w:p><w:pPr><w:pStyle w:val=\"Base\"/></w:pPr><w:r><w:t>Inherited</w:t></w:r></w:p>"
                        + "<w:p><w:pPr><w:pStyle w:val=\"Reset\"/></w:pPr>"
                        + "<w:r><w:rPr><w:shd w:fill=\"aabbcc\"/></w:rPr><w:t>Highlighted</w:t></w:r>"
                        + "<w:r><w:rPr><w:shd w:fill=\"aabbcc\"/><w:highlight w:val=\"none\"/></w:rPr><w:t>Shaded</w:t></w:r></w:p>"
                        + "<w:p><w:pPr><w:pStyle w:val=\"Base\"/><w:jc w:val=\"left\"/>"
                        + "<w:spacing w:line=\"240\" w:lineRule=\"auto\"/></w:pPr>"
                        + "<w:r><w:rPr><w:vertAlign w:val=\"baseline\"/></w:rPr><w:t>DirectReset</w:t></w:r></w:p>"
                        + "</w:body></w:document>");
            }
            try (ZipFile zip = new ZipFile(file)) {
                RenderedDocument doc = DocumentDocxLayoutExtractor.extract(zip, "styles", "readwide.local", 28);
                com.readwide.manager.document.render.RenderedParagraph inherited =
                        doc.pages.get(0).blocks.get(0).paragraph;
                com.readwide.manager.document.render.RenderedParagraph reset =
                        doc.pages.get(0).blocks.get(1).paragraph;
                com.readwide.manager.document.render.RenderedParagraph direct =
                        doc.pages.get(0).blocks.get(2).paragraph;
                assertEquals(com.readwide.manager.document.render.ParagraphStyle.Alignment.CENTER, inherited.style.alignment);
                assertEquals(com.readwide.manager.document.render.TextStyle.VerticalAlign.SUPERSCRIPT, inherited.runs.get(0).style.verticalAlign);
                for (com.readwide.manager.document.render.RenderedParagraph p : new com.readwide.manager.document.render.RenderedParagraph[] {reset, direct}) {
                    assertEquals(com.readwide.manager.document.render.ParagraphStyle.Alignment.LEFT, p.style.alignment);
                    assertEquals(com.readwide.manager.document.render.TextStyle.VerticalAlign.BASELINE, p.runs.get(0).style.verticalAlign);
                    org.junit.Assert.assertNull(p.style.lineHeightPt);
                }
                assertEquals(1.5f, reset.style.lineHeightMultiplier, 0.001f);
                assertEquals(1f, direct.style.lineHeightMultiplier, 0.001f);
                assertEquals("#ffff00", reset.runs.get(0).style.highlightColor);
                assertEquals("#aabbcc", reset.runs.get(0).style.backgroundColor);
                assertEquals("none", reset.runs.get(1).style.highlightColor);
                String html = FixedHtmlRenderer.render(doc);
                assertTrue(html.contains("background-color:#ffff00;vertical-align:baseline;\">Highlighted"));
                assertTrue(html.contains("background-color:#aabbcc;vertical-align:baseline;\">Shaded"));
            }
        } finally { file.delete(); }
    }

    @Test
    public void extractsParagraphStyleTableAndImageIntoRenderedDocument() throws Exception {
        File docx = File.createTempFile("readwide-docx-layout", ".docx");
        docx.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(docx))) {
            put(out, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
                    "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
                    "<w:body>" +
                    "<w:p><w:pPr><w:jc w:val=\"center\"/><w:shd w:fill=\"ddffee\"/></w:pPr>" +
                    "<w:r><w:rPr><w:b/><w:u w:val=\"single\"/><w:color w:val=\"cc0000\"/><w:sz w:val=\"28\"/></w:rPr><w:t>Proposal</w:t></w:r>" +
                    "</w:p>" +
                    "<w:p><w:r><w:drawing><a:blip r:embed=\"rId1\"/></w:drawing></w:r></w:p>" +
                    "<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/><w:shd w:fill=\"ffeeaa\"/></w:tcPr><w:p><w:r><w:t>Merged</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" +
                    "<w:sectPr><w:pgSz w:w=\"11900\" w:h=\"16840\"/><w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\"/></w:sectPr>" +
                    "</w:body></w:document>");
            put(out, "word/_rels/document.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Target=\"media/image1.png\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"/>" +
                    "</Relationships>");
            put(out, "word/media/image1.png", "png");
        }

        try (ZipFile zip = new ZipFile(docx)) {
            RenderedDocument document = DocumentDocxLayoutExtractor.extract(zip, "fixture.docx", "readwide.local", 28);
            assertEquals("docx", document.sourceFormat);
            assertEquals(1, document.pageCount());
            assertTrue(document.plainText.contains("Proposal"));
            assertTrue(document.plainText.contains("Merged"));

            String html = FixedHtmlRenderer.render(document);
            assertTrue(html.contains("text-align:center"));
            assertTrue(html.contains("background-color:#ddffee"));
            assertTrue(html.contains("font-weight:bold"));
            assertTrue(html.contains("text-decoration:underline"));
            assertTrue(html.contains("color:#cc0000"));
            assertTrue(html.contains("font-size:14pt"));
            assertTrue(html.contains("src=\"https://readwide.local/word/media/image1.png\""));
            assertTrue(html.contains("colspan=\"2\""));
            assertTrue(html.contains("background-color:#ffeeaa"));
            // Both axes and source text use the same fitted page point.
            assertTrue("page top margin should share the page scale, html=" + html,
                    html.contains("padding-top:calc(var(--rw-point,1pt) * 36);"));
        }
    }

    @Test
    public void extractsNumberingXmlListsIntoRenderedParagraphMarkers() throws Exception {
        File docx = File.createTempFile("readwide-docx-numbering", ".docx");
        docx.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(docx))) {
            put(out, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body>" +
                    "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr><w:r><w:t>First ordered item</w:t></w:r></w:p>" +
                    "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr><w:r><w:t>Second ordered item</w:t></w:r></w:p>" +
                    "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"2\"/></w:numPr></w:pPr><w:r><w:t>Bullet item</w:t></w:r></w:p>" +
                    "</w:body></w:document>");
            put(out, "word/numbering.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:abstractNum w:abstractNumId=\"10\">" +
                    "<w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/><w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl>" +
                    "</w:abstractNum>" +
                    "<w:abstractNum w:abstractNumId=\"20\">" +
                    "<w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"&#xF0B7;\"/>" +
                    "<w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr>" +
                    "<w:rPr><w:rFonts w:ascii=\"Symbol\" w:hAnsi=\"Symbol\"/></w:rPr></w:lvl>" +
                    "</w:abstractNum>" +
                    "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"10\"/></w:num>" +
                    "<w:num w:numId=\"2\"><w:abstractNumId w:val=\"20\"/></w:num>" +
                    "</w:numbering>");
        }

        try (ZipFile zip = new ZipFile(docx)) {
            RenderedDocument document = DocumentDocxLayoutExtractor.extract(zip, "numbering.docx", "readwide.local", 28);
            String html = FixedHtmlRenderer.render(document);
            assertTrue(html.contains("rw-list-p"));
            assertTrue(html.contains("rw-list-marker"));
            assertTrue(html.contains(">1.</span>"));
            assertTrue(html.contains(">2.</span>"));
            assertTrue(html.contains(">\u2022</span>"));
            assertTrue(html.contains("margin-left:36pt"));
            assertFalse(html.contains("text-indent:-18pt"));
        }
    }

    @Test
    public void coalescesSplitRunsBeforeInlineMathRendering() throws Exception {
        File docx = File.createTempFile("readwide-docx-split-inline-math", ".docx");
        docx.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(docx))) {
            put(out, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body>" +
                    "<w:p><w:r><w:t>The factor $e^{-\\text{barrier}/</w:t></w:r>" +
                    "<w:proofErr w:type=\"spellStart\"/>" +
                    "<w:r><w:t>kT</w:t></w:r>" +
                    "<w:proofErr w:type=\"spellEnd\"/>" +
                    "<w:r><w:t>}$ and frequency $\\nu_0$.</w:t></w:r></w:p>" +
                    "</w:body></w:document>");
        }

        try (ZipFile zip = new ZipFile(docx)) {
            RenderedDocument document = DocumentDocxLayoutExtractor.extract(zip, "math.docx", "readwide.local", 28);
            String html = FixedHtmlRenderer.render(document);
            assertTrue(html.contains("e<sup>-barrier/kT</sup>"));
            assertTrue(html.contains("\u03bd<sub>0</sub>"));
            assertFalse(html.contains("\\text{barrier}"));
            assertFalse(html.contains("$\\nu_0$"));
        }
    }

    @Test
    public void extractsDocxTableWidthBordersAndVerticalMerges() throws Exception {
        File docx = File.createTempFile("readwide-docx-table-fidelity", ".docx");
        docx.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(docx))) {
            put(out, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body>" +
                    "<w:tbl>" +
                    "<w:tblPr><w:tblW w:type=\"pct\" w:w=\"5000\"/>" +
                    "<w:tblBorders><w:top w:val=\"single\" w:color=\"112233\"/><w:left w:val=\"single\" w:color=\"112233\"/><w:bottom w:val=\"single\" w:color=\"112233\"/><w:right w:val=\"single\" w:color=\"112233\"/></w:tblBorders>" +
                    "</w:tblPr>" +
                    "<w:tblGrid><w:gridCol w:w=\"2400\"/><w:gridCol w:w=\"2400\"/></w:tblGrid>" +
                    "<w:tr>" +
                    "<w:tc><w:tcPr><w:vMerge w:val=\"restart\"/><w:tcW w:type=\"dxa\" w:w=\"2400\"/><w:shd w:fill=\"ddeeff\"/>" +
                    "<w:tcMar><w:top w:w=\"120\" w:type=\"dxa\"/><w:right w:w=\"180\" w:type=\"dxa\"/><w:bottom w:w=\"240\" w:type=\"dxa\"/><w:left w:w=\"300\" w:type=\"dxa\"/></w:tcMar>" +
                    "</w:tcPr><w:p><w:r><w:t>Top merged</w:t></w:r></w:p></w:tc>" +
                    "<w:tc><w:p><w:r><w:t>Right top</w:t></w:r></w:p></w:tc>" +
                    "</w:tr>" +
                    "<w:tr>" +
                    "<w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc>" +
                    "<w:tc><w:tcPr><w:tcBorders><w:top w:val=\"single\" w:color=\"445566\"/></w:tcBorders></w:tcPr><w:p><w:r><w:t>Right bottom</w:t></w:r></w:p></w:tc>" +
                    "</w:tr>" +
                    "</w:tbl>" +
                    "</w:body></w:document>");
        }

        try (ZipFile zip = new ZipFile(docx)) {
            RenderedDocument document = DocumentDocxLayoutExtractor.extract(zip, "table.docx", "readwide.local", 28);
            String html = FixedHtmlRenderer.render(document);
            assertTrue(html.contains("<colgroup>"));
            assertTrue(html.contains("width:50%"));
            assertTrue(html.contains("rowspan=\"2\""));
            assertTrue(html.contains("background-color:#ddeeff"));
            assertTrue(html.contains("padding-top:6pt"));
            assertTrue(html.contains("padding-right:9pt"));
            assertTrue(html.contains("padding-bottom:12pt"));
            assertTrue(html.contains("padding-left:15pt"));
            assertTrue(html.contains("border-color:#112233"));
            assertTrue(html.contains("border-color:#445566"));
            assertTrue(html.contains("Right bottom"));
        }
    }


    @Test
    public void appliesDocxStylesXmlDefaultsAndParagraphCharacterStyles() throws Exception {
        File docx = File.createTempFile("readwide-docx-style-inheritance", ".docx");
        docx.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(docx))) {
            put(out, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body>" +
                    "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Styled Heading</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:rPr><w:rStyle w:val=\"Emphasis\"/></w:rPr><w:t>Character style</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:rPr><w:b w:val=\"0\"/><w:t>Not bold</w:t></w:rPr></w:r></w:p>" +
                    "</w:body></w:document>");
            put(out, "word/styles.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val=\"22\"/><w:color w:val=\"222222\"/></w:rPr></w:rPrDefault>" +
                    "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
                    "<w:style w:type=\"paragraph\" w:styleId=\"BaseHeading\"><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr></w:style>" +
                    "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:basedOn w:val=\"BaseHeading\"/><w:pPr><w:spacing w:before=\"240\"/></w:pPr><w:rPr><w:color w:val=\"cc0000\"/></w:rPr></w:style>" +
                    "<w:style w:type=\"character\" w:styleId=\"Emphasis\"><w:rPr><w:i/><w:u w:val=\"single\"/><w:highlight w:val=\"yellow\"/></w:rPr></w:style>" +
                    "</w:styles>");
        }

        try (ZipFile zip = new ZipFile(docx)) {
            RenderedDocument document = DocumentDocxLayoutExtractor.extract(zip, "styles.docx", "readwide.local", 28);
            String html = FixedHtmlRenderer.render(document);
            assertTrue(html.contains("Styled Heading"));
            assertTrue(html.contains("text-align:center"));
            assertTrue(html.contains("margin-top:12pt"));
            assertTrue(html.contains("margin-bottom:6pt"));
            assertTrue(html.contains("font-weight:bold"));
            assertTrue(html.contains("font-size:16pt"));
            assertTrue(html.contains("color:#cc0000"));
            assertTrue(html.contains("font-style:italic"));
            assertTrue(html.contains("text-decoration:underline"));
            assertTrue(html.contains("background-color:#ffff00"));
            assertTrue(html.contains("font-size:11pt"));
        }
    }


    @Test
    public void extractsDocxImageExtentsAndDowngradesFloatingAnchors() throws Exception {
        File docx = File.createTempFile("readwide-docx-image-fidelity", ".docx");
        docx.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(docx))) {
            put(out, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
                    "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
                    "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
                    "<w:body>" +
                    "<w:p><w:r><w:t>Before image</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"914400\" cy=\"457200\"/>" +
                    "<a:graphic><a:graphicData><a:blip r:embed=\"rId1\"/></a:graphicData></a:graphic>" +
                    "</wp:inline></w:drawing></w:r></w:p>" +
                    "<w:p><w:r><w:drawing><wp:anchor><wp:extent cx=\"1828800\" cy=\"914400\"/>" +
                    "<a:graphic><a:graphicData><a:blip r:embed=\"rId2\"/></a:graphicData></a:graphic>" +
                    "</wp:anchor></w:drawing></w:r></w:p>" +
                    "</w:body></w:document>");
            put(out, "word/_rels/document.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Target=\"media/inline.png\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"/>" +
                    "<Relationship Id=\"rId2\" Target=\"media/floating.png\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"/>" +
                    "</Relationships>");
            put(out, "word/media/inline.png", "png");
            put(out, "word/media/floating.png", "png");
        }

        try (ZipFile zip = new ZipFile(docx)) {
            RenderedDocument document = DocumentDocxLayoutExtractor.extract(zip, "image.docx", "readwide.local", 28);
            String html = FixedHtmlRenderer.render(document);
            assertTrue(html.contains("src=\"https://readwide.local/word/media/inline.png\""));
            assertTrue(html.contains("width:72pt"));
            assertTrue(html.contains("height:36pt"));
            assertTrue(html.contains("src=\"https://readwide.local/word/media/floating.png\""));
            assertTrue(html.contains("rw-floating-downgraded"));
            assertTrue(html.contains("width:144pt"));
            assertTrue(html.contains("height:72pt"));
        }
    }


    @Test
    public void preservesDocxFootnotesAndEndnotesAtDocumentEnd() throws Exception {
        File docx = File.createTempFile("readwide-docx-notes", ".docx");
        docx.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(docx))) {
            put(out, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body>" +
                    "<w:p><w:r><w:t>Main text</w:t></w:r><w:r><w:footnoteReference w:id=\"2\"/></w:r></w:p>" +
                    "<w:p><w:r><w:t>More text</w:t></w:r><w:r><w:endnoteReference w:id=\"3\"/></w:r></w:p>" +
                    "</w:body></w:document>");
            put(out, "word/footnotes.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:footnotes xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:footnote w:id=\"-1\"><w:p><w:r><w:t>separator</w:t></w:r></w:p></w:footnote>" +
                    "<w:footnote w:id=\"2\"><w:p><w:r><w:t>Footnote body</w:t></w:r></w:p></w:footnote>" +
                    "</w:footnotes>");
            put(out, "word/endnotes.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:endnotes xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:endnote w:id=\"3\"><w:p><w:r><w:t>Endnote body</w:t></w:r></w:p></w:endnote>" +
                    "</w:endnotes>");
        }

        try (ZipFile zip = new ZipFile(docx)) {
            RenderedDocument document = DocumentDocxLayoutExtractor.extract(zip, "notes.docx", "readwide.local", 28);
            String html = FixedHtmlRenderer.render(document);
            assertTrue(html.contains("Main text"));
            assertTrue(html.contains("More text"));
            assertTrue(html.contains("href=\"#rw-footnote-2\""));
            assertTrue(html.contains("id=\"rw-footnote-2\""));
            assertTrue(html.contains("Footnotes"));
            assertTrue(html.contains("Footnote body"));
            assertTrue(html.contains("href=\"#rw-endnote-3\""));
            assertTrue(html.contains("id=\"rw-endnote-3\""));
            assertTrue(html.contains("Endnotes"));
            assertTrue(html.contains("Endnote body"));
            assertTrue(!html.contains("separator"));
        }
    }


    @Test
    public void preservesDocxHeadersAndFootersAsReadingOrderSections() throws Exception {
        File docx = File.createTempFile("readwide-docx-header-footer", ".docx");
        docx.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(docx))) {
            put(out, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<w:body>" +
                    "<w:p><w:r><w:t>Main body</w:t></w:r></w:p>" +
                    "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdHeader\"/>" +
                    "<w:footerReference w:type=\"default\" r:id=\"rIdFooter\"/></w:sectPr>" +
                    "</w:body></w:document>");
            put(out, "word/_rels/document.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rIdHeader\" Target=\"header1.xml\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\"/>" +
                    "<Relationship Id=\"rIdFooter\" Target=\"footer1.xml\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\"/>" +
                    "</Relationships>");
            put(out, "word/header1.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:hdr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
                    "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
                    "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">" +
                    "<w:p><w:r><w:t>Header text</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"914400\" cy=\"457200\"/>" +
                    "<a:graphic><a:graphicData><a:blip r:embed=\"rIdHeaderImage\"/></a:graphicData></a:graphic>" +
                    "</wp:inline></w:drawing></w:r></w:p>" +
                    "</w:hdr>");
            put(out, "word/footer1.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:ftr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:p><w:r><w:t>Footer text</w:t></w:r></w:p>" +
                    "</w:ftr>");
            put(out, "word/_rels/header1.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rIdHeaderImage\" Target=\"media/header.png\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"/>" +
                    "</Relationships>");
            put(out, "word/media/header.png", "png");
        }

        try (ZipFile zip = new ZipFile(docx)) {
            RenderedDocument document = DocumentDocxLayoutExtractor.extract(zip, "header-footer.docx", "readwide.local", 28);
            String html = FixedHtmlRenderer.render(document);
            assertTrue(html.contains("rw-page-header"));
            assertTrue(html.contains("Header text"));
            assertTrue(html.contains("Main body"));
            assertTrue(html.contains("rw-page-footer"));
            assertTrue(html.contains("Footer text"));
            assertTrue(!html.contains("Headers"));
            assertTrue(!html.contains("Footers"));
            assertTrue(html.indexOf("Header text") < html.indexOf("Main body"));
            assertTrue(html.indexOf("Footer text") > html.indexOf("Main body"));
            assertTrue(html.contains("src=\"https://readwide.local/word/media/header.png\""));
            assertTrue(html.contains("width:72pt"));
        }
    }

    @Test
    public void preservesDocxPageBreakBeforeAndExactLineSpacing() throws Exception {
        File docx = File.createTempFile("readwide-docx-page-break-line", ".docx");
        docx.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(docx))) {
            put(out, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body>" +
                    "<w:p><w:r><w:t>First page paragraph</w:t></w:r></w:p>" +
                    "<w:p><w:pPr><w:pageBreakBefore/><w:spacing w:line=\"360\" w:lineRule=\"exact\"/></w:pPr>" +
                    "<w:r><w:t>Second page exact spacing</w:t></w:r></w:p>" +
                    "</w:body></w:document>");
        }

        try (ZipFile zip = new ZipFile(docx)) {
            RenderedDocument document = DocumentDocxLayoutExtractor.extract(zip, "page-break.docx", "readwide.local", 28);
            assertEquals(2, document.pageCount());
            String html = FixedHtmlRenderer.render(document);
            assertTrue(html.contains("First page paragraph"));
            assertTrue(html.contains("Second page exact spacing"));
            assertTrue(html.contains("line-height:18pt"));
            assertTrue(html.indexOf("data-page=\"1\"") < html.indexOf("First page paragraph"));
            assertTrue(html.indexOf("data-page=\"2\"") < html.indexOf("Second page exact spacing"));
        }
    }

    @Test
    public void rendersDocxVmlLineShapesAsHorizontalRules() throws Exception {
        File docx = File.createTempFile("readwide-docx-vml-line", ".docx");
        docx.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(docx))) {
            put(out, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                    "xmlns:v=\"urn:schemas-microsoft-com:vml\" " +
                    "xmlns:w10=\"urn:schemas-microsoft-com:office:word\">" +
                    "<w:body>" +
                    "<w:p><w:r><w:t>제   목 : </w:t></w:r></w:p>" +
                    "<w:p><w:r><w:pict><v:line id=\"_x0000_s1026\" from=\"57.4pt,234.5pt\" to=\"555.65pt,234.5pt\" strokeweight=\".99pt\"><w10:wrap/></v:line></w:pict></w:r></w:p>" +
                    "<w:p><w:r><w:t>귀사의 무궁한 발전을 기원합니다.</w:t></w:r></w:p>" +
                    "</w:body></w:document>");
        }

        try (ZipFile zip = new ZipFile(docx)) {
            RenderedDocument document = DocumentDocxLayoutExtractor.extract(zip, "vml-line.docx", "readwide.local", 28);
            String html = FixedHtmlRenderer.render(document);
            assertTrue(html.contains("제   목"));
            assertTrue(html.contains("귀사의 무궁한 발전"));
            // The v:line renders as a horizontal rule. With no sectPr/pgSz the
            // page width is unknown, so there is no width/margin percentage, but
            // the stroke weight from strokeweight=".99pt" is still applied.
            assertTrue("missing rw-hr, html=" + html, html.contains("class=\"rw-hr\""));
            assertTrue("missing stroke weight, html=" + html, html.contains("border-top:0.75pt solid"));
            assertTrue("placeholder sentinel leaked", !html.contains("__RW_HWP_OFFICIAL_HR__"));
        }
    }

    @Test
    public void extractsVmlHorizontalLineWithStrokeWeightAndExtent() throws Exception {
        File docx = File.createTempFile("readwide-docx-vmlline", ".docx");
        docx.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(docx))) {
            // A4 page (11906 twips ~= 595.3pt). Two v:line shapes: one horizontal
            // rule with from/to x = 57.4pt..555.65pt at the same y, and one
            // vertical line that must NOT become a horizontal rule.
            put(out, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                    "xmlns:v=\"urn:schemas-microsoft-com:vml\">" +
                    "<w:body>" +
                    "<w:p><w:r><w:pict>" +
                    "<v:line id=\"l1\" from=\"57.4pt,234.5pt\" to=\"555.65pt,234.5pt\" strokeweight=\".99pt\"/>" +
                    "</w:pict></w:r></w:p>" +
                    "<w:p><w:r><w:pict>" +
                    "<v:line id=\"l2\" from=\"100pt,50pt\" to=\"100pt,300pt\" strokeweight=\"1pt\"/>" +
                    "</w:pict></w:r></w:p>" +
                    "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
                    "<w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\"/></w:sectPr>" +
                    "</w:body></w:document>");
        }

        try (ZipFile zip = new ZipFile(docx)) {
            RenderedDocument document = DocumentDocxLayoutExtractor.extract(zip, "fixture.docx", "readwide.local", 28);
            String html = FixedHtmlRenderer.render(document);

            // The horizontal v:line becomes a sized rw-hr with the stroke weight
            // and a width proportional to the page. The source left inset here
            // (57.4pt / ~595pt ≈ 9.6%) is small, so it is dropped and folded into
            // the width so the rule aligns with the body text column (margin-left
            // 0) instead of reading as misaligned in the reflow layout.
            assertTrue("missing stroke weight, html=" + html, html.contains("border-top:0.75pt solid"));
            assertTrue("missing width %, html=" + html, html.contains("width:93.3"));
            assertTrue("small inset not collapsed, html=" + html, html.contains("margin-left:0%"));
            // No raw placeholder sentinel should leak into the output.
            assertTrue("placeholder sentinel leaked", !html.contains("__RW_HWP_OFFICIAL_HR__"));
            // The vertical line should not have produced a horizontal rule.
            int firstWidth = html.indexOf("width:93.3");
            assertTrue(firstWidth >= 0);
            assertTrue("vertical line wrongly became a sized rule",
                    html.indexOf("width:93.3", firstWidth + 1) < 0);
        }
    }

    @Test
    public void tableWithoutBorderDefinitionsRendersBorderless() throws Exception {
        // A table with neither tblBorders nor any tcBorders must render with no
        // cell borders (OOXML default), so an invisible layout-grid table — like
        // the Korean official-letter sender/contact block — does not get the
        // renderer's default hairline border drawn around every cell.
        File docx = File.createTempFile("readwide-docx-noborder", ".docx");
        docx.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(docx))) {
            put(out, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body><w:tbl>" +
                    "<w:tblGrid><w:gridCol w:w=\"4918\"/><w:gridCol w:w=\"4918\"/></w:tblGrid>" +
                    "<w:tr>" +
                    "<w:tc><w:tcPr><w:tcW w:w=\"4918\" w:type=\"dxa\"/></w:tcPr><w:p><w:r><w:t>발신</w:t></w:r></w:p></w:tc>" +
                    "<w:tc><w:tcPr><w:tcW w:w=\"4918\" w:type=\"dxa\"/></w:tcPr><w:p><w:r><w:t>직통</w:t></w:r></w:p></w:tc>" +
                    "</w:tr></w:tbl>" +
                    "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>" +
                    "</w:body></w:document>");
        }
        try (ZipFile zip = new ZipFile(docx)) {
            RenderedDocument document = DocumentDocxLayoutExtractor.extract(zip, "fixture.docx", "readwide.local", 28);
            String html = FixedHtmlRenderer.render(document);
            // Both cells must explicitly suppress the border.
            int first = html.indexOf("border:none");
            assertTrue("first cell not borderless, html=" + html, first >= 0);
            assertTrue("second cell not borderless, html=" + html,
                    html.indexOf("border:none", first + 1) >= 0);
            // No cell should declare a solid border.
            assertTrue("a cell drew a solid border, html=" + html,
                    !html.contains("border-style:solid"));
        }
    }

    private static void put(ZipOutputStream out, String name, String text) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
