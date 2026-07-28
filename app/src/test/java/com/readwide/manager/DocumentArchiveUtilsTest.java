package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

public class DocumentArchiveUtilsTest {

    @Test
    public void epub3CoverImagePropertyWinsOverOtherManifestImages() throws Exception {
        String opf = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<package xmlns='http://www.idpf.org/2007/opf' version='3.0'>"
                + "<manifest>"
                + "<item id='page' href='images/001.jpg' media-type='image/jpeg'/>"
                + "<item id='cover' href='images/front+cover.jpg' media-type='image/jpeg' "
                + "properties='cover-image'/>"
                + "</manifest><spine><itemref idref='page'/></spine></package>";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/images/001.jpg", new byte[]{1});
        entries.put("OPS/images/front+cover.jpg", new byte[]{2});

        File epub = createEpub(opf, entries);
        try (ZipFile zip = new ZipFile(epub)) {
            assertEquals("OPS/images/front+cover.jpg",
                    DocumentArchiveUtils.findEpubCoverImagePath(zip));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

    @Test
    public void epub2CoverMetadataResolvesManifestId() throws Exception {
        String opf = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<package xmlns='http://www.idpf.org/2007/opf' version='2.0'>"
                + "<metadata><meta name='cover' content='front'/></metadata>"
                + "<manifest>"
                + "<item id='other' href='images/001.png' media-type='image/png'/>"
                + "<item id='front' href='images/cover.png' media-type='image/png'/>"
                + "</manifest><spine><itemref idref='other'/></spine></package>";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/images/001.png", new byte[]{1});
        entries.put("OPS/images/cover.png", new byte[]{2});

        File epub = createEpub(opf, entries);
        try (ZipFile zip = new ZipFile(epub)) {
            assertEquals("OPS/images/cover.png",
                    DocumentArchiveUtils.findEpubCoverImagePath(zip));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

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
    public void pageLocalClassifierSeparatesImageAndTextFixedPages() {
        String imagePage = "<html><body><img src='page.jpg'></body></html>";
        String textPage = "<html><body><h1>Notes</h1><p>"
                + repeat("This fixed-layout page contains readable text. ", 80)
                + "</p></body></html>";

        assertTrue(EpubImagePageClassifier.isImageDominantPage(imagePage, true));
        assertFalse(EpubImagePageClassifier.isImageDominantPage(textPage, true));
    }

    @Test
    public void shortMixedImageAndTextPageKeepsNaturalScrolling() {
        String mixedPage = "<html><body style='background-image:url(page.jpg)'>"
                + "<h1>Feature</h1><p>" + repeat(
                "This illustrated fixed-layout page still contains article text. ", 6)
                + "</p></body></html>";

        assertFalse(EpubImagePageClassifier.isImageDominantPage(mixedPage, true));
        assertTrue(DocumentArchiveUtils.detectEpubImagePageLike(
                Arrays.asList(mixedPage, mixedPage, mixedPage), true));
    }

    @Test
    public void imageBookMayStillContainAnUnclippedTextPage() {
        String image1 = "<html><body><img src='001.jpg'></body></html>";
        String image2 = "<html><body><img src='002.jpg'></body></html>";
        String image3 = "<html><body><img src='003.jpg'></body></html>";
        String textPage = "<html><body><h1>Appendix</h1><p>"
                + repeat("Long text must retain the fixed-layout overflow policy. ", 90)
                + "</p></body></html>";
        List<String> pages = Arrays.asList(image1, image2, image3, textPage);

        assertTrue(DocumentArchiveUtils.detectEpubImagePageLike(pages, true));
        assertFalse(EpubImagePageClassifier.isImageDominantPage(textPage, true));
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

    @Test
    public void directImageSpineWrapperKeepsUnicodeNameAndViewport() {
        String html = DocumentArchiveUtils.buildDirectImageSpineHtml(
                "OPS/images/夏目&漱石.jpg", 600, 837);
        assertTrue(html.contains("width=600, height=837"));
        assertTrue(html.contains("src=\"%E5%A4%8F%E7%9B%AE%26%E6%BC%B1%E7%9F%B3.jpg\""));
        assertTrue(EpubImagePageClassifier.isImageDominantPage(html, true));
    }

    @Test
    public void itemLevelFixedPagesDoNotMakeMixedBookGloballyFixed() throws Exception {
        StringBuilder manifest = new StringBuilder();
        StringBuilder spine = new StringBuilder();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            manifest.append("<item id='p").append(i).append("' href='text/p")
                    .append(i).append(".xhtml' media-type='application/xhtml+xml'/>");
            spine.append("<itemref idref='p").append(i).append("'")
                    .append(i < 4 ? " properties='rendition:layout-pre-paginated'" : "")
                    .append("/>");
            String viewport = i < 4
                    ? "<meta name='viewport' content='width=1200,height=1600'/>" : "";
            entries.put("OPS/text/p" + i + ".xhtml", utf8(
                    "<html><head>" + viewport + "</head><body>page " + i + "</body></html>"));
        }
        String opf = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<package xmlns='http://www.idpf.org/2007/opf' version='3.0'>"
                + "<manifest>" + manifest + "</manifest><spine>" + spine
                + "</spine></package>";

        File epub = createEpub(opf, entries);
        try (ZipFile zip = new ZipFile(epub)) {
            assertFalse(DocumentArchiveUtils.detectEpubFixedLayoutLike(zip));
            List<DocumentArchiveUtils.EpubSpineItem> items =
                    DocumentArchiveUtils.findEpubSpineItems(zip);
            assertTrue(items.get(0).isFixedLayoutOverride());
            assertFalse(items.get(5).isFixedLayoutOverride());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

    @Test
    public void packageLevelPrePaginatedMetadataStillMarksWholeBookFixed() throws Exception {
        String opf = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<package xmlns='http://www.idpf.org/2007/opf' version='3.0'>"
                + "<metadata><meta property='rendition:layout'>pre-paginated</meta></metadata>"
                + "<manifest><item id='p' href='text/p.xhtml' "
                + "media-type='application/xhtml+xml'/></manifest>"
                + "<spine><itemref idref='p'/></spine></package>";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/text/p.xhtml", utf8("<html><body>fixed page</body></html>"));

        File epub = createEpub(opf, entries);
        try (ZipFile zip = new ZipFile(epub)) {
            assertTrue(DocumentArchiveUtils.detectEpubFixedLayoutLike(zip));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

    @Test
    public void typedSpineKeepsDirectImagesAndPerItemProperties() throws Exception {
        String opf = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<package xmlns='http://www.idpf.org/2007/opf' version='3.0'>"
                + "<manifest>"
                + "<item id='image' href='images/01.jpg' media-type='image/jpeg' "
                + "fallback='fallback' properties='rendition:layout-pre-paginated'/>"
                + "<item id='fallback' href='text/fallback.xhtml' media-type='application/xhtml+xml'/>"
                + "<item id='about' href='text/about.xhtml' media-type='application/xhtml+xml'/>"
                + "</manifest><spine>"
                + "<itemref idref='image' properties='page-spread-left'/>"
                + "<itemref idref='about' properties='rendition:layout-reflowable'/>"
                + "</spine></package>";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/images/01.jpg", new byte[]{1, 2, 3});
        entries.put("OPS/text/fallback.xhtml", utf8("<html><body>fallback</body></html>"));
        entries.put("OPS/text/about.xhtml", utf8("<html><body>about</body></html>"));

        File epub = createEpub(opf, entries);
        try (ZipFile zip = new ZipFile(epub)) {
            List<DocumentArchiveUtils.EpubSpineItem> spine =
                    DocumentArchiveUtils.findEpubSpineItems(zip);
            assertEquals(2, spine.size());

            DocumentArchiveUtils.EpubSpineItem image = spine.get(0);
            assertEquals("OPS/images/01.jpg", image.path);
            assertEquals("image/jpeg", image.mediaType);
            assertEquals("fallback", image.fallback);
            assertTrue(image.isImage());
            assertFalse(image.isHtml());
            assertTrue(image.hasProperty("rendition:layout-pre-paginated"));
            assertTrue(image.hasProperty("page-spread-left"));

            DocumentArchiveUtils.EpubSpineItem about = spine.get(1);
            assertEquals("OPS/text/about.xhtml", about.path);
            assertTrue(about.isHtml());
            assertTrue(about.isReflowableOverride());

            // The compatibility API remains XHTML-only.
            assertEquals(Arrays.asList("OPS/text/about.xhtml"),
                    DocumentArchiveUtils.findEpubSpinePaths(zip));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

    @Test
    public void unsupportedSpineResourceFollowsFallbackChain() throws Exception {
        String opf = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<package xmlns='http://www.idpf.org/2007/opf' version='3.0'>"
                + "<manifest>"
                + "<item id='raw' href='payload/raw.bin' media-type='application/x-raw' "
                + "fallback='middle' properties='source-property'/>"
                + "<item id='middle' href='payload/middle.bin' media-type='application/x-middle' "
                + "fallback='html'/>"
                + "<item id='html' href='text/chapter.xhtml' media-type='application/xhtml+xml' "
                + "properties='fallback-property'/>"
                + "</manifest><spine><itemref idref='raw' properties='itemref-property'/></spine>"
                + "</package>";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/payload/raw.bin", new byte[]{1});
        entries.put("OPS/payload/middle.bin", new byte[]{2});
        entries.put("OPS/text/chapter.xhtml", utf8("<html><body>chapter</body></html>"));

        File epub = createEpub(opf, entries);
        try (ZipFile zip = new ZipFile(epub)) {
            List<DocumentArchiveUtils.EpubSpineItem> spine =
                    DocumentArchiveUtils.findEpubSpineItems(zip);
            assertEquals(1, spine.size());
            DocumentArchiveUtils.EpubSpineItem item = spine.get(0);
            assertEquals("OPS/text/chapter.xhtml", item.path);
            assertEquals("application/xhtml+xml", item.mediaType);
            assertEquals("middle", item.fallback);
            assertTrue(item.isHtml());
            // The unsupported primary resource's capability flags must not be
            // applied to the rendered fallback DOM.
            assertFalse(item.hasProperty("source-property"));
            assertTrue(item.hasProperty("fallback-property"));
            assertTrue(item.hasProperty("itemref-property"));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

    @Test
    public void fallbackCycleIsSkippedWithoutDroppingLaterSpineItems() throws Exception {
        String opf = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<package xmlns='http://www.idpf.org/2007/opf' version='3.0'>"
                + "<manifest>"
                + "<item id='a' href='payload/a.bin' media-type='application/x-a' fallback='b'/>"
                + "<item id='b' href='payload/b.bin' media-type='application/x-b' fallback='a'/>"
                + "<item id='image' href='images/page.png' media-type='image/png'/>"
                + "</manifest><spine><itemref idref='a'/><itemref idref='image'/></spine>"
                + "</package>";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/payload/a.bin", new byte[]{1});
        entries.put("OPS/payload/b.bin", new byte[]{2});
        entries.put("OPS/images/page.png", new byte[]{3});

        File epub = createEpub(opf, entries);
        try (ZipFile zip = new ZipFile(epub)) {
            List<DocumentArchiveUtils.EpubSpineItem> spine =
                    DocumentArchiveUtils.findEpubSpineItems(zip);
            assertEquals(1, spine.size());
            assertEquals("OPS/images/page.png", spine.get(0).path);
            assertTrue(spine.get(0).isImage());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

    private static File createEpub(String opf, Map<String, byte[]> entries) throws Exception {
        File file = File.createTempFile("readwide-epub-spine-", ".epub");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            putZipEntry(out, "META-INF/container.xml", utf8(
                    "<?xml version='1.0' encoding='UTF-8'?>"
                            + "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container' version='1.0'>"
                            + "<rootfiles><rootfile full-path='OPS/package.opf' "
                            + "media-type='application/oebps-package+xml'/></rootfiles></container>"));
            putZipEntry(out, "OPS/package.opf", utf8(opf));
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                putZipEntry(out, entry.getKey(), entry.getValue());
            }
        }
        return file;
    }

    private static void putZipEntry(ZipOutputStream out, String name, byte[] data) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(data);
        out.closeEntry();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
