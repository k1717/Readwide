package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EpubCssCompatibilityTest {

    @Test public void xhtmlCdataAndLegacyStyleWrappersKeepRealDeclarations() {
        String html = "<style><![CDATA[body{-epub-writing-mode:vertical-rl}]]></style>";
        assertTrue(EpubCssCompatibility.detectsVerticalWritingInHtml(html));
        assertTrue(EpubCssCompatibility.addHtmlWebViewAliases(html).contains("-webkit-writing-mode:vertical-rl;"));
        assertTrue(EpubCssCompatibility.detectsVerticalWritingInHtml(
                "<style><!-- body{writing-mode:vertical-lr} --></style>"));
        assertEquals(java.util.Arrays.asList("base.css"),
                EpubCssCompatibility.importedStylesheets("<![CDATA[ @import 'base.css'; body{} ]]>"));
    }

    @Test public void customPropertyTokensAndFunctionsAreOpaque() {
        String css = "body{--tokens:{-epub-writing-mode:vertical-rl;};"
                + "--more:fn({-epub-text-combine:horizontal;});"
                + "background:url(data:example,-epub-writing-mode:vertical-rl);writing-mode:horizontal-tb}";
        assertEquals(css, EpubCssCompatibility.addWebViewAliases(css));
        assertFalse(EpubCssCompatibility.detectsVerticalWriting(css));
        String real = css + "p:hover{-epub-writing-mode:vertical-lr}";
        assertTrue(EpubCssCompatibility.addWebViewAliases(real).contains("-webkit-writing-mode:vertical-lr;"));
        assertTrue(EpubCssCompatibility.detectsVerticalWriting(real));
    }

    @Test public void commentsBeforeColonsAndInsidePriorityAreHandled() {
        String css = "body{-epub-writing-mode/**/:vertical-rl;"
                + "-epub-text-combine:horizontal ! /* priority */ important}";
        String result = EpubCssCompatibility.addWebViewAliases(css);
        assertTrue(result.contains("writing-mode:vertical-rl;"));
        assertTrue(result.contains("text-combine-upright:all !   important;"));
        assertEquals(result, EpubCssCompatibility.addWebViewAliases(result));
    }

    @Test public void quotedCommentMarkersAndEscapedDelimitersStayIntact() {
        String css = "body{-epub-text-emphasis-style:'/*';content:escaped\\;value;"
                + "-epub-writing-mode:vertical-rl}";
        String result = EpubCssCompatibility.addWebViewAliases(css);
        assertTrue(result.contains("text-emphasis-style:'/*';-webkit-text-emphasis-style:'/*';"));
        assertTrue(result.contains("content:escaped\\;value;"));
        assertTrue(result.contains("-webkit-writing-mode:vertical-rl;"));
        assertEquals(result, EpubCssCompatibility.addWebViewAliases(result));
    }

    @Test public void sameBlockWritingModeRespectsOrderAndImportance() {
        assertFalse(EpubCssCompatibility.detectsVerticalWriting(
                "body{-epub-writing-mode:vertical-rl;writing-mode:horizontal-tb}"));
        assertTrue(EpubCssCompatibility.detectsVerticalWriting(
                "body{writing-mode:horizontal-tb;writing-mode:vertical-lr}"));
        assertTrue(EpubCssCompatibility.detectsVerticalWriting(
                "body{writing-mode:vertical-rl !important;writing-mode:horizontal-tb}"));
        assertFalse(EpubCssCompatibility.detectsVerticalWriting(
                "body{writing-mode:vertical-rl!important;writing-mode:horizontal-tb ! /*x*/ important}"));
        assertFalse(EpubCssCompatibility.detectsVerticalWriting(
                "writing-mode:horizontal-tb!important;writing-mode:vertical-rl"));
        assertTrue(EpubCssCompatibility.detectsVerticalWriting(
                "body{writing-mode:vertical-rl;writing-mode:not-a-mode}"));
        assertFalse(EpubCssCompatibility.detectsVerticalWriting(
                "body{writing-mode:vertical-rl;writing-mode:initial}"));
    }

    @Test public void inactiveMediaAndKeyframesDoNotSupplyLayoutHints() {
        assertFalse(EpubCssCompatibility.detectsVerticalWriting(
                "@media only print and (min-width:1px){body{writing-mode:vertical-rl}}"
                + "@media speech{body{writing-mode:vertical-rl}}"
                + "@keyframes turn{to{writing-mode:vertical-rl}}"
                + "@font-face{writing-mode:vertical-rl}"));
        assertFalse(EpubCssCompatibility.detectsVerticalWriting(
                "@media print{@media screen{body{writing-mode:vertical-rl}}}"));
        assertTrue(EpubCssCompatibility.detectsVerticalWriting(
                "@media print, screen and (min-width:1px){body{writing-mode:vertical-rl}}"));
        assertTrue(EpubCssCompatibility.detectsVerticalWriting(
                "@supports (writing-mode:vertical-rl){body{writing-mode:vertical-rl}}"));
        assertFalse(EpubCssCompatibility.detectsVerticalWritingInHtml(
                "<style media='only print and (color)'>body{writing-mode:vertical-rl}</style>"));
    }

    @Test public void embeddedStylesAndInlineAttributesReceiveAliases() {
        String html = "<html><head><style>body{-epub-writing-mode:vertical-rl}</style></head>"
                + "<body STYLE='-epub-text-combine:horizontal;content:&quot;a&amp;b&quot;'>text</body></html>";
        String result = EpubCssCompatibility.addHtmlWebViewAliases(html);
        assertTrue(result.contains("writing-mode:vertical-rl;-webkit-writing-mode:vertical-rl;"));
        assertTrue(result.contains("text-combine-upright:all;-webkit-text-combine:horizontal;"));
        assertTrue(result.contains("content:\"a&amp;b\""));
        assertTrue(EpubCssCompatibility.detectsVerticalWritingInHtml(html));
        assertTrue(EpubCssCompatibility.detectsVerticalWritingInHtml(
                "<body style=\"-epub-writing-mode:vertical-lr\">text</body>"));
    }

    @Test public void fakeStylesInCommentsScriptsAndTextAreUntouched() {
        String html = "<!-- <style>body{-epub-writing-mode:vertical-rl}</style> -->"
                + "<script>var text = '<p style=\"-epub-writing-mode:vertical-rl\">';</script>"
                + "<textarea><style>body{-epub-writing-mode:vertical-rl}</style></textarea>"
                + "<style type='text/example'>body{-epub-writing-mode:vertical-rl}</style>"
                + "<p title='x > -epub-writing-mode:vertical-rl'>writing-mode:vertical-rl</p>";
        assertEquals(html, EpubCssCompatibility.addHtmlWebViewAliases(html));
        assertFalse(EpubCssCompatibility.detectsVerticalWritingInHtml(html));
    }

    @Test public void unquotedStylesAreQuotedOnlyWhenChanged() {
        String html = "<body style=-epub-writing-mode:vertical-rl><p style='color:red'>text</p></body>";
        String result = EpubCssCompatibility.addHtmlWebViewAliases(html);
        assertTrue(result.contains("style=\"-epub-writing-mode:vertical-rl;writing-mode:vertical-rl;-webkit-writing-mode:vertical-rl;\""));
        assertTrue(result.contains("<p style='color:red'>"));
        assertEquals("<p style='color:red'>text</p>", EpubCssCompatibility.addHtmlWebViewAliases("<p style='color:red'>text</p>"));
    }

    @Test public void quotedBracesDoNotTurnInlineDeclarationsIntoStylesheets() {
        String css = "-epub-writing-mode:vertical-rl;content:'{';";
        String result = EpubCssCompatibility.addWebViewAliases(css);
        assertTrue(result.contains("-webkit-writing-mode:vertical-rl;"));
        assertTrue(result.contains("content:'{';"));
    }

    @Test
    public void writingModeKeepsOriginalAndAddsBothAliases() {
        String css = "html{-epub-writing-mode: vertical-rl;}";

        String result = EpubCssCompatibility.addWebViewAliases(css);

        assertTrue(result.contains("-epub-writing-mode: vertical-rl;"));
        assertTrue(result.contains("writing-mode:vertical-rl;"));
        assertTrue(result.contains("-webkit-writing-mode:vertical-rl;"));
    }

    @Test
    public void horizontalTextCombineMapsToStandardAll() {
        String css = ".tcy{-epub-text-combine: horizontal !important}";

        String result = EpubCssCompatibility.addWebViewAliases(css);

        assertTrue(result.contains("-epub-text-combine: horizontal !important"));
        assertTrue(result.contains("text-combine-upright:all !important;"));
        assertTrue(result.contains("-webkit-text-combine:horizontal !important;"));
    }

    @Test
    public void emphasisStyleAndColorReceiveStandardAndWebkitAliases() {
        String css = "strong{-epub-text-emphasis-style:sesame;"
                + "-epub-text-emphasis-color:#f00;}";

        String result = EpubCssCompatibility.addWebViewAliases(css);

        assertTrue(result.contains("text-emphasis-style:sesame;"));
        assertTrue(result.contains("-webkit-text-emphasis-style:sesame;"));
        assertTrue(result.contains("text-emphasis-color:#f00;"));
        assertTrue(result.contains("-webkit-text-emphasis-color:#f00;"));
    }

    @Test
    public void commentsAndQuotedContentAreNotRewritten() {
        String css = "/* html{-epub-writing-mode:vertical-rl;} */"
                + ".note{content:'x;-epub-writing-mode:vertical-rl;"
                + "-epub-text-emphasis-style:sesame';}";

        assertEquals(css, EpubCssCompatibility.addWebViewAliases(css));
        assertFalse(EpubCssCompatibility.detectsVerticalWriting(css));
    }

    @Test
    public void existingStandardDeclarationsRemainUntouched() {
        String css = "html{writing-mode:vertical-rl;-webkit-writing-mode:vertical-rl;}";

        assertEquals(css, EpubCssCompatibility.addWebViewAliases(css));
    }

    @Test
    public void detectsLegacyStandardAndWebkitVerticalWriting() {
        assertTrue(EpubCssCompatibility.detectsVerticalWriting(
                "html{-epub-writing-mode:vertical-rl}"));
        assertTrue(EpubCssCompatibility.detectsVerticalWriting(
                "html{writing-mode: vertical-lr;}"));
        assertTrue(EpubCssCompatibility.detectsVerticalWriting(
                "html{-webkit-writing-mode:vertical-rl;}"));
        assertFalse(EpubCssCompatibility.detectsVerticalWriting(
                "html{writing-mode:horizontal-tb;}"));
    }
}
