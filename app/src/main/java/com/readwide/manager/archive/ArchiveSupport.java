package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.z.ZCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import com.readwide.manager.util.FileOperationProgress;
import com.readwide.manager.util.FileTreeProgressTracker;
import com.readwide.manager.util.FileTreeWalk;
import com.readwide.manager.util.ArchiveZipWriter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ArchiveSupport {
    private ArchiveSupport() {}


    private static final long MIN_EXTRACTION_FREE_MARGIN_BYTES = 64L * 1024L * 1024L;
    private static final int ZIP_RAW_NAME_CACHE_MAX_ENTRIES = 8;
    private static final Map<String, List<ZipRawName>> ZIP_RAW_NAME_CACHE = new LinkedHashMap<String, List<ZipRawName>>(
            ZIP_RAW_NAME_CACHE_MAX_ENTRIES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<ZipRawName>> eldest) {
            return size() > ZIP_RAW_NAME_CACHE_MAX_ENTRIES;
        }
    };

    private static final int ZIP_INDEX_CACHE_MAX_ENTRIES = 3;
    private static final Map<String, CachedZipIndex> ZIP_INDEX_CACHE = new LinkedHashMap<String, CachedZipIndex>(
            ZIP_INDEX_CACHE_MAX_ENTRIES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedZipIndex> eldest) {
            return size() > ZIP_INDEX_CACHE_MAX_ENTRIES;
        }
    };

    public enum Type {
        ZIP,
        TAR,
        TAR_GZ,
        TAR_BZ2,
        TAR_XZ,
        TAR_LZMA,
        TAR_Z,
        TAR_ZST,
        TAR_LZ4,
        SEVEN_Z,
        RAR,
        LIBARCHIVE,
        ALZ,
        EGG,
        SINGLE_GZ,
        SINGLE_BZ2,
        SINGLE_XZ,
        SINGLE_LZMA,
        SINGLE_Z,
        SINGLE_ZST,
        SINGLE_LZ4
    }

    public static final class PasswordRequiredException extends IOException {
        public PasswordRequiredException() { super("Archive password required"); }
    }

    public static class UnsupportedArchiveFeatureException extends IOException {
        UnsupportedArchiveFeatureException(@NonNull String message) {
            super(message);
        }

        UnsupportedArchiveFeatureException(@NonNull String message, @NonNull Throwable cause) {
            super(message, cause);
        }
    }

    public enum ExtractionFailure {
        NONE,
        PASSWORD_REQUIRED,
        BAD_PASSWORD,
        UNSUPPORTED_FEATURE,
        CORRUPT_ARCHIVE,
        FAILED
    }

    public static final class ExtractionResult {
        public final boolean success;
        @NonNull public final ExtractionFailure failure;
        @Nullable public final String detail;

        private ExtractionResult(boolean success,
                                 @NonNull ExtractionFailure failure,
                                 @Nullable String detail) {
            this.success = success;
            this.failure = failure;
            this.detail = detail;
        }

        @NonNull
        public static ExtractionResult success() {
            return new ExtractionResult(true, ExtractionFailure.NONE, null);
        }

        @NonNull
        public static ExtractionResult failed(@NonNull ExtractionFailure failure, @Nullable String detail) {
            return new ExtractionResult(false, failure, detail);
        }
    }

    public static final class EntryInfo {
        public final String path;
        public final boolean directory;
        public final long size;
        public final long timeMillis;

        public EntryInfo(@NonNull String path, boolean directory, long size, long timeMillis) {
            this.path = normalizeDisplayPath(path, directory);
            this.directory = directory;
            this.size = size;
            this.timeMillis = timeMillis;
        }

        public String name() {
            String p = path;
            if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
            int slash = p.lastIndexOf('/');
            return slash >= 0 ? p.substring(slash + 1) : p;
        }
    }

    @Nullable
    public static Type getSupportedArchiveType(@NonNull File file) {
        return ArchiveTypeDetector.fromFile(file);
    }

    @Nullable
    public static Type getSupportedArchiveType(@NonNull String fileName) {
        return ArchiveTypeDetector.fromFileName(fileName);
    }

    public static boolean isSupportedArchive(@NonNull File file) {
        return getSupportedArchiveType(file) != null;
    }

    public static boolean isSupportedArchiveFileName(@NonNull String fileName) {
        return getSupportedArchiveType(fileName) != null;
    }

    private static boolean isFirstNumericSplitName(@NonNull String lowerName) {
        return ArchiveTypeDetector.isFirstNumericSplitName(lowerName);
    }

    private static boolean isFirstNumericSplitArchive(@NonNull File archive) {
        return ArchiveTypeDetector.isFirstNumericSplitName(archive.getName().toLowerCase(Locale.ROOT));
    }

    /**
     * True when this file is any canonical volume of a generic numeric split
     * ({@code name.zip.001} style). Except for dedicated split-7z and linked-CAB routes,
     * read paths concatenate these volumes into a temporary file before opening
     * ({@code combineSplitParts}). That costs O(total archive size) per opening - callers
     * that would otherwise extract entries one at a time (e.g. the image preview
     * cache) can use this to switch to a single whole-archive pass instead.
     */
    public static boolean isNumericSplitArchive(@NonNull File archive) {
        return ArchiveTypeDetector.numericSplitStem(archive.getName()) != null;
    }

    public static String getArchiveOutputBaseName(@NonNull File archive, @NonNull String fallback) {
        return ArchiveTypeDetector.outputBaseName(archive, fallback);
    }

    /**
     * Returns the single archive file that should own an extraction queue item.
     *
     * <p>Split archives expose several readable file names in the browser
     * (for example {@code book.part1.rar}, {@code book.part2.rar}, or
     * {@code book.7z.001}, {@code book.7z.002}). Selecting every visible part
     * must still enqueue exactly one extraction job, because the extractor reads
     * the chain from the first volume. This method mirrors the read-path first
     * volume resolution without concatenating numeric split payloads or creating
     * temporary files.</p>
     */
    @NonNull
    public static File normalizeExtractionQueueArchive(@NonNull File archive) {
        Type type = getSupportedArchiveType(archive);
        if (type == null) return archive;
        try {
            if (type == Type.LIBARCHIVE && CabVolumeResolver.hasVolumeLinks(archive)) {
                return CabVolumeResolver.resolve(archive).parts.get(0);
            }
            if (isNumericSplitArchive(archive)) {
                return SevenZSplitVolumeResolver.resolveNumeric(archive).firstPart;
            }
            if (LhaVolumeResolver.isCandidate(archive.getName())) {
                return LhaVolumeResolver.resolve(archive).get(0);
            }
            if (type == Type.ZIP && ZipVolumeResolver.isContinuation(archive.getName())) {
                return ZipVolumeResolver.resolveFinalVolume(archive);
            }
            if (type == Type.RAR && isRarSplitPart(archive)) {
                List<File> parts = collectRarSplitParts(archive);
                return parts.isEmpty() ? archive : parts.get(0);
            }
            if ((type == Type.ALZ || type == Type.EGG) && isAlzipSplitPart(archive)) {
                return resolveFirstAlzipPart(archive, type);
            }
            if (type == Type.SEVEN_Z && SevenZSplitVolumeResolver.isSevenZSplitPart(archive)) {
                return SevenZSplitVolumeResolver.resolveFirstPart(archive);
            }
        } catch (IOException | SecurityException ignored) {
            return archive;
        }
        return archive;
    }

    public static boolean canUsePassword(@NonNull File archive) {
        Type type = getSupportedArchiveType(archive);
        return type == Type.ZIP || type == Type.SEVEN_Z || type == Type.RAR
                || type == Type.ALZ || type == Type.EGG;
    }

    public static boolean isZipEncrypted(@NonNull File archive) {
        if (getSupportedArchiveType(archive) != Type.ZIP) return false;
        try (PreparedArchive prepared = prepareArchiveForRead(archive)) {
            return isZipFileEncrypted(prepared.file);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    public static boolean requiresPasswordForExtraction(@NonNull File archive) {
        Type type = getSupportedArchiveType(archive);
        if (type == null) return false;
        try (PreparedArchive prepared = prepareArchiveForRead(archive)) {
            switch (prepared.type) {
                case ZIP:
                    return isZipFileEncrypted(prepared.file);
                case SEVEN_Z:
                    return requiresSevenZPasswordForExtraction(prepared.file);
                case RAR:
                    return RarArchiveReader.requiresPasswordForExtraction(prepared.file);
                case LIBARCHIVE:
                    return false;
                case ALZ:
                    return AlzipArchiveReader.requiresPasswordForExtraction(prepared.file);
                case EGG:
                    return EggArchiveReader.requiresPasswordForExtraction(prepared.file);
                case TAR:
                case TAR_GZ:
                case TAR_BZ2:
                case TAR_XZ:
                case TAR_LZMA:
                case TAR_Z:
                case TAR_ZST:
                case TAR_LZ4:
                case SINGLE_GZ:
                case SINGLE_BZ2:
                case SINGLE_XZ:
                case SINGLE_LZMA:
                case SINGLE_Z:
                case SINGLE_ZST:
                case SINGLE_LZ4:
                    return false;
                default:
                    return false;
            }
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    @NonNull
    public static List<EntryInfo> listEntries(@NonNull File archive, @Nullable char[] password) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.io.InterruptedIOException("Archive listing cancelled");
        }
        try (PreparedArchive prepared = prepareArchiveForRead(archive)) {
            switch (prepared.type) {
                case ZIP:
                    return listZipEntriesWithFallback(prepared.file, prepared.type, password);
                case SEVEN_Z:
                    return listSevenZEntriesWithFallback(prepared.file, prepared.type, password);
                case RAR:
                    return RarArchiveReader.listEntries(prepared.file, password);
                case LIBARCHIVE:
                    return LibarchiveArchiveReader.listEntries(prepared.file, password);
                case ALZ:
                    return AlzipArchiveReader.listEntries(prepared.file, password);
                case EGG:
                    return EggArchiveReader.listEntries(prepared.file, password);
                case TAR:
                case TAR_GZ:
                case TAR_BZ2:
                case TAR_XZ:
                case TAR_LZMA:
                case TAR_Z:
                case TAR_ZST:
                case TAR_LZ4:
                    return listTarEntriesWithFallback(prepared.file, prepared.type, password);
                case SINGLE_GZ:
                case SINGLE_BZ2:
                case SINGLE_XZ:
                case SINGLE_LZMA:
                case SINGLE_Z:
                case SINGLE_ZST:
                case SINGLE_LZ4:
                    return listSingleCompressedEntry(archive);
                default:
                    throw new IOException("Unsupported archive");
            }
        }
    }

    @NonNull
    private static List<EntryInfo> listZipEntries(@NonNull File archive, @Nullable char[] password) throws IOException {
        List<EntryInfo> split = ZipxAesArchiveReader.listSplitEntries(archive, password);
        if (split != null) return withFirstSelectedEntryIdentities(split);
        try {
            ZipFile zip = new ZipFile(archive);
            if (zip.isEncrypted()) {
                if (password == null || password.length == 0) throw new PasswordRequiredException();
                zip.setPassword(password);
            }
            List<EntryInfo> result = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<FileHeader> headers = zip.getFileHeaders();
            List<ZipRawName> rawNames = getZipRawNames(archive);
            if (rawNames.size() != headers.size()) rawNames = Collections.emptyList();
            for (int i = 0; i < headers.size(); i++) {
                FileHeader header = headers.get(i);
                if (header == null) continue;
                String displayName = zipDisplayName(rawNames, i, header.getFileName());
                String path = sanitizeEntryPathForList(displayName);
                if (path == null) continue;
                result.add(new EntryInfo(path, header.isDirectory(), header.getUncompressedSize(), 0L));
            }
            return withFirstSelectedEntryIdentities(result);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (ZipException | SecurityException e) {
            if (isUnknownZipCompression(e)) {
                return listRawZipEntries(archive, password);
            }
            throw new IOException(e);
        }
    }

    private static boolean isZipFileEncrypted(@NonNull File archive) {
        try {
            ZipFile zip = new ZipFile(archive);
            if (zip.isEncrypted()) return true;
            @SuppressWarnings("unchecked")
            List<FileHeader> headers = zip.getFileHeaders();
            for (FileHeader header : headers) {
                if (header != null && header.isEncrypted()) return true;
            }
        } catch (ZipException | SecurityException ignored) {
            // Fall through to the raw ZIP header scan; some ZIPX/AES samples are
            // still easy to classify even when Zip4j cannot build full headers.
        }
        return hasZipEncryptedHeaderSignature(archive);
    }

    private static boolean hasZipEncryptedHeaderSignature(@NonNull File archive) {
        long length = archive.length();
        if (length <= 0L) return false;
        int readSize = (int) Math.min(length, 1024L * 1024L);
        byte[] tail = new byte[readSize];
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            raf.seek(Math.max(0L, length - readSize));
            raf.readFully(tail);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
        for (int i = 0; i + 11 < tail.length; i++) {
            int sig = (tail[i] & 0xff)
                    | ((tail[i + 1] & 0xff) << 8)
                    | ((tail[i + 2] & 0xff) << 16)
                    | ((tail[i + 3] & 0xff) << 24);
            if (sig != 0x02014b50 && sig != 0x04034b50) continue;
            // Local headers omit the central header's two-byte "version made by" field.
            int flagOffset = sig == 0x04034b50 ? 6 : 8;
            int methodOffset = flagOffset + 2;
            int flag = (tail[i + flagOffset] & 0xff) | ((tail[i + flagOffset + 1] & 0xff) << 8);
            int method = (tail[i + methodOffset] & 0xff) | ((tail[i + methodOffset + 1] & 0xff) << 8);
            if ((flag & 0x0001) != 0 || method == 99) return true;
        }
        return false;
    }

    @NonNull
    private static List<ZipRawName> getZipRawNames(@NonNull File archive) {
        String key = zipRawNameCacheKey(archive);
        synchronized (ZIP_RAW_NAME_CACHE) {
            List<ZipRawName> cached = ZIP_RAW_NAME_CACHE.get(key);
            if (cached != null) return cached;
        }
        List<ZipRawName> parsed = readZipRawNames(archive);
        List<ZipRawName> stable = parsed.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(parsed));
        synchronized (ZIP_RAW_NAME_CACHE) {
            ZIP_RAW_NAME_CACHE.put(key, stable);
        }
        return stable;
    }

    @NonNull
    private static String zipRawNameCacheKey(@NonNull File archive) {
        String path;
        try {
            path = archive.getCanonicalPath();
        } catch (IOException | SecurityException ignored) {
            path = archive.getAbsolutePath();
        }
        return path + "\n" + archive.length() + "\n" + archive.lastModified();
    }

    @NonNull
    private static List<ZipRawName> readZipRawNames(@NonNull File archive) {
        long length = archive.length();
        if (length <= 0L) return Collections.emptyList();
        int eocdSearchSize = (int) Math.min(length, 22L + 0xffffL);
        byte[] tail = new byte[eocdSearchSize];
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            raf.seek(Math.max(0L, length - eocdSearchSize));
            raf.readFully(tail);

            int eocdOffset = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (readIntLE(tail, i) == 0x06054b50) {
                    eocdOffset = i;
                    break;
                }
            }
            if (eocdOffset < 0 || eocdOffset + 22 > tail.length) return Collections.emptyList();
            int expectedEntries = readUInt16LE(tail, eocdOffset + 10);
            long centralSize = readUInt32LE(tail, eocdOffset + 12);
            long centralOffset = readUInt32LE(tail, eocdOffset + 16);
            if (expectedEntries == 0xffff || centralSize == 0xffffffffL || centralOffset == 0xffffffffL) {
                return readZipNamesWithCommons(archive);
            }
            if (centralSize <= 0L) return Collections.emptyList();
            // This is a fast-buffer limit, not a limit on browsable ZIP directory metadata.
            if (centralSize > 4L * 1024L * 1024L) return readZipNamesWithCommons(archive);
            if (centralOffset < 0L || centralOffset + centralSize > length) return Collections.emptyList();
            byte[] central = new byte[(int) centralSize];
            raf.seek(centralOffset);
            raf.readFully(central);
            // Pass 1: collect raw names so the whole archive shares one
            // charset decision (ArchiveFilenameDecoder.NameCorpus) - short
            // legacy names inherit the code page their longer siblings
            // establish instead of being scored alone. UTF-8-flagged names
            // keep their flag path and are not part of the legacy corpus.
            ArrayList<byte[]> rawNames = new ArrayList<>();
            ArrayList<Boolean> utf8Flags = new ArrayList<>();
            ArrayList<String> unicodeNames = new ArrayList<>();
            ArrayList<Long> sizes = new ArrayList<>();
            ArchiveFilenameDecoder.NameCorpus corpus = new ArchiveFilenameDecoder.NameCorpus();
            for (int i = 0; i + 46 <= central.length; i++) {
                int sig = readIntLE(central, i);
                if (sig != 0x02014b50) break;
                int flags = readUInt16LE(central, i + 8);
                int nameLength = readUInt16LE(central, i + 28);
                int extraLength = readUInt16LE(central, i + 30);
                int commentLength = readUInt16LE(central, i + 32);
                int nameStart = i + 46;
                int nameEnd = nameStart + nameLength;
                if (nameLength <= 0 || nameEnd > central.length) return Collections.emptyList();
                byte[] rawNameBytes = copyOfRange(central, nameStart, nameLength);
                boolean utf8Flag = (flags & 0x0800) != 0;
                long next = (long) nameEnd + extraLength + commentLength;
                if (next <= i || next > central.length) return Collections.emptyList();
                String unicodeName = ZipEntryNameDecoder.unicodePath(rawNameBytes, utf8Flag,
                        central, nameEnd, extraLength);
                unicodeNames.add(unicodeName);
                rawNames.add(rawNameBytes);
                utf8Flags.add(utf8Flag);
                long size = readUInt32LE(central, i + 24);
                // A ZIP64 sentinel is not a four-gigabyte logical size.
                if (size == 0xffffffffL) return readZipNamesWithCommons(archive);
                sizes.add(size);
                if (!utf8Flag) corpus.observe(rawNameBytes);
                i = (int) next - 1;
            }
            ArrayList<ZipRawName> result = new ArrayList<>();
            for (int i = 0; i < rawNames.size(); i++) {
                String decoded = unicodeNames.get(i);
                if (decoded == null) decoded = ArchiveFilenameDecoder.decodeZipName(rawNames.get(i), utf8Flags.get(i), corpus);
                boolean directory = decoded.replace('\\', '/').endsWith("/");
                result.add(new ZipRawName(decoded, directory, sizes.get(i)));
            }
            return result.size() == expectedEntries ? result : Collections.emptyList();
        } catch (IOException | SecurityException ignored) {
            return Collections.emptyList();
        }
    }

    /** Resolve ZIP64/large-directory metadata without payloads or usable local headers. */
    @NonNull
    private static List<ZipRawName> readZipNamesWithCommons(@NonNull File archive) throws IOException {
        try (org.apache.commons.compress.archivers.zip.ZipFile zip =
                     org.apache.commons.compress.archivers.zip.ZipFile.builder()
                             .setFile(archive).setIgnoreLocalFileHeader(true).get()) {
            ArrayList<ZipArchiveEntry> entries = Collections.list(zip.getEntries());
            ArchiveFilenameDecoder.NameCorpus corpus = new ArchiveFilenameDecoder.NameCorpus();
            for (ZipArchiveEntry entry : entries) {
                byte[] raw = entry.getRawName();
                if (raw != null && !entry.getGeneralPurposeBit().usesUTF8ForNames()) corpus.observe(raw);
            }
            ArrayList<ZipRawName> names = new ArrayList<>(entries.size());
            for (ZipArchiveEntry entry : entries) {
                byte[] raw = entry.getRawName();
                boolean utf8 = entry.getGeneralPurposeBit().usesUTF8ForNames();
                String name = ZipEntryNameDecoder.unicodePath(raw, utf8, entry.getCentralDirectoryExtra());
                if (name == null) name = raw == null ? entry.getName()
                        : ArchiveFilenameDecoder.decodeZipName(raw, utf8, corpus);
                names.add(new ZipRawName(name, name.replace('\\', '/').endsWith("/"), entry.getSize()));
            }
            return names;
        }
    }

    @NonNull
    private static String zipDisplayName(@NonNull List<ZipRawName> rawNames, int index, @Nullable String fallback) {
        if (index >= 0 && index < rawNames.size()) {
            String name = rawNames.get(index).decodedName;
            if (name != null && name.length() > 0) return name;
        }
        return fallback == null ? "" : fallback;
    }

    @NonNull
    private static byte[] copyOfRange(@NonNull byte[] data, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        return out;
    }

    @NonNull
    private static List<EntryInfo> listRawZipEntries(@NonNull File archive,
                                                     @Nullable char[] password) throws IOException {
        if (hasZipEncryptedHeaderSignature(archive) && (password == null || password.length == 0)) {
            throw new PasswordRequiredException();
        }
        List<ZipRawName> centralNames = getZipRawNames(archive);
        if (!centralNames.isEmpty()) {
            ArrayList<EntryInfo> result = new ArrayList<>();
            for (ZipRawName rawName : centralNames) {
                if (rawName == null) continue;
                String path = sanitizeEntryPathForList(rawName.decodedName);
                if (path != null) result.add(new EntryInfo(path, rawName.directory, rawName.size, 0L));
            }
            if (!result.isEmpty()) return withFirstSelectedEntryIdentities(result);
        }
        long length = archive.length();
        int readSize = (int) Math.min(length, 4L * 1024L * 1024L);
        byte[] tail = new byte[readSize];
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            raf.seek(Math.max(0L, length - readSize));
            raf.readFully(tail);
        }
        ArrayList<EntryInfo> result = new ArrayList<>();
        ArrayList<byte[]> tailRawNames = new ArrayList<>();
        ArrayList<Boolean> tailUtf8Flags = new ArrayList<>();
        ArrayList<String> tailUnicodeNames = new ArrayList<>();
        ArrayList<Long> tailSizes = new ArrayList<>();
        ArchiveFilenameDecoder.NameCorpus tailCorpus = new ArchiveFilenameDecoder.NameCorpus();
        for (int i = 0; i + 46 <= tail.length; i++) {
            int sig = readIntLE(tail, i);
            if (sig != 0x02014b50) continue;
            int nameLength = readUInt16LE(tail, i + 28);
            int extraLength = readUInt16LE(tail, i + 30);
            int commentLength = readUInt16LE(tail, i + 32);
            int nameStart = i + 46;
            int nameEnd = nameStart + nameLength;
            if (nameLength <= 0 || nameEnd > tail.length) continue;
            int flags = readUInt16LE(tail, i + 8);
            byte[] rawNameBytes = copyOfRange(tail, nameStart, nameLength);
            boolean utf8Flag = (flags & 0x0800) != 0;
            long next = (long) nameEnd + extraLength + commentLength;
            if (next <= i || next > tail.length) continue;
            String unicodeName = ZipEntryNameDecoder.unicodePath(rawNameBytes, utf8Flag,
                    tail, nameEnd, extraLength);
            tailUnicodeNames.add(unicodeName);
            tailRawNames.add(rawNameBytes);
            tailUtf8Flags.add(utf8Flag);
            tailSizes.add(readUInt32LE(tail, i + 24));
            if (!utf8Flag) tailCorpus.observe(rawNameBytes);
            i = (int) next - 1;
        }
        for (int i = 0; i < tailRawNames.size(); i++) {
            String rawName = tailUnicodeNames.get(i);
            if (rawName == null) rawName = ArchiveFilenameDecoder.decodeZipName(tailRawNames.get(i), tailUtf8Flags.get(i), tailCorpus);
            String path = sanitizeEntryPathForList(rawName);
            if (path != null) {
                boolean directory = rawName.replace('\\', '/').endsWith("/");
                result.add(new EntryInfo(path, directory, tailSizes.get(i), 0L));
            }
        }
        if (result.isEmpty()) throw new IOException("Unsupported ZIP directory");
        return withFirstSelectedEntryIdentities(result);
    }

    @NonNull
    private static List<EntryInfo> listSevenZEntries(@NonNull File archive, @Nullable char[] password) throws IOException {
        List<EntryInfo> result = new ArrayList<>();
        try (SevenZFile sevenZ = openSevenZFile(archive, password)) {
            // getEntries() walks the already-parsed header metadata only.
            // getNextEntry() would additionally build each entry's decoder
            // chain, which throws for coders Commons Compress cannot decode
            // (PPMd 030401, BCJ2 0303011B) even though the entry names and
            // sizes are fully available. Listing must stay decode-free so
            // PPMd/BCJ2 archives remain browsable without the libarchive
            // fallback; extraction keeps using getNextEntry() and falls back.
            for (SevenZArchiveEntry entry : sevenZ.getEntries()) {
                // Differential-backup deletion markers are not readable files.
                if (entry.isAntiItem()) continue;
                String path = sanitizeEntryPathForList(entry.getName());
                if (path == null) continue;
                result.add(new EntryInfo(path, entry.isDirectory(), entry.getSize(), 0L));
            }
            return withFirstSelectedEntryIdentities(result);
        } catch (IOException e) {
            if ((password == null || password.length == 0) && isSevenZPasswordRequired(e)) {
                throw new PasswordRequiredException();
            }
            throw e;
        }
    }

    private static boolean isSevenZPasswordRequired(@NonNull Exception e) {
        String className = e.getClass().getName().toLowerCase(Locale.ROOT);
        if (className.contains("passwordrequired")) return true;
        String message = e.getMessage();
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return lower.contains("password required")
                || lower.contains("password is required")
                || lower.contains("requires password")
                || lower.contains("no password supplied")
                || lower.contains("password has not been set")
                || lower.contains("cannot read encrypted")
                || lower.contains("encrypted content")
                || lower.contains("encrypted header")
                || lower.contains("encrypted archive");
    }

    /**
     * Detects both header-encrypted and data-encrypted 7z archives for the UI
     * password prompt. Commons Compress can list visible headers of a data-
     * encrypted 7z without building the decoder chain, so entry listing alone is
     * not enough. To keep solid-archive cost bounded, probe only the first
     * stream-bearing entry and read at most one byte. Mixed archives with a later
     * encrypted stream after an unencrypted first stream remain extraction-time
     * failures rather than expensive up-front scans.
     */
    private static boolean requiresSevenZPasswordForExtraction(@NonNull File archive) {
        byte[] probe = new byte[1];
        try (SevenZFile sevenZ = openSevenZFile(archive, null)) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (!entry.hasStream()) continue;
                if (sevenZEntryUsesAes(entry)) return true;
                try {
                    int read = sevenZ.read(probe);
                    if (sevenZEntryUsesAes(entry)) return true;
                    return false;
                } catch (IOException e) {
                    return isSevenZPasswordRequired(e) || sevenZEntryUsesAes(entry);
                }
            }
            return false;
        } catch (PasswordRequiredException e) {
            return true;
        } catch (IOException | SecurityException e) {
            return isSevenZPasswordRequired(e);
        }
    }

    private static boolean sevenZEntryUsesAes(@NonNull SevenZArchiveEntry entry) {
        Iterable<? extends SevenZMethodConfiguration> methods = entry.getContentMethods();
        if (methods == null) return false;
        for (SevenZMethodConfiguration methodConfig : methods) {
            if (methodConfig != null && methodConfig.getMethod() == SevenZMethod.AES256SHA256) {
                return true;
            }
        }
        return false;
    }

    private static boolean sevenZArchiveHasAesContext(@NonNull File archive, @Nullable char[] password) {
        try (SevenZFile sevenZ = openSevenZFile(archive, password)) {
            byte[] probe = new byte[1];
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (sevenZEntryUsesAes(entry)) return true;
                if (!entry.hasStream()) continue;
                try {
                    sevenZ.read(probe);
                } catch (IOException e) {
                    if (sevenZEntryUsesAes(entry)) return true;
                    break;
                }
                if (sevenZEntryUsesAes(entry)) return true;
                // Bound the probe to the first stream-bearing entry to avoid
                // solid-draining attacker-controlled data during classification.
                break;
            }
        } catch (IOException | SecurityException ignored) {
            // Fall through to the raw coder-id scan below. Header-encrypted 7z
            // usually fails before content methods are available.
        }
        return rawSevenZHeaderContainsAesCoder(archive);
    }

    private static boolean rawSevenZHeaderContainsAesCoder(@NonNull File archive) {
        try {
            SevenZSplitVolumeResolver.VolumeSet splitVolumes = SevenZSplitVolumeResolver.resolve(archive);
            if (splitVolumes != null) {
                for (File part : splitVolumes.parts) {
                    if (rawFileContainsSevenZAesCoder(part)) return true;
                }
                return false;
            }
        } catch (IOException | SecurityException ignored) {
            // The classifier must never turn a split-chain resolution problem into
            // a crash. If the split chain is broken, normal extraction already
            // reports CORRUPT_ARCHIVE through the primary path.
        }
        return rawFileContainsSevenZAesCoder(archive);
    }

    private static boolean rawFileContainsSevenZAesCoder(@NonNull File file) {
        final byte[] aesCoder = new byte[] { 0x06, (byte) 0xf1, 0x07, 0x01 };
        byte[] buffer = new byte[1024 * 64];
        int matched = 0;
        long remaining = Math.min(file.length(), 16L * 1024L * 1024L);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            while (remaining > 0L) {
                int want = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, want);
                if (read < 0) break;
                remaining -= read;
                for (int i = 0; i < read; i++) {
                    byte b = buffer[i];
                    if (b == aesCoder[matched]) {
                        matched++;
                        if (matched == aesCoder.length) return true;
                    } else {
                        matched = (b == aesCoder[0]) ? 1 : 0;
                    }
                }
            }
        } catch (IOException | SecurityException ignored) {
            return false;
        }
        return false;
    }

    @NonNull
    private static List<EntryInfo> listTarEntries(@NonNull File archive, @NonNull Type type) throws IOException {
        if (type == Type.TAR) {
            try { return withSyntheticDirectories(TarEntryIndex.get(archive).listing); }
            catch (TarIntegrityException invalidHeader) { throw invalidHeader; }
            catch (IOException | RuntimeException unsupportedIndex) {
                ArchiveCancellation.rethrowIfCancelled(unsupportedIndex, null);
                // Preserve stream/native compatibility when the random-access parser cannot index it.
            }
        }
        List<EntryInfo> result = new ArrayList<>();
        java.util.Set<String> files = new java.util.HashSet<>();
        try (InputStream fileIn = new BufferedInputStream(new FileInputStream(archive));
             InputStream payloadIn = wrapTarPayloadInputStream(fileIn, type);
             TarArchiveInputStream tar = new TarArchiveInputStream(payloadIn)) {
            ArchiveEntry entry;
            while (true) {
                ArchiveCancellation.checkpoint(null);
                entry = tar.getNextEntry();
                ArchiveCancellation.checkpoint(null);
                if (entry == null) break;
                if (!isReadableTarMember(entry)) continue;
                if (!tar.canReadEntryData(entry)) throw new IOException("Cannot read TAR entry");
                String path = sanitizeEntryPathForList(entry.getName());
                if (path == null) continue;
                // Selected extraction and the plain TAR index use the first readable member.
                if (!entry.isDirectory() && !files.add(path)) continue;
                result.add(new EntryInfo(path, entry.isDirectory(), ((org.apache.commons.compress.archivers.tar.TarArchiveEntry) entry).getRealSize(), 0L));
            }
            finishTarPayload(payloadIn, type, null);
            return withSyntheticDirectories(result);
        }
    }

    @NonNull
    private static List<EntryInfo> listZipEntriesWithFallback(@NonNull File archive,
                                                              @NonNull Type type,
                                                              @Nullable char[] password) throws IOException {
        try {
            return listZipEntries(archive, password);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (IOException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            List<EntryInfo> fallback = tryListEntriesWithLibarchive(archive, type, password);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            List<EntryInfo> fallback = tryListEntriesWithLibarchive(archive, type, password);
            if (fallback != null) return fallback;
            throw new IOException(e);
        }
    }

    @NonNull
    private static List<EntryInfo> listSevenZEntriesWithFallback(@NonNull File archive,
                                                                 @NonNull Type type,
                                                                 @Nullable char[] password) throws IOException {
        // Validate supplemental container rules before a permissive backend can
        // accept only part of an encoded header. Reuse that parsed listing.
        List<EntryInfo> supplemental = tryListSevenZBcj2Entries(archive, password);
        if (supplemental != null) return supplemental;
        try {
            return listSevenZEntries(archive, password);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (IOException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            List<EntryInfo> fallback = tryListEntriesWithLibarchive(archive, type, password);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            List<EntryInfo> fallback = tryListEntriesWithLibarchive(archive, type, password);
            if (fallback != null) return fallback;
            throw new IOException(e);
        }
    }

    /**
     * Supplemental 7z listing, including supported AES-encoded special headers.
     * Only a negative capability probe permits another backend; a selected
     * reader's failure must not turn into a successful but inconsistent list.
     */
    @Nullable
    private static List<EntryInfo> tryListSevenZBcj2Entries(@NonNull File archive,
                                                            @Nullable char[] password) throws IOException {
        ArchiveCancellation.checkpoint(null);
        try {
            return SevenZBcj2ArchiveReader.tryListSpecialEntries(archive, password);
        } catch (RuntimeException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            throw new IOException("7z supplemental listing failed", e);
        }
    }

    @NonNull
    private static List<EntryInfo> listTarEntriesWithFallback(@NonNull File archive,
                                                              @NonNull Type type,
                                                              @Nullable char[] password) throws IOException {
        try {
            return listTarEntries(archive, type);
        } catch (TarIntegrityException e) {
            throw e;
        } catch (IOException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            List<EntryInfo> fallback = tryListEntriesWithLibarchive(archive, type, password);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            List<EntryInfo> fallback = tryListEntriesWithLibarchive(archive, type, password);
            if (fallback != null) return fallback;
            throw new IOException(e);
        }
    }

    public static boolean extractArchive(@NonNull File archive,
                                         @NonNull File destinationDir,
                                         boolean overwrite,
                                         @Nullable char[] password) {
        return extractArchive(archive, destinationDir, overwrite, password, null);
    }

    public static boolean extractArchive(@NonNull File archive,
                                         @NonNull File destinationDir,
                                         boolean overwrite,
                                         @Nullable char[] password,
                                         @Nullable FileOperationProgress progress) {
        return extractArchiveDetailed(archive, destinationDir, overwrite, password, progress).success;
    }

    @NonNull
    public static ExtractionResult extractArchiveDetailed(@NonNull File archive,
                                                          @NonNull File destinationDir,
                                                          boolean overwrite,
                                                          @Nullable char[] password,
                                                          @Nullable FileOperationProgress progress) {
        if (!isSupportedArchive(archive) || !archive.exists() || !archive.isFile()) {
            return ExtractionResult.failed(ExtractionFailure.FAILED, null);
        }
        File parent = destinationDir.getParentFile();
        if (parent == null || !parent.exists() || !parent.isDirectory() || !parent.canWrite()) {
            return ExtractionResult.failed(ExtractionFailure.FAILED, null);
        }

        File workDir = destinationDir;
        File tempDir = null;
        ArchiveSourceSnapshot replacementSources = null;
        if (destinationDir.exists()) {
            if (!overwrite) return ExtractionResult.failed(ExtractionFailure.FAILED, null);
            try {
                replacementSources = ArchiveSourceSnapshot.capture(archive);
                replacementSources.requireOutsideReplacementTree(destinationDir);
            } catch (IOException | SecurityException unsafeReplacement) {
                ArchiveCancellation.restoreIfCancelled(unsafeReplacement, progress);
                return ExtractionResult.failed(ExtractionFailure.FAILED, unsafeReplacement.getMessage());
            }
            tempDir = buildTempExtractDirectory(parent, destinationDir.getName());
            if (tempDir == null) return ExtractionResult.failed(ExtractionFailure.FAILED, null);
            workDir = tempDir;
        }

        long runtimeBudgetBytes = runtimeExtractionBudgetBytes(parent);
        if (runtimeBudgetBytes <= 0L) {
            return ExtractionResult.failed(ExtractionFailure.FAILED, "Not enough free space for extraction");
        }

        boolean workDirectoryCreated = false;
        try (ArchiveExtractionByteBudget.Scope ignored =
                     ArchiveExtractionByteBudget.begin(runtimeBudgetBytes)) {
            ArchiveCancellation.checkpoint(progress);
            List<EntryInfo> extractionEntries = listEntries(archive, password);
            long estimatedPayloadBytes = estimatePayloadBytesFromEntries(extractionEntries);
            if (estimatedPayloadBytes > 0L) {
                if (progress != null) progress.setTotalBytes(estimatedPayloadBytes);
                if (estimatedPayloadBytes > runtimeBudgetBytes
                        || !hasUsableSpaceForExtraction(parent, estimatedPayloadBytes)) {
                    return ExtractionResult.failed(ExtractionFailure.FAILED, "Not enough free space for extraction");
                }
            }

            ArchiveCancellation.checkpoint(progress);
            if (workDir.exists()) return ExtractionResult.failed(ExtractionFailure.FAILED, null);
            if (!workDir.mkdirs()) return ExtractionResult.failed(ExtractionFailure.FAILED, null);
            workDirectoryCreated = true;
            ArchiveExtractionProgressTracker entryProgress = ArchiveExtractionProgressTracker.create(progress, extractionEntries);
            boolean ok = extractArchiveIntoDirectory(archive, workDir, password, progress, entryProgress);
            if (!ok) {
                deleteFileSystemItem(workDir);
                return ExtractionResult.failed(ExtractionFailure.FAILED, null);
            }

            ArchiveCancellation.checkpoint(progress);
            if (tempDir != null) {
                // Sources or destination aliases can change during listing/decoding.
                // Recheck before the original tree is moved, not after its backup is deleted.
                if (replacementSources == null || !replacementSources.matches(archive)) {
                    throw new IOException("Archive sources changed before destination replacement");
                }
                replacementSources.requireOutsideReplacementTree(destinationDir);
                // Source/alias checks can take time. Honor cancellation before
                // entering the backup/restore sequence that moves the original tree.
                ArchiveCancellation.checkpoint(progress);
                if (!replaceExistingDirectoryWithTemp(destinationDir, tempDir)) {
                    return ExtractionResult.failed(ExtractionFailure.FAILED, null);
                }
            }
            return ExtractionResult.success();
        } catch (IOException | SecurityException e) {
            if (workDirectoryCreated) deleteFileSystemItem(workDir);
            return extractionFailureResult(archive, password, e, progress);
        } catch (RuntimeException | Error failure) {
            // Callback/decoder failures must not leave owned partial plaintext
            // behind or become successful extraction/backend fallback.
            if (workDirectoryCreated) {
                try {
                    if (!deleteFileSystemItem(workDir)) {
                        failure.addSuppressed(new IOException("Cannot remove failed extraction directory"));
                    }
                } catch (RuntimeException | Error cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    public static boolean extractSingleEntry(@NonNull File archive,
                                             @NonNull String entryPath,
                                             @NonNull File outFile,
                                             @Nullable char[] password) {
        return extractSingleEntryDetailed(archive, entryPath, outFile, password).success;
    }

    /**
     * Forward-only entry reader for solid/sequential archives (RAR/7z and the TAR family).
     *
     * These formats have no cheap random per-entry access: a single open stream is read
     * strictly forward, decoding each entry once. Callers keep one reader open for a whole
     * image-viewing session so paging forward never re-decompresses earlier entries.
     */
    public interface ForwardArchiveReader extends java.io.Closeable {
        /** Advances to the next entry, or returns null at end of archive. */
        @Nullable ForwardEntry nextEntry() throws IOException;

        /** Reads bytes of the current entry; returns -1 at the end of the current entry. */
        int read(@NonNull byte[] buffer) throws IOException;

        /**
         * Decodes and discards the current entry without copying it through the
         * caller's heap buffer. Returns true when the backend handled the drain.
         */
        default boolean drainCurrentEntry(long maxDecodedBytes) throws IOException {
            return false;
        }

        /**
         * Whether advancing to the next header can omit decoding the current entry
         * without losing sequential compression state. Solid archives normally
         * return false even if the container API can move to the next header.
         */
        default boolean skipsUnreadEntryOnAdvance() {
            return false;
        }
    }

    public static final class ForwardEntry {
        /** Sanitized internal path, or null when the entry must be skipped (unsafe/unreadable). */
        @Nullable public final String path;
        public final boolean directory;
        public final boolean hasData;

        ForwardEntry(@Nullable String path, boolean directory, boolean hasData) {
            this.path = path;
            this.directory = directory;
            this.hasData = hasData;
        }
    }

    public static boolean isForwardImageReadableType(@Nullable Type type) {
        if (type == null) return false;
        switch (type) {
            case EGG: // File-level eligibility below narrows this to solid EGG.
            case SEVEN_Z:
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

    public static boolean isForwardImageReadableType(@NonNull File archive) {
        Type type = getSupportedArchiveType(archive);
        if (type == Type.EGG) return EggArchiveReader.isSolidArchive(archive);
        // A numeric split TAR still benefits from one prepared, concatenated session.
        if (type == Type.TAR) return isNumericSplitArchive(archive);
        if (type == Type.RAR) {
            return isRarForwardImageReadable(archive);
        }
        return isForwardImageReadableType(type);
    }

    private static boolean isRarForwardImageReadable(@NonNull File archive) {
        // RAR5 and scoped RAR3/RAR4 PPMd also have first-party adapters.
        try {
            int version = RarArchiveLocator.detectRarVersion(archive);
            return version == 5 || version == 4;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    @Nullable
    public static ForwardArchiveReader openForwardReader(@NonNull File archive,
                                                         @Nullable char[] password) throws IOException {
        return openForwardReader(archive, password, null);
    }

    @Nullable
    public static ForwardArchiveReader openForwardReader(@NonNull File archive,
                                                         @Nullable char[] password,
                                                         @Nullable File spoolDirectory) throws IOException {
        PreparedArchive prepared = prepareArchiveForRead(archive);
        try {
            switch (prepared.type) {
                case EGG: {
                    if (spoolDirectory == null) { prepared.close(); return null; }
                    ForwardArchiveReader delegate = EggArchiveReader.openSolidForwardReader(
                            prepared.file, spoolDirectory, password);
                    if (delegate == null) { prepared.close(); return null; }
                    return ownForwardReader(delegate, prepared);
                }
                case SEVEN_Z:
                    if (spoolDirectory != null && ((password != null && password.length > 0)
                            || !LibarchiveNativeBridge.isAvailable())) {
                        ForwardArchiveReader special = SevenZBcj2ArchiveReader.openSpecialForwardReader(
                                prepared.file, password, spoolDirectory);
                        if (special != null) return ownForwardReader(special, prepared);
                    }
                    if (shouldOpenSevenZForwardWithLibarchive(prepared.file, password)) {
                        String[] archivePaths = sevenZForwardArchivePaths(prepared.file);
                        LibarchiveNativeBridge.ForwardStream stream =
                                LibarchiveNativeBridge.openForwardStream(
                                        archivePaths, null);
                        return new LibarchiveForwardReader(stream, prepared);
                    }
                    return new SevenZForwardReader(prepared, openSevenZFile(prepared.file, password));
                case TAR:
                case TAR_GZ:
                case TAR_BZ2:
                case TAR_XZ:
                case TAR_LZMA:
                case TAR_Z:
                case TAR_LZ4: {
                    InputStream fileIn = new BufferedInputStream(new FileInputStream(prepared.file));
                    try {
                        InputStream payload = wrapTarPayloadInputStream(fileIn, prepared.type);
                        return new TarForwardReader(prepared, new TarArchiveInputStream(payload), payload, fileIn);
                    } catch (IOException | RuntimeException | Error e) {
                        try { fileIn.close(); }
                        catch (IOException | RuntimeException | Error cleanup) {
                            if (cleanup != e) e.addSuppressed(cleanup);
                        }
                        throw e;
                    }
                }
                case TAR_ZST: {
                    // The Maven zstd-jni JAR carries desktop/Linux natives, not Android
                    // jni/<abi> payloads. Use libarchive's Android Zstd filter directly;
                    // the Java stream remains a JVM fallback when the bridge is absent.
                    if (LibarchiveNativeBridge.isAvailable()) {
                        LibarchiveNativeBridge.ForwardStream stream =
                                LibarchiveNativeBridge.openForwardStream(
                                        new String[]{prepared.file.getAbsolutePath()}, null);
                        return new LibarchiveForwardReader(stream, prepared);
                    }
                    InputStream fileIn = new BufferedInputStream(new FileInputStream(prepared.file));
                    try {
                        InputStream payload = wrapTarPayloadInputStream(fileIn, prepared.type);
                        return new TarForwardReader(prepared, new TarArchiveInputStream(payload), payload, fileIn);
                    } catch (IOException | RuntimeException | Error e) {
                        try { fileIn.close(); }
                        catch (IOException | RuntimeException | Error cleanup) {
                            if (cleanup != e) e.addSuppressed(cleanup);
                        }
                        if (e instanceof IOException) throw (IOException) e;
                        if (e instanceof Error && !(e instanceof LinkageError)) throw (Error) e;
                        throw new IOException(e);
                    }
                }
                case RAR: {
                    if (spoolDirectory != null) {
                        ForwardArchiveReader firstParty = Rar5CompressedArchiveExtractor.openForwardReader(
                                prepared.file, password, spoolDirectory, LibarchiveNativeBridge.isRarFormatAvailable());
                        if (firstParty != null) return ownForwardReader(firstParty, prepared);
                        ForwardArchiveReader ppmd = null;
                        try {
                            ppmd = Rar3PpmdSolidArchiveExtractor.openForwardReader(prepared.file, password,
                                    spoolDirectory, LibarchiveNativeBridge.isRarFormatAvailable());
                        } catch (IOException unsupportedPpmdHeader) {
                            ArchiveCancellation.rethrowIfCancelled(unsupportedPpmdHeader, null);
                            // Broader native/decryption paths remain available for ineligible headers.
                        }
                        if (ppmd != null) return ownForwardReader(ppmd, prepared);
                        ForwardArchiveReader checked = Rar3CheckedForwardReader.open(
                                prepared.file, spoolDirectory, LibarchiveNativeBridge.isRarFormatAvailable());
                        if (checked != null) return ownForwardReader(checked, prepared);
                    }
                    // libarchive reads RAR forward-only; resolve the volume chain (one file for a
                    // single-volume archive) and hand the ordered paths to the streaming bridge.
                    // libarchive's own volume input handles concatenation and embedded SFX offsets.
                    List<File> volumes = RarArchiveLocator.collectReadableVolumes(prepared.file);
                    String[] paths = new String[volumes.size()];
                    for (int i = 0; i < volumes.size(); i++) {
                        paths[i] = volumes.get(i).getAbsolutePath();
                    }
                    LibarchiveNativeBridge.ForwardStream stream;
                    try {
                        stream = LibarchiveNativeBridge.openForwardStream(paths, password);
                    } catch (IOException nativeFailure) {
                        ArchiveCancellation.rethrowIfCancelled(nativeFailure, null);
                        if (spoolDirectory != null) {
                            try {
                                ForwardArchiveReader checked = Rar3CheckedForwardReader.open(
                                        prepared.file, spoolDirectory, false);
                                if (checked != null) return ownForwardReader(checked, prepared);
                            } catch (IOException checkedFailure) {
                                ArchiveCancellation.rethrowIfCancelled(checkedFailure, null);
                                nativeFailure.addSuppressed(checkedFailure);
                            }
                        }
                        throw nativeFailure;
                    }
                    return new LibarchiveForwardReader(stream, prepared);
                }
                case LIBARCHIVE: {
                    if (spoolDirectory != null) {
                        CabArchiveReader.Session cab = CabArchiveReader.openIfNeeded(prepared.file,null);
                        if (cab != null) {
                            try { return ownForwardReader(new CabForwardReader(cab,spoolDirectory),prepared); }
                            catch (RuntimeException | Error failure) {
                                cab.retire(failure);
                                throw failure;
                            }
                        }
                    } else if (CabVolumeResolver.hasVolumeLinks(prepared.file)) {
                        // The CAB bridge verifies whole entries before exposing spool bytes.
                        prepared.close();
                        return null;
                    }
                    LibarchiveNativeBridge.ForwardStream stream =
                            LibarchiveNativeBridge.openForwardStream(
                                    new String[]{prepared.file.getAbsolutePath()}, password);
                    return new LibarchiveForwardReader(stream, prepared);
                }
                default:
                    prepared.close();
                    return null;
            }
        } catch (IOException | RuntimeException | Error e) {
            prepared.close();
            if (e instanceof IOException) throw (IOException) e;
            if (e instanceof Error) throw (Error) e;
            throw new IOException(e);
        }
    }

    private static ForwardArchiveReader ownForwardReader(ForwardArchiveReader delegate, PreparedArchive prepared) {
        return new ForwardArchiveReader() {
            @Override public ForwardEntry nextEntry() throws IOException { return delegate.nextEntry(); }
            @Override public int read(byte[] buffer) throws IOException { return delegate.read(buffer); }
            @Override public boolean drainCurrentEntry(long maximum) throws IOException {
                return delegate.drainCurrentEntry(maximum);
            }
            @Override public boolean skipsUnreadEntryOnAdvance() { return delegate.skipsUnreadEntryOnAdvance(); }
            @Override public void close() throws IOException {
                try { delegate.close(); } finally { prepared.close(); }
            }
        };
    }

    private static boolean shouldOpenSevenZForwardWithLibarchive(@NonNull File archive,
                                                                  @Nullable char[] password) throws IOException {
        boolean hasPassword = password != null && password.length > 0;
        boolean splitArchive = SevenZSplitVolumeResolver.isSevenZSplitPart(archive);
        if (!LibarchiveNativeBridge.isAvailable() || hasPassword) {
            return false;
        }
        // A split archive's encoded header may live in a later volume, so the
        // single-file special-coder probe cannot classify it reliably. Route
        // every plain split through the volume-aware native stream; a method the
        // backend cannot decode still falls back to the dedicated whole path.
        if (splitArchive) return true;
        try {
            return shouldUseLibarchiveForwardForSevenZ(
                    true,
                    false,
                    SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null),
                    false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean shouldUseLibarchiveForwardForSevenZ(boolean libarchiveAvailable,
                                                        boolean hasPassword,
                                                        boolean specialCoder) {
        return shouldUseLibarchiveForwardForSevenZ(
                libarchiveAvailable, hasPassword, specialCoder, false);
    }

    static boolean shouldUseLibarchiveForwardForSevenZ(boolean libarchiveAvailable,
                                                        boolean hasPassword,
                                                        boolean specialCoder,
                                                        boolean splitArchive) {
        return libarchiveAvailable && !hasPassword && (specialCoder || splitArchive);
    }

    @NonNull
    private static String[] sevenZForwardArchivePaths(@NonNull File archive) throws IOException {
        SevenZSplitVolumeResolver.VolumeSet split = SevenZSplitVolumeResolver.resolve(archive);
        if (split == null) return new String[]{archive.getAbsolutePath()};
        String[] paths = new String[split.parts.size()];
        for (int i = 0; i < split.parts.size(); i++) {
            paths[i] = split.parts.get(i).getAbsolutePath();
        }
        return paths;
    }

    private static final class SevenZForwardReader implements ForwardArchiveReader {
        @NonNull private final PreparedArchive prepared;
        @NonNull private final SevenZFile sevenZ;

        SevenZForwardReader(@NonNull PreparedArchive prepared, @NonNull SevenZFile sevenZ) {
            this.prepared = prepared;
            this.sevenZ = sevenZ;
        }

        @Nullable
        @Override
        public ForwardEntry nextEntry() throws IOException {
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                ArchiveCancellation.checkpoint(null);
                if (entry.isAntiItem()) continue;
                String path = sanitizeEntryPathForList(entry.getName());
                return new ForwardEntry(path, entry.isDirectory(), entry.hasStream());
            }
            return null;
        }

        @Override
        public int read(@NonNull byte[] buffer) throws IOException {
            return sevenZ.read(buffer);
        }

        @Override
        public void close() throws IOException {
            try {
                sevenZ.close();
            } finally {
                prepared.close();
            }
        }
    }

    private static final class TarForwardReader implements ForwardArchiveReader {
        @NonNull private final PreparedArchive prepared;
        @NonNull private final TarArchiveInputStream tar;
        @NonNull private final InputStream payload;
        @NonNull private final InputStream fileIn;
        @Nullable private IOException failure;
        private boolean finished, closed;
        private boolean tarClosed, fileClosed;

        TarForwardReader(@NonNull PreparedArchive prepared,
                         @NonNull TarArchiveInputStream tar,
                         @NonNull InputStream payload,
                         @NonNull InputStream fileIn) {
            this.prepared = prepared;
            this.tar = tar;
            this.payload = payload;
            this.fileIn = fileIn;
        }

        @Nullable
        @Override
        public ForwardEntry nextEntry() throws IOException {
            checkpoint();
            if (finished) return null;
            try {
                ArchiveEntry entry = tar.getNextEntry();
                ArchiveCancellation.checkpoint(null);
                if (entry == null) {
                    finishTarPayload(payload, prepared.type, null);
                    finished = true;
                    return null;
                }
                if (!isReadableTarMember(entry) || !tar.canReadEntryData(entry)) {
                    return new ForwardEntry(null, entry.isDirectory(), false);
                }
                boolean directory = entry.isDirectory();
                String path = sanitizeEntryPathForList(entry.getName());
                return new ForwardEntry(path, directory, !directory);
            } catch (IOException | RuntimeException error) {
                throw retire(error);
            } catch (Error error) {
                retire(error);
                throw error;
            }
        }

        @Override
        public int read(@NonNull byte[] buffer) throws IOException {
            checkpoint();
            if (buffer.length == 0) return 0;
            if (finished) return -1;
            try {
                int count = tar.read(buffer);
                ArchiveCancellation.checkpoint(null);
                if (count < -1 || count == 0 || count > buffer.length) {
                    throw new IOException("Invalid TAR forward read progress");
                }
                return count;
            }
            catch (IOException | RuntimeException error) { throw retire(error); }
            catch (Error error) { retire(error); throw error; }
        }

        private void checkpoint() throws IOException {
            if (failure != null) throw failure;
            if (closed) throw new IOException("TAR forward reader is closed");
            ArchiveCancellation.checkpoint(null);
        }

        private IOException retire(Throwable error) {
            failure = error instanceof IOException ? (IOException) error
                    : new IOException("TAR forward reader failed", error);
            try { close(); }
            catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != error && cleanup != failure) error.addSuppressed(cleanup);
            }
            return failure;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            Throwable cleanupFailure = null;
            if (!tarClosed) {
                try { tar.close(); tarClosed = true; }
                catch (IOException | RuntimeException | Error error) { cleanupFailure = error; }
            }
            if (!fileClosed) {
                try { fileIn.close(); fileClosed = true; }
                catch (IOException | RuntimeException | Error error) {
                    if (cleanupFailure == null) cleanupFailure = error;
                    else if (cleanupFailure != error) cleanupFailure.addSuppressed(error);
                }
            }
            prepared.close();
            // Failed components remain owned for a later close; reads stay closed.
            if (cleanupFailure instanceof IOException) throw (IOException) cleanupFailure;
            if (cleanupFailure instanceof RuntimeException) throw (RuntimeException) cleanupFailure;
            if (cleanupFailure instanceof Error) throw (Error) cleanupFailure;
        }
    }

    /**
     * Optional image-cache fast path. Null means ineligible, not success: the caller
     * must use its normal serialized route. Payload/integrity errors never fall through.
     * Each call owns its I/O and decoder; this method does not create worker threads.
     */
    @Nullable
    public static ExtractionResult tryExtractIndependentZipImageEntry(@NonNull File archive,
                                                                      @NonNull String entryPath,
                                                                      @NonNull File outFile) {
        String name = archive.getName().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".zip") && !name.endsWith(".cbz")) return null;
        if (Thread.currentThread().isInterrupted()) {
            return ExtractionResult.failed(ExtractionFailure.FAILED, "Archive extraction interrupted");
        }
        String normalized = sanitizeEntryPathForList(entryPath);
        if (normalized == null || normalized.endsWith("/")) return null;
        File parent = outFile.getParentFile();
        if (parent == null) return ExtractionResult.failed(ExtractionFailure.FAILED, null);
        try {
            if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                return ExtractionResult.failed(ExtractionFailure.FAILED, null);
            }
            long budget = runtimeExtractionBudgetBytes(parent);
            if (budget <= 0) return ExtractionResult.failed(ExtractionFailure.FAILED, "Not enough free space for extraction");
            try (ArchiveExtractionByteBudget.Scope ignored = ArchiveExtractionByteBudget.begin(budget)) {
                Boolean result = ZipxAesArchiveReader.tryExtractIndependentPlainEntry(archive, normalized, outFile);
                return result == null ? null : result ? ExtractionResult.success()
                        : ExtractionResult.failed(ExtractionFailure.FAILED, null);
            }
        } catch (IOException | SecurityException e) {
            return extractionFailureResult(archive, null, e, null);
        }
    }

    @NonNull
    public static ExtractionResult extractSingleEntryDetailed(@NonNull File archive,
                                                              @NonNull String entryPath,
                                                              @NonNull File outFile,
                                                              @Nullable char[] password) {
        if (Thread.currentThread().isInterrupted()) {
            return ExtractionResult.failed(ExtractionFailure.FAILED, "Archive extraction interrupted");
        }
        String normalized = sanitizeEntryPathForList(entryPath);
        if (normalized == null || normalized.endsWith("/")) {
            return ExtractionResult.failed(ExtractionFailure.FAILED, null);
        }
        File parent = outFile.getParentFile();
        if (parent == null) return ExtractionResult.failed(ExtractionFailure.FAILED, null);
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            return ExtractionResult.failed(ExtractionFailure.FAILED, null);
        }

        long runtimeBudgetBytes = runtimeExtractionBudgetBytes(parent);
        if (runtimeBudgetBytes <= 0L) {
            return ExtractionResult.failed(ExtractionFailure.FAILED, "Not enough free space for extraction");
        }

        try {
            // Never move/truncate the selected archive or another declared volume.
            // Failed metadata capture is not a reusable identity; the decoder still
            // validates unresolved inputs and the output guard rolls them back.
            if (archive.getCanonicalFile().equals(outFile.getCanonicalFile())
                    || ArchiveSourceSnapshot.capture(archive).containsFile(outFile)) {
                return ExtractionResult.failed(ExtractionFailure.FAILED,
                        "Extraction output is an archive source volume");
            }
            try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile);
                 ArchiveExtractionByteBudget.Scope ignored =
                         ArchiveExtractionByteBudget.begin(runtimeBudgetBytes)) {
                boolean ok;
                // Do not commit until the prepared source and its temporary spool close.
                try (PreparedArchive prepared = prepareArchiveForRead(archive)) {
                    switch (prepared.type) {
                        case ZIP:
                            ok = extractSingleZipEntryWithFallback(prepared.file, prepared.type, normalized, outFile, password);
                            break;
                        case SEVEN_Z:
                            ok = extractSingleSevenZEntryWithFallback(prepared.file, prepared.type, normalized, outFile, password);
                            break;
                        case RAR:
                            ok = extractSingleRarEntry(prepared.file, normalized, outFile, password);
                            break;
                        case LIBARCHIVE:
                            ok = LibarchiveArchiveReader.extractSingleEntry(
                                    prepared.file, normalized, outFile, password, null);
                            break;
                        case ALZ:
                            ok = AlzipArchiveReader.extractSingleEntry(prepared.file, normalized, outFile, password);
                            break;
                        case EGG:
                            ok = EggArchiveReader.extractSingleEntry(prepared.file, normalized, outFile, password);
                            break;
                        case TAR:
                        case TAR_GZ:
                        case TAR_BZ2:
                        case TAR_XZ:
                        case TAR_LZMA:
                        case TAR_Z:
                        case TAR_ZST:
                        case TAR_LZ4:
                            ok = extractSingleTarEntryWithFallback(prepared.file, prepared.type, normalized, outFile);
                            break;
                        case SINGLE_GZ:
                        case SINGLE_BZ2:
                        case SINGLE_XZ:
                        case SINGLE_LZMA:
                        case SINGLE_Z:
                        case SINGLE_ZST:
                        case SINGLE_LZ4:
                            ok = extractSingleCompressedEntry(prepared.file, archive, normalized, outFile, prepared.type);
                            break;
                        default:
                            ok = false;
                    }
                }
                if (!ok) return ExtractionResult.failed(ExtractionFailure.FAILED, null);
                ArchiveCancellation.checkpoint(null);
                guard.commit();
                return ExtractionResult.success();
            }
        } catch (IOException | SecurityException e) {
            return extractionFailureResult(archive, password, e, null);
        }
    }

    static ExtractionResult extractionFailureResult(File archive, char[] password,
                                                     Exception failure, FileOperationProgress progress) {
        // A cancelled read must not become a password prompt, metadata reopen or
        // whole-archive fallback merely because this API returns instead of throws.
        ExtractionFailure kind = ArchiveCancellation.restoreIfCancelled(failure, progress)
                ? ExtractionFailure.FAILED : classifyExtractionFailure(archive, password, failure);
        return ExtractionResult.failed(kind, failure.getMessage());
    }

    private static void cleanupPartialSingleEntryOutput(@NonNull File outFile) {
        try { deleteFileSystemItem(outFile); } catch (SecurityException ignored) {}
    }

    public static boolean createZipArchive(@NonNull List<File> sources,
                                           @NonNull File outFile,
                                           @Nullable FileOperationProgress progress) {
        return ArchiveZipWriter.create(sources, outFile, progress);
    }

    private static boolean extractArchiveIntoDirectory(@NonNull File archive,
                                                       @NonNull File targetDir,
                                                       @Nullable char[] password,
                                                       @Nullable FileOperationProgress progress,
                                                       @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        try (PreparedArchive prepared = prepareArchiveForRead(archive)) {
            if (progress != null) progress.setDetail(archive.getName());
            switch (prepared.type) {
                case ZIP:
                    return extractZipIntoDirectoryWithFallback(prepared.file, prepared.type, targetDir, password, progress, entryProgress);
                case SEVEN_Z:
                    return extractSevenZIntoDirectoryWithFallback(prepared.file, prepared.type, targetDir, password, progress, entryProgress);
                case RAR:
                    return extractRarIntoDirectory(prepared.file, targetDir, password, progress, entryProgress);
                case LIBARCHIVE:
                    return LibarchiveArchiveReader.extractArchiveIntoDirectory(
                            prepared.file, targetDir, password, progress, entryProgress);
                case ALZ:
                    return AlzipArchiveReader.extractArchiveIntoDirectory(prepared.file, targetDir, password, progress, entryProgress);
                case EGG:
                    return EggArchiveReader.extractArchiveIntoDirectory(prepared.file, targetDir, password, progress, entryProgress);
                case TAR:
                case TAR_GZ:
                case TAR_BZ2:
                case TAR_XZ:
                case TAR_LZMA:
                case TAR_Z:
                case TAR_ZST:
                case TAR_LZ4:
                    return extractTarIntoDirectoryWithFallback(prepared.file, prepared.type, targetDir, progress, entryProgress);
                case SINGLE_GZ:
                case SINGLE_BZ2:
                case SINGLE_XZ:
                case SINGLE_LZMA:
                case SINGLE_Z:
                case SINGLE_ZST:
                case SINGLE_LZ4:
                    return extractSingleCompressedIntoDirectory(prepared.file, archive, targetDir, prepared.type, progress, entryProgress);
                default:
                    return false;
            }
        }
    }


    private static boolean extractZipIntoDirectoryWithFallback(@NonNull File archive,
                                                               @NonNull Type type,
                                                               @NonNull File targetDir,
                                                               @Nullable char[] password,
                                                               @Nullable FileOperationProgress progress,
                                                               @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        if (ZipxAesArchiveReader.canExtractArchive(archive)) {
            return ZipxAesArchiveReader.extractArchiveIntoDirectory(
                    archive, targetDir, password, progress, entryProgress);
        }
        if (ZipxAesArchiveReader.hasUnsupportedAesMethod(archive)) {
            throw new UnsupportedArchiveFeatureException(
                    "ZIPX AES compression method is unsupported");
        }
        try {
            return extractZipIntoDirectory(archive, targetDir, password, progress, entryProgress);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (ZipPpmdEntryExtractor.ExtractionException e) {
            throw e; // Revision/integrity/source failures must not become backend fallback.
        } catch (UnsupportedArchiveFeatureException e) {
            ArchiveCancellation.rethrowIfCancelled(e, progress);
            Boolean fallback = tryExtractArchiveWithLibarchiveAfterDedicatedFailure(
                    archive, type, targetDir, password, progress, entryProgress);
            if (fallback != null) return fallback;
            throw e;
        } catch (IOException e) {
            ArchiveCancellation.rethrowIfCancelled(e, progress);
            Boolean fallback = tryExtractArchiveWithLibarchiveAfterDedicatedFailure(
                    archive, type, targetDir, password, progress, entryProgress);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
            ArchiveCancellation.rethrowIfCancelled(e, progress);
            Boolean fallback = tryExtractArchiveWithLibarchiveAfterDedicatedFailure(
                    archive, type, targetDir, password, progress, entryProgress);
            if (fallback != null) return fallback;
            throw new IOException(e);
        }
    }

    private static boolean extractSevenZIntoDirectoryWithFallback(@NonNull File archive,
                                                                  @NonNull Type type,
                                                                  @NonNull File targetDir,
                                                                  @Nullable char[] password,
                                                                  @Nullable FileOperationProgress progress,
                                                                  @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        Boolean supplemental = tryExtractSevenZBcj2IntoDirectory(archive, targetDir, password, progress, entryProgress);
        if (supplemental != null) return supplemental;
        try {
            return extractSevenZIntoDirectory(archive, targetDir, password, progress, entryProgress);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (IOException e) {
            ArchiveCancellation.rethrowIfCancelled(e, progress);
            Boolean fallback = tryExtractArchiveWithLibarchiveAfterDedicatedFailure(
                    archive, type, targetDir, password, progress, entryProgress);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
            ArchiveCancellation.rethrowIfCancelled(e, progress);
            Boolean fallback = tryExtractArchiveWithLibarchiveAfterDedicatedFailure(
                    archive, type, targetDir, password, progress, entryProgress);
            if (fallback != null) return fallback;
            throw new IOException(e);
        }
    }

    private static boolean extractTarIntoDirectoryWithFallback(@NonNull File archive,
                                                               @NonNull Type type,
                                                               @NonNull File targetDir,
                                                               @Nullable FileOperationProgress progress,
                                                               @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        try {
            return extractTarIntoDirectory(archive, targetDir, type, progress, entryProgress);
        } catch (TarIntegrityException | TarEntryIndex.ExtractionException e) {
            throw e;
        } catch (IOException e) {
            ArchiveCancellation.rethrowIfCancelled(e, progress);
            Boolean fallback = tryExtractArchiveWithLibarchiveAfterDedicatedFailure(
                    archive, type, targetDir, null, progress, entryProgress);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
            ArchiveCancellation.rethrowIfCancelled(e, progress);
            Boolean fallback = tryExtractArchiveWithLibarchiveAfterDedicatedFailure(
                    archive, type, targetDir, null, progress, entryProgress);
            if (fallback != null) return fallback;
            throw new IOException(e);
        }
    }

    private static boolean extractSingleZipEntryWithFallback(@NonNull File archive,
                                                             @NonNull Type type,
                                                             @NonNull String entryPath,
                                                             @NonNull File outFile,
                                                             @Nullable char[] password) throws IOException {
        // Ordinary ZIPs already have a cached Zip4j index. Do not open and
        // reparse them with Commons Compress merely to rule out AES ZIPX.
        // The supplement defers index-parser failures to Zip4j, but actual
        // extraction/authentication failures must remain final (outside the
        // generic backend-fallback catch below).
        if (needsZipxAesProbe(archive)) {
            Boolean zipxAes = ZipxAesArchiveReader.tryExtractSingleEntry(
                    archive, entryPath, outFile, password);
            if (zipxAes != null) return zipxAes;
        }
        try {
            return extractSingleZipEntry(archive, entryPath, outFile, password);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (ZipPpmdEntryExtractor.ExtractionException e) {
            throw e;
        } catch (UnsupportedArchiveFeatureException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            Boolean fallback = tryExtractSingleEntryWithLibarchiveAfterDedicatedFailure(
                    archive, type, entryPath, outFile, password, null);
            if (fallback != null) return fallback;
            throw e;
        } catch (IOException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            Boolean fallback = tryExtractSingleEntryWithLibarchiveAfterDedicatedFailure(
                    archive, type, entryPath, outFile, password, null);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            Boolean fallback = tryExtractSingleEntryWithLibarchiveAfterDedicatedFailure(
                    archive, type, entryPath, outFile, password, null);
            if (fallback != null) return fallback;
            throw new IOException(e);
        }
    }

    static boolean needsZipxAesProbe(@NonNull File archive) {
        if (ZipxAesArchiveReader.hasCachedIndex(archive)) return true;
        try {
            return ZipVolumeResolver.forSnapshot(archive).size() > 1 || getZipIndex(archive).encrypted;
        } catch (IOException | RuntimeException e) {
            // Zip4j may not understand this container; retain supplemental routing.
            return true;
        }
    }

    /** Run off the UI thread; in-flight index leases remain valid until release. */
    public static void releaseViewerArchiveIndex(@NonNull File archive) {
        ZipxAesArchiveReader.releaseArchiveIndex(archive);
        TarEntryIndex.release(archive);
        AlzipArchiveReader.releaseArchiveIndex(archive);
        EggArchiveReader.releaseArchiveIndex(archive);
    }

    private static boolean extractSingleSevenZEntryWithFallback(@NonNull File archive,
                                                                @NonNull Type type,
                                                                @NonNull String entryPath,
                                                                @NonNull File outFile,
                                                                @Nullable char[] password) throws IOException {
        Boolean supplemental = tryExtractSevenZBcj2SingleEntry(archive, entryPath, outFile, password);
        if (supplemental != null) return supplemental;
        try {
            return extractSingleSevenZEntry(archive, entryPath, outFile, password);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (IOException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            Boolean fallback = tryExtractSingleEntryWithLibarchiveAfterDedicatedFailure(
                    archive, type, entryPath, outFile, password, null);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            Boolean fallback = tryExtractSingleEntryWithLibarchiveAfterDedicatedFailure(
                    archive, type, entryPath, outFile, password, null);
            if (fallback != null) return fallback;
            throw new IOException(e);
        }
    }

    private static boolean extractSingleTarEntryWithFallback(@NonNull File archive,
                                                             @NonNull Type type,
                                                             @NonNull String entryPath,
                                                             @NonNull File outFile) throws IOException {
        try {
            return extractSingleTarEntry(archive, entryPath, outFile, type);
        } catch (TarIntegrityException | TarEntryIndex.ExtractionException e) {
            throw e;
        } catch (IOException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            Boolean fallback = tryExtractSingleEntryWithLibarchiveAfterDedicatedFailure(
                    archive, type, entryPath, outFile, null, null);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            Boolean fallback = tryExtractSingleEntryWithLibarchiveAfterDedicatedFailure(
                    archive, type, entryPath, outFile, null, null);
            if (fallback != null) return fallback;
            throw new IOException(e);
        }
    }

    /**
     * Supplemental 7z extraction. A negative capability probe keeps ordinary
     * Commons/native routing. Once selected, payload, output, source-change and
     * close failures are terminal: retrying can hide damage or mix outputs from
     * different decoders. Folder/selected guards retain their rollback ownership.
     */
    @Nullable
    private static Boolean tryExtractSevenZBcj2IntoDirectory(@NonNull File archive,
                                                             @NonNull File targetDir,
                                                             @Nullable char[] password,
                                                             @Nullable FileOperationProgress progress,
                                                             @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        ArchiveCancellation.checkpoint(progress);
        try {
            if (!SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, password, progress)) return null;
            return SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, targetDir, password, progress, entryProgress);
        } catch (RuntimeException e) {
            ArchiveCancellation.rethrowIfCancelled(e, progress);
            throw new IOException("7z supplemental extraction failed", e);
        }
    }

    @Nullable
    private static Boolean tryExtractSevenZBcj2SingleEntry(@NonNull File archive,
                                                           @NonNull String entryPath,
                                                           @NonNull File outFile,
                                                           @Nullable char[] password) throws IOException {
        ArchiveCancellation.checkpoint(null);
        try {
            if (!SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, password)) return null;
            return SevenZBcj2ArchiveReader.extractSingleEntry(archive, entryPath, outFile, password);
        } catch (RuntimeException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            throw new IOException("7z supplemental extraction failed", e);
        }
    }

    @Nullable
    private static Boolean tryExtractArchiveWithLibarchiveAfterDedicatedFailure(@NonNull File archive,
                                                                                @NonNull Type type,
                                                                                @NonNull File targetDir,
                                                                                @Nullable char[] password,
                                                                                @Nullable FileOperationProgress progress,
                                                                                @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        ArchiveCancellation.checkpoint(progress);
        if (!shouldUseLibarchiveFallbackAfterDedicated(type)) return null;
        if (!LibarchiveArchiveReader.isAvailable()) return null;
        if (!clearDirectoryContents(targetDir)) return null;
        return tryExtractArchiveWithLibarchive(archive, type, targetDir, password, progress, entryProgress);
    }

    @Nullable
    private static Boolean tryExtractSingleEntryWithLibarchiveAfterDedicatedFailure(@NonNull File archive,
                                                                                    @NonNull Type type,
                                                                                    @NonNull String entryPath,
                                                                                    @NonNull File outFile,
                                                                                    @Nullable char[] password,
                                                                                    @Nullable FileOperationProgress progress) throws IOException {
        ArchiveCancellation.checkpoint(progress);
        if (!shouldUseLibarchiveFallbackAfterDedicated(type)) return null;
        if (!LibarchiveArchiveReader.isAvailable()) return null;
        try { outFile.delete(); } catch (SecurityException ignored) {}
        return tryExtractSingleEntryWithLibarchive(archive, type, entryPath, outFile, password, progress);
    }


    @Nullable
    private static List<EntryInfo> tryListEntriesWithLibarchive(@NonNull File archive,
                                                                @NonNull Type type,
                                                                @Nullable char[] password) throws IOException {
        ArchiveCancellation.checkpoint(null);
        if (!canUseGenericLibarchive(type)) return null;
        if (!LibarchiveArchiveReader.isAvailable()) return null;
        try {
            return LibarchiveArchiveReader.listEntries(archive, password);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (UnsupportedArchiveFeatureException | SecurityException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            return null;
        } catch (IOException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
            return null;
        }
    }

    @Nullable
    private static Boolean tryExtractArchiveWithLibarchive(@NonNull File archive,
                                                           @NonNull Type type,
                                                           @NonNull File targetDir,
                                                           @Nullable char[] password,
                                                           @Nullable FileOperationProgress progress,
                                                           @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        ArchiveCancellation.checkpoint(progress);
        if (!canUseGenericLibarchive(type)) return null;
        if (!LibarchiveArchiveReader.isAvailable()) return null;
        try {
            return LibarchiveArchiveReader.extractArchiveIntoDirectory(
                    archive, targetDir, password, progress, entryProgress);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (UnsupportedArchiveFeatureException | SecurityException e) {
            ArchiveCancellation.rethrowIfCancelled(e, progress);
            return null;
        } catch (IOException e) {
            ArchiveCancellation.rethrowIfCancelled(e, progress);
            return null;
        }
    }

    @Nullable
    private static Boolean tryExtractSingleEntryWithLibarchive(@NonNull File archive,
                                                               @NonNull Type type,
                                                               @NonNull String entryPath,
                                                               @NonNull File outFile,
                                                               @Nullable char[] password,
                                                               @Nullable FileOperationProgress progress) throws IOException {
        ArchiveCancellation.checkpoint(progress);
        if (!canUseGenericLibarchive(type)) return null;
        if (!LibarchiveArchiveReader.isAvailable()) return null;
        try {
            return LibarchiveArchiveReader.extractSingleEntry(archive, entryPath, outFile, password, progress);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (UnsupportedArchiveFeatureException | SecurityException e) {
            ArchiveCancellation.rethrowIfCancelled(e, progress);
            try { outFile.delete(); } catch (SecurityException ignored) {}
            return null;
        } catch (IOException e) {
            ArchiveCancellation.rethrowIfCancelled(e, progress);
            try { outFile.delete(); } catch (SecurityException ignored) {}
            return null;
        }
    }

    private static boolean shouldUseLibarchiveFallbackAfterDedicated(@NonNull Type type) {
        return canUseGenericLibarchive(type);
    }

    private static boolean canUseGenericLibarchive(@NonNull Type type) {
        switch (type) {
            case ZIP:
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

    @NonNull
    /** Classify listing/opening errors without retrying extraction or touching the source. */
    public static ExtractionFailure classifyOperationFailure(@NonNull Exception e) {
        return ArchiveFailureClassifier.classify(e);
    }

    private static ExtractionFailure classifyExtractionFailure(@NonNull Exception e) {
        return ArchiveFailureClassifier.classify(e);
    }

    @NonNull
    private static ExtractionFailure classifyExtractionFailure(@NonNull File archive,
                                                              @Nullable char[] password,
                                                              @NonNull Exception e) {
        ExtractionFailure base = ArchiveFailureClassifier.classify(e);
        if (base != ExtractionFailure.CORRUPT_ARCHIVE
                || password == null
                || password.length == 0) {
            return base;
        }
        try (PreparedArchive prepared = prepareArchiveForRead(archive)) {
            return classifyExtractionFailure(prepared.type, prepared.file, password, e);
        } catch (IOException | SecurityException ignored) {
            return base;
        }
    }

    @NonNull
    private static ExtractionFailure classifyExtractionFailure(@NonNull Type type,
                                                              @NonNull File preparedArchive,
                                                              @Nullable char[] password,
                                                              @NonNull Exception e) {
        ExtractionFailure base = ArchiveFailureClassifier.classify(e);
        if (base == ExtractionFailure.CORRUPT_ARCHIVE
                && type == Type.SEVEN_Z
                && password != null
                && password.length > 0
                && sevenZArchiveHasAesContext(preparedArchive, password)) {
            return ExtractionFailure.BAD_PASSWORD;
        }
        return base;
    }

    private static boolean isUnknownZipCompression(@NonNull Exception e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("unknown compression method");
    }

    @Nullable
    private static String sanitizeZipEntryName(String rawName, boolean directory) {
        String name = sanitizeEntryPathForList(rawName);
        if (name == null) return null;
        while (name.startsWith("/")) name = name.substring(1);
        if (name.length() == 0) return null;
        return directory && !name.endsWith("/") ? name + "/" : name;
    }

    private static long addMeasuredBytes(long left, long right) {
        if (left == Long.MAX_VALUE || right == Long.MAX_VALUE) return Long.MAX_VALUE;
        if (right < 0L || Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }

    private static boolean isSameFile(@NonNull File first, @NonNull File second) throws IOException {
        return first.getCanonicalFile().equals(second.getCanonicalFile());
    }


    private static final class PreparedArchive implements Closeable {
        final File file;
        final Type type;
        @Nullable final File tempFile;

        PreparedArchive(@NonNull File file, @NonNull Type type, @Nullable File tempFile) {
            this.file = file;
            this.type = type;
            this.tempFile = tempFile;
        }

        @Override
        public void close() {
            if (tempFile != null) {
                try { tempFile.delete(); } catch (SecurityException ignored) {}
            }
        }
    }

    @NonNull
    private static PreparedArchive prepareArchiveForRead(@NonNull File archive) throws IOException {
        Type type = getSupportedArchiveType(archive);
        if (type == null) throw new IOException("Unsupported archive");
        if (type == Type.LIBARCHIVE && CabVolumeResolver.hasVolumeLinks(archive)) {
            // Explicit CAB sets may themselves have numeric filenames. They are
            // never a raw concatenation of bytes from independently headed cabinets.
            return new PreparedArchive(archive,type,null);
        }
        if (LhaVolumeResolver.isCandidate(archive.getName())) {
            List<File> parts = LhaVolumeResolver.resolve(archive);
            if (parts.size() == 1) return new PreparedArchive(archive, type, null);
            File temp = combineSplitParts(parts);
            PreparedArchive prepared = new PreparedArchive(temp, type, temp);
            try {
                LhaSplitFraming.validate(temp);
                return prepared;
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    if (temp.exists() && !temp.delete()) {
                        failure.addSuppressed(new IOException("Cannot remove rejected LHA split spool"));
                    }
                } catch (SecurityException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        if (type == Type.ZIP && ZipVolumeResolver.isContinuation(archive.getName())) {
            return new PreparedArchive(ZipVolumeResolver.resolveFinalVolume(archive), type, null);
        }
        if (type == Type.RAR && isRarSplitPart(archive)) {
            List<File> parts = collectRarSplitParts(archive);
            if (parts.isEmpty()) return new PreparedArchive(archive, type, null);
            return new PreparedArchive(parts.get(0), type, null);
        }
        if ((type == Type.ALZ || type == Type.EGG) && isAlzipSplitPart(archive)) {
            File firstPart = resolveFirstAlzipPart(archive, type);
            return new PreparedArchive(firstPart, type, null);
        }
        if (type == Type.SEVEN_Z && SevenZSplitVolumeResolver.isSevenZSplitPart(archive)) {
            return new PreparedArchive(SevenZSplitVolumeResolver.resolveFirstPart(archive), type, null);
        }
        if (!isNumericSplitArchive(archive)) return new PreparedArchive(archive, type, null);

        List<File> parts = collectNumericSplitParts(archive);
        if (parts.isEmpty()) throw new IOException("No split archive parts");
        File temp = combineSplitParts(parts);
        return new PreparedArchive(temp, type, temp);
    }

    @NonNull
    private static File combineSplitParts(@NonNull List<File> parts) throws IOException {
        return NumericSplitArchiveMerger.merge(parts);
    }

    @NonNull
    private static List<File> collectNumericSplitParts(@NonNull File selectedPart) throws IOException {
        if (!isNumericSplitArchive(selectedPart)) return Collections.emptyList();
        return SevenZSplitVolumeResolver.resolveNumeric(selectedPart).parts;
    }

    private static boolean isFirstRarSplitName(@NonNull String lowerName) {
        return ArchiveTypeDetector.isFirstRarSplitName(lowerName);
    }

    private static boolean isRarSplitPart(@NonNull File file) {
        return ArchiveTypeDetector.isRarSplitPart(file);
    }

    @Nullable
    private static Type getAlzipSplitArchiveType(@NonNull File file) {
        return ArchiveTypeDetector.getAlzipSplitArchiveType(file);
    }

    private static boolean isAlzipSplitPart(@NonNull File file) {
        return ArchiveTypeDetector.isAlzipSplitPart(file);
    }

    @NonNull
    private static File resolveFirstAlzipPart(@NonNull File selectedPart, @NonNull Type type) throws IOException {
        if (type == Type.EGG) return EggVolumeResolver.resolveFirstVolume(selectedPart);
        if (type == Type.ALZ) return AlzVolumeResolver.resolveFirstVolume(selectedPart);
        return selectedPart;
    }

    @NonNull
    private static List<File> collectRarSplitParts(@NonNull File selectedPart) throws IOException {
        // Use the same validated catalogue as the Java/native RAR readers. The
        // former filename-probing duplicate could select a different archive.
        return RarArchiveLocator.collectReadableVolumes(selectedPart);
    }


    @NonNull
    private static List<EntryInfo> listSingleCompressedEntry(@NonNull File archive) {
        String outputName = getSingleCompressedOutputName(archive);
        List<EntryInfo> result = new ArrayList<>();
        result.add(new EntryInfo(outputName, false, -1L, 0L));
        return result;
    }

    private static boolean extractSingleCompressedIntoDirectory(@NonNull File payloadArchive,
                                                                @NonNull File nameSourceArchive,
                                                                @NonNull File targetDir,
                                                                @NonNull Type type,
                                                                @Nullable FileOperationProgress progress,
                                                                @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        File out = new File(targetDir, getSingleCompressedOutputName(nameSourceArchive));
        if (!isSameOrDescendant(targetDir, out)) return false;
        if (entryProgress != null) entryProgress.onFile(out.getName());
        else if (progress != null) progress.setDetail(out.getName());
        return extractSingleCompressedPayload(payloadArchive, out, type, progress);
    }

    private static boolean extractSingleCompressedEntry(@NonNull File payloadArchive,
                                                        @NonNull File nameSourceArchive,
                                                        @NonNull String entryPath,
                                                        @NonNull File outFile,
                                                        @NonNull Type type) throws IOException {
        String outputName = getSingleCompressedOutputName(nameSourceArchive);
        if (!entryPath.equals(outputName)) return false;
        return extractSingleCompressedPayload(payloadArchive, outFile, type, null);
    }

    /** Retain the old target until every decoded member and stream close succeeds. */
    private static boolean extractSingleCompressedPayload(@NonNull File archive,
                                                          @NonNull File output,
                                                          @NonNull Type type,
                                                          @Nullable FileOperationProgress progress) throws IOException {
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(output)) {
            boolean written;
            if (type == Type.SINGLE_ZST && LibarchiveNativeBridge.isAvailable()) {
                written = extractSingleZstdWithLibarchive(archive, output, progress);
            } else {
                try (InputStream fileIn = new BufferedInputStream(new FileInputStream(archive));
                     InputStream payloadIn = wrapSingleCompressedInputStream(fileIn, type)) {
                    written = writeArchiveEntryStream(payloadIn, output, progress);
                }
            }
            if (!written) return false;
            guard.commit();
            return true;
        }
    }

    private static boolean extractSingleZstdWithLibarchive(@NonNull File archive,
                                                            @NonNull File outFile,
                                                            @Nullable FileOperationProgress progress) throws IOException {
        LibarchiveNativeBridge.ForwardStream stream =
                LibarchiveNativeBridge.openRawForwardStream(archive.getAbsolutePath());
        try {
            LibarchiveNativeBridge.ForwardStreamEntry entry = stream.nextEntry();
            // The empty format has no payload entry; still create the correct zero-byte output.
            if (entry != null && (entry.directory || !entry.regularFile)) return false;
            File outParent = outFile.getParentFile();
            if (outParent == null) return false;
            if (!outParent.exists() && !outParent.mkdirs()) return false;
            byte[] buffer = new byte[1024 * 64];
            long decodedBytes = 0L;
            try (OutputStream out = openExtractionOutputStream(outFile)) {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (progress != null && !progress.checkpoint()) return false;
                    decodedBytes = checkedAddDecodedStreamBytes(decodedBytes, read);
                    if (read > 0) {
                        out.write(buffer, 0, read);
                        if (progress != null) progress.addDoneBytes(read);
                    }
                }
                out.flush();
                return true;
            }
        } finally {
            stream.close();
        }
    }

    private static boolean extractZipIntoDirectory(@NonNull File archive,
                                                   @NonNull File targetDir,
                                                   @Nullable char[] password,
                                                   @Nullable FileOperationProgress progress,
                                                   @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        try {
            ZipFile zip = new ZipFile(archive);
            if (zip.isEncrypted()) {
                if (password == null || password.length == 0) throw new PasswordRequiredException();
                zip.setPassword(password);
            }
            boolean sawEntry = false;
            @SuppressWarnings("unchecked")
            List<FileHeader> headers = zip.getFileHeaders();
            List<ZipRawName> rawNames = getZipRawNames(archive);
            if (progress != null) progress.setTotalBytes(sumZipPayloadBytes(headers));
            for (int i = 0; i < headers.size(); i++) {
                FileHeader header = headers.get(i);
                if (header == null) continue;
                if (progress != null && !progress.checkpoint()) return false;
                String displayName = zipDisplayName(rawNames, i, header.getFileName());
                File out = resolveArchiveEntryOutput(targetDir, displayName);
                if (out == null) return false;
                sawEntry = true;
                if (header.isDirectory() || displayName.replace('\\', '/').endsWith("/")) {
                    if (entryProgress != null) entryProgress.onDirectory(displayName);
                    RarOutputFileGuard.ensureDirectory(out);
                    continue;
                }
                if (entryProgress != null) entryProgress.onFile(displayName);
                else if (progress != null) progress.setDetail(displayName);
                File outParent = out.getParentFile();
                if (outParent == null) return false;
                if (!outParent.exists() && !outParent.mkdirs()) return false;
                try (InputStream in = zip.getInputStream(header)) {
                    if (!writeArchiveEntryStream(in, out, progress)) return false;
                }
            }
            return sawEntry || headers.isEmpty();
        } catch (ZipException e) {
            // zip4j supports store/deflate (+ AES). For unencrypted archives that
            // use a method it lacks (notably deflate64 from Windows Explorer on
            // 2GB+ zips, or bzip2), fall back to commons-compress, which decodes a
            // wider set. AES-encrypted entries cannot use this path (commons-compress
            // ZipFile has no AES), so only attempt it when no password is involved.
            if (isUnknownZipCompression(e) && (password == null || password.length == 0)
                    && !hasZipEncryptedHeaderSignature(archive)) {
                return extractZipWithCommonsCompress(archive, targetDir, null, progress, entryProgress);
            }
            throw new IOException(e);
        }
    }

    /**
     * Fallback extraction using commons-compress, which understands ZIP-internal
     * compression methods that zip4j does not. With the bundled Commons Compress
     * and bundled codec dependencies, this covers non-encrypted Deflate64, BZip2,
     * XZ, and ZSTD entries. Methods or codec combinations that the bundled runtime
     * cannot decode, such as AES-encrypted entries or LZMA/PPMd, still surface as
     * unsupported-feature failures.
     * Only valid for non-encrypted archives.
     */
    private static boolean extractZipWithCommonsCompress(@NonNull File archive,
                                                         @NonNull File targetDir,
                                                         @Nullable String onlyEntryPath,
                                                         @Nullable FileOperationProgress progress,
                                                         @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        boolean sawEntry = false;
        boolean extractedAny = false;
        List<ZipRawName> rawNames = getZipRawNames(archive);
        try (org.apache.commons.compress.archivers.zip.ZipFile zip =
                     org.apache.commons.compress.archivers.zip.ZipFile.builder()
                             .setFile(archive)
                             .get()) {
            java.util.Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            int entryIndex = 0;
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (entry == null) {
                    entryIndex++;
                    continue;
                }
                String displayName = zipDisplayName(rawNames, entryIndex, entry.getName());
                entryIndex++;
                String path = sanitizeEntryPathForList(displayName);
                if (path == null) continue;
                if (onlyEntryPath != null && !onlyEntryPath.equals(path)) continue;

                File out = resolveArchiveEntryOutput(targetDir, displayName);
                if (out == null) {
                    if (onlyEntryPath != null) return false;
                    continue;
                }
                sawEntry = true;
                if (entry.isDirectory() || entry.getName().replace('\\', '/').endsWith("/")) {
                    if (entryProgress != null) entryProgress.onDirectory(displayName);
                    RarOutputFileGuard.ensureDirectory(out);
                    continue;
                }
                if (!zip.canReadEntryData(entry) && !ZipxAesArchiveReader.isIndependentPpmd(entry)) {
                    // The bundled extraction paths cannot decode this method or
                    // method/codec combination. Non-encrypted XZ is handled when
                    // XZ for Java is present; AES and some legacy/optional codecs
                    // still fail cleanly here.
                    throw new UnsupportedArchiveFeatureException(
                            "ZIP entry uses an unsupported compression method");
                }
                File outParent = out.getParentFile();
                if (outParent == null) return false;
                if (!outParent.exists() && !outParent.mkdirs()) return false;
                if (entryProgress != null) entryProgress.onFile(displayName);
                else if (progress != null) progress.setDetail(displayName);
                try {
                    if (!ZipxAesArchiveReader.extractPlainEntry(archive, zip, entry, out, progress)) return false;
                } catch (LinkageError missingCodec) {
                    // Optional Commons codecs may be absent from an Android runtime.
                    // Convert linkage failure so the existing libarchive fallback runs.
                    throw new UnsupportedArchiveFeatureException(
                            "ZIP entry uses a compression codec that is not available");
                }
                extractedAny = true;
                if (onlyEntryPath != null) return true;
            }
        }
        if (onlyEntryPath != null) return extractedAny;
        return sawEntry;
    }

    /**
     * Parsed zip central-directory index, cached per archive (keyed by path, size,
     * and mtime, like the raw-name cache). Sequential single-entry extraction -- the
     * archive image viewer paging through one image after another -- would otherwise
     * re-open the zip and re-parse the entire central directory on every image,
     * which is O(entries) per image and O(entries^2) over a large comic. With the
     * cached index each extraction is an O(1) header lookup plus the inflate.
     * zip4j ZipFile keeps no persistent OS handle (it opens a RandomAccessFile per
     * getInputStream), so a cached instance only holds the parsed headers in memory;
     * eviction just drops them. Encrypted archives are cached too, but the
     * password is set on the ZipFile only for the duration of one extraction
     * (under the index lock) and cleared immediately afterward, so the static
     * cache never retains a password between extractions.
     */
    private static final class CachedZipIndex {
        final ZipFile zip;
        final Map<String, FileHeader> byPath;
        final boolean encrypted;
        final Object lock = new Object();

        CachedZipIndex(@NonNull ZipFile zip, @NonNull Map<String, FileHeader> byPath, boolean encrypted) {
            this.zip = zip;
            this.byPath = byPath;
            this.encrypted = encrypted;
        }
    }

    @NonNull
    private static CachedZipIndex getZipIndex(@NonNull File archive) throws ZipException {
        String key = zipRawNameCacheKey(archive);
        synchronized (ZIP_INDEX_CACHE) {
            CachedZipIndex cached = ZIP_INDEX_CACHE.get(key);
            if (cached != null) return cached;
        }
        CachedZipIndex built = buildZipIndex(archive);
        synchronized (ZIP_INDEX_CACHE) {
            CachedZipIndex existing = ZIP_INDEX_CACHE.get(key);
            if (existing != null) return existing;
            ZIP_INDEX_CACHE.put(key, built);
            return built;
        }
    }

    @NonNull
    private static CachedZipIndex buildZipIndex(@NonNull File archive) throws ZipException {
        ZipFile zip = new ZipFile(archive);
        boolean encrypted = zip.isEncrypted();
        List<FileHeader> headers = zip.getFileHeaders();
        List<ZipRawName> rawNames = getZipRawNames(archive);
        if (rawNames.size() != headers.size()) rawNames = Collections.emptyList();
        Map<String, FileHeader> byPath = new java.util.HashMap<>(Math.max(16, headers.size() * 2));
        for (int i = 0; i < headers.size(); i++) {
            FileHeader header = headers.get(i);
            if (header == null || header.isDirectory()) continue;
            String displayName = zipDisplayName(rawNames, i, header.getFileName());
            String path = sanitizeEntryPathForList(displayName);
            // Reserve displayed identities before adding compatibility aliases.
            if (path != null && !byPath.containsKey(path)) byPath.put(path, header);
        }
        for (FileHeader header : headers) {
            if (header == null || header.isDirectory()) continue;
            String zip4jPath = sanitizeEntryPathForList(header.getFileName());
            if (zip4jPath != null && !byPath.containsKey(zip4jPath)) byPath.put(zip4jPath, header);
        }
        return new CachedZipIndex(zip, byPath, encrypted);
    }

    private static boolean extractSingleZipEntry(@NonNull File archive,
                                                 @NonNull String entryPath,
                                                 @NonNull File outFile,
                                                 @Nullable char[] password) throws IOException {
        try {
            CachedZipIndex index = getZipIndex(archive);
            synchronized (index.lock) {
                if (index.encrypted && (password == null || password.length == 0)) {
                    throw new PasswordRequiredException();
                }
                FileHeader header = index.byPath.get(entryPath);
                if (header == null) return false;
                if (index.encrypted) {
                    index.zip.setPassword(password);
                }
                try {
                    try (InputStream in = index.zip.getInputStream(header)) {
                        return writeArchiveEntryStream(in, outFile);
                    }
                } finally {
                    // Never leave the caller's password attached to the cached ZipFile.
                    if (index.encrypted) {
                        index.zip.setPassword((char[]) null);
                    }
                }
            }
        } catch (ZipException e) {
            if (isUnknownZipCompression(e) && (password == null || password.length == 0)
                    && !hasZipEncryptedHeaderSignature(archive)) {
                return extractSingleZipEntryWithCommonsCompress(archive, entryPath, outFile);
            }
            throw new IOException(e);
        }
    }

    private static boolean extractSingleZipEntryWithCommonsCompress(@NonNull File archive,
                                                                    @NonNull String entryPath,
                                                                    @NonNull File outFile) throws IOException {
        List<ZipRawName> rawNames = getZipRawNames(archive);
        try (org.apache.commons.compress.archivers.zip.ZipFile zip =
                     org.apache.commons.compress.archivers.zip.ZipFile.builder()
                             .setFile(archive)
                             .get()) {
            java.util.Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            int entryIndex = 0;
            ZipArchiveEntry selected = null, alias = null;
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (entry == null) {
                    entryIndex++;
                    continue;
                }
                String displayName = zipDisplayName(rawNames, entryIndex, entry.getName());
                entryIndex++;
                if (entry.isDirectory()) continue;
                String path = sanitizeEntryPathForList(displayName);
                String commonsPath = sanitizeEntryPathForList(entry.getName());
                // A displayed identity wins even when a prior entry exposes the same parser alias.
                if (entryPath.equals(path)) { selected = entry; break; }
                if (alias == null && entryPath.equals(commonsPath)) alias = entry;
            }
            if (selected == null) selected = alias;
            if (selected == null) return false;
            if (!zip.canReadEntryData(selected) && !ZipxAesArchiveReader.isIndependentPpmd(selected)) {
                throw new UnsupportedArchiveFeatureException(
                        "ZIP entry uses an unsupported compression method");
            }
            try {
                return ZipxAesArchiveReader.extractPlainEntry(archive, zip, selected, outFile, null);
            } catch (LinkageError missingCodec) {
                throw new UnsupportedArchiveFeatureException(
                        "ZIP entry uses a compression codec that is not available");
            }
        }
    }

    /** TAR header/compression corruption must not be retried as an unsupported codec. */
    static final class TarIntegrityException extends IOException {
        TarIntegrityException() { super("Invalid TAR header checksum"); }
        TarIntegrityException(Exception cause) { super("TAR compression stream validation failed", cause); }
    }

    static boolean isReadableTarMember(ArchiveEntry entry) throws IOException {
        if (!(entry instanceof TarArchiveEntry)) return false;
        TarArchiveEntry member = (TarArchiveEntry) entry;
        if (!member.isCheckSumOK()) throw new TarIntegrityException();
        // Commons isFile() also accepts FIFO/device and unknown type flags.
        // Use the container type so a trailing slash cannot turn a FIFO into a directory.
        byte type = member.getLinkFlag();
        return member.isSparse() || type == TarConstants.LF_DIR
                || type == TarConstants.LF_NORMAL || type == TarConstants.LF_OLDNORM
                || type == TarConstants.LF_CONTIG;
    }

    private static boolean writeTarMember(InputStream input, File output,
                                          FileOperationProgress progress) throws IOException {
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(output)) {
            if (!writeArchiveEntryStream(input, output, progress)) return false;
            guard.commit();
            return true;
        } catch (IOException failure) {
            // A failed write is not a reason to restart extraction with another engine.
            throw new TarEntryIndex.ExtractionException(failure);
        }
    }

    private static boolean extractTarIntoDirectory(@NonNull File archive,
                                                   @NonNull File targetDir,
                                                   @NonNull Type type,
                                                   @Nullable FileOperationProgress progress,
                                                   @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        boolean sawEntry = false;
        try (InputStream fileIn = new BufferedInputStream(new FileInputStream(archive));
             InputStream payloadIn = wrapTarPayloadInputStream(fileIn, type);
             TarArchiveInputStream tar = new TarArchiveInputStream(payloadIn)) {
            ArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (progress != null && !progress.checkpoint()) return false;
                if (!isReadableTarMember(entry)) continue;
                if (!tar.canReadEntryData(entry)) return false;
                String displayName = entry.getName();
                File out = resolveArchiveEntryOutput(targetDir, displayName);
                if (out == null) return false;
                sawEntry = true;
                if (entry.isDirectory() || displayName.replace('\\', '/').endsWith("/")) {
                    if (entryProgress != null) entryProgress.onDirectory(displayName);
                    RarOutputFileGuard.ensureDirectory(out);
                    continue;
                }
                if (entryProgress != null) entryProgress.onFile(displayName);
                else if (progress != null) progress.setDetail(displayName);
                if (!writeTarMember(tar, out, progress)) return false;
            }
            // Empty TAR still needs its complete two-record end marker. A zero-byte
            // or one-record truncated payload is not a successfully extracted archive.
            finishTarPayload(payloadIn, type, progress);
            return sawEntry || tar.getBytesRead() >= 1024;
        }
    }

    private static boolean extractSingleTarEntry(@NonNull File archive,
                                                 @NonNull String entryPath,
                                                 @NonNull File outFile,
                                                 @NonNull Type type) throws IOException {
        if (type == Type.TAR) {
            // Only indexing failures may defer to the old scanner. Once copying starts,
            // cancellation, storage failure and a changing/truncated archive remain failures.
            TarEntryIndex.Index index = null;
            try { index = TarEntryIndex.get(archive); }
            catch (TarIntegrityException invalidHeader) { throw invalidHeader; }
            catch (IOException | RuntimeException unsupportedIndex) {
                ArchiveCancellation.rethrowIfCancelled(unsupportedIndex, null);
            }
            if (index != null) {
                Boolean extracted = TarEntryIndex.extract(index, archive, entryPath, outFile);
                if (extracted != null) return extracted;
            }
        }
        try (InputStream fileIn = new BufferedInputStream(new FileInputStream(archive));
             InputStream payloadIn = wrapTarPayloadInputStream(fileIn, type);
             TarArchiveInputStream tar = new TarArchiveInputStream(payloadIn)) {
            ArchiveEntry entry;
            while (true) {
                ArchiveCancellation.checkpoint(null);
                entry = tar.getNextEntry();
                ArchiveCancellation.checkpoint(null);
                if (entry == null) break;
                if (!isReadableTarMember(entry)) continue;
                if (!tar.canReadEntryData(entry)) return false;
                if (entry.isDirectory()) continue;
                String path = sanitizeEntryPathForList(entry.getName());
                if (!entryPath.equals(path)) continue;
                return writeTarMember(tar, outFile, null);
            }
            return false;
        }
    }

    private static boolean extractSevenZIntoDirectory(@NonNull File archive,
                                                      @NonNull File targetDir,
                                                      @Nullable char[] password,
                                                      @Nullable FileOperationProgress progress,
                                                      @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        byte[] buffer = new byte[1024 * 64];
        try (SevenZFile sevenZ = openSevenZFile(archive, password)) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (progress != null && !progress.checkpoint()) return false;
                if (entry.isAntiItem()) continue;
                String displayName = entry.getName();
                File out = resolveArchiveEntryOutput(targetDir, displayName);
                if (out == null) return false;
                if (entry.isDirectory() || displayName.replace('\\', '/').endsWith("/")) {
                    if (entryProgress != null) entryProgress.onDirectory(displayName);
                    RarOutputFileGuard.ensureDirectory(out);
                    continue;
                }
                if (entryProgress != null) entryProgress.onFile(displayName);
                else if (progress != null) progress.setDetail(displayName);
                File outParent = out.getParentFile();
                if (outParent == null) return false;
                if (!outParent.exists() && !outParent.mkdirs()) return false;
                long decodedBytes = 0L;
                try (OutputStream outStream = openExtractionOutputStream(out)) {
                    if (entry.hasStream()) {
                        int read;
                        while ((read = sevenZ.read(buffer)) != -1) {
                            if (progress != null && !progress.checkpoint()) return false;
                            if (read > buffer.length) throw new IOException("Invalid archive read count");
                            decodedBytes = checkedAddDecodedStreamBytes(decodedBytes, read);
                            outStream.write(buffer, 0, read);
                            if (progress != null) progress.addDoneBytes(read);
                        }
                    }
                    outStream.flush();
                }
            }
            return true; // A validated archive with zero members still extracts successfully.
        }
    }

    private static boolean extractSingleSevenZEntry(@NonNull File archive,
                                                    @NonNull String entryPath,
                                                    @NonNull File outFile,
                                                    @Nullable char[] password) throws IOException {
        byte[] buffer = new byte[1024 * 64];
        try (SevenZFile sevenZ = openSevenZFile(archive, password)) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (entry.isAntiItem() || entry.isDirectory()) continue;
                String path = sanitizeEntryPathForList(entry.getName());
                if (!entryPath.equals(path)) {
                    drainSevenZEntry(sevenZ, entry, buffer);
                    continue;
                }
                long decodedBytes = 0L;
                try (OutputStream outStream = openExtractionOutputStream(outFile)) {
                    if (entry.hasStream()) {
                        int read;
                        while ((read = sevenZ.read(buffer)) != -1) {
                            if (read > buffer.length) throw new IOException("Invalid archive read count");
                            decodedBytes = checkedAddDecodedStreamBytes(decodedBytes, read);
                            outStream.write(buffer, 0, read);
                        }
                    }
                    outStream.flush();
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean extractRarIntoDirectory(@NonNull File archive,
                                                   @NonNull File targetDir,
                                                   @Nullable char[] password,
                                                   @Nullable FileOperationProgress progress,
                                                   @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        return RarArchiveReader.extractArchiveIntoDirectory(archive, targetDir, password, progress, entryProgress);
    }

    private static boolean extractSingleRarEntry(@NonNull File archive,
                                                 @NonNull String entryPath,
                                                 @NonNull File outFile,
                                                 @Nullable char[] password) throws IOException {
        return RarArchiveReader.extractSingleEntry(archive, entryPath, outFile, password);
    }

    private static void drainSevenZEntry(@NonNull SevenZFile sevenZ,
                                         @NonNull SevenZArchiveEntry entry,
                                         @NonNull byte[] buffer) throws IOException {
        if (!entry.hasStream()) return;
        long decodedBytes = 0L;
        int read;
        while ((read = sevenZ.read(buffer)) != -1) {
            decodedBytes = checkedAddDecodedStreamBytes(decodedBytes, read);
            // Drain unread payload before moving to the next entry in solid archives.
            // The stream-time safety limit still applies here because draining a
            // previous solid member can decode attacker-controlled bytes even when
            // the caller only requested a later single entry.
        }
    }

    // Commons Compress 1.28.0's builder changes failure-close ownership for both
    // supplied channels and file origins. Keep these constructors so split-open
    // cleanup preserves its original failure and ordinary file opens close on error.
    @SuppressWarnings("deprecation")
    private static SevenZFile openSevenZFile(@NonNull File archive, @Nullable char[] password) throws IOException {
        SevenZSplitVolumeResolver.VolumeSet splitVolumes = SevenZSplitVolumeResolver.resolve(archive);
        if (splitVolumes != null) {
            SeekableByteChannel channel = new SplitSeekableByteChannel(splitVolumes.parts);
            try {
                if (password != null && password.length > 0) {
                    return new SevenZFile(channel, password);
                }
                return new SevenZFile(channel);
            } catch (IOException | RuntimeException e) {
                try { channel.close(); } catch (IOException ignored) {}
                throw e;
            }
        }
        if (password != null && password.length > 0) {
            return new SevenZFile(archive, password);
        }
        return new SevenZFile(archive);
    }

    /** TAR EOF can precede the compression trailer or later concatenated members. */
    private static void finishTarPayload(InputStream payload, Type type,
                                         FileOperationProgress progress) throws IOException {
        if (type == Type.TAR) return; // Do not change plain TAR/index compatibility.
        byte[] buffer = new byte[64 * 1024];
        long discarded = 0;
        try {
            while (true) {
                ArchiveCancellation.checkpoint(progress);
                int count = payload.read(buffer);
                ArchiveCancellation.checkpoint(progress);
                if (count == -1) return;
                if (count > buffer.length) throw new IOException("Invalid TAR payload read count");
                discarded = checkedAddDecodedStreamBytes(discarded, count);
            }
        } catch (IOException | RuntimeException failure) {
            ArchiveCancellation.rethrowIfCancelled(failure, progress);
            throw tarPayloadFailure(failure);
        }
    }

    private static TarIntegrityException tarPayloadFailure(Exception failure) throws IOException {
        ArchiveCancellation.rethrowIfCancelled(failure, null);
        return failure instanceof TarIntegrityException ? (TarIntegrityException) failure
                : new TarIntegrityException(failure);
    }

    /** Once a compression stream opens, read/skip/close errors are not codec probes. */
    private static InputStream checkedTarPayload(InputStream payload) {
        return new java.io.FilterInputStream(payload) {
            @Override public int read() throws IOException {
                try {
                    ArchiveCancellation.checkpoint(null);
                    int value = in.read();
                    ArchiveCancellation.checkpoint(null);
                    return value;
                }
                catch (IOException | RuntimeException failure) { throw tarPayloadFailure(failure); }
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                java.util.Objects.requireNonNull(bytes, "bytes");
                if ((offset | length) < 0 || length > bytes.length - offset) throw new IndexOutOfBoundsException();
                if (length == 0) return 0;
                try {
                    ArchiveCancellation.checkpoint(null);
                    int count = in.read(bytes, offset, length);
                    ArchiveCancellation.checkpoint(null);
                    return count;
                }
                catch (IOException | RuntimeException failure) { throw tarPayloadFailure(failure); }
            }
            @Override public long skip(long count) throws IOException {
                if (count <= 0L) return 0L;
                try {
                    long total = 0L;
                    while (total < count) {
                        ArchiveCancellation.checkpoint(null);
                        // TAR advances over non-selected members with skip(). A codec
                        // may decode that whole request internally without polling interrupts.
                        long requested = Math.min(64L * 1024L, count - total);
                        long skipped = in.skip(requested);
                        ArchiveCancellation.checkpoint(null);
                        if (skipped < 0L || skipped > requested) throw new IOException("Invalid TAR payload skip count");
                        if (skipped == 0L) break; // Legal short skip: let the TAR caller read or handle EOF.
                        total += skipped;
                    }
                    return total;
                }
                catch (IOException | RuntimeException failure) { throw tarPayloadFailure(failure); }
            }
            @Override public void close() throws IOException {
                try { in.close(); }
                catch (IOException | RuntimeException failure) { throw tarPayloadFailure(failure); }
            }
        };
    }

    private static InputStream wrapTarPayloadInputStream(@NonNull InputStream input, @NonNull Type type) throws IOException {
        InputStream payload;
        switch (type) {
            case TAR_GZ:
                payload = GzipCompressorInputStream.builder().setInputStream(input)
                        .setDecompressConcatenated(true).get(); break;
            case TAR_BZ2:
                payload = new BZip2CompressorInputStream(input, true); break;
            case TAR_XZ:
                payload = XZCompressorInputStream.builder().setInputStream(input)
                        .setDecompressConcatenated(true).get(); break;
            case TAR_LZMA:
                payload = new LZMACompressorInputStream(input); break;
            case TAR_Z:
                payload = new ZCompressorInputStream(input); break;
            case TAR_ZST:
                payload = openZstdPayload(input); break;
            case TAR_LZ4:
                payload = new Lz4ArchiveInputStream(input); break;
            case TAR:
            default:
                return input;
        }
        return checkedTarPayload(payload);
    }


    private static InputStream wrapSingleCompressedInputStream(@NonNull InputStream input, @NonNull Type type) throws IOException {
        switch (type) {
            case SINGLE_GZ:
                return GzipCompressorInputStream.builder().setInputStream(input)
                        .setDecompressConcatenated(true).get();
            case SINGLE_BZ2:
                return new BZip2CompressorInputStream(input, true);
            case SINGLE_XZ:
                return XZCompressorInputStream.builder().setInputStream(input)
                        .setDecompressConcatenated(true).get();
            case SINGLE_LZMA:
                return new LZMACompressorInputStream(input);
            case SINGLE_Z:
                return new ZCompressorInputStream(input);
            case SINGLE_ZST:
                return openZstdPayload(input);
            case SINGLE_LZ4:
                return new Lz4ArchiveInputStream(input);
            default:
                throw new IOException("Unsupported single-file compression format");
        }
    }

    @NonNull
    private static InputStream openZstdPayload(@NonNull InputStream input) throws IOException {
        try {
            return new ZstdCompressorInputStream(input);
        } catch (LinkageError missingCodec) {
            throw new UnsupportedArchiveFeatureException(
                    "Zstandard codec is not available in this runtime", missingCodec);
        }
    }

    @NonNull
    private static String getSingleCompressedOutputName(@NonNull File archive) {
        String name = archive.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        String numericStem = ArchiveTypeDetector.numericSplitStem(name);
        if (numericStem != null) {
            name = numericStem;
            lower = name.toLowerCase(Locale.ROOT);
        }
        String[] extensions = new String[] {".lzma", ".zst", ".lz4", ".bz2", ".gz", ".xz", ".z"};
        for (String ext : extensions) {
            if (lower.endsWith(ext) && name.length() > ext.length()) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name.length() > 0 ? name + ".out" : "decompressed";
    }

    @Nullable
    static File resolveArchiveEntryOutput(@NonNull File targetDir, String rawEntryName) {
        String entryName = sanitizeEntryPathForList(rawEntryName);
        if (entryName == null) return null;
        File out = new File(targetDir, entryName);
        return isSameOrDescendant(targetDir, out) ? out : null;
    }

    static boolean writeArchiveEntryStream(@NonNull InputStream in, @NonNull File out) throws IOException {
        return writeArchiveEntryStream(in, out, null);
    }

    static boolean writeArchiveEntryStream(@NonNull InputStream in,
                                           @NonNull File out,
                                           @Nullable FileOperationProgress progress) throws IOException {
        File outParent = out.getParentFile();
        if (outParent == null) return false;
        if (!outParent.isDirectory() && !outParent.mkdirs() && !outParent.isDirectory()) return false;
        byte[] buffer = new byte[1024 * 64];
        long decodedBytes = 0L;
        try (OutputStream outStream = openExtractionOutputStream(out)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (progress != null && !progress.checkpoint()) return false;
                if (read > buffer.length) throw new IOException("Invalid archive read count");
                decodedBytes = checkedAddDecodedStreamBytes(decodedBytes, read);
                outStream.write(buffer, 0, read);
                if (progress != null) progress.addDoneBytes(read);
            }
            outStream.flush();
            return true;
        }
    }

    static long checkedAddDecodedStreamBytes(long current, int justRead) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Archive extraction interrupted");
        if (justRead <= 0) throw new IOException("Archive decoder made no valid read progress");
        if (current < 0L || current > Long.MAX_VALUE - justRead) {
            throw new UnsupportedArchiveFeatureException(
                    "Decoded archive stream byte count overflow");
        }
        return current + justRead;
    }

    @NonNull
    public static OutputStream openExtractionOutputStream(@NonNull File out) throws IOException {
        File parent = out.getAbsoluteFile().getParentFile();
        long standaloneBudget = parent != null ? runtimeExtractionBudgetBytes(parent) : Long.MAX_VALUE;
        OutputStream raw = ArchiveExtractionByteBudget.openOutputStream(out, standaloneBudget);
        try {
            return new BufferedOutputStream(raw);
        } catch (RuntimeException | Error failure) {
            closeUnadoptedExtractionOutput(raw, failure);
            throw failure;
        }
    }

    private static void closeUnadoptedExtractionOutput(OutputStream raw, Throwable failure) {
        try { raw.close(); }
        catch (IOException | RuntimeException | Error cleanup) {
            if (cleanup != failure) failure.addSuppressed(cleanup);
        }
    }

    private static long sumZipPayloadBytes(@NonNull List<FileHeader> headers) {
        long total = 0L;
        for (FileHeader header : headers) {
            if (header == null || header.isDirectory()) continue;
            long size = Math.max(0L, header.getUncompressedSize());
            if (Long.MAX_VALUE - total < size) return Long.MAX_VALUE;
            total += size;
        }
        return total;
    }

    @Nullable
    static String sanitizeEntryPathForList(String rawEntryName) {
        if (rawEntryName == null) return null;
        // Whitespace is part of an archive member's identity, not UI input.
        String entryName = rawEntryName.replace('\\', '/');
        // Skip the prefix once: repeatedly copying the remaining substring is
        // quadratic for names with many leading ./ components.
        int start = 0;
        while (start + 1 < entryName.length()
                && entryName.charAt(start) == '.' && entryName.charAt(start + 1) == '/') start += 2;
        if (start > 0) entryName = entryName.substring(start);
        if (entryName.length() == 0 || entryName.indexOf('\0') >= 0) return null;
        // Reject before collapsing separators, preserving the existing .//name
        // rejection. A drive prefix must also be rejected when its suffix has a
        // newline; regex dot matching previously missed that case.
        char first = entryName.charAt(0);
        if (entryName.startsWith("/")
                || (entryName.length() >= 2 && entryName.charAt(1) == ':'
                    && ((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z')))) {
            return null;
        }
        int repeatedSlash = entryName.indexOf("//");
        if (repeatedSlash >= 0) {
            StringBuilder normalized = new StringBuilder(entryName.length());
            normalized.append(entryName, 0, repeatedSlash + 1);
            boolean slash = true;
            for (int i = repeatedSlash + 1; i < entryName.length(); i++) {
                char c = entryName.charAt(i);
                if (c != '/' || !slash) normalized.append(c);
                slash = c == '/';
            }
            entryName = normalized.toString();
        }
        if (entryName.equals("..")
                || entryName.startsWith("../")
                || entryName.contains("/../")
                || entryName.endsWith("/..")) {
            return null;
        }
        return entryName;
    }

    /** Selected indexes reserve the first displayed identity; listing must describe it. */
    static List<EntryInfo> withFirstSelectedEntryIdentities(List<EntryInfo> entries) throws IOException {
        Map<String, EntryInfo> first = new LinkedHashMap<>();
        for (EntryInfo entry : entries) {
            ArchiveCancellation.checkpoint(null);
            first.putIfAbsent(entry.path, entry);
        }
        return withSyntheticDirectories(first.values());
    }

    private static String normalizeDisplayPath(@NonNull String path, boolean directory) {
        String p = path.replace('\\', '/');
        if (directory && !p.endsWith("/")) p += "/";
        return p;
    }

    private static final class ZipRawName {
        final String decodedName;
        final boolean directory;
        final long size;

        ZipRawName(@NonNull String decodedName, boolean directory, long size) {
            this.decodedName = decodedName;
            this.directory = directory;
            this.size = size;
        }
    }

    private static int readIntLE(@NonNull byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private static int readUInt16LE(@NonNull byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long readUInt32LE(@NonNull byte[] data, int offset) {
        return readIntLE(data, offset) & 0xffffffffL;
    }

    @NonNull
    static List<EntryInfo> withSyntheticDirectories(@NonNull Collection<EntryInfo> entries) throws IOException {
        return withSyntheticDirectories(entries, false);
    }

    /** ALZ/native selection keeps the first file; explicit directory metadata may be refreshed. */
    static List<EntryInfo> withFirstFileEntryIdentities(@NonNull Collection<EntryInfo> entries) throws IOException {
        return withSyntheticDirectories(entries, true);
    }

    private static List<EntryInfo> withSyntheticDirectories(@NonNull Collection<EntryInfo> entries,
                                                           boolean keepFirstFile) throws IOException {
        Map<String, EntryInfo> map = new LinkedHashMap<>();
        for (EntryInfo entry : entries) {
            ArchiveCancellation.checkpoint(null);
            String path = entry.path;
            int parentEnd = path.lastIndexOf('/');
            // Every inserted parent already has its ancestors. Sibling files need
            // just one lookup, not another walk/copy of every directory prefix.
            if (parentEnd >= 0 && !map.containsKey(path.substring(0, parentEnd + 1))) {
                int slash = path.indexOf('/');
                while (slash >= 0) {
                    ArchiveCancellation.checkpoint(null);
                    String dir = path.substring(0, slash + 1);
                    if (!map.containsKey(dir)) map.put(dir, new EntryInfo(dir, true, -1L, 0L));
                    slash = path.indexOf('/', slash + 1);
                }
            }
            if (keepFirstFile && !entry.directory) map.putIfAbsent(path, entry);
            else map.put(path, entry);
        }
        return new ArrayList<>(map.values());
    }

    private static long estimatePayloadBytesFromEntries(@NonNull List<EntryInfo> entries) {
        long total = 0L;
        boolean unknown = false;
        for (EntryInfo entry : entries) {
            if (entry == null || entry.directory) continue;
            if (entry.size < 0L) {
                unknown = true;
                continue;
            }
            total = addMeasuredBytes(total, entry.size);
            if (total == Long.MAX_VALUE) return total;
        }
        return total > 0L ? total : (unknown ? -1L : 0L);
    }

    private static boolean hasUsableSpaceForExtraction(@NonNull File parentDir, long expectedBytes) {
        if (expectedBytes <= 0L) return true;
        long usable;
        try {
            usable = parentDir.getUsableSpace();
        } catch (SecurityException ignored) {
            return true;
        }
        if (usable <= 0L) return true;
        long required = addMeasuredBytes(expectedBytes, MIN_EXTRACTION_FREE_MARGIN_BYTES);
        return required != Long.MAX_VALUE && usable >= required;
    }

    static long runtimeExtractionBudgetBytes(@NonNull File parentDir) {
        long usable;
        try {
            usable = parentDir.getUsableSpace();
        } catch (SecurityException ignored) {
            return Long.MAX_VALUE;
        }
        return runtimeExtractionBudgetForUsableSpace(usable);
    }

    /** No policy size ceiling: use measurable free space, or long accounting if unavailable. */
    static long runtimeExtractionBudgetForUsableSpace(long usable) {
        if (usable <= 0L) return Long.MAX_VALUE;
        if (usable <= MIN_EXTRACTION_FREE_MARGIN_BYTES) return 0L;
        return usable - MIN_EXTRACTION_FREE_MARGIN_BYTES;
    }

    private static boolean replaceExistingDirectoryWithTemp(@NonNull File destinationDir,
                                                            @NonNull File tempDir) throws IOException {
        File parent = destinationDir.getParentFile();
        if (parent == null || !destinationDir.exists() || !tempDir.exists()) {
            deleteFileSystemItem(tempDir);
            return false;
        }
        File backupDir = buildTempExtractDirectory(parent, destinationDir.getName() + "_backup");
        if (backupDir == null) {
            deleteFileSystemItem(tempDir);
            return false;
        }
        if (!renameFileSystemItem(destinationDir, backupDir)) {
            deleteFileSystemItem(tempDir);
            return false;
        }

        boolean installed = renameFileSystemItem(tempDir, destinationDir);
        if (!installed) {
            installed = copyDirectoryRecursively(tempDir, destinationDir);
            deleteFileSystemItem(tempDir);
        }

        if (installed) {
            deleteFileSystemItem(backupDir);
            return true;
        }

        // A failed rename creates nothing, and failed copying cleans only its
        // owned tree. Anything still at this path may belong to another writer.
        // Refuse restoration into it and retain the backup rather than delete it.
        restoreDirectoryBackup(backupDir, destinationDir);
        return false;
    }

    /** Never discard the original tree unless restoration has actually succeeded. */
    private static void restoreDirectoryBackup(@NonNull File backupDir,
                                                @NonNull File destinationDir) throws IOException {
        boolean restored = renameFileSystemItem(backupDir, destinationDir);
        if (!restored) {
            restored = copyDirectoryRecursively(backupDir, destinationDir);
            if (restored) deleteFileSystemItem(backupDir);
        }
        if (!restored) {
            throw new IOException("Archive replacement failed; original files retained at: "
                    + backupDir.getAbsolutePath());
        }
    }

    private static boolean renameFileSystemItem(@NonNull File source, @NonNull File destination) {
        try {
            if (FileTreeWalk.kind(source) == FileTreeWalk.Kind.MISSING
                    || FileTreeWalk.kind(destination) != FileTreeWalk.Kind.MISSING) return false;
            return source.renameTo(destination);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    @Nullable
    private static File buildTempExtractDirectory(@NonNull File parentDir, @NonNull String targetName) {
        String base = ".textview_extract_" + targetName + "_" + System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            File candidate = new File(parentDir, i == 0 ? base : base + "_" + i);
            if (!candidate.exists()) return candidate;
        }
        return null;
    }

    private static boolean copyDirectoryRecursively(@NonNull File sourceDir, @NonNull File destinationDir) {
        boolean[] ownsDestination = {false};
        boolean copied = false;
        try {
            if (FileTreeWalk.kind(sourceDir) != FileTreeWalk.Kind.DIRECTORY
                    || FileTreeWalk.kind(destinationDir) != FileTreeWalk.Kind.MISSING
                    || isSameOrDescendant(sourceDir, destinationDir)
                    || isSameOrDescendant(destinationDir, sourceDir)) return false;
            // Resolve before any output mutation. Never merge a rollback copy into
            // an existing tree or dereference source links/devices as file data.
            File outputRoot = destinationDir.getCanonicalFile();
            byte[] buffer = new byte[64 * 1024];
            copied = FileTreeWalk.walk(sourceDir, () -> !Thread.currentThread().isInterrupted(), entry -> {
                entry.recheck();
                File output = entry.relative.isEmpty() ? destinationDir : new File(destinationDir, entry.relative);
                if (!isSameOrDescendant(outputRoot, output)) return false;
                if (entry.kind == FileTreeWalk.Kind.DIRECTORY) {
                    if (!output.mkdir()) return false;
                    if (entry.relative.isEmpty()) ownsDestination[0] = true;
                    return true;
                }
                return entry.kind == FileTreeWalk.Kind.FILE && copyRegularFile(entry, output, buffer);
            });
            return copied;
        } catch (IOException | SecurityException ignored) {
            return false;
        } finally {
            if (!copied && ownsDestination[0]) {
                deleteFileSystemItem(destinationDir);
            }
        }
    }

    private static boolean copyRegularFile(@NonNull FileTreeWalk.Entry entry,
                                            @NonNull File destination,
                                            @NonNull byte[] buffer) throws IOException {
        entry.recheck();
        if (!destination.createNewFile()) return false;
        boolean copied = false;
        try {
            long written = 0L;
            // Copies briefly coexist with the staged tree/backup. Charge their
            // physical writes too, and refresh free space just like extraction.
            try (InputStream in = new FileInputStream(entry.file);
                 OutputStream out = openExtractionOutputStream(destination)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (read <= 0 || read > entry.bytes - written) throw new IOException("Archive copy source changed");
                    out.write(buffer, 0, read);
                    written += read;
                }
            }
            entry.recheck();
            copied = written == entry.bytes && destination.length() == entry.bytes
                    && entry.file.length() == entry.bytes;
            return copied;
        } finally {
            if (!copied) {
                try { destination.delete(); } catch (SecurityException ignored) { }
            }
        }
    }

    private static boolean clearDirectoryContents(@NonNull File directory) {
        return deleteFileSystemItem(directory, true);
    }

    private static boolean deleteFileSystemItem(@NonNull File target) {
        return deleteFileSystemItem(target, false);
    }

    private static boolean deleteFileSystemItem(@NonNull File target, boolean contentsOnly) {
        try {
            // Cleanup must still run after cancellation. The no-follow walker
            // treats directory links, cycles and dangling links as leaf entries.
            return FileTreeWalk.walk(target, () -> true, new FileTreeWalk.Visitor() {
                @Override public boolean enter(FileTreeWalk.Entry entry) throws IOException {
                    if (entry.kind == FileTreeWalk.Kind.MISSING) return true;
                    if (contentsOnly && entry.relative.isEmpty()
                            && entry.kind != FileTreeWalk.Kind.DIRECTORY) return false;
                    entry.recheck();
                    return entry.kind == FileTreeWalk.Kind.DIRECTORY || entry.file.delete();
                }
                @Override public boolean leave(FileTreeWalk.Entry entry) {
                    return (contentsOnly && entry.relative.isEmpty()) || entry.file.delete();
                }
            });
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    private static boolean isSameOrDescendant(@NonNull File ancestor, @NonNull File candidate) {
        try {
            File ancestorCanonical = ancestor.getCanonicalFile();
            File current = candidate.getCanonicalFile();
            while (current != null) {
                if (ancestorCanonical.equals(current)) return true;
                current = current.getParentFile();
            }
            return false;
        } catch (IOException | SecurityException ignored) {
            // Unresolved path text cannot prove that extraction stays in its root.
            return false;
        }
    }
}
