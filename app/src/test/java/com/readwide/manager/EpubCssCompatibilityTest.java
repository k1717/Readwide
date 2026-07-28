package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EpubCssCompatibilityTest {

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
