package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class EpubBindingRewriterTest {
    private static final String ORIGIN = "https://binding-7.readwide.invalid";

    @Test
    public void rewritesBoundObjectToOpaqueSandboxedHandlerAndPreservesFallback() {
        String html = "<html><body><object id='gallery' data='moon-phases.xml' "
                + "type='application/x-epub-figure-gallery'>"
                + "<img src='../images/fallback.jpg'/></object></body></html>";
        Map<String, String> handlers = handlers(
                "application/x-epub-figure-gallery",
                "EPUB/content/figure-gallery/figure-gallery-impl.xhtml");
        Set<String> entries = entries(
                "EPUB/content/text.xhtml",
                "EPUB/content/moon-phases.xml",
                "EPUB/content/figure-gallery/figure-gallery-impl.xhtml");

        EpubBindingRewriter.RewriteResult result = EpubBindingRewriter.rewriteBoundObjects(
                html, "EPUB/content/text.xhtml", handlers, ORIGIN, entries);

        assertEquals(1, result.replacementCount);
        assertEquals(entries("EPUB/content/moon-phases.xml"), result.payloadPaths);
        assertTrue(result.requiresJavaScript());
        assertTrue(result.html.contains(
                "src=\"https://binding-7.readwide.invalid/epub/EPUB/content/figure-gallery/"
                        + "figure-gallery-impl.xhtml?src=/epub/EPUB/content/moon-phases.xml"
                        + "&amp;type=application%2Fx-epub-figure-gallery\""));
        assertTrue(result.html.contains("sandbox=\"allow-scripts\""));
        assertFalse(result.html.contains("allow-same-origin"));
        assertFalse(result.html.contains("allow-top-navigation"));
        assertTrue(result.html.contains("<summary>Static fallback</summary>"));
        assertTrue(result.html.contains("<img src='../images/fallback.jpg'/>"));
        assertFalse(result.html.contains("<object id='gallery'"));
    }

    @Test
    public void includesDirectParamChildrenButDoesNotLetThemOverrideSrcOrType() {
        String html = "<object type=\"application/x-demo\" data=\"data.xml\">"
                + "<param name=\"mode\" value=\"a &amp; b\"/>"
                + "<param name=\"src\" value=\"https://evil.invalid\"/>"
                + "<div><param name=\"nested\" value=\"ignored\"/></div>"
                + "fallback</object>";
        EpubBindingRewriter.RewriteResult result = EpubBindingRewriter.rewriteBoundObjects(
                html,
                "OPS/chapter.xhtml",
                handlers("application/x-demo", "OPS/handlers/demo.xhtml"),
                ORIGIN);

        assertEquals(1, result.replacementCount);
        assertTrue(result.html.contains("&amp;mode=a%20%26%20b"));
        String frame = result.html.substring(
                result.html.indexOf("<iframe"),
                result.html.indexOf("</iframe>") + "</iframe>".length());
        assertFalse(frame.contains("evil.invalid"));
        assertFalse(frame.contains("&amp;src="));
        assertFalse(result.html.contains("&amp;nested="));
    }

    @Test
    public void doesNotRewriteUnknownMissingOrEscapingResources() {
        Map<String, String> handlers = handlers("application/x-demo", "OPS/handler.xhtml");
        Set<String> entries = entries("OPS/page.xhtml", "OPS/handler.xhtml", "OPS/data.xml");
        String html = "<object type='application/x-other' data='data.xml'>other</object>"
                + "<object type='application/x-demo' data='missing.xml'>missing</object>"
                + "<object type='application/x-demo' data='../../outside.xml'>escape</object>";

        EpubBindingRewriter.RewriteResult result = EpubBindingRewriter.rewriteBoundObjects(
                html, "OPS/page.xhtml", handlers, ORIGIN, entries);

        assertEquals(0, result.replacementCount);
        assertSame(html, result.html);
        assertFalse(result.requiresJavaScript());
    }

    @Test
    public void handlesAttributeOrderQuotesGreaterThanAndMultipleObjects() {
        String html = "<object data='one.xml' title='1 > 0' TYPE='application/x-demo'/>"
                + "<script>var fake=\"<object data='bad.xml' type='application/x-demo'>\";</script>"
                + "<OBJECT TYPE=application/x-demo DATA=two.xml>two</OBJECT>";
        Set<String> entries = entries(
                "OPS/ch.xhtml", "OPS/handler.xhtml", "OPS/one.xml", "OPS/two.xml");

        EpubBindingRewriter.RewriteResult result = EpubBindingRewriter.rewriteBoundObjects(
                html,
                "OPS/ch.xhtml",
                handlers("application/x-demo", "OPS/handler.xhtml"),
                ORIGIN,
                entries);

        assertEquals(2, result.replacementCount);
        assertTrue(result.html.contains("?src=/epub/OPS/one.xml"));
        assertTrue(result.html.contains("?src=/epub/OPS/two.xml"));
        assertTrue(result.html.contains("var fake=\"<object data='bad.xml'"));
    }

    @Test
    public void preservesLiteralPlusAndEncodesSpacesAndUnicodeInSyntheticPath() {
        String html = "<object type='application/x-demo' data='../data/夏目+漱石 1.xml'/>";
        EpubBindingRewriter.RewriteResult result = EpubBindingRewriter.rewriteBoundObjects(
                html,
                "OPS/text/ch.xhtml",
                handlers("application/x-demo", "OPS/handler.xhtml"),
                ORIGIN);

        assertTrue(result.html.contains(
                "?src=/epub/OPS/data/%E5%A4%8F%E7%9B%AE+%E6%BC%B1%E7%9F%B3%201.xml"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonHttpsSyntheticOrigin() {
        EpubBindingRewriter.rewriteBoundObjects(
                "<object type='application/x-demo' data='data.xml'/>",
                "OPS/ch.xhtml",
                handlers("application/x-demo", "OPS/handler.xhtml"),
                "http://readwide.local");
    }

    @Test
    public void rewritesGalleryXmlUrisAgainstPayloadInsteadOfHandlerDirectory() {
        String xml = "<figureGalleryData><figure>"
                + "<img src='../images/moon-images/1.new moon.jpg'/>"
                + "<a href=\"notes/info.xhtml#phase-1\">Notes</a>"
                + "<img src='https://example.invalid/remote.jpg'/>"
                + "<a href='#local'>Local</a>"
                + "</figure></figureGalleryData>";
        Set<String> entries = entries(
                "EPUB/content/moon-phases.xml",
                "EPUB/images/moon-images/1.new moon.jpg",
                "EPUB/content/notes/info.xhtml");

        String rewritten = EpubBindingRewriter.rewriteXmlResourceUris(
                xml, "EPUB/content/moon-phases.xml", ORIGIN, entries);

        assertTrue(rewritten.contains(
                "src='https://binding-7.readwide.invalid/epub/EPUB/images/moon-images/1.new%20moon.jpg'"));
        assertTrue(rewritten.contains(
                "href=\"https://binding-7.readwide.invalid/epub/EPUB/content/notes/info.xhtml#phase-1\""));
        assertTrue(rewritten.contains("src='https://example.invalid/remote.jpg'"));
        assertTrue(rewritten.contains("href='#local'"));
    }

    @Test
    public void xmlRewritePreservesAmpersandEscapingAndSkipsMissingEntry() {
        String xml = "<root><img src='images/a&amp;b.png'/><img src='images/missing.png'/></root>";
        String rewritten = EpubBindingRewriter.rewriteXmlResourceUris(
                xml,
                "OPS/data.xml",
                ORIGIN,
                entries("OPS/images/a&b.png"));

        assertTrue(rewritten.contains(
                "src='https://binding-7.readwide.invalid/epub/OPS/images/a%26b.png'"));
        assertTrue(rewritten.contains("src='images/missing.png'"));
    }

    @Test
    public void malformedUnclosedObjectIsLeftUntouched() {
        String html = "<p>before</p><object type='application/x-demo' data='data.xml'><p>fallback";
        EpubBindingRewriter.RewriteResult result = EpubBindingRewriter.rewriteBoundObjects(
                html,
                "OPS/ch.xhtml",
                handlers("application/x-demo", "OPS/handler.xhtml"),
                ORIGIN);
        assertEquals(0, result.replacementCount);
        assertSame(html, result.html);
    }

    @Test
    public void sanitizesNonScriptedParentBeforeBindingJavascriptIsEnabled() {
        String html = "<html><body onload='run()'><script>run()</script>"
                + "<a href='java&#x73;cript:run()' onclick='run()'>x</a>"
                + "<iframe srcdoc='<script>run()</script>'></iframe>"
                + "<object type='application/x-demo' data='data.xml'/></body></html>";

        String sanitized = EpubBindingRewriter.sanitizeNonScriptedParent(html);

        assertFalse(sanitized.contains("<script>run()</script>"));
        assertFalse(sanitized.contains("onload="));
        assertFalse(sanitized.contains("onclick="));
        assertFalse(sanitized.contains("java&#x73;cript:"));
        assertFalse(sanitized.contains("srcdoc="));
        assertTrue(sanitized.contains("<object type='application/x-demo'"));
    }

    @Test
    public void removesXhtmlCdataWrappersForHtmlParser() {
        String source = "<script><![CDATA[var ok = true;]]></script>"
                + "<style><![CDATA[body{color:red}]]></style>";
        String normalized = EpubBindingRewriter.normalizeXhtmlCdataForHtmlParser(source);
        assertEquals("<script>var ok = true;</script>"
                + "<style>body{color:red}</style>", normalized);
    }

    private static Map<String, String> handlers(String mediaType, String handler) {
        Map<String, String> result = new HashMap<>();
        result.put(mediaType, handler);
        return result;
    }

    private static Set<String> entries(String... paths) {
        return new HashSet<>(Arrays.asList(paths));
    }
}
