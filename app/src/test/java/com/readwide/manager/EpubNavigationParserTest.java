package com.readwide.manager;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class EpubNavigationParserTest {
    private static final String HTML = "<html xmlns='http://www.w3.org/1999/xhtml' "
            + "xmlns:e='http://www.idpf.org/2007/ops'><body>";
    private static final String END = "</body></html>";

    @Test public void resolvesDecodedBaseAndEncodedHrefExactlyOnce() {
        EpubNavigationParser.Target target = EpubNavigationParser.resolve(
                "OPS%20/nav.xhtml", "text/a+%2520.xhtml?ignored#n%2B%2520");
        assertEquals("OPS%20/text/a+%20.xhtml", target.path);
        assertEquals("n+%20", target.fragment);
        assertEquals("OPS/a.xhtml", EpubNavigationParser.resolve("OPS/a.xhtml", "#한글").path);
        assertEquals("a.xhtml", EpubNavigationParser.resolve("OPS/nav.xhtml", "../a.xhtml").path);
    }

    @Test public void rejectsExternalAndEscapingTargets() {
        for (String href : Arrays.asList("https://evil/a", "//evil/a", "/a", "../../a", "%2f%2fevil/a",
                "javascript:alert(1)", "file%3A/a", "a%00.xhtml", "a\\b", "a#%00")) {
            assertNull(href, EpubNavigationParser.resolve("OPS/nav.xhtml", href));
        }
    }

    @Test public void readsNestedTocNotLandmarksAndFiltersUnavailableTargets() throws Exception {
        String nav = HTML + "<nav e:type='landmarks'><ol><li><a href='a.xhtml'>Wrong</a></li></ol></nav>"
                + "<nav e:type='toc'><ol><li><span>Part I</span><ol>"
                + "<li><a href='a.xhtml#first'>One <em>chapter</em></a></li>"
                + "<li><a href='https://evil'>Remote</a></li><li><a href='missing.xhtml'>Missing</a></li>"
                + "</ol></li><li><a href='b.xhtml'>Two</a></li></ol></nav>" + END;
        File file = zip("OPS/nav.xhtml", nav);
        try (ZipFile zip = new ZipFile(file)) {
            DocumentArchiveUtils.EpubPackageResources metadata = metadata("OPS/nav.xhtml", "");
            List<EpubNavigationParser.Entry> entries = EpubNavigationParser.contents(zip, metadata,
                    new HashSet<>(Arrays.asList("OPS/a.xhtml", "OPS/b.xhtml")));
            assertEquals(3, entries.size());
            assertNull(entries.get(0).target);
            assertEquals("One chapter", entries.get(1).title);
            assertEquals(1, entries.get(1).depth);
            assertEquals("first", entries.get(1).target.fragment);
            assertEquals(0, entries.get(2).depth);
        } finally { file.delete(); }
    }

    @Test public void malformedNavFallsBackToNcxWithInertExternalDoctype() throws Exception {
        String ncx = "<?xml version='1.0'?><!DOCTYPE ncx SYSTEM 'https://invalid.example/external.dtd'>"
                + "<ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap>"
                + "<navPoint><navLabel><text>Parent</text></navLabel><content src='a.xhtml'/>"
                + "<navPoint><navLabel><text>Child</text></navLabel><content src='a.xhtml#child'/></navPoint>"
                + "</navPoint></navMap></ncx>";
        File file = zip("OPS/nav.xhtml", "<broken>", "OPS/toc.ncx", ncx);
        try (ZipFile zip = new ZipFile(file)) {
            List<EpubNavigationParser.Entry> entries = EpubNavigationParser.contents(zip,
                    metadata("OPS/nav.xhtml", "OPS/toc.ncx"), new HashSet<>(Arrays.asList("OPS/a.xhtml")));
            assertEquals(2, entries.size());
            assertEquals(1, entries.get(1).depth);
            assertEquals("child", entries.get(1).target.fragment);
        } finally { file.delete(); }
    }

    @Test public void opfUsesNavPropertyAndExplicitSpineTocReference() throws Exception {
        String container = "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>";
        String opf = "<package><manifest>"
                + "<item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav scripted'/>"
                + "<item id='wrong' href='wrong.ncx' media-type='application/x-dtbncx+xml'/>"
                + "<item id='right' href='right.ncx' media-type='application/x-dtbncx+xml'/>"
                + "</manifest><spine toc='right'/></package>";
        File file = zip("META-INF/container.xml", container, "OPS/book.opf", opf,
                "OPS/nav.xhtml", "<html/>", "OPS/wrong.ncx", "<ncx/>", "OPS/right.ncx", "<ncx/>");
        try (ZipFile zip = new ZipFile(file)) {
            DocumentArchiveUtils.EpubPackageResources metadata = DocumentArchiveUtils.findEpubPackageResources(zip);
            assertEquals("OPS/nav.xhtml", metadata.navigationPath);
            assertEquals("OPS/right.ncx", metadata.ncxPath);
        } finally { file.delete(); }
    }

    @Test public void semanticNoteAncestorIncludesParagraphsButNotScriptsOrBacklinks() throws Exception {
        File file = zip("OPS/notes.xhtml", HTML + "<aside e:type='footnote'><a id='n1'/>"
                + "<p>First <em>note</em>.</p><p>Second paragraph.</p>"
                + "<script>danger()</script><style>hidden</style><a e:type='backlink'>Back</a></aside>" + END);
        try (ZipFile zip = new ZipFile(file)) {
            String note = EpubNavigationParser.footnote(zip,
                    new EpubNavigationParser.Target("OPS/notes.xhtml", "n1"), "OPS/book.xhtml");
            assertEquals("First note.\nSecond paragraph.", note);
        } finally { file.delete(); }
    }

    @Test public void recognizesAriaEndnoteAndLegacyNamedAnchor() throws Exception {
        File file = zip("notes.xhtml", HTML + "<li role='doc-endnote'><a name='old'/>End note</li>" + END);
        try (ZipFile zip = new ZipFile(file)) {
            assertEquals("End note", EpubNavigationParser.footnote(zip,
                    new EpubNavigationParser.Target("notes.xhtml", "old"), "text.xhtml"));
        } finally { file.delete(); }
    }

    @Test public void markedReferenceCanIdentifyAnUnmarkedDestinationOutsideSpine() throws Exception {
        File file = zip("OPS/text.xhtml", HTML + "<a role='doc-noteref' href='notes.xhtml#n'>1</a>" + END,
                "OPS/notes.xhtml", HTML + "<p id='n'>Unmarked note.</p>" + END);
        try (ZipFile zip = new ZipFile(file)) {
            assertEquals("Unmarked note.", EpubNavigationParser.footnote(zip,
                    new EpubNavigationParser.Target("OPS/notes.xhtml", "n"), "OPS/text.xhtml"));
        } finally { file.delete(); }
    }

    @Test public void ordinaryFragmentAndUnknownTargetAreNotGuessedToBeNotes() throws Exception {
        File file = zip("text.xhtml", HTML + "<a href='#note1'>Chapter</a><h2 id='note1'>Not a footnote</h2>" + END);
        try (ZipFile zip = new ZipFile(file)) {
            assertNull(EpubNavigationParser.footnote(zip,
                    new EpubNavigationParser.Target("text.xhtml", "note1"), "text.xhtml"));
            assertNull(EpubNavigationParser.footnote(zip,
                    new EpubNavigationParser.Target("text.xhtml", "missing"), "text.xhtml"));
        } finally { file.delete(); }
    }

    @Test public void rejectsInternalEntitySubsetWithoutResolvingIt() throws Exception {
        File file = zip("notes.xhtml", "<!DOCTYPE html [<!ENTITY x SYSTEM 'file:///private'>]>"
                + HTML + "<aside e:type='footnote' id='n'>&x;</aside>" + END);
        try (ZipFile zip = new ZipFile(file)) {
            assertNull(EpubNavigationParser.footnote(zip,
                    new EpubNavigationParser.Target("notes.xhtml", "n"), "notes.xhtml"));
        } finally { file.delete(); }
    }

    @Test public void readsLegacyNamedEntitiesInContentsAndFootnotesWithoutDtdAccess() throws Exception {
        String dtd = "<!DOCTYPE html SYSTEM 'https://invalid.example/xhtml.dtd'>";
        File file = zip("OPS/nav.xhtml", dtd + HTML
                        + "<nav e:type='toc'><ol><li><a href='text.xhtml'>Part&nbsp;I &mdash; &alpha;</a></li></ol></nav>" + END,
                "OPS/notes.xhtml", dtd + HTML
                        + "<aside e:type='footnote' id='n'>A&nbsp;note &mdash; &lt;script&gt; &amp;nbsp;</aside>" + END);
        try (ZipFile zip = new ZipFile(file)) {
            List<EpubNavigationParser.Entry> entries = EpubNavigationParser.contents(zip,
                    metadata("OPS/nav.xhtml", ""), new HashSet<>(Arrays.asList("OPS/text.xhtml")));
            assertEquals(1, entries.size());
            assertEquals("Part\u00a0I \u2014 \u03b1", entries.get(0).title);
            assertEquals("A\u00a0note \u2014 <script> &nbsp;", EpubNavigationParser.footnote(zip,
                    new EpubNavigationParser.Target("OPS/notes.xhtml", "n"), "OPS/text.xhtml"));
        } finally { file.delete(); }
    }

    @Test public void standaloneLegacyAnchorUsesOnlyImmediatelyFollowingNoteBlock() throws Exception {
        File file = zip("text.xhtml", HTML + "<a e:type='noteref' href='notes.xhtml#n'>1</a>" + END,
                "notes.xhtml", HTML + "<div><a id='n'/> \n<!-- note --><p>Legacy note.</p>"
                        + "<p>Other text must not be included.</p></div>" + END);
        try (ZipFile zip = new ZipFile(file)) {
            assertEquals("Legacy note.", EpubNavigationParser.footnote(zip,
                    new EpubNavigationParser.Target("notes.xhtml", "n"), "text.xhtml"));
        } finally { file.delete(); }
    }

    @Test public void standaloneLegacyAnchorDoesNotCrossAnotherTargetOrHeadingOrProse() throws Exception {
        for (String following : Arrays.asList("<a id='other'/><p>Other note.</p>",
                "<p id='other'>Other note.</p>", "<p><a name='other'/>Other note.</p>",
                "<h2>Chapter</h2><p>Prose.</p>",
                "Unwrapped prose.<p>Paragraph.</p>", "<div><p>A whole section.</p></div>")) {
            File file = zip("text.xhtml", HTML + "<a e:type='noteref' href='notes.xhtml#n'>1</a>" + END,
                    "notes.xhtml", HTML + "<a id='n'/>" + following + END);
            try (ZipFile zip = new ZipFile(file)) {
                assertNull(following, EpubNavigationParser.footnote(zip,
                        new EpubNavigationParser.Target("notes.xhtml", "n"), "text.xhtml"));
            } finally { file.delete(); }
        }
    }

    @Test public void standaloneAnchorStillRequiresAMarkedNoteReference() throws Exception {
        File file = zip("text.xhtml", HTML + "<a href='notes.xhtml#n'>Chapter</a>" + END,
                "notes.xhtml", HTML + "<a id='n'/><p>Not identified as a note.</p>" + END);
        try (ZipFile zip = new ZipFile(file)) {
            assertNull(EpubNavigationParser.footnote(zip,
                    new EpubNavigationParser.Target("notes.xhtml", "n"), "text.xhtml"));
        } finally { file.delete(); }
    }

    @Test public void deeplyNestedOptionalNavigationFallsBackOrParsesWithoutEscaping() throws Exception {
        String ordinaryNav = HTML + "<nav e:type='toc'><ol><li><a href='text.xhtml'>Ordinary</a></li></ol></nav>" + END;
        String deepNav = HTML + nestedMarkup("<nav e:type='toc'><ol><li><a href='text.xhtml'>Deep</a></li></ol></nav>") + END;
        String ncx = "<ncx><navMap><navPoint><navLabel><text>Fallback</text></navLabel>"
                + "<content src='text.xhtml'/></navPoint></navMap></ncx>";
        File file = zip("deep.xhtml", deepNav, "toc.ncx", ncx, "ordinary.xhtml", ordinaryNav);
        try (ZipFile zip = new ZipFile(file)) {
            HashSet<String> readable = new HashSet<>(Arrays.asList("text.xhtml"));
            List<EpubNavigationParser.Entry> entries = EpubNavigationParser.contents(zip,
                    metadata("deep.xhtml", "toc.ncx"), readable);
            // DOM parsers have different stack budgets. Successful parsing and
            // graceful NCX fallback are both valid; an escaping Error is not.
            assertEquals(1, entries.size());
            assertTrue(entries.get(0).title.equals("Deep") || entries.get(0).title.equals("Fallback"));
            List<EpubNavigationParser.Entry> ordinary = EpubNavigationParser.contents(zip,
                    metadata("ordinary.xhtml", ""), readable);
            assertEquals(1, ordinary.size());
            assertEquals("Ordinary", ordinary.get(0).title);
        } finally { file.delete(); }
    }

    @Test public void deeplyNestedOptionalNoteCannotPreventTheNextOrdinaryNote() throws Exception {
        File file = zip("deep.xhtml", HTML + "<aside e:type='footnote' id='n'>"
                        + nestedMarkup("Deep note") + "</aside>" + END,
                "ordinary.xhtml", HTML + "<aside e:type='footnote' id='n'>Ordinary note</aside>" + END);
        try (ZipFile zip = new ZipFile(file)) {
            String deep = EpubNavigationParser.footnote(zip,
                    new EpubNavigationParser.Target("deep.xhtml", "n"), "text.xhtml");
            assertTrue(deep == null || deep.equals("Deep note"));
            assertEquals("Ordinary note", EpubNavigationParser.footnote(zip,
                    new EpubNavigationParser.Target("ordinary.xhtml", "n"), "text.xhtml"));
        } finally { file.delete(); }
    }

    private static String nestedMarkup(String content) {
        // A bounded sub-100-KiB fixture, not a throughput/heap stress benchmark.
        StringBuilder xml = new StringBuilder();
        for (int i = 0; i < 8192; i++) xml.append("<div>");
        xml.append(content);
        for (int i = 0; i < 8192; i++) xml.append("</div>");
        return xml.toString();
    }

    private static DocumentArchiveUtils.EpubPackageResources metadata(String nav, String ncx) {
        DocumentArchiveUtils.EpubPackageResources result = new DocumentArchiveUtils.EpubPackageResources();
        result.navigationPath = nav;
        result.ncxPath = ncx;
        return result;
    }

    private static File zip(String... pairs) throws Exception {
        File file = File.createTempFile("readwide-epub-navigation-", ".epub");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < pairs.length; i += 2) {
                out.putNextEntry(new ZipEntry(pairs[i]));
                out.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }
}
