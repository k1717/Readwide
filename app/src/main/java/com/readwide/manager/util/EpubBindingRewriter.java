package com.readwide.manager.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java compatibility helpers for legacy EPUB 3.0 OPF bindings.
 *
 * <p>The package parser is responsible for validating that each supplied handler is an
 * XHTML manifest item carrying the {@code scripted} property. This class deliberately
 * knows nothing about Android, WebView, ZIP I/O, or the OPF DOM.</p>
 */
public final class EpubBindingRewriter {
    private static final String EPUB_PREFIX = "/epub/";

    private EpubBindingRewriter() {}

    public static final class RewriteResult {
        public final String html;
        public final int replacementCount;
        public final Set<String> payloadPaths;

        RewriteResult(String html, int replacementCount, Set<String> payloadPaths) {
            this.html = html != null ? html : "";
            this.replacementCount = Math.max(0, replacementCount);
            this.payloadPaths = payloadPaths == null || payloadPaths.isEmpty()
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(new HashSet<>(payloadPaths));
        }

        public boolean requiresJavaScript() {
            return replacementCount > 0;
        }
    }

    /**
     * Replaces bound top-level {@code object} elements with isolated handler iframes.
     *
     * <p>The generated frame grants only {@code allow-scripts}; it intentionally omits
     * {@code allow-same-origin}, parent navigation, popups, forms, and downloads. The handler
     * therefore runs in an opaque container-constrained origin even when
     * {@code syntheticOrigin} is also used for the parent spine document.</p>
     *
     * @param xhtml spine XHTML/HTML source
     * @param spinePath normalized or relative ZIP path of the spine document
     * @param handlerPathsByMediaType foreign media type to normalized handler ZIP path
     * @param syntheticOrigin local HTTPS origin intercepted by the reading system
     * @param archiveEntryPaths optional archive allow-list; empty/null disables existence checks
     */
    public static RewriteResult rewriteBoundObjects(
            String xhtml,
            String spinePath,
            Map<String, String> handlerPathsByMediaType,
            String syntheticOrigin,
            Set<String> archiveEntryPaths) {
        if (xhtml == null || xhtml.isEmpty()
                || handlerPathsByMediaType == null || handlerPathsByMediaType.isEmpty()) {
            return new RewriteResult(xhtml, 0, Collections.emptySet());
        }

        String origin = normalizeSyntheticOrigin(syntheticOrigin);
        Map<String, String> handlers = normalizeHandlers(handlerPathsByMediaType);
        if (handlers.isEmpty()) return new RewriteResult(xhtml, 0, Collections.emptySet());
        Set<String> entries = normalizeEntries(archiveEntryPaths);
        String normalizedSpine = normalizeArchivePath("", spinePath);
        if (normalizedSpine == null || normalizedSpine.isEmpty()) {
            return new RewriteResult(xhtml, 0, Collections.emptySet());
        }

        StringBuilder out = new StringBuilder(xhtml.length() + 256);
        int emittedThrough = 0;
        int searchFrom = 0;
        int replacements = 0;
        Set<String> payloadPaths = new HashSet<>();

        while (true) {
            Tag open = findNextObjectOpen(xhtml, searchFrom);
            if (open == null) break;
            int endExclusive;
            String fallback = "";
            if (open.selfClosing) {
                endExclusive = open.end + 1;
            } else {
                Tag close = findMatchingObjectClose(xhtml, open.end + 1);
                if (close == null) {
                    searchFrom = open.end + 1;
                    continue;
                }
                fallback = xhtml.substring(open.end + 1, close.start);
                endExclusive = close.end + 1;
            }

            Map<String, Attribute> attrs = attributesByName(xhtml, open);
            String type = attributeValue(attrs, "type").trim().toLowerCase(Locale.ROOT);
            String data = attributeValue(attrs, "data").trim();
            String handler = handlers.get(type);
            ResolvedReference dataRef = resolveArchiveReference(normalizedSpine, data);
            if (handler == null || dataRef == null
                    || !entryAllowed(entries, handler)
                    || !entryAllowed(entries, dataRef.archivePath)) {
                searchFrom = open.end + 1;
                continue;
            }

            String handlerUrl = origin + EPUB_PREFIX + encodeArchivePath(handler);
            String sourcePath = EPUB_PREFIX + encodeArchivePath(dataRef.archivePath);
            StringBuilder query = new StringBuilder();
            query.append("?src=").append(sourcePath);
            query.append("&type=").append(encodeQueryComponent(type));
            for (NameValue param : directObjectParams(fallback)) {
                String name = param.name.trim();
                if (name.isEmpty() || "src".equalsIgnoreCase(name)
                        || "type".equalsIgnoreCase(name)) {
                    continue;
                }
                query.append('&').append(encodeQueryComponent(name));
                query.append('=').append(encodeQueryComponent(param.value));
            }

            String replacement = buildBindingMarkup(
                    handlerUrl + query,
                    type,
                    fallback);
            out.append(xhtml, emittedThrough, open.start);
            out.append(replacement);
            emittedThrough = endExclusive;
            searchFrom = endExclusive;
            replacements++;
            payloadPaths.add(dataRef.archivePath);
        }

        if (replacements == 0) {
            return new RewriteResult(xhtml, 0, Collections.emptySet());
        }
        out.append(xhtml, emittedThrough, xhtml.length());
        return new RewriteResult(out.toString(), replacements, payloadPaths);
    }

    /**
     * Removes publisher active-content entry points before a non-scripted parent
     * document is hosted in a JavaScript-enabled WebView for a sandboxed binding
     * iframe. The generated binding iframe is added only after this pass.
     */
    public static String sanitizeNonScriptedParent(String xhtml) {
        if (xhtml == null || xhtml.isEmpty()) return xhtml;
        List<TextReplacement> replacements = new ArrayList<>();
        int from = 0;
        while (true) {
            Tag tag = nextTag(xhtml, from);
            if (tag == null) break;
            from = tag.end + 1;
            if (tag.declaration || tag.closing) continue;
            if ("script".equals(tag.name)) {
                int end = tag.end + 1;
                if (!tag.selfClosing) {
                    Tag close = findRawTextClose(xhtml, end, "script");
                    if (close != null) {
                        end = close.end + 1;
                        from = end;
                    }
                }
                replacements.add(new TextReplacement(tag.start, end, ""));
                continue;
            }
            for (Attribute attr : parseAttributes(xhtml, tag)) {
                String name = attr.normalizedName;
                boolean eventHandler = name.length() > 2 && name.startsWith("on");
                boolean activeUrlAttribute = "href".equals(name)
                        || "xlink:href".equals(name)
                        || "src".equals(name)
                        || "action".equals(name)
                        || "formaction".equals(name)
                        || "srcdoc".equals(name);
                String value = decodeXmlEntitiesForPolicy(attr.value)
                        .replaceAll("[\\u0000-\\u0020]+", "")
                        .toLowerCase(Locale.ROOT);
                boolean activeUrl = activeUrlAttribute
                        && (value.startsWith("javascript:")
                        || value.startsWith("vbscript:")
                        || value.startsWith("data:text/html"));
                if (eventHandler || activeUrl || "srcdoc".equals(name)) {
                    replacements.add(new TextReplacement(
                            attr.attributeStart, attr.attributeEnd, ""));
                }
            }
        }
        if (replacements.isEmpty()) return xhtml;
        StringBuilder out = new StringBuilder(xhtml);
        for (int i = replacements.size() - 1; i >= 0; i--) {
            TextReplacement replacement = replacements.get(i);
            out.replace(replacement.start, replacement.end, replacement.value);
        }
        return out.toString();
    }

    /** Removes XHTML CDATA wrappers which become JavaScript/CSS tokens under text/html. */
    public static String normalizeXhtmlCdataForHtmlParser(String xhtml) {
        if (xhtml == null || xhtml.isEmpty()) return xhtml;
        return xhtml
                .replaceAll("(?is)(<script\\b[^>]*>)\\s*<!\\[CDATA\\[", "$1")
                .replaceAll("(?is)\\]\\]>\\s*(</script\\s*>)", "$1")
                .replaceAll("(?is)(<style\\b[^>]*>)\\s*<!\\[CDATA\\[", "$1")
                .replaceAll("(?is)\\]\\]>\\s*(</style\\s*>)", "$1");
    }

    public static RewriteResult rewriteBoundObjects(
            String xhtml,
            String spinePath,
            Map<String, String> handlerPathsByMediaType,
            String syntheticOrigin) {
        return rewriteBoundObjects(
                xhtml,
                spinePath,
                handlerPathsByMediaType,
                syntheticOrigin,
                Collections.emptySet());
    }

    /**
     * Rewrites local {@code src} and {@code href} values in an XML binding payload to full
     * synthetic archive URLs. This is needed when a handler copies nodes from responseXML into
     * its own document: relative URI attributes must continue to resolve against the payload,
     * not the handler directory.
     */
    public static String rewriteXmlResourceUris(
            String xml,
            String xmlPath,
            String syntheticOrigin,
            Set<String> archiveEntryPaths) {
        if (xml == null || xml.isEmpty()) return xml;
        String origin = normalizeSyntheticOrigin(syntheticOrigin);
        String normalizedXmlPath = normalizeArchivePath("", xmlPath);
        if (normalizedXmlPath == null || normalizedXmlPath.isEmpty()) return xml;
        Set<String> entries = normalizeEntries(archiveEntryPaths);

        List<TextReplacement> replacements = new ArrayList<>();
        int from = 0;
        while (true) {
            Tag tag = nextTag(xml, from);
            if (tag == null) break;
            from = tag.end + 1;
            if (tag.closing || tag.declaration) continue;
            for (Attribute attr : parseAttributes(xml, tag)) {
                if (!"src".equals(attr.normalizedName) && !"href".equals(attr.normalizedName)) {
                    continue;
                }
                String decodedValue = decodeXmlEntities(attr.value);
                ResolvedReference ref = resolveArchiveReference(normalizedXmlPath, decodedValue);
                if (ref == null || !entryAllowed(entries, ref.archivePath)) continue;
                String absolute = origin + EPUB_PREFIX + encodeArchivePath(ref.archivePath)
                        + ref.suffix;
                replacements.add(new TextReplacement(
                        attr.valueStart,
                        attr.valueEnd,
                        escapeAttributeValue(absolute, attr.quote)));
            }
        }
        if (replacements.isEmpty()) return xml;
        StringBuilder out = new StringBuilder(xml);
        for (int i = replacements.size() - 1; i >= 0; i--) {
            TextReplacement replacement = replacements.get(i);
            out.replace(replacement.start, replacement.end, replacement.value);
        }
        return out.toString();
    }

    public static String rewriteXmlResourceUris(
            String xml,
            String xmlPath,
            String syntheticOrigin) {
        return rewriteXmlResourceUris(
                xml,
                xmlPath,
                syntheticOrigin,
                Collections.emptySet());
    }

    private static String buildBindingMarkup(String frameUrl, String type, String fallback) {
        StringBuilder html = new StringBuilder(frameUrl.length() + fallback.length() + 320);
        html.append("<div class=\"rw-epub-binding\" data-rw-binding-type=\"")
                .append(escapeHtmlAttribute(type))
                .append("\">");
        html.append("<iframe class=\"rw-epub-binding-frame\" src=\"")
                .append(escapeHtmlAttribute(frameUrl))
                .append("\" sandbox=\"allow-scripts\" referrerpolicy=\"no-referrer\"")
                .append(" loading=\"eager\" title=\"Embedded EPUB content\"")
                .append(" style=\"display:block;width:100%;height:72vh;min-height:280px;")
                .append("max-height:720px;border:0\"></iframe>");
        if (fallback != null && !fallback.trim().isEmpty()) {
            html.append("<details class=\"rw-epub-binding-fallback\">")
                    .append("<summary>Static fallback</summary>")
                    .append(fallback)
                    .append("</details>");
        }
        html.append("</div>");
        return html.toString();
    }

    private static Map<String, String> normalizeHandlers(Map<String, String> handlers) {
        Map<String, String> normalized = new HashMap<>();
        for (Map.Entry<String, String> entry : handlers.entrySet()) {
            String mediaType = entry.getKey() != null
                    ? entry.getKey().trim().toLowerCase(Locale.ROOT) : "";
            String path = normalizeArchivePath("", entry.getValue());
            if (!mediaType.isEmpty() && path != null && !path.isEmpty()) {
                normalized.put(mediaType, path);
            }
        }
        return normalized;
    }

    private static Set<String> normalizeEntries(Set<String> entries) {
        if (entries == null || entries.isEmpty()) return Collections.emptySet();
        Set<String> normalized = new HashSet<>();
        for (String entry : entries) {
            String path = normalizeArchivePath("", entry);
            if (path != null && !path.isEmpty()) normalized.add(path);
        }
        return normalized;
    }

    private static boolean entryAllowed(Set<String> entries, String path) {
        return entries == null || entries.isEmpty() || entries.contains(path);
    }

    private static String normalizeSyntheticOrigin(String origin) {
        String value = origin != null ? origin.trim() : "";
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") || value.indexOf('?') >= 0
                || value.indexOf('#') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\'') >= 0 || value.indexOf('<') >= 0
                || value.indexOf('>') >= 0) {
            throw new IllegalArgumentException("syntheticOrigin must be a plain HTTPS origin");
        }
        return value;
    }

    private static ResolvedReference resolveArchiveReference(String baseDocumentPath, String value) {
        if (value == null) return null;
        String ref = decodeXmlEntities(value).trim();
        if (ref.isEmpty() || ref.startsWith("#")) return null;
        int suffixAt = firstSuffixIndex(ref);
        String pathPart = suffixAt >= 0 ? ref.substring(0, suffixAt) : ref;
        String suffix = suffixAt >= 0 ? ref.substring(suffixAt) : "";
        pathPart = decodeUriPath(pathPart).replace('\\', '/');
        if (pathPart.isEmpty() || pathPart.startsWith("//") || hasScheme(pathPart)) return null;

        String resolved;
        if (pathPart.startsWith(EPUB_PREFIX)) {
            resolved = normalizeArchivePath("", pathPart.substring(EPUB_PREFIX.length()));
        } else if (pathPart.startsWith("/")) {
            resolved = normalizeArchivePath("", pathPart.substring(1));
        } else {
            resolved = normalizeArchivePath(parentPath(baseDocumentPath), pathPart);
        }
        if (resolved == null || resolved.isEmpty()) return null;
        return new ResolvedReference(resolved, sanitizeSuffix(suffix));
    }

    private static String sanitizeSuffix(String suffix) {
        if (suffix == null || suffix.isEmpty()) return "";
        // URI suffixes are retained for XML attributes, but control characters are not.
        StringBuilder out = new StringBuilder(suffix.length());
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (c >= 0x20 && c != 0x7f) out.append(c);
        }
        return out.toString();
    }

    private static int firstSuffixIndex(String value) {
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        if (query < 0) return fragment;
        if (fragment < 0) return query;
        return Math.min(query, fragment);
    }

    private static boolean hasScheme(String value) {
        if (value == null || value.isEmpty() || !isAsciiLetter(value.charAt(0))) return false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ':') return true;
            if (!(isAsciiLetter(c) || Character.isDigit(c) || c == '+' || c == '-' || c == '.')) {
                return false;
            }
        }
        return false;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** Returns null when resolving the reference would escape above the archive root. */
    private static String normalizeArchivePath(String baseDirectory, String reference) {
        if (reference == null) return null;
        String ref = decodeUriPath(reference).replace('\\', '/');
        if (ref.startsWith("/") || ref.startsWith("//") || hasScheme(ref)) return null;
        String base = baseDirectory != null ? decodeUriPath(baseDirectory).replace('\\', '/') : "";
        String combined = base.isEmpty() ? ref : base + "/" + ref;
        List<String> parts = new ArrayList<>();
        for (String part : combined.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (parts.isEmpty()) return null;
                parts.remove(parts.size() - 1);
            } else {
                if (part.indexOf('\u0000') >= 0) return null;
                parts.add(part);
            }
        }
        return join(parts, "/");
    }

    private static String parentPath(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash) : "";
    }

    private static String encodeArchivePath(String path) {
        String[] parts = path.split("/", -1);
        StringBuilder out = new StringBuilder(path.length() + 16);
        for (String part : parts) {
            if (out.length() > 0) out.append('/');
            out.append(encodePathSegment(part));
        }
        return out.toString();
    }

    private static String encodePathSegment(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 8);
        for (byte raw : bytes) {
            int b = raw & 0xff;
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                    || (b >= '0' && b <= '9') || b == '-' || b == '.'
                    || b == '_' || b == '~' || b == '+') {
                out.append((char) b);
            } else {
                appendPercentEncoded(out, b);
            }
        }
        return out.toString();
    }

    private static String encodeQueryComponent(String value) {
        byte[] bytes = (value != null ? value : "").getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 8);
        for (byte raw : bytes) {
            int b = raw & 0xff;
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                    || (b >= '0' && b <= '9') || b == '-' || b == '.'
                    || b == '_' || b == '~') {
                out.append((char) b);
            } else {
                appendPercentEncoded(out, b);
            }
        }
        return out.toString();
    }

    private static void appendPercentEncoded(StringBuilder out, int value) {
        final char[] hex = "0123456789ABCDEF".toCharArray();
        out.append('%').append(hex[(value >>> 4) & 0xf]).append(hex[value & 0xf]);
    }

    private static String decodeUriPath(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String join(List<String> values, String separator) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(separator);
            out.append(value);
        }
        return out.toString();
    }

    private static String escapeHtmlAttribute(String value) {
        return escapeAttributeValue(value != null ? value : "", '"');
    }

    private static String escapeAttributeValue(String value, char quote) {
        String escaped = value.replace("&", "&amp;").replace("<", "&lt;");
        if (quote == '\'') return escaped.replace("'", "&apos;");
        return escaped.replace("\"", "&quot;");
    }

    private static String decodeXmlEntities(String value) {
        if (value == null || value.indexOf('&') < 0) return value != null ? value : "";
        return value.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String decodeXmlEntitiesForPolicy(String value) {
        String decoded = decodeXmlEntities(value);
        StringBuilder out = new StringBuilder(decoded.length());
        for (int i = 0; i < decoded.length();) {
            if (decoded.charAt(i) == '&' && i + 3 < decoded.length()
                    && decoded.charAt(i + 1) == '#') {
                int semi = decoded.indexOf(';', i + 2);
                if (semi > 0 && semi - i <= 10) {
                    String number = decoded.substring(i + 2, semi);
                    int radix = 10;
                    if (number.startsWith("x") || number.startsWith("X")) {
                        radix = 16;
                        number = number.substring(1);
                    }
                    try {
                        int codePoint = Integer.parseInt(number, radix);
                        if (Character.isValidCodePoint(codePoint)) {
                            out.appendCodePoint(codePoint);
                            i = semi + 1;
                            continue;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            out.append(decoded.charAt(i++));
        }
        return out.toString();
    }

    private static String attributeValue(Map<String, Attribute> attrs, String name) {
        Attribute attr = attrs.get(name.toLowerCase(Locale.ROOT));
        return attr != null ? decodeXmlEntities(attr.value) : "";
    }

    private static Map<String, Attribute> attributesByName(String source, Tag tag) {
        Map<String, Attribute> result = new HashMap<>();
        for (Attribute attr : parseAttributes(source, tag)) {
            if (!result.containsKey(attr.normalizedName)) {
                result.put(attr.normalizedName, attr);
            }
        }
        return result;
    }

    private static List<Attribute> parseAttributes(String source, Tag tag) {
        if (tag == null || tag.closing || tag.declaration) return Collections.emptyList();
        List<Attribute> result = new ArrayList<>();
        int i = tag.start + 1;
        while (i < tag.end && Character.isWhitespace(source.charAt(i))) i++;
        while (i < tag.end && isNameChar(source.charAt(i))) i++;
        while (i < tag.end) {
            while (i < tag.end && (Character.isWhitespace(source.charAt(i))
                    || source.charAt(i) == '/')) i++;
            if (i >= tag.end) break;
            int nameStart = i;
            while (i < tag.end && isAttributeNameChar(source.charAt(i))) i++;
            if (i == nameStart) {
                i++;
                continue;
            }
            String name = source.substring(nameStart, i);
            while (i < tag.end && Character.isWhitespace(source.charAt(i))) i++;
            if (i >= tag.end || source.charAt(i) != '=') {
                result.add(new Attribute(name, "", i, i, '\0', nameStart, i));
                continue;
            }
            i++;
            while (i < tag.end && Character.isWhitespace(source.charAt(i))) i++;
            if (i >= tag.end) {
                result.add(new Attribute(name, "", i, i, '\0', nameStart, i));
                break;
            }
            char quote = source.charAt(i);
            int valueStart;
            int valueEnd;
            if (quote == '\'' || quote == '"') {
                valueStart = ++i;
                while (i < tag.end && source.charAt(i) != quote) i++;
                valueEnd = i;
                if (i < tag.end) i++;
            } else {
                quote = '\0';
                valueStart = i;
                while (i < tag.end && !Character.isWhitespace(source.charAt(i))
                        && source.charAt(i) != '>') i++;
                valueEnd = i;
            }
            result.add(new Attribute(
                    name,
                    source.substring(valueStart, valueEnd),
                    valueStart,
                    valueEnd,
                    quote,
                    nameStart,
                    i));
        }
        return result;
    }

    private static List<NameValue> directObjectParams(String inner) {
        if (inner == null || inner.isEmpty()) return Collections.emptyList();
        List<NameValue> result = new ArrayList<>();
        int from = 0;
        int depth = 0;
        while (true) {
            Tag tag = nextTag(inner, from);
            if (tag == null) break;
            from = tag.end + 1;
            if (tag.declaration) continue;
            if (tag.closing) {
                if (depth > 0) depth--;
                continue;
            }
            if (depth == 0 && "param".equals(tag.name)) {
                Map<String, Attribute> attrs = attributesByName(inner, tag);
                String name = attributeValue(attrs, "name");
                String value = attributeValue(attrs, "value");
                if (!name.trim().isEmpty()) result.add(new NameValue(name, value));
            }
            if (!tag.selfClosing && !isVoidElement(tag.name)) depth++;
        }
        return result;
    }

    private static boolean isVoidElement(String name) {
        return "area".equals(name) || "base".equals(name) || "br".equals(name)
                || "col".equals(name) || "embed".equals(name) || "hr".equals(name)
                || "img".equals(name) || "input".equals(name) || "link".equals(name)
                || "meta".equals(name) || "param".equals(name) || "source".equals(name)
                || "track".equals(name) || "wbr".equals(name);
    }

    private static Tag findNextObjectOpen(String source, int from) {
        int cursor = from;
        while (true) {
            Tag tag = nextTag(source, cursor);
            if (tag == null) return null;
            cursor = tag.end + 1;
            if (!tag.closing && !tag.selfClosing
                    && ("script".equals(tag.name) || "style".equals(tag.name))) {
                Tag close = findRawTextClose(source, cursor, tag.name);
                if (close == null) return null;
                cursor = close.end + 1;
                continue;
            }
            if (!tag.closing && "object".equals(tag.name)) return tag;
        }
    }

    private static Tag findMatchingObjectClose(String source, int from) {
        int depth = 1;
        int cursor = from;
        while (true) {
            Tag tag = nextTag(source, cursor);
            if (tag == null) return null;
            cursor = tag.end + 1;
            if (!tag.closing && !tag.selfClosing
                    && ("script".equals(tag.name) || "style".equals(tag.name))) {
                Tag close = findRawTextClose(source, cursor, tag.name);
                if (close == null) return null;
                cursor = close.end + 1;
                continue;
            }
            if (!"object".equals(tag.name)) continue;
            if (tag.closing) {
                depth--;
                if (depth == 0) return tag;
            } else if (!tag.selfClosing) {
                depth++;
            }
        }
    }

    private static Tag findRawTextClose(String source, int from, String name) {
        String needle = "</" + name;
        int cursor = from;
        while (true) {
            int start = indexOfIgnoreCase(source, needle, cursor);
            if (start < 0) return null;
            Tag tag = nextTag(source, start);
            if (tag != null && tag.start == start && tag.closing && name.equals(tag.name)) {
                return tag;
            }
            cursor = start + needle.length();
        }
    }

    private static Tag nextTag(String source, int from) {
        int cursor = Math.max(0, from);
        while (cursor < source.length()) {
            int start = source.indexOf('<', cursor);
            if (start < 0) return null;
            if (source.startsWith("<!--", start)) {
                int end = source.indexOf("-->", start + 4);
                cursor = end >= 0 ? end + 3 : source.length();
                continue;
            }
            if (source.startsWith("<![CDATA[", start)) {
                int end = source.indexOf("]]>", start + 9);
                cursor = end >= 0 ? end + 3 : source.length();
                continue;
            }
            if (source.startsWith("<?", start)) {
                int end = source.indexOf("?>", start + 2);
                cursor = end >= 0 ? end + 2 : source.length();
                continue;
            }

            int i = start + 1;
            boolean closing = false;
            if (i < source.length() && source.charAt(i) == '/') {
                closing = true;
                i++;
            }
            while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
            if (i >= source.length()) return null;
            if (source.charAt(i) == '!') {
                int end = findTagEnd(source, i + 1);
                if (end < 0) return null;
                return new Tag(start, end, "", false, false, true);
            }
            int nameStart = i;
            while (i < source.length() && isNameChar(source.charAt(i))) i++;
            if (i == nameStart) {
                cursor = start + 1;
                continue;
            }
            String name = source.substring(nameStart, i).toLowerCase(Locale.ROOT);
            int end = findTagEnd(source, i);
            if (end < 0) return null;
            int beforeEnd = end - 1;
            while (beforeEnd > i && Character.isWhitespace(source.charAt(beforeEnd))) beforeEnd--;
            boolean selfClosing = !closing && beforeEnd >= i && source.charAt(beforeEnd) == '/';
            return new Tag(start, end, name, closing, selfClosing, false);
        }
        return null;
    }

    private static int findTagEnd(String source, int from) {
        char quote = '\0';
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != '\0') {
                if (c == quote) quote = '\0';
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == ':' || c == '_' || c == '-';
    }

    private static boolean isAttributeNameChar(char c) {
        return isNameChar(c) || c == '.';
    }

    private static int indexOfIgnoreCase(String source, String needle, int from) {
        int max = source.length() - needle.length();
        for (int i = Math.max(0, from); i <= max; i++) {
            if (source.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    private static final class ResolvedReference {
        final String archivePath;
        final String suffix;

        ResolvedReference(String archivePath, String suffix) {
            this.archivePath = archivePath;
            this.suffix = suffix != null ? suffix : "";
        }
    }

    private static final class Tag {
        final int start;
        final int end;
        final String name;
        final boolean closing;
        final boolean selfClosing;
        final boolean declaration;

        Tag(int start, int end, String name,
            boolean closing, boolean selfClosing, boolean declaration) {
            this.start = start;
            this.end = end;
            this.name = name;
            this.closing = closing;
            this.selfClosing = selfClosing;
            this.declaration = declaration;
        }
    }

    private static final class Attribute {
        final String normalizedName;
        final String value;
        final int valueStart;
        final int valueEnd;
        final char quote;
        final int attributeStart;
        final int attributeEnd;

        Attribute(String name, String value, int valueStart, int valueEnd, char quote,
                  int attributeStart, int attributeEnd) {
            this.normalizedName = name.toLowerCase(Locale.ROOT);
            this.value = value;
            this.valueStart = valueStart;
            this.valueEnd = valueEnd;
            this.quote = quote;
            this.attributeStart = attributeStart;
            this.attributeEnd = attributeEnd;
        }
    }

    private static final class NameValue {
        final String name;
        final String value;

        NameValue(String name, String value) {
            this.name = name != null ? name : "";
            this.value = value != null ? value : "";
        }
    }

    private static final class TextReplacement {
        final int start;
        final int end;
        final String value;

        TextReplacement(int start, int end, String value) {
            this.start = start;
            this.end = end;
            this.value = value;
        }
    }
}
