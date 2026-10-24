package com.readwide.manager;

import com.readwide.manager.util.EpubBindingRewriter;
import com.readwide.manager.util.EpubTextAlignment;
import org.junit.Test;
import static org.junit.Assert.*;

public class EpubParagraphAlignmentTest {
    @Test public void literalProseValuesKeepTheirFallbackAndPriority() {
        assertEquals("p{text-align:var(--readwide-epub-align,justify) !important;"
                        + "text-align-last:var(--readwide-epub-align-last,justify)}",
                EpubCssCompatibility.prepareParagraphAlignment(
                        "p{text-align: justify ! /*priority*/ important;text-align-last:justify}"));
        assertEquals("text-align:var(--readwide-epub-align,left);text-align:var(--readwide-epub-align,START)",
                EpubCssCompatibility.prepareParagraphAlignment("text-align:left;text-align:START"));
    }

    @Test public void explicitSpecialAlignmentAndExpressionsAreNotReinterpreted() {
        String css = "h1{text-align:center}td{text-align:right!important}p{text-align:end;"
                + "text-align:var(--publisher-alignment);text-align-last:center}"
                + "blockquote{text-align:inherit}p{text-align:invalid-value}";
        assertEquals(css, EpubCssCompatibility.prepareParagraphAlignment(css));
    }

    @Test public void commentsStringsSelectorsAndCustomPropertyBodiesStayOpaque() {
        String css = "/*text-align:justify*/[data-text='text-align:left']{content:'text-align:justify';"
                + "--tokens:{text-align:justify;};background:url('text-align:justify')}";
        assertEquals(css, EpubCssCompatibility.prepareParagraphAlignment(css));
    }

    @Test public void inactiveRulesStayUnchangedAndNestedScreenRulesArePrepared() {
        String inactive = "@media print{p{text-align:justify}}@keyframes a{to{text-align:left}}";
        assertEquals(inactive, EpubCssCompatibility.prepareParagraphAlignment(inactive));
        assertTrue(EpubCssCompatibility.prepareParagraphAlignment(
                "@media screen{@supports(display:block){p{text-align:justify}}}")
                .contains("text-align:var(--readwide-epub-align,justify)"));
    }

    @Test public void repeatedPreparationIsIdempotent() {
        String css = "p:hover{text-align:justify!important}li{text-align:left}dd{text-align:start}";
        String once = EpubCssCompatibility.prepareParagraphAlignment(css);
        assertEquals(once, EpubCssCompatibility.prepareParagraphAlignment(once));
        assertEquals("", EpubCssCompatibility.prepareParagraphAlignment(null));
    }

    @Test public void onlyRealEmbeddedStylesArePrepared() {
        String fake = "<!--<style>p{text-align:justify}</style>-->"
                + "<script>var s='text-align:justify';</script>"
                + "<textarea><p style='text-align:justify'>example</p></textarea>";
        assertEquals(fake, EpubBindingRewriter.transformEmbeddedCss(fake,
                EpubCssCompatibility::prepareParagraphAlignment));
        String prepared = EpubBindingRewriter.transformEmbeddedCss(
                "<style><![CDATA[p{text-align:justify}]]></style><p style='text-align:left!important'>text</p>",
                EpubCssCompatibility::prepareParagraphAlignment);
        assertTrue(prepared.contains("text-align:var(--readwide-epub-align,justify)"));
        assertTrue(prepared.contains("text-align:var(--readwide-epub-align,left) !important"));
    }

    @Test public void defaultAndUnknownModesDoNotInjectAnOverride() {
        for (int value : new int[]{0, -1, 3, Integer.MAX_VALUE}) {
            assertEquals(EpubTextAlignment.BOOK_DEFAULT, EpubTextAlignment.normalize(value));
            assertEquals("", EpubCssCompatibility.paragraphAlignmentStyle(value));
        }
    }

    @Test public void logicalStartAndJustifiedDoNotForceEveryParagraph() {
        String start = EpubCssCompatibility.paragraphAlignmentStyle(EpubTextAlignment.START);
        String justified = EpubCssCompatibility.paragraphAlignmentStyle(EpubTextAlignment.JUSTIFY);
        assertTrue(start.contains("--readwide-epub-align:start !important"));
        assertTrue(justified.contains("--readwide-epub-align:justify !important"));
        assertTrue(start.contains("--readwide-epub-align-last:auto !important"));
        assertTrue(start.contains("--readwide-epub-align:initial !important"));
        assertTrue(start.contains("h1,h2,h3,h4,h5,h6,table,pre,code"));
        assertFalse(start.contains("p{text-align:"));
        assertFalse(start.contains("<script"));
    }
}
