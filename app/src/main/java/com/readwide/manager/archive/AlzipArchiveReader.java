package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

import com.readwide.manager.util.FileOperationProgress;

/**
 * Read-only decoder for the ESTsoft ALZ archive container (the EGG family
 * member is handled by {@link EggArchiveReader}).
 *
 * <p>The container layout is implemented as first-party Java code from public
 * ALZ container concepts and empirical verification against real ALZip-created
 * archives (store, deflate, bzip2, ZipCrypto-encrypted, CP949 file names, and
 * two-segment split archives). Store, raw-deflate, and bzip2 payloads are
 * decoded with CRC32 verification; ZipCrypto entries decrypt with the classic
 * PKWARE keystream after the 12-byte check-data verification. Method-1
 * (bzip2) payloads come in two flavors across ALZip versions: plain bzip2,
 * and the ALZip 4.x trimmed bitstream variant (no stream/block magics or
 * per-block CRC fields) decoded by {@link AlzBzip2InputStream}; the first
 * bytes select the decoder.</p>
 *
 * <p>Split archives ({@code name.alz} + {@code name.a00} ... {@code name.a99},
 * {@code name.b00} ... {@code name.j99}, or decimal {@code name.a100} aliases)
 * are a byte-level cut of one logical archive with per-segment framing:
 * every segment of a split set ends with a 16-byte trailer, and each
 * continuation segment that starts with the ALZ signature carries an 8-byte
 * segment header before its payload bytes. Intermediate framing is removed;
 * the final trailer is retained for container completion checks. The segments are
 * presented as one logical stream through {@link SplitVolumeInput}, so entry
 * data that straddles a segment boundary decodes normally. A missing segment
 * fails cleanly with no partial output.</p>
 */
final class AlzipArchiveReader {
    private static final int SIG_ALZ_FILE_HEADER = 0x015a4c41;
    private static final int SIG_LOCAL_FILE_HEADER = 0x015a4c42;
    private static final int SIG_CENTRAL_DIRECTORY = 0x015a4c43;
    private static final int SIG_END_CENTRAL_DIRECTORY = 0x025a4c43;
    private static final int SIG_SPLIT_MARKER = 0x035a4c43;
    private static final int SIG_COMMENT = 0x015a4c45;
    private static final int ALZ_FILEATTR_DIRECTORY = 0x10;
    private static final int ALZ_DESCRIPTOR_ENCRYPTED = 0x01;
    private static final int ALZ_DESCRIPTOR_DATA_DESCRIPTOR = 0x08;
    private static final int COMP_STORED = 0;
    private static final int COMP_BZIP2 = 1;
    private static final int COMP_DEFLATE = 2;
    private static final int COMP_OBFUSCATED_DEFLATE = 3;
    private static final int BUFFER_SIZE = 64 * 1024;
    // Admission limits for metadata retention, never archive or extracted-file size limits.
    private static final int MAX_INDEX_ARCHIVES = 3;
    private static final int MAX_INDEX_ENTRIES = 20000;
    private static final long MAX_INDEX_NAME_CHARS = 1024 * 1024;
    private static final Map<String, Index> INDEXES = new LinkedHashMap<>(4, 0.75f, true);

    private AlzipArchiveReader() {
    }

    enum Family {
        ALZ,
        EGG,
        UNKNOWN
    }

    @NonNull
    static List<ArchiveSupport.EntryInfo> listEntries(@NonNull File archive,
                                                      @Nullable char[] password) throws IOException {
        if (detectFamily(archive) == Family.EGG) {
            return EggArchiveReader.listEntries(archive, password);
        }
        List<AlzEntry> entries = indexFor(archive).entries;
        ArrayList<ArchiveSupport.EntryInfo> result = new ArrayList<>();
        for (AlzEntry entry : entries) {
            if (sanitizeEntryPath(entry.path) == null) continue;
            result.add(new ArchiveSupport.EntryInfo(entry.path, entry.directory, entry.uncompressedSize, entry.timeMillis));
        }
        return ArchiveSupport.withFirstFileEntryIdentities(result);
    }

    static boolean extractArchiveIntoDirectory(@NonNull File archive,
                                               @NonNull File targetDir,
                                               @Nullable char[] password) throws IOException {
        return extractArchiveIntoDirectory(archive, targetDir, password, null);
    }

    static boolean extractArchiveIntoDirectory(@NonNull File archive,
                                               @NonNull File targetDir,
                                               @Nullable char[] password,
                                               @Nullable FileOperationProgress progress) throws IOException {
        return extractArchiveIntoDirectory(archive, targetDir, password, progress, null);
    }

    static boolean extractArchiveIntoDirectory(@NonNull File archive,
                                               @NonNull File targetDir,
                                               @Nullable char[] password,
                                               @Nullable FileOperationProgress progress,
                                               @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        if (detectFamily(archive) == Family.EGG) {
            return EggArchiveReader.extractArchiveIntoDirectory(archive, targetDir, password, progress, entryProgress);
        }
        AlzVolumeResolver.VolumeSet volumes = AlzVolumeResolver.resolve(archive);
        try (SplitVolumeInput in = new SplitVolumeInput(volumes.segments)) {
            List<AlzEntry> entries = readAlzEntries(archive, in, volumes.retainedFinalTrailer);
            in.requireUnchanged();
            ArchiveOutputPreflight preflight = new ArchiveOutputPreflight(targetDir);
            for (AlzEntry entry : entries) preflight.add(entry.path, entry.directory);
            if (progress != null) progress.setTotalBytes(sumUncompressedBytes(entries));
            indexCheckpoint();
            if (progress != null && !progress.checkpoint()) throw new java.io.InterruptedIOException("ALZ extraction cancelled");
            for (AlzEntry entry : entries) {
                indexCheckpoint();
                if (progress != null && !progress.checkpoint()) throw new java.io.InterruptedIOException("ALZ extraction cancelled");
                if (entryProgress != null) {
                    if (entry.directory || entry.path.endsWith("/")) entryProgress.onDirectory(entry.path);
                    else entryProgress.onFile(entry.path);
                } else if (progress != null) progress.setDetail(entry.path);
                ArchiveCancellation.checkpoint(progress);
                in.requireUnchanged();
                File out = resolveOutput(targetDir, entry.path);
                if (out == null) return false;
                if (entry.directory || entry.path.endsWith("/")) {
                    if (!out.isDirectory() && !out.mkdirs() && !out.isDirectory()) return false;
                    continue;
                }
                extractEntryPayload(in, entry, out, password, progress);
            }
            in.requireUnchanged();
            return true;
        }
    }

    static boolean extractSingleEntry(@NonNull File archive,
                                      @NonNull String entryPath,
                                      @NonNull File outFile,
                                      @Nullable char[] password) throws IOException {
        if (detectFamily(archive) == Family.EGG) {
            return EggArchiveReader.extractSingleEntry(archive, entryPath, outFile, password);
        }
        String normalized = sanitizeEntryPath(entryPath);
        if (normalized == null || normalized.endsWith("/")) return false;
        Index index = indexFor(archive);
        AlzEntry entry = index.files.get(normalized);
        if (entry == null) return false;
        try (SplitVolumeInput in = openAlzVolumes(archive)) {
            index.requireUnchanged(archive);
            try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
                extractEntryPayload(in, entry, outFile, password, null);
                index.requireUnchanged(archive);
                guard.commit();
            }
        }
        return true;
    }

    static boolean requiresPasswordForExtraction(@NonNull File archive) {
        if (detectFamily(archive) == Family.EGG) return EggArchiveReader.requiresPasswordForExtraction(archive);
        try { archive = AlzVolumeResolver.resolveFirstVolume(archive); }
        catch (IOException unavailable) { return false; }
        Family family = detectFamily(archive);
        if (family == Family.UNKNOWN) return false;
        if (family == Family.EGG) return EggArchiveReader.requiresPasswordForExtraction(archive);
        try {
            for (AlzEntry entry : indexFor(archive).entries) {
                if (!entry.directory && entry.encrypted) return true;
            }
        } catch (IOException ignored) {
            // A damaged header or missing volume is not evidence of encryption.
            // Listing/extraction will report the original archive error.
            return false;
        }
        return false;
    }

    /** Immutable metadata only: no passwords, decoded bytes, or live file handles. */
    static final class Index {
        private final List<VolumeStamp> volumes;
        private final List<AlzEntry> entries;
        private final Map<String, AlzEntry> files;
        private final boolean cacheable;

        private Index(List<VolumeStamp> volumes, List<AlzEntry> entries) {
            this.volumes = Collections.unmodifiableList(new ArrayList<>(volumes));
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            Map<String, AlzEntry> files = new LinkedHashMap<>();
            long nameChars = 0;
            for (AlzEntry entry : entries) {
                nameChars += entry.path.length();
                // Preserve the previous single-entry scanner's first-file-wins behavior.
                if (!entry.directory && sanitizeEntryPath(entry.path) != null
                        && !files.containsKey(entry.path)) files.put(entry.path, entry);
            }
            this.files = Collections.unmodifiableMap(files);
            cacheable = entries.size() <= MAX_INDEX_ENTRIES && nameChars <= MAX_INDEX_NAME_CHARS;
        }

        private boolean matches(List<VolumeStamp> current) {
            if (volumes.size() != current.size()) return false;
            for (int i = 0; i < volumes.size(); i++) {
                if (!volumes.get(i).matches(current.get(i))) return false;
            }
            return true;
        }

        private void requireUnchanged(File archive) throws IOException {
            indexCheckpoint();
            if (!matches(snapshotVolumes(archive))) throw new IOException("ALZ volumes changed during indexed access");
        }
    }

    private static final class VolumeStamp {
        final String path;
        final long length, modified;

        VolumeStamp(File file) throws IOException {
            if (!file.isFile()) throw new IOException("ALZ volume is missing or unreadable");
            path = file.getCanonicalPath();
            length = file.length();
            modified = file.lastModified();
        }

        boolean matches(VolumeStamp other) {
            return path.equals(other.path) && length == other.length && modified == other.modified;
        }
    }

    private static List<VolumeStamp> snapshotVolumes(File archive) throws IOException {
        indexCheckpoint();
        List<VolumeStamp> result = new ArrayList<>();
        for (File part : AlzVolumeResolver.resolve(archive).files) {
            indexCheckpoint();
            result.add(new VolumeStamp(part));
        }
        return result;
    }

    static Index indexFor(File archive) throws IOException {
        List<VolumeStamp> snapshot = snapshotVolumes(archive);
        String path = snapshot.get(0).path;
        synchronized (INDEXES) {
            Index cached = INDEXES.get(path);
            if (cached != null && cached.matches(snapshot)) return cached;
            INDEXES.remove(path);
        }
        Index built;
        AlzVolumeResolver.VolumeSet volumes = AlzVolumeResolver.resolve(archive);
        try (SplitVolumeInput in = new SplitVolumeInput(volumes.segments)) {
            built = new Index(snapshot, readAlzEntries(archive, in, volumes.retainedFinalTrailer));
        }
        built.requireUnchanged(archive);
        synchronized (INDEXES) {
            Index concurrent = INDEXES.get(path);
            if (concurrent != null && concurrent.matches(snapshot)) return concurrent;
            if (built.cacheable) {
                INDEXES.put(path, built);
                while (INDEXES.size() > MAX_INDEX_ARCHIVES) {
                    INDEXES.remove(INDEXES.keySet().iterator().next());
                }
            }
        }
        return built;
    }

    static void releaseArchiveIndex(File archive) {
        try {
            String path = archive.getCanonicalPath();
            synchronized (INDEXES) {
                // Also release when a viewer was opened through a continuation filename.
                java.util.Iterator<Index> iterator = INDEXES.values().iterator();
                while (iterator.hasNext()) {
                    for (VolumeStamp volume : iterator.next().volumes) {
                        if (path.equals(volume.path)) { iterator.remove(); break; }
                    }
                }
            }
        } catch (IOException | SecurityException ignored) { }
    }

    static void clearIndexes() { synchronized (INDEXES) { INDEXES.clear(); } }

    private static void indexCheckpoint() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("ALZ operation cancelled");
    }

    @NonNull
    static Family detectFamily(@NonNull File archive) {
        byte[] signature = new byte[4];
        try (InputStream in = new FileInputStream(archive)) {
            int read = in.read(signature);
            if (read < 4) return Family.UNKNOWN;
            if (signature[0] == 'A' && signature[1] == 'L' && signature[2] == 'Z') return Family.ALZ;
            if (signature[0] == 'E' && signature[1] == 'G' && signature[2] == 'G' && signature[3] == 'A') return Family.EGG;
            return Family.UNKNOWN;
        } catch (IOException | SecurityException ignored) {
            return Family.UNKNOWN;
        }
    }

    // ----- Split segment resolution -----

    /**
     * Opens the archive as a logical segment set. A single-file archive yields
     * one whole-file segment. A split set resolved by {@link AlzVolumeResolver}
     * strips intermediate framing: every segment of a split set ends with a
     * 16-byte trailer, and continuation segments that start with the ALZ
     * signature carry an 8-byte header before their payload bytes. The last
     * framed trailer stays in the logical stream, without moving payload offsets.
     */
    @NonNull
    private static SplitVolumeInput openAlzVolumes(@NonNull File archive) throws IOException {
        return new SplitVolumeInput(AlzVolumeResolver.resolve(archive).segments);
    }

    // ----- Parsing -----

    @NonNull
    private static List<AlzEntry> readAlzEntries(@NonNull File archive,
                                                 @NonNull SplitVolumeInput in,
                                                 boolean retainedFinalTrailer) throws IOException {
        in.seek(0);
        if (in.length() < 8 || readIntLE(in) != SIG_ALZ_FILE_HEADER) throw invalidSignature(archive);
        ArrayList<AlzEntry> entries = new ArrayList<>();
        ArrayList<PendingAlzEntry> pendings = new ArrayList<>();
        in.seek(8);
        long len = in.length();
        long payloadEnd = len - (retainedFinalTrailer ? 16 : 0);
        boolean archiveEnded = false;
        while (in.getFilePointer() < len) {
            indexCheckpoint();
            if (len - in.getFilePointer() < 4) throw new IOException("Truncated ALZ signature");
            int signature = readIntLE(in);
            if (signature == SIG_ALZ_FILE_HEADER) {
                skipFully(in, 4);
            } else if (signature == SIG_LOCAL_FILE_HEADER) {
                PendingAlzEntry pending = readLocalFileHeader(archive, in);
                if (in.getFilePointer() > payloadEnd) throw new IOException("ALZ entry overlaps split trailer");
                if (pending != null) pendings.add(pending);
            } else if (signature == SIG_COMMENT) {
                skipComments(in);
            } else if (signature == SIG_CENTRAL_DIRECTORY) {
                // Two metadata words followed by the final marker. Older
                // central records can own a split marker before a separate END.
                skipFully(in, 8);
                int end = readIntLE(in);
                if (end == SIG_SPLIT_MARKER) end = readIntLE(in);
                if (end != SIG_END_CENTRAL_DIRECTORY) throw new IOException("Invalid ALZ end marker");
                archiveEnded = true;
                break;
            } else if (signature == SIG_END_CENTRAL_DIRECTORY) {
                archiveEnded = true;
                break;
            } else {
                throw new IOException("Unrecognized ALZ structure between files");
            }
        }
        // Legacy raw continuations can end exactly after a complete payload.
        // Keep that nonempty layout, but do not accept a bare header as empty.
        if (pendings.isEmpty() && !archiveEnded) throw new IOException("Missing ALZ archive terminator");
        if (retainedFinalTrailer && !archiveEnded) throw new IOException("Missing ALZ split terminator");
        // One archive-wide charset decision (ArchiveFilenameDecoder.NameCorpus):
        // ALZ names carry no encoding flag at all, and per-name detection is
        // unreliable for one-syllable names, so the long names in the archive
        // decide the code page for everyone.
        ArchiveFilenameDecoder.NameCorpus corpus = new ArchiveFilenameDecoder.NameCorpus();
        for (PendingAlzEntry pending : pendings) {
            indexCheckpoint();
            corpus.observe(pending.nameBytes);
        }
        for (PendingAlzEntry pending : pendings) {
            indexCheckpoint();
            String rawPath = ArchiveFilenameDecoder.decodeLegacyName(pending.nameBytes, corpus);
            String path = sanitizeEntryPath(rawPath);
            // Retain unsafe metadata for whole-archive preflight. Listing/selected
            // access excludes it, but bulk must not silently report partial success.
            if (path == null) path = rawPath;
            boolean directory = (pending.fileAttribute & ALZ_FILEATTR_DIRECTORY) != 0 || path.endsWith("/");
            entries.add(new AlzEntry(path, directory, pending.method, pending.encrypted, pending.hasDescriptor,
                    pending.crc, pending.compressedSize, pending.uncompressedSize, pending.dataOffset,
                    pending.encryptedHeader, pending.timeMillis));
        }
        return entries;
    }

    /** Skip optional comments by their tail-declared extent, never by scanning for magic bytes. */
    private static void skipComments(SplitVolumeInput in) throws IOException {
        long start = in.getFilePointer() - 4;
        long tail = in.length() - 16;
        if (tail < start + 4) throw new IOException("Truncated ALZ comment section");
        in.seek(tail);
        if (readIntLE(in) != SIG_CENTRAL_DIRECTORY) throw new IOException("Missing ALZ comment tail");
        long size = readUInt32LE(in);
        skipFully(in, 4);
        if (readIntLE(in) != SIG_END_CENTRAL_DIRECTORY || size != tail - start) {
            throw new IOException("Invalid ALZ comment extent");
        }
        in.seek(start + 4);
        while (in.getFilePointer() < tail) {
            indexCheckpoint();
            if (tail - in.getFilePointer() < 6) throw new IOException("Truncated ALZ comment record");
            skipFully(in, 4); // File index, or 0xffffffff for an archive comment.
            int bytes = readUInt16LE(in);
            if (bytes > tail - in.getFilePointer()) throw new IOException("ALZ comment exceeds its section");
            skipFully(in, bytes); // Comments are metadata, not entry names or payloads.
        }
    }

    /** Parsed local header whose name is decoded later with the archive-wide corpus. */
    private static final class PendingAlzEntry {
        final byte[] nameBytes;
        final int fileAttribute;
        final int method;
        final boolean encrypted;
        final boolean hasDescriptor;
        final long crc;
        final long compressedSize;
        final long uncompressedSize;
        final long dataOffset;
        final byte[] encryptedHeader;
        final long timeMillis;

        PendingAlzEntry(byte[] nameBytes, int fileAttribute, int method, boolean encrypted, boolean hasDescriptor,
                        long crc, long compressedSize, long uncompressedSize, long dataOffset,
                        byte[] encryptedHeader, long timeMillis) {
            this.nameBytes = nameBytes;
            this.fileAttribute = fileAttribute;
            this.method = method;
            this.encrypted = encrypted;
            this.hasDescriptor = hasDescriptor;
            this.crc = crc;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.dataOffset = dataOffset;
            this.encryptedHeader = encryptedHeader;
            this.timeMillis = timeMillis;
        }
    }

    @Nullable
    private static PendingAlzEntry readLocalFileHeader(@NonNull File archive, @NonNull SplitVolumeInput in) throws IOException {
        int nameLength = readUInt16LE(in);
        int fileAttribute = in.readUnsignedByte();
        long fileTimeDate = readUInt32LE(in);
        int descriptor = in.readUnsignedByte();
        in.readUnsignedByte();
        int sizeBytes = (descriptor & 0xf0) >>> 4;

        int method = COMP_STORED;
        long crc = -1L;
        long compressedSize = 0L;
        long uncompressedSize = 0L;
        if (sizeBytes != 0) {
            // Entries with payload carry method/CRC/size fields; a zero size
            // nibble (directories and empty files) omits them entirely.
            if (sizeBytes != 1 && sizeBytes != 2 && sizeBytes != 4 && sizeBytes != 8) throw unsupported(archive);
            method = in.readUnsignedByte();
            in.readUnsignedByte();
            crc = readUInt32LE(in);
            compressedSize = readUIntLE(in, sizeBytes);
            uncompressedSize = readUIntLE(in, sizeBytes);
        }
        if (nameLength <= 0 || nameLength > 65535) throw new IOException("Invalid ALZ name length");
        byte[] nameBytes = new byte[nameLength];
        in.readFully(nameBytes);
        boolean encrypted = (descriptor & ALZ_DESCRIPTOR_ENCRYPTED) != 0;
        byte[] encryptedHeader = null;
        if (encrypted) {
            encryptedHeader = new byte[12];
            in.readFully(encryptedHeader);
        }
        long dataOffset = in.getFilePointer();
        skipFully(in, compressedSize);
        return new PendingAlzEntry(nameBytes, fileAttribute, method, encrypted,
                (descriptor & ALZ_DESCRIPTOR_DATA_DESCRIPTOR) != 0,
                crc, compressedSize, uncompressedSize, dataOffset, encryptedHeader, dosTimeToMillis(fileTimeDate));
    }

    // ----- Extraction -----

    private static void extractEntryPayload(@NonNull SplitVolumeInput in,
                                            @NonNull AlzEntry entry,
                                            @NonNull File outFile,
                                            @Nullable char[] password,
                                            @Nullable FileOperationProgress progress) throws IOException {
        if (Thread.currentThread().isInterrupted()
                || (progress != null && !progress.checkpoint())) throw new IOException("ALZ extraction cancelled");
        in.requireUnchanged();
        if (entry.directory) return;
        if (entry.compressedSize < 0L || entry.uncompressedSize < 0L) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException("ALZ entry has an invalid size");
        }
        File parent = outFile.getParentFile();
        if (parent == null) throw new IOException("Output file has no parent");
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create output directory");

        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
            try (InputStream decoded = openDecodedPayloadStream(in, entry, password, progress);
                 OutputStream out = ArchiveSupport.openExtractionOutputStream(outFile)) {
                CRC32 crc = new CRC32();
                copyDecodedPayload(decoded, out, crc, entry.uncompressedSize, progress);
                verifyCrc(entry, crc.getValue());
                out.flush();
            }
            // Stream close failures must also roll back the current destination.
            ArchiveCancellation.checkpoint(progress);
            in.requireUnchanged();
            ArchiveCancellation.checkCancelled(progress);
            guard.commit();
        }
    }

    @NonNull
    private static InputStream openDecodedPayloadStream(@NonNull SplitVolumeInput in,
                                                        @NonNull AlzEntry entry,
                                                        @Nullable char[] password,
                                                        @Nullable FileOperationProgress progress) throws IOException {
        return decodePayload(openPayloadStream(in, entry, password, progress), entry.method, entry.uncompressedSize, progress);
    }

    private static InputStream decodePayload(InputStream payload, int method, long unpackedSize,
                                            FileOperationProgress progress) throws IOException {
        try {
            if (method == COMP_STORED) return payload;
            if (method == COMP_DEFLATE) {
                return new OwnedDeflateInputStream(payload, progress);
            }
            if (method == COMP_BZIP2) {
                return openAlzBzipStream(payload, progress);
            }
            if (method == COMP_OBFUSCATED_DEFLATE) {
                return new AlzObfuscatedDeflateInputStream(payload, unpackedSize, progress);
            }
            throw new ArchiveSupport.UnsupportedArchiveFeatureException("Unsupported ALZ compression method");
        } catch (IOException | RuntimeException | Error e) {
            try { payload.close(); } catch (IOException | RuntimeException | Error closeError) {
                if (closeError != e) e.addSuppressed(closeError);
            }
            // Method 1 is supported: malformed tables, cancellation and source
            // I/O errors must not be relabelled as an unsupported codec.
            throw e;
        }
    }

    @NonNull
    private static InputStream openPayloadStream(@NonNull SplitVolumeInput in,
                                                 @NonNull AlzEntry entry,
                                                 @Nullable char[] password,
                                                 @Nullable FileOperationProgress progress) throws IOException {
        if (!entry.encrypted) return in.boundedStream(entry.dataOffset, entry.compressedSize);
        if (password == null || password.length == 0) {
            throw new ArchiveSupport.PasswordRequiredException();
        }
        if (entry.encryptedHeader == null) {
            throw new IOException("Missing ALZ encryption header");
        }
        boolean knownMethod = entry.method == COMP_STORED || entry.method == COMP_DEFLATE
                || entry.method == COMP_BZIP2 || entry.method == COMP_OBFUSCATED_DEFLATE;
        AlzipZipCrypto.PayloadVerifier verifier = knownMethod && entry.crc >= 0
                ? candidate -> passwordMatches(in, entry, candidate, progress) : null;
        AlzipZipCrypto crypto = AlzipZipCrypto.checked(password, entry.encryptedHeader,
                entry.passwordCheckByte(), entry.crc >= 0, verifier);
        InputStream raw = null;
        try {
            raw = in.boundedStream(entry.dataOffset, entry.compressedSize);
            return new AlzDecryptingInputStream(raw, crypto);
        } catch (IOException | RuntimeException | Error failure) {
            crypto.destroy();
            if (raw != null) {
                try { raw.close(); } catch (IOException | RuntimeException | Error cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    /** A read-only CRC probe, only when several password encodings pass the header. */
    private static boolean passwordMatches(SplitVolumeInput in, AlzEntry entry,
                                           AlzipZipCrypto crypto,
                                           FileOperationProgress progress) throws IOException {
        ArchiveCancellation.checkpoint(progress);
        in.requireUnchanged();
        if (entry.method == COMP_STORED && entry.compressedSize != entry.uncompressedSize) {
            throw new IOException("ALZ stored size mismatch");
        }
        boolean matches = verifyPasswordPayload(in.boundedStream(entry.dataOffset, entry.compressedSize),
                entry.method, crypto, entry.uncompressedSize, entry.crc, progress);
        // A changed volume must not select/reject another password encoding.
        ArchiveCancellation.checkpoint(progress);
        in.requireUnchanged();
        ArchiveCancellation.checkCancelled(progress);
        return matches;
    }

    /** Owns raw. Decoder rejection can reject an encoding; source/close errors cannot. */
    static boolean verifyPasswordPayload(InputStream raw, int method, AlzipZipCrypto crypto,
                                         long expectedSize, long expectedCrc,
                                         FileOperationProgress progress) throws IOException {
        try (AlzipZipCrypto.ProbeInput source = new AlzipZipCrypto.ProbeInput(raw, crypto, progress);
             PasswordProbeDecoder decoded = new PasswordProbeDecoder()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            try {
                ArchiveCancellation.checkpoint(progress);
                decoded.stream = decodePayload(source, method, expectedSize, progress);
                CRC32 crc = new CRC32();
                long total = 0;
                while (true) {
                    ArchiveCancellation.checkpoint(progress);
                    int read = decoded.stream.read(buffer);
                    if (read == -1) break;
                    if (read <= 0 || read > buffer.length) throw new IOException("Invalid ALZ password probe read");
                    if (read > expectedSize - total) return false;
                    crc.update(buffer, 0, read);
                    total += read;
                }
                return total == expectedSize && crc.getValue() == (expectedCrc & 0xffffffffL);
            } catch (IOException candidateFailure) {
                source.rethrowFailure();
                ArchiveCancellation.rethrowIfCancelled(candidateFailure, progress);
                if (candidateFailure instanceof ArchiveSupport.UnsupportedArchiveFeatureException) throw candidateFailure;
                // Only decoder-origin failures reach here. Wrong decrypted bytes
                // may be invalid Deflate/BZip2, not merely a valid stream with bad CRC.
                return false;
            } finally {
                java.util.Arrays.fill(buffer, (byte) 0);
            }
        }
    }

    // Resource closure is outside the decoder-rejection catch above. A failed
    // close overrides a false/matching result; it never selects another encoding.
    private static final class PasswordProbeDecoder implements AutoCloseable {
        InputStream stream;
        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }

    /**
     * Opens a decoder for an ALZ bzip2 payload. ALZip versions wrote two
     * different streams under method 1: the ALZip 4.x trimmed bitstream
     * variant framed as {@code 'D','L','Z',0x01} (see
     * docs/ALZ_FORMAT_NOTES.md, decoded by {@link AlzBzip2InputStream}) and
     * plain bzip2 with or without its {@code BZh} magic. The first bytes
     * distinguish them deterministically, since the variant's first block
     * header is byte aligned at offset 0.
     */
    @NonNull
    private static InputStream openAlzBzipStream(@NonNull InputStream payload) throws IOException {
        return openAlzBzipStream(payload, null);
    }

    @NonNull
    private static InputStream openAlzBzipStream(@NonNull InputStream payload,
                                                @Nullable FileOperationProgress progress) throws IOException {
        ArchiveCancellation.checkpoint(progress);
        PushbackInputStream in = new PushbackInputStream(payload, 3);
        byte[] probe = new byte[3];
        int count = 0;
        while (count < probe.length) {
            ArchiveCancellation.checkpoint(progress);
            int requested = probe.length - count;
            int read = in.read(probe, count, requested);
            if (read == -1) break;
            if (read <= 0 || read > requested) throw new IOException("Invalid or zero-progress ALZ BZip2 source read");
            ArchiveCancellation.checkpoint(progress);
            count += read;
        }
        if (count > 0) in.unread(probe, 0, count);
        if (count >= 3 && probe[0] == 'D' && probe[1] == 'L' && probe[2] == 'Z') {
            return new AlzBzip2InputStream(in, progress);
        }
        if (count >= 3 && probe[0] == 'B' && probe[1] == 'Z' && probe[2] == 'h') {
            return new ArchiveBzip2InputStream(in, progress);
        }
        if (count >= 1 && probe[0] == 'h') {
            // Standard bzip2 with the leading "BZ" stripped.
            return new ArchiveBzip2InputStream(
                    new SequenceInputStream(new ByteArrayInputStream(new byte[] {'B', 'Z'}), in), progress);
        }
        return new ArchiveBzip2InputStream(in, progress);
    }

    private static void copyDecodedPayload(@NonNull InputStream decoded,
                                           @NonNull OutputStream out,
                                           @NonNull CRC32 crc,
                                           long expectedSize,
                                           @Nullable FileOperationProgress progress) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long written = 0L;
        int read;
        while ((read = decoded.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()
                    || (progress != null && !progress.checkpoint())) throw new IOException("ALZ extraction cancelled");
            if (read <= 0) throw new IOException("ALZ decoder made no progress");
            if (read > expectedSize - written) throw new IOException("ALZ decoded size exceeds declared size");
            crc.update(buffer, 0, read);
            out.write(buffer, 0, read);
            written += read;
            if (progress != null) progress.addDoneBytes(read);
        }
        if (written != expectedSize) throw new IOException("ALZ decoded size mismatch");
    }

    private static void verifyCrc(@NonNull AlzEntry entry, long actualCrc) throws IOException {
        if (entry.crc < 0) return;
        if ((actualCrc & 0xffffffffL) != (entry.crc & 0xffffffffL)) {
            throw new IOException("ALZ CRC mismatch");
        }
    }

    private static final class AlzDecryptingInputStream extends InputStream {
        @NonNull private final InputStream source;
        @NonNull private final AlzipZipCrypto crypto;
        private final byte[] one = new byte[1];

        AlzDecryptingInputStream(@NonNull InputStream source, @NonNull AlzipZipCrypto crypto) {
            this.source = source;
            this.crypto = crypto;
        }

        @Override
        public int read() throws IOException {
            int read = read(one, 0, 1);
            return read <= 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            int read = source.read(buffer, offset, length);
            if (read > 0) crypto.decryptInPlace(buffer, offset, read);
            return read;
        }

        @Override
        public void close() throws IOException {
            try { source.close(); }
            finally {
                one[0] = 0;
                crypto.destroy();
            }
        }
    }

    // ----- Helpers -----

    private static long sumUncompressedBytes(@NonNull List<AlzEntry> entries) {
        long total = 0L;
        boolean unknown = false;
        for (AlzEntry entry : entries) {
            if (entry == null || entry.directory) continue;
            if (entry.uncompressedSize < 0L) {
                unknown = true;
                continue;
            }
            if (Long.MAX_VALUE - total < entry.uncompressedSize) return Long.MAX_VALUE;
            total += entry.uncompressedSize;
        }
        return total > 0L ? total : (unknown ? -1L : 0L);
    }

    @Nullable
    private static File resolveOutput(@NonNull File targetDir, @NonNull String entryPath) throws IOException {
        String path = sanitizeEntryPath(entryPath);
        if (path == null) return null;
        File out = new File(targetDir, path);
        return isSameOrDescendant(targetDir, out) ? out : null;
    }

    @Nullable
    private static String sanitizeEntryPath(String rawEntryName) {
        return ArchiveSupport.sanitizeEntryPathForList(rawEntryName);
    }

    private static boolean isSameOrDescendant(@NonNull File ancestor, @NonNull File candidate) throws IOException {
        File ancestorCanonical = ancestor.getCanonicalFile();
        File current = candidate.getCanonicalFile();
        while (current != null) {
            if (ancestorCanonical.equals(current)) return true;
            current = current.getParentFile();
        }
        return false;
    }

    private static int readIntLE(@NonNull SplitVolumeInput in) throws IOException {
        return (int) in.readLittleEndian(4);
    }

    private static int readUInt16LE(@NonNull SplitVolumeInput in) throws IOException {
        return (int) in.readLittleEndian(2);
    }

    private static long readUInt32LE(@NonNull SplitVolumeInput in) throws IOException {
        return readIntLE(in) & 0xffffffffL;
    }

    private static long readUIntLE(@NonNull SplitVolumeInput in, int bytes) throws IOException {
        return in.readLittleEndian(bytes);
    }

    private static void skipFully(@NonNull SplitVolumeInput in, long bytes) throws IOException {
        if (bytes < 0 || in.getFilePointer() + bytes > in.length()) throw new IOException("Unexpected ALZ EOF");
        in.seek(in.getFilePointer() + bytes);
    }

    private static long dosTimeToMillis(long dosTime) {
        int second = (int) (dosTime & 0x1f) * 2;
        int minute = (int) ((dosTime >>> 5) & 0x3f);
        int hour = (int) ((dosTime >>> 11) & 0x1f);
        int day = (int) ((dosTime >>> 16) & 0x1f);
        int month = (int) ((dosTime >>> 21) & 0x0f);
        int year = 1980 + (int) ((dosTime >>> 25) & 0x7f);
        if (month < 1 || month > 12 || day < 1 || day > 31
                || hour > 23 || minute > 59 || second > 59) return 0L;
        java.util.Calendar calendar = new java.util.GregorianCalendar();
        calendar.clear();
        calendar.setLenient(false);
        calendar.set(year, month - 1, day, hour, minute, second);
        try { return calendar.getTimeInMillis(); }
        catch (IllegalArgumentException invalidDate) { return 0L; }
    }

    @NonNull
    private static IOException unsupported(@NonNull File archive) {
        Family family = detectFamily(archive);
        String label = family == Family.ALZ ? "ALZ" : family == Family.EGG ? "EGG" : "ALZ/EGG";
        return new ArchiveSupport.UnsupportedArchiveFeatureException("Unsupported " + label + " feature or archive variant: " + archive.getName());
    }

    @NonNull
    private static IOException invalidSignature(@NonNull File archive) {
        return new IOException("Invalid ALZ/EGG signature: " + archive.getName());
    }

    private static final class AlzEntry {
        final String path;
        final boolean directory;
        final int method;
        final boolean encrypted;
        final boolean dataDescriptor;
        final long crc;
        final long compressedSize;
        final long uncompressedSize;
        final long dataOffset;
        @Nullable final byte[] encryptedHeader;
        final long timeMillis;

        AlzEntry(String path,
                 boolean directory,
                 int method,
                 boolean encrypted,
                 boolean dataDescriptor,
                 long crc,
                 long compressedSize,
                 long uncompressedSize,
                 long dataOffset,
                 @Nullable byte[] encryptedHeader,
                 long timeMillis) {
            this.path = path;
            this.directory = directory;
            this.method = method;
            this.encrypted = encrypted;
            this.dataDescriptor = dataDescriptor;
            this.crc = crc;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.dataOffset = dataOffset;
            this.encryptedHeader = encryptedHeader;
            this.timeMillis = timeMillis;
        }

        int passwordCheckByte() {
            return dataDescriptor ? 0 : (int) ((crc >>> 24) & 0xff);
        }
    }
}
