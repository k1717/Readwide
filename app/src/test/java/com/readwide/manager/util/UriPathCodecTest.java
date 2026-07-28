package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UriPathCodecTest {
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
