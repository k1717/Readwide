package com.readwide.manager;

import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.archive.ArchiveSupport;
import com.readwide.manager.util.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared cache/extraction gate for archive-backed image sequences.
 *
 * Non-password archives may trust an existing preview file because the archive
 * content is not gated by user input. Password-protected archives must prove
 * that the current password can still extract the requested entry before a
 * sensitive cached plaintext image is reused.
 */
final class ArchiveImageEntryCache {
    private static final int LOCK_STRIPE_COUNT = 64;
    private static final Object[] LOCK_STRIPES = new Object[LOCK_STRIPE_COUNT];
    private static final Object[] ARCHIVE_LOCK_STRIPES = new Object[LOCK_STRIPE_COUNT];

    static {
        for (int i = 0; i < LOCK_STRIPES.length; i++) {
            LOCK_STRIPES[i] = new Object();
            ARCHIVE_LOCK_STRIPES[i] = new Object();
        }
    }

    private ArchiveImageEntryCache() {}

    @NonNull
    static ArchiveSupport.ExtractionResult ensureReady(@NonNull File archiveFile,
                                                       @NonNull String entryPath,
                                                       @NonNull File outFile,
                                                       @Nullable char[] password,
                                                       boolean sensitiveCache,
                                                       @Nullable Set<String> verifiedSensitivePaths) {
        String cacheKey = outFile.getAbsolutePath();
        synchronized (lockFor(cacheKey)) {
            if (shouldReuseReadyImageFile(entryPath, outFile, sensitiveCache, verifiedSensitivePaths)) {
                return ArchiveSupport.ExtractionResult.success();
            }
            discardUnverifiedSensitiveReadyCache(outFile, sensitiveCache, verifiedSensitivePaths);
            discardReadyIfStaleOrCorrupt(entryPath, outFile);

            File tmpFile = null;
            try {
                File parent = outFile.getParentFile();
                if (parent == null) {
                    return ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.FAILED, null);
                }
                if (!parent.exists() && !parent.mkdirs()) {
                    return ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.FAILED, null);
                }

                boolean likelyUnsupportedRarSolidPpmd = isLikelyUnsupportedRar3PpmdSolidImage(archiveFile, entryPath);
                if (shouldPreferWholeArchiveImageCache(archiveFile, entryPath)) {
                    ArchiveSupport.ExtractionResult bulkResult = ensureReadyByWholeArchiveExtraction(
                            archiveFile,
                            entryPath,
                            outFile,
                            password,
                            sensitiveCache,
                            verifiedSensitivePaths);
                    if (bulkResult.success || bulkResult.failure == ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED
                            || bulkResult.failure == ArchiveSupport.ExtractionFailure.BAD_PASSWORD) {
                        return bulkResult;
                    }
                    if (likelyUnsupportedRarSolidPpmd) {
                        return unsupportedRarSolidPpmd(entryPath, bulkResult);
                    }
                    // Fall through to the older single-entry path for non-solid or backend-specific
                    // RAR cases where archive-wide extraction is unavailable but a single member can
                    // still be extracted.
                }

                if (likelyUnsupportedRarSolidPpmd) {
                    return unsupportedRarSolidPpmd(entryPath, null);
                }

                tmpFile = File.createTempFile("archive_image_", ".extracting", parent);
                ArchiveSupport.ExtractionResult result;
                synchronized (lockForArchive(archiveFile)) {
                    result = ArchiveSupport.extractSingleEntryDetailed(
                            archiveFile,
                            entryPath,
                            tmpFile,
                            password);
                }
                if (!result.success) {
                    deleteQuietly(tmpFile);
                    ArchiveSupport.ExtractionResult fallback = tryEnsureReadyByWholeArchiveExtraction(
                            archiveFile, entryPath, outFile, password, sensitiveCache, verifiedSensitivePaths, result);
                    return fallback != null ? fallback : result;
                }
                if (!isUsableFile(tmpFile) || !looksLikeExpectedImage(entryPath, tmpFile)) {
                    deleteQuietly(tmpFile);
                    ArchiveSupport.ExtractionResult fallback = tryEnsureReadyByWholeArchiveExtraction(
                            archiveFile, entryPath, outFile, password, sensitiveCache, verifiedSensitivePaths,
                            ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.FAILED,
                                    "Extracted archive image did not decode as the expected image type"));
                    return fallback != null ? fallback
                            : ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.FAILED, null);
                }
                if (!replaceReadyFile(tmpFile, outFile)) {
                    deleteQuietly(tmpFile);
                    return ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.FAILED, null);
                }
                writeReadyMarker(outFile);
                if (sensitiveCache && verifiedSensitivePaths != null) {
                    verifiedSensitivePaths.add(cacheKey);
                }
                return ArchiveSupport.ExtractionResult.success();
            } catch (IOException | SecurityException e) {
                deleteQuietly(tmpFile);
                return ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.FAILED, e.getMessage());
            }
        }
    }


    @NonNull
    private static ArchiveSupport.ExtractionResult unsupportedRarSolidPpmd(
            @NonNull String entryPath,
            @Nullable ArchiveSupport.ExtractionResult backendResult) {
        String detail = "RAR3/RAR4 solid PPMd image entry is not supported by the current "
                + "first-party decoder. The bundled libarchive backend was tried first"
                + (backendResult != null && backendResult.detail != null && backendResult.detail.trim().length() > 0
                ? ", but did not produce a valid image cache for " + entryPath + ": " + backendResult.detail.trim()
                : ", but did not produce a valid image cache for " + entryPath)
                + ". A full RAR3 PPMd solid decoder is required for this CBR case.";
        return ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE, detail);
    }

    private static boolean isLikelyUnsupportedRar3PpmdSolidImage(@NonNull File archiveFile,
                                                                 @NonNull String entryPath) {
        if (ArchiveSupport.getSupportedArchiveType(archiveFile) != ArchiveSupport.Type.RAR) return false;
        if (!FileUtils.isImageFile(entryPath)) return false;
        Rar4EntryProbe probe = probeRar4Entry(archiveFile, entryPath);
        return probe != null && probe.found && probe.solid && probe.ppmd;
    }

    @Nullable
    private static Rar4EntryProbe probeRar4Entry(@NonNull File archiveFile, @NonNull String targetPath) {
        String wanted = normalizeRarProbePath(targetPath);
        if (wanted.length() == 0) return null;
        try (RandomAccessFile raf = new RandomAccessFile(archiveFile, "r")) {
            long signature = findRar4Signature(raf);
            if (signature < 0L) return null;
            raf.seek(signature + 7L);
            while (raf.getFilePointer() + 7L <= raf.length()) {
                long headerStart = raf.getFilePointer();
                readUInt16LE(raf);
                int type = raf.readUnsignedByte();
                int flags = readUInt16LE(raf);
                int headerSize = readUInt16LE(raf);
                if (headerSize < 7 || headerStart + headerSize > raf.length()) return null;
                byte[] header = new byte[headerSize - 7];
                raf.readFully(header);
                long dataSize = 0L;
                if (type == 0x74) {
                    ParsedRar4File parsed = parseRar4FileHeader(header, flags, headerStart + headerSize);
                    if (parsed == null) return null;
                    dataSize = parsed.packedSize;
                    if (wanted.equals(normalizeRarProbePath(parsed.path))) {
                        boolean ppmd = false;
                        if (parsed.packedSize >= 2L && parsed.dataOffset + 2L <= raf.length()) {
                            long old = raf.getFilePointer();
                            raf.seek(parsed.dataOffset);
                            int first = raf.readUnsignedByte();
                            int second = raf.readUnsignedByte();
                            raf.seek(old);
                            int blockFlags = (first << 8) | second;
                            ppmd = (blockFlags & 0x8000) != 0;
                        }
                        return new Rar4EntryProbe(true, parsed.solid, parsed.method, ppmd);
                    }
                } else if ((flags & 0x8000) != 0 && header.length >= 4) {
                    dataSize = uint32LE(header, 0);
                }
                long next = headerStart + headerSize + dataSize;
                if (next <= headerStart || next > raf.length()) return null;
                raf.seek(next);
                if (type == 0x7b) break;
            }
        } catch (IOException | SecurityException ignored) {
            return null;
        }
        return null;
    }

    @Nullable
    private static ParsedRar4File parseRar4FileHeader(@NonNull byte[] header, int flags, long dataOffset) {
        try {
            int pos = 0;
            long packSize = uint32LE(header, pos); pos += 4;
            pos += 4; // unpacked size
            pos += 1; // host OS
            pos += 4; // data CRC
            pos += 4; // DOS time
            pos += 1; // unp ver
            int method = header[pos++] & 0xff;
            int nameSize = uint16LE(header, pos); pos += 2;
            pos += 4; // attributes
            if ((flags & 0x0100) != 0) {
                long highPack = uint32LE(header, pos); pos += 4;
                pos += 4; // high unpacked
                packSize |= highPack << 32;
            }
            if (nameSize < 0 || pos + nameSize > header.length) return null;
            byte[] rawName = new byte[nameSize];
            System.arraycopy(header, pos, rawName, 0, nameSize);
            String path = decodeRar4ProbeName(rawName);
            if (path.length() == 0) return null;
            boolean solid = (flags & 0x0010) != 0;
            int normalizedMethod = method == 0x30 ? 0 : method;
            return new ParsedRar4File(path, packSize, dataOffset, normalizedMethod, solid);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @NonNull
    private static String decodeRar4ProbeName(@NonNull byte[] rawName) {
        int zero = -1;
        for (int i = 0; i < rawName.length; i++) {
            if (rawName[i] == 0) { zero = i; break; }
        }
        int len = zero >= 0 ? zero : rawName.length;
        try {
            return new String(rawName, 0, len, "UTF-8").replace('\\', '/').trim();
        } catch (Exception ignored) {
            return new String(rawName, 0, len).replace('\\', '/').trim();
        }
    }

    @NonNull
    private static String normalizeRarProbePath(@Nullable String value) {
        if (value == null) return "";
        String path = value.replace('\\', '/').trim();
        while (path.startsWith("/")) path = path.substring(1);
        while (path.startsWith("./")) path = path.substring(2);
        while (path.contains("//")) path = path.replace("//", "/");
        return path.toLowerCase(Locale.ROOT);
    }

    private static long findRar4Signature(@NonNull RandomAccessFile raf) throws IOException {
        final byte[] sig = new byte[] {0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00};
        long max = Math.min(raf.length(), 1024L * 1024L);
        int match = 0;
        raf.seek(0L);
        for (long pos = 0; pos < max; pos++) {
            int b = raf.read();
            if (b < 0) break;
            if ((byte) b == sig[match]) {
                match++;
                if (match == sig.length) return pos - sig.length + 1L;
            } else {
                match = ((byte) b == sig[0]) ? 1 : 0;
            }
        }
        return -1L;
    }

    private static int readUInt16LE(@NonNull RandomAccessFile raf) throws IOException {
        int b0 = raf.readUnsignedByte();
        int b1 = raf.readUnsignedByte();
        return b0 | (b1 << 8);
    }

    private static int uint16LE(@NonNull byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long uint32LE(@NonNull byte[] data, int offset) {
        return ((long) data[offset] & 0xffL)
                | (((long) data[offset + 1] & 0xffL) << 8)
                | (((long) data[offset + 2] & 0xffL) << 16)
                | (((long) data[offset + 3] & 0xffL) << 24);
    }

    private static final class Rar4EntryProbe {
        final boolean found;
        final boolean solid;
        final int method;
        final boolean ppmd;

        Rar4EntryProbe(boolean found, boolean solid, int method, boolean ppmd) {
            this.found = found;
            this.solid = solid;
            this.method = method;
            this.ppmd = ppmd;
        }
    }

    private static final class ParsedRar4File {
        @NonNull final String path;
        final long packedSize;
        final long dataOffset;
        final int method;
        final boolean solid;

        ParsedRar4File(@NonNull String path, long packedSize, long dataOffset, int method, boolean solid) {
            this.path = path;
            this.packedSize = packedSize;
            this.dataOffset = dataOffset;
            this.method = method;
            this.solid = solid;
        }
    }

    @Nullable
    private static ArchiveSupport.ExtractionResult tryEnsureReadyByWholeArchiveExtraction(
            @NonNull File archiveFile,
            @NonNull String entryPath,
            @NonNull File outFile,
            @Nullable char[] password,
            boolean sensitiveCache,
            @Nullable Set<String> verifiedSensitivePaths,
            @Nullable ArchiveSupport.ExtractionResult originalResult) {
        if (!shouldUseWholeArchiveFallback(archiveFile, originalResult)) return null;
        return ensureReadyByWholeArchiveExtraction(
                archiveFile,
                entryPath,
                outFile,
                password,
                sensitiveCache,
                verifiedSensitivePaths);
    }

    @NonNull
    private static ArchiveSupport.ExtractionResult ensureReadyByWholeArchiveExtraction(
            @NonNull File archiveFile,
            @NonNull String entryPath,
            @NonNull File outFile,
            @Nullable char[] password,
            boolean sensitiveCache,
            @Nullable Set<String> verifiedSensitivePaths) {
        File parent = outFile.getParentFile();
        if (parent == null) {
            return ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.FAILED, null);
        }
        File extractRoot = null;
        try {
            if (!parent.exists() && !parent.mkdirs()) {
                return ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.FAILED, null);
            }
            extractRoot = buildWholeArchiveExtractRoot(parent);
            ArchiveSupport.ExtractionResult result;
            int copied;
            synchronized (lockForArchive(archiveFile)) {
                if (shouldReuseReadyImageFile(entryPath, outFile, sensitiveCache, verifiedSensitivePaths)) {
                    return ArchiveSupport.ExtractionResult.success();
                }
                discardUnverifiedSensitiveReadyCache(outFile, sensitiveCache, verifiedSensitivePaths);
                result = ArchiveSupport.extractArchiveDetailed(
                        archiveFile,
                        extractRoot,
                        false,
                        password,
                        null);
                if (!result.success) {
                    return result;
                }

                copied = copyExtractedImagesIntoPreviewCache(
                        extractRoot,
                        parent,
                        sensitiveCache,
                        verifiedSensitivePaths);
            }
            if (copied <= 0) {
                return ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.FAILED,
                        "Whole-archive fallback produced no readable image cache entries");
            }
            // The whole archive was extracted and its images copied into the preview
            // cache; remember this so a later miss extracts only the missing member.
            WHOLE_ARCHIVE_BULK_DONE.add(archiveBulkKey(archiveFile));
            return isReadyImageFile(entryPath, outFile)
                    ? ArchiveSupport.ExtractionResult.success()
                    : ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.FAILED,
                            "Whole-archive fallback did not produce the requested image entry");
        } catch (IOException | SecurityException e) {
            return ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.FAILED, e.getMessage());
        } finally {
            deleteRecursively(extractRoot);
        }
    }

    @NonNull
    private static File buildWholeArchiveExtractRoot(@NonNull File parent) throws IOException {
        File token = File.createTempFile("archive_image_full_", ".dir", parent);
        if (!token.delete()) throw new IOException("Cannot prepare temporary extraction directory");
        return token;
    }

    private static int copyExtractedImagesIntoPreviewCache(@NonNull File extractRoot,
                                                           @NonNull File cacheParent,
                                                           boolean sensitiveCache,
                                                           @Nullable Set<String> verifiedSensitivePaths) throws IOException {
        List<File> files = new ArrayList<>();
        collectFiles(extractRoot, files);
        int copied = 0;
        for (File file : files) {
            String relative = relativePath(extractRoot, file);
            if (relative == null || relative.length() == 0 || !FileUtils.isImageFile(relative)) continue;
            if (!looksLikeExpectedImage(relative, file)) continue;
            File target = new File(cacheParent, ArchivePreviewCache.cacheFileNameForEntry(relative));
            if (copyReadyFile(file, target)) {
                writeReadyMarker(target);
                if (sensitiveCache && verifiedSensitivePaths != null) {
                    verifiedSensitivePaths.add(target.getAbsolutePath());
                }
                copied++;
            }
        }
        return copied;
    }

    private static boolean copyReadyFile(@NonNull File source, @NonNull File target) throws IOException {
        File parent = target.getParentFile();
        if (parent == null) return false;
        if (!parent.exists() && !parent.mkdirs()) return false;
        File tmp = File.createTempFile("archive_image_ready_", ".tmp", parent);
        try {
            copyFile(source, tmp);
            if (!isUsableFile(tmp)) return false;
            return replaceReadyFile(tmp, target);
        } finally {
            deleteQuietly(tmp);
        }
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target, false)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    private static void collectFiles(@Nullable File root, @NonNull List<File> out) {
        if (root == null || !root.exists()) return;
        if (root.isFile()) {
            out.add(root);
            return;
        }
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) collectFiles(child, out);
    }

    @Nullable
    private static String relativePath(@NonNull File root, @NonNull File file) {
        try {
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if (filePath.equals(rootPath)) return "";
            String prefix = rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
            if (!filePath.startsWith(prefix)) return null;
            return filePath.substring(prefix.length()).replace(File.separatorChar, '/');
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private static boolean shouldUseWholeArchiveFallback(@NonNull File archiveFile,
                                                          @Nullable ArchiveSupport.ExtractionResult originalResult) {
        if (ArchiveSupport.getSupportedArchiveType(archiveFile) != ArchiveSupport.Type.RAR) return false;
        if (originalResult == null) return true;
        if (originalResult.failure == ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED
                || originalResult.failure == ArchiveSupport.ExtractionFailure.BAD_PASSWORD) {
            return false;
        }
        return true;
    }

    // Archives whose whole-archive image bulk extraction has already succeeded this
    // process session, keyed by path+size+mtime. Used so a RAR cache miss after the
    // initial bulk extracts just the missing member instead of re-extracting the
    // whole archive again.
    private static final Set<String> WHOLE_ARCHIVE_BULK_DONE =
            ConcurrentHashMap.newKeySet();

    private static String archiveBulkKey(@NonNull File archiveFile) {
        return archiveFile.getAbsolutePath() + ":" + archiveFile.length() + ":" + archiveFile.lastModified();
    }

    private static boolean shouldPreferWholeArchiveImageCache(@NonNull File archiveFile,
                                                              @NonNull String entryPath) {
        if (!FileUtils.isImageFile(entryPath)) return false;
        ArchiveSupport.Type type = ArchiveSupport.getSupportedArchiveType(archiveFile);
        // Numeric split archives (name.zip.001 style) of random-access types have
        // no cheap per-entry access either, but for a different reason than the
        // solid formats: every open concatenates all volumes into a temporary file
        // first, so extracting entries one at a time costs O(total archive size)
        // of disk I/O per entry (a full re-concatenation per page and per
        // prefetched neighbor). One whole-archive pass pays the concatenation
        // once. Splits of the sequential types (e.g. name.7z.001) are excluded
        // here on purpose - they already take the sequential path above, with its
        // own volume handling and forward-reader-first design.
        boolean numericSplitRandomAccess = ArchiveSupport.isNumericSplitArchive(archiveFile)
                && !isSequentialEntryArchiveType(type);
        if (!isSequentialEntryArchiveType(type) && !numericSplitRandomAccess) return false;
        // RAR normally enters the viewer through the session-scoped libarchive forward
        // reader. This bulk branch is now only the degradation path when that stream
        // cannot open or decode the file's exact RAR variant. Once a fallback bulk has
        // succeeded, a later cache miss extracts only that member instead of repeating
        // the whole pass. The same downgrade applies to random-access numeric splits: after the bulk,
        // refilling one evicted page costs one concatenation plus one entry, which
        // beats re-running the whole-archive pass. 7z and the TAR family keep
        // whole-archive as their fallback because their forward reader is the
        // primary, prune-tolerant path.
        if ((type == ArchiveSupport.Type.RAR || numericSplitRandomAccess)
                && WHOLE_ARCHIVE_BULK_DONE.contains(archiveBulkKey(archiveFile))) {
            return false;
        }
        return true;
    }

    // RAR, 7z and the TAR family have no cheap random per-entry access: extracting a
    // single entry re-reads the archive from the start up to that entry, and for the
    // solid (RAR/7z) and compressed-TAR streams it must re-decompress everything before
    // it. That makes per-page image extraction O(n) per page and O(n^2) for a full
    // read-through, which stalls paging and leaves the previous image on screen. For
    // these formats every image is extracted once into the preview cache so later page
    // turns are cache hits. ZIP, ALZ and EGG seek directly to each entry and are excluded.
    private static boolean isSequentialEntryArchiveType(@Nullable ArchiveSupport.Type type) {
        if (type == null) return false;
        switch (type) {
            case RAR:
            case SEVEN_Z:
            case TAR:
            case TAR_GZ:
            case TAR_BZ2:
            case TAR_XZ:
            case TAR_LZMA:
            case TAR_Z:
            case TAR_ZST:
            case TAR_LZ4:
            case LIBARCHIVE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns whether an existing ready image may be reused in the current
     * archive-password session.
     *
     * <p>Forward archive readers must use the same gate as the normal
     * extraction path. Checking only the ready marker would let an old
     * plaintext preview from a password-protected archive bypass verification
     * by the password supplied for the current open.</p>
     */
    static boolean shouldReuseReadyImageFile(@NonNull String entryPath,
                                             @NonNull File file,
                                             boolean sensitiveCache,
                                             @Nullable Set<String> verifiedSensitivePaths) {
        return canReuseReadyMarkerForCache(sensitiveCache, verifiedSensitivePaths, file.getAbsolutePath())
                && isReadyImageFile(entryPath, file);
    }

    static boolean canReuseReadyMarkerForCache(boolean sensitiveCache,
                                               @Nullable Set<String> verifiedSensitivePaths,
                                               @NonNull String cacheKey) {
        return !sensitiveCache
                || (verifiedSensitivePaths != null && verifiedSensitivePaths.contains(cacheKey));
    }

    static boolean shouldDiscardUnverifiedSensitiveReadyCache(boolean sensitiveCache,
                                                              @Nullable Set<String> verifiedSensitivePaths,
                                                              @NonNull String cacheKey) {
        return sensitiveCache && !canReuseReadyMarkerForCache(true, verifiedSensitivePaths, cacheKey);
    }

    static void discardUnverifiedSensitiveReadyCache(@NonNull File file,
                                                     boolean sensitiveCache,
                                                     @Nullable Set<String> verifiedSensitivePaths) {
        if (!shouldDiscardUnverifiedSensitiveReadyCache(
                sensitiveCache,
                verifiedSensitivePaths,
                file.getAbsolutePath())) {
            return;
        }
        synchronized (lockFor(file.getAbsolutePath())) {
            // Verification can be published while this caller waits for the
            // file stripe; do not delete a cache that became valid meanwhile.
            if (!shouldDiscardUnverifiedSensitiveReadyCache(
                    sensitiveCache,
                    verifiedSensitivePaths,
                    file.getAbsolutePath())) {
                return;
            }
            deleteQuietly(readyMarkerFor(file));
            deleteQuietly(file);
        }
    }

    private static void discardReadyIfStaleOrCorrupt(@NonNull String entryPath, @NonNull File file) {
        File marker = readyMarkerFor(file);
        if (!marker.exists()) return;
        if (isUsableFile(file) && looksLikeExpectedImage(entryPath, file)) return;
        deleteQuietly(marker);
        deleteQuietly(file);
    }

    static boolean isReadyImageFileForHandoff(@NonNull String entryPath, @Nullable File file) {
        return isReadyImageFile(entryPath, file);
    }

    /**
     * Commits an already-extracted temp image as the ready cache file for an entry.
     *
     * Used by the forward (sequential) image reader so it can populate the same preview
     * cache that {@link #ensureReady} reads, reusing the shared validation and ready-marker
     * logic. The temp file is consumed on success and deleted on any failure.
     */
    static boolean commitReadyImageFile(@NonNull String entryPath,
                                        @NonNull File tmpFile,
                                        @NonNull File outFile,
                                        boolean sensitiveCache,
                                        @Nullable Set<String> verifiedSensitivePaths) {
        synchronized (lockFor(outFile.getAbsolutePath())) {
            try {
                if (!isUsableFile(tmpFile) || !looksLikeExpectedImage(entryPath, tmpFile)) {
                    deleteQuietly(tmpFile);
                    return false;
                }
                if (!replaceReadyFile(tmpFile, outFile)) {
                    deleteQuietly(tmpFile);
                    return false;
                }
                writeReadyMarker(outFile);
                if (sensitiveCache && verifiedSensitivePaths != null) {
                    verifiedSensitivePaths.add(outFile.getAbsolutePath());
                }
                return true;
            } catch (IOException | SecurityException e) {
                deleteQuietly(tmpFile);
                return false;
            }
        }
    }

    private static boolean isReadyImageFile(@NonNull String entryPath, @Nullable File file) {
        return isReadyFile(file) && looksLikeExpectedImage(entryPath, file);
    }

    private static boolean looksLikeExpectedImage(@NonNull String entryPath, @Nullable File file) {
        if (!isUsableFile(file)) return false;
        String lower = entryPath.toLowerCase(Locale.ROOT);
        byte[] header = new byte[16];
        int read;
        try (InputStream in = new FileInputStream(file)) {
            read = in.read(header);
        } catch (IOException | SecurityException e) {
            return false;
        }
        if (read <= 0) return false;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return read >= 2
                    && (header[0] & 0xff) == 0xff
                    && (header[1] & 0xff) == 0xd8
                    && imageBoundsDecodeSucceeds(file);
        }
        if (lower.endsWith(".png")) {
            return read >= 8
                    && (header[0] & 0xff) == 0x89
                    && header[1] == 0x50 && header[2] == 0x4e && header[3] == 0x47
                    && header[4] == 0x0d && header[5] == 0x0a && header[6] == 0x1a && header[7] == 0x0a
                    && imageBoundsDecodeSucceeds(file);
        }
        if (lower.endsWith(".gif")) {
            return read >= 6
                    && header[0] == 0x47 && header[1] == 0x49 && header[2] == 0x46
                    && header[3] == 0x38 && (header[4] == 0x37 || header[4] == 0x39) && header[5] == 0x61
                    && imageBoundsDecodeSucceeds(file);
        }
        if (lower.endsWith(".webp")) {
            return read >= 12
                    && header[0] == 0x52 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x46
                    && header[8] == 0x57 && header[9] == 0x45 && header[10] == 0x42 && header[11] == 0x50
                    && imageBoundsDecodeSucceeds(file);
        }
        if (lower.endsWith(".bmp")) {
            return read >= 2 && header[0] == 0x42 && header[1] == 0x4d && imageBoundsDecodeSucceeds(file);
        }
        if (lower.endsWith(".heic") || lower.endsWith(".heif") || lower.endsWith(".avif")) {
            // Older Android releases may not decode these formats even when the file is valid.
            // Keep header validation here and let the viewer backend make the final decision.
            return read >= 12 && header[4] == 0x66 && header[5] == 0x74 && header[6] == 0x79 && header[7] == 0x70;
        }
        return true;
    }

    private static boolean imageBoundsDecodeSucceeds(@NonNull File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            return options.outWidth > 0 && options.outHeight > 0;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static boolean isUsableFile(@Nullable File file) {
        return file != null && file.exists() && file.isFile() && file.length() > 0L;
    }

    static void discardReady(@Nullable File file) {
        if (file == null) return;
        synchronized (lockFor(file.getAbsolutePath())) {
            deleteQuietly(readyMarkerFor(file));
            deleteQuietly(file);
        }
    }

    private static boolean isReadyFile(@Nullable File file) {
        return isUsableFile(file) && readyMarkerFor(file).exists();
    }

    @NonNull
    private static Object lockFor(@NonNull String key) {
        int index = (key.hashCode() & 0x7fffffff) % LOCK_STRIPES.length;
        return LOCK_STRIPES[index];
    }

    @NonNull
    private static Object lockForArchive(@NonNull File archiveFile) {
        String key;
        try {
            key = archiveFile.getCanonicalPath();
        } catch (IOException | SecurityException ignored) {
            key = archiveFile.getAbsolutePath();
        }
        key = key + "\n" + archiveFile.length() + "\n" + archiveFile.lastModified();
        int index = (key.hashCode() & 0x7fffffff) % ARCHIVE_LOCK_STRIPES.length;
        return ARCHIVE_LOCK_STRIPES[index];
    }

    @NonNull
    private static File readyMarkerFor(@NonNull File file) {
        return new File(file.getAbsolutePath() + ".ready");
    }

    private static boolean replaceReadyFile(@NonNull File tmpFile, @NonNull File outFile) {
        File marker = readyMarkerFor(outFile);
        deleteQuietly(marker);
        if (outFile.exists() && !outFile.delete()) return false;
        if (tmpFile.renameTo(outFile)) return true;
        return false;
    }

    private static void writeReadyMarker(@NonNull File outFile) throws IOException {
        File marker = readyMarkerFor(outFile);
        File parent = marker.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create cache marker directory");
        }
        if (!marker.exists() && !marker.createNewFile()) {
            throw new IOException("Cannot create cache marker");
        }
        //noinspection ResultOfMethodCallIgnored
        marker.setLastModified(System.currentTimeMillis());
    }


    private static void deleteRecursively(@Nullable File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        deleteQuietly(file);
    }

    private static void deleteQuietly(@Nullable File file) {
        if (file == null) return;
        try {
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (SecurityException ignored) {
        }
    }
}
