package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import com.textview.reader.util.FileOperationProgress;

/**
 * Recognition boundary for ESTsoft ALZip-family archives.
 *
 * ALZ/EGG use proprietary containers with several compression, encryption,
 * solid, and split variants. Until a verified decoder is implemented, this
 * reader fails explicitly instead of producing partial or corrupt output.
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
        List<AlzEntry> entries = readAlzEntries(archive);
        if (entries.isEmpty()) throw unsupported(archive);
        ArrayList<ArchiveSupport.EntryInfo> result = new ArrayList<>();
        for (AlzEntry entry : entries) {
            result.add(new ArchiveSupport.EntryInfo(entry.path, entry.directory, entry.uncompressedSize, entry.timeMillis));
        }
        return withSyntheticDirectories(result);
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
        List<AlzEntry> entries = readAlzEntries(archive);
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
            extractEntryPayload(archive, entry, out, password, progress);
        }
        return sawEntry;
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
        List<AlzEntry> entries = readAlzEntries(archive);
        for (AlzEntry entry : entries) {
            if (entry.directory || !normalized.equals(entry.path)) continue;
            extractEntryPayload(archive, entry, outFile, password, null);
            return true;
        }
        return false;
    }

    static boolean requiresPasswordForExtraction(@NonNull File archive) {
        Family family = detectFamily(archive);
        if (family == Family.UNKNOWN) return false;
        if (family == Family.EGG) return true;
        try {
            for (AlzEntry entry : readAlzEntries(archive)) {
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

    @NonNull
    private static List<AlzEntry> readAlzEntries(@NonNull File archive) throws IOException {
        if (detectFamily(archive) != Family.ALZ) throw invalidSignature(archive);
        ArrayList<AlzEntry> entries = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            while (raf.getFilePointer() + 4 <= raf.length()) {
                int signature = readIntLE(raf);
                if (signature == SIG_ALZ_FILE_HEADER) {
                    skipFully(raf, 4);
                } else if (signature == SIG_LOCAL_FILE_HEADER) {
                    AlzEntry entry = readLocalFileHeader(archive, raf);
                    if (entry != null) entries.add(entry);
                } else if (signature == SIG_CENTRAL_DIRECTORY || signature == SIG_END_CENTRAL_DIRECTORY) {
                    break;
                } else {
                    break;
                }
            }
        }
        return entries;
    }

    @Nullable
    private static AlzEntry readLocalFileHeader(@NonNull File archive, @NonNull RandomAccessFile raf) throws IOException {
        int nameLength = readUInt16LE(raf);
        int fileAttribute = raf.readUnsignedByte();
        long fileTimeDate = readUInt32LE(raf);
        int descriptor = raf.readUnsignedByte();
        raf.readUnsignedByte();
        int sizeBytes = (descriptor & 0xf0) >>> 4;
        if (sizeBytes != 1 && sizeBytes != 2 && sizeBytes != 4 && sizeBytes != 8) throw unsupported(archive);

        int method = raf.readUnsignedByte();
        raf.readUnsignedByte();
        long crc = readUInt32LE(raf);
        long compressedSize = readUIntLE(raf, sizeBytes);
        long uncompressedSize = readUIntLE(raf, sizeBytes);
        if (nameLength <= 0 || nameLength > 65535) throw new IOException("Invalid ALZ name length");
        byte[] nameBytes = new byte[nameLength];
        raf.readFully(nameBytes);
        String path = sanitizeEntryPath(decodeAlzName(nameBytes));
        boolean encrypted = (descriptor & ALZ_DESCRIPTOR_ENCRYPTED) != 0;
        byte[] encryptedHeader = null;
        if (encrypted) {
            encryptedHeader = new byte[12];
            raf.readFully(encryptedHeader);
        }
        long dataOffset = raf.getFilePointer();
        skipFully(raf, compressedSize);
        if (path == null) return null;
        boolean directory = (fileAttribute & ALZ_FILEATTR_DIRECTORY) != 0 || path.endsWith("/");
        return new AlzEntry(path, directory, method, encrypted, (descriptor & ALZ_DESCRIPTOR_DATA_DESCRIPTOR) != 0,
                crc, compressedSize, uncompressedSize, dataOffset, encryptedHeader, dosTimeToMillis(fileTimeDate));
    }

    private static void extractEntryPayload(@NonNull File archive,
                                            @NonNull AlzEntry entry,
                                            @NonNull File outFile,
                                            @Nullable char[] password,
                                            @Nullable FileOperationProgress progress) throws IOException {
        if (progress != null && !progress.checkpoint()) throw new IOException("ALZ extraction cancelled");
        if (entry.directory) return;
        if (entry.compressedSize > Integer.MAX_VALUE) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException("ALZ entry is too large for this decoder pass");
        }
        File parent = outFile.getParentFile();
        if (parent == null) throw new IOException("Output file has no parent");
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create output directory");
        byte[] payload = new byte[(int) entry.compressedSize];
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            raf.seek(entry.dataOffset);
            raf.readFully(payload);
        }
        if (progress != null && !progress.checkpoint()) throw new IOException("ALZ extraction cancelled");
        if (entry.encrypted) {
            if (password == null || password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
            if (entry.encryptedHeader == null) throw new IOException("Missing ALZ encryption header");
            AlzipZipCrypto crypto = new AlzipZipCrypto(password);
            if (!crypto.checkHeader(entry.encryptedHeader, entry.passwordCheckByte())) {
                throw new IOException("Invalid ALZ password");
            }
            crypto.decryptInPlace(payload, 0, payload.length);
        }
        byte[] plain = decodePayload(entry, payload, progress);
        verifyCrc(entry, plain);
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            if (progress != null && !progress.checkpoint()) throw new IOException("ALZ extraction cancelled");
            out.write(plain);
            if (progress != null && (entry.method == COMP_STORED || entry.method == COMP_BZIP2)) {
                progress.addDoneBytes(plain.length);
            }
        }
    }

    @NonNull
    private static byte[] decodePayload(@NonNull AlzEntry entry,
                                        @NonNull byte[] payload,
                                        @Nullable FileOperationProgress progress) throws IOException {
        if (entry.method == COMP_STORED) return payload;
        if (entry.method == COMP_DEFLATE) {
            return readAll(new InflaterInputStream(new ByteArrayInputStream(payload), new Inflater(true)), progress);
        }
        if (entry.method == COMP_BZIP2) {
            // ALZ's BZip2 method carries a BZip2 stream. commons-compress (already
            // a project dependency) decodes the standard "BZh"-prefixed form. Some
            // ALZ writers omit the 2-byte "BZ" magic, so if the payload lacks it we
            // prepend it before decoding. The entry CRC is verified by the caller
            // afterward, so a wrong stream shape fails loudly rather than yielding
            // silent corruption.
            byte[] stream = payload;
            if (!(payload.length >= 3 && payload[0] == 'B' && payload[1] == 'Z' && payload[2] == 'h')) {
                if (payload.length >= 1 && payload[0] == 'h') {
                    // Missing only the "BZ" prefix.
                    byte[] prefixed = new byte[payload.length + 2];
                    prefixed[0] = 'B';
                    prefixed[1] = 'Z';
                    System.arraycopy(payload, 0, prefixed, 2, payload.length);
                    stream = prefixed;
                }
            }
            try {
                return readAll(new BZip2CompressorInputStream(new ByteArrayInputStream(stream)), null);
            } catch (IOException e) {
                throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                        "ALZ BZip2 stream could not be decoded: " + e.getMessage());
            }
        }
        throw new ArchiveSupport.UnsupportedArchiveFeatureException("Unsupported ALZ compression method");
    }

    private static void verifyCrc(@NonNull AlzEntry entry, @NonNull byte[] plain) throws IOException {
        if (entry.crc < 0) return;
        CRC32 crc = new CRC32();
        crc.update(plain);
        if ((crc.getValue() & 0xffffffffL) != (entry.crc & 0xffffffffL)) {
            throw new IOException("ALZ CRC mismatch");
        }
    }

    @NonNull
    private static byte[] readAll(@NonNull InputStream input,
                                  @Nullable FileOperationProgress progress) throws IOException {
        try (InputStream in = input;
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (progress != null && !progress.checkpoint()) throw new IOException("ALZ extraction cancelled");
                out.write(buffer, 0, read);
                if (progress != null) progress.addDoneBytes(read);
            }
            return out.toByteArray();
        }
    }

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

    @NonNull
    private static String decodeAlzName(@NonNull byte[] bytes) {
        try {
            return new String(bytes, Charset.forName("MS949"));
        } catch (Exception ignored) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static int readIntLE(@NonNull RandomAccessFile raf) throws IOException {
        return raf.readUnsignedByte()
                | (raf.readUnsignedByte() << 8)
                | (raf.readUnsignedByte() << 16)
                | (raf.readUnsignedByte() << 24);
    }

    private static int readUInt16LE(@NonNull RandomAccessFile raf) throws IOException {
        return raf.readUnsignedByte() | (raf.readUnsignedByte() << 8);
    }

    private static long readUInt32LE(@NonNull RandomAccessFile raf) throws IOException {
        return readIntLE(raf) & 0xffffffffL;
    }

    private static long readUIntLE(@NonNull RandomAccessFile raf, int bytes) throws IOException {
        long value = 0L;
        for (int i = 0; i < bytes; i++) value |= ((long) raf.readUnsignedByte()) << (i * 8);
        return value;
    }

    private static void skipFully(@NonNull RandomAccessFile raf, long bytes) throws IOException {
        if (bytes < 0 || raf.getFilePointer() + bytes > raf.length()) throw new IOException("Unexpected ALZ EOF");
        raf.seek(raf.getFilePointer() + bytes);
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
