package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class ArchiveFilenameDecoderTest {
    @Test
    public void decodeLegacyName_prefersUtf8WhenNameIsValidUtf8() {
        byte[] raw = "한글/page001.txt".getBytes(StandardCharsets.UTF_8);

        assertEquals("한글/page001.txt", ArchiveFilenameDecoder.decodeLegacyName(raw));
    }

    @Test
    public void decodeLegacyName_detectsKoreanCp949ArchiveName() {
        assertLegacy("한글/page001.txt", "MS949");
    }

    @Test
    public void decodeLegacyName_detectsJapaneseShiftJisArchiveName() {
        assertLegacy("日本語/page001.txt", "Shift_JIS");
    }

    @Test
    public void decodeLegacyName_detectsSimplifiedChineseGb18030ArchiveName() {
        assertLegacy("简体中文/page001.txt", "GB18030");
    }

    @Test
    public void decodeLegacyName_detectsTraditionalChineseBig5ArchiveName() {
        assertLegacy("繁體中文/page001.txt", "Big5");
    }

    @Test
    public void decodeEggName_honorsMajorLegacyCodePageHints() {
        assertHinted("Русский/page001.txt", "windows-1251", 1251);
        assertHinted("Ελληνικά/page001.txt", "windows-1253", 1253);
        assertHinted("Türkçe_İstanbul/page001.txt", "windows-1254", 1254);
        assertHinted("עברית/page001.txt", "windows-1255", 1255);
        assertHinted("العربية/page001.txt", "windows-1256", 1256);
        assertHinted("ภาษาไทย/page001.txt", "windows-874", 874);
        assertHinted("Ươ_ĂĐ/page001.txt", "windows-1258", 1258);
        assertHinted("Zażółć_gęślą_jaźń/page001.txt", "windows-1250", 1250);
        assertHinted("Latviešu_āēīū/page001.txt", "windows-1257", 1257);
    }

    @Test
    public void decodeZipName_honorsUtf8Flag() {
        byte[] raw = "日本語/page001.txt".getBytes(StandardCharsets.UTF_8);

        assertEquals("日本語/page001.txt", ArchiveFilenameDecoder.decodeZipName(raw, true));
    }

    @Test
    public void decodeEggName_honorsCp949LocaleCodePage() {
        byte[] raw = "한국어/page001.txt".getBytes(Charset.forName("MS949"));

        assertEquals("한국어/page001.txt", ArchiveFilenameDecoder.decodeEggName(raw, 0, raw.length, 949));
    }

    @Test
    public void decodeEggName_honorsRussianLocaleCodePage() {
        byte[] raw = "Русский/page001.txt".getBytes(Charset.forName("windows-1251"));

        assertEquals("Русский/page001.txt", ArchiveFilenameDecoder.decodeEggName(raw, 0, raw.length, 1251));
    }

    private static void assertLegacy(String expected, String charsetName) {
        byte[] raw = expected.getBytes(Charset.forName(charsetName));
        assertEquals(expected, ArchiveFilenameDecoder.decodeLegacyName(raw));
    }

    private static void assertHinted(String expected, String charsetName, int codePage) {
        byte[] raw = expected.getBytes(Charset.forName(charsetName));
        assertEquals(expected, ArchiveFilenameDecoder.decodeEggName(raw, 0, raw.length, codePage));
    }
}
