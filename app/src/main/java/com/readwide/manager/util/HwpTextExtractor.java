package com.readwide.manager.util;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractor;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.textextractor.TextMarks;

/**
 * Scoped HWP/HWPX text extraction for the document reader.
 *
 * The primary path uses Apache-2.0 dogfoot libraries:
 * - hwplib for HWP 5.x document reading and text extraction
 * - hwpxlib for HWPX package reading and text extraction
 *
 * HWP is intentionally text-first.  The normal path uses hwplib's extraction-only
 * reader so large images/embedded binaries are not pulled into memory just to show
 * document text.  If that lightweight path cannot handle a document variant such
 * as distribution/ViewText HWP, a full-reader fallback is tried before failing.
 *
 * The fallback XML/record helpers are intentionally limited and exist only for
 * synthetic tests and damaged/minimal HWPX packages. They are not the main HWP
 * implementation and should not be expanded into a layout renderer.
 */
public final class HwpTextExtractor {
    private static final long MAX_HWP_FILE_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_TEXT_CHARS = 12L * 1024L * 1024L;
    private static final long MAX_HWPX_FALLBACK_XML_BYTES = 8L * 1024L * 1024L;
    private static final int HWP_TAG_PARA_TEXT = 67;

    private HwpTextExtractor() {}

    public static boolean isHwpFileName(String fileName) {
        String lower = FileUtils.normalizeDisplayFileName(fileName).toLowerCase(Locale.ROOT);
        return lower.endsWith(".hwp") || lower.endsWith(".hwpx");
    }

    public static String read(File file) throws IOException {
        if (file == null) throw new IOException("No HWP file supplied");
        String lower = FileUtils.normalizeDisplayFileName(file.getName()).toLowerCase(Locale.ROOT);
        if (lower.endsWith(".hwpx")) return readHwpx(file);
        if (lower.endsWith(".hwp")) return readBinaryHwp(file);
        throw new IOException("Unsupported HWP file extension");
    }

    private static String readBinaryHwp(File file) throws IOException {
        enforceInputSize(file, "HWP");

        IOException streamingFailure = null;
        try {
            return readBinaryHwpStreaming(file);
        } catch (BoundedTextLimitException e) {
            throw new IOException("Extracted HWP text exceeds safety limit", e);
        } catch (IOException e) {
            streamingFailure = e;
        } catch (Exception | LinkageError e) {
            streamingFailure = classifyHwpFailure("HWP", e);
        }

        if (isPasswordFailure(streamingFailure)) throw streamingFailure;

        try {
            return readBinaryHwpFullFallback(file);
        } catch (BoundedTextLimitException e) {
            IOException out = new IOException("Extracted HWP text exceeds safety limit", e);
            out.addSuppressed(streamingFailure);
            throw out;
        } catch (IOException e) {
            e.addSuppressed(streamingFailure);
            throw e;
        } catch (Exception | LinkageError e) {
            IOException out = classifyHwpFailure("HWP", e);
            out.addSuppressed(streamingFailure);
            throw out;
        }
    }

    private static String readBinaryHwpStreaming(File file) throws Exception {
        BoundedTextCollector collector = new BoundedTextCollector();
        HWPReader.forExtractText(file.getAbsolutePath(), collector::appendParagraph,
                TextExtractMethod.InsertControlTextBetweenParagraphText);
        return sanitizeAndRequireText(collector.finish(), "HWP");
    }

    private static String readBinaryHwpFullFallback(File file) throws Exception {
        HWPFile hwpFile = HWPReader.fromFile(file);
        if (hwpFile == null) throw new IOException("HWP reader returned no document");
        String text = TextExtractor.extract(hwpFile,
                TextExtractMethod.InsertControlTextBetweenParagraphText);
        return sanitizeAndRequireText(text, "HWP");
    }

    private static String readHwpx(File file) throws IOException {
        enforceInputSize(file, "HWPX");
        IOException libraryFailure = null;
        try {
            HWPXFile hwpxFile = HWPXReader.fromFile(file, true);
            if (hwpxFile == null) throw new IOException("HWPX reader returned no document");
            String text = kr.dogfoot.hwpxlib.tool.textextractor.TextExtractor.extract(
                    hwpxFile,
                    kr.dogfoot.hwpxlib.tool.textextractor.TextExtractMethod.InsertControlTextBetweenParagraphText,
                    true,
                    defaultHwpxTextMarks());
            return sanitizeAndRequireText(text, "HWPX");
        } catch (IOException e) {
            libraryFailure = e;
        } catch (Exception | LinkageError e) {
            libraryFailure = classifyHwpFailure("HWPX", e);
        }

        if (isPasswordFailure(libraryFailure)) throw libraryFailure;

        // Last-resort fallback for very small/synthetic HWPX ZIPs. Real HWPX
        // files should be handled by hwpxlib so manifest order, paragraph heads,
        // controls, and table text are treated consistently.
        try {
            return sanitizeAndRequireText(readHwpxSectionXmlFallback(file), "HWPX");
        } catch (IOException fallbackFailure) {
            if (libraryFailure != null) {
                libraryFailure.addSuppressed(fallbackFailure);
                throw libraryFailure;
            }
            throw fallbackFailure;
        }
    }

    private static TextMarks defaultHwpxTextMarks() {
        return new TextMarks()
                .paraSeparatorAnd("\n\n")
                .lineBreakAnd("\n")
                .tabAnd("\t")
                .fieldStartAnd("")
                .fieldEndAnd("")
                .tableStartAnd("\n")
                .tableEndAnd("\n")
                .tableRowSeparatorAnd("\n")
                .tableCellSeparatorAnd("\t")
                .containerStartAnd("")
                .containerEndAnd("")
                .lineStartAnd("")
                .lineEndAnd("")
                .rectangleStartAnd("")
                .rectangleEndAnd("")
                .ellipseStartAnd("")
                .ellipseEndAnd("")
                .arcStartAnd("")
                .arcEndAnd("")
                .polygonStartAnd("")
                .polygonEndAnd("")
                .curveStartAnd("")
                .curveEndAnd("")
                .connectLineStartAnd("")
                .connectLineEndAnd("")
                .textArtStartAnd("")
                .textArtEndAnd("");
    }

    private static void enforceInputSize(File file, String label) throws IOException {
        long length = file.length();
        if (length <= 0) throw new IOException("Empty " + label + " file");
        if (length > MAX_HWP_FILE_BYTES) {
            throw new IOException(label + " file is too large for document text extraction");
        }
    }

    private static String sanitizeAndRequireText(String text, String label) throws IOException {
        String sanitized = sanitizeExtractedText(text);
        enforceTextLimit(sanitized);
        if (sanitized.trim().isEmpty()) {
            throw new IOException(label + " file contains no extractable text");
        }
        return sanitized;
    }

    private static IOException classifyHwpFailure(String label, Throwable e) {
        String message = e != null && e.getMessage() != null ? e.getMessage() : "";
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("password") || lower.contains("encrypt") || lower.contains("encrypted")) {
            return new IOException("Encrypted/password-protected " + label + " files are not supported.", e);
        }
        if (lower.contains("distribution files are not supported")) {
            return new IOException("Distribution/protected " + label + " files require the full text fallback path.", e);
        }
        if (lower.contains("not_hwp") || lower.contains("not hwpx")
                || lower.contains("not hwp") || lower.contains("not supported")) {
            return new IOException("Unsupported " + label + " document variant.", e);
        }
        if (e instanceof LinkageError) {
            return new IOException(label + " support dependency is unavailable or incompatible.", e);
        }
        return new IOException("Could not extract text from " + label + " file." + appendCause(message), e);
    }

    private static boolean isPasswordFailure(IOException e) {
        if (e == null || e.getMessage() == null) return false;
        String lower = e.getMessage().toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("encrypted");
    }

    private static String appendCause(String message) {
        if (message == null || message.trim().isEmpty()) return "";
        return " Cause: " + message.trim();
    }

    private static String readHwpxSectionXmlFallback(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            List<String> sectionPaths = findHwpxSectionPaths(zip);
            if (sectionPaths.isEmpty()) {
                throw new IOException("No readable HWPX section XML found");
            }

            StringBuilder out = new StringBuilder();
            for (String path : sectionPaths) {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null || entry.isDirectory()) continue;
                if (entry.getSize() > MAX_HWPX_FALLBACK_XML_BYTES) {
                    throw new IOException("HWPX fallback section XML exceeds safety limit: " + path);
                }
                String text;
                try (InputStream is = zip.getInputStream(entry)) {
                    byte[] xml = readAllBytesWithLimit(is, MAX_HWPX_FALLBACK_XML_BYTES);
                    text = extractTextFromHwpxXml(new ByteArrayInputStream(xml));
                }
                appendSectionText(out, text);
                enforceTextLimit(out);
            }
            return out.toString();
        }
    }

    private static byte[] readAllBytesWithLimit(InputStream is, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = is.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) throw new IOException("HWPX fallback XML exceeds safety limit");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static List<String> findHwpxSectionPaths(ZipFile zip) {
        ArrayList<String> sections = new ArrayList<>();
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry == null || entry.isDirectory()) continue;
            String name = entry.getName();
            String lower = name.toLowerCase(Locale.ROOT).replace('\\', '/');
            if (!lower.endsWith(".xml")) continue;
            if (lower.matches("(^|.*/)contents/(section|sections/section)[0-9]+\\.xml")) {
                sections.add(name);
            }
        }
        if (sections.isEmpty()) {
            entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;
                String lower = entry.getName().toLowerCase(Locale.ROOT).replace('\\', '/');
                if (lower.endsWith(".xml") && lower.contains("section")) sections.add(entry.getName());
            }
        }
        Collections.sort(sections, HwpTextExtractor::compareSectionPath);
        return sections;
    }

    private static int compareSectionPath(String a, String b) {
        int ai = trailingNumber(a);
        int bi = trailingNumber(b);
        if (ai != bi) return Integer.compare(ai, bi);
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    }

    private static int trailingNumber(String path) {
        if (path == null) return Integer.MAX_VALUE;
        int end = path.lastIndexOf('.');
        if (end < 0) end = path.length();
        int start = end;
        while (start > 0 && Character.isDigit(path.charAt(start - 1))) start--;
        if (start == end) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(path.substring(start, end));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    static String extractTextFromHwpxXml(InputStream is) throws IOException {
        try {
            Document doc = secureDocumentBuilder().parse(is);
            StringBuilder out = new StringBuilder();
            appendHwpxNodeText(doc.getDocumentElement(), out);
            return out.toString();
        } catch (Exception e) {
            throw new IOException("Cannot parse HWPX section XML", e);
        }
    }

    private static void appendHwpxNodeText(Node node, StringBuilder out) {
        if (node == null) return;
        String local = node.getLocalName();
        String name = node.getNodeName();
        if ("t".equals(local) || "hp:t".equals(name) || "text".equals(local)) {
            String text = node.getTextContent();
            if (text != null) out.append(text);
            return;
        }
        if ("tab".equals(local) || "hp:tab".equals(name)) {
            out.append('\t');
            return;
        }
        if ("lineBreak".equals(local) || "br".equals(local)
                || "hp:lineBreak".equals(name) || "hp:br".equals(name)) {
            out.append('\n');
            return;
        }

        boolean paragraph = "p".equals(local) || "hp:p".equals(name);
        int before = out.length();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            appendHwpxNodeText(children.item(i), out);
        }
        if (paragraph && out.length() > before && !endsWithNewline(out)) out.append('\n');
    }

    static String extractTextFromHwpSection(byte[] section) throws IOException {
        if (section == null || section.length == 0) return "";
        StringBuilder out = new StringBuilder();
        int offset = 0;
        while (offset + 4 <= section.length) {
            int header = u32ToInt(section, offset);
            offset += 4;
            int tag = header & 0x3FF;
            int size = (header >>> 20) & 0xFFF;
            if (size == 0xFFF) {
                if (offset + 4 > section.length) break;
                size = u32ToInt(section, offset);
                offset += 4;
            }
            if (size < 0 || offset + size > section.length) break;
            if (tag == HWP_TAG_PARA_TEXT && size > 0) {
                String text = new String(section, offset, size, StandardCharsets.UTF_16LE)
                        .replace('\u0000', ' ');
                if (!text.trim().isEmpty()) {
                    if (out.length() > 0 && !endsWithNewline(out)) out.append('\n');
                    out.append(text.trim());
                    if (!endsWithNewline(out)) out.append('\n');
                }
            }
            offset += size;
        }
        return out.toString();
    }

    private static int u32ToInt(byte[] data, int off) {
        return (data[off] & 0xFF)
                | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16)
                | ((data[off + 3] & 0xFF) << 24);
    }

    private static DocumentBuilder secureDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // Android XML parser implementations vary; best-effort hardening.
        }
    }

    private static void appendSectionText(StringBuilder out, String text) {
        String clean = sanitizeExtractedText(text);
        if (clean.isEmpty()) return;
        if (out.length() > 0 && !endsWithDoubleNewline(out)) out.append("\n\n");
        out.append(clean);
    }

    static String sanitizeExtractedTextForTest(String text) {
        return sanitizeExtractedText(text);
    }

    private static String sanitizeExtractedText(String text) {
        if (text == null || text.isEmpty()) return "";
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(normalized.length());
        int consecutiveNewlines = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '\n') {
                consecutiveNewlines++;
                if (consecutiveNewlines <= 3) out.append('\n');
                continue;
            }
            consecutiveNewlines = 0;
            if (ch == '\t') {
                out.append('\t');
                continue;
            }
            if (Character.isISOControl(ch)) {
                continue;
            }
            out.append(ch);
        }
        return out.toString().trim();
    }

    private static void enforceTextLimit(CharSequence text) throws IOException {
        if (text != null && text.length() > MAX_EXTRACTED_TEXT_CHARS) {
            throw new IOException("Extracted HWP text exceeds safety limit");
        }
    }

    private static boolean endsWithNewline(StringBuilder sb) {
        return sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n';
    }

    private static boolean endsWithDoubleNewline(StringBuilder sb) {
        return sb.length() >= 2
                && sb.charAt(sb.length() - 1) == '\n'
                && sb.charAt(sb.length() - 2) == '\n';
    }

    private static final class BoundedTextCollector {
        private final StringBuilder out = new StringBuilder();

        void appendParagraph(String text) {
            String clean = sanitizeExtractedText(text);
            if (clean.isEmpty()) return;
            if (out.length() > 0 && !endsWithDoubleNewline(out)) out.append("\n\n");
            out.append(clean);
            if (out.length() > MAX_EXTRACTED_TEXT_CHARS) {
                throw new BoundedTextLimitException();
            }
        }

        String finish() {
            return out.toString();
        }
    }

    private static final class BoundedTextLimitException extends RuntimeException {
        BoundedTextLimitException() {
            super("Extracted HWP text exceeds safety limit");
        }
    }
}
