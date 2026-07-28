package com.readwide.manager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EpubMediaOverlayJavascriptTest {

    @Test
    public void acceptsSingleSafePublisherClassIncludingLeadingHyphen() {
        assertEquals("-epub-media-overlay-active",
                EpubMediaOverlayJavascript.safeClassOrEmpty(" -epub-media-overlay-active "));
        assertEquals("overlay_active-2",
                EpubMediaOverlayJavascript.safeClassOrEmpty("overlay_active-2"));
    }

    @Test
    public void rejectsSelectorsAndMultipleClassTokens() {
        assertEquals("", EpubMediaOverlayJavascript.safeClassOrEmpty("active other"));
        assertEquals("", EpubMediaOverlayJavascript.safeClassOrEmpty(".active"));
        assertEquals("", EpubMediaOverlayJavascript.safeClassOrEmpty("x'];alert(1)//"));
        assertEquals("", EpubMediaOverlayJavascript.safeClassOrEmpty("-"));
    }

    @Test
    public void highlightEscapesFragmentAndUsesNonLayoutFallbackStyle() {
        String javascript = EpubMediaOverlayJavascript.highlight(
                "quote\"\\line\n\u2028end", "-epub-media-overlay-active");
        assertTrue(javascript.contains("quote\\\"\\\\line\\n\\u2028end"));
        assertTrue(javascript.contains(EpubMediaOverlayJavascript.READWIDE_ACTIVE_CLASS));
        assertTrue(javascript.contains("-epub-media-overlay-active"));
        assertTrue(javascript.contains("scrollIntoView"));
        assertTrue(javascript.contains("inline:'center'"));
        assertFalse(javascript.contains("<script"));
    }

    @Test
    public void invalidPublisherClassNeverAppearsInGeneratedScript() {
        String injected = "x'];alert(1)//";
        String javascript = EpubMediaOverlayJavascript.highlight("fragment", injected);
        assertFalse(javascript.contains(injected));
        assertTrue(javascript.contains(",p=\"\";"));
    }

    @Test
    public void clearOnlyTargetsElementsMarkedByReadwide() {
        String javascript = EpubMediaOverlayJavascript.clear("publisher-active");
        assertTrue(javascript.contains("data-readwide-media-overlay-active"));
        assertTrue(javascript.contains("removeAttribute"));
        assertTrue(javascript.contains("classList.remove"));
        assertFalse(javascript.contains("querySelectorAll('.publisher-active')"));
    }
}
