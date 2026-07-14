package com.readwide.manager;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decodes EPUB/HTML/XML text entries without assuming every book is UTF-8. */
final class DocumentTextDecoder {
    private static final Pattern XML_ENCODING = Pattern.compile(
            "(?is)<\\?xml[^>]*encoding\\s*=\\s*['\"]\\s*([^'\"\\s]+)");
    private static final Pattern HTML_CHARSET = Pattern.compile(
            "(?is)<meta[^>]+charset\\s*=\\s*['\"]?\\s*([^'\"\\s/>;]+)");
    private static final Pattern HTML_CONTENT_TYPE_CHARSET = Pattern.compile(
            "(?is)<meta[^>]+content\\s*=\\s*['\"][^'\"]*charset\\s*=\\s*([^'\";\\s]+)");

    private DocumentTextDecoder() {}

    static String decode(byte[] data) {
        if (data == null || data.length == 0) return "";

        if (startsWith(data, 0xEF, 0xBB, 0xBF)) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(data, 0x00, 0x00, 0xFE, 0xFF)) {
            return decodeWithNamedCharset(data, 4, "UTF-32BE", StandardCharsets.UTF_8);
        }
        if (startsWith(data, 0xFF, 0xFE, 0x00, 0x00)) {
            return decodeWithNamedCharset(data, 4, "UTF-32LE", StandardCharsets.UTF_8);
        }
        if (startsWith(data, 0xFE, 0xFF)) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        }
        if (startsWith(data, 0xFF, 0xFE)) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        }
        // XML/XHTML without a BOM can still be identified from the first '<'
        // byte pair, including documents that start with a DOCTYPE.
        int utf16 = sniffUtf16Markup(data);
        if (utf16 > 0) return new String(data, StandardCharsets.UTF_16BE);
        if (utf16 < 0) return new String(data, StandardCharsets.UTF_16LE);

        String asciiHead = new String(data, 0, Math.min(data.length, 4096),
                StandardCharsets.ISO_8859_1);
        String declared = declaredCharset(asciiHead);
        if (declared != null) {
            try {
                Charset charset = Charset.forName(declared);
                return new String(data, charset);
            } catch (Exception ignored) {
                // Invalid/unsupported declaration: EPUB defaults remain UTF-8.
            }
        }
        return new String(data, StandardCharsets.UTF_8);
    }


    private static int sniffUtf16Markup(byte[] data) {
        int limit = Math.min(data.length - 1, 32);
        for (int i = 0; i < limit; i++) {
            int a = data[i] & 0xFF;
            int b = data[i + 1] & 0xFF;
            if (a == 0 && b == 0x3C) return 1;
            if (a == 0x3C && b == 0) return -1;
            if (a > 0x20 && b > 0x20) break;
        }
        return 0;
    }

    private static String decodeWithNamedCharset(byte[] data,
                                                  int offset,
                                                  String charsetName,
                                                  Charset fallback) {
        try {
            return new String(data, offset, data.length - offset, Charset.forName(charsetName));
        } catch (Exception ignored) {
            return new String(data, offset, data.length - offset, fallback);
        }
    }
    private static String declaredCharset(String head) {
        Matcher matcher = XML_ENCODING.matcher(head);
        if (matcher.find()) return normalize(matcher.group(1));
        matcher = HTML_CHARSET.matcher(head);
        if (matcher.find()) return normalize(matcher.group(1));
        matcher = HTML_CONTENT_TYPE_CHARSET.matcher(head);
        if (matcher.find()) return normalize(matcher.group(1));
        return null;
    }

    private static String normalize(String name) {
        if (name == null) return null;
        String value = name.trim();
        return value.isEmpty() ? null : value.toUpperCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] data, int... bytes) {
        if (data.length < bytes.length) return false;
        for (int i = 0; i < bytes.length; i++) {
            if ((data[i] & 0xFF) != bytes[i]) return false;
        }
        return true;
    }
}
