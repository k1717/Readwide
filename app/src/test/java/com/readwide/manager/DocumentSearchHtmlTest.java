package com.readwide.manager;

import com.readwide.manager.util.SearchMatcher;
import com.readwide.manager.util.SearchOptions;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure markup/count regressions; these do not launch an Android WebView. */
public class DocumentSearchHtmlTest {
    @Test public void overlappingLiteralUsesTwoReachableDocumentOrdinals() {
        SearchMatcher matcher = SearchMatcher.compile("aa", SearchOptions.literal());
        String html = "<p>aaaa</p>";
        assertEquals(2, DocumentSearchController.countHtmlTextSegmentMatches(html, matcher));
        for (int ordinal = 1; ordinal <= 2; ordinal++) {
            String rendered = DocumentSearchController.highlightHtmlTextSegments(html, matcher, ordinal);
            assertTrue(rendered.contains("data-rw-doc-search-ordinal=\"" + ordinal
                    + "\" id=\"rw-document-search-current\""));
            assertFalse(rendered.contains("data-rw-doc-search-ordinal=\"3\""));
            assertEquals("aaaa", rendered.replaceAll("<[^>]*>", ""));
        }
    }

    @Test public void entityOverlapsUseSameCountAndMarkupModel() {
        SearchMatcher matcher = SearchMatcher.compile("&&", SearchOptions.literal());
        String html = "<p>&amp;&amp;&amp;&amp;</p>";
        assertEquals(2, DocumentSearchController.countHtmlTextSegmentMatches(html, matcher));
        String rendered = DocumentSearchController.highlightHtmlTextSegments(html, matcher, 2);
        assertTrue(rendered.contains("data-rw-doc-search-ordinal=\"2\" id=\"rw-document-search-current\""));
        assertEquals("&amp;&amp;&amp;&amp;", rendered.replaceAll("<[^>]*>", ""));
    }

    @Test public void attributesAndCommentsAreNeverSearchHits() {
        SearchMatcher matcher = SearchMatcher.compile("hit", SearchOptions.literal());
        String prefix = "<!-- > hit --><p title=\"a > hit\" data-x='> hit'>";
        String html = prefix + "hit</p>";
        assertEquals(1, DocumentSearchController.countHtmlTextSegmentMatches(html, matcher));
        assertTrue(DocumentSearchController.highlightHtmlTextSegments(html, matcher, 1).startsWith(prefix));
    }

    @Test public void unicodeBeforeRawTextClosingTagDoesNotShiftOffsets() {
        SearchMatcher matcher = SearchMatcher.compile("hit", SearchOptions.literal());
        String prefix = "<HEAD><title>İ hit</title></HEAD><style>hit</style><script>hit</script><p>";
        String html = prefix + "hit</p>";
        assertEquals(1, DocumentSearchController.countHtmlTextSegmentMatches(html, matcher));
        assertTrue(DocumentSearchController.highlightHtmlTextSegments(html, matcher, 1).startsWith(prefix));
    }
}
