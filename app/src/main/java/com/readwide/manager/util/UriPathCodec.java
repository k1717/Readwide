package com.readwide.manager.util;

import java.net.URLDecoder;

/** URI-path percent decoding without HTML-form '+' to space conversion. */
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
}
