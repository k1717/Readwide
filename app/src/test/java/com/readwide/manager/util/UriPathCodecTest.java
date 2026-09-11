package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UriPathCodecTest {
    @Test
    public void archiveDirectoryEncodingPreservesSeparatorsAndLiteralPercentNames() {
        String path = "OPS/part%20/夏+ #?/";
        String encoded = UriPathCodec.encodePath(path);
        assertEquals("OPS/part%2520/%E5%A4%8F+%20%23%3F/", encoded);
        assertEquals(path, UriPathCodec.decodePercentEscapes(encoded));
        assertEquals("/OPS//", UriPathCodec.encodePath("/OPS//"));
        assertEquals("", UriPathCodec.encodePath(null));
    }

    @Test
    public void oneDecodePreservesLiteralPercentSequencesAndDelimitersInZipNames() {
        assertEquals("OPS/chapter%20.xhtml", UriPathCodec.decodePercentEscapes("OPS/chapter%2520.xhtml"));
        assertEquals("OPS/%2e%2e/pic.png", UriPathCodec.decodePercentEscapes("OPS/%252e%252e/pic.png"));
        assertEquals("OPS/a#b?.xhtml", UriPathCodec.decodePercentEscapes("OPS/a%23b%3F.xhtml"));
    }
    @Test
    public void literalPlusIsPreserved() {
        assertEquals("Text/chapter+1.xhtml",
                UriPathCodec.decodePercentEscapes("Text/chapter+1.xhtml"));
    }

    @Test
    public void percentEscapesStillDecode() {
        assertEquals("Text/chapter +2.xhtml",
                UriPathCodec.decodePercentEscapes("Text/chapter%20%2B2.xhtml"));
    }

    @Test
    public void malformedEscapeFallsBackToOriginal() {
        assertEquals("bad%path+name", UriPathCodec.decodePercentEscapes("bad%path+name"));
    }

    @Test
    public void pathSegmentEncodingPreservesPlusButEscapesUnicodeAndDelimiters() {
        String encoded = UriPathCodec.encodePathSegment("夏目+漱石 #1.jpg");
        assertEquals("%E5%A4%8F%E7%9B%AE+%E6%BC%B1%E7%9F%B3%20%231.jpg", encoded);
        assertEquals("夏目+漱石 #1.jpg", UriPathCodec.decodePercentEscapes(encoded));
    }
}
