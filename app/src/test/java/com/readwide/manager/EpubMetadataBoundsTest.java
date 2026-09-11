package com.readwide.manager;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class EpubMetadataBoundsTest {
    @Test public void parsesMetadataAtExactByteBoundary() throws Exception {
        byte[] xml = "<package><metadata/></package>".getBytes(StandardCharsets.UTF_8);
        assertEquals("package", DocumentArchiveUtils.parseEpubMetadata(
                new ByteArrayInputStream(xml), xml.length).getDocumentElement().getNodeName());
    }
    @Test public void oversizedMetadataFailsBeforeDomParsing() throws Exception {
        byte[] xml = "<container/>".getBytes(StandardCharsets.UTF_8);
        try {
            DocumentArchiveUtils.parseEpubMetadata(new ByteArrayInputStream(xml), xml.length - 1);
            fail("Expected bounded metadata read");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("size limit")); }
    }
    @Test public void negativeReadLimitIsRejected() throws Exception {
        try {
            DocumentArchiveUtils.readAllBytesWithLimit(new ByteArrayInputStream(new byte[0]), -1);
            fail("Expected invalid limit");
        } catch (IOException expected) { }
    }
}
