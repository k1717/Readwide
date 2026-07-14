package com.readwide.manager;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android-free parser and rewriter for fixed-layout EPUB viewport metadata. */
final class EpubViewportParser {
    private static final Pattern META_TAG = Pattern.compile("(?is)<meta\\b[^>]*>");
    private static final Pattern ATTR = Pattern.compile(
            "(?is)\\b([a-z_:][-a-z0-9_:.]*)\\s*=\\s*(?:(['\"])(.*?)\\2|([^\\s\"'=<>`]+))");
    private static final Pattern WIDTH = Pattern.compile(
            "(?i)(?:^|[,;\\s])width\\s*=\\s*([0-9]{2,5})");
    private static final Pattern HEIGHT = Pattern.compile(
            "(?i)(?:^|[,;\\s])height\\s*=\\s*([0-9]{2,5})");

    private EpubViewportParser() {}

    static Dimensions parse(String html) {
        if (html == null || html.isEmpty()) return Dimensions.EMPTY;
        Matcher tags = META_TAG.matcher(html);
        while (tags.find()) {
            ViewportMeta meta = parseViewportMeta(tags.group());
            if (meta == null) continue;
            int width = parseDimension(WIDTH, meta.content);
            int height = parseDimension(HEIGHT, meta.content);
            if (width > 0 && height > 0) return new Dimensions(width, height);
        }
        return Dimensions.EMPTY;
    }

    /**
     * Replaces the first actual viewport meta tag regardless of attribute order
     * or whether HTML-style attributes are quoted. Returns the original string
     * unchanged when no viewport meta tag is present.
     */
    static String replaceViewportMeta(String html, String replacement) {
        if (html == null || html.isEmpty() || replacement == null) return html;
        Matcher tags = META_TAG.matcher(html);
        while (tags.find()) {
            if (parseViewportMeta(tags.group()) == null) continue;
            return html.substring(0, tags.start()) + replacement + html.substring(tags.end());
        }
        return html;
    }

    private static ViewportMeta parseViewportMeta(String tag) {
        String name = null;
        String content = null;
        Matcher attrs = ATTR.matcher(tag);
        while (attrs.find()) {
            String key = attrs.group(1).toLowerCase(Locale.ROOT);
            String value = attrs.group(3) != null ? attrs.group(3) : attrs.group(4);
            if ("name".equals(key)) name = value;
            else if ("content".equals(key)) content = value;
        }
        if (name == null || !"viewport".equalsIgnoreCase(name.trim()) || content == null) {
            return null;
        }
        return new ViewportMeta(content);
    }

    private static int parseDimension(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) return 0;
        try {
            int value = Integer.parseInt(matcher.group(1));
            return value >= 100 ? value : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static final class ViewportMeta {
        final String content;

        ViewportMeta(String content) {
            this.content = content;
        }
    }

    static final class Dimensions {
        static final Dimensions EMPTY = new Dimensions(0, 0);
        final int width;
        final int height;

        Dimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }

        boolean isUsable() {
            return width >= 100 && height >= 100;
        }
    }
}
