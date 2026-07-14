package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class SecureXmlTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void plainNamespacedXmlStillParses() throws Exception {
        String xml = "<root xmlns:x=\"urn:test\"><x:item>ok</x:item></root>";

        Document document = SecureXml.newDocumentBuilder(true).parse(stream(xml));

        assertNotNull(document);
        assertEquals("ok", document.getElementsByTagNameNS("urn:test", "item")
                .item(0).getTextContent());
    }

    @Test
    public void externalEntityCanNeverReadLocalFile() throws Exception {
        File secret = tempFolder.newFile("secret.txt");
        Files.write(secret.toPath(), "DO_NOT_EXPAND".getBytes(StandardCharsets.UTF_8));
        String xml = "<!DOCTYPE root [<!ENTITY ext SYSTEM \""
                + secret.toURI() + "\">]><root>&ext;</root>";

        try {
            Document document = SecureXml.newDocumentBuilder(true).parse(stream(xml));
            assertFalse(document.getDocumentElement().getTextContent().contains("DO_NOT_EXPAND"));
        } catch (org.xml.sax.SAXException expected) {
            // Preferred parser behavior: reject the DOCTYPE outright.
        }
    }

    private static ByteArrayInputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
