package com.readwide.manager;

import org.junit.Test;
import static org.junit.Assert.*;

public class MarkdownDocumentRendererTest {
    @Test public void inlineCodeIsLiteralAndEmphasisCanSurroundIt() {
        String html = MarkdownDocumentRenderer.render(
                "**before `**literal** [x](https://example.org)` after** and `` a ` b ``", "test");
        assertTrue(html.contains("<strong>before <code>**literal** [x](https://example.org)</code> after</strong>"));
        assertTrue(html.contains("<code>a ` b</code>"));
        assertFalse(html.contains("<a href="));
    }

    @Test public void linksAndImageAttributesAreEscapedOnceAndNeverEmphasized() {
        String html = MarkdownDocumentRenderer.render(
                "[**A & B**](https://example.org/a__b__?x=1&y=2 \"title\") "
                        + "![a\"b & c](https://example.org/p__q__.png)", "test");
        assertTrue(html.contains("href=\"https://example.org/a__b__?x=1&amp;y=2\""));
        assertTrue(html.contains("<strong>A &amp; B</strong></a>"));
        assertTrue(html.contains("src=\"https://example.org/p__q__.png\" alt=\"a&quot;b &amp; c\""));
        assertFalse(html.contains("&amp;amp;"));
    }

    @Test public void inlineCodeInLinksAndTokenLikeInputRemainSafe() {
        String html = MarkdownDocumentRenderer.render(
                "literal \u0001RW0\u0002 **[a `b`](https://example.org)** "
                        + "[<script>](javascript:alert) <img src=x onerror=y>", "test");
        assertTrue(html.contains("\u0001RW0\u0002"));
        assertTrue(html.contains("<strong><a href=\"https://example.org\" rel=\"noreferrer\">a <code>b</code></a></strong>"));
        assertFalse(html.contains("href=\"javascript:"));
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;img src=x onerror=y&gt;"));
    }

    @Test public void fencesKeepShorterRunsBlankLinesAndSourceAnchor() {
        String markdown = "intro\n\n````java\n\n```\n```not-a-close\nx\n````\nafter";
        String html = MarkdownDocumentRenderer.render(markdown, "test");
        assertTrue(html.contains("<pre id=\"rw-md-src-7\" data-rw-src-line=\"3\" data-rw-src-offset=\"7\"><code>\n```\n```not-a-close\nx</code></pre>"));
        assertTrue(html.contains(">after</p>"));
    }

    @Test public void diagramBlocksHaveAnchorsAndMixedRulesRemainText() {
        String html = MarkdownDocumentRenderer.render("-*_\n\n~~~mermaid\na-->b\n~~~", "test");
        assertFalse(html.contains("<hr"));
        assertTrue(html.contains("<figure class=\"md-diagram\" id=\"rw-md-src-5\""));
    }
}
