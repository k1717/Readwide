package com.readwide.manager;

import com.readwide.manager.util.UriPathCodec;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Archive-local navigation metadata. No WebView, scripts, or network access. */
final class EpubNavigationParser {
    private static final String OPS = "http://www.idpf.org/2007/ops";

    static final class Target {
        final String path, fragment;
        Target(String path, String fragment) { this.path = path; this.fragment = fragment; }
    }

    static final class Entry {
        final String title;
        final Target target;
        final int depth;
        Entry(String title, Target target, int depth) {
            this.title = title == null ? "" : title;
            this.target = target;
            this.depth = Math.max(0, depth);
        }
    }

    private EpubNavigationParser() { }

    /** basePath is already decoded; only the href is percent-decoded, once. */
    static Target resolve(String basePath, String href) {
        if (href == null || href.trim().isEmpty()) return null;
        String value = href.trim();
        int hash = value.indexOf('#');
        String fragment = hash < 0 ? "" : UriPathCodec.decodePercentEscapes(value.substring(hash + 1));
        String path = hash < 0 ? value : value.substring(0, hash);
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        path = UriPathCodec.decodePercentEscapes(path);
        if (path.startsWith("/") || path.indexOf(':') >= 0 || path.indexOf('\\') >= 0
                || path.indexOf('\0') >= 0 || fragment.indexOf('\0') >= 0) return null;
        if (path.isEmpty()) return new Target(basePath, fragment);
        int slash = basePath.lastIndexOf('/');
        String joined = (slash < 0 ? "" : basePath.substring(0, slash + 1)) + path;
        ArrayList<String> parts = new ArrayList<>();
        for (String part : joined.split("/")) {
            if (part.equals("..")) {
                if (parts.isEmpty()) return null;
                parts.remove(parts.size() - 1);
            } else if (!part.isEmpty() && !part.equals(".")) parts.add(part);
        }
        return parts.isEmpty() ? null : new Target(String.join("/", parts), fragment);
    }

    static List<Entry> contents(ZipFile zip, DocumentArchiveUtils.EpubPackageResources metadata,
                                Set<String> readablePaths) {
        List<Entry> entries = readContents(zip, metadata.navigationPath, readablePaths, false);
        if (entries.isEmpty()) entries = readContents(zip, metadata.ncxPath, readablePaths, true);
        return entries;
    }

    private static List<Entry> readContents(ZipFile zip, String path, Set<String> paths, boolean ncx) {
        if (path.isEmpty()) return Collections.emptyList();
        try {
            Document document = readDocument(zip, path);
            Node root = null;
            for (Node n = document.getDocumentElement(); n != null; n = next(n, document)) {
                if ((ncx && named(n, "navMap")) || (!ncx && named(n, "nav")
                        && (type(n, "toc") || token(attr(n, "role"), "doc-toc")))) {
                    root = n;
                    break;
                }
            }
            if (root == null) return Collections.emptyList();
            List<Entry> result = new ArrayList<>();
            boolean actionable = false;
            for (Node n = root.getFirstChild(); n != null; n = next(n, root)) {
                String title, href;
                int depth = 0;
                if (ncx) {
                    if (!named(n, "navPoint")) continue;
                    Node label = DocumentArchiveUtils.firstDirectChildByLocalName(n, "navLabel");
                    Node content = DocumentArchiveUtils.firstDirectChildByLocalName(n, "content");
                    title = label == null ? "" : text(label);
                    href = attr(content, "src");
                } else {
                    if (!named(n.getParentNode(), "li") || (!named(n, "a") && !named(n, "span"))) continue;
                    title = text(n);
                    href = attr(n, "href");
                }
                if (title.isEmpty()) continue;
                for (Node p = n.getParentNode(); p != null && p != root; p = p.getParentNode()) {
                    if (named(p, ncx ? "navPoint" : "ol")) depth++;
                }
                if (!ncx) depth = Math.max(0, depth - 1);
                Target target = resolve(path, href);
                if (target != null && !paths.contains(target.path)) continue;
                // Unlinked spans are group headings, not broken clickable rows.
                if (target == null && (ncx || !named(n, "span"))) continue;
                result.add(new Entry(title, target, depth));
                actionable |= target != null;
            }
            return actionable ? result : Collections.emptyList();
        } catch (Exception | OutOfMemoryError | StackOverflowError e) {
            // DOM construction may recurse even though traversal below does not.
            // Optional navigation metadata must not prevent the book from opening.
            return Collections.emptyList();
        }
    }

    /** Only semantic note containers qualify; an arbitrary fragment is still normal navigation. */
    static String footnote(ZipFile zip, Target target, String sourcePath) {
        if (target.fragment.isEmpty()) return null;
        try {
            Document document = readDocument(zip, target.path);
            for (Node n = document.getDocumentElement(); n != null; n = next(n, document)) {
                if (!target.fragment.equals(attr(n, "id"))
                        && !(named(n, "a") && target.fragment.equals(attr(n, "name")))) continue;
                for (Node p = n; p != null; p = p.getParentNode()) {
                    if (type(p, "footnote") || type(p, "endnote") || type(p, "rearnote")
                            || token(attr(p, "role"), "doc-footnote")
                            || token(attr(p, "role"), "doc-endnote")) {
                        String note = text(p);
                        return note.isEmpty() ? null : note;
                    }
                }
                // Some books mark only the reference, not the destination.
                Document source = target.path.equals(sourcePath) ? document : readDocument(zip, sourcePath);
                if (!hasNoteReference(source, sourcePath, target)) return null;
                Node content = n;
                if (named(n, "a") && text(n).isEmpty()) {
                    content = legacyNoteContent(n);
                    if (content == null) return null;
                }
                String note = text(content);
                return note.isEmpty() ? null : note;
            }
        } catch (Exception | OutOfMemoryError | StackOverflowError ignored) { }
        return null;
    }

    /** A marked reference permits a nearby legacy note, not an entire chapter. */
    private static Node legacyNoteContent(Node anchor) {
        Node parent = anchor.getParentNode();
        if (noteBlock(parent)) return parent;
        Node next = anchor.getNextSibling();
        while (next != null && (next.getNodeType() == Node.COMMENT_NODE
                || next.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE
                || (next.getNodeType() == Node.TEXT_NODE && next.getNodeValue().trim().isEmpty()))) {
            next = next.getNextSibling();
        }
        // Do not jump over a heading, a second anchor, prose, or another named
        // note block to obtain text belonging to a different destination.
        if (!noteBlock(next) || !attr(next, "id").isEmpty()) return null;
        Node first = next.getFirstChild();
        while (first != null && (first.getNodeType() == Node.COMMENT_NODE
                || first.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE
                || (first.getNodeType() == Node.TEXT_NODE && first.getNodeValue().trim().isEmpty()))) {
            first = first.getNextSibling();
        }
        if (named(first, "a") && (!attr(first, "id").isEmpty() || !attr(first, "name").isEmpty())) return null;
        return next;
    }

    private static boolean noteBlock(Node node) {
        return named(node, "p") || named(node, "li") || named(node, "aside");
    }

    private static boolean hasNoteReference(Document source, String sourcePath, Target target) {
        for (Node n = source.getDocumentElement(); n != null; n = next(n, source)) {
            if (!named(n, "a") || !(type(n, "noteref") || token(attr(n, "role"), "doc-noteref"))) continue;
            Target reference = resolve(sourcePath, attr(n, "href"));
            if (reference != null && reference.path.equals(target.path)
                    && reference.fragment.equals(target.fragment)) return true;
        }
        return false;
    }

    private static Document readDocument(ZipFile zip, String path) throws Exception {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null || entry.isDirectory()) throw new java.io.IOException("Missing navigation entry");
        String xml = EpubNavigationXml.prepare(DocumentArchiveUtils.readZipEntryString(zip, entry));
        return DocumentArchiveUtils.secureDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static boolean named(Node node, String name) {
        return node != null && (name.equals(node.getLocalName()) || name.equals(node.getNodeName()));
    }

    private static String attr(Node node, String name) {
        return node instanceof Element ? ((Element) node).getAttribute(name) : "";
    }

    private static boolean type(Node node, String value) {
        return node instanceof Element && token(((Element) node).getAttributeNS(OPS, "type"), value);
    }

    private static boolean token(String list, String value) {
        for (String part : list.trim().split("\\s+")) if (part.equals(value)) return true;
        return false;
    }

    private static Node next(Node node, Node root) {
        if (node.getFirstChild() != null) return node.getFirstChild();
        return after(node, root);
    }

    private static Node after(Node node, Node root) {
        while (node != null && node != root) {
            if (node.getNextSibling() != null) return node.getNextSibling();
            node = node.getParentNode();
        }
        return null;
    }

    /** Iterative traversal avoids a recursive Java stack for deeply nested XHTML. */
    private static String text(Node root) {
        StringBuilder result = new StringBuilder();
        for (Node n = root; n != null;) {
            if (named(n, "script") || named(n, "style") || type(n, "backlink")
                    || token(attr(n, "role"), "doc-backlink")) {
                n = after(n, root);
                continue;
            }
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                result.append(n.getNodeValue());
            } else if (named(n, "img")) result.append(attr(n, "alt"));
            else if (named(n, "p") || named(n, "div") || named(n, "li") || named(n, "br")) result.append('\n');
            n = next(n, root);
        }
        return result.toString().replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n[\\s]*", "\n").trim();
    }
}
