package com.readwide.manager.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.Html;
import android.text.Spanned;
import android.util.Log;

import com.readwide.manager.model.TextChunk;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.text.Normalizer;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * File utilities including broad text encoding detection.
 *
 * Supported text families:
 * - Unicode: UTF-8, UTF-8 BOM, UTF-16LE/BE, UTF-16 BOM, UTF-32LE/BE BOM, BOM-less UTF-16LE/BE heuristic
 * - Android ICU-assisted detection and Mozilla/JUniversalChardet-assisted detection where available
 * - Western/Central European/Turkish/Baltic single-byte encodings: Windows-1252/1250/1254/1257 and ISO-8859 variants
 * - Greek/Cyrillic/Hebrew/Arabic/Thai legacy encodings: Windows-1253/1251/1255/1256/874 and ISO/KOI8 variants
 * - Korean legacy: MS949 / windows-949 / CP949 / EUC-KR
 * - Japanese legacy: Shift_JIS / windows-31j, EUC-JP; ISO-2022-JP only when strict 7-bit ISO-2022 shifts are present
 * - Korean legacy: ISO-2022-KR only when strict 7-bit designation plus SO/SI shifts are present
 * - Chinese legacy: GB18030, GBK, Big5; HZ-GB-2312 only when strict 7-bit HZ escapes are present
 *
 * Bad or unmappable bytes are decoded with replacement instead of crashing.
 */
public class FileUtils {
    private static final String TAG = "FileUtils";

    public static class EncodingResult {
        public static final int HIGH_CONFIDENCE = 3;
        public static final int MEDIUM_CONFIDENCE = 2;
        public static final int LOW_CONFIDENCE = 1;

        public final String charsetName;
        public final int confidence;
        public final String source;
        public final String family;

        EncodingResult(String charsetName, int confidence, String source, String family) {
            this.charsetName = charsetName;
            this.confidence = confidence;
            this.source = source;
            this.family = family;
        }

        public boolean isHighConfidence() {
            return confidence >= HIGH_CONFIDENCE;
        }

        public String confidenceLabel() {
            if (confidence >= HIGH_CONFIDENCE) return "high";
            if (confidence >= MEDIUM_CONFIDENCE) return "medium";
            return "low";
        }

        public String displayLabel() {
            return charsetName + " (auto, " + confidenceLabel() + ")";
        }
    }

    public static String detectEncoding(File file) {
        return TextEncodingDetector.detectEncoding(file);
    }

    public static EncodingResult detectEncodingDetailed(File file) {
        return TextEncodingDetector.detectEncodingDetailed(file);
    }

    public static String detectEncoding(InputStream inputStream) {
        return TextEncodingDetector.detectEncoding(inputStream);
    }

    public static String[] getManualTextEncodingOptions() {
        return TextEncodingDetector.getManualTextEncodingOptions();
    }

    public static String normalizeManualEncodingName(String name) {
        return TextEncodingDetector.normalizeManualEncodingName(name);
    }

    public static String readTextFile(File file) throws IOException {
        return TextEncodingDetector.readTextFile(file);
    }

    public static String readTextFile(File file, String encoding) throws IOException {
        return TextEncodingDetector.readTextFile(file, encoding);
    }

    public static List<TextChunk> readTextFileAsChunks(File file, int targetChunkChars) throws IOException {
        return TextEncodingDetector.readTextFileAsChunks(file, targetChunkChars);
    }

    public static String readTextFromUri(Context context, Uri uri) throws IOException {
        return TextEncodingDetector.readTextFromUri(context, uri);
    }





    public static int clampToSurrogateSafeStart(String text, int index) {
        return TextStringUtils.clampToSurrogateSafeStart(text, index);
    }

    public static int clampToSurrogateSafeEnd(String text, int index) {
        return TextStringUtils.clampToSurrogateSafeEnd(text, index);
    }

    public static String safeSubstring(String text, int start, int end) {
        return TextStringUtils.safeSubstring(text, start, end);
    }

    public static String enforceTextPresentationSelectors(String text) {
        return TextStringUtils.enforceTextPresentationSelectors(text);
    }


    /**
     * Get filename from URI.
     */
    public static String getFileNameFromUri(Context context, Uri uri) {
        String result = null;

        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            } catch (Exception e) {
                // A hostile or buggy content provider can throw SecurityException,
                // IllegalArgumentException, or a crashy RuntimeException from query()
                // (ACTION_VIEW is exported). Fall back to the URI's last path segment.
                result = null;
            }
        }

        if (result == null) result = uri.getLastPathSegment();
        return normalizeDisplayFileName(result);
    }

    /**
     * Copy content URI to a local file and return the file.
     */
    /** Cleanup target (not a hard cap) for the inactive opened_files cache, plus a max
     *  age. Pruned opportunistically before and after each copy; the just-opened file
     *  is kept, so the live total can briefly exceed this budget (a single external copy
     *  is separately bounded by MAX_OPENED_URI_COPY_BYTES). */
    private static final long MAX_OPENED_FILES_CACHE_BYTES = 1024L * 1024L * 1024L;
    private static final long MAX_OPENED_FILES_CACHE_AGE_MS = 30L * 24L * 60L * 60L * 1000L;
    /** Hard cap for one external ACTION_VIEW/OpenDocument URI copied into opened_files;
     *  external providers (browser, messenger, file manager, document provider) may hand
     *  Readwide a large local document. The inactive opened_files cache budget
     *  (MAX_OPENED_FILES_CACHE_BYTES) is lower and is a cleanup target, not a hard cap:
     *  older entries are pruned before and after the copy, but the just-opened file is
     *  preserved so the requested document can still be read. Internal file-manager moves
     *  use FileSystemOps and are not affected. */
    private static final long MAX_OPENED_URI_COPY_BYTES = 2L * 1024L * 1024L * 1024L;
    /** Cap per EPUB chapter read during text extraction, so a crafted chapter can't OOM. */
    private static final long MAX_EPUB_CHAPTER_BYTES = 32L * 1024L * 1024L;

    public static File copyUriToLocal(Context context, Uri uri, String fileName) throws IOException {
        File cacheDir = new File(context.getCacheDir(), "opened_files");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Cannot create opened_files cache");
        }
        pruneOpenedFilesCache(cacheDir);

        // The display name comes from a possibly-untrusted content provider (this app
        // exports ACTION_VIEW), so reduce it to a bare, safe filename. Each distinct
        // source URI gets its own subdirectory keyed by a hash of the URI, so two
        // different files sharing a display name (e.g. book.pdf from two folders) do
        // not collide on one cache file. The visible filename stays the original, so
        // the viewer title is unaffected. Verify the path stays inside opened_files.
        String safeName = safeCacheFileName(fileName, "opened_file");
        File uriDir = new File(cacheDir, uriCacheKey(uri));
        if (!uriDir.exists() && !uriDir.mkdirs()) {
            throw new IOException("Cannot create URI cache directory");
        }
        File localFile = new File(uriDir, safeName);
        String root = cacheDir.getCanonicalPath() + File.separator;
        if (!localFile.getCanonicalPath().startsWith(root)) {
            throw new IOException("Unsafe cache file name");
        }

        boolean success = false;
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(localFile)) {
            if (is == null) throw new IOException("Cannot open URI");

            byte[] buffer = new byte[8192];
            int bytesRead;
            long copied = 0L;

            while ((bytesRead = is.read(buffer)) != -1) {
                copied += bytesRead;
                if (copied > MAX_OPENED_URI_COPY_BYTES) {
                    throw new IOException("External file exceeds size limit");
                }
                fos.write(buffer, 0, bytesRead);
            }
            success = true;
        } finally {
            // A failed, aborted, or over-limit copy must not leave a broken partial
            // file in the cache.
            if (!success) localFile.delete();
        }

        // Re-prune now that the new file exists, so it counts against the total budget
        // and older entries are evicted oldest-first. The just-opened file is preserved
        // even if it alone exceeds the budget (it is about to be read); it will be
        // eligible for eviction on a later open.
        deleteSiblingsInDirectory(uriDir, localFile);
        pruneOpenedFilesCache(cacheDir, uriDir);
        return localFile;
    }

    // Reduce a content-provider display name to a safe single path segment: strip path
    // separators, control characters and any ".." run so it cannot escape the cache
    // directory, fall back when empty, and cap the length.
    private static String safeCacheFileName(String name, String fallback) {
        String n = normalizeDisplayFileName(name);
        n = n.replace('\\', '_').replace('/', '_');
        n = n.replaceAll("\\p{Cntrl}", "_");
        while (n.contains("..")) n = n.replace("..", "_");
        n = n.trim();
        if (n.isEmpty()) n = fallback;
        if (n.length() > 120) {
            // Preserve a short trailing extension when truncating, so the viewer title
            // keeps the right type suffix. ".." has already been removed above.
            int dot = n.lastIndexOf('.');
            if (dot > 0 && n.length() - dot <= 10) {
                String ext = n.substring(dot);
                n = n.substring(0, Math.max(1, 120 - ext.length())) + ext;
            } else {
                n = n.substring(0, 120);
            }
        }
        return n;
    }

    // Stable per-URI cache subdirectory name (hash of the URI), so distinct sources
    // with the same display name don't share one cache file.
    private static String uriCacheKey(Uri uri) {
        String s = uri != null ? uri.toString() : "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    // Opportunistic cache hygiene: drop opened_files entries (per-URI subdirectories)
    // older than the max age, then enforce the total-size budget oldest-first. Runs
    // before a new copy, so the entry about to be written is never the one pruned.
    private static void pruneOpenedFilesCache(File cacheDir) {
        pruneOpenedFilesCache(cacheDir, null);
    }

    // When 'keep' is non-null (the just-opened file's per-URI subdirectory), it is
    // never evicted even if it alone exceeds the size budget, since it is about to be
    // read. Other entries are still cleaned oldest-first to re-enforce the budget.
    private static void pruneOpenedFilesCache(File cacheDir, File keep) {
        File[] entries = cacheDir.listFiles();
        if (entries == null || entries.length == 0) return;
        String keepPath = keep != null ? keep.getAbsolutePath() : null;
        Arrays.sort(entries, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long now = System.currentTimeMillis();
        long total = 0L;
        for (File e : entries) {
            if (e == null) continue;
            boolean isKeep = keepPath != null && keepPath.equals(e.getAbsolutePath());
            if (!isKeep && now - e.lastModified() > MAX_OPENED_FILES_CACHE_AGE_MS) {
                deleteRecursive(e);
            } else {
                total += sizeOf(e);
            }
        }
        if (total <= MAX_OPENED_FILES_CACHE_BYTES) return;
        for (File e : entries) {
            if (total <= MAX_OPENED_FILES_CACHE_BYTES) break;
            if (e == null || !e.exists()) continue;
            if (keepPath != null && keepPath.equals(e.getAbsolutePath())) continue;
            long sz = sizeOf(e);
            deleteRecursive(e);
            total -= sz;
        }
    }

    private static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        File[] kids = f.listFiles();
        if (kids == null) return 0L;
        long s = 0L;
        for (File k : kids) if (k != null) s += sizeOf(k);
        return s;
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) if (k != null) deleteRecursive(k);
        }
        f.delete();
    }

    // After a successful copy the per-URI subdirectory should hold only the current
    // file; drop any stale siblings (e.g. a prior copy under a different display name,
    // or a leftover partial) so the keep-the-whole-dir post-copy prune doesn't preserve
    // dead files. Compares canonical paths, falling back to absolute on failure.
    private static void deleteSiblingsInDirectory(File dir, File keepFile) {
        File[] kids = dir.listFiles();
        if (kids == null || kids.length == 0) return;
        String keepPath;
        try {
            keepPath = keepFile.getCanonicalPath();
        } catch (IOException e) {
            keepPath = keepFile.getAbsolutePath();
        }
        for (File kid : kids) {
            if (kid == null) continue;
            String path;
            try {
                path = kid.getCanonicalPath();
            } catch (IOException e) {
                path = kid.getAbsolutePath();
            }
            if (!keepPath.equals(path)) {
                deleteRecursive(kid);
            }
        }
    }


    /**
     * Read any app-supported readable file and normalize it into plain text.
     * This keeps the existing reader, paging, search, recent-file, and bookmark logic
     * shared across TXT, PDF, EPUB, and Word documents.
     */
    public static String readReadableFile(Context context, File file) throws IOException {
        String lower = lowerName(file != null ? file.getName() : null);

        if (lower.endsWith(".pdf")) {
            throw new IOException("PDF files use the original-page PDF viewer, not text extraction.");
        }
        if (lower.endsWith(".epub")) {
            return readEpubFile(file);
        }
        if (isHwpFileName(lower)) {
            return HwpTextExtractor.read(file);
        }
        if (isWordFileName(lower)) {
            return readWordFile(file);
        }
        return readTextFile(file);
    }

    /**
     * Human-readable format label used in file info and list subtitles.
     */
    public static String getReadableFileType(String fileName) {
        String lower = lowerName(fileName);
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".epub")) return "EPUB";
        if (isMarkdownFile(lower)) return "Markdown";
        if (isHwpFileName(lower)) return "HWP";
        if (isWordFileName(lower)) return "Word";
        if (isArchiveFile(fileName)) return "Archive";
        if (isImageFile(fileName)) return "Image";
        if (isApkFile(fileName)) return "APK";
        if (isVideoFile(fileName)) return "Video";
        if (isTextFile(fileName)) return "Text";
        return "File";
    }

    public static boolean isSupportedReadableFile(String fileName) {
        return isTextFile(fileName)
                || isPdfFile(fileName)
                || isEpubFile(fileName)
                || isWordFile(fileName)
                || isHwpFile(fileName)
                || isArchiveFile(fileName)
                || isImageFile(fileName);
    }

    public static boolean isVisibleInAllFilesFilter(String fileName) {
        return normalizeDisplayFileName(fileName).length() > 0;
    }

    public static boolean isExternalOpenableFile(String fileName) {
        return isVideoFile(fileName) || isAudioFile(fileName);
    }

    public static boolean isArchiveFile(String fileName) {
        String lower = lowerName(fileName);
        if (lower.endsWith(".001") && lower.length() > 4) {
            return isArchiveFile(fileName.substring(0, fileName.length() - 4));
        }
        if (lower.matches(".*\\.(7z|cb7)\\.\\d{3}$")) {
            return true;
        }
        return lower.endsWith(".zip") || lower.endsWith(".cbz")
                || lower.endsWith(".rar") || lower.endsWith(".cbr")
                || lower.matches(".*\\.r\\d{2,3}$")
                || lower.endsWith(".alz")
                || lower.endsWith(".egg")
                || lower.endsWith(".7z")
                || lower.endsWith(".cb7")
                || lower.endsWith(".tar")
                || lower.endsWith(".cbt")
                || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
                || lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz")
                || lower.endsWith(".tar.xz") || lower.endsWith(".txz")
                || lower.endsWith(".tar.lzma") || lower.endsWith(".tlz")
                || lower.endsWith(".tar.z") || lower.endsWith(".taz")
                || lower.endsWith(".tar.zst") || lower.endsWith(".tzst")
                || lower.endsWith(".tar.lz4")
                || lower.endsWith(".zst")
                || lower.endsWith(".lz4")
                || lower.endsWith(".gz")
                || lower.endsWith(".bz2")
                || lower.endsWith(".xz")
                || lower.endsWith(".lzma")
                || lower.endsWith(".z");
    }


    public static boolean isImageFile(String fileName) {
        String lower = lowerName(fileName);
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".jfif")
                || lower.endsWith(".png")
                || lower.endsWith(".webp")
                || lower.endsWith(".gif")
                || lower.endsWith(".bmp")
                || lower.endsWith(".wbmp")
                || lower.endsWith(".dng")
                || lower.endsWith(".heic")
                || lower.endsWith(".heif")
                || lower.endsWith(".avif");
    }

    public static boolean isApkFile(String fileName) {
        return lowerName(fileName).endsWith(".apk");
    }

    public static boolean isVideoFile(String fileName) {
        String lower = lowerName(fileName);
        return lower.endsWith(".mp4")
                || lower.endsWith(".m4v")
                || lower.endsWith(".mkv")
                || lower.endsWith(".webm")
                || lower.endsWith(".avi")
                || lower.endsWith(".mov")
                || lower.endsWith(".3gp")
                || lower.endsWith(".3gpp")
                || lower.endsWith(".ts")
                || lower.endsWith(".m2ts")
                || lower.endsWith(".mts")
                || lower.endsWith(".wmv")
                || lower.endsWith(".flv")
                || lower.endsWith(".mpg")
                || lower.endsWith(".mpeg")
                || lower.endsWith(".ogv");
    }

    public static boolean isAudioFile(String fileName) {
        String lower = lowerName(fileName);
        return lower.endsWith(".mp3")
                || lower.endsWith(".m4a")
                || lower.endsWith(".m4b")
                || lower.endsWith(".aac")
                || lower.endsWith(".wav")
                || lower.endsWith(".flac")
                || lower.endsWith(".ogg")
                || lower.endsWith(".oga")
                || lower.endsWith(".opus")
                || lower.endsWith(".wma")
                || lower.endsWith(".mid")
                || lower.endsWith(".midi")
                || lower.endsWith(".amr")
                || lower.endsWith(".aiff")
                || lower.endsWith(".aif")
                || lower.endsWith(".ape")
                || lower.endsWith(".mka");
    }

    public static boolean isPdfFile(String fileName) {
        return lowerName(fileName).endsWith(".pdf");
    }

    public static boolean isEpubFile(String fileName) {
        return lowerName(fileName).endsWith(".epub");
    }

    public static boolean isWordFile(String fileName) {
        return isWordFileName(lowerName(fileName));
    }

    public static boolean isHwpFile(String fileName) {
        return isHwpFileName(lowerName(fileName));
    }

    public static boolean isWordOrHwpFile(String fileName) {
        String lower = lowerName(fileName);
        return isWordFileName(lower) || isHwpFileName(lower);
    }

    private static boolean isWordFileName(String lowerName) {
        return lowerName.endsWith(".doc")
                || lowerName.endsWith(".docx")
                || lowerName.endsWith(".docm")
                || lowerName.endsWith(".dotx")
                || lowerName.endsWith(".dotm");
    }

    private static boolean isHwpFileName(String lowerName) {
        return lowerName.endsWith(".hwp") || lowerName.endsWith(".hwpx");
    }

    private static String readWordFile(File file) throws IOException {
        String lower = lowerName(file != null ? file.getName() : null);
        if (lower.endsWith(".doc")) {
            throw new IOException("Legacy binary Word (.doc) files are recognized in the Word filter, but only OOXML Word files (.docx/.docm/.dotx/.dotm) are currently rendered.");
        }
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry documentXml = zip.getEntry("word/document.xml");
            if (documentXml == null) {
                throw new IOException("Unsupported Word file. Only OOXML Word files (.docx/.docm/.dotx/.dotm) are supported.");
            }

            Document doc;
            try (InputStream is = zip.getInputStream(documentXml)) {
                doc = secureDocumentBuilder().parse(is);
            } catch (Exception e) {
                throw new IOException("Cannot parse Word document text", e);
            }

            StringBuilder out = new StringBuilder();
            NodeList paragraphs = doc.getElementsByTagName("w:p");
            if (paragraphs.getLength() == 0) {
                paragraphs = doc.getElementsByTagNameNS("*", "p");
            }

            for (int i = 0; i < paragraphs.getLength(); i++) {
                StringBuilder paragraph = new StringBuilder();
                appendWordNodeText(paragraphs.item(i), paragraph);
                String line = paragraph.toString().trim();
                if (!line.isEmpty()) out.append(line);
                out.append('\n');
            }

            return sanitizeExtractedText(out.toString());
        }
    }

    private static void appendWordNodeText(Node node, StringBuilder out) {
        if (node == null) return;

        String name = node.getNodeName();
        String local = node.getLocalName();
        if ("w:t".equals(name) || "t".equals(local)) {
            String text = node.getTextContent();
            if (text != null) out.append(text);
            return;
        }
        if ("w:tab".equals(name) || "tab".equals(local)) {
            out.append('\t');
            return;
        }
        if ("w:br".equals(name) || "w:cr".equals(name)
                || "br".equals(local) || "cr".equals(local)) {
            out.append('\n');
            return;
        }

        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            appendWordNodeText(children.item(i), out);
        }
    }

    private static String readEpubFile(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            List<String> chapterPaths = findEpubSpinePaths(zip);
            if (chapterPaths.isEmpty()) {
                chapterPaths = findEpubHtmlEntries(zip);
            }

            if (chapterPaths.isEmpty()) {
                throw new IOException("No readable EPUB chapters found");
            }

            StringBuilder out = new StringBuilder();
            for (String path : chapterPaths) {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null || entry.isDirectory()) continue;
                String html;
                try (InputStream is = zip.getInputStream(entry)) {
                    byte[] data = TextEncodingDetector.readAllBytes(is, MAX_EPUB_CHAPTER_BYTES);
                    html = TextEncodingDetector.decodeBestEffort(data, TextEncodingDetector.detectEncodingFromBytes(TextEncodingDetector.sampleBytes(data)));
                }
                String text = htmlToPlainText(html);
                if (!text.trim().isEmpty()) {
                    out.append(text.trim()).append("\n\n");
                }
            }
            return sanitizeExtractedText(out.toString());
        }
    }

    private static List<String> findEpubSpinePaths(ZipFile zip) {
        ArrayList<String> result = new ArrayList<>();
        try {
            ZipEntry containerEntry = zip.getEntry("META-INF/container.xml");
            if (containerEntry == null) return result;

            Document containerDoc;
            try (InputStream is = zip.getInputStream(containerEntry)) {
                containerDoc = secureDocumentBuilder().parse(is);
            }

            NodeList rootFiles = containerDoc.getElementsByTagName("rootfile");
            if (rootFiles.getLength() == 0) {
                rootFiles = containerDoc.getElementsByTagNameNS("*", "rootfile");
            }
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

            String basePath = "";
            int slash = opfPath.lastIndexOf('/');
            if (slash >= 0) basePath = opfPath.substring(0, slash + 1);

            java.util.Map<String, String> manifest = new java.util.LinkedHashMap<>();
            NodeList items = opfDoc.getElementsByTagName("item");
            if (items.getLength() == 0) {
                items = opfDoc.getElementsByTagNameNS("*", "item");
            }
            for (int i = 0; i < items.getLength(); i++) {
                Node item = items.item(i);
                NamedNodeMap itemAttrs = item.getAttributes();
                if (itemAttrs == null) continue;
                Node id = itemAttrs.getNamedItem("id");
                Node href = itemAttrs.getNamedItem("href");
                if (id != null && href != null) {
                    manifest.put(id.getNodeValue(), normalizeZipPath(basePath + decodeZipHref(href.getNodeValue())));
                }
            }

            NodeList itemRefs = opfDoc.getElementsByTagName("itemref");
            if (itemRefs.getLength() == 0) {
                itemRefs = opfDoc.getElementsByTagNameNS("*", "itemref");
            }
            for (int i = 0; i < itemRefs.getLength(); i++) {
                Node itemRef = itemRefs.item(i);
                NamedNodeMap itemRefAttrs = itemRef.getAttributes();
                if (itemRefAttrs == null) continue;
                Node idRef = itemRefAttrs.getNamedItem("idref");
                if (idRef == null) continue;
                String path = manifest.get(idRef.getNodeValue());
                if (path != null && isEpubHtmlPath(path) && zip.getEntry(path) != null) {
                    result.add(path);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "EPUB spine parse failed; falling back to entry order", e);
        }
        return result;
    }

    private static List<String> findEpubHtmlEntries(ZipFile zip) {
        ArrayList<String> result = new ArrayList<>();
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && isEpubHtmlPath(entry.getName())) {
                result.add(entry.getName());
            }
        }
        java.util.Collections.sort(result);
        return result;
    }

    private static boolean isEpubHtmlPath(String path) {
        String lower = lowerName(path);
        return lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm");
    }

    /**
     * Plain reading text from rendered page HTML. Used by document search and,
     * since read-aloud reached the document viewer, by
     * {@code DocumentTtsTextSource} to build the page-indexed speech buffer.
     */
    public static String htmlToPlainText(String html) {
        if (html == null || html.isEmpty()) return "";

        String cleaned = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n")
                .replaceAll("(?i)</div\\s*>", "\n")
                .replaceAll("(?i)</h[1-6]\\s*>", "\n")
                .replaceAll("(?i)</li\\s*>", "\n");

        Spanned spanned = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY);
        return spanned.toString();
    }

    private static DocumentBuilder secureDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        setXmlFeatureQuietly(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setXmlFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false);
        setXmlFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setXmlFeatureQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newDocumentBuilder();
    }

    private static void setXmlFeatureQuietly(DocumentBuilderFactory factory, String feature, boolean enabled) {
        try {
            factory.setFeature(feature, enabled);
        } catch (Exception ignored) {
            // Some Android XML parser implementations do not expose every hardening flag.
        }
    }


    private static String decodeZipHref(String href) {
        if (href == null) return "";
        try {
            return URLDecoder.decode(href, "UTF-8");
        } catch (Exception ignored) {
            return href;
        }
    }

    private static String normalizeZipPath(String path) {
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
        return String.join("/", parts);
    }

    private static String sanitizeExtractedText(String text) {
        String normalized = TextStringUtils.sanitizeDecodedText(text != null ? text : "");
        normalized = normalized
                .replace('\u00A0', ' ')
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n[ \\t]+", "\n")
                .replaceAll("\\n{4,}", "\n\n\n")
                .trim();
        return normalized.isEmpty() ? " " : normalized;
    }

    private static String lowerName(String fileName) {
        return normalizeDisplayFileName(fileName).toLowerCase(Locale.ROOT);
    }

    public static String normalizeDisplayFileName(String fileName) {
        if (fileName == null) return "";
        String result = fileName.trim();
        try {
            result = URLDecoder.decode(result, "UTF-8");
        } catch (Exception ignored) {
            // Keep the original string when it is not percent-encoded UTF-8.
        }
        try {
            result = Normalizer.normalize(result, Normalizer.Form.NFC);
        } catch (Exception ignored) {}
        return result;
    }

    /**
     * Format file size for display.
     */
    public static String formatFileSize(long size) {
        if (size <= 0) return "0 B";

        final String[] units = {"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        digitGroups = Math.min(digitGroups, units.length - 1);

        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups))
                + " " + units[digitGroups];
    }

    public static boolean isTxtFile(String fileName) {
        String lower = lowerName(fileName).trim();
        return lower.endsWith(".txt") || lower.endsWith(".text");
    }

    public static boolean isMarkdownFile(String fileName) {
        String lower = lowerName(fileName).trim();
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    /**
     * General plain-text family used by the main quick-search chip. It excludes
     * dedicated TXT files because TXT has its own chip, and excludes SVG because
     * SVG is XML text internally but is normally treated as an image/document asset.
     */
    public static boolean isGeneralTextFile(String fileName) {
        String lower = lowerName(fileName).trim();
        return isTextFile(fileName)
                && !isTxtFile(fileName)
                && !lower.endsWith(".svg");
    }

    /**
     * Check if file extension/name is a supported plain-text file.
     */
    public static boolean isTextFile(String fileName) {
        String lower = lowerName(fileName).trim();
        if (lower.isEmpty()) return false;

        // Common extensionless text files found inside source/archive packages.
        String base = lower;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        if (base.equals("readme") || base.equals("license") || base.equals("licence")
                || base.equals("copying") || base.equals("notice") || base.equals("authors")
                || base.equals("contributors") || base.equals("changelog") || base.equals("changes")
                || base.equals("makefile") || base.equals("dockerfile") || base.equals("gemfile")
                || base.equals("rakefile") || base.equals("podfile") || base.equals("procfile")) {
            return true;
        }

        return lower.endsWith(".txt") || lower.endsWith(".text")
                || lower.endsWith(".log") || lower.endsWith(".md") || lower.endsWith(".markdown")
                || lower.endsWith(".csv") || lower.endsWith(".tsv")
                || lower.endsWith(".ini") || lower.endsWith(".cfg") || lower.endsWith(".conf")
                || lower.endsWith(".properties") || lower.endsWith(".prop")
                || lower.endsWith(".json") || lower.endsWith(".jsonl") || lower.endsWith(".xml")
                || lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml")
                || lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".sass")
                || lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".toml")
                || lower.endsWith(".sql") || lower.endsWith(".srt") || lower.endsWith(".vtt")
                || lower.endsWith(".rtf") || lower.endsWith(".tex") || lower.endsWith(".bib")
                || lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".kts")
                || lower.endsWith(".gradle") || lower.endsWith(".groovy")
                || lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs")
                || lower.endsWith(".tsx") || lower.endsWith(".jsx")
                || lower.endsWith(".vue") || lower.endsWith(".svelte")
                || lower.endsWith(".py") || lower.endsWith(".pyw") || lower.endsWith(".rb")
                || lower.endsWith(".go") || lower.endsWith(".rs") || lower.endsWith(".swift")
                || lower.endsWith(".c") || lower.endsWith(".cc") || lower.endsWith(".cpp")
                || lower.endsWith(".cxx") || lower.endsWith(".h") || lower.endsWith(".hh")
                || lower.endsWith(".hpp") || lower.endsWith(".m") || lower.endsWith(".mm")
                || lower.endsWith(".cs") || lower.endsWith(".php") || lower.endsWith(".pl")
                || lower.endsWith(".pm") || lower.endsWith(".r") || lower.endsWith(".lua")
                || lower.endsWith(".dart") || lower.endsWith(".scala") || lower.endsWith(".sc")
                || lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh")
                || lower.endsWith(".fish") || lower.endsWith(".bat") || lower.endsWith(".cmd")
                || lower.endsWith(".ps1") || lower.endsWith(".psm1")
                || lower.endsWith(".gitignore") || lower.endsWith(".gitattributes")
                || lower.endsWith(".editorconfig") || lower.endsWith(".env")
                || lower.endsWith(".manifest") || lower.endsWith(".mf") || lower.endsWith(".plist")
                || lower.endsWith(".svg");
    }

    /**
     * Best-effort content sniff used when an archive entry has an uncommon file
     * name. This does not classify encoding; it only rejects obvious binary data.
     */
    public static boolean isProbablyPlainTextFile(File file) {
        if (file == null || !file.isFile()) return false;
        byte[] buffer = new byte[8192];
        int read;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            read = in.read(buffer);
        } catch (IOException | SecurityException e) {
            return false;
        }
        if (read <= 0) return true;

        int suspicious = 0;
        for (int i = 0; i < read; i++) {
            int b = buffer[i] & 0xFF;
            if (b == 0) return false;
            if (b < 0x20 && b != '\n' && b != '\r' && b != '\t' && b != 0x0C && b != 0x1B) {
                suspicious++;
            }
        }
        return suspicious <= Math.max(2, read / 20);
    }

    /**
     * True if {@code candidatePath} is the same as, or a descendant of,
     * {@code rootPath}. Uses canonical paths when resolvable, falling back to
     * absolute paths. Consolidated from MainActivity / MainRecentFilesController.
     */
    public static boolean isSameOrChildPath(String candidatePath, String rootPath) {
        if (candidatePath == null || rootPath == null) return false;
        String candidate = candidatePath.trim();
        String root = rootPath.trim();
        if (candidate.isEmpty() || root.isEmpty()) return false;

        try {
            candidate = new File(candidate).getCanonicalPath();
            root = new File(root).getCanonicalPath();
        } catch (IOException ignored) {
            candidate = new File(candidate).getAbsolutePath();
            root = new File(root).getAbsolutePath();
        }

        if (candidate.equals(root)) return true;
        String normalizedRoot = root.endsWith(File.separator) ? root : root + File.separator;
        return candidate.startsWith(normalizedRoot);
    }
}
