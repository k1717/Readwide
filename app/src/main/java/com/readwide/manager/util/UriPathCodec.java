package com.readwide.manager.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** URI-path encoding/decoding without HTML-form '+' to space conversion. */
public final class UriPathCodec {
    private UriPathCodec() {}

    public static String decodePercentEscapes(String value) {
        if (value == null) return "";
        try {
            // URLDecoder follows application/x-www-form-urlencoded semantics.
            // Escaping literal '+' first gives URI-path behavior while retaining
            // normal percent-decoding for %20, %2B, and non-ASCII UTF-8 bytes.
            return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    /** Encodes a decoded archive path, preserving directory separators, including a trailing slash. */
    public static String encodePath(String value) {
        if (value == null || value.isEmpty()) return "";
        String[] segments = value.split("/", -1);
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) out.append('/');
            out.append(encodePathSegment(segments[i]));
        }
        return out.toString();
    }

    /** Percent-encodes one URI path segment without form-style space/plus rules. */
    public static String encodePathSegment(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 16);
        final char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte raw : bytes) {
            int b = raw & 0xff;
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                    || (b >= '0' && b <= '9') || b == '-' || b == '.'
                    || b == '_' || b == '~' || b == '+') {
                out.append((char) b);
            } else {
                out.append('%').append(hex[b >>> 4]).append(hex[b & 0x0f]);
            }
        }
        return out.toString();
    }
}
