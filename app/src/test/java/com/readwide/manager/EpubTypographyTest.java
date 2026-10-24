package com.readwide.manager;

import com.readwide.manager.util.EpubBindingRewriter;
import com.readwide.manager.util.EpubTypography;
import org.junit.Test;
import static org.junit.Assert.*;

public class EpubTypographyTest {
    @Test public void sizeMigrationRetainsThePreviousZoomRange() {
        assertEquals(100, EpubTypography.textZoomPercent(16f));
        assertEquals(150, EpubTypography.textZoomPercent(24f));
        assertEquals(50, EpubTypography.textZoomPercent(8f));
        assertEquals(267, EpubTypography.textZoomPercent(48f));
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
            assertEquals(100, EpubTypography.textZoomPercent(value));
    }

    @Test public void lineHeightChoicesProduceOnlyFiniteUnitlessCss() {
        assertEquals("", EpubTypography.lineHeightCss(0));
        String[] expected = {"1.2", "1.4", "1.6", "1.8", "2.0"};
        for (int i = 1; i < EpubTypography.LINE_HEIGHT_CHOICE_COUNT; i++)
            assertEquals(expected[i - 1], EpubTypography.lineHeightCss(i));
        for (int invalid : new int[]{-1, 6, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertEquals(0, EpubTypography.normalizeLineHeight(invalid));
            assertEquals("", EpubCssCompatibility.lineHeightStyle(invalid));
        }
    }

    @Test public void literalSpacingRetainsOriginalFallbackAndPriority() {
        assertEquals("p{line-height:var(--readwide-epub-line-height,140%) !important}",
                EpubCssCompatibility.prepareLineHeight("p{line-height:140% ! /*priority*/ important}"));
        for (String value : new String[]{"normal", "1.25", ".8", "20px", "1.2em", "0"})
            assertEquals("line-height:var(--readwide-epub-line-height," + value + ")",
                    EpubCssCompatibility.prepareLineHeight("line-height:" + value));
    }

    @Test public void expressionsShorthandsAndInvalidDeclarationsArePreserved() {
        String css = "p{font:italic 12px/1.3 serif;line-height:var(--book);line-height:calc(1em + 2px);"
                + "line-height:inherit;line-height:-1;line-height:garbage;line-height:1\\.5}"
                + "/*line-height:1*/[data-x='line-height:2']{content:'line-height:2';--tokens:{line-height:2;}}";
        assertEquals(css, EpubCssCompatibility.prepareLineHeight(css));
    }

    @Test public void inactiveRulesAreNotChangedAndPreparationIsIdempotent() {
        String inactive = "@media print{p{line-height:1}}@keyframes a{to{line-height:2}}";
        assertEquals(inactive, EpubCssCompatibility.prepareLineHeight(inactive));
        String once = EpubCssCompatibility.prepareLineHeight("@media screen{@supports(display:block){p{line-height:1.3}}}");
        assertTrue(once.contains("var(--readwide-epub-line-height,1.3)"));
        assertEquals(once, EpubCssCompatibility.prepareLineHeight(once));
    }

    @Test public void embeddedStylesArePreparedWithoutTouchingDocumentTextOrScripts() {
        String fake = "<!--<style>p{line-height:2}</style>--><script>var s='line-height:2';</script>"
                + "<textarea><p style='line-height:2'>example</p></textarea>";
        assertEquals(fake, EpubBindingRewriter.transformEmbeddedCss(fake, EpubCssCompatibility::prepareLineHeight));
        String html = EpubBindingRewriter.transformEmbeddedCss("<p style='line-height:1.3!important'>text</p>",
                EpubCssCompatibility::prepareLineHeight);
        assertTrue(html.contains("var(--readwide-epub-line-height,1.3) !important"));
    }

    @Test public void bookDefaultInstallsNoOverrideAndProtectedSubtreesRetainFallbacks() {
        assertEquals("", EpubCssCompatibility.lineHeightStyle(0));
        String style = EpubCssCompatibility.lineHeightStyle(3);
        assertTrue(style.contains("--readwide-epub-line-height:1.6 !important"));
        assertTrue(style.contains("--readwide-epub-line-height:initial !important"));
        assertTrue(style.contains("h1,h2,h3,h4,h5,h6,table,pre,code"));
        assertFalse(style.contains("<script"));
        assertFalse(style.contains("p{line-height:"));
    }
}
