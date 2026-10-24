package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.codecs.ZipxNativeCodecs;
import com.readwide.codecs.PpmdZipInputStream;
import com.readwide.manager.util.FileOperationProgress;

import net.lingala.zip4j.crypto.AESDecrypter;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.AESExtraDataRecord;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.AesVersion;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.compressors.deflate64.Deflate64CompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.tukaani.xz.LZMAInputStream;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Narrow ZIPX supplement for combinations Zip4j cannot decode by itself.
 *
 * <p>ZIPX is still a ZIP container. WinZip AES stores method 99 in the ZIP
 * header and puts the real compression method in extra field 0x9901. Commons
 * Compress can decode Deflate64, BZip2, and XZ, while XZ for Java decodes the
 * ZIP-specific LZMA stream; neither layer decrypts WinZip AES. This reader joins
 * those already-bundled capabilities: Zip4j authenticates/decrypts the raw entry
 * payload and the matching codec decodes it. WinZip JPEG and WavPack use the
 * isolated FOSS native codec module.</p>
 *
 * <p>The routing is intentionally explicit. It claims only Store, Deflate,
 * Shrink, Reduce, Implode, Deflate64, BZip2, LZMA, XZ, JPEG, WavPack, PPMd-I
 * and Zstandard (93/legacy 20).
 * Zstandard uses the existing native raw-stream decoder after AES decryption;
 * PPMd-I has an independent streaming decoder; the native ZIP-container route
 * remains available when that supplemental decoder cannot load.</p>
 */
final class ZipxAesArchiveReader {
    private static final int METHOD_STORED = 0;
    private static final int METHOD_SHRINK = 1;
    private static final int METHOD_IMPLODE = 6;
    private static final int METHOD_DEFLATE = 8;
    private static final int METHOD_DEFLATE64 = 9;
    private static final int METHOD_BZIP2 = 12;
    private static final int METHOD_LZMA = 14;
    private static final int METHOD_ZSTANDARD_LEGACY = 20;
    private static final int METHOD_ZSTANDARD = 93;
    private static final int METHOD_XZ = 95;
    private static final int METHOD_JPEG = ZipxNativeCodecs.METHOD_JPEG;
    private static final int METHOD_WAVPACK = ZipxNativeCodecs.METHOD_WAVPACK;
    private static final int METHOD_PPMD = 98;
    private static final int METHOD_WINZIP_AES = 99;
    private static final int WINZIP_AES_EXTRA_ID = 0x9901;
    private static final int AES_AUTHENTICATION_LENGTH = 10;
    private static final int ZIP_LZMA_PROPERTIES_SIZE = 5;

    private ZipxAesArchiveReader() {}

    // Bounded open indexes, with leases so eviction never closes an in-flight
    // decode. Passwords/decryptors are per extraction, never stored here.
    private static final Map<String, CachedIndex> INDEXES = new LinkedHashMap<>(4, 0.75f, true);
    private static final Map<String, IndexGate> INDEX_GATES = new java.util.HashMap<>();
    private static long cacheGeneration;

    private static final class IndexGate {
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        int users;
    }

    private static final class CachedIndex {
        final org.apache.commons.compress.archivers.zip.ZipFile zip;
        final Source source;
        final Map<String, EntryDescriptor> entries = new java.util.HashMap<>();
        int users;
        boolean retired;
        private SplitVolumeInput headers;
        CachedIndex(Source source) throws IOException {
            this.source = source;
            zip = source.openZip();
            boolean initialized = false;
            try {
                List<EntryDescriptor> descriptors = readEntries(zip, source);
                // Displayed identities own their keys before compatibility aliases are added.
                // An earlier Unicode/parser alias must not shadow a later listed file.
                for (EntryDescriptor entry : descriptors) {
                    if (entry.entry.isDirectory()) continue;
                    String path = ArchiveSupport.sanitizeEntryPathForList(entry.displayName);
                    if (path != null && !entries.containsKey(path)) entries.put(path, entry);
                }
                for (EntryDescriptor entry : descriptors) {
                    if (entry.entry.isDirectory()) continue;
                    String alias = ArchiveSupport.sanitizeEntryPathForList(entry.entry.getName());
                    if (alias != null && !entries.containsKey(alias)) entries.put(alias, entry);
                    String legacyAlias = ArchiveSupport.sanitizeEntryPathForList(entry.legacyName);
                    if (legacyAlias != null && !entries.containsKey(legacyAlias)) entries.put(legacyAlias, entry);
                }
                source.requireSameVolumes();
                initialized = true;
            } finally {
                if (!initialized) zip.close();
            }
        }
        SplitVolumeInput headers() throws IOException {
            if (headers == null) headers = source.openHeaderInput();
            return headers;
        }
        void closeQuietly() {
            try { zip.close(); } catch (IOException | RuntimeException ignored) { }
            finally {
                if (headers != null) try { headers.close(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static CachedIndex acquireIndex(File archive) throws IOException {
        String key = archive.getCanonicalPath();
        IndexGate gate;
        synchronized (INDEXES) {
            gate = INDEX_GATES.get(key);
            if (gate == null) {
                gate = new IndexGate();
                INDEX_GATES.put(key, gate);
            }
            gate.users++;
        }
        boolean locked = false;
        List<CachedIndex> closing = new ArrayList<>();
        try {
            try {
                gate.lock.lockInterruptibly();
                locked = true;
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("ZIPX index acquisition cancelled");
            }
            // Discovery and parsing can be slow. Serialize only callers for this key,
            // never unrelated archives or lease release behind the global LRU lock.
            Source source = Source.capture(archive);
            long generation;
            synchronized (INDEXES) {
                CachedIndex index = INDEXES.get(key);
                if (index != null && index.source.sameVolumes(source)) {
                    index.users++;
                    return index;
                }
                if (index != null) retire(INDEXES.remove(key), closing);
                generation = cacheGeneration;
            }
            CachedIndex created = new CachedIndex(source);
            try {
                Source.checkpoint();
            } catch (IOException cancelled) {
                created.closeQuietly();
                throw cancelled;
            }
            synchronized (INDEXES) {
                created.users = 1;
                if (generation != cacheGeneration) {
                    // Clear/release during construction must not resurrect an index.
                    created.retired = true;
                } else {
                    INDEXES.put(key, created);
                    while (INDEXES.size() > 3) {
                        String oldest = INDEXES.keySet().iterator().next();
                        retire(INDEXES.remove(oldest), closing);
                    }
                }
            }
            return created;
        } finally {
            if (locked) gate.lock.unlock();
            synchronized (INDEXES) {
                if (--gate.users == 0) INDEX_GATES.remove(key);
            }
            closeIndexes(closing);
        }
    }

    /** Called under INDEXES; potentially blocking close is deferred until after unlock. */
    private static void retire(CachedIndex index, List<CachedIndex> closing) {
        index.retired = true;
        if (index.users == 0) closing.add(index);
    }

    private static void closeIndexes(List<CachedIndex> indexes) {
        for (CachedIndex index : indexes) index.closeQuietly();
    }

    private static void releaseIndex(CachedIndex index) {
        boolean close;
        synchronized (INDEXES) {
            index.users--;
            close = index.retired && index.users == 0;
        }
        if (close) index.closeQuietly();
    }

    static void clearCachedIndexes() {
        List<CachedIndex> closing = new ArrayList<>();
        synchronized (INDEXES) {
            cacheGeneration++;
            for (CachedIndex index : INDEXES.values()) retire(index, closing);
            INDEXES.clear();
        }
        closeIndexes(closing);
    }

    static void releaseArchiveIndex(File archive) {
        List<CachedIndex> closing = new ArrayList<>();
        try {
            String path = archive.getCanonicalPath();
            synchronized (INDEXES) {
                cacheGeneration++;
                java.util.Iterator<Map.Entry<String, CachedIndex>> iterator = INDEXES.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, CachedIndex> index = iterator.next();
                    if (index.getValue().source.contains(path)) {
                        retire(index.getValue(), closing);
                        iterator.remove();
                    }
                }
            }
        } catch (IOException | SecurityException ignored) { }
        finally { closeIndexes(closing); }
    }

    static boolean hasCachedIndex(File archive) {
        try {
            String key = archive.getCanonicalPath();
            CachedIndex index;
            synchronized (INDEXES) {
                index = INDEXES.get(key);
                if (index == null) return false;
            }
            // Routing hint only: acquisition performs full membership validation before use.
            // No disk I/O under the LRU lock, and no duplicate directory/EOCD scan here.
            index.source.requireUnchanged();
            return true;
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    /** Split directories may span disks that a single-file header parser cannot read. */
    @Nullable
    static List<ArchiveSupport.EntryInfo> listSplitEntries(File archive, char[] password) throws IOException {
        Source source = Source.capture(archive);
        if (source.files.size() == 1) return null;
        try (org.apache.commons.compress.archivers.zip.ZipFile zip = source.openZip(true)) {
            List<EntryDescriptor> entries = readEntries(zip, source);
            ArrayList<ArchiveSupport.EntryInfo> result = new ArrayList<>(entries.size());
            for (EntryDescriptor descriptor : entries) {
                Source.checkpoint();
                if (descriptor.entry.getGeneralPurposeBit().usesEncryption()
                        && (password == null || password.length == 0)) {
                    throw new ArchiveSupport.PasswordRequiredException();
                }
                String path = ArchiveSupport.sanitizeEntryPathForList(descriptor.displayName);
                if (path != null) result.add(new ArchiveSupport.EntryInfo(path,
                        descriptor.entry.isDirectory() || path.endsWith("/"), descriptor.entry.getSize(), 0L));
            }
            source.requireSameVolumes();
            return result;
        }
    }

    /** True only when the complete archive can use this reader without mixing backends. */
    static boolean canExtractArchive(@NonNull File archive) {
        try {
            Source source = Source.capture(archive);
            try (org.apache.commons.compress.archivers.zip.ZipFile zip = source.openZip()) {
                Enumeration<ZipArchiveEntry> entries = zip.getEntries();
                boolean sawSupplemental = source.files.size() > 1;
                while (entries.hasMoreElements()) {
                    ZipArchiveEntry entry = entries.nextElement();
                    if (entry == null || entry.isDirectory()) continue;
                    AesMetadata aes = parseAesMetadata(entry);
                    if (aes != null) {
                        if (!isSupportedAesMethod(aes.compressionMethod)) return false;
                        sawSupplemental |= isExtendedMethod(aes.compressionMethod);
                        continue;
                    }
                    if (entry.getGeneralPurposeBit().usesEncryption()) {
                        if (!isTraditionalStreamEntry(entry)) return false;
                        sawSupplemental |= isExtendedMethod(entry.getMethod());
                        continue;
                    }
                    if (isPlainNativeEntry(entry) || isPlainStreamEntry(entry)
                            || (isPlainCommonsExtendedEntry(entry) && zip.canReadEntryData(entry))) {
                        sawSupplemental = true;
                        continue;
                    }
                    if (!isRuntimeSupportedPlainMethod(entry.getMethod())
                            || !zip.canReadEntryData(entry)) {
                        return false;
                    }
                }
                return sawSupplemental;
            }
        } catch (IOException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Detects AES ZIPX methods outside both the Java and libarchive 3.8.9 sets. */
    static boolean hasUnsupportedAesMethod(@NonNull File archive) {
        try (org.apache.commons.compress.archivers.zip.ZipFile zip = open(archive)) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;
                AesMetadata aes = parseAesMetadata(entry);
                if (aes != null
                        && !isSupportedAesMethod(aes.compressionMethod)
                        && !isLibarchiveAesMethod(aes.compressionMethod)) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException | LinkageError ignored) {
            // Let the established ZIP parsers classify malformed containers.
        }
        return false;
    }

    static boolean extractArchiveIntoDirectory(@NonNull File archive,
                                               @NonNull File targetDir,
                                               @Nullable char[] password,
                                               @Nullable FileOperationProgress progress,
                                               @Nullable ArchiveExtractionProgressTracker entryProgress)
            throws IOException {
        Source source = Source.capture(archive);
        try (org.apache.commons.compress.archivers.zip.ZipFile zip = source.openZip();
             SplitVolumeInput headers = source.openHeaderInput()) {
            List<EntryDescriptor> entries = readEntries(zip, source);
            if (password == null || password.length == 0) {
                for (EntryDescriptor descriptor : entries) {
                    if (!descriptor.entry.isDirectory() && descriptor.entry.getGeneralPurposeBit().usesEncryption()) {
                        throw new ArchiveSupport.PasswordRequiredException();
                    }
                }
            }
            List<File> outputs = preflightOutputs(targetDir, entries);
            if (progress != null) progress.setTotalBytes(sumDecodedSizes(entries));
            boolean sawEntry = false;
            for (int i = 0; i < entries.size(); i++) {
                EntryDescriptor descriptor = entries.get(i);
                ZipArchiveEntry entry = descriptor.entry;
                if (progress != null && !progress.checkpoint()) return false;
                source.requireUnchanged();
                File out = outputs.get(i);
                sawEntry = true;
                if (entry.isDirectory() || descriptor.displayName.replace('\\', '/').endsWith("/")) {
                    if (entryProgress != null) entryProgress.onDirectory(descriptor.displayName);
                    RarOutputFileGuard.ensureDirectory(out);
                    continue;
                }
                if (entryProgress != null) entryProgress.onFile(descriptor.displayName);
                else if (progress != null) progress.setDetail(descriptor.displayName);
                if (!extractDescriptor(zip, headers, descriptor, out, password, progress, false)) return false;
            }
            source.requireSameVolumes();
            return sawEntry || entries.isEmpty();
        } catch (LinkageError missingCodec) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                    "ZIPX compression codec is not available", missingCodec);
        }
    }

    /** Validate explicit and implied directory identities before touching any output. */
    private static List<File> preflightOutputs(File targetDir, List<EntryDescriptor> entries) throws IOException {
        ArchiveOutputPreflight plan = new ArchiveOutputPreflight(targetDir);
        List<File> outputs = new ArrayList<>(entries.size());
        for (EntryDescriptor descriptor : entries) {
            Source.checkpoint();
            boolean directory = descriptor.entry.isDirectory()
                    || descriptor.displayName.replace('\\', '/').endsWith("/");
            outputs.add(plan.add(descriptor.displayName, directory));
        }
        return outputs;
    }

    /** Independent handles for plain, single-file Store/Deflate image-cache requests only. */
    @Nullable
    static Boolean tryExtractIndependentPlainEntry(File archive, String path, File output) throws IOException {
        CachedIndex index;
        try {
            index = acquireIndex(archive);
        } catch (java.io.InterruptedIOException cancelled) {
            throw cancelled;
        } catch (IOException unavailableIndex) {
            return null; // No payload/output touched: retain the general parser fallback.
        }
        try {
            EntryDescriptor descriptor = index.entries.get(path);
            if (descriptor == null || index.source.files.size() != 1) return null;
            ZipArchiveEntry entry = descriptor.entry;
            if (descriptor.aes != null || entry.isDirectory() || entry.isUnixSymlink()
                    || (entry.getRawFlag() & 0x2041) != 0
                    || (entry.getMethod() != 0 && entry.getMethod() != 8)
                    || entry.getSize() < 0 || entry.getCompressedSize() < 0 || entry.getCrc() < 0) return null;
            if (index.source.contains(output.getCanonicalPath())) throw new IOException("Output is a ZIP source volume");
            try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(output)) {
                // Cached descriptors are immutable after publication. Do not touch the
                // cached ZipFile channel or shared header cursor during this decode.
                try (SplitVolumeInput input = index.source.openHeaderInput()) {
                    byte[] header = index.source.localHeader(entry, input);
                    long extra = 30L + readUnsignedShort(header, 26) + readUnsignedShort(header, 28);
                    long start = entry.getLocalHeaderOffset();
                    if (start > input.length() - extra) throw new IOException("Truncated ZIP local metadata");
                    try (InputStream packed = input.boundedStream(start + extra, entry.getCompressedSize());
                         InputStream decoded = wrapCompressedStream(packed, entry.getMethod(), entry.getSize(), false)) {
                        IntegrityInputStream checked = new IntegrityInputStream(decoded, entry.getSize());
                        if (!ArchiveSupport.writeArchiveEntryStream(checked, output, null)) return false;
                        verifyPlainEntry(entry, checked);
                    }
                }
                index.source.requireUnchanged();
                index.source.requireSameVolumes();
                guard.commit();
                return true;
            }
        } finally {
            releaseIndex(index);
        }
    }

    /**
     * Returns null when the requested entry does not need this supplemental path.
     * A non-null value is the final extraction result for a covered ZIPX entry.
     */
    @Nullable
    static Boolean tryExtractSingleEntry(@NonNull File archive,
                                         @NonNull String entryPath,
                                         @NonNull File outFile,
                                         @Nullable char[] password) throws IOException {
        CachedIndex index;
        try {
            index = acquireIndex(archive);
        } catch (java.io.InterruptedIOException cancelled) {
            throw cancelled;
        } catch (IOException parserFailure) {
            // Only container/index failure may defer to Zip4j. Once extraction
            // starts below, checksum/authentication errors remain real failures.
            return null;
        } catch (LinkageError missingCodec) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                    "ZIPX parser is not available", missingCodec);
        }
        try {
            synchronized (index) {
                EntryDescriptor descriptor = index.entries.get(entryPath);
                if (descriptor == null) return null;
                AesMetadata aes = descriptor.aes;
                if (aes == null) {
                    if (isPlainNativeEntry(descriptor.entry) || isPlainStreamEntry(descriptor.entry)
                            || (!descriptor.entry.getGeneralPurposeBit().usesEncryption()
                            && (isPlainCommonsExtendedEntry(descriptor.entry) || index.source.files.size() > 1)
                            && index.zip.canReadEntryData(descriptor.entry))) {
                        // Keep the chosen identity and split-volume channel for the supported plain Commons methods too.
                        return extractDescriptor(index.zip, index.headers(), descriptor, outFile, password, null, true);
                    }
                    if (!isTraditionalStreamEntry(descriptor.entry)
                            || (!isExtendedMethod(descriptor.entry.getMethod()) && index.source.files.size() == 1)) return null;
                    if (password == null || password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
                    return extractDescriptor(index.zip, index.headers(), descriptor, outFile, password, null, true);
                }
                if (!isSupportedAesMethod(aes.compressionMethod)) {
                    if (isLibarchiveAesMethod(aes.compressionMethod)) return null;
                    throw unsupportedMethod(aes.compressionMethod);
                }
                if (!isExtendedMethod(aes.compressionMethod) && index.source.files.size() == 1) return null;
                if (password == null || password.length == 0) {
                    throw new ArchiveSupport.PasswordRequiredException();
                }
                return extractDescriptor(index.zip, index.headers(), descriptor, outFile, password, null, true);
            }
        } catch (IOException failure) {
            // A cancelled/failed shared header input is terminal. Future attempts must
            // get a fresh owner; existing leases still close through releaseIndex.
            synchronized (INDEXES) {
                INDEXES.values().remove(index);
                index.retired = true;
            }
            throw failure;
        } catch (LinkageError missingCodec) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                    "ZIPX compression codec is not available", missingCodec);
        } finally {
            releaseIndex(index);
        }
    }

    @NonNull
    private static org.apache.commons.compress.archivers.zip.ZipFile open(@NonNull File archive)
            throws IOException {
        return Source.capture(archive).openZip();
    }

    @NonNull
    private static List<EntryDescriptor> readEntries(
            @NonNull org.apache.commons.compress.archivers.zip.ZipFile zip, Source source) throws IOException {
        ArrayList<ZipArchiveEntry> rawEntries = new ArrayList<>();
        ArrayList<String> unicodeNames = new ArrayList<>();
        boolean hasUnicodeExtra = false;
        ArchiveFilenameDecoder.NameCorpus corpus = new ArchiveFilenameDecoder.NameCorpus();
        Enumeration<ZipArchiveEntry> enumeration = zip.getEntries();
        while (enumeration.hasMoreElements()) {
            ZipArchiveEntry entry = enumeration.nextElement();
            if (entry == null) continue;
            rawEntries.add(entry);
            byte[] rawName = entry.getRawName();
            String unicodeName = ZipEntryNameDecoder.unicodePath(rawName,
                    entry.getGeneralPurposeBit().usesUTF8ForNames(), entry.getCentralDirectoryExtra());
            unicodeNames.add(unicodeName);
            hasUnicodeExtra |= entry.getExtraField(new org.apache.commons.compress.archivers.zip.ZipShort(0x7075)) != null;
            if (rawName != null && !entry.getGeneralPurposeBit().usesUTF8ForNames()) {
                corpus.observe(rawName);
            }
        }
        if (hasUnicodeExtra) {
            // Commons merges local-only extra fields into entries. Such aliases must not
            // become displayed identities: read the actual central fields without locals.
            // Archives without Unicode extras retain the single metadata pass.
            try (org.apache.commons.compress.archivers.zip.ZipFile central = source.openZip(true)) {
                Enumeration<ZipArchiveEntry> names = central.getEntries();
                int index = 0;
                while (names.hasMoreElements()) {
                    ZipArchiveEntry entry = names.nextElement();
                    if (entry == null) continue;
                    if (index >= rawEntries.size()
                            || !java.util.Arrays.equals(entry.getRawName(), rawEntries.get(index).getRawName())) {
                        throw new IOException("ZIP entry identities changed while indexing");
                    }
                    unicodeNames.set(index++, ZipEntryNameDecoder.unicodePath(entry.getRawName(),
                            entry.getGeneralPurposeBit().usesUTF8ForNames(), entry.getCentralDirectoryExtra()));
                }
                if (index != rawEntries.size()) throw new IOException("ZIP entry count changed while indexing");
            }
        }
        ArrayList<EntryDescriptor> result = new ArrayList<>(rawEntries.size());
        for (ZipArchiveEntry entry : rawEntries) {
            byte[] rawName = entry.getRawName();
            String displayName = unicodeNames.get(result.size());
            String legacyName = rawName == null
                    ? entry.getName()
                    : ArchiveFilenameDecoder.decodeZipName(
                            rawName, entry.getGeneralPurposeBit().usesUTF8ForNames(), corpus);
            if (displayName == null) displayName = legacyName;
            result.add(new EntryDescriptor(entry, displayName, legacyName, parseAesMetadata(entry), source));
        }
        return result;
    }

    private static boolean extractDescriptor(
            @NonNull org.apache.commons.compress.archivers.zip.ZipFile zip,
            @NonNull SplitVolumeInput headers,
            @NonNull EntryDescriptor descriptor,
            @NonNull File outFile,
            @Nullable char[] password,
            @Nullable FileOperationProgress progress,
            boolean verifyVolumeMembership) throws IOException {
        // Source identity, integrity/authentication and decoder close precede commit.
        descriptor.source.requireUnchanged();
        byte[] localHeader = descriptor.source.localHeader(descriptor.entry, headers);
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
            if (!extractDescriptorPayload(zip, descriptor, localHeader, outFile, password, progress)) return false;
            descriptor.source.requireUnchanged();
            if (verifyVolumeMembership) descriptor.source.requireSameVolumes();
            guard.commit();
            return true;
        }
    }

    private static boolean extractDescriptorPayload(
            @NonNull org.apache.commons.compress.archivers.zip.ZipFile zip,
            @NonNull EntryDescriptor descriptor,
            @NonNull byte[] localHeader,
            @NonNull File outFile,
            @Nullable char[] password,
            @Nullable FileOperationProgress progress) throws IOException {
        ZipArchiveEntry entry = descriptor.entry;
        if ((descriptor.aes == null ? entry.getMethod() : descriptor.aes.compressionMethod) == METHOD_PPMD
                && PpmdZipInputStream.isAvailable()) {
            return extractPpmdEntry(zip, entry, descriptor.aes, localHeader, descriptor.source,
                    outFile, password, progress);
        }
        if (descriptor.aes == null) {
            if (isPlainNativeEntry(entry)) return extractPlainNativeEntry(zip, entry, outFile, progress);
            if (isTraditionalStreamEntry(entry)) {
                return extractTraditionalEntry(zip, descriptor, localHeader, outFile, password, progress);
            }
            if (isPlainStreamEntry(entry)) {
                return extractPlainStreamPayload(zip, entry, outFile, progress);
            }
            if (entry.getGeneralPurposeBit().usesEncryption() || !zip.canReadEntryData(entry)) {
                throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                        "ZIPX entry uses an unsupported compression/encryption combination");
            }
            return extractPlainEntry(zip, entry, outFile, progress);
        }

        AesMetadata aes = descriptor.aes;
        if (password == null || password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
        if (!isSupportedAesMethod(aes.compressionMethod)) {
            throw unsupportedMethod(aes.compressionMethod);
        }
        InputStream raw = zip.getRawInputStream(entry);
        if (raw == null) throw new IOException("ZIPX raw entry stream is unavailable");
        if (ZipxNativeCodecs.supports(aes.compressionMethod)) {
            return extractNativeAesEntry(raw, entry, aes, outFile, password, progress);
        }
        try (InputStream ownedRaw = raw;
             WinZipAesInputStream decrypted = new WinZipAesInputStream(
                     raw, entry.getCompressedSize(), aes, password, progress);
             InputStream decoded = wrapCompressedStream(
                     decrypted, aes.compressionMethod, entry.getSize(), usesLzmaEndMarker(entry), entry.getRawFlag())) {
            IntegrityInputStream checked = new IntegrityInputStream(decoded, entry.getSize());
            boolean written = ArchiveSupport.writeArchiveEntryStream(checked, outFile, progress);
            if (!written) return false;
            decrypted.verifyAuthentication();
            verifyDecodedEntry(entry, aes, checked);
            return true;
        }
    }

    private static boolean isTraditionalStreamEntry(ZipArchiveEntry entry) {
        return entry.getGeneralPurposeBit().usesEncryption() && (entry.getRawFlag() & 0x2040) == 0
                && entry.getMethod() != METHOD_WINZIP_AES && entry.getCompressedSize() >= 12
                && entry.getSize() >= 0 && entry.getCrc() >= 0
                && isSupportedAesMethod(entry.getMethod());
    }

    private static boolean extractTraditionalEntry(org.apache.commons.compress.archivers.zip.ZipFile zip,
            EntryDescriptor descriptor, byte[] localHeader, File output, char[] password, FileOperationProgress progress) throws IOException {
        ZipArchiveEntry entry = descriptor.entry;
        int checkByte = traditionalCheckByte(entry, localHeader);
        InputStream raw = zip.getRawInputStream(entry);
        if (raw == null) throw new IOException("ZIPX raw entry stream is unavailable");
        try (InputStream owned = raw;
             TraditionalInputStream decrypted = new TraditionalInputStream(
                     raw, entry.getCompressedSize(), checkByte, password, progress)) {
            if (ZipxNativeCodecs.supports(entry.getMethod())) {
                return extractNativeNonAesPayload(decrypted, entry, output, progress, decrypted::finish);
            }
            try (InputStream decoded = wrapCompressedStream(decrypted, entry.getMethod(), entry.getSize(),
                    usesLzmaEndMarker(entry), entry.getRawFlag())) {
                IntegrityInputStream checked = new IntegrityInputStream(decoded, entry.getSize());
                if (!ArchiveSupport.writeArchiveEntryStream(checked, output, progress)) return false;
                decrypted.finish();
                verifyPlainEntry(entry, checked);
                return true;
            }
        }
    }

    private static boolean isPlainStreamEntry(ZipArchiveEntry entry) {
        return !entry.getGeneralPurposeBit().usesEncryption()
                && (entry.getMethod() == METHOD_LZMA || entry.getMethod() == METHOD_XZ
                    || entry.getMethod() == METHOD_BZIP2
                    || (entry.getMethod() == METHOD_PPMD && PpmdZipInputStream.isAvailable())
                    || isZstandardMethod(entry.getMethod()) || isReduceMethod(entry.getMethod()))
                && entry.getSize() >= 0 && entry.getCompressedSize() >= 0 && entry.getCrc() >= 0;
    }

    static boolean isIndependentPpmd(ZipArchiveEntry entry) {
        return entry.getMethod() == METHOD_PPMD && isPlainStreamEntry(entry);
    }

    private static boolean extractPpmdEntry(org.apache.commons.compress.archivers.zip.ZipFile zip,
            ZipArchiveEntry entry, AesMetadata aes, byte[] localHeader, Source source,
            File output, char[] password, FileOperationProgress progress) throws IOException {
        long crc = aes != null && aes.version == AesVersion.TWO ? -1L : entry.getCrc();
        return ZipPpmdEntryExtractor.extract(() -> {
            source.requireUnchanged();
            InputStream raw = zip.getRawInputStream(entry);
            if (raw == null) throw new IOException("ZIPX raw entry stream is unavailable");
            try {
                if (aes != null) {
                    WinZipAesInputStream decrypted = new WinZipAesInputStream(
                            raw, entry.getCompressedSize(), aes, password, progress);
                    return new ZipPpmdEntryExtractor.Payload(decrypted, decrypted::verifyAuthentication);
                }
                if (entry.getGeneralPurposeBit().usesEncryption()) {
                    if (!isTraditionalStreamEntry(entry)) throw new IOException("Unsupported ZIP PPMd-I encryption");
                    TraditionalInputStream decrypted = new TraditionalInputStream(raw, entry.getCompressedSize(),
                            traditionalCheckByte(entry, localHeader), password, progress);
                    return new ZipPpmdEntryExtractor.Payload(decrypted, decrypted::finish);
                }
                return new ZipPpmdEntryExtractor.Payload(raw, () -> {});
            } catch (IOException | RuntimeException | Error failure) {
                try { raw.close(); }
                catch (IOException | RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
        }, source::requireUnchanged, output, entry.getSize(), crc, progress);
    }

    private static boolean isPlainNativeEntry(ZipArchiveEntry entry) {
        return !entry.getGeneralPurposeBit().usesEncryption() && ZipxNativeCodecs.supports(entry.getMethod())
                && entry.getSize() >= 0 && entry.getCompressedSize() >= 0 && entry.getCrc() >= 0;
    }

    private interface PayloadCompletion { void finish() throws IOException; }

    private static boolean extractPlainNativeEntry(org.apache.commons.compress.archivers.zip.ZipFile zip,
            ZipArchiveEntry entry, File output, FileOperationProgress progress) throws IOException {
        InputStream raw = zip.getRawInputStream(entry);
        if (raw == null) throw new IOException("ZIPX raw entry stream is unavailable");
        try (InputStream owned = raw) {
            IntegrityInputStream packed = new IntegrityInputStream(raw);
            return extractNativeNonAesPayload(packed, entry, output, progress, () -> {
                byte[] buffer = new byte[64 * 1024];
                while (true) {
                    if (Thread.currentThread().isInterrupted() || (progress != null && !progress.checkpoint())) {
                        throw new java.io.InterruptedIOException("ZIPX extraction cancelled");
                    }
                    int count = packed.read(buffer);
                    if (count < 0) break;
                    if (count == 0) throw new IOException("Zero-progress ZIPX packed read");
                }
                if (packed.count != entry.getCompressedSize()) throw new IOException("Truncated ZIPX packed payload");
            });
        }
    }

    /** Caller owns the stream and destination guard; close/count/CRC checks precede its commit. */
    private static boolean extractNativeNonAesPayload(InputStream input, ZipArchiveEntry entry,
            File output, FileOperationProgress progress, PayloadCompletion completion) throws IOException {
        IntegrityOutputStream checked;
        try (OutputStream fileOut = ArchiveSupport.openExtractionOutputStream(output)) {
            checked = new IntegrityOutputStream(fileOut, progress, entry.getSize());
            long count = ZipxNativeCodecs.decode(entry.getMethod(), input, checked, entry.getSize());
            checked.flush();
            if (count != checked.count) throw new IOException("ZIPX native decoder byte count mismatch");
        } catch (ExtractionCancelledException cancelled) { return false; }
        completion.finish();
        verifyPlainEntry(entry, checked.count, checked.crc.getValue());
        return true;
    }

    private static int traditionalCheckByte(ZipArchiveEntry entry, byte[] localHeader) {
        if ((entry.getRawFlag() & 8) == 0) return (int) (entry.getCrc() >>> 24) & 255;
        return localHeader[11] & 255;
    }

    private static final class TraditionalInputStream extends InputStream {
        private final InputStream raw;
        private final net.lingala.zip4j.crypto.StandardDecrypter decrypter;
        private final FileOperationProgress progress;
        private final byte[] one = new byte[1];
        private long remaining;
        private boolean closed;
        TraditionalInputStream(InputStream raw, long storedSize, int checkByte, char[] password,
                FileOperationProgress progress) throws IOException {
            this.raw = raw; this.progress = progress;
            checkpoint();
            if (password == null || password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
            if (storedSize < 12) throw new IOException("Truncated ZIP traditional encryption header");
            byte[] header = new byte[12]; readFully(raw, header);
            try {
                // Supply the same selected byte in both API slots; do not accept the alternate field.
                decrypter = new net.lingala.zip4j.crypto.StandardDecrypter(password,
                        (long) checkByte << 24, (long) checkByte << 8, header, true);
            } catch (ZipException failure) {
                throw new IOException("ZIPX traditional password verification failed", failure);
            }
            remaining = storedSize - 12;
        }
        private void checkpoint() throws IOException {
            if (Thread.currentThread().isInterrupted() || (progress != null && !progress.checkpoint())) {
                throw new java.io.InterruptedIOException("ZIPX extraction cancelled");
            }
        }
        @Override public int read() throws IOException {
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] data, int offset, int length) throws IOException {
            if (data == null) throw new NullPointerException("data");
            if ((offset | length) < 0 || length > data.length - offset) throw new IndexOutOfBoundsException();
            if (closed) throw new IOException("ZIP traditional stream is closed");
            if (length == 0) return 0;
            checkpoint();
            if (remaining == 0) return -1;
            int requested = (int) Math.min(length, remaining);
            int count = raw.read(data, offset, requested);
            if (count <= 0 || count > requested) throw new IOException("Truncated or invalid ZIP traditional payload read");
            decrypter.decryptData(data, offset, count);
            remaining -= count;
            return count;
        }
        void finish() throws IOException {
            checkpoint();
            if (remaining == 0) return; // Some codec wrappers close their input at physical EOF.
            byte[] buffer = new byte[64 * 1024];
            while (read(buffer) >= 0) { }
        }
        @Override public void close() throws IOException { if (!closed) { closed = true; raw.close(); } }
    }

    /** Shared by ZIPX companions and the ordinary Commons ZIP fallback. */
    static boolean extractPlainEntry(File archive,
            org.apache.commons.compress.archivers.zip.ZipFile zip, ZipArchiveEntry entry,
            File outFile, FileOperationProgress progress) throws IOException {
        if (!isIndependentPpmd(entry)) return extractPlainEntry(zip, entry, outFile, progress);
        try {
            Source source = Source.capture(archive);
            if (source.contains(outFile.getCanonicalPath())) throw new IOException("Output is a ZIP source volume");
            try (SplitVolumeInput headers = source.openHeaderInput();
                 RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
                byte[] localHeader = source.localHeader(entry, headers);
                if (!extractPpmdEntry(zip, entry, null, localHeader, source, outFile, null, progress)) return false;
                source.requireSameVolumes();
                guard.commit();
                return true;
            }
        } catch (ZipPpmdEntryExtractor.ExtractionException failure) { throw failure; }
        catch (IOException | SecurityException failure) {
            ArchiveCancellation.rethrowIfCancelled(failure, progress);
            throw new ZipPpmdEntryExtractor.ExtractionException(failure);
        }
    }

    static boolean extractPlainEntry(
            org.apache.commons.compress.archivers.zip.ZipFile zip, ZipArchiveEntry entry,
            File outFile, FileOperationProgress progress) throws IOException {
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
            if (isPlainStreamEntry(entry)) {
                if (!extractPlainStreamPayload(zip, entry, outFile, progress)) return false;
            } else {
                try (InputStream in = zip.getInputStream(entry)) {
                    IntegrityInputStream checked = new IntegrityInputStream(in, entry.getSize());
                    if (!ArchiveSupport.writeArchiveEntryStream(checked, outFile, progress)) return false;
                    verifyPlainEntry(entry, checked);
                }
            }
            guard.commit();
            return true;
        }
    }

    /** Both callers own the destination guard; decoder close must finish before commit. */
    private static boolean extractPlainStreamPayload(
            org.apache.commons.compress.archivers.zip.ZipFile zip, ZipArchiveEntry entry,
            File outFile, FileOperationProgress progress) throws IOException {
        InputStream raw = zip.getRawInputStream(entry);
        if (raw == null) throw new IOException("ZIPX raw entry stream is unavailable");
        try (InputStream rawEntry = raw;
             InputStream decoded = wrapCompressedStream(
                     rawEntry, entry.getMethod(), entry.getSize(), usesLzmaEndMarker(entry), entry.getRawFlag())) {
            IntegrityInputStream checked = new IntegrityInputStream(decoded, entry.getSize());
            if (!ArchiveSupport.writeArchiveEntryStream(checked, outFile, progress)) return false;
            verifyPlainEntry(entry, checked);
            return true;
        }
    }

    private static boolean extractNativeAesEntry(@NonNull InputStream raw,
                                                 @NonNull ZipArchiveEntry entry,
                                                 @NonNull AesMetadata aes,
                                                 @NonNull File outFile,
                                                 @NonNull char[] password,
                                                 @Nullable FileOperationProgress progress)
            throws IOException {
        // The codec is bounded by declared size, not an application-wide file cap.
        // The guarded output stream independently enforces available-space accounting.
        long outputLimit = entry.getSize() >= 0L ? entry.getSize() : Long.MAX_VALUE;
        try (InputStream ownedRaw = raw;
             WinZipAesInputStream decrypted = new WinZipAesInputStream(
                     raw, entry.getCompressedSize(), aes, password, progress);
             RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
            IntegrityOutputStream checked;
            try (OutputStream fileOut = ArchiveSupport.openExtractionOutputStream(outFile)) {
                checked = new IntegrityOutputStream(fileOut, progress, outputLimit);
                long decoded = ZipxNativeCodecs.decode(
                        aes.compressionMethod, decrypted, checked, outputLimit);
                checked.flush();
                if (decoded != checked.count) {
                    throw new IOException("ZIPX native decoder byte count mismatch");
                }
            } catch (ExtractionCancelledException cancelled) {
                return false;
            }
            // Authentication and entry integrity are checked before the guarded output commits.
            decrypted.verifyAuthentication();
            verifyDecodedEntry(entry, aes, checked.count, checked.crc.getValue());
            guard.commit();
            return true;
        }
    }

    @NonNull
    private static InputStream wrapCompressedStream(@NonNull InputStream decrypted,
                                                    int method,
                                                    long uncompressedSize,
                                                    boolean lzmaEndMarker)
            throws IOException {
        return wrapCompressedStream(decrypted, method, uncompressedSize, lzmaEndMarker, 0);
    }

    private static InputStream wrapCompressedStream(InputStream decrypted, int method,
            long uncompressedSize, boolean lzmaEndMarker, int rawFlags) throws IOException {
        switch (method) {
            case 2:
            case 3:
            case 4:
            case 5:
                return new ZipReduceInputStream(new java.io.BufferedInputStream(decrypted, 64 * 1024), method, uncompressedSize);
            case METHOD_SHRINK:
            case METHOD_IMPLODE:
                return org.apache.commons.compress.archivers.zip.ReadwideZipLegacyStreams.open(
                        method, rawFlags, new java.io.BufferedInputStream(decrypted, 64 * 1024));
            case METHOD_STORED:
                return decrypted;
            case METHOD_DEFLATE:
                // Raw ZIP deflate streams may require one trailing zero byte.
                return new OwnedDeflateInputStream(
                        new SequenceInputStream(
                                decrypted, new ByteArrayInputStream(new byte[] {0})));
            case METHOD_DEFLATE64:
                return new Deflate64CompressorInputStream(decrypted);
            case METHOD_BZIP2:
                return new ArchiveBzip2InputStream(decrypted, null);
            case METHOD_LZMA:
                return wrapZipLzmaStream(decrypted, uncompressedSize, lzmaEndMarker);
            case METHOD_PPMD:
                return PpmdZipInputStream.open(decrypted, uncompressedSize);
            case METHOD_XZ:
                // XZ permits concatenated streams and four-byte-aligned padding.
                // The ZIP payload/decryptor bounds input; outer size/CRC/MAC
                // checks still cover the complete concatenated output.
                return XZCompressorInputStream.builder().setInputStream(decrypted)
                        .setDecompressConcatenated(true).get();
            case METHOD_ZSTANDARD:
            case METHOD_ZSTANDARD_LEGACY:
                return LibarchiveZstdInputStream.open(decrypted);
            default:
                throw unsupportedMethod(method);
        }
    }

    @NonNull
    private static InputStream wrapZipLzmaStream(@NonNull InputStream input,
                                                long uncompressedSize,
                                                boolean endMarker) throws IOException {
        if (uncompressedSize < 0L) {
            throw new IOException("Corrupt ZIPX LZMA entry has no decoded size");
        }
        byte[] header = new byte[4];
        readFully(input, header);
        int propertySize = readUnsignedShort(header, 2);
        if (propertySize != ZIP_LZMA_PROPERTIES_SIZE) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                    "ZIPX LZMA property size " + propertySize + " is unsupported");
        }
        byte[] properties = new byte[ZIP_LZMA_PROPERTIES_SIZE];
        readFully(input, properties);
        int dictionarySize = lzmaDictionarySize(properties, uncompressedSize);
        try {
            return new LZMAInputStream(
                    input, endMarker ? -1L : uncompressedSize, properties[0], dictionarySize);
        } catch (OutOfMemoryError error) {
            throw new IOException("Not enough memory for the ZIPX LZMA dictionary", error);
        }
    }

    /** Validate LZMA properties without allocating its dictionary or imposing a RAM quota. */
    static int lzmaDictionarySize(@NonNull byte[] properties, long uncompressedSize) throws IOException {
        if (properties.length != ZIP_LZMA_PROPERTIES_SIZE || uncompressedSize < 0L) {
            throw new IOException("Invalid ZIPX LZMA properties or decoded size");
        }
        long declaredDictionary = (properties[1] & 0xffL)
                | ((properties[2] & 0xffL) << 8)
                | ((properties[3] & 0xffL) << 16)
                | ((properties[4] & 0xffL) << 24);
        // Independent ZIP LZMA has no preset history; total output bounds useful history.
        int dictionarySize = SevenZCoderRegistry.boundedDictionary(
                declaredDictionary, uncompressedSize, LZMAInputStream.DICT_SIZE_MAX);
        LZMAInputStream.getMemoryUsage(dictionarySize, properties[0]); // Validate lc/lp/pb.
        return dictionarySize;
    }

    private static boolean usesLzmaEndMarker(@NonNull ZipArchiveEntry entry) {
        // For ZIP method 14, general-purpose bit 1 means that an LZMA EOS
        // marker is present. GeneralPurposeBit treats the same bit as legacy
        // implode metadata and doesn't preserve it from encode(), so use the
        // original ZIP flag word retained by ZipArchiveEntry.
        return (entry.getRawFlag() & 0x0002) != 0;
    }

    private static void verifyPlainEntry(@NonNull ZipArchiveEntry entry,
                                         @NonNull IntegrityInputStream checked) throws IOException {
        verifyPlainEntry(entry, checked.count, checked.crc.getValue());
    }

    private static void verifyPlainEntry(ZipArchiveEntry entry, long count, long crc) throws IOException {
        long expectedSize = entry.getSize();
        if (expectedSize >= 0L && expectedSize != count) {
            throw new IOException("Corrupt ZIPX decoded size mismatch");
        }
        long expectedCrc = entry.getCrc();
        if (expectedCrc >= 0L && expectedCrc != crc) {
            throw new IOException("ZIPX CRC mismatch");
        }
    }

    private static void verifyDecodedEntry(@NonNull ZipArchiveEntry entry,
                                           @NonNull AesMetadata aes,
                                           @NonNull IntegrityInputStream checked) throws IOException {
        verifyDecodedEntry(entry, aes, checked.count, checked.crc.getValue());
    }

    private static void verifyDecodedEntry(@NonNull ZipArchiveEntry entry,
                                           @NonNull AesMetadata aes,
                                           long decodedCount,
                                           long decodedCrc) throws IOException {
        long expectedSize = entry.getSize();
        if (expectedSize >= 0L && expectedSize != decodedCount) {
            throw new IOException("Corrupt ZIPX decoded size mismatch");
        }
        // AE-1 retains the ZIP CRC. AE-2 uses zero and relies on HMAC-SHA1-80.
        if (aes.version == AesVersion.ONE) {
            long expectedCrc = entry.getCrc();
            if (expectedCrc >= 0L && expectedCrc != decodedCrc) {
                throw new IOException("ZIPX CRC mismatch");
            }
        }
    }

    @Nullable
    private static AesMetadata parseAesMetadata(@NonNull ZipArchiveEntry entry) {
        if (entry.getMethod() != METHOD_WINZIP_AES
                || !entry.getGeneralPurposeBit().usesEncryption()) {
            return null;
        }
        AesMetadata central = parseAesExtra(entry.getCentralDirectoryExtra());
        return central != null ? central : parseAesExtra(entry.getLocalFileDataExtra());
    }

    @Nullable
    private static AesMetadata parseAesExtra(@Nullable byte[] extra) {
        if (extra == null) return null;
        int offset = 0;
        while (offset + 4 <= extra.length) {
            int headerId = readUnsignedShort(extra, offset);
            int dataSize = readUnsignedShort(extra, offset + 2);
            int dataOffset = offset + 4;
            int next = dataOffset + dataSize;
            if (next < dataOffset || next > extra.length) return null;
            if (headerId == WINZIP_AES_EXTRA_ID) {
                if (dataSize < 7 || extra[dataOffset + 2] != 'A' || extra[dataOffset + 3] != 'E') {
                    return null;
                }
                int versionNumber = readUnsignedShort(extra, dataOffset);
                AesVersion version;
                if (versionNumber == 1) version = AesVersion.ONE;
                else if (versionNumber == 2) version = AesVersion.TWO;
                else return null;
                int strengthCode = extra[dataOffset + 4] & 0xff;
                AesKeyStrength strength;
                try {
                    strength = AesKeyStrength.getAesKeyStrengthFromRawCode(strengthCode);
                } catch (IllegalArgumentException e) {
                    return null;
                }
                if (strength == null) return null;
                int method = readUnsignedShort(extra, dataOffset + 5);
                return new AesMetadata(version, strength, method);
            }
            offset = next;
        }
        return null;
    }

    private static int readUnsignedShort(@NonNull byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static boolean isZstandardMethod(int method) {
        return method == METHOD_ZSTANDARD || method == METHOD_ZSTANDARD_LEGACY;
    }

    private static boolean isReduceMethod(int method) { return method >= 2 && method <= 5; }

    private static boolean isExtendedMethod(int method) {
        return isReduceMethod(method) || method == METHOD_SHRINK || method == METHOD_IMPLODE
                || method == METHOD_DEFLATE64 || method == METHOD_BZIP2
                || method == METHOD_LZMA || method == METHOD_XZ
                || (method == METHOD_PPMD && PpmdZipInputStream.isAvailable())
                || method == METHOD_JPEG || method == METHOD_WAVPACK || isZstandardMethod(method);
    }

    private static boolean isSupportedAesMethod(int method) {
        return method == METHOD_STORED || method == METHOD_DEFLATE || isExtendedMethod(method);
    }

    private static boolean isLibarchiveAesMethod(int method) {
        return method == METHOD_BZIP2 || method == METHOD_LZMA
                || method == METHOD_PPMD || method == METHOD_XZ
                || method == METHOD_ZSTANDARD;
    }

    private static boolean isPlainCommonsExtendedEntry(ZipArchiveEntry entry) {
        int method = entry.getMethod();
        return !entry.getGeneralPurposeBit().usesEncryption()
                && (method == METHOD_SHRINK || method == METHOD_IMPLODE
                || method == METHOD_DEFLATE64 || method == METHOD_BZIP2);
    }

    private static boolean isRuntimeSupportedPlainMethod(int method) {
        // Commons implements these without an optional Zstandard runtime.
        return method == 0 || method == 1 || method == 6 || method == 8
                || method == 9 || method == 12 || method == 95;
    }

    @NonNull
    private static ArchiveSupport.UnsupportedArchiveFeatureException unsupportedMethod(int method) {
        return new ArchiveSupport.UnsupportedArchiveFeatureException(
                "ZIPX AES compression method " + method + " is unsupported");
    }

    private static long sumDecodedSizes(@NonNull List<EntryDescriptor> entries) {
        long total = 0L;
        for (EntryDescriptor descriptor : entries) {
            long size = descriptor.entry.getSize();
            if (size <= 0L) continue;
            if (Long.MAX_VALUE - total < size) return Long.MAX_VALUE;
            total += size;
        }
        return total;
    }

    /** ZIPX-owned volume identity; no plaintext, password, or content hash retained. */
    private static final class Source {
        final File selected;
        final List<File> files;
        final List<SplitVolumeInput.Segment> segments;
        final long[] lengths, modified, starts;

        private Source(File selected, List<File> resolved) throws IOException {
            this.selected = selected.getCanonicalFile();
            files = new ArrayList<>(resolved.size());
            segments = new ArrayList<>(resolved.size());
            lengths = new long[resolved.size()]; modified = new long[resolved.size()];
            starts = new long[resolved.size()];
            long total = 0;
            for (int i = 0; i < resolved.size(); i++) {
                checkpoint();
                File file = resolved.get(i).getCanonicalFile();
                if (!file.isFile() || !file.canRead()) throw new IOException("ZIPX volume unavailable");
                files.add(file); lengths[i] = file.length(); modified[i] = file.lastModified();
                if (lengths[i] < 0 || lengths[i] > Long.MAX_VALUE - total) throw new IOException("ZIPX volume set too large");
                starts[i] = total;
                segments.add(new SplitVolumeInput.Segment(file, 0, lengths[i]));
                total += lengths[i];
            }
            if (files.isEmpty()) throw new IOException("Empty ZIPX volume set");
        }
        static void checkpoint() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("ZIPX operation cancelled");
        }
        static Source capture(File selected) throws IOException {
            checkpoint();
            return new Source(selected, ZipVolumeResolver.isCandidate(selected.getName())
                    ? ZipVolumeResolver.forSnapshot(selected) : java.util.Collections.singletonList(selected));
        }
        boolean sameVolumes(Source other) {
            return files.equals(other.files) && java.util.Arrays.equals(lengths, other.lengths)
                    && java.util.Arrays.equals(modified, other.modified);
        }
        boolean contains(String path) {
            for (File file : files) if (file.getPath().equals(path)) return true;
            return false;
        }
        void requireUnchanged() throws IOException {
            // Cheap checks around each payload; no sibling discovery or ZIP tail parsing.
            for (int i = 0; i < files.size(); i++) {
                checkpoint();
                File file = files.get(i);
                if (!file.isFile() || !file.canRead() || file.length() != lengths[i]
                        || file.lastModified() != modified[i]) {
                    throw new IOException("ZIPX volumes changed during extraction");
                }
            }
        }
        void requireSameVolumes() throws IOException {
            // Full discovery at acquisition/commit boundaries also detects added parts.
            if (!sameVolumes(capture(selected))) throw new IOException("ZIPX volumes changed during extraction");
        }
        SplitVolumeInput openHeaderInput() throws IOException {
            requireUnchanged();
            return new SplitVolumeInput(segments);
        }
        org.apache.commons.compress.archivers.zip.ZipFile openZip() throws IOException {
            try {
                return openZip(false); // Preserve local-only name aliases for ordinary valid archives.
            } catch (java.io.InterruptedIOException cancelled) {
                throw cancelled;
            } catch (IOException eagerFailure) {
                checkpoint();
                // An unrelated damaged local header is not a selected-entry dependency.
                // The central directory must still parse; the selected local header,
                // payload bounds, checksum and authentication are validated on extraction.
                try { return openZip(true); }
                catch (IOException metadataFailure) {
                    metadataFailure.addSuppressed(eagerFailure);
                    throw metadataFailure;
                }
            }
        }
        private org.apache.commons.compress.archivers.zip.ZipFile openZip(boolean metadataOnly) throws IOException {
            checkpoint();
            java.nio.channels.SeekableByteChannel channel = files.size() == 1
                    ? java.nio.channels.FileChannel.open(files.get(0).toPath(), java.nio.file.StandardOpenOption.READ)
                    : ZipVolumeChannels.open(files, lengths);
            org.apache.commons.compress.archivers.zip.ZipFile zip = null;
            try {
                zip = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                        .setSeekableByteChannel(channel).setIgnoreLocalFileHeader(metadataOnly).get();
                requireUnchanged();
                return zip; // ZipFile owns the single file or bounded split-volume channel.
            } catch (IOException | RuntimeException | Error failure) {
                if (zip != null) {
                    try { zip.close(); } catch (IOException close) { failure.addSuppressed(close); }
                }
                try { channel.close(); } catch (IOException close) { failure.addSuppressed(close); }
                throw failure;
            }
        }
        byte[] localHeader(ZipArchiveEntry entry, SplitVolumeInput input) throws IOException {
            long disk = entry.getDiskNumberStart(), offset = entry.getLocalHeaderOffset();
            if (disk < 0 || disk >= files.size() || offset < 0 || offset >= lengths[(int) disk]) {
                throw new IOException("Invalid ZIPX local header disk or offset");
            }
            long absolute = starts[(int) disk] + offset; // Total was checked during capture.
            byte[] header = new byte[30];
            // Owned by the bulk operation or selected-index lease; keep one bounded
            // header handle instead of opening every physical volume for every page.
            synchronized (input) {
                if (absolute > input.length() - header.length) throw new IOException("Truncated ZIPX local header");
                input.seek(absolute); input.readFully(header);
                if (entry.getMethod() == METHOD_WINZIP_AES) {
                    int nameLength = readUnsignedShort(header, 26);
                    int extraLength = readUnsignedShort(header, 28);
                    long extraStart = input.getFilePointer();
                    if (nameLength > input.length() - extraStart) throw new IOException("Truncated ZIPX local filename");
                    input.seek(extraStart + nameLength);
                    byte[] extra = new byte[extraLength];
                    input.readFully(extra);
                    AesMetadata local = parseAesExtra(extra);
                    AesMetadata central = parseAesExtra(entry.getCentralDirectoryExtra());
                    if (local == null || central == null || local.version != central.version
                            || local.strength != central.strength || local.compressionMethod != central.compressionMethod) {
                        throw new IOException("Inconsistent ZIPX local AES metadata");
                    }
                }
            }
            if (header[0] != 'P' || header[1] != 'K' || header[2] != 3 || header[3] != 4
                    || (readUnsignedShort(header, 6) & 0x204f) != (entry.getRawFlag() & 0x204f)
                    || readUnsignedShort(header, 8) != entry.getMethod()) {
                throw new IOException("Inconsistent ZIPX local compression/encryption header");
            }
            return header;
        }
    }

    private static final class EntryDescriptor {
        @NonNull final ZipArchiveEntry entry;
        @NonNull final String displayName;
        @NonNull final String legacyName;
        @Nullable final AesMetadata aes;
        @NonNull final Source source;

        EntryDescriptor(@NonNull ZipArchiveEntry entry,
                        @NonNull String displayName, @NonNull String legacyName,
                        @Nullable AesMetadata aes, Source source) {
            this.entry = entry;
            this.displayName = displayName;
            this.legacyName = legacyName;
            this.aes = aes;
            this.source = source;
        }
    }

    private static final class AesMetadata {
        @NonNull final AesVersion version;
        @NonNull final AesKeyStrength strength;
        final int compressionMethod;

        AesMetadata(@NonNull AesVersion version,
                    @NonNull AesKeyStrength strength,
                    int compressionMethod) {
            this.version = version;
            this.strength = strength;
            this.compressionMethod = compressionMethod;
        }

        @NonNull
        AESExtraDataRecord toZip4jRecord() {
            AESExtraDataRecord record = new AESExtraDataRecord();
            record.setDataSize(7);
            record.setAesVersion(version);
            record.setVendorID("AE");
            record.setAesKeyStrength(strength);
            return record;
        }
    }

    /** Bounded AES payload stream; authentication bytes never reach the decoder. */
    private static final class WinZipAesInputStream extends InputStream {
        @NonNull private final InputStream raw;
        @NonNull private final AESDecrypter decrypter;
        private long ciphertextRemaining;
        // Zip4j accepts multiple CTR blocks per call; only the final batch may
        // be short. Avoid a raw read/decrypt/copy cycle for every 16 output bytes.
        private final byte[] decryptedBlock = new byte[64 * 1024];
        private final byte[] single = new byte[1];
        private int blockOffset;
        private int blockLength;
        private boolean authenticated;
        private boolean closed;
        private IOException failure;
        private final FileOperationProgress progress;

        WinZipAesInputStream(@NonNull InputStream raw,
                            long storedSize,
                            @NonNull AesMetadata aes,
                            @NonNull char[] password, FileOperationProgress progress) throws IOException {
            this.progress = progress;
            checkpoint();
            if (password == null || password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
            this.raw = raw;
            int saltLength = aes.strength.getSaltLength();
            long overhead = saltLength + 2L + AES_AUTHENTICATION_LENGTH;
            if (storedSize < overhead) throw new IOException("Truncated ZIPX AES entry");
            byte[] salt = new byte[saltLength];
            byte[] verifier = new byte[2];
            readFully(raw, salt);
            readFully(raw, verifier);
            try {
                this.decrypter = new AESDecrypter(
                        aes.toZip4jRecord(), password, salt, verifier, true);
            } catch (ZipException e) {
                throw new IOException("ZIPX AES password verification failed", e);
            }
            this.ciphertextRemaining = storedSize - overhead;
        }

        @Override
        public int read() throws IOException {
            int read = read(single, 0, 1);
            return read < 0 ? -1 : single[0] & 0xff;
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            if (closed) throw new IOException("ZIPX AES stream is closed");
            if (buffer == null) throw new NullPointerException("buffer");
            if (offset < 0 || length < 0 || length > buffer.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            if (failure != null) throw failure;
            if (length == 0) return 0;
            try {
                checkpoint();
                if (blockOffset >= blockLength && !readNextBlock()) {
                    verifyAuthentication();
                    return -1;
                }
                int copied = Math.min(length, blockLength - blockOffset);
                System.arraycopy(decryptedBlock, blockOffset, buffer, offset, copied);
                blockOffset += copied;
                return copied;
            } catch (IOException error) { failure = error; throw error; }
        }

        private void checkpoint() throws IOException {
            if (Thread.currentThread().isInterrupted() || (progress != null && !progress.checkpoint())) {
                throw new java.io.InterruptedIOException("ZIPX AES extraction cancelled");
            }
        }

        private boolean readNextBlock() throws IOException {
            checkpoint();
            if (ciphertextRemaining <= 0L) return false;
            int size = (int) Math.min((long) decryptedBlock.length, ciphertextRemaining);
            readFully(raw, decryptedBlock, 0, size);
            try {
                decrypter.decryptData(decryptedBlock, 0, size);
            } catch (ZipException e) {
                throw new IOException("ZIPX AES decryption failed", e);
            }
            ciphertextRemaining -= size;
            blockOffset = 0;
            blockLength = size;
            return true;
        }

        void verifyAuthentication() throws IOException {
            if (failure != null) throw failure;
            try { verifyAuthenticationBody(); }
            catch (IOException error) { failure = error; throw error; }
        }

        private void verifyAuthenticationBody() throws IOException {
            checkpoint();
            if (authenticated) return;
            while (readNextBlock()) {
                blockOffset = blockLength;
            }
            byte[] expected = new byte[AES_AUTHENTICATION_LENGTH];
            readFully(raw, expected);
            byte[] calculated = decrypter.getCalculatedAuthenticationBytes(0);
            byte[] actual = new byte[AES_AUTHENTICATION_LENGTH];
            if (calculated.length < actual.length) {
                throw new IOException("ZIPX AES authentication failed");
            }
            System.arraycopy(calculated, 0, actual, 0, actual.length);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new IOException("ZIPX AES authentication failed");
            }
            authenticated = true;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { raw.close(); }
            finally {
                java.util.Arrays.fill(decryptedBlock, (byte) 0);
                single[0] = 0;
                blockOffset = blockLength = 0;
            }
        }
    }

    private static final class IntegrityInputStream extends InputStream {
        @NonNull private final InputStream in;
        @NonNull final CRC32 crc = new CRC32();
        long count;
        private final long limit;
        private IOException failure;
        private final byte[] single = new byte[1];

        IntegrityInputStream(@NonNull InputStream in) { this(in, -1); }
        IntegrityInputStream(@NonNull InputStream in, long expectedSize) {
            this.in = in;
            this.limit = expectedSize < 0 ? Long.MAX_VALUE : expectedSize;
        }

        @Override
        public int read() throws IOException {
            return read(single, 0, 1) < 0 ? -1 : single[0] & 255;
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            if (buffer == null) throw new NullPointerException("buffer");
            if ((offset | length) < 0 || length > buffer.length - offset) throw new IndexOutOfBoundsException();
            if (failure != null) throw failure;
            if (length == 0) return 0;
            try {
                long remaining = limit - count;
                int requested = remaining >= length ? length : (int) remaining + 1;
                int read = in.read(buffer, offset, requested);
                if (read == 0 || read < -1 || read > requested) throw new IOException("Invalid or zero-progress ZIPX decoded read");
                if (read > remaining) throw new IOException("ZIPX decoded output exceeds declared size");
                if (read > 0) {
                    crc.update(buffer, offset, read);
                    count += read;
                }
                return read;
            } catch (IOException error) { failure = error; throw error; }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static final class IntegrityOutputStream extends OutputStream {
        @NonNull private final OutputStream out;
        @Nullable private final FileOperationProgress progress;
        @NonNull final CRC32 crc = new CRC32();
        private final long outputLimit;
        long count;

        IntegrityOutputStream(@NonNull OutputStream out,
                              @Nullable FileOperationProgress progress,
                              long outputLimit) {
            this.out = out;
            this.progress = progress;
            this.outputLimit = outputLimit;
        }

        @Override
        public void write(int value) throws IOException {
            byte[] one = {(byte) value};
            write(one, 0, 1);
        }

        @Override
        public void write(@NonNull byte[] buffer, int offset, int length) throws IOException {
            if (progress != null && !progress.checkpoint()) {
                throw new ExtractionCancelledException();
            }
            if (length < 0 || count > outputLimit - length) {
                throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                        "ZIPX decoded output exceeds the extraction safety limit");
            }
            out.write(buffer, offset, length);
            crc.update(buffer, offset, length);
            count += length;
            if (progress != null) progress.addDoneBytes(length);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }
    }

    private static final class ExtractionCancelledException extends IOException {
        ExtractionCancelledException() {
            super("ZIPX extraction cancelled");
        }
    }

    private static void readFully(@NonNull InputStream in, @NonNull byte[] buffer) throws IOException {
        readFully(in, buffer, 0, buffer.length);
    }

    private static void readFully(@NonNull InputStream in,
                                  @NonNull byte[] buffer,
                                  int offset,
                                  int length) throws IOException {
        int done = 0;
        while (done < length) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("ZIPX read cancelled");
            int read = in.read(buffer, offset + done, length - done);
            if (read < 0) throw new IOException("Truncated ZIPX AES entry");
            if (read == 0 || read > length - done) throw new IOException("Invalid or zero-progress ZIPX read");
            done += read;
        }
    }
}
