package com.readwide.manager;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Bounded, Android-free parser for the basic EPUB 3 Media Overlays grammar.
 *
 * <p>The caller supplies the SMIL path obtained from an OPF manifest item's
 * {@code media-overlay} attribute. This class deliberately does not discover or
 * scan every {@code .smil} entry in the archive: unreferenced SMIL resources may
 * be partial publisher artifacts and are not part of the publication's active
 * media-overlay graph.</p>
 *
 * <p>The supported timing shape is a document-order sequence of {@code par}
 * elements containing one local {@code text} reference and one local
 * {@code audio} reference with a finite clip range. More advanced SMIL timing
 * semantics (parallel audio streams, repeats, event timing, and remote media)
 * remain outside this small parser.</p>
 */
final class EpubSmilParser {
    private static final long MAX_SMIL_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_CUES = 100_000;
    private static final Pattern TIME_COUNT = Pattern.compile(
            "^([0-9]+(?:\\.[0-9]+)?)(h|min|s|ms)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URI_SCHEME = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9+.-]*:");

    private EpubSmilParser() {
    }

    static final class Cue {
        final String parId;
        final String textPath;
        final String textFragment;
        final String audioPath;
        final long clipBeginMs;
        final long clipEndMs;

        private Cue(String parId,
                    String textPath,
                    String textFragment,
                    String audioPath,
                    long clipBeginMs,
                    long clipEndMs) {
            this.parId = valueOrEmpty(parId);
            this.textPath = valueOrEmpty(textPath);
            this.textFragment = valueOrEmpty(textFragment);
            this.audioPath = valueOrEmpty(audioPath);
            this.clipBeginMs = clipBeginMs;
            this.clipEndMs = clipEndMs;
        }

        long durationMs() {
            return clipEndMs - clipBeginMs;
        }
    }

    static final class Timeline {
        final String smilPath;
        final List<Cue> cues;

        private Timeline(String smilPath, List<Cue> cues) {
            this.smilPath = valueOrEmpty(smilPath);
            this.cues = Collections.unmodifiableList(new ArrayList<>(cues));
        }

        boolean isEmpty() {
            return cues.isEmpty();
        }
    }

    /**
     * Parses exactly one OPF-linked SMIL entry. Invalid individual {@code par}
     * elements are skipped so one malformed cue does not disable the rest of the
     * narration. A missing, oversized, or malformed SMIL document yields an
     * {@link IOException}; callers can then treat that spine page as having no
     * usable overlay.
     */
    static Timeline parse(ZipFile zip, String linkedSmilPath) throws IOException {
        if (zip == null) throw new IOException("EPUB archive is unavailable");
        String smilPath = normalizeLinkedPath(linkedSmilPath);
        if (smilPath.isEmpty()) throw new IOException("SMIL path is empty or external");

        ZipEntry smilEntry = zip.getEntry(smilPath);
        if (smilEntry == null || smilEntry.isDirectory()) {
            throw new IOException("Linked SMIL entry is missing: " + smilPath);
        }
        long declaredSize = smilEntry.getSize();
        if (declaredSize > MAX_SMIL_BYTES) {
            throw new IOException("Linked SMIL entry exceeds size limit");
        }

        final Document document;
        try (InputStream raw = zip.getInputStream(smilEntry);
             InputStream bounded = new SizeLimitedInputStream(raw, MAX_SMIL_BYTES)) {
            document = DocumentArchiveUtils.secureDocumentBuilder().parse(bounded);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to parse linked SMIL entry", e);
        }

        ArrayList<Cue> cues = new ArrayList<>();
        NodeList pars = document.getElementsByTagNameNS("*", "par");
        if (pars.getLength() == 0) pars = document.getElementsByTagName("par");
        for (int i = 0; i < pars.getLength() && cues.size() < MAX_CUES; i++) {
            Cue cue = parsePar(zip, smilPath, pars.item(i));
            if (cue != null) cues.add(cue);
        }
        return new Timeline(smilPath, cues);
    }

    private static Cue parsePar(ZipFile zip, String smilPath, Node par) {
        if (par == null) return null;
        Node text = firstDescendantByLocalName(par, "text");
        Node audio = firstDescendantByLocalName(par, "audio");
        if (text == null || audio == null) return null;

        ResourceReference textRef = resolveReference(
                zip, smilPath, DocumentArchiveUtils.attr(text, "src", "src"), true);
        ResourceReference audioRef = resolveReference(
                zip, smilPath, DocumentArchiveUtils.attr(audio, "src", "src"), false);
        if (textRef == null || audioRef == null || !isSupportedAudioPath(audioRef.path)) {
            return null;
        }

        String beginValue = DocumentArchiveUtils.attr(audio, "clipBegin", "clipBegin");
        String endValue = DocumentArchiveUtils.attr(audio, "clipEnd", "clipEnd");
        long beginMs = beginValue == null || beginValue.trim().isEmpty()
                ? 0L : parseClockMillis(beginValue);
        long endMs = parseClockMillis(endValue);
        if (beginMs < 0L || endMs <= beginMs) return null;

        return new Cue(
                DocumentArchiveUtils.attr(par, "id", "id"),
                textRef.path,
                textRef.fragment,
                audioRef.path,
                beginMs,
                endMs);
    }

    /**
     * Parses SMIL full/partial clock values and time-count values. Returns -1 for
     * malformed, negative, non-finite, or overflowing input.
     */
    static long parseClockMillis(String rawValue) {
        if (rawValue == null) return -1L;
        String value = rawValue.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("npt=")) value = value.substring(4).trim();
        if (value.isEmpty() || value.startsWith("-") || value.startsWith("+")) return -1L;
        try {
            if (value.indexOf(':') >= 0) {
                String[] parts = value.split(":", -1);
                if (parts.length != 2 && parts.length != 3) return -1L;
                BigDecimal totalSeconds = BigDecimal.ZERO;
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].isEmpty()) return -1L;
                    BigDecimal component = new BigDecimal(parts[i]);
                    if (component.signum() < 0) return -1L;
                    if (i > 0 && component.compareTo(BigDecimal.valueOf(60L)) >= 0) {
                        return -1L;
                    }
                    totalSeconds = totalSeconds.multiply(BigDecimal.valueOf(60L)).add(component);
                }
                return secondsToMillis(totalSeconds);
            }

            Matcher matcher = TIME_COUNT.matcher(value);
            if (!matcher.matches()) return -1L;
            BigDecimal amount = new BigDecimal(matcher.group(1));
            String unit = matcher.group(2);
            if (unit == null || unit.isEmpty() || "s".equalsIgnoreCase(unit)) {
                return secondsToMillis(amount);
            }
            if ("ms".equalsIgnoreCase(unit)) {
                return toRoundedLong(amount);
            }
            if ("min".equalsIgnoreCase(unit)) {
                return secondsToMillis(amount.multiply(BigDecimal.valueOf(60L)));
            }
            if ("h".equalsIgnoreCase(unit)) {
                return secondsToMillis(amount.multiply(BigDecimal.valueOf(3600L)));
            }
        } catch (ArithmeticException | NumberFormatException ignored) {
            return -1L;
        }
        return -1L;
    }

    private static long secondsToMillis(BigDecimal seconds) {
        if (seconds == null || seconds.signum() < 0) return -1L;
        return toRoundedLong(seconds.multiply(BigDecimal.valueOf(1000L)));
    }

    private static long toRoundedLong(BigDecimal milliseconds) {
        if (milliseconds == null || milliseconds.signum() < 0) return -1L;
        BigDecimal rounded = milliseconds.setScale(0, RoundingMode.HALF_UP);
        if (rounded.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) return -1L;
        return rounded.longValueExact();
    }

    private static ResourceReference resolveReference(ZipFile zip,
                                                      String smilPath,
                                                      String rawReference,
                                                      boolean keepFragment) {
        if (rawReference == null) return null;
        String reference = rawReference.trim();
        if (reference.isEmpty() || isExternalReference(reference)) return null;

        int hash = reference.indexOf('#');
        String pathPart = hash >= 0 ? reference.substring(0, hash) : reference;
        String fragmentPart = keepFragment && hash >= 0
                ? reference.substring(hash + 1) : "";
        int query = pathPart.indexOf('?');
        if (query >= 0) pathPart = pathPart.substring(0, query);
        if (pathPart.trim().isEmpty()) return null;

        String decodedPath = DocumentArchiveUtils.decodeHref(pathPart);
        if (decodedPath.isEmpty() || isExternalReference(decodedPath)) return null;
        String parent = DocumentArchiveUtils.parentPath(smilPath);
        String resolved = DocumentArchiveUtils.normalizeZipPath(
                parent.isEmpty() ? decodedPath : parent + "/" + decodedPath);
        if (resolved.isEmpty()) return null;

        ZipEntry target = zip.getEntry(resolved);
        if (target == null || target.isDirectory()) return null;
        String fragment = keepFragment
                ? DocumentArchiveUtils.decodeHref(fragmentPart) : "";
        return new ResourceReference(resolved, fragment);
    }

    private static boolean isExternalReference(String reference) {
        if (reference == null) return true;
        String value = reference.trim();
        return value.startsWith("//") || URI_SCHEME.matcher(value).find();
    }

    private static String normalizeLinkedPath(String linkedSmilPath) {
        if (linkedSmilPath == null || isExternalReference(linkedSmilPath)) return "";
        String decoded = DocumentArchiveUtils.decodeHref(linkedSmilPath.trim());
        if (decoded.isEmpty() || isExternalReference(decoded)) return "";
        return DocumentArchiveUtils.normalizeZipPath(decoded);
    }

    private static boolean isSupportedAudioPath(String path) {
        String lower = valueOrEmpty(path).toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".mp4")
                || lower.endsWith(".m4a") || lower.endsWith(".aac")
                || lower.endsWith(".wav") || lower.endsWith(".ogg")
                || lower.endsWith(".oga") || lower.endsWith(".flac")
                || lower.endsWith(".opus") || lower.endsWith(".webm");
    }

    private static Node firstDescendantByLocalName(Node parent, String wanted) {
        NodeList children = parent != null ? parent.getChildNodes() : null;
        if (children == null) return null;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child == null || child.getNodeType() != Node.ELEMENT_NODE) continue;
            String local = child.getLocalName();
            String name = local != null ? local : child.getNodeName();
            int colon = name != null ? name.indexOf(':') : -1;
            if (colon >= 0) name = name.substring(colon + 1);
            if (wanted.equals(name)) return child;
            Node nested = firstDescendantByLocalName(child, wanted);
            if (nested != null) return nested;
        }
        return null;
    }

    private static String valueOrEmpty(String value) {
        return value != null ? value : "";
    }

    private static final class ResourceReference {
        final String path;
        final String fragment;

        ResourceReference(String path, String fragment) {
            this.path = valueOrEmpty(path);
            this.fragment = valueOrEmpty(fragment);
        }
    }

    private static final class SizeLimitedInputStream extends FilterInputStream {
        private final long limit;
        private long read;

        SizeLimitedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) account(1L);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) account(count);
            return count;
        }

        private void account(long count) throws IOException {
            read += count;
            if (read > limit) throw new IOException("Linked SMIL entry exceeds size limit");
        }
    }
}
