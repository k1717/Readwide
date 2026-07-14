package com.readwide.manager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class DocumentArchiveUtilsTest {

    @Test
    public void imagePageEpubIsEligibleForLandscapeSpread() {
        List<String> pages = Arrays.asList(
                "<html><body><img src='001.jpg'></body></html>",
                "<html><body><img src='002.jpg'></body></html>",
                "<html><body><svg viewBox='0 0 1200 1800'><image href='003.png'/></svg></body></html>",
                "<html><body><img src='004.webp'></body></html>");

        assertTrue(DocumentArchiveUtils.detectEpubImagePageLike(pages, false));
    }

    @Test
    public void fixedLayoutTextBookIsNotTreatedAsImagePages() {
        String chapter = "<html><body><img src='ornament.png'>"
                + "<h1>Chapter</h1><p>" + repeat("Readable paragraph text. ", 80)
                + "</p></body></html>";

        assertFalse(DocumentArchiveUtils.detectEpubImagePageLike(
                Arrays.asList(chapter, chapter, chapter), true));
    }

    @Test
    public void coverOnlyImageDoesNotEnableSpreads() {
        String cover = "<html><body><img src='cover.jpg'></body></html>";
        String text = "<html><body><h1>Chapter</h1><p>"
                + repeat("This is ordinary reflowable text. ", 70)
                + "</p></body></html>";

        assertFalse(DocumentArchiveUtils.detectEpubImagePageLike(
                Arrays.asList(cover, text, text, text), false));
    }


    @Test
    public void embeddedImageObjectsAreRecognized() {
        List<String> pages = Arrays.asList(
                "<html><body><object data='p1.webp' type='image/webp'></object></body></html>",
                "<html><body><embed src='p2.png' type='image/png'></body></html>",
                "<html><body><object data='p3.svg' type='image/svg+xml'></object></body></html>"
        );
        assertTrue(DocumentArchiveUtils.detectEpubImagePageLike(pages, true));
    }

    @Test
    public void nonImageEmbeddedObjectsDoNotEnableSpreads() {
        String page = "<html><body><object data='audio.mp3' type='audio/mpeg'></object></body></html>";
        assertFalse(DocumentArchiveUtils.detectEpubImagePageLike(
                Arrays.asList(page, page, page), true));
    }

    @Test
    public void cssBackgroundImagePagesAreRecognized() {
        String page = "<html><head><style>body{background-image:url(page.jpg)}</style></head>"
                + "<body></body></html>";

        assertTrue(DocumentArchiveUtils.detectEpubImagePageLike(
                Arrays.asList(page, page, page), true));
    }



    @Test
    public void backgroundImageNoneDoesNotEnableSpreads() {
        String page = "<html><head><style>body{background-image:none}</style></head>"
                + "<body></body></html>";
        assertFalse(DocumentArchiveUtils.detectEpubImagePageLike(
                Arrays.asList(page, page, page), true));
    }

    @Test
    public void unrelatedImageFilenameDoesNotTurnAudioObjectIntoImagePage() {
        String page = "<html><head><style>.note:after{content:'cover.jpg'}</style></head>"
                + "<body><object data='audio.mp3' type='audio/mpeg'></object></body></html>";
        assertFalse(DocumentArchiveUtils.detectEpubImagePageLike(
                Arrays.asList(page, page, page), true));
    }

    @Test
    public void manifestHrefKeepsLiteralPlusAndDecodesPercentEscapes() {
        assertTrue("Text/chapter+1.xhtml".equals(
                DocumentArchiveUtils.decodeHref("Text/chapter+1.xhtml")));
        assertTrue("Text/chapter 2.xhtml".equals(
                DocumentArchiveUtils.decodeHref("Text/chapter%202.xhtml")));
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
