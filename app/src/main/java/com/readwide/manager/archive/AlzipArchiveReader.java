package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

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
 * <p>Split archives ({@code name.alz} + {@code name.a00}, {@code name.a01},
 * ...) are a byte-level cut of one logical archive with per-segment framing:
 * every segment of a split set ends with a 16-byte trailer, and each
 * continuation segment that starts with the ALZ signature carries an 8-byte
 * segment header before its payload bytes. The segments minus that framing are
 * presented as one logical stream through {@link SplitVolumeInput}, so entry
 * data that straddles a segment boundary decodes normally. A missing segment
 * fails cleanly with no partial output.</p>
 */
final class AlzipArchiveReader {
    private static final int SIG_ALZ_FILE_HEADER = 0x015a4c41;
    private static final int SIG_LOCAL_FILE_HEADER = 0x015a4c42;
    private static final int SIG_CENTRAL_DIRECTORY = 0x015a4c43;
    private static final int SIG_END_CENTRAL_DIRECTORY = 0x025a4c43;
    private static final int ALZ_FILEATTR_DIRECTORY = 0x10;
    private static final int ALZ_DESCRIPTOR_ENCRYPTED = 0x01;
    private static final int ALZ_DESCRIPTOR_DATA_DESCRIPTOR = 0x08;
    private static final int COMP_STORED = 0;
    private static final int COMP_BZIP2 = 1;
    private static final int COMP_DEFLATE = 2;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int SEGMENT_HEADER_BYTES = 8;   // sig u32 + version u16 + segment id u16
    private static final int SEGMENT_TRAILER_BYTES = 16; // CLZ\1 + 8 bytes + CLZ\2 or CLZ\3
    private static final int MAX_SPLIT_SEGMENTS = 1000;

    private static final Pattern CONTINUATION_NAME =
            Pattern.compile("^(.*)\\.a(\\d{2,3})$", Pattern.CASE_INSENSITIVE);

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
            requirePasswordThenFailUnsupported(archive, password);
            throw unsupported(archive);
        }
        try (SplitVolumeInput in = openAlzVolumes(archive)) {
            List<AlzEntry> entries = readAlzEntries(archive, in);
            if (entries.isEmpty()) throw unsupported(archive);
            ArrayList<ArchiveSupport.EntryInfo> result = new ArrayList<>();
            for (AlzEntry entry : entries) {
                result.add(new ArchiveSupport.EntryInfo(entry.path, entry.directory, entry.uncompressedSize, entry.timeMillis));
            }
            return withSyntheticDirectories(result);
        }
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
            requirePasswordThenFailUnsupported(archive, password);
            throw unsupported(archive);
        }
        try (SplitVolumeInput in = openAlzVolumes(archive)) {
            List<AlzEntry> entries = readAlzEntries(archive, in);
            if (progress != null) progress.setTotalBytes(sumUncompressedBytes(entries));
            boolean sawEntry = false;
            for (AlzEntry entry : entries) {
                if (progress != null && !progress.checkpoint()) return false;
                if (entryProgress != null) {
                    if (entry.directory || entry.path.endsWith("/")) entryProgress.onDirectory(entry.path);
                    else entryProgress.onFile(entry.path);
                } else if (progress != null) progress.setDetail(entry.path);
                File out = resolveOutput(targetDir, entry.path);
                if (out == null) return false;
                sawEntry = true;
                if (entry.directory || entry.path.endsWith("/")) {
                    if (!out.exists() && !out.mkdirs()) return false;
                    continue;
                }
                extractEntryPayload(in, entry, out, password, progress);
            }
            return sawEntry;
        }
    }

    static boolean extractSingleEntry(@NonNull File archive,
                                      @NonNull String entryPath,
                                      @NonNull File outFile,
                                      @Nullable char[] password) throws IOException {
        if (detectFamily(archive) == Family.EGG) {
            requirePasswordThenFailUnsupported(archive, password);
            throw unsupported(archive);
        }
        String normalized = sanitizeEntryPath(entryPath);
        if (normalized == null || normalized.endsWith("/")) return false;
        try (SplitVolumeInput in = openAlzVolumes(archive)) {
            for (AlzEntry entry : readAlzEntries(archive, in)) {
                if (entry.directory || !normalized.equals(entry.path)) continue;
                extractEntryPayload(in, entry, outFile, password, null);
                return true;
            }
        }
        return false;
    }

    static boolean requiresPasswordForExtraction(@NonNull File archive) {
        Family family = detectFamily(archive);
        if (family == Family.UNKNOWN) return false;
        if (family == Family.EGG) return true;
        try (SplitVolumeInput in = openAlzVolumes(archive)) {
            for (AlzEntry entry : readAlzEntries(archive, in)) {
                if (!entry.directory && entry.encrypted) return true;
            }
        } catch (IOException ignored) {
            return true;
        }
        return false;
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

    private static void requirePasswordThenFailUnsupported(@NonNull File archive,
                                                           @Nullable char[] password) throws IOException {
        Family family = detectFamily(archive);
        if (family == Family.UNKNOWN) throw invalidSignature(archive);
        if (password == null || password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
    }

    // ----- Split segment resolution -----

    /**
     * Opens the archive as a logical segment set. A single-file archive yields
     * one whole-file segment. A split set ({@code name.alz} + {@code name.aNN})
     * strips each segment's framing: every segment of a split set ends with a
     * 16-byte trailer, and continuation segments that start with the ALZ
     * signature carry an 8-byte header before their payload bytes.
     */
    @NonNull
    private static SplitVolumeInput openAlzVolumes(@NonNull File archive) throws IOException {
        List<File> continuations = collectContinuationSegments(archive);
        List<SplitVolumeInput.Segment> segments = new ArrayList<>();
        long firstLength = archive.length();
        if (!continuations.isEmpty()) {
            firstLength -= SEGMENT_TRAILER_BYTES;
            if (firstLength < SEGMENT_HEADER_BYTES) {
                throw new IOException("ALZ split first segment shorter than its framing: " + archive.getName());
            }
        }
        segments.add(new SplitVolumeInput.Segment(archive, 0L, firstLength));
        for (File part : continuations) {
            long offset = startsWithAlzSignature(part) ? SEGMENT_HEADER_BYTES : 0L;
            long trailer = offset > 0L ? SEGMENT_TRAILER_BYTES : 0L;
            long length = part.length() - offset - trailer;
            if (length < 0L) throw new IOException("ALZ split segment shorter than its framing: " + part.getName());
            segments.add(new SplitVolumeInput.Segment(part, offset, length));
        }
        return new SplitVolumeInput(segments);
    }

    /** Ordered {@code name.a00}, {@code name.a01}, ... siblings of the first part. */
    @NonNull
    private static List<File> collectContinuationSegments(@NonNull File firstPart) throws IOException {
        File parent = firstPart.getParentFile();
        String name = firstPart.getName();
        int dot = name.toLowerCase(Locale.ROOT).lastIndexOf(".alz");
        if (parent == null || dot <= 0) return Collections.emptyList();
        String base = name.substring(0, dot);
        File[] children = parent.listFiles();
        if (children == null) return Collections.emptyList();
        ArrayList<File> parts = new ArrayList<>();
        for (File child : children) {
            if (!child.isFile()) continue;
            Matcher m = CONTINUATION_NAME.matcher(child.getName());
            if (!m.matches() || !m.group(1).equalsIgnoreCase(base)) continue;
            parts.add(child);
        }
        if (parts.isEmpty()) return Collections.emptyList();
        parts.sort(Comparator.comparingInt(AlzipArchiveReader::continuationIndex));
        if (parts.size() > MAX_SPLIT_SEGMENTS) throw new IOException("ALZ split has too many segments");
        // The set must be contiguous from .a00; a gap means a missing segment.
        for (int i = 0; i < parts.size(); i++) {
            if (continuationIndex(parts.get(i)) != i) {
                throw new IOException("Missing ALZ split segment before " + parts.get(i).getName());
            }
        }
        return parts;
    }

    private static int continuationIndex(@NonNull File part) {
        Matcher m = CONTINUATION_NAME.matcher(part.getName());
        if (!m.matches()) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static boolean startsWithAlzSignature(@NonNull File part) {
        byte[] signature = new byte[4];
        try (InputStream in = new FileInputStream(part)) {
            if (in.read(signature) < 4) return false;
            return signature[0] == 'A' && signature[1] == 'L' && signature[2] == 'Z';
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    // ----- Parsing -----

    @NonNull
    private static List<AlzEntry> readAlzEntries(@NonNull File archive,
                                                 @NonNull SplitVolumeInput in) throws IOException {
        if (detectFamily(archive) != Family.ALZ) throw invalidSignature(archive);
        ArrayList<AlzEntry> entries = new ArrayList<>();
        ArrayList<PendingAlzEntry> pendings = new ArrayList<>();
        in.seek(0);
        long len = in.length();
        while (in.getFilePointer() + 4 <= len) {
            int signature = readIntLE(in);
            if (signature == SIG_ALZ_FILE_HEADER) {
                skipFully(in, 4);
            } else if (signature == SIG_LOCAL_FILE_HEADER) {
                PendingAlzEntry pending = readLocalFileHeader(archive, in);
                if (pending != null) pendings.add(pending);
            } else if (signature == SIG_CENTRAL_DIRECTORY || signature == SIG_END_CENTRAL_DIRECTORY) {
                break;
            } else {
                break;
            }
        }
        // One archive-wide charset decision (ArchiveFilenameDecoder.NameCorpus):
        // ALZ names carry no encoding flag at all, and per-name detection is
        // unreliable for one-syllable names, so the long names in the archive
        // decide the code page for everyone.
        ArchiveFilenameDecoder.NameCorpus corpus = new ArchiveFilenameDecoder.NameCorpus();
        for (PendingAlzEntry pending : pendings) corpus.observe(pending.nameBytes);
        for (PendingAlzEntry pending : pendings) {
            String path = sanitizeEntryPath(ArchiveFilenameDecoder.decodeLegacyName(pending.nameBytes, corpus));
            if (path == null) continue;
            boolean directory = (pending.fileAttribute & ALZ_FILEATTR_DIRECTORY) != 0 || path.endsWith("/");
            entries.add(new AlzEntry(path, directory, pending.method, pending.encrypted, pending.hasDescriptor,
                    pending.crc, pending.compressedSize, pending.uncompressedSize, pending.dataOffset,
                    pending.encryptedHeader, pending.timeMillis));
        }
        return entries;
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
        if (progress != null && !progress.checkpoint()) throw new IOException("ALZ extraction cancelled");
        if (entry.directory) return;
        if (entry.compressedSize < 0L) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException("ALZ entry has an invalid size");
        }
        File parent = outFile.getParentFile();
        if (parent == null) throw new IOException("Output file has no parent");
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create output directory");

        boolean ok = false;
        try (InputStream decoded = openDecodedPayloadStream(in, entry, password);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            CRC32 crc = new CRC32();
            copyDecodedPayload(decoded, out, crc, progress);
            verifyCrc(entry, crc.getValue());
            out.flush();
            ok = true;
        } finally {
            if (!ok) {
                try { //noinspection ResultOfMethodCallIgnored
                    outFile.delete();
                } catch (SecurityException ignored) {
                }
            }
        }
    }

    @NonNull
    private static InputStream openDecodedPayloadStream(@NonNull SplitVolumeInput in,
                                                        @NonNull AlzEntry entry,
                                                        @Nullable char[] password) throws IOException {
        InputStream payload = openPayloadStream(in, entry, password);
        try {
            if (entry.method == COMP_STORED) return payload;
            if (entry.method == COMP_DEFLATE) {
                return new InflaterInputStream(payload, new Inflater(true));
            }
            if (entry.method == COMP_BZIP2) {
                return openAlzBzipStream(payload);
            }
            throw new ArchiveSupport.UnsupportedArchiveFeatureException("Unsupported ALZ compression method");
        } catch (IOException | RuntimeException e) {
            try { payload.close(); } catch (IOException ignored) {}
            if (e instanceof ArchiveSupport.UnsupportedArchiveFeatureException) throw (ArchiveSupport.UnsupportedArchiveFeatureException) e;
            if (entry.method == COMP_BZIP2) {
                throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                        "ALZ BZip2 stream could not be decoded: " + e.getMessage());
            }
            if (e instanceof IOException) throw (IOException) e;
            throw (RuntimeException) e;
        }
    }

    @NonNull
    private static InputStream openPayloadStream(@NonNull SplitVolumeInput in,
                                                 @NonNull AlzEntry entry,
                                                 @Nullable char[] password) throws IOException {
        InputStream raw = in.boundedStream(entry.dataOffset, entry.compressedSize);
        if (!entry.encrypted) return raw;
        if (password == null || password.length == 0) {
            try { raw.close(); } catch (IOException ignored) {}
            throw new ArchiveSupport.PasswordRequiredException();
        }
        if (entry.encryptedHeader == null) {
            try { raw.close(); } catch (IOException ignored) {}
            throw new IOException("Missing ALZ encryption header");
        }
        AlzipZipCrypto crypto = new AlzipZipCrypto(password);
        if (!crypto.checkHeader(entry.encryptedHeader, entry.passwordCheckByte())) {
            try { raw.close(); } catch (IOException ignored) {}
            throw new IOException("Invalid ALZ password");
        }
        return new AlzDecryptingInputStream(raw, crypto);
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
        PushbackInputStream in = new PushbackInputStream(payload, 3);
        byte[] probe = new byte[3];
        int count = 0;
        while (count < probe.length) {
            int read = in.read(probe, count, probe.length - count);
            if (read <= 0) break;
            count += read;
        }
        if (count > 0) in.unread(probe, 0, count);
        if (count >= 3 && probe[0] == 'D' && probe[1] == 'L' && probe[2] == 'Z') {
            return new AlzBzip2InputStream(in);
        }
        if (count >= 3 && probe[0] == 'B' && probe[1] == 'Z' && probe[2] == 'h') {
            return new BZip2CompressorInputStream(in);
        }
        if (count >= 1 && probe[0] == 'h') {
            // Standard bzip2 with the leading "BZ" stripped.
            return new BZip2CompressorInputStream(
                    new SequenceInputStream(new ByteArrayInputStream(new byte[] {'B', 'Z'}), in));
        }
        return new BZip2CompressorInputStream(in);
    }

    private static void copyDecodedPayload(@NonNull InputStream decoded,
                                           @NonNull OutputStream out,
                                           @NonNull CRC32 crc,
                                           @Nullable FileOperationProgress progress) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = decoded.read(buffer)) != -1) {
            if (progress != null && !progress.checkpoint()) throw new IOException("ALZ extraction cancelled");
            crc.update(buffer, 0, read);
            out.write(buffer, 0, read);
            if (progress != null) progress.addDoneBytes(read);
        }
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

        AlzDecryptingInputStream(@NonNull InputStream source, @NonNull AlzipZipCrypto crypto) {
            this.source = source;
            this.crypto = crypto;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
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
            source.close();
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

    private static boolean isSameOrDescendant(@NonNull File ancestor, @NonNull File candidate) throws IOException {
        File ancestorCanonical = ancestor.getCanonicalFile();
        File current = candidate.getCanonicalFile();
        while (current != null) {
            if (ancestorCanonical.equals(current)) return true;
            current = current.getParentFile();
        }
        return false;
    }

    @NonNull
    private static List<ArchiveSupport.EntryInfo> withSyntheticDirectories(@NonNull List<ArchiveSupport.EntryInfo> entries) {
        Map<String, ArchiveSupport.EntryInfo> map = new LinkedHashMap<>();
        for (ArchiveSupport.EntryInfo entry : entries) {
            String path = entry.path;
            int slash = path.indexOf('/');
            while (slash >= 0) {
                String dir = path.substring(0, slash + 1);
                if (!map.containsKey(dir)) map.put(dir, new ArchiveSupport.EntryInfo(dir, true, -1L, 0L));
                slash = path.indexOf('/', slash + 1);
            }
            map.put(path, entry);
        }
        return new ArrayList<>(map.values());
    }

    private static int readIntLE(@NonNull SplitVolumeInput in) throws IOException {
        return in.readUnsignedByte()
                | (in.readUnsignedByte() << 8)
                | (in.readUnsignedByte() << 16)
                | (in.readUnsignedByte() << 24);
    }

    private static int readUInt16LE(@NonNull SplitVolumeInput in) throws IOException {
        return in.readUnsignedByte() | (in.readUnsignedByte() << 8);
    }

    private static long readUInt32LE(@NonNull SplitVolumeInput in) throws IOException {
        return readIntLE(in) & 0xffffffffL;
    }

    private static long readUIntLE(@NonNull SplitVolumeInput in, int bytes) throws IOException {
        long value = 0L;
        for (int i = 0; i < bytes; i++) value |= ((long) in.readUnsignedByte()) << (i * 8);
        return value;
    }

    private static void skipFully(@NonNull SplitVolumeInput in, long bytes) throws IOException {
        if (bytes < 0 || in.getFilePointer() + bytes > in.length()) throw new IOException("Unexpected ALZ EOF");
        in.seek(in.getFilePointer() + bytes);
    }

    private static long dosTimeToMillis(long dosTime) {
        return 0L;
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
