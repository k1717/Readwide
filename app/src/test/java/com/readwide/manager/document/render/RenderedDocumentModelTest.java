package com.readwide.manager.document.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RenderedDocumentModelTest {
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
