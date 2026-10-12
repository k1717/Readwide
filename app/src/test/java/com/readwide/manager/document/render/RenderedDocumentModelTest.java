package com.readwide.manager.document.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RenderedDocumentModelTest {
    @Test public void malformedPageGeometryCannotProduceInvalidOrEmptyContentBoxes() {
        RenderedPage page = RenderedPage.builder(0).pageSizePt(Float.NaN, Float.POSITIVE_INFINITY)
                .marginsPt(Float.NaN, 1000f, Float.POSITIVE_INFINITY, 1000f).build();
        assertEquals(595f, page.widthPt, 0f);
        assertEquals(842f, page.heightPt, 0f);
        assertEquals(54f, page.marginTopPt, 0f);
        assertTrue(page.marginLeftPt + page.marginRightPt < page.widthPt);
        String html = FixedHtmlRenderer.render(RenderedDocument.builder("docx").addPage(page).build());
        assertTrue(!html.contains("NaN") && !html.contains("Infinity"));
    }

    @Test public void validLandscapeAndAsymmetricMarginsAreNotNormalizedAway() {
        RenderedPage page = RenderedPage.builder(0).pageSizePt(842f, 595f).marginsPt(0f, 24f, 36f, 72f).build();
        assertEquals(842f, page.widthPt, 0f);
        assertEquals(595f, page.heightPt, 0f);
        assertEquals(0f, page.marginTopPt, 0f);
        assertEquals(72f, page.marginLeftPt, 0f);
    }

    @Test
    public void collectsPlainTextFromParagraphTableAndImageAlt() {
        RenderedTable table = RenderedTable.ofRows(java.util.Collections.singletonList(
                java.util.Collections.singletonList(RenderedTableCell.text("Cell text"))));

        RenderedDocument document = RenderedDocument.builder("fixture")
                .addPage(RenderedPage.builder(0)
                        .addHeaderBlock(RenderedBlock.paragraph(RenderedParagraph.of(RenderedRun.text("Header text"))))
                        .addBlock(RenderedBlock.paragraph(RenderedParagraph.of(RenderedRun.text("Title"))))
                        .addBlock(RenderedBlock.table(table))
                        .addBlock(RenderedBlock.image(new RenderedImage("local://image.png", "Image alt", 10f, 10f, false)))
                        .addFooterBlock(RenderedBlock.paragraph(RenderedParagraph.of(RenderedRun.text("Footer text"))))
                        .build())
                .build();

        assertEquals(1, document.pageCount());
        assertTrue(document.plainText.contains("Header text"));
        assertTrue(document.plainText.contains("Title"));
        assertTrue(document.plainText.contains("Cell text"));
        assertTrue(document.plainText.contains("Image alt"));
        assertTrue(document.plainText.contains("Footer text"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidAnchorRange() {
        new TextAnchor("bad", 5, 4, 0, "bad");
    }
}
