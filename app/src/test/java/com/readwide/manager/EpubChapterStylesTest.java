package com.readwide.manager;

import com.readwide.manager.util.EpubBindingRewriter;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class EpubChapterStylesTest {
    @Test public void mediaHintsAreConservativeAndSharedByLinksAndImports() {
        for (String media : Arrays.asList("print", "only print and (color)", "speech", "not screen",
                "not all", "print, speech", "/*x*/ PRINT and (min-width: 1px)")) {
            assertFalse(media, EpubBindingRewriter.mayApplyToScreen(media));
        }
        for (String media : Arrays.asList("", "screen", "all", "not print", "not screen and (color)",
                "print, screen", "(min-width: 1px)", "unknown", "layer(theme) screen",
                "supports(display: grid) screen", "print or (color)")) {
            assertTrue(media, EpubBindingRewriter.mayApplyToScreen(media));
        }
        List<String> links = new ArrayList<>();
        List<String> styles = new ArrayList<>();
        EpubBindingRewriter.inspectDocumentStyles(
                "<link rel='stylesheet' media='only print and (color)' href='print.css'>"
                + "<link rel='stylesheet' media='print, screen' href='screen.css'>"
                + "<style media='not screen'>ignored</style><style media='not print'>kept</style>",
                styles::add, links::add);
        assertEquals(Arrays.asList("screen.css"), links);
        assertEquals(Arrays.asList("kept"), styles);
        assertEquals(Arrays.asList("screen.css", "conditional.css"), EpubCssCompatibility.importedStylesheets(
                "@import 'print.css' only print and (color); @import 'speech.css' speech;"
                + "@import 'screen.css' print, screen; @import 'conditional.css' not screen and (color);"));
    }
    @Test public void chaptersUseOnlyTheirOwnStylesAndFollowCyclicImports() throws Exception {
        File file = File.createTempFile("readwide-chapter-styles", ".epub");
        try {
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
                put(out, "OPS/css/horizontal.css", "body{writing-mode:horizontal-tb}");
                put(out, "OPS/css/a.css", "@import 'b.css';");
                put(out, "OPS/css/b.css", "@import url(a.css); @import url('vertical.css');");
                put(out, "OPS/css/vertical.css", "body{-epub-writing-mode:vertical-rl}");
                put(out, "unused.css", "body{writing-mode:vertical-lr}");
            }
            try (CountingZipFile zip = new CountingZipFile(file)) {
                DocumentArchiveUtils.EpubChapterStyles styles = new DocumentArchiveUtils.EpubChapterStyles(zip);
                String chapter = "OPS/Text/chapter.xhtml";
                assertFalse(styles.isVertical(chapter, "<body>horizontal</body>"));
                assertFalse(styles.isVertical(chapter, link("../css/horizontal.css")));
                assertTrue(styles.isVertical(chapter, link("../css/a.css")));
                assertTrue(styles.isVertical(chapter, link("../css/b.css")));
                assertTrue(styles.isVertical(chapter, link("missing.css") + link("../css/a.css")));
                assertFalse(styles.isVertical(chapter, link("../css/horizontal.css")));
                assertTrue(styles.isVertical(chapter, "<style>@import '../css/a.css';</style>"));
                assertTrue(styles.isVertical(chapter, "<body style='writing-mode:vertical-lr'>text</body>"));
                assertFalse(styles.isVertical(chapter, "<style media='print'>body{writing-mode:vertical-rl}</style>"));
                StringBuilder repeated = new StringBuilder();
                for (int i = 0; i < 250; i++) repeated.append(link("../css/horizontal.css"));
                repeated.append(link("../css/a.css"));
                assertTrue(styles.isVertical(chapter, repeated.toString()));
                assertEquals("shared/cyclic styles are read only once", 4, zip.reads);
            }
        } finally { file.delete(); }
    }

    @Test public void stylesheetReferencesAreLocalAndDecodedOnce() {
        assertEquals("OPS/%20/css/a+b.css", DocumentArchiveUtils.resolveStylesheetPath(
                "OPS/%20/Text/chapter.xhtml", "../css/a+b.css?x=1#part"));
        assertEquals("OPS/css/a b.css", DocumentArchiveUtils.resolveStylesheetPath(
                "OPS/Text/chapter.xhtml", "../css/a%20b.css"));
        assertNull(DocumentArchiveUtils.resolveStylesheetPath("OPS/ch.xhtml", "../../outside.css"));
        assertNull(DocumentArchiveUtils.resolveStylesheetPath("OPS/ch.xhtml", "https://example.org/v.css"));
        assertNull(DocumentArchiveUtils.resolveStylesheetPath("OPS/ch.xhtml", "//example.org/v.css"));
        assertNull(DocumentArchiveUtils.resolveStylesheetPath("OPS/ch.xhtml", "%2Foutside.css"));
    }

    @Test public void linksIgnoreInactiveAndFakeStylesheets() {
        List<String> links = new ArrayList<>();
        String html = "<!--" + link("fake.css") + "--><script>var s=\"<link rel='stylesheet' href='script.css'>\";</script>"
                + "<link rel='alternate stylesheet' href='alternate.css'>"
                + "<link rel='stylesheet' disabled href='disabled.css'>"
                + "<link rel='stylesheet' media='print' href='print.css'>"
                + "<link REL='STYLESHEET' href='real.css?a=1&amp;b=2'>";
        EpubBindingRewriter.inspectDocumentStyles(html, css -> {}, links::add);
        assertEquals(Arrays.asList("real.css?a=1&b=2"), links);
    }

    @Test public void importsRespectStringsCommentsOrderAndEscapes() {
        String css = "@charset \"UTF-8\"; /* @import 'fake.css'; */ @layer base;"
                + "@import url(\"a.css\"); @import 'b.css' screen; @import 'print.css' print;"
                + "@import url(c\\20 d.css); body{content:\"@import 'fake.css';\";} @import 'late.css';";
        assertEquals(Arrays.asList("a.css", "b.css", "c d.css"), EpubCssCompatibility.importedStylesheets(css));
        assertTrue(EpubCssCompatibility.importedStylesheets("@layer base { @import 'nested.css'; }").isEmpty());
        assertTrue(EpubCssCompatibility.importedStylesheets("@import-bogus 'wrong.css';").isEmpty());
    }

    private static String link(String href) { return "<link rel='stylesheet' href='" + href + "'>"; }
    private static final class CountingZipFile extends ZipFile {
        int reads;
        CountingZipFile(File file) throws java.io.IOException { super(file); }
        @Override public java.io.InputStream getInputStream(ZipEntry entry) throws java.io.IOException {
            reads++;
            return super.getInputStream(entry);
        }
    }
    private static void put(ZipOutputStream out, String path, String value) throws Exception {
        out.putNextEntry(new ZipEntry(path));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
