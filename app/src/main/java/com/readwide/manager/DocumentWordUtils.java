package com.readwide.manager;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class DocumentWordUtils {
    private DocumentWordUtils() {}

    static String detectDefaultFontFamily(ZipFile zip) {
        String fromStyles = detectDefaultFontFromStyles(zip);
        if (fromStyles != null && !fromStyles.trim().isEmpty()) return fromStyles.trim();

        String fromDocument = detectFirstDeclaredFont(zip);
        if (fromDocument != null && !fromDocument.trim().isEmpty()) return fromDocument.trim();

        String fromFontTable = detectFirstFontTableName(zip);
        if (fromFontTable != null && !fromFontTable.trim().isEmpty()) return fromFontTable.trim();
        return null;
    }

    static Map<String, String> loadRelationships(ZipFile zip) {
        Map<String, String> relationships = new LinkedHashMap<>();
        try {
            ZipEntry rels = zip.getEntry("word/_rels/document.xml.rels");
            if (rels == null) return relationships;
            Document relDoc;
            try (InputStream is = zip.getInputStream(rels)) {
                relDoc = DocumentArchiveUtils.secureDocumentBuilder().parse(is);
            }
            NodeList relsList = relDoc.getElementsByTagName("Relationship");
            if (relsList.getLength() == 0) relsList = relDoc.getElementsByTagNameNS("*", "Relationship");
            for (int i = 0; i < relsList.getLength(); i++) {
                Node r = relsList.item(i);
                String id = DocumentArchiveUtils.attr(r, "Id", "Id");
                String target = DocumentArchiveUtils.attr(r, "Target", "Target");
                String mode = DocumentArchiveUtils.attr(r, "TargetMode", "TargetMode");
                if (id == null || target == null || "External".equalsIgnoreCase(mode)) continue;
                if (target.startsWith("/")) target = target.substring(1);
                else target = "word/" + target;
                relationships.put(id, DocumentArchiveUtils.normalizeZipPath(target));
            }
        } catch (Exception ignored) {}
        return relationships;
    }

    static String renderParagraph(Node p, Map<String, String> wordRelationships, String localHost) {
        StringBuilder inline = new StringBuilder();
        appendWordInlineHtmlLimited(p, inline, p, wordRelationships, localHost);

        StringBuilder out = new StringBuilder();
        if (hasVisibleHtml(inline)) {
            out.append("<p").append(wordParagraphStyle(p)).append(">");
            out.append(inline);
            out.append("</p>");
        }
        appendWordTextBoxes(p, out, wordRelationships, localHost);
        return out.toString();
    }

    private static boolean hasVisibleHtml(StringBuilder html) {
        if (html == null || html.length() == 0) return false;
        String text = html.toString().replaceAll("(?is)<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&emsp;", " ").trim();
        return !text.isEmpty() || html.indexOf("<img") >= 0;
    }

    static String renderTable(Node table, Map<String, String> wordRelationships, String localHost) {
        StringBuilder out = new StringBuilder("<table>");
        NodeList rows = table.getChildNodes();
        for (int i = 0; i < rows.getLength(); i++) {
            Node row = rows.item(i);
            String local = row.getLocalName();
            String name = row.getNodeName();
            if (!"tr".equals(local) && !"w:tr".equals(name)) continue;
            out.append("<tr>");
            NodeList cells = row.getChildNodes();
            for (int j = 0; j < cells.getLength(); j++) {
                Node cell = cells.item(j);
                String cl = cell.getLocalName();
                String cn = cell.getNodeName();
                if (!"tc".equals(cl) && !"w:tc".equals(cn)) continue;
                out.append("<td>");
                NodeList ps = cell.getChildNodes();
                for (int k = 0; k < ps.getLength(); k++) {
                    Node cp = ps.item(k);
                    String pl = cp.getLocalName();
                    String pn = cp.getNodeName();
                    if ("p".equals(pl) || "w:p".equals(pn)) out.append(renderParagraph(cp, wordRelationships, localHost));
                    else if ("tbl".equals(pl) || "w:tbl".equals(pn)) out.append(renderTable(cp, wordRelationships, localHost));
                }
                out.append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</table>");
        return out.toString();
    }

    static boolean containsPageBreak(Node node) {
        if (node == null) return false;
        String local = node.getLocalName();
        String name = node.getNodeName();
        if ("lastRenderedPageBreak".equals(local) || "w:lastRenderedPageBreak".equals(name)) return true;
        if ("br".equals(local) || "w:br".equals(name)) {
            NamedNodeMap attrs = node.getAttributes();
            Node type = attrs != null ? attrs.getNamedItem("w:type") : null;
            if (type == null && attrs != null) type = attrs.getNamedItem("type");
            if ("page".equalsIgnoreCase(type != null ? type.getNodeValue() : null)) return true;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (containsPageBreak(children.item(i))) return true;
        }
        return false;
    }

    private static String detectDefaultFontFromStyles(ZipFile zip) {
        try {
            ZipEntry styles = zip.getEntry("word/styles.xml");
            if (styles == null) return null;
            Document stylesDoc;
            try (InputStream is = zip.getInputStream(styles)) {
                stylesDoc = DocumentArchiveUtils.secureDocumentBuilder().parse(is);
            }

            Node docDefaults = DocumentArchiveUtils.firstNodeByLocalName(stylesDoc, "docDefaults");
            String font = firstFontFamilyUnderNode(docDefaults);
            if (font != null) return font;

            Node normalStyle = findStyleById(stylesDoc, "Normal");
            font = firstFontFamilyUnderNode(normalStyle);
            if (font != null) return font;
        } catch (Throwable ignored) {
            // Fall back to scanning document/fontTable entries.
        }
        return null;
    }

    private static Node findStyleById(Document stylesDoc, String styleId) {
        NodeList styles = stylesDoc.getElementsByTagNameNS("*", "style");
        if (styles.getLength() == 0) styles = stylesDoc.getElementsByTagName("w:style");
        if (styles.getLength() == 0) styles = stylesDoc.getElementsByTagName("style");
        for (int i = 0; i < styles.getLength(); i++) {
            Node style = styles.item(i);
            String id = DocumentArchiveUtils.attr(style, "w:styleId", "styleId");
            if (styleId.equalsIgnoreCase(id)) return style;
        }
        return null;
    }

    private static String detectFirstDeclaredFont(ZipFile zip) {
        try {
            ZipEntry documentXml = zip.getEntry("word/document.xml");
            if (documentXml == null) return null;
            Document doc;
            try (InputStream is = zip.getInputStream(documentXml)) {
                doc = DocumentArchiveUtils.secureDocumentBuilder().parse(is);
            }
            return firstFontFamilyUnderNode(doc.getDocumentElement());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String detectFirstFontTableName(ZipFile zip) {
        try {
            ZipEntry fontTable = zip.getEntry("word/fontTable.xml");
            if (fontTable == null) return null;
            Document fontDoc;
            try (InputStream is = zip.getInputStream(fontTable)) {
                fontDoc = DocumentArchiveUtils.secureDocumentBuilder().parse(is);
            }
            NodeList fonts = fontDoc.getElementsByTagNameNS("*", "font");
            if (fonts.getLength() == 0) fonts = fontDoc.getElementsByTagName("w:font");
            if (fonts.getLength() == 0) fonts = fontDoc.getElementsByTagName("font");
            for (int i = 0; i < fonts.getLength(); i++) {
                String name = sanitizeFontName(DocumentArchiveUtils.attr(fonts.item(i), "w:name", "name"));
                if (name != null) return name;
            }
        } catch (Throwable ignored) {
            // Missing fontTable is normal for simple DOCX files.
        }
        return null;
    }

    private static String firstFontFamilyUnderNode(Node root) {
        if (root == null) return null;
        if ("rFonts".equals(root.getLocalName()) || "w:rFonts".equals(root.getNodeName())) {
            String font = wordFontFromRFonts(root);
            if (font != null) return font;
        }
        NodeList nodes = root.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            String font = firstFontFamilyUnderNode(nodes.item(i));
            if (font != null) return font;
        }
        return null;
    }

    private static String wordFontFromRFonts(Node rFonts) {
        String font = sanitizeFontName(DocumentArchiveUtils.attr(rFonts, "w:ascii", "ascii"));
        if (font != null) return font;
        font = sanitizeFontName(DocumentArchiveUtils.attr(rFonts, "w:hAnsi", "hAnsi"));
        if (font != null) return font;
        font = sanitizeFontName(DocumentArchiveUtils.attr(rFonts, "w:eastAsia", "eastAsia"));
        if (font != null) return font;
        font = sanitizeFontName(DocumentArchiveUtils.attr(rFonts, "w:cs", "cs"));
        if (font != null) return font;
        font = sanitizeFontName(DocumentArchiveUtils.attr(rFonts, "w:asciiTheme", "asciiTheme"));
        if (font != null) return font;
        font = sanitizeFontName(DocumentArchiveUtils.attr(rFonts, "w:hAnsiTheme", "hAnsiTheme"));
        if (font != null) return font;
        font = sanitizeFontName(DocumentArchiveUtils.attr(rFonts, "w:eastAsiaTheme", "eastAsiaTheme"));
        if (font != null) return font;
        return sanitizeFontName(DocumentArchiveUtils.attr(rFonts, "w:cstheme", "cstheme"));
    }

    private static String sanitizeFontName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("+") || trimmed.toLowerCase(Locale.US).contains("theme")) return null;
        return trimmed;
    }

    private static void appendWordInlineHtmlLimited(Node node, StringBuilder out, Node rootParagraph,
                                                    Map<String, String> wordRelationships, String localHost) {
        if (node == null) return;
        String local = node.getLocalName();
        String name = node.getNodeName();

        if (node != rootParagraph && ("p".equals(local) || "w:p".equals(name)
                || "tbl".equals(local) || "w:tbl".equals(name)
                || "txbxContent".equals(local) || "w:txbxContent".equals(name))) {
            return;
        }

        if ("t".equals(local) || "w:t".equals(name)) {
            out.append(escapeWordText(node));
            return;
        }
        if ("tab".equals(local) || "w:tab".equals(name)) {
            out.append("&emsp;");
            return;
        }
        if ("br".equals(local) || "w:br".equals(name) || "cr".equals(local) || "w:cr".equals(name)) {
            if (!isPageBreakNode(node)) out.append("<br>");
            return;
        }
        if ("blip".equals(local) || "a:blip".equals(name)) {
            String img = imageSourceForBlip(node, wordRelationships, localHost);
            if (img != null) out.append("<img class=\"word-img\" src=\"").append(img).append("\"/>");
            return;
        }

        boolean run = "r".equals(local) || "w:r".equals(name);
        String runSuffix = "";
        if (run) {
            WordRunStyle style = readRunStyle(node);
            if (style.hasStyle()) {
                out.append("<span style=\"").append(style.css).append("\">");
                runSuffix = "</span>" + runSuffix;
            }
            if (style.bold) { out.append("<b>"); runSuffix = "</b>" + runSuffix; }
            if (style.italic) { out.append("<i>"); runSuffix = "</i>" + runSuffix; }
            if (style.underline) { out.append("<u>"); runSuffix = "</u>" + runSuffix; }
        }

        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            appendWordInlineHtmlLimited(children.item(i), out, rootParagraph, wordRelationships, localHost);
        }

        if (run) out.append(runSuffix);
    }

    /** Select one preview-compatible OOXML alternative, before traversing text, images or breaks. */
    static void selectMarkupAlternatives(Node root) {
        if (root == null) return;
        java.util.ArrayDeque<Node> pending = new java.util.ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if ("AlternateContent".equals(node.getLocalName())
                    && "http://schemas.openxmlformats.org/markup-compatibility/2006".equals(node.getNamespaceURI())) {
                Node selected = null;
                Node fallback = null;
                for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (!node.getNamespaceURI().equals(child.getNamespaceURI())) continue;
                    if ("Fallback".equals(child.getLocalName())) fallback = child;
                    if (selected == null && "Choice".equals(child.getLocalName()) && supportsChoice(child)) selected = child;
                }
                if (selected == null) selected = fallback;
                // Keep the namespace-declaring ancestors in place. The layout reader
                // unwraps these containers; discarded alternatives cannot leak breaks/text.
                for (Node child = node.getFirstChild(); child != null;) {
                    Node next = child.getNextSibling();
                    if (child != selected) node.removeChild(child);
                    child = next;
                }
                if (selected != null) pending.push(selected);
                continue;
            }
            for (Node child = node.getLastChild(); child != null; child = child.getPreviousSibling()) pending.push(child);
        }
    }

    private static boolean supportsChoice(Node choice) {
        String requires = DocumentArchiveUtils.attr(choice, "Requires", "Requires");
        if (requires == null || requires.trim().isEmpty()) return false;
        for (String prefix : requires.trim().split("\\s+")) {
            String namespace = choice.lookupNamespaceURI(prefix);
            // These namespaces have reading-order preview handlers, not full Office layout parity.
            if (!"http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(namespace)
                    && !"http://schemas.openxmlformats.org/drawingml/2006/main".equals(namespace)
                    && !"http://schemas.openxmlformats.org/drawingml/2006/picture".equals(namespace)
                    && !"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing".equals(namespace)
                    && !"http://schemas.microsoft.com/office/word/2010/wordprocessingShape".equals(namespace)
                    && !"urn:schemas-microsoft-com:vml".equals(namespace)) return false;
        }
        return true;
    }

    private static void appendWordTextBoxes(Node node, StringBuilder out,
                                            Map<String, String> wordRelationships, String localHost) {
        if (node == null) return;
        String local = node.getLocalName();
        String name = node.getNodeName();
        if ("txbxContent".equals(local) || "w:txbxContent".equals(name)) {
            StringBuilder box = new StringBuilder();
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                String cl = child.getLocalName();
                String cn = child.getNodeName();
                if ("p".equals(cl) || "w:p".equals(cn)) box.append(renderParagraph(child, wordRelationships, localHost));
                else if ("tbl".equals(cl) || "w:tbl".equals(cn)) box.append(renderTable(child, wordRelationships, localHost));
            }
            if (box.length() > 0) out.append("<div class=\"textbox\">").append(box).append("</div>");
            return;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) appendWordTextBoxes(children.item(i), out, wordRelationships, localHost);
    }

    private static String wordParagraphStyle(Node p) {
        StringBuilder css = new StringBuilder();
        Node pPr = DocumentArchiveUtils.firstDirectChildByLocalName(p, "pPr");
        if (pPr != null) {
            Node jc = DocumentArchiveUtils.firstDirectChildByLocalName(pPr, "jc");
            String align = DocumentArchiveUtils.attr(jc, "w:val", "val");
            if (align != null) {
                if ("center".equalsIgnoreCase(align)) css.append("text-align:center;");
                else if ("right".equalsIgnoreCase(align) || "end".equalsIgnoreCase(align)) css.append("text-align:right;");
                else if ("both".equalsIgnoreCase(align)) css.append("text-align:justify;");
            }
        }
        return css.length() > 0 ? " style=\"" + css + "\"" : "";
    }

    private static final class WordRunStyle {
        boolean bold;
        boolean italic;
        boolean underline;
        String css = "";
        boolean hasStyle() { return css != null && css.length() > 0; }
    }

    private static WordRunStyle readRunStyle(Node run) {
        WordRunStyle style = new WordRunStyle();
        Node props = DocumentArchiveUtils.firstDirectChildByLocalName(run, "rPr");
        if (props != null) {
            style.bold = DocumentArchiveUtils.firstDirectChildByLocalName(props, "b") != null;
            style.italic = DocumentArchiveUtils.firstDirectChildByLocalName(props, "i") != null;
            style.underline = DocumentArchiveUtils.firstDirectChildByLocalName(props, "u") != null;
            StringBuilder css = new StringBuilder();
            Node color = DocumentArchiveUtils.firstDirectChildByLocalName(props, "color");
            String colorVal = DocumentArchiveUtils.attr(color, "w:val", "val");
            if (colorVal != null && colorVal.matches("[0-9A-Fa-f]{6}")) css.append("color:#").append(colorVal).append(";");
            Node sz = DocumentArchiveUtils.firstDirectChildByLocalName(props, "sz");
            String szVal = DocumentArchiveUtils.attr(sz, "w:val", "val");
            if (szVal != null) {
                try {
                    double halfPoints = Double.parseDouble(szVal);
                    double px = Math.max(8.0, halfPoints / 2.0 * 1.3333);
                    css.append("font-size:").append(String.format(Locale.US, "%.1f", px)).append("px;");
                } catch (Exception ignored) {}
            }
            style.css = css.toString();
        }
        return style;
    }

    private static String escapeWordText(Node textNode) {
        String raw = textNode.getTextContent();
        if (raw == null) return "";
        String escaped = DocumentArchiveUtils.escapeHtml(raw).replace("  ", " &nbsp;");
        if (raw.startsWith(" ")) escaped = "&nbsp;" + escaped.substring(1);
        if (raw.endsWith(" ") && escaped.length() > 0) escaped = escaped.substring(0, escaped.length() - 1) + "&nbsp;";
        return escaped;
    }

    private static boolean isPageBreakNode(Node node) {
        if (node == null) return false;
        if (!"br".equals(node.getLocalName()) && !"w:br".equals(node.getNodeName())) return false;
        String type = DocumentArchiveUtils.attr(node, "w:type", "type");
        return "page".equalsIgnoreCase(type);
    }

    private static String imageSourceForBlip(Node blip, Map<String, String> wordRelationships, String localHost) {
        String rid = DocumentArchiveUtils.attr(blip, "r:embed", "embed");
        if (rid == null) rid = DocumentArchiveUtils.attr(blip, "r:link", "link");
        if (rid == null) return null;
        String target = wordRelationships.get(rid);
        if (target == null) return null;
        return "https://" + localHost + "/" + target;
    }
}
