package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

/** Regression tests for OPF package, spine, overlay, and binding metadata. */
public class DocumentArchiveUtilsMetadataTest {

    @Test
    public void packagePrePaginatedDoesNotErasePageReflowableOverride() throws Exception {
        String opf = packageDocument(
                "<metadata><meta property='rendition:layout'>pre-paginated</meta></metadata>",
                "<manifest>"
                        + htmlItem("one", "text/one.xhtml", "")
                        + htmlItem("two", "text/two.xhtml", "")
                        + "</manifest>",
                "<spine><itemref idref='one' "
                        + "properties='rendition:layout-reflowable'/><itemref idref='two'/></spine>");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/text/one.xhtml", html("one"));
        entries.put("OPS/text/two.xhtml", html("two"));

        File epub = createEpub(opf, entries);
        try (ZipFile zip = new ZipFile(epub)) {
            assertTrue(DocumentArchiveUtils.detectEpubFixedLayoutLike(zip));
            List<DocumentArchiveUtils.EpubSpineItem> spine =
                    DocumentArchiveUtils.findEpubSpineItems(zip);
            assertEquals(2, spine.size());
            assertTrue(spine.get(0).isReflowableOverride());
            assertFalse(spine.get(0).isFixedLayoutOverride());
            assertFalse(spine.get(1).isReflowableOverride());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

    @Test
    public void packageReflowableDoesNotErasePagePrePaginatedOverride() throws Exception {
        String opf = packageDocument(
                "<metadata><meta property='rendition:layout'>reflowable</meta></metadata>",
                "<manifest>" + htmlItem("page", "text/page.xhtml", "") + "</manifest>",
                "<spine><itemref idref='page' "
                        + "properties='rendition:layout-pre-paginated'/></spine>");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/text/page.xhtml", html("page"));

        File epub = createEpub(opf, entries);
        try (ZipFile zip = new ZipFile(epub)) {
            assertFalse(DocumentArchiveUtils.detectEpubFixedLayoutLike(zip));
            List<DocumentArchiveUtils.EpubSpineItem> spine =
                    DocumentArchiveUtils.findEpubSpineItems(zip);
            assertEquals(1, spine.size());
            assertTrue(spine.get(0).isFixedLayoutOverride());
            assertFalse(spine.get(0).isReflowableOverride());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

    @Test
    public void spineKeepsItemrefIdentityIndexLinearFlagAndLinkedSmil() throws Exception {
        String opf = packageDocument(
                "<metadata><meta property='media:active-class'>spoken</meta></metadata>",
                "<manifest>"
                        + item("unsupported", "data/raw.bin", "application/x-raw", "", "")
                        + item("chapter", "text/chapter.xhtml", "application/xhtml+xml",
                        "scripted", " media-overlay='chapter-mo'")
                        + item("chapter-mo", "overlays/chapter.smil",
                        "application/smil+xml", "", "")
                        + "</manifest>",
                "<spine><itemref id='raw-ref' idref='unsupported'/>"
                        + "<itemref id='chapter-ref' idref='chapter' linear='no'/></spine>");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/data/raw.bin", new byte[]{1});
        entries.put("OPS/text/chapter.xhtml", html("chapter"));
        entries.put("OPS/overlays/chapter.smil", utf8(
                "<smil xmlns='http://www.w3.org/ns/SMIL'><body/></smil>"));

        File epub = createEpub(opf, entries);
        try (ZipFile zip = new ZipFile(epub)) {
            List<DocumentArchiveUtils.EpubSpineItem> spine =
                    DocumentArchiveUtils.findEpubSpineItems(zip);
            assertEquals(1, spine.size());
            DocumentArchiveUtils.EpubSpineItem chapter = spine.get(0);
            assertEquals("chapter", chapter.manifestId);
            assertEquals("chapter-ref", chapter.itemRefId);
            assertEquals(1, chapter.spineIndex);
            assertEquals("OPS/text/chapter.xhtml", chapter.path);
            assertEquals("OPS/overlays/chapter.smil", chapter.mediaOverlayPath);
            assertTrue(chapter.hasMediaOverlay());
            assertTrue(chapter.isScripted());
            assertFalse(chapter.linear);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

    @Test
    public void bindingsAcceptOnlyDirectScriptedXhtmlHandlers() throws Exception {
        String opf = packageDocument(
                "<metadata>"
                        + "<meta property='media:active-class'>spoken</meta>"
                        + "<mediaType media-type='application/x-outside' handler='valid'/>"
                        + "</metadata>",
                "<manifest>"
                        + htmlItem("page", "text/page.xhtml", "")
                        + htmlItem("valid", "handlers/valid.xhtml", "scripted")
                        + htmlItem("duplicate", "handlers/duplicate.xhtml", "scripted")
                        + htmlItem("not-scripted", "handlers/not-scripted.xhtml", "")
                        + item("wrong-mime", "handlers/wrong.xhtml",
                        "application/octet-stream", "scripted", "")
                        + htmlItem("missing", "handlers/missing.xhtml", "scripted")
                        + item("custom", "data/figure.bin", "application/x-figure", "", "")
                        + "</manifest>"
                        + "<bindings>"
                        + binding("Application/X-Figure", "valid")
                        + binding("application/x-figure", "duplicate")
                        + binding("application/x-no-script", "not-scripted")
                        + binding("application/x-wrong", "wrong-mime")
                        + binding("application/x-missing", "missing")
                        + binding("not a mime", "valid")
                        + "</bindings>",
                "<spine><itemref idref='page'/></spine>");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/text/page.xhtml", html("page"));
        entries.put("OPS/handlers/valid.xhtml", html("valid"));
        entries.put("OPS/handlers/duplicate.xhtml", html("duplicate"));
        entries.put("OPS/handlers/not-scripted.xhtml", html("not scripted"));
        entries.put("OPS/handlers/wrong.xhtml", html("wrong MIME"));
        entries.put("OPS/data/figure.bin", new byte[]{3, 4});

        File epub = createEpub(opf, entries);
        try (ZipFile zip = new ZipFile(epub)) {
            DocumentArchiveUtils.EpubPackageResources resources =
                    DocumentArchiveUtils.findEpubPackageResources(zip);
            assertEquals("OPS/package.opf", resources.packagePath);
            assertEquals("spoken", resources.mediaOverlayActiveClass);
            assertEquals("application/x-figure",
                    resources.mediaTypeForPath("OPS/data/figure.bin"));
            assertEquals(1, resources.bindingsByMediaType.size());
            DocumentArchiveUtils.EpubBinding binding =
                    resources.bindingsByMediaType.get("application/x-figure");
            assertTrue(binding != null);
            assertEquals("Application/X-Figure", binding.mediaType);
            assertEquals("OPS/handlers/valid.xhtml", binding.handlerPath);
            assertFalse(resources.bindingsByMediaType.containsKey("application/x-outside"));
            assertFalse(resources.bindingsByMediaType.containsKey("application/x-no-script"));
            assertFalse(resources.bindingsByMediaType.containsKey("application/x-wrong"));
            assertFalse(resources.bindingsByMediaType.containsKey("application/x-missing"));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

    @Test
    public void missingOrWrongTypeMediaOverlayIsNotAttached() throws Exception {
        String opf = packageDocument(
                "<metadata/>",
                "<manifest>"
                        + item("missing-page", "text/missing.xhtml", "application/xhtml+xml",
                        "", " media-overlay='missing-mo'")
                        + item("wrong-page", "text/wrong.xhtml", "application/xhtml+xml",
                        "", " media-overlay='wrong-mo'")
                        + item("missing-mo", "overlays/missing.smil",
                        "application/smil+xml", "", "")
                        + item("wrong-mo", "overlays/wrong.smil",
                        "application/xml", "", "")
                        + "</manifest>",
                "<spine><itemref idref='missing-page'/><itemref idref='wrong-page'/></spine>");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/text/missing.xhtml", html("missing"));
        entries.put("OPS/text/wrong.xhtml", html("wrong"));
        entries.put("OPS/overlays/wrong.smil", utf8("<smil/>"));

        File epub = createEpub(opf, entries);
        try (ZipFile zip = new ZipFile(epub)) {
            List<DocumentArchiveUtils.EpubSpineItem> spine =
                    DocumentArchiveUtils.findEpubSpineItems(zip);
            assertEquals(2, spine.size());
            assertFalse(spine.get(0).hasMediaOverlay());
            assertFalse(spine.get(1).hasMediaOverlay());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

    @Test
    public void renderedFallbackOwnsItsMediaOverlay() throws Exception {
        String opf = packageDocument(
                "<metadata/>",
                "<manifest>"
                        + item("primary", "data/raw.bin", "application/x-raw", "scripted",
                        " fallback='fallback-page' media-overlay='wrong-mo'")
                        + item("fallback-page", "text/fallback.xhtml", "application/xhtml+xml", "",
                        " media-overlay='right-mo'")
                        + item("wrong-mo", "overlays/wrong.smil", "application/smil+xml", "", "")
                        + item("right-mo", "overlays/right.smil", "application/smil+xml", "", "")
                        + "</manifest>",
                "<spine><itemref idref='primary'/></spine>");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/data/raw.bin", new byte[]{1});
        entries.put("OPS/text/fallback.xhtml", html("fallback"));
        entries.put("OPS/overlays/wrong.smil", utf8("<smil/>"));
        entries.put("OPS/overlays/right.smil", utf8("<smil/>"));

        File epub = createEpub(opf, entries);
        try (ZipFile zip = new ZipFile(epub)) {
            List<DocumentArchiveUtils.EpubSpineItem> spine =
                    DocumentArchiveUtils.findEpubSpineItems(zip);
            assertEquals(1, spine.size());
            assertEquals("fallback-page", spine.get(0).manifestId);
            assertEquals("OPS/overlays/right.smil", spine.get(0).mediaOverlayPath);
            assertFalse(spine.get(0).isScripted());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

    private static String packageDocument(String metadata, String manifest, String spine) {
        return "<?xml version='1.0' encoding='UTF-8'?>"
                + "<package xmlns='http://www.idpf.org/2007/opf' version='3.0'>"
                + metadata + manifest + spine + "</package>";
    }

    private static String htmlItem(String id, String href, String properties) {
        return item(id, href, "application/xhtml+xml", properties, "");
    }

    private static String item(String id,
                               String href,
                               String mediaType,
                               String properties,
                               String extraAttributes) {
        return "<item id='" + id + "' href='" + href + "' media-type='" + mediaType + "'"
                + (properties.isEmpty() ? "" : " properties='" + properties + "'")
                + extraAttributes + "/>";
    }

    private static String binding(String mediaType, String handler) {
        return "<mediaType media-type='" + mediaType + "' handler='" + handler + "'/>";
    }

    private static byte[] html(String body) {
        return utf8("<html xmlns='http://www.w3.org/1999/xhtml'><body>"
                + body + "</body></html>");
    }

    private static File createEpub(String opf, Map<String, byte[]> entries) throws Exception {
        File file = File.createTempFile("readwide-epub-metadata-", ".epub");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            put(out, "META-INF/container.xml", utf8(
                    "<?xml version='1.0' encoding='UTF-8'?>"
                            + "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container' "
                            + "version='1.0'><rootfiles><rootfile "
                            + "full-path='OPS/package.opf' "
                            + "media-type='application/oebps-package+xml'/>"
                            + "</rootfiles></container>"));
            put(out, "OPS/package.opf", utf8(opf));
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                put(out, entry.getKey(), entry.getValue());
            }
        }
        return file;
    }

    private static void put(ZipOutputStream out, String name, byte[] data) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(data);
        out.closeEntry();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
