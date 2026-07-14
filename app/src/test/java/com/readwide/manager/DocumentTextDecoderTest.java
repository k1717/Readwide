package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class DocumentTextDecoderTest {
    @Test
    public void utf16LeBomIsDecoded() {
        byte[] body = "<html><body>한글</body></html>".getBytes(StandardCharsets.UTF_16LE);
        byte[] data = new byte[body.length + 2];
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xFE;
        System.arraycopy(body, 0, data, 2, body.length);

        assertTrue(DocumentTextDecoder.decode(data).contains("한글"));
    }

    @Test
    public void utf16DoctypeWithoutBomIsDetected() {
        String source = "<!DOCTYPE html><html><body>page</body></html>";
        assertEquals(source, DocumentTextDecoder.decode(source.getBytes(StandardCharsets.UTF_16BE)));
    }

    @Test
    public void htmlDeclaredWindows1252IsDecoded() {
        String source = "<meta charset=windows-1252><p>caf\u00e9</p>";
        byte[] data = source.getBytes(Charset.forName("windows-1252"));

        assertTrue(DocumentTextDecoder.decode(data).contains("caf\u00e9"));
    }
}
