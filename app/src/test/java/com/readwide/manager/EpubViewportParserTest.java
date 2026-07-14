package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EpubViewportParserTest {
    @Test
    public void parsesNameBeforeContent() {
        EpubViewportParser.Dimensions d = EpubViewportParser.parse(
                "<meta name='viewport' content='width=1200,height=1800'>");
        assertEquals(1200, d.width);
        assertEquals(1800, d.height);
    }

    @Test
    public void parsesContentBeforeName() {
        EpubViewportParser.Dimensions d = EpubViewportParser.parse(
                "<meta content=\"height=1920; width=1080\" name=\"viewport\">");
        assertEquals(1080, d.width);
        assertEquals(1920, d.height);
    }

    @Test
    public void ignoresUnrelatedMeta() {
        EpubViewportParser.Dimensions d = EpubViewportParser.parse(
                "<meta name='description' content='width=1200,height=1800'>");
        assertFalse(d.isUsable());
    }

    @Test
    public void parsesUnquotedHtmlAttributes() {
        EpubViewportParser.Dimensions d = EpubViewportParser.parse(
                "<meta content='width=1024,height=768' name=viewport>");
        assertEquals(1024, d.width);
        assertEquals(768, d.height);
    }

    @Test
    public void replacesViewportWhenContentComesBeforeName() {
        String html = "<head><meta content='height=1800,width=1200' name='viewport'>"
                + "<meta name='description' content='keep'></head>";
        String replacement = "<meta name=\"viewport\" content=\"width=device-width\">";
        String replaced = EpubViewportParser.replaceViewportMeta(html, replacement);

        assertTrue(replaced.contains(replacement));
        assertTrue(replaced.contains("name='description'"));
        assertFalse(replaced.contains("height=1800"));
    }

}
