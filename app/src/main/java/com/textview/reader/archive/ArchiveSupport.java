package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.z.ZCompressorInputStream;

import com.textview.reader.util.FileOperationProgress;
import com.textview.reader.util.FileTreeProgressTracker;

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
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ArchiveSupport {
    private ArchiveSupport() {}

    private static final Pattern RAR_NEW_STYLE_PART = Pattern.compile("^(.*)\\.part(\\d+)\\.rar$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAR_OLD_STYLE_PART = Pattern.compile("^(.*)\\.r(\\d{2,3})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EGG_VOLUME_PART = Pattern.compile("^(.*)\\.vol(\\d+)\\.egg$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALZ_VOLUME_PART = Pattern.compile("^(.*)\\.a(\\d{2,3})$", Pattern.CASE_INSENSITIVE);

    private static final long MAX_EXTRACTION_TOTAL_BYTES = 32L * 1024L * 1024L * 1024L;
    private static final long MIN_EXTRACTION_FREE_MARGIN_BYTES = 64L * 1024L * 1024L;

    public enum Type {
        ZIP,
        TAR,
        TAR_GZ,
        TAR_BZ2,
        TAR_XZ,
        TAR_LZMA,
        TAR_Z,
        SEVEN_Z,
        RAR,
        ALZ,
        EGG,
        SINGLE_GZ,
        SINGLE_BZ2,
        SINGLE_XZ,
        SINGLE_LZMA,
        SINGLE_Z
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
        UNSUPPORTED_FEATURE,
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
        if (!file.isFile()) return null;
        Type splitType = getAlzipSplitArchiveType(file);
        if (splitType != null) return splitType;
        Type nameType = getSupportedArchiveType(file.getName());
        if (nameType != null) return nameType;
        return hasEmbeddedRarSignatureForSfx(file) ? Type.RAR : null;
    }

    @Nullable
    public static Type getSupportedArchiveType(@NonNull String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (SevenZSplitVolumeResolver.isSevenZSplitPartName(fileName)) return Type.SEVEN_Z;
        if (isFirstNumericSplitName(name)) {
            Type splitBaseType = getSupportedArchiveType(name.substring(0, name.length() - 4));
            if (splitBaseType != null) return splitBaseType;
        }
        if (isFirstRarSplitName(name)) return Type.RAR;
        if (RAR_OLD_STYLE_PART.matcher(name).matches()) return Type.RAR;
        if (EGG_VOLUME_PART.matcher(name).matches()) return Type.EGG;
        if (name.endsWith(".zip") || name.endsWith(".zipx") || name.endsWith(".cbz")) return Type.ZIP;
        if (name.endsWith(".rar") || name.endsWith(".cbr")) return Type.RAR;
        if (name.endsWith(".alz")) return Type.ALZ;
        if (name.endsWith(".egg")) return Type.EGG;
        if (name.endsWith(".7z") || name.endsWith(".cb7")) return Type.SEVEN_Z;
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) return Type.TAR_GZ;
        if (name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz")) return Type.TAR_BZ2;
        if (name.endsWith(".tar.xz") || name.endsWith(".txz")) return Type.TAR_XZ;
        if (name.endsWith(".tar.lzma") || name.endsWith(".tlz")) return Type.TAR_LZMA;
        if (name.endsWith(".tar.z") || name.endsWith(".taz")) return Type.TAR_Z;
        if (name.endsWith(".tar") || name.endsWith(".cbt")) return Type.TAR;
        if (name.endsWith(".gz")) return Type.SINGLE_GZ;
        if (name.endsWith(".bz2")) return Type.SINGLE_BZ2;
        if (name.endsWith(".xz")) return Type.SINGLE_XZ;
        if (name.endsWith(".lzma")) return Type.SINGLE_LZMA;
        if (name.endsWith(".z")) return Type.SINGLE_Z;
        return null;
    }

    public static boolean isSupportedArchive(@NonNull File file) {
        return getSupportedArchiveType(file) != null;
    }

    public static boolean isSupportedArchiveFileName(@NonNull String fileName) {
        return getSupportedArchiveType(fileName) != null;
    }

    private static boolean isFirstNumericSplitName(@NonNull String lowerName) {
        return lowerName.endsWith(".001") && lowerName.length() > 4;
    }

    private static boolean hasEmbeddedRarSignatureForSfx(@NonNull File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".exe") && !name.endsWith(".sfx") && !name.endsWith(".bin")) {
            return false;
        }
        try {
            return RarArchiveReader.findEmbeddedRarSignatureOffsetForBackend(file) >= 0L;
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    private static boolean isFirstNumericSplitArchive(@NonNull File file) {
        return isFirstNumericSplitName(file.getName().toLowerCase(Locale.ROOT));
    }

    public static String getArchiveOutputBaseName(@NonNull File archive, @NonNull String fallback) {
        String name = archive.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (isFirstNumericSplitName(lower)
                || SevenZSplitVolumeResolver.isSevenZSplitPartName(name)) {
            name = name.substring(0, name.length() - 4);
            lower = lower.substring(0, lower.length() - 4);
        }
        Matcher rarPartMatcher = RAR_NEW_STYLE_PART.matcher(name);
        if (rarPartMatcher.matches()) {
            name = rarPartMatcher.group(1) + ".rar";
            lower = name.toLowerCase(Locale.ROOT);
        }
        String[] archiveExtensions = new String[] {
                ".tar.gz",
                ".tar.bz2",
                ".tar.xz",
                ".tar.lzma",
                ".tar.z",
                ".tgz",
                ".tbz2",
                ".tbz",
                ".txz",
                ".tlz",
                ".taz",
                ".lzma",
                ".bz2",
                ".gz",
                ".xz",
                ".z",
                ".zip",
                ".zipx",
                ".cbz",
                ".rar",
                ".cbr",
                ".alz",
                ".egg",
                ".cb7",
                ".7z",
                ".cbt",
                ".tar"
        };
        for (String ext : archiveExtensions) {
            if (lower.endsWith(ext) && name.length() > ext.length()) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name.length() > 0 ? name : fallback;
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
                    try {
                        listSevenZEntries(prepared.file, null);
                        return false;
                    } catch (PasswordRequiredException e) {
                        return true;
                    } catch (IOException e) {
                        return false;
                    }
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
                case SINGLE_GZ:
                case SINGLE_BZ2:
                case SINGLE_XZ:
                case SINGLE_LZMA:
                case SINGLE_Z:
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
                    return listTarEntriesWithFallback(prepared.file, prepared.type, password);
                case SINGLE_GZ:
                case SINGLE_BZ2:
                case SINGLE_XZ:
                case SINGLE_LZMA:
                case SINGLE_Z:
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
            for (FileHeader header : headers) {
                if (header == null) continue;
                String path = sanitizeEntryPathForList(header.getFileName());
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
    private static List<EntryInfo> listRawZipEntries(@NonNull File archive,
                                                     @Nullable char[] password) throws IOException {
        if (hasZipEncryptedHeaderSignature(archive) && (password == null || password.length == 0)) {
            throw new PasswordRequiredException();
        }
        long length = archive.length();
        int readSize = (int) Math.min(length, 4L * 1024L * 1024L);
        byte[] tail = new byte[readSize];
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            raf.seek(Math.max(0L, length - readSize));
            raf.readFully(tail);
        }
        ArrayList<EntryInfo> result = new ArrayList<>();
        for (int i = 0; i + 46 <= tail.length; i++) {
            int sig = readIntLE(tail, i);
            if (sig != 0x02014b50) continue;
            int nameLength = readUInt16LE(tail, i + 28);
            int extraLength = readUInt16LE(tail, i + 30);
            int commentLength = readUInt16LE(tail, i + 32);
            int nameStart = i + 46;
            int nameEnd = nameStart + nameLength;
            if (nameLength <= 0 || nameEnd > tail.length) continue;
            String rawName = new String(tail, nameStart, nameLength, StandardCharsets.UTF_8);
            String path = sanitizeEntryPathForList(rawName);
            if (path != null) {
                long size = readUInt32LE(tail, i + 24);
                boolean directory = rawName.replace('\\', '/').endsWith("/");
                result.add(new EntryInfo(path, directory, size, 0L));
            }
            long next = (long) nameEnd + extraLength + commentLength;
            if (next > i && next <= tail.length) i = (int) next - 1;
        }
        if (result.isEmpty()) throw new IOException("Unsupported ZIP directory");
        return withSyntheticDirectories(result);
    }

    @NonNull
    private static List<EntryInfo> listSevenZEntries(@NonNull File archive, @Nullable char[] password) throws IOException {
        List<EntryInfo> result = new ArrayList<>();
        try (SevenZFile sevenZ = openSevenZFile(archive, password)) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                String path = sanitizeEntryPathForList(entry.getName());
                if (path == null) continue;
                result.add(new EntryInfo(path, entry.isDirectory(), entry.getSize(), 0L));
            }
            return withSyntheticDirectories(result);
        } catch (IOException e) {
            if (password == null || password.length == 0) throw new PasswordRequiredException();
            throw e;
        }
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
            return ExtractionResult.failed(classifyExtractionFailure(e), e.getMessage());
        }
    }

    public static boolean extractSingleEntry(@NonNull File archive,
                                             @NonNull String entryPath,
                                             @NonNull File outFile,
                                             @Nullable char[] password) {
        return extractSingleEntryDetailed(archive, entryPath, outFile, password).success;
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
                    ok = extractSingleTarEntryWithFallback(prepared.file, prepared.type, normalized, outFile);
                    break;
                case SINGLE_GZ:
                case SINGLE_BZ2:
                case SINGLE_XZ:
                case SINGLE_LZMA:
                case SINGLE_Z:
                    ok = extractSingleCompressedEntry(prepared.file, archive, normalized, outFile, prepared.type);
                    break;
                default:
                    ok = false;
            }
            return ok
                    ? ExtractionResult.success()
                    : ExtractionResult.failed(ExtractionFailure.FAILED, null);
        } catch (PasswordRequiredException e) {
            try { outFile.delete(); } catch (SecurityException ignored) {}
            return ExtractionResult.failed(ExtractionFailure.PASSWORD_REQUIRED, e.getMessage());
        } catch (UnsupportedArchiveFeatureException e) {
            try { outFile.delete(); } catch (SecurityException ignored) {}
            return ExtractionResult.failed(ExtractionFailure.UNSUPPORTED_FEATURE, e.getMessage());
        } catch (IOException | SecurityException e) {
            try { outFile.delete(); } catch (SecurityException ignored2) {}
            return ExtractionResult.failed(classifyExtractionFailure(e), e.getMessage());
        }
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
                    return extractTarIntoDirectoryWithFallback(prepared.file, prepared.type, targetDir, progress, entryProgress);
                case SINGLE_GZ:
                case SINGLE_BZ2:
                case SINGLE_XZ:
                case SINGLE_LZMA:
                case SINGLE_Z:
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
                return true;
            default:
                return false;
        }
    }

    @NonNull
    private static ExtractionFailure classifyExtractionFailure(@NonNull Exception e) {
        String message = e.getMessage();
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (lower.contains("password") || lower.contains("decrypt")) {
            return ExtractionFailure.PASSWORD_REQUIRED;
        }
        if (lower.contains("not supported") || lower.contains("unsupported")
                || lower.contains("unknown compression method")
                || lower.contains("not available yet")) {
            return ExtractionFailure.UNSUPPORTED_FEATURE;
        }
        return ExtractionFailure.FAILED;
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
        for (int index = 1; index <= 999; index++) {
            String suffix = String.format(Locale.ROOT, ".%03d", index);
            File part = new File(parent, stem + suffix);
            if (!part.exists() || !part.isFile()) {
                if (index == 1) throw new IOException("First split archive part is missing");
                break;
            }
            result.add(part);
        }
        return result;
    }

    private static boolean isFirstRarSplitName(@NonNull String lowerName) {
        Matcher matcher = RAR_NEW_STYLE_PART.matcher(lowerName);
        if (matcher.matches()) {
            try {
                return Integer.parseInt(matcher.group(2)) == 1;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return lowerName.endsWith(".rar");
    }

    private static boolean isRarSplitPart(@NonNull File file) {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        return lower.endsWith(".rar") || RAR_NEW_STYLE_PART.matcher(lower).matches() || RAR_OLD_STYLE_PART.matcher(lower).matches();
    }

    @Nullable
    private static Type getAlzipSplitArchiveType(@NonNull File file) {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (EGG_VOLUME_PART.matcher(lower).matches()) return Type.EGG;
        Matcher alzPart = ALZ_VOLUME_PART.matcher(lower);
        if (!alzPart.matches()) return null;
        File parent = file.getParentFile();
        if (parent == null) return null;
        String prefix = file.getName().substring(0, lower.lastIndexOf(".a"));
        File first = new File(parent, prefix + ".alz");
        return first.exists() && first.isFile() ? Type.ALZ : null;
    }

    private static boolean isAlzipSplitPart(@NonNull File file) {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        return EGG_VOLUME_PART.matcher(lower).matches() || ALZ_VOLUME_PART.matcher(lower).matches();
    }

    @NonNull
    private static File resolveFirstAlzipPart(@NonNull File selectedPart, @NonNull Type type) {
        File parent = selectedPart.getParentFile();
        if (parent == null) return selectedPart;
        String name = selectedPart.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (type == Type.EGG) {
            Matcher eggPart = EGG_VOLUME_PART.matcher(lower);
            if (eggPart.matches()) {
                String prefix = name.substring(0, lower.lastIndexOf(".vol"));
                File first = new File(parent, prefix + ".vol1.egg");
                return first.exists() && first.isFile() ? first : selectedPart;
            }
        }
        if (type == Type.ALZ) {
            Matcher alzPart = ALZ_VOLUME_PART.matcher(lower);
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
        Matcher newStyle = RAR_NEW_STYLE_PART.matcher(lower);
        if (newStyle.matches()) {
            String originalPrefix = name.substring(0, lower.lastIndexOf(".part"));
            return collectNewStyleRarParts(parent, originalPrefix);
        }
        Matcher oldStyle = RAR_OLD_STYLE_PART.matcher(lower);
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
            if (progress != null) progress.setTotalBytes(sumZipPayloadBytes(headers));
            for (FileHeader header : headers) {
                if (header == null) continue;
                if (progress != null && !progress.checkpoint()) return false;
                File out = resolveArchiveEntryOutput(targetDir, header.getFileName());
                if (out == null) return false;
                sawEntry = true;
                if (header.isDirectory() || header.getFileName().replace('\\', '/').endsWith("/")) {
                    if (entryProgress != null) entryProgress.onDirectory(header.getFileName());
                    if (!out.exists() && !out.mkdirs()) return false;
                    continue;
                }
                if (entryProgress != null) entryProgress.onFile(header.getFileName());
                else if (progress != null) progress.setDetail(header.getFileName());
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
        try (org.apache.commons.compress.archivers.zip.ZipFile zip =
                     org.apache.commons.compress.archivers.zip.ZipFile.builder()
                             .setFile(archive)
                             .get()) {
            java.util.Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (entry == null) continue;
                String path = sanitizeEntryPathForList(entry.getName());
                if (path == null) continue;
                if (onlyEntryPath != null && !onlyEntryPath.equals(path)) continue;

                File out = resolveArchiveEntryOutput(targetDir, entry.getName());
                if (out == null) {
                    if (onlyEntryPath != null) return false;
                    continue;
                }
                sawEntry = true;
                if (entry.isDirectory() || entry.getName().replace('\\', '/').endsWith("/")) {
                    if (entryProgress != null) entryProgress.onDirectory(entry.getName());
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
                if (entryProgress != null) entryProgress.onFile(entry.getName());
                else if (progress != null) progress.setDetail(entry.getName());
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

    private static boolean extractSingleZipEntry(@NonNull File archive,
                                                 @NonNull String entryPath,
                                                 @NonNull File outFile,
                                                 @Nullable char[] password) throws IOException {
        try {
            ZipFile zip = new ZipFile(archive);
            if (zip.isEncrypted()) {
                if (password == null || password.length == 0) throw new PasswordRequiredException();
                zip.setPassword(password);
            }
            @SuppressWarnings("unchecked")
            List<FileHeader> headers = zip.getFileHeaders();
            for (FileHeader header : headers) {
                if (header == null || header.isDirectory()) continue;
                String path = sanitizeEntryPathForList(header.getFileName());
                if (!entryPath.equals(path)) continue;
                try (InputStream in = zip.getInputStream(header)) {
                    return writeArchiveEntryStream(in, outFile);
                }
            }
            return false;
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
        try (org.apache.commons.compress.archivers.zip.ZipFile zip =
                     org.apache.commons.compress.archivers.zip.ZipFile.builder()
                             .setFile(archive)
                             .get()) {
            java.util.Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;
                String path = sanitizeEntryPathForList(entry.getName());
                if (!entryPath.equals(path)) continue;
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
                File out = resolveArchiveEntryOutput(targetDir, entry.getName());
                if (out == null) return false;
                sawEntry = true;
                if (entry.isDirectory() || entry.getName().replace('\\', '/').endsWith("/")) {
                    if (entryProgress != null) entryProgress.onDirectory(entry.getName());
                    if (!out.exists() && !out.mkdirs()) return false;
                    continue;
                }
                if (entryProgress != null) entryProgress.onFile(entry.getName());
                else if (progress != null) progress.setDetail(entry.getName());
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
                File out = resolveArchiveEntryOutput(targetDir, entry.getName());
                if (out == null) return false;
                sawEntry = true;
                if (entry.isDirectory() || entry.getName().replace('\\', '/').endsWith("/")) {
                    if (entryProgress != null) entryProgress.onDirectory(entry.getName());
                    if (!out.exists() && !out.mkdirs()) return false;
                    continue;
                }
                if (entryProgress != null) entryProgress.onFile(entry.getName());
                else if (progress != null) progress.setDetail(entry.getName());
                File outParent = out.getParentFile();
                if (outParent == null) return false;
                if (!outParent.exists() && !outParent.mkdirs()) return false;
                try (OutputStream outStream = new BufferedOutputStream(new FileOutputStream(out))) {
                    if (entry.hasStream()) {
                        int read;
                        while ((read = sevenZ.read(buffer)) > 0) {
                            if (progress != null && !progress.checkpoint()) return false;
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
                try (OutputStream outStream = new BufferedOutputStream(new FileOutputStream(outFile))) {
                    if (entry.hasStream()) {
                        int read;
                        while ((read = sevenZ.read(buffer)) > 0) {
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
        while (sevenZ.read(buffer) > 0) {
            // Drain unread payload before moving to the next entry in solid archives.
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
        String[] extensions = new String[] {".lzma", ".bz2", ".gz", ".xz", ".z"};
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
        try (OutputStream outStream = new BufferedOutputStream(new FileOutputStream(out))) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (progress != null && !progress.checkpoint()) return false;
                outStream.write(buffer, 0, read);
                if (progress != null) progress.addDoneBytes(read);
            }
            outStream.flush();
            return true;
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
