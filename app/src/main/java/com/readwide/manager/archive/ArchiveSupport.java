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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.z.ZCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream;

import com.readwide.manager.util.FileOperationProgress;
import com.readwide.manager.util.FileTreeProgressTracker;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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


    private static final long MAX_EXTRACTION_TOTAL_BYTES = 32L * 1024L * 1024L * 1024L;
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
     * True when this file is the first volume of a generic numeric split
     * ({@code name.zip.001} style). Read paths for such archives concatenate all
     * volumes into a temporary file before opening ({@code combineSplitParts}),
     * which makes per-entry access cost O(total archive size) per entry - callers
     * that would otherwise extract entries one at a time (e.g. the image preview
     * cache) can use this to switch to a single whole-archive pass instead.
     */
    public static boolean isNumericSplitArchive(@NonNull File archive) {
        return isFirstNumericSplitArchive(archive);
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
        try (PreparedArchive prepared = prepareArchiveForRead(archive)) {
            switch (prepared.type) {
                case ZIP:
                    return listZipEntriesWithFallback(prepared.file, prepared.type, password);
                case SEVEN_Z:
                    return listSevenZEntriesWithFallback(prepared.file, prepared.type, password);
                case RAR:
                    return RarArchiveReader.listEntries(prepared.file, password);
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
            return withSyntheticDirectories(result);
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
            int flag = (tail[i + 8] & 0xff) | ((tail[i + 9] & 0xff) << 8);
            int method = (tail[i + 10] & 0xff) | ((tail[i + 11] & 0xff) << 8);
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
                return Collections.emptyList();
            }
            if (centralSize <= 0L || centralSize > 4L * 1024L * 1024L) return Collections.emptyList();
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
                rawNames.add(rawNameBytes);
                utf8Flags.add(utf8Flag);
                sizes.add(readUInt32LE(central, i + 24));
                if (!utf8Flag) corpus.observe(rawNameBytes);
                long next = (long) nameEnd + extraLength + commentLength;
                if (next <= i || next > central.length) return Collections.emptyList();
                i = (int) next - 1;
            }
            ArrayList<ZipRawName> result = new ArrayList<>();
            for (int i = 0; i < rawNames.size(); i++) {
                String decoded = ArchiveFilenameDecoder.decodeZipName(rawNames.get(i), utf8Flags.get(i), corpus);
                boolean directory = decoded.replace('\\', '/').endsWith("/");
                result.add(new ZipRawName(decoded, directory, sizes.get(i)));
            }
            return result.size() == expectedEntries ? result : Collections.emptyList();
        } catch (IOException | SecurityException ignored) {
            return Collections.emptyList();
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
            if (!result.isEmpty()) return withSyntheticDirectories(result);
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
            tailRawNames.add(rawNameBytes);
            tailUtf8Flags.add(utf8Flag);
            tailSizes.add(readUInt32LE(tail, i + 24));
            if (!utf8Flag) tailCorpus.observe(rawNameBytes);
            long next = (long) nameEnd + extraLength + commentLength;
            if (next > i && next <= tail.length) i = (int) next - 1;
        }
        for (int i = 0; i < tailRawNames.size(); i++) {
            String rawName = ArchiveFilenameDecoder.decodeZipName(tailRawNames.get(i), tailUtf8Flags.get(i), tailCorpus);
            String path = sanitizeEntryPathForList(rawName);
            if (path != null) {
                boolean directory = rawName.replace('\\', '/').endsWith("/");
                result.add(new EntryInfo(path, directory, tailSizes.get(i), 0L));
            }
        }
        if (result.isEmpty()) throw new IOException("Unsupported ZIP directory");
        return withSyntheticDirectories(result);
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
                String path = sanitizeEntryPathForList(entry.getName());
                if (path == null) continue;
                result.add(new EntryInfo(path, entry.isDirectory(), entry.getSize(), 0L));
            }
            return withSyntheticDirectories(result);
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
        List<EntryInfo> result = new ArrayList<>();
        try (InputStream fileIn = new BufferedInputStream(new FileInputStream(archive));
             InputStream payloadIn = wrapTarPayloadInputStream(fileIn, type);
             TarArchiveInputStream tar = new TarArchiveInputStream(payloadIn)) {
            ArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) throw new IOException("Cannot read TAR entry");
                if (entry instanceof TarArchiveEntry) {
                    TarArchiveEntry tarEntry = (TarArchiveEntry) entry;
                    if (tarEntry.isSymbolicLink() || tarEntry.isLink()) continue;
                }
                String path = sanitizeEntryPathForList(entry.getName());
                if (path == null) continue;
                result.add(new EntryInfo(path, entry.isDirectory(), entry.getSize(), 0L));
            }
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
            List<EntryInfo> fallback = tryListEntriesWithLibarchive(archive, type, password);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
            List<EntryInfo> fallback = tryListEntriesWithLibarchive(archive, type, password);
            if (fallback != null) return fallback;
            throw new IOException(e);
        }
    }

    @NonNull
    private static List<EntryInfo> listSevenZEntriesWithFallback(@NonNull File archive,
                                                                 @NonNull Type type,
                                                                 @Nullable char[] password) throws IOException {
        try {
            return listSevenZEntries(archive, password);
        } catch (PasswordRequiredException e) {
            List<EntryInfo> bcj2 = tryListSevenZBcj2Entries(archive, password);
            if (bcj2 != null) return bcj2;
            throw e;
        } catch (IOException e) {
            List<EntryInfo> bcj2 = tryListSevenZBcj2Entries(archive, password);
            if (bcj2 != null) return bcj2;
            List<EntryInfo> fallback = tryListEntriesWithLibarchive(archive, type, password);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
            List<EntryInfo> bcj2 = tryListSevenZBcj2Entries(archive, password);
            if (bcj2 != null) return bcj2;
            List<EntryInfo> fallback = tryListEntriesWithLibarchive(archive, type, password);
            if (fallback != null) return fallback;
            throw new IOException(e);
        }
    }

    /**
     * First-party 7z entry listing for BCJ2/PPMd archives whose header is
     * itself AES-encrypted (encoded header), where Commons Compress cannot
     * list without decrypting. Returns {@code null} if the archive uses
     * neither coder, so other fallbacks still apply.
     */
    @Nullable
    private static List<EntryInfo> tryListSevenZBcj2Entries(@NonNull File archive,
                                                            @Nullable char[] password) {
        try {
            if (!SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, password)) return null;
            return SevenZBcj2ArchiveReader.listEntries(archive, password);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @NonNull
    private static List<EntryInfo> listTarEntriesWithFallback(@NonNull File archive,
                                                              @NonNull Type type,
                                                              @Nullable char[] password) throws IOException {
        try {
            return listTarEntries(archive, type);
        } catch (IOException e) {
            List<EntryInfo> fallback = tryListEntriesWithLibarchive(archive, type, password);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
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
        if (destinationDir.exists()) {
            if (!overwrite) return ExtractionResult.failed(ExtractionFailure.FAILED, null);
            tempDir = buildTempExtractDirectory(parent, destinationDir.getName());
            if (tempDir == null) return ExtractionResult.failed(ExtractionFailure.FAILED, null);
            workDir = tempDir;
        }

        try {
            List<EntryInfo> extractionEntries = listEntries(archive, password);
            long estimatedPayloadBytes = estimatePayloadBytesFromEntries(extractionEntries);
            if (estimatedPayloadBytes > MAX_EXTRACTION_TOTAL_BYTES) {
                return ExtractionResult.failed(ExtractionFailure.UNSUPPORTED_FEATURE,
                        "Archive expands beyond the extraction safety limit");
            }
            if (estimatedPayloadBytes > 0L) {
                if (progress != null) progress.setTotalBytes(estimatedPayloadBytes);
                if (!hasUsableSpaceForExtraction(parent, estimatedPayloadBytes)) {
                    return ExtractionResult.failed(ExtractionFailure.FAILED, "Not enough free space for extraction");
                }
            }

            if (workDir.exists()) return ExtractionResult.failed(ExtractionFailure.FAILED, null);
            if (!workDir.mkdirs()) return ExtractionResult.failed(ExtractionFailure.FAILED, null);
            ArchiveExtractionProgressTracker entryProgress = ArchiveExtractionProgressTracker.create(progress, extractionEntries);
            boolean ok = extractArchiveIntoDirectory(archive, workDir, password, progress, entryProgress);
            if (!ok) {
                deleteFileSystemItem(workDir);
                return ExtractionResult.failed(ExtractionFailure.FAILED, null);
            }

            if (tempDir != null && !replaceExistingDirectoryWithTemp(destinationDir, tempDir)) {
                return ExtractionResult.failed(ExtractionFailure.FAILED, null);
            }
            return ExtractionResult.success();
        } catch (PasswordRequiredException e) {
            deleteFileSystemItem(workDir);
            return ExtractionResult.failed(ExtractionFailure.PASSWORD_REQUIRED, e.getMessage());
        } catch (UnsupportedArchiveFeatureException e) {
            deleteFileSystemItem(workDir);
            return ExtractionResult.failed(ExtractionFailure.UNSUPPORTED_FEATURE, e.getMessage());
        } catch (IOException | SecurityException e) {
            deleteFileSystemItem(workDir);
            return ExtractionResult.failed(classifyExtractionFailure(archive, password, e), e.getMessage());
        }
    }

    public static boolean extractSingleEntry(@NonNull File archive,
                                             @NonNull String entryPath,
                                             @NonNull File outFile,
                                             @Nullable char[] password) {
        return extractSingleEntryDetailed(archive, entryPath, outFile, password).success;
    }

    /**
     * Forward-only entry reader for solid/sequential archives (7z and the TAR family).
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
            case SEVEN_Z:
            case TAR:
            case TAR_GZ:
            case TAR_BZ2:
            case TAR_XZ:
            case TAR_LZMA:
            case TAR_Z:
            case TAR_ZST:
            case TAR_LZ4:
                return true;
            default:
                return false;
        }
    }

    public static boolean isForwardImageReadableType(@NonNull File archive) {
        Type type = getSupportedArchiveType(archive);
        if (type == Type.RAR) {
            return isRarForwardImageReadable(archive);
        }
        return isForwardImageReadableType(type);
    }

    private static boolean isRarForwardImageReadable(@NonNull File archive) {
        // RAR has no streaming Java engine of its own, but its libarchive backend is a strictly
        // forward, single-pass reader - the access pattern the sequential reader is built for.
        // Route RAR through the forward reader only when that backend is present and the file is a
        // RAR version libarchive reads (4 or 5). Everything else, and any entry libarchive cannot
        // decode at run time, falls back to the existing whole-archive path.
        if (!LibarchiveNativeBridge.isRarFormatAvailable()) {
            return false;
        }
        try {
            int version = RarArchiveLocator.detectRarVersion(archive);
            return version == 4 || version == 5;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    @Nullable
    public static ForwardArchiveReader openForwardReader(@NonNull File archive,
                                                         @Nullable char[] password) throws IOException {
        PreparedArchive prepared = prepareArchiveForRead(archive);
        try {
            switch (prepared.type) {
                case SEVEN_Z:
                    return new SevenZForwardReader(prepared, openSevenZFile(prepared.file, password));
                case TAR:
                case TAR_GZ:
                case TAR_BZ2:
                case TAR_XZ:
                case TAR_LZMA:
                case TAR_Z:
                case TAR_ZST:
                case TAR_LZ4: {
                    InputStream fileIn = new BufferedInputStream(new FileInputStream(prepared.file));
                    try {
                        InputStream payload = wrapTarPayloadInputStream(fileIn, prepared.type);
                        return new TarForwardReader(prepared, new TarArchiveInputStream(payload), fileIn);
                    } catch (IOException | RuntimeException e) {
                        try { fileIn.close(); } catch (IOException ignored) {}
                        throw e;
                    }
                }
                case RAR: {
                    // libarchive reads RAR forward-only; resolve the volume chain (one file for a
                    // single-volume archive) and hand the ordered paths to the streaming bridge.
                    // libarchive's own volume input handles concatenation and embedded SFX offsets.
                    List<File> volumes = RarArchiveLocator.collectReadableVolumes(prepared.file);
                    String[] paths = new String[volumes.size()];
                    for (int i = 0; i < volumes.size(); i++) {
                        paths[i] = volumes.get(i).getAbsolutePath();
                    }
                    LibarchiveNativeBridge.ForwardStream stream =
                            LibarchiveNativeBridge.openForwardStream(paths, password);
                    prepared.close();
                    return new LibarchiveForwardReader(stream);
                }
                default:
                    prepared.close();
                    return null;
            }
        } catch (IOException | RuntimeException e) {
            prepared.close();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e);
        }
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
            SevenZArchiveEntry entry = sevenZ.getNextEntry();
            if (entry == null) return null;
            String path = sanitizeEntryPathForList(entry.getName());
            return new ForwardEntry(path, entry.isDirectory(), entry.hasStream());
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
        @NonNull private final InputStream fileIn;

        TarForwardReader(@NonNull PreparedArchive prepared,
                         @NonNull TarArchiveInputStream tar,
                         @NonNull InputStream fileIn) {
            this.prepared = prepared;
            this.tar = tar;
            this.fileIn = fileIn;
        }

        @Nullable
        @Override
        public ForwardEntry nextEntry() throws IOException {
            ArchiveEntry entry = tar.getNextEntry();
            if (entry == null) return null;
            if (!tar.canReadEntryData(entry)) {
                return new ForwardEntry(null, entry.isDirectory(), false);
            }
            if (entry instanceof TarArchiveEntry) {
                TarArchiveEntry tarEntry = (TarArchiveEntry) entry;
                if (tarEntry.isSymbolicLink() || tarEntry.isLink()) {
                    return new ForwardEntry(null, false, false);
                }
            }
            boolean directory = entry.isDirectory();
            String path = sanitizeEntryPathForList(entry.getName());
            return new ForwardEntry(path, directory, !directory);
        }

        @Override
        public int read(@NonNull byte[] buffer) throws IOException {
            return tar.read(buffer);
        }

        @Override
        public void close() throws IOException {
            try {
                tar.close();
            } finally {
                try {
                    fileIn.close();
                } catch (IOException ignored) {
                } finally {
                    prepared.close();
                }
            }
        }
    }

    @NonNull
    public static ExtractionResult extractSingleEntryDetailed(@NonNull File archive,
                                                              @NonNull String entryPath,
                                                              @NonNull File outFile,
                                                              @Nullable char[] password) {
        String normalized = sanitizeEntryPathForList(entryPath);
        if (normalized == null || normalized.endsWith("/")) {
            return ExtractionResult.failed(ExtractionFailure.FAILED, null);
        }
        if (outFile.exists() && !deleteFileSystemItem(outFile)) {
            return ExtractionResult.failed(ExtractionFailure.FAILED, null);
        }
        File parent = outFile.getParentFile();
        if (parent == null) return ExtractionResult.failed(ExtractionFailure.FAILED, null);
        if (!parent.exists() && !parent.mkdirs()) {
            return ExtractionResult.failed(ExtractionFailure.FAILED, null);
        }

        try (PreparedArchive prepared = prepareArchiveForRead(archive)) {
            boolean ok;
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
            if (ok) return ExtractionResult.success();
            cleanupPartialSingleEntryOutput(outFile);
            return ExtractionResult.failed(ExtractionFailure.FAILED, null);
        } catch (PasswordRequiredException e) {
            try { outFile.delete(); } catch (SecurityException ignored) {}
            return ExtractionResult.failed(ExtractionFailure.PASSWORD_REQUIRED, e.getMessage());
        } catch (UnsupportedArchiveFeatureException e) {
            try { outFile.delete(); } catch (SecurityException ignored) {}
            return ExtractionResult.failed(ExtractionFailure.UNSUPPORTED_FEATURE, e.getMessage());
        } catch (IOException | SecurityException e) {
            try { outFile.delete(); } catch (SecurityException ignored2) {}
            return ExtractionResult.failed(classifyExtractionFailure(archive, password, e), e.getMessage());
        }
    }

    private static void cleanupPartialSingleEntryOutput(@NonNull File outFile) {
        try { deleteFileSystemItem(outFile); } catch (SecurityException ignored) {}
    }

    public static boolean createZipArchive(@NonNull List<File> sources,
                                           @NonNull File outFile,
                                           @Nullable FileOperationProgress progress) {
        if (sources.isEmpty()) return false;
        File parent = outFile.getParentFile();
        if (parent == null || !parent.exists() || !parent.isDirectory() || !parent.canWrite()) return false;
        if (outFile.exists()) return false;

        long total = 0L;
        for (File source : sources) {
            total = addMeasuredBytes(total, measureSourceBytes(source));
            if (total == Long.MAX_VALUE) break;
        }
        FileTreeProgressTracker treeProgress = null;
        if (progress != null) {
            progress.setTotalBytes(total);
            treeProgress = FileTreeProgressTracker.create(progress, sources);
        }

        boolean ok = false;
        Set<String> usedNames = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)))) {
            byte[] buffer = new byte[1024 * 64];
            for (File source : sources) {
                if (source == null || !source.exists() || !source.canRead()) return false;
                if (isSameFile(source, outFile)) return false;
                if (progress != null && !progress.checkpoint()) return false;
                addSourceToZip(zip, source, source.getName(), usedNames, buffer, progress, treeProgress);
            }
            ok = true;
            return true;
        } catch (IOException | SecurityException ignored) {
            return false;
        } finally {
            if (!ok) {
                try { outFile.delete(); } catch (SecurityException ignored) {}
            }
        }
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
        try {
            return extractZipIntoDirectory(archive, targetDir, password, progress, entryProgress);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (UnsupportedArchiveFeatureException e) {
            Boolean fallback = tryExtractArchiveWithLibarchiveAfterDedicatedFailure(
                    archive, type, targetDir, password, progress, entryProgress);
            if (fallback != null) return fallback;
            throw e;
        } catch (IOException e) {
            Boolean fallback = tryExtractArchiveWithLibarchiveAfterDedicatedFailure(
                    archive, type, targetDir, password, progress, entryProgress);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
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
        try {
            return extractSevenZIntoDirectory(archive, targetDir, password, progress, entryProgress);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (IOException e) {
            Boolean bcj2 = tryExtractSevenZBcj2IntoDirectory(archive, targetDir, password, progress, entryProgress);
            if (bcj2 != null) return bcj2;
            Boolean fallback = tryExtractArchiveWithLibarchiveAfterDedicatedFailure(
                    archive, type, targetDir, password, progress, entryProgress);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
            Boolean bcj2 = tryExtractSevenZBcj2IntoDirectory(archive, targetDir, password, progress, entryProgress);
            if (bcj2 != null) return bcj2;
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
        } catch (IOException e) {
            Boolean fallback = tryExtractArchiveWithLibarchiveAfterDedicatedFailure(
                    archive, type, targetDir, null, progress, entryProgress);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
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
        try {
            return extractSingleZipEntry(archive, entryPath, outFile, password);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (UnsupportedArchiveFeatureException e) {
            Boolean fallback = tryExtractSingleEntryWithLibarchiveAfterDedicatedFailure(
                    archive, type, entryPath, outFile, password, null);
            if (fallback != null) return fallback;
            throw e;
        } catch (IOException e) {
            Boolean fallback = tryExtractSingleEntryWithLibarchiveAfterDedicatedFailure(
                    archive, type, entryPath, outFile, password, null);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
            Boolean fallback = tryExtractSingleEntryWithLibarchiveAfterDedicatedFailure(
                    archive, type, entryPath, outFile, password, null);
            if (fallback != null) return fallback;
            throw new IOException(e);
        }
    }

    private static boolean extractSingleSevenZEntryWithFallback(@NonNull File archive,
                                                                @NonNull Type type,
                                                                @NonNull String entryPath,
                                                                @NonNull File outFile,
                                                                @Nullable char[] password) throws IOException {
        try {
            return extractSingleSevenZEntry(archive, entryPath, outFile, password);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (IOException e) {
            Boolean bcj2 = tryExtractSevenZBcj2SingleEntry(archive, entryPath, outFile, password);
            if (bcj2 != null) return bcj2;
            Boolean fallback = tryExtractSingleEntryWithLibarchiveAfterDedicatedFailure(
                    archive, type, entryPath, outFile, password, null);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
            Boolean bcj2 = tryExtractSevenZBcj2SingleEntry(archive, entryPath, outFile, password);
            if (bcj2 != null) return bcj2;
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
        } catch (IOException e) {
            Boolean fallback = tryExtractSingleEntryWithLibarchiveAfterDedicatedFailure(
                    archive, type, entryPath, outFile, null, null);
            if (fallback != null) return fallback;
            throw e;
        } catch (SecurityException e) {
            Boolean fallback = tryExtractSingleEntryWithLibarchiveAfterDedicatedFailure(
                    archive, type, entryPath, outFile, null, null);
            if (fallback != null) return fallback;
            throw new IOException(e);
        }
    }

    /**
     * First-party 7z extraction path for archives whose entries use a coder
     * Commons Compress cannot decode: BCJ2 ("Multi input/output stream coders
     * are not yet supported") or PPMd (no coder). Returns {@code null} when
     * the archive uses neither, or when the first-party read fails for a
     * reason other than a missing password, so the caller still falls through
     * to the libarchive path that previously served unencrypted PPMd/BCJ2.
     * AES-encrypted BCJ2/PPMd archives have no other path, since libarchive
     * cannot decrypt 7z at all.
     */
    @Nullable
    private static Boolean tryExtractSevenZBcj2IntoDirectory(@NonNull File archive,
                                                             @NonNull File targetDir,
                                                             @Nullable char[] password,
                                                             @Nullable FileOperationProgress progress,
                                                             @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        if (!SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, password)) return null;
        try {
            return SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, targetDir, password, progress, entryProgress);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static Boolean tryExtractSevenZBcj2SingleEntry(@NonNull File archive,
                                                           @NonNull String entryPath,
                                                           @NonNull File outFile,
                                                           @Nullable char[] password) throws IOException {
        if (!SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, password)) return null;
        try {
            return SevenZBcj2ArchiveReader.extractSingleEntry(archive, entryPath, outFile, password);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static Boolean tryExtractArchiveWithLibarchiveAfterDedicatedFailure(@NonNull File archive,
                                                                                @NonNull Type type,
                                                                                @NonNull File targetDir,
                                                                                @Nullable char[] password,
                                                                                @Nullable FileOperationProgress progress,
                                                                                @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        if (!shouldUseLibarchiveFallbackAfterDedicated(type)) return null;
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
        if (!shouldUseLibarchiveFallbackAfterDedicated(type)) return null;
        try { outFile.delete(); } catch (SecurityException ignored) {}
        return tryExtractSingleEntryWithLibarchive(archive, type, entryPath, outFile, password, progress);
    }


    @Nullable
    private static List<EntryInfo> tryListEntriesWithLibarchive(@NonNull File archive,
                                                                @NonNull Type type,
                                                                @Nullable char[] password) throws IOException {
        if (!canUseGenericLibarchive(type)) return null;
        if (!LibarchiveArchiveReader.isAvailable()) return null;
        try {
            return LibarchiveArchiveReader.listEntries(archive, password);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (UnsupportedArchiveFeatureException | SecurityException e) {
            return null;
        } catch (IOException e) {
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
        if (!canUseGenericLibarchive(type)) return null;
        if (!LibarchiveArchiveReader.isAvailable()) return null;
        try {
            return LibarchiveArchiveReader.extractArchiveIntoDirectory(
                    archive, targetDir, password, progress, entryProgress);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (UnsupportedArchiveFeatureException | SecurityException e) {
            return null;
        } catch (IOException e) {
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
        if (!canUseGenericLibarchive(type)) return null;
        if (!LibarchiveArchiveReader.isAvailable()) return null;
        try {
            return LibarchiveArchiveReader.extractSingleEntry(archive, entryPath, outFile, password, progress);
        } catch (PasswordRequiredException e) {
            throw e;
        } catch (UnsupportedArchiveFeatureException | SecurityException e) {
            try { outFile.delete(); } catch (SecurityException ignored) {}
            return null;
        } catch (IOException e) {
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
                return true;
            default:
                return false;
        }
    }

    @NonNull
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

    private static void addSourceToZip(@NonNull ZipOutputStream zip,
                                       @NonNull File source,
                                       @NonNull String entryName,
                                       @NonNull Set<String> usedNames,
                                       @NonNull byte[] buffer,
                                       @Nullable FileOperationProgress progress,
                                       @Nullable FileTreeProgressTracker treeProgress) throws IOException {
        String safeEntryName = sanitizeZipEntryName(entryName, source.isDirectory());
        if (safeEntryName == null) return;
        if (source.isDirectory()) {
            if (treeProgress != null) treeProgress.onDirectory(source);
            String dirName = safeEntryName.endsWith("/") ? safeEntryName : safeEntryName + "/";
            if (usedNames.add(dirName)) {
                ZipEntry dirEntry = new ZipEntry(dirName);
                dirEntry.setTime(Math.max(0L, source.lastModified()));
                zip.putNextEntry(dirEntry);
                zip.closeEntry();
            }
            File[] children = source.listFiles();
            if (children == null || children.length == 0) return;
            for (File child : children) {
                if (progress != null && !progress.checkpoint()) throw new IOException("Archive creation cancelled");
                addSourceToZip(zip, child, dirName + child.getName(), usedNames, buffer, progress, treeProgress);
            }
            return;
        }
        if (!source.isFile()) return;
        if (!usedNames.add(safeEntryName)) return;
        if (treeProgress != null) treeProgress.onFile(source);
        else if (progress != null) progress.setDetail(source.getName());
        ZipEntry entry = new ZipEntry(safeEntryName);
        entry.setTime(Math.max(0L, source.lastModified()));
        zip.putNextEntry(entry);
        try (InputStream in = new BufferedInputStream(new FileInputStream(source))) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (progress != null && !progress.checkpoint()) throw new IOException("Archive creation cancelled");
                zip.write(buffer, 0, read);
                if (progress != null) progress.addDoneBytes(read);
            }
        }
        zip.closeEntry();
    }

    @Nullable
    private static String sanitizeZipEntryName(String rawName, boolean directory) {
        String name = sanitizeEntryPathForList(rawName);
        if (name == null) return null;
        while (name.startsWith("/")) name = name.substring(1);
        if (name.length() == 0) return null;
        return directory && !name.endsWith("/") ? name + "/" : name;
    }

    private static long measureSourceBytes(@Nullable File source) {
        if (source == null || !source.exists()) return 0L;
        if (source.isFile()) return Math.max(0L, source.length());
        File[] children = source.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) {
            total = addMeasuredBytes(total, measureSourceBytes(child));
            if (total == Long.MAX_VALUE) return total;
        }
        return total;
    }

    private static long addMeasuredBytes(long left, long right) {
        if (left == Long.MAX_VALUE || right == Long.MAX_VALUE) return Long.MAX_VALUE;
        if (right < 0L || Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }

    private static boolean isSameFile(@NonNull File first, @NonNull File second) throws IOException {
        return first.getCanonicalFile().equals(second.getCanonicalFile());
    }


    private static final class PreparedArchive implements AutoCloseable {
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
        if (!isFirstNumericSplitArchive(archive)) return new PreparedArchive(archive, type, null);

        List<File> parts = collectNumericSplitParts(archive);
        if (parts.isEmpty()) throw new IOException("No split archive parts");
        File temp = combineSplitParts(parts);
        return new PreparedArchive(temp, type, temp);
    }

    @NonNull
    private static File combineSplitParts(@NonNull List<File> parts) throws IOException {
        File temp = File.createTempFile("textview_split_archive_", ".tmp");
        boolean ok = false;
        try {
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
                byte[] buffer = new byte[1024 * 64];
                for (File part : parts) {
                    try (InputStream in = new BufferedInputStream(new FileInputStream(part))) {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                }
                out.flush();
            }
            ok = true;
            return temp;
        } finally {
            if (!ok) {
                try { temp.delete(); } catch (SecurityException ignored) {}
            }
        }
    }

    @NonNull
    private static List<File> collectNumericSplitParts(@NonNull File firstPart) throws IOException {
        String name = firstPart.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!isFirstNumericSplitName(lower)) return Collections.emptyList();
        File parent = firstPart.getParentFile();
        if (parent == null) throw new IOException("Split archive has no parent directory");
        String stem = name.substring(0, name.length() - 4);
        List<File> result = new ArrayList<>();
        int firstMissing = -1;
        for (int index = 1; index <= 999; index++) {
            String suffix = String.format(Locale.ROOT, ".%03d", index);
            File part = new File(parent, stem + suffix);
            if (!part.exists() || !part.isFile()) {
                firstMissing = index;
                if (index == 1) throw new IOException("First numeric split archive part is missing: " + stem + suffix);
                break;
            }
            result.add(part);
        }
        if (firstMissing > 0 && hasLaterNumericSplitPart(parent, stem, firstMissing + 1)) {
            throw new IOException("Missing numeric split archive part: "
                    + stem + String.format(Locale.ROOT, ".%03d", firstMissing));
        }
        return result;
    }

    private static boolean hasLaterNumericSplitPart(@NonNull File parent,
                                                    @NonNull String stem,
                                                    int startIndex) {
        for (int index = startIndex; index <= 999; index++) {
            File part = new File(parent, stem + String.format(Locale.ROOT, ".%03d", index));
            if (part.exists() && part.isFile()) return true;
        }
        return false;
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
    private static File resolveFirstAlzipPart(@NonNull File selectedPart, @NonNull Type type) {
        File parent = selectedPart.getParentFile();
        if (parent == null) return selectedPart;
        String name = selectedPart.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (type == Type.EGG) {
            Matcher eggPart = ArchiveTypeDetector.EGG_VOLUME_PART.matcher(lower);
            if (eggPart.matches()) {
                String prefix = name.substring(0, lower.lastIndexOf(".vol"));
                File first = new File(parent, prefix + ".vol1.egg");
                return first.exists() && first.isFile() ? first : selectedPart;
            }
        }
        if (type == Type.ALZ) {
            Matcher alzPart = ArchiveTypeDetector.ALZ_VOLUME_PART.matcher(lower);
            if (alzPart.matches()) {
                String prefix = name.substring(0, lower.lastIndexOf(".a"));
                File first = new File(parent, prefix + ".alz");
                return first.exists() && first.isFile() ? first : selectedPart;
            }
        }
        return selectedPart;
    }

    @NonNull
    private static List<File> collectRarSplitParts(@NonNull File selectedPart) throws IOException {
        File parent = selectedPart.getParentFile();
        if (parent == null) throw new IOException("RAR split archive has no parent directory");
        String name = selectedPart.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        Matcher newStyle = ArchiveTypeDetector.RAR_NEW_STYLE_PART.matcher(lower);
        if (newStyle.matches()) {
            String originalPrefix = name.substring(0, lower.lastIndexOf(".part"));
            return collectNewStyleRarParts(parent, originalPrefix);
        }
        Matcher oldStyle = ArchiveTypeDetector.RAR_OLD_STYLE_PART.matcher(lower);
        if (oldStyle.matches()) {
            String originalPrefix = name.substring(0, name.length() - 4);
            return collectOldStyleRarParts(parent, originalPrefix);
        }
        if (lower.endsWith(".rar")) {
            String originalPrefix = name.substring(0, name.length() - 4);
            List<File> newStyleParts = collectNewStyleRarParts(parent, originalPrefix);
            if (newStyleParts.size() > 1) return newStyleParts;
            List<File> oldStyleParts = collectOldStyleRarParts(parent, originalPrefix);
            return oldStyleParts.size() > 1 ? oldStyleParts : Collections.singletonList(selectedPart);
        }
        return Collections.singletonList(selectedPart);
    }

    @NonNull
    private static List<File> collectNewStyleRarParts(@NonNull File parent, @NonNull String prefix) throws IOException {
        List<File> result = new ArrayList<>();
        for (int index = 1; index <= 9999; index++) {
            File part = new File(parent, prefix + ".part" + index + ".rar");
            if (!part.exists() || !part.isFile()) {
                if (index == 1) break;
                return result;
            }
            result.add(part);
        }
        return result;
    }

    @NonNull
    private static List<File> collectOldStyleRarParts(@NonNull File parent, @NonNull String prefix) throws IOException {
        File first = new File(parent, prefix + ".rar");
        if (!first.exists() || !first.isFile()) return Collections.emptyList();
        List<File> result = new ArrayList<>();
        result.add(first);
        for (int index = 0; index <= 999; index++) {
            File part = new File(parent, String.format(Locale.ROOT, "%s.r%02d", prefix, index));
            if (!part.exists() || !part.isFile()) return result;
            result.add(part);
        }
        return result;
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
        try (InputStream fileIn = new BufferedInputStream(new FileInputStream(payloadArchive));
             InputStream payloadIn = wrapSingleCompressedInputStream(fileIn, type)) {
            return writeArchiveEntryStream(payloadIn, out, progress);
        }
    }

    private static boolean extractSingleCompressedEntry(@NonNull File payloadArchive,
                                                        @NonNull File nameSourceArchive,
                                                        @NonNull String entryPath,
                                                        @NonNull File outFile,
                                                        @NonNull Type type) throws IOException {
        String outputName = getSingleCompressedOutputName(nameSourceArchive);
        if (!entryPath.equals(outputName)) return false;
        try (InputStream fileIn = new BufferedInputStream(new FileInputStream(payloadArchive));
             InputStream payloadIn = wrapSingleCompressedInputStream(fileIn, type)) {
            return writeArchiveEntryStream(payloadIn, outFile);
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
                    if (!out.exists() && !out.mkdirs()) return false;
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
            return sawEntry;
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
                    if (!out.exists() && !out.mkdirs()) return false;
                    continue;
                }
                if (!zip.canReadEntryData(entry)) {
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
                try (InputStream in = zip.getInputStream(entry)) {
                    if (!writeArchiveEntryStream(in, out, progress)) return false;
                } catch (LinkageError missingCodec) {
                    // Keep a defensive guard for optional/native codec linkage failures.
                    // ZSTD is normally available because zstd-jni is bundled, but this
                    // keeps extraction from crashing if an ABI-specific native load fails.
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
            String zip4jPath = sanitizeEntryPathForList(header.getFileName());
            // First header wins for a given key, matching the old linear scan.
            if (path != null && !byPath.containsKey(path)) byPath.put(path, header);
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
                if (!entryPath.equals(path) && !entryPath.equals(commonsPath)) continue;
                if (!zip.canReadEntryData(entry)) {
                    throw new UnsupportedArchiveFeatureException(
                            "ZIP entry uses an unsupported compression method");
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    return writeArchiveEntryStream(in, outFile);
                } catch (LinkageError missingCodec) {
                    throw new UnsupportedArchiveFeatureException(
                            "ZIP entry uses a compression codec that is not available");
                }
            }
            return false;
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
                if (!tar.canReadEntryData(entry)) return false;
                if (entry instanceof TarArchiveEntry) {
                    TarArchiveEntry tarEntry = (TarArchiveEntry) entry;
                    if (tarEntry.isSymbolicLink() || tarEntry.isLink()) return false;
                }
                String displayName = entry.getName();
                File out = resolveArchiveEntryOutput(targetDir, displayName);
                if (out == null) return false;
                sawEntry = true;
                if (entry.isDirectory() || displayName.replace('\\', '/').endsWith("/")) {
                    if (entryProgress != null) entryProgress.onDirectory(displayName);
                    if (!out.exists() && !out.mkdirs()) return false;
                    continue;
                }
                if (entryProgress != null) entryProgress.onFile(displayName);
                else if (progress != null) progress.setDetail(displayName);
                if (!writeArchiveEntryStream(tar, out, progress)) return false;
            }
            return sawEntry;
        }
    }

    private static boolean extractSingleTarEntry(@NonNull File archive,
                                                 @NonNull String entryPath,
                                                 @NonNull File outFile,
                                                 @NonNull Type type) throws IOException {
        try (InputStream fileIn = new BufferedInputStream(new FileInputStream(archive));
             InputStream payloadIn = wrapTarPayloadInputStream(fileIn, type);
             TarArchiveInputStream tar = new TarArchiveInputStream(payloadIn)) {
            ArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) return false;
                if (entry instanceof TarArchiveEntry) {
                    TarArchiveEntry tarEntry = (TarArchiveEntry) entry;
                    if (tarEntry.isSymbolicLink() || tarEntry.isLink()) return false;
                }
                if (entry.isDirectory()) continue;
                String path = sanitizeEntryPathForList(entry.getName());
                if (!entryPath.equals(path)) continue;
                return writeArchiveEntryStream(tar, outFile);
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
        boolean sawEntry = false;
        try (SevenZFile sevenZ = openSevenZFile(archive, password)) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (progress != null && !progress.checkpoint()) return false;
                String displayName = entry.getName();
                File out = resolveArchiveEntryOutput(targetDir, displayName);
                if (out == null) return false;
                sawEntry = true;
                if (entry.isDirectory() || displayName.replace('\\', '/').endsWith("/")) {
                    if (entryProgress != null) entryProgress.onDirectory(displayName);
                    if (!out.exists() && !out.mkdirs()) return false;
                    continue;
                }
                if (entryProgress != null) entryProgress.onFile(displayName);
                else if (progress != null) progress.setDetail(displayName);
                File outParent = out.getParentFile();
                if (outParent == null) return false;
                if (!outParent.exists() && !outParent.mkdirs()) return false;
                long decodedBytes = 0L;
                try (OutputStream outStream = new BufferedOutputStream(new FileOutputStream(out))) {
                    if (entry.hasStream()) {
                        int read;
                        while ((read = sevenZ.read(buffer)) > 0) {
                            if (progress != null && !progress.checkpoint()) return false;
                            decodedBytes = checkedAddDecodedStreamBytes(decodedBytes, read);
                            outStream.write(buffer, 0, read);
                            if (progress != null) progress.addDoneBytes(read);
                        }
                    }
                    outStream.flush();
                }
            }
            return sawEntry;
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
                if (entry.isDirectory()) continue;
                String path = sanitizeEntryPathForList(entry.getName());
                if (!entryPath.equals(path)) {
                    drainSevenZEntry(sevenZ, entry, buffer);
                    continue;
                }
                long decodedBytes = 0L;
                try (OutputStream outStream = new BufferedOutputStream(new FileOutputStream(outFile))) {
                    if (entry.hasStream()) {
                        int read;
                        while ((read = sevenZ.read(buffer)) > 0) {
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
        while ((read = sevenZ.read(buffer)) > 0) {
            decodedBytes = checkedAddDecodedStreamBytes(decodedBytes, read);
            // Drain unread payload before moving to the next entry in solid archives.
            // The stream-time safety limit still applies here because draining a
            // previous solid member can decode attacker-controlled bytes even when
            // the caller only requested a later single entry.
        }
    }

    private static SevenZFile openSevenZFile(@NonNull File archive, @Nullable char[] password) throws IOException {
        SevenZSplitVolumeResolver.VolumeSet splitVolumes = SevenZSplitVolumeResolver.resolve(archive);
        if (splitVolumes != null) {
            SeekableByteChannel channel = MultiReadOnlySeekableByteChannel.forFiles(
                    splitVolumes.parts.toArray(new File[0]));
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

    private static InputStream wrapTarPayloadInputStream(@NonNull InputStream input, @NonNull Type type) throws IOException {
        switch (type) {
            case TAR_GZ:
                return new GzipCompressorInputStream(input);
            case TAR_BZ2:
                return new BZip2CompressorInputStream(input);
            case TAR_XZ:
                return new XZCompressorInputStream(input);
            case TAR_LZMA:
                return new LZMACompressorInputStream(input);
            case TAR_Z:
                return new ZCompressorInputStream(input);
            case TAR_ZST:
                return new ZstdCompressorInputStream(input);
            case TAR_LZ4:
                return new FramedLZ4CompressorInputStream(input);
            case TAR:
            default:
                return input;
        }
    }


    private static InputStream wrapSingleCompressedInputStream(@NonNull InputStream input, @NonNull Type type) throws IOException {
        switch (type) {
            case SINGLE_GZ:
                return new GzipCompressorInputStream(input);
            case SINGLE_BZ2:
                return new BZip2CompressorInputStream(input);
            case SINGLE_XZ:
                return new XZCompressorInputStream(input);
            case SINGLE_LZMA:
                return new LZMACompressorInputStream(input);
            case SINGLE_Z:
                return new ZCompressorInputStream(input);
            case SINGLE_ZST:
                return new ZstdCompressorInputStream(input);
            case SINGLE_LZ4:
                return new FramedLZ4CompressorInputStream(input);
            default:
                throw new IOException("Unsupported single-file compression format");
        }
    }

    @NonNull
    private static String getSingleCompressedOutputName(@NonNull File archive) {
        String name = archive.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (isFirstNumericSplitName(lower)) {
            name = name.substring(0, name.length() - 4);
            lower = lower.substring(0, lower.length() - 4);
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
    private static File resolveArchiveEntryOutput(@NonNull File targetDir, String rawEntryName) {
        String entryName = sanitizeEntryPathForList(rawEntryName);
        if (entryName == null) return null;
        File out = new File(targetDir, entryName);
        return isSameOrDescendant(targetDir, out) ? out : null;
    }

    private static boolean writeArchiveEntryStream(@NonNull InputStream in, @NonNull File out) throws IOException {
        return writeArchiveEntryStream(in, out, null);
    }

    private static boolean writeArchiveEntryStream(@NonNull InputStream in,
                                                   @NonNull File out,
                                                   @Nullable FileOperationProgress progress) throws IOException {
        File outParent = out.getParentFile();
        if (outParent == null) return false;
        if (!outParent.exists() && !outParent.mkdirs()) return false;
        byte[] buffer = new byte[1024 * 64];
        long decodedBytes = 0L;
        try (OutputStream outStream = new BufferedOutputStream(new FileOutputStream(out))) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (progress != null && !progress.checkpoint()) return false;
                decodedBytes = checkedAddDecodedStreamBytes(decodedBytes, read);
                outStream.write(buffer, 0, read);
                if (progress != null) progress.addDoneBytes(read);
            }
            outStream.flush();
            return true;
        }
    }

    private static long checkedAddDecodedStreamBytes(long current, int justRead) throws IOException {
        if (justRead <= 0) return current;
        if (current > MAX_EXTRACTION_TOTAL_BYTES - justRead) {
            throw new UnsupportedArchiveFeatureException(
                    "Decoded archive stream exceeds the extraction safety limit");
        }
        return current + justRead;
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
    private static String sanitizeEntryPathForList(String rawEntryName) {
        if (rawEntryName == null) return null;
        String entryName = rawEntryName.trim().replace('\\', '/');
        while (entryName.startsWith("./")) entryName = entryName.substring(2);
        while (entryName.contains("//")) entryName = entryName.replace("//", "/");
        if (entryName.length() == 0) return null;
        if (entryName.startsWith("/")
                || entryName.equals("..")
                || entryName.startsWith("../")
                || entryName.contains("/../")
                || entryName.endsWith("/..")
                || entryName.matches("^[A-Za-z]:.*")) {
            return null;
        }
        return entryName;
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
    private static List<EntryInfo> withSyntheticDirectories(@NonNull List<EntryInfo> entries) {
        Map<String, EntryInfo> map = new LinkedHashMap<>();
        for (EntryInfo entry : entries) {
            String path = entry.path;
            int slash = path.indexOf('/');
            while (slash >= 0) {
                String dir = path.substring(0, slash + 1);
                if (!map.containsKey(dir)) map.put(dir, new EntryInfo(dir, true, -1L, 0L));
                slash = path.indexOf('/', slash + 1);
            }
            map.put(path, entry);
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

    private static boolean replaceExistingDirectoryWithTemp(@NonNull File destinationDir,
                                                            @NonNull File tempDir) {
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

        deleteFileSystemItem(destinationDir);
        boolean restored = renameFileSystemItem(backupDir, destinationDir);
        if (!restored) {
            restored = copyDirectoryRecursively(backupDir, destinationDir);
            deleteFileSystemItem(backupDir);
        }
        return false;
    }

    private static boolean renameFileSystemItem(@NonNull File source, @NonNull File destination) {
        if (!source.exists() || destination.exists()) return false;
        try {
            return source.renameTo(destination);
        } catch (SecurityException ignored) {
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
        if (!sourceDir.exists() || !sourceDir.isDirectory()) return false;
        if (isSameOrDescendant(sourceDir, destinationDir)) return false;
        if (!destinationDir.exists()) {
            try {
                if (!destinationDir.mkdirs()) return false;
            } catch (SecurityException ignored) {
                return false;
            }
        }
        File[] children;
        try {
            children = sourceDir.listFiles();
        } catch (SecurityException ignored) {
            return false;
        }
        if (children == null) return false;
        for (File child : children) {
            File childDestination = new File(destinationDir, child.getName());
            boolean ok = child.isDirectory()
                    ? copyDirectoryRecursively(child, childDestination)
                    : copyRegularFile(child, childDestination);
            if (!ok) {
                deleteFileSystemItem(destinationDir);
                return false;
            }
        }
        return true;
    }

    private static boolean copyRegularFile(@NonNull File source, @NonNull File destination) {
        File parent = destination.getParentFile();
        if (parent == null || !parent.exists() || !parent.isDirectory()) return false;
        byte[] buffer = new byte[1024 * 64];
        boolean copied;
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
            copied = destination.length() == source.length();
        } catch (IOException | SecurityException ignored) {
            copied = false;
        }
        if (!copied) {
            try { destination.delete(); } catch (SecurityException ignored) {}
        }
        return copied;
    }

    private static boolean clearDirectoryContents(@NonNull File directory) {
        if (!directory.exists()) return true;
        if (!directory.isDirectory()) return false;
        File[] children;
        try {
            children = directory.listFiles();
        } catch (SecurityException ignored) {
            return false;
        }
        if (children == null) return false;
        for (File child : children) {
            if (!deleteFileSystemItem(child)) return false;
        }
        return true;
    }

    private static boolean deleteFileSystemItem(@NonNull File target) {
        if (!target.exists()) return true;
        if (target.isDirectory()) {
            File[] children;
            try {
                children = target.listFiles();
            } catch (SecurityException ignored) {
                return false;
            }
            if (children == null) return false;
            for (File child : children) {
                if (!deleteFileSystemItem(child)) return false;
            }
        }
        try {
            return target.delete();
        } catch (SecurityException ignored) {
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
        } catch (IOException ignored) {
            String ancestorPath = ancestor.getAbsolutePath();
            String candidatePath = candidate.getAbsolutePath();
            return candidatePath.equals(ancestorPath) || candidatePath.startsWith(ancestorPath + File.separator);
        }
    }
}
