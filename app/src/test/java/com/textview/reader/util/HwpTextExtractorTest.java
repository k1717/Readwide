package com.textview.reader.util;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HwpTextExtractorTest {
    @Test
    public void extractsParaTextRecordFromHwpSection() throws Exception {
        byte[] text = "첫 문단\nSecond paragraph".getBytes(StandardCharsets.UTF_16LE);
        int header = 67 | (0 << 10) | (text.length << 20);
        byte[] section = new byte[4 + text.length];
        section[0] = (byte) (header & 0xFF);
        section[1] = (byte) ((header >>> 8) & 0xFF);
        section[2] = (byte) ((header >>> 16) & 0xFF);
        section[3] = (byte) ((header >>> 24) & 0xFF);
        System.arraycopy(text, 0, section, 4, text.length);

        String extracted = HwpTextExtractor.extractTextFromHwpSection(section);

        assertTrue(extracted.contains("첫 문단"));
        assertTrue(extracted.contains("Second paragraph"));
    }

    @Test
    public void extractsExtendedSizeParaTextRecordFromHwpSection() throws Exception {
        byte[] text = "extended-size paragraph".getBytes(StandardCharsets.UTF_16LE);
        int header = 67 | (0 << 10) | (0xFFF << 20);
        byte[] section = new byte[8 + text.length];
        section[0] = (byte) (header & 0xFF);
        section[1] = (byte) ((header >>> 8) & 0xFF);
        section[2] = (byte) ((header >>> 16) & 0xFF);
        section[3] = (byte) ((header >>> 24) & 0xFF);
        section[4] = (byte) (text.length & 0xFF);
        section[5] = (byte) ((text.length >>> 8) & 0xFF);
        section[6] = (byte) ((text.length >>> 16) & 0xFF);
        section[7] = (byte) ((text.length >>> 24) & 0xFF);
        System.arraycopy(text, 0, section, 8, text.length);

        String extracted = HwpTextExtractor.extractTextFromHwpSection(section);

        assertTrue(extracted.contains("extended-size paragraph"));
    }

    @Test
    public void extractsTextFromHwpxSectionXml() throws Exception {
        File tmp = File.createTempFile("readwide-hwpx", ".hwpx");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tmp))) {
                zip.putNextEntry(new ZipEntry("Contents/section0.xml"));
                String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<hp:sec xmlns:hp=\"http://www.hancom.co.kr/hwpml/2016/format\">"
                        + "<hp:p><hp:run><hp:t>가나다</hp:t></hp:run></hp:p>"
                        + "<hp:p><hp:run><hp:t>ABC</hp:t></hp:run></hp:p>"
                        + "</hp:sec>";
                zip.write(xml.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }

            String extracted = HwpTextExtractor.read(tmp);

            assertTrue(extracted.contains("가나다"));
            assertTrue(extracted.contains("ABC"));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    @Test
    public void extractsTabsAndLineBreaksFromHwpxXmlFallback() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<hp:sec xmlns:hp=\"http://www.hancom.co.kr/hwpml/2016/format\">"
                + "<hp:p><hp:run><hp:t>A</hp:t><hp:tab/><hp:t>B</hp:t>"
                + "<hp:lineBreak/><hp:t>C</hp:t></hp:run></hp:p>"
                + "</hp:sec>";

        String extracted = HwpTextExtractor.extractTextFromHwpxXml(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertTrue(extracted.contains("A\tB"));
        assertTrue(extracted.contains("B\nC"));
    }

    @Test
    public void sanitizeRemovesControlCharactersButKeepsTabsAndNewlines() {
        String sanitized = HwpTextExtractor.sanitizeExtractedTextForTest("A\u0000B\tC\n\n\n\nD");

        assertTrue(sanitized.contains("AB\tC"));
        assertTrue(sanitized.contains("\n\n\nD"));
        assertFalse(sanitized.contains("\u0000"));
    }
}
