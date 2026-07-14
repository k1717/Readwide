package com.readwide.manager;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.readwide.manager.util.SecureXml;
import com.readwide.manager.util.UriPathCodec;

import javax.xml.parsers.DocumentBuilder;

final class DocumentArchiveUtils {
    private static final int DETECTION_TEXT_READ_LIMIT_BYTES = 512 * 1024;

    private DocumentArchiveUtils() {}

    static boolean detectEpubFixedLayoutLike(ZipFile zip) {
        try {
            ZipEntry containerEntry = zip.getEntry("META-INF/container.xml");
            if (containerEntry != null) {
                Document containerDoc;
                try (InputStream is = zip.getInputStream(containerEntry)) {
                    containerDoc = secureDocumentBuilder().parse(is);
                }
                NodeList rootFiles = containerDoc.getElementsByTagName("rootfile");
                if (rootFiles.getLength() == 0) rootFiles = containerDoc.getElementsByTagNameNS("*", "rootfile");
                if (rootFiles.getLength() > 0) {
                    Node fullPathAttr = rootFiles.item(0).getAttributes() != null
                            ? rootFiles.item(0).getAttributes().getNamedItem("full-path") : null;
                    if (fullPathAttr != null) {
                        String opfPath = fullPathAttr.getNodeValue();
                        ZipEntry opfEntry = zip.getEntry(opfPath);
                        if (opfEntry != null) {
                            String opf = readZipEntryPreviewString(zip, opfEntry);
                            String lower = opf != null ? opf.toLowerCase(Locale.US) : "";
                            if (lower.contains("rendition:layout") && lower.contains("pre-paginated")) return true;
                            if (lower.contains("fixed layout") || lower.contains("fixed-layout")) return true;
                        }
                    }
                }
            }

            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            int scanned = 0;
            while (entries.hasMoreElements() && scanned < 80) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory() || !isEpubHtmlPath(entry.getName())) continue;
                scanned++;
                String html = readZipEntryPreviewString(zip, entry);
                EpubViewportParser.Dimensions viewport = EpubViewportParser.parse(html);
                if (viewport.isUsable()) return true;
            }
        } catch (Throwable ignored) {
            // Fall through to reflowable handling if detection fails.
        }
        return false;
    }

    /**
     * Classifies only genuinely page-image EPUBs for landscape spreads. A fixed
     * layout declaration is a hint, not proof: fixed-layout EPUBs can still be
     * ordinary HTML/text documents. Cover-only books are rejected because the
     * non-cover spine items must also be image-dominant.
     */
    static boolean detectEpubImagePageLike(List<String> htmlPages,
                                           boolean fixedLayoutHint) {
        return EpubImagePageClassifier.isImagePageBook(htmlPages, fixedLayoutHint);
    }

    static boolean detectEpubDeclaredFont(ZipFile zip) {
        try {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            int scanned = 0;
            while (entries.hasMoreElements() && scanned < 160) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;
                String name = entry.getName();
                if (name == null) continue;
                String lower = name.toLowerCase(Locale.US);
                if (!(lower.endsWith(".css") || lower.endsWith(".html") || lower.endsWith(".xhtml") || lower.endsWith(".htm"))) {
                    continue;
                }
                scanned++;
                String text = readZipEntryPreviewString(zip, entry);
                if (text == null) continue;
                String compact = text.toLowerCase(Locale.US);
                if (compact.contains("@font-face") || compact.contains("font-family")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // If detection fails, fall back to normal Readwide font handling.
        }
        return false;
    }

    static List<String> findEpubSpinePaths(ZipFile zip) {
        ArrayList<String> result = new ArrayList<>();
        try {
            ZipEntry containerEntry = zip.getEntry("META-INF/container.xml");
            if (containerEntry == null) return result;

            Document containerDoc;
            try (InputStream is = zip.getInputStream(containerEntry)) {
                containerDoc = secureDocumentBuilder().parse(is);
            }

            NodeList rootFiles = containerDoc.getElementsByTagName("rootfile");
            if (rootFiles.getLength() == 0) rootFiles = containerDoc.getElementsByTagNameNS("*", "rootfile");
            if (rootFiles.getLength() == 0) return result;

            Node rootFile = rootFiles.item(0);
            NamedNodeMap rootAttrs = rootFile.getAttributes();
            Node fullPathAttr = rootAttrs != null ? rootAttrs.getNamedItem("full-path") : null;
            if (fullPathAttr == null) return result;

            String opfPath = fullPathAttr.getNodeValue();
            ZipEntry opfEntry = zip.getEntry(opfPath);
            if (opfEntry == null) return result;

            Document opfDoc;
            try (InputStream is = zip.getInputStream(opfEntry)) {
                opfDoc = secureDocumentBuilder().parse(is);
            }

            String basePath = parentPath(opfPath);
            Map<String, String> manifest = new LinkedHashMap<>();
            NodeList items = opfDoc.getElementsByTagName("item");
            if (items.getLength() == 0) items = opfDoc.getElementsByTagNameNS("*", "item");
            for (int i = 0; i < items.getLength(); i++) {
                Node item = items.item(i);
                NamedNodeMap attrs = item.getAttributes();
                if (attrs == null) continue;
                Node id = attrs.getNamedItem("id");
                Node href = attrs.getNamedItem("href");
                if (id != null && href != null) {
                    manifest.put(id.getNodeValue(), normalizeZipPath(basePath + "/" + decodeHref(href.getNodeValue())));
                }
            }

            NodeList itemRefs = opfDoc.getElementsByTagName("itemref");
            if (itemRefs.getLength() == 0) itemRefs = opfDoc.getElementsByTagNameNS("*", "itemref");
            for (int i = 0; i < itemRefs.getLength(); i++) {
                Node itemRef = itemRefs.item(i);
                NamedNodeMap attrs = itemRef.getAttributes();
                if (attrs == null) continue;
                Node idRef = attrs.getNamedItem("idref");
                if (idRef == null) continue;
                String path = manifest.get(idRef.getNodeValue());
                if (path != null && isEpubHtmlPath(path) && zip.getEntry(path) != null) result.add(path);
            }
        } catch (Exception ignored) {}
        return result;
    }

    static List<String> findEpubHtmlEntries(ZipFile zip) {
        ArrayList<String> result = new ArrayList<>();
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && isEpubHtmlPath(entry.getName())) result.add(entry.getName());
        }
        java.util.Collections.sort(result);
        return result;
    }

    /** Cap a single document text entry (EPUB/HTML/OPF, Word/HWPX XML) so a crafted
     *  oversized entry can't exhaust memory during page rendering. */
    private static final long MAX_DOCUMENT_TEXT_ENTRY_BYTES = 32L * 1024L * 1024L;

    static String readZipEntryString(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream is = zip.getInputStream(entry)) {
            byte[] data = readAllBytesWithLimit(is, MAX_DOCUMENT_TEXT_ENTRY_BYTES);
            return DocumentTextDecoder.decode(data);
        }
    }

    static byte[] readAllBytesWithLimit(InputStream is, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        long total = 0L;
        while ((n = is.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("Document text entry exceeds size limit");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static String readZipEntryPreviewString(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream is = zip.getInputStream(entry)) {
            byte[] data = readAtMostBytes(is, DETECTION_TEXT_READ_LIMIT_BYTES);
            return DocumentTextDecoder.decode(data);
        }
    }

    private static byte[] readAtMostBytes(InputStream is, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(8192, Math.max(0, maxBytes)));
        byte[] buf = new byte[8192];
        int remaining = Math.max(0, maxBytes);
        while (remaining > 0) {
            int n = is.read(buf, 0, Math.min(buf.length, remaining));
            if (n == -1) break;
            out.write(buf, 0, n);
            remaining -= n;
        }
        return out.toByteArray();
    }

    static DocumentBuilder secureDocumentBuilder() throws Exception {
        return SecureXml.newDocumentBuilder(true);
    }

    static Node firstNodeByLocalName(Document doc, String localName) {
        NodeList list = doc.getElementsByTagNameNS("*", localName);
        if (list.getLength() > 0) return list.item(0);
        list = doc.getElementsByTagName("w:" + localName);
        if (list.getLength() > 0) return list.item(0);
        list = doc.getElementsByTagName(localName);
        if (list.getLength() > 0) return list.item(0);
        return null;
    }

    static Node firstDirectChildByLocalName(Node node, String localName) {
        if (node == null) return null;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (localName.equals(child.getLocalName()) || ("w:" + localName).equals(child.getNodeName())) return child;
        }
        return null;
    }

    static String titleFromHtml(String html) {
        if (html == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (m.find()) return htmlToText(m.group(1)).trim();
        m = java.util.regex.Pattern.compile("(?is)<h1[^>]*>(.*?)</h1>").matcher(html);
        if (m.find()) return htmlToText(m.group(1)).trim();
        return "";
    }

    static String htmlToText(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ");
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static boolean isEpubHtmlPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm");
    }

    static String parentPath(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash) : "";
    }

    static String fileNameFromPath(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    static String decodeHref(String href) {
        return UriPathCodec.decodePercentEscapes(href);
    }

    static String normalizeZipPath(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/');
        while (normalized.contains("//")) normalized = normalized.replace("//", "/");
        ArrayList<String> parts = new ArrayList<>();
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) out.append('/');
            out.append(parts.get(i));
        }
        return out.toString();
    }

    static String mimeForPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".otf")) return "font/otf";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    static String attr(Node node, String qualified, String localName) {
        if (node == null || node.getAttributes() == null) return null;
        Node a = node.getAttributes().getNamedItem(qualified);
        if (a == null) a = node.getAttributes().getNamedItem(localName);
        if (a == null) a = node.getAttributes().getNamedItemNS("*", localName);
        return a != null ? a.getNodeValue() : null;
    }
}
