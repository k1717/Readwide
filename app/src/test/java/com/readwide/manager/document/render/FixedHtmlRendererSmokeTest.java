package com.readwide.manager.document.render;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class FixedHtmlRendererSmokeTest {
    @Test public void scriptsConsumeCommandsGroupsSpacesAndFullUnicodeCharacters() {
        String html = mathHtml(RenderedRun.text("$x_\\alpha + y^ \\beta + H_{2}O + z^\\frac{1}{2} + a^\uD835\uDC65$"));
        assertTrue(html.contains("x<sub>\u03b1</sub>"));
        assertTrue(html.contains("y<sup>\u03b2</sup>"));
        assertTrue(html.contains("H<sub>2</sub>O"));
        assertTrue(html.contains("z<sup><span class=\"frac\">"));
        assertTrue(html.contains("a<sup>\uD835\uDC65</sup>"));
        assertFalse(html.contains("<sub></sub>"));
    }

    @Test public void explicitBracketMathAndInlineFractionsShareLayoutStyles() {
        String html = mathHtml(RenderedRun.text("\\(\\frac {x_1} {y^2}\\) and \\[E=mc^2\\]"));
        assertTrue(html.contains("<span class=\"rw-inline-math\"><span class=\"frac\">"));
        assertTrue(html.contains("<span class=\"rw-display-math\">E=mc<sup>2</sup>"));
        assertTrue(html.contains(".rw-inline-math .frac,.rw-display-math .frac{display:inline-flex;"));
    }

    @Test public void escapedMarkersAreNotInterpretedAsScriptsOrDelimiters() {
        String html = mathHtml(RenderedRun.text("literal \\$5; $a\\_b + c\\^d + \\{x\\} + \\$5$"));
        assertTrue(html.contains("literal \\$5; "));
        assertTrue(html.contains("a_b + c^d + {x} + $5"));
        assertFalse(html.contains("<sub>"));
        assertFalse(html.contains("<sup>"));
    }

    @Test public void unsupportedMalformedAndPlainNotationRemainLiteralAndHtmlSafe() {
        String html = mathHtml(RenderedRun.text("file_name x^2 $\\unknown{x_1}$ $x_{2$ $x^$ <script> $x_1$"));
        assertTrue(html.contains("file_name x^2 $\\unknown{x_1}$ $x_{2$ $x^$ &lt;script&gt;"));
        assertTrue(html.contains("x<sub>1</sub>"));
        assertFalse(html.contains("<script>"));
    }

    @Test public void pairedCurrencyAndProseDoNotBecomeMath() {
        String html = mathHtml(RenderedRun.text("Costs $5 and $10; $not math$; $x + y$"));
        assertTrue(html.contains("Costs $5 and $10; $not math$;"));
        assertTrue(html.contains("<span class=\"rw-inline-math\">x + y</span>"));
    }

    @Test public void splitFormulaKeepsSurroundingRunStylesAndNativeScripts() {
        String html = mathHtml(RenderedRun.text("Bold prefix $x_", new TextStyle.Builder().bold(true).build()),
                RenderedRun.text("{1}$"),
                RenderedRun.text(" suffix", new TextStyle.Builder().italic(true).build()),
                RenderedRun.text("2", new TextStyle.Builder().verticalAlign(TextStyle.VerticalAlign.SUPERSCRIPT).build()));
        assertTrue(html.contains("<span style=\"font-weight:bold;\">Bold prefix </span>"));
        assertTrue(html.contains("x<sub>1</sub>"));
        assertTrue(html.contains("<span style=\"font-style:italic;\"> suffix</span>"));
        assertTrue(html.contains("vertical-align:super;font-size:smaller;\">2</span>"));
    }

    @Test public void joiningMathNeverErasesNavigationOrAnchorMetadata() {
        String html = mathHtml(RenderedRun.text("$x_"), RenderedRun.text("1$"),
                RenderedRun.link("[1]", null, "#note", "ref"),
                new RenderedRun("Anchor", TextStyle.plain(), 10, 16));
        assertTrue(html.contains("x<sub>1</sub>"));
        assertTrue(html.contains("id=\"ref\" href=\"#note\""));
        assertTrue(html.contains("data-anchor-start=\"10\" data-anchor-end=\"16\""));
    }

    @Test public void deepFragmentsStayLiteralAndAuthoredScriptSizeUsesRunSize() {
        StringBuilder deep = new StringBuilder("$");
        for (int i = 0; i < 30; i++) deep.append("x^{");
        deep.append('1');
        for (int i = 0; i < 30; i++) deep.append('}');
        deep.append('$');
        String html = mathHtml(RenderedRun.text(deep.toString()), RenderedRun.text("2",
                new TextStyle.Builder().fontSizePt(20f).verticalAlign(TextStyle.VerticalAlign.SUPERSCRIPT).build()));
        assertTrue(html.contains(deep.toString()));
        assertTrue(html.contains("vertical-align:super;font-size:14.40pt;font-size:calc(var(--rw-point,1pt) * 14.40);"));
    }

    private static String mathHtml(RenderedRun... runs) {
        return FixedHtmlRenderer.render(RenderedDocument.builder("docx").addPage(RenderedPage.builder(0)
                .addBlock(RenderedBlock.paragraph(new RenderedParagraph(null, Arrays.asList(runs)))).build()).build());
    }

    @Test public void authoredGeometryUsesOneScaleForBothAxesAndText() {
        RenderedDocument doc = RenderedDocument.builder("docx").addPage(RenderedPage.builder(0)
                .pageSizePt(600f, 800f).marginsPt(40f, 60f, 80f, 20f)
                .addBlock(RenderedBlock.paragraph(RenderedParagraph.of(RenderedRun.text("Text",
                        new TextStyle.Builder().fontSizePt(12f).build())))).build()).build();
        String html = FixedHtmlRenderer.render(doc);
        assertTrue(html.contains("--rw-point:calc(100vw / 600);"));
        assertTrue(html.contains("--rw-page-min-height:calc(var(--rw-point) * 800);"));
        assertTrue(html.contains("padding-top:calc(var(--rw-point,1pt) * 40);"));
        assertTrue(html.contains("padding-left:calc(var(--rw-point,1pt) * 20);"));
        assertTrue(html.contains("font-size:calc(var(--rw-point,1pt) * 12);"));
        assertTrue(html.contains("min-height:var(--rw-page-min-height,100vh)"));
        assertTrue(html.contains("object-fit:contain"));
        assertFalse(html.contains("padding-top:clamp("));
    }

    @Test public void tableDimensionsAlignmentAndHeightReachCssWithoutClipping() {
        RenderedTableCell cell = RenderedTableCell.builder().minHeightPt(80f)
                .verticalAlignment(RenderedTableCell.VerticalAlignment.BOTTOM)
                .addBlock(RenderedBlock.paragraph(RenderedParagraph.of(RenderedRun.text("Cell")))).build();
        RenderedTable table = new RenderedTable(Arrays.asList(Arrays.asList(cell)), 100f, null,
                240f, ParagraphStyle.Alignment.RIGHT, 20f);
        String html = FixedHtmlRenderer.render(RenderedDocument.builder("docx")
                .addPage(RenderedPage.builder(0).addBlock(RenderedBlock.table(table)).build()).build());
        assertTrue(html.contains("width:calc(var(--rw-point,1pt) * 240);margin-left:auto;margin-right:0;"));
        assertTrue(html.contains("height:calc(var(--rw-point,1pt) * 80);vertical-align:bottom;"));
        assertFalse(html.contains("margin-left:20pt"));
    }

    @Test public void heuristicLetterDoesNotClaimAuthoredPaperGeometry() {
        String html = FixedHtmlRenderer.render(RenderedDocument.builder("hwp-official-letter")
                .addPage(RenderedPage.builder(0).build()).build());
        assertFalse(html.contains("--rw-point:calc(100vw /"));
        assertFalse(html.contains("--rw-page-min-height:calc("));
    }

    @Test public void tableParagraphSpacingAndLongListWrappingArePreserved() {
        RenderedTable table = RenderedTable.ofRows(Arrays.asList(Arrays.asList(RenderedTableCell.text("column  one\tvalue"))));
        RenderedDocument doc = RenderedDocument.builder("hwp").addPage(RenderedPage.builder(0)
                .addBlock(RenderedBlock.table(table)).build()).build();
        String html = FixedHtmlRenderer.render(doc);
        assertTrue(html.contains("column  one"));
        assertTrue(html.contains(".rw-table .rw-p{max-width:100%;white-space:pre-wrap;"));
        assertTrue(html.contains(".rw-list-content{flex:1 1 auto;min-width:0;}"));
    }

    @Test public void explicitDefaultsRemainVisibleToStyleMerging() {
        assertTrue(ParagraphStyle.normal().isDefault());
        assertFalse(new ParagraphStyle.Builder().alignment(ParagraphStyle.Alignment.LEFT).build().isDefault());
        assertTrue(TextStyle.plain().isEmpty());
        assertFalse(new TextStyle.Builder().verticalAlign(TextStyle.VerticalAlign.BASELINE).build().isEmpty());
        assertFalse(new TextStyle.Builder().highlightColor("none").build().isEmpty());
    }

    @Test public void clearedHighlightRevealsShadingAndLegacyParagraphSpacingReachesHtml() {
        TextStyle style = new TextStyle.Builder().backgroundColor("#aabbcc").highlightColor("none").build();
        RenderedDocument doc = RenderedDocument.builder("doc").addPage(RenderedPage.builder(0)
                .addBlock(RenderedBlock.paragraph(new RenderedParagraph(new ParagraphStyle.Builder()
                        .marginTopPt(12f).marginBottomPt(0f).build(), Arrays.asList(new RenderedRun("Body", style, 0, 4)))))
                .build()).build();
        String html = FixedHtmlRenderer.render(doc);
        assertTrue(html.contains("margin-top:calc(var(--rw-point,1pt) * 12);"));
        assertTrue(html.contains("margin-bottom:calc(var(--rw-point,1pt) * 0);"));
        assertTrue(html.contains("background-color:#aabbcc;"));
        assertFalse(html.contains("background-color:none;"));
    }

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
