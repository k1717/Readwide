package com.readwide.manager.document.render;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class FixedHtmlRendererSmokeTest {
    @Test
    public void rendersPageParagraphRunTableImageAndEscapesHtml() {
        TextStyle boldRed = new TextStyle.Builder()
                .bold(true)
                .underline(true)
                .color("#cc0000")
                .fontSizePt(14f)
                .build();
        ParagraphStyle centered = new ParagraphStyle.Builder()
                .alignment(ParagraphStyle.Alignment.CENTER)
                .marginBottomPt(8f)
                .lineHeightPt(18f)
                .backgroundColor("#eeffee")
                .build();

        RenderedParagraph paragraph = new RenderedParagraph(centered, Arrays.asList(
                new RenderedRun("Proposal <Title>", boldRed, 0, 16),
                RenderedRun.text(" & body")
        ));

        RenderedTable table = RenderedTable.ofRows(Arrays.asList(
                Arrays.asList(
                        RenderedTableCell.builder()
                                .colSpan(2)
                                .backgroundColor("#ffeeaa")
                                .addBlock(RenderedBlock.paragraph(RenderedParagraph.of(RenderedRun.text("Merged cell"))))
                                .build()
                ),
                Arrays.asList(RenderedTableCell.text("A"), RenderedTableCell.text("B"))
        ));

        RenderedDocument document = RenderedDocument.builder("docx")
                .title("fixture")
                .addPage(RenderedPage.builder(0)
                        .pageSizePt(595f, 842f)
                        .marginsPt(36f, 40f, 36f, 40f)
                        .addHeaderBlock(RenderedBlock.paragraph(RenderedParagraph.of(RenderedRun.text("Page header"))))
                        .addBlock(RenderedBlock.paragraph(paragraph))
                        .addBlock(RenderedBlock.table(table))
                        .addBlock(RenderedBlock.image(new RenderedImage("local://media/a.png", "Logo", 120f, 60f, true)))
                        .addBlock(RenderedBlock.unsupported("Unsupported shape"))
                        .addFooterBlock(RenderedBlock.paragraph(RenderedParagraph.of(RenderedRun.text("Page footer"))))
                        .build())
                .build();

        String html = FixedHtmlRenderer.render(document);

        assertTrue(html.contains("class=\"rw-page\""));
        assertTrue(html.contains("rw-page-header"));
        assertTrue(html.contains("rw-page-footer"));
        assertTrue(html.contains("Page header"));
        assertTrue(html.contains("Page footer"));
        assertTrue(html.contains("data-format=\"docx\""));
        assertTrue(html.contains("Proposal &lt;Title&gt;"));
        assertTrue(html.contains("&amp; body"));
        assertTrue(html.contains("data-anchor-start=\"0\""));
        assertTrue(html.contains("font-weight:bold"));
        assertTrue(html.contains("text-decoration:underline"));
        assertTrue(html.contains("line-height:18pt"));
        assertTrue(html.contains("background-color:#eeffee"));
        assertTrue(html.contains("colspan=\"2\""));
        assertTrue(html.contains("background-color:#ffeeaa"));
        assertTrue(html.contains("overflow-wrap:anywhere"));
        assertTrue(html.contains("overflow:visible"));
        assertFalse(html.contains("max-width:0"));
        assertFalse(html.contains("overflow:hidden;overflow-wrap:anywhere"));
        assertTrue(html.contains("rw-floating-downgraded"));
        assertTrue(html.contains("Unsupported shape"));
        assertFalse(html.contains("<Title>"));
    }

    @Test
    public void rendersInlineMathMarkersAndProtectsNarrowTableCells() {
        RenderedTable table = RenderedTable.ofRows(Arrays.asList(
                Arrays.asList(
                        RenderedTableCell.text("Fabrication"),
                        RenderedTableCell.text("Batch fabrication is possible")
                )
        ));
        RenderedDocument document = RenderedDocument.builder("docx")
                .title("math-table")
                .addPage(RenderedPage.builder(0)
                        .addBlock(RenderedBlock.paragraph(RenderedParagraph.of(
                                RenderedRun.text("height $W$ and jump frequency $\\nu_0$ with factor $e^{-\\text{barrier}/kT}$."))))
                        .addBlock(RenderedBlock.table(table))
                        .build())
                .build();

        String html = FixedHtmlRenderer.render(document);

        assertTrue(html.contains("class=\"rw-inline-math\""));
        assertTrue(html.contains("\u03bd<sub>0</sub>"));
        assertTrue(html.contains("e<sup>-barrier/kT</sup>"));
        assertFalse(html.contains("$\\nu_0$"));
        assertFalse(html.contains("\\text{barrier}"));
        assertTrue(html.contains(".rw-table td{border:1px solid #777;padding:.35em .45em;vertical-align:top;min-width:0;overflow:visible;overflow-wrap:break-word;word-break:normal;}"));
        assertTrue(html.contains(".rw-table td *{max-width:100%;overflow-wrap:break-word;word-break:normal;}"));
    }

    @Test
    public void rendersDisplayMathFractionsAndSymbols() {
        RenderedDocument document = RenderedDocument.builder("docx")
                .title("display-math")
                .addPage(RenderedPage.builder(0)
                        .addBlock(RenderedBlock.paragraph(RenderedParagraph.of(
                                RenderedRun.text("$$\\frac{\\partial C}{\\partial t} = D \\frac{\\partial^2 C}{\\partial x^2}$$"))))
                        .addBlock(RenderedBlock.paragraph(RenderedParagraph.of(
                                RenderedRun.text("$$C_S \\cdot \\text{erfc}\\left(\\frac{x}{2 \\sqrt{D t}}\\right)$$"))))
                        .build())
                .build();

        String html = FixedHtmlRenderer.render(document);

        assertTrue(html.contains("class=\"rw-display-math\""));
        assertTrue(html.contains("class=\"frac\""));
        assertTrue(html.contains("\u2202"));
        assertTrue(html.contains("<sup>2</sup>"));
        assertTrue(html.contains("C<sub>S</sub>"));
        assertTrue(html.contains("\u221a(D t)"));
        assertFalse(html.contains("$$"));
        assertFalse(html.contains("\\frac"));
        assertFalse(html.contains("\\partial"));
    }

    @Test
    public void rendersDelimiterOnlyMathWithoutLatexCommands() {
        // Math whose only signal is the $...$ delimiters (no _, ^, or backslash
        // command) must still render: digit-leading ("2Dt") and operator-bearing
        // ("L/W", "n>1") expressions previously fell through to literal text.
        RenderedDocument document = RenderedDocument.builder("docx")
                .title("delimiter-math")
                .addPage(RenderedPage.builder(0)
                        .addBlock(RenderedBlock.paragraph(RenderedParagraph.of(
                                RenderedRun.text("normalized by $2Dt$ and split into $L/W$ when $n>1$."))))
                        .build())
                .build();

        String html = FixedHtmlRenderer.render(document);

        assertTrue(html.contains("class=\"rw-inline-math\""));
        assertTrue(html.contains(">2Dt<"));
        assertTrue(html.contains(">L/W<"));
        assertTrue(html.contains("n&gt;1"));
        assertFalse(html.contains("$2Dt$"));
        assertFalse(html.contains("$L/W$"));
    }

    @Test
    public void doesNotTreatCurrencyAsMath() {
        // A lone dollar amount is not math and must survive verbatim.
        RenderedDocument document = RenderedDocument.builder("docx")
                .title("currency")
                .addPage(RenderedPage.builder(0)
                        .addBlock(RenderedBlock.paragraph(RenderedParagraph.of(
                                RenderedRun.text("costing around $200 million plus each."))))
                        .build())
                .build();

        String html = FixedHtmlRenderer.render(document);

        assertTrue(html.contains("$200 million"));
        // The math wrapper must not appear in the body. (The class name also
        // appears once in the <style> block, so assert the opening tag is absent
        // rather than the bare class string.)
        assertFalse(html.contains("<span class=\"rw-inline-math\">"));
    }

    @Test
    public void rendersMathSpanningMultipleRuns() {
        // DOCX commonly splits a single $...$ across runs when only part of it is
        // styled. Per-run detection misses these, leaving raw LaTeX visible, so a
        // span that crosses run boundaries must be re-rendered as a unit.
        RenderedParagraph paragraph = new RenderedParagraph(null, Arrays.asList(
                RenderedRun.text("solution (or $1 - \\"),
                RenderedRun.text("text{"),
                RenderedRun.text("error "),
                RenderedRun.text("function}$) of distance.")
        ));
        RenderedDocument document = RenderedDocument.builder("docx")
                .title("cross-run-math")
                .addPage(RenderedPage.builder(0)
                        .addBlock(RenderedBlock.paragraph(paragraph))
                        .build())
                .build();

        String html = FixedHtmlRenderer.render(document);

        assertTrue(html.contains("class=\"rw-inline-math\""));
        assertTrue(html.contains("1 - error function"));
        assertFalse(html.contains("\\text{"));
        assertFalse(html.contains("$1"));
    }

    @Test
    public void mapsGreekRhoToUnicode() {
        // \rho previously fell through unmapped and rendered as the literal
        // letters "rho"; it must become the Greek character.
        RenderedDocument document = RenderedDocument.builder("docx")
                .title("greek")
                .addPage(RenderedPage.builder(0)
                        .addBlock(RenderedBlock.paragraph(RenderedParagraph.of(
                                RenderedRun.text("sheet resistance $\\rho_{S}$ here."))))
                        .build())
                .build();

        String html = FixedHtmlRenderer.render(document);

        assertTrue(html.contains("\u03c1<sub>S</sub>"));
        assertFalse(html.contains("rho"));
        assertFalse(html.contains("\\rho"));
    }
}
