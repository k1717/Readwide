package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.codecs.ZipxNativeCodecs;
import com.readwide.manager.util.FileOperationProgress;

import net.lingala.zip4j.crypto.AESDecrypter;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.AESExtraDataRecord;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.AesVersion;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
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
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

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
 * Deflate64, BZip2, LZMA, XZ, JPEG, and WavPack. PPMd and Zstandard AES entries
 * fall through to the vendored libarchive 3.8.9 backend.</p>
 */
final class ZipxAesArchiveReader {
    private static final int METHOD_STORED = 0;
    private static final int METHOD_DEFLATE = 8;
    private static final int METHOD_DEFLATE64 = 9;
    private static final int METHOD_BZIP2 = 12;
    private static final int METHOD_LZMA = 14;
    private static final int METHOD_ZSTANDARD = 93;
    private static final int METHOD_XZ = 95;
    private static final int METHOD_JPEG = ZipxNativeCodecs.METHOD_JPEG;
    private static final int METHOD_WAVPACK = ZipxNativeCodecs.METHOD_WAVPACK;
    private static final int METHOD_PPMD = 98;
    private static final int METHOD_WINZIP_AES = 99;
    private static final int WINZIP_AES_EXTRA_ID = 0x9901;
    private static final int AES_AUTHENTICATION_LENGTH = 10;
    private static final int ZIP_LZMA_PROPERTIES_SIZE = 5;
    private static final int MAX_LZMA_DECODER_MEMORY_KIB = 64 * 1024;

    private ZipxAesArchiveReader() {}

    /** True only when the complete archive can use this reader without mixing backends. */
    static boolean canExtractArchive(@NonNull File archive) {
        try (org.apache.commons.compress.archivers.zip.ZipFile zip = open(archive)) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            boolean sawExtendedAes = false;
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;
                AesMetadata aes = parseAesMetadata(entry);
                if (aes != null) {
                    if (!isSupportedAesMethod(aes.compressionMethod)) return false;
                    sawExtendedAes |= isExtendedMethod(aes.compressionMethod);
                    continue;
                }
                if (entry.getGeneralPurposeBit().usesEncryption()) return false;
                if (entry.getMethod() == METHOD_LZMA) continue;
                if (!isRuntimeSupportedPlainMethod(entry.getMethod())
                        || !zip.canReadEntryData(entry)) {
                    return false;
                }
            }
            return sawExtendedAes;
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
        if (password == null || password.length == 0) {
            throw new ArchiveSupport.PasswordRequiredException();
        }
        try (org.apache.commons.compress.archivers.zip.ZipFile zip = open(archive)) {
            List<EntryDescriptor> entries = readEntries(zip);
            if (progress != null) progress.setTotalBytes(sumDecodedSizes(entries));
            boolean sawEntry = false;
            for (EntryDescriptor descriptor : entries) {
                ZipArchiveEntry entry = descriptor.entry;
                if (progress != null && !progress.checkpoint()) return false;
                File out = ArchiveSupport.resolveArchiveEntryOutput(targetDir, descriptor.displayName);
                if (out == null) return false;
                sawEntry = true;
                if (entry.isDirectory() || descriptor.displayName.replace('\\', '/').endsWith("/")) {
                    if (entryProgress != null) entryProgress.onDirectory(descriptor.displayName);
                    if (!out.exists() && !out.mkdirs()) return false;
                    continue;
                }
                if (entryProgress != null) entryProgress.onFile(descriptor.displayName);
                else if (progress != null) progress.setDetail(descriptor.displayName);
                if (!extractDescriptor(zip, descriptor, out, password, progress)) return false;
            }
            return sawEntry;
        } catch (LinkageError missingCodec) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                    "ZIPX compression codec is not available", missingCodec);
        }
    }

    /**
     * Returns null when the requested entry does not need this supplemental path.
     * A non-null value is the final extraction result for a covered AES ZIPX entry.
     */
    @Nullable
    static Boolean tryExtractSingleEntry(@NonNull File archive,
                                         @NonNull String entryPath,
                                         @NonNull File outFile,
                                         @Nullable char[] password) throws IOException {
        try (org.apache.commons.compress.archivers.zip.ZipFile zip = open(archive)) {
            for (EntryDescriptor descriptor : readEntries(zip)) {
                if (descriptor.entry.isDirectory()) continue;
                String path = ArchiveSupport.sanitizeEntryPathForList(descriptor.displayName);
                String commonsPath = ArchiveSupport.sanitizeEntryPathForList(descriptor.entry.getName());
                if (!entryPath.equals(path) && !entryPath.equals(commonsPath)) continue;
                AesMetadata aes = descriptor.aes;
                if (aes == null) return null;
                if (!isSupportedAesMethod(aes.compressionMethod)) {
                    if (isLibarchiveAesMethod(aes.compressionMethod)) return null;
                    throw unsupportedMethod(aes.compressionMethod);
                }
                if (!isExtendedMethod(aes.compressionMethod)) return null;
                if (password == null || password.length == 0) {
                    throw new ArchiveSupport.PasswordRequiredException();
                }
                return extractDescriptor(zip, descriptor, outFile, password, null);
            }
            return null;
        } catch (LinkageError missingCodec) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                    "ZIPX compression codec is not available", missingCodec);
        }
    }

    @NonNull
    private static org.apache.commons.compress.archivers.zip.ZipFile open(@NonNull File archive)
            throws IOException {
        return org.apache.commons.compress.archivers.zip.ZipFile.builder()
                .setFile(archive)
                .get();
    }

    @NonNull
    private static List<EntryDescriptor> readEntries(
            @NonNull org.apache.commons.compress.archivers.zip.ZipFile zip) {
        ArrayList<ZipArchiveEntry> rawEntries = new ArrayList<>();
        ArchiveFilenameDecoder.NameCorpus corpus = new ArchiveFilenameDecoder.NameCorpus();
        Enumeration<ZipArchiveEntry> enumeration = zip.getEntries();
        while (enumeration.hasMoreElements()) {
            ZipArchiveEntry entry = enumeration.nextElement();
            if (entry == null) continue;
            rawEntries.add(entry);
            byte[] rawName = entry.getRawName();
            if (rawName != null && !entry.getGeneralPurposeBit().usesUTF8ForNames()) {
                corpus.observe(rawName);
            }
        }
        ArrayList<EntryDescriptor> result = new ArrayList<>(rawEntries.size());
        for (ZipArchiveEntry entry : rawEntries) {
            byte[] rawName = entry.getRawName();
            String displayName = rawName == null
                    ? entry.getName()
                    : ArchiveFilenameDecoder.decodeZipName(
                            rawName, entry.getGeneralPurposeBit().usesUTF8ForNames(), corpus);
            result.add(new EntryDescriptor(entry, displayName, parseAesMetadata(entry)));
        }
        return result;
    }

    private static boolean extractDescriptor(
            @NonNull org.apache.commons.compress.archivers.zip.ZipFile zip,
            @NonNull EntryDescriptor descriptor,
            @NonNull File outFile,
            @NonNull char[] password,
            @Nullable FileOperationProgress progress) throws IOException {
        ZipArchiveEntry entry = descriptor.entry;
        if (descriptor.aes == null) {
            if (entry.getMethod() == METHOD_LZMA) {
                InputStream raw = zip.getRawInputStream(entry);
                if (raw == null) throw new IOException("ZIPX raw entry stream is unavailable");
                try (InputStream rawEntry = raw;
                     InputStream decoded = wrapCompressedStream(
                             rawEntry, METHOD_LZMA, entry.getSize(), usesLzmaEndMarker(entry))) {
                    IntegrityInputStream checked = new IntegrityInputStream(decoded);
                    boolean written = ArchiveSupport.writeArchiveEntryStream(
                            checked, outFile, progress);
                    if (!written) return false;
                    verifyPlainEntry(entry, checked);
                    return true;
                }
            }
            if (entry.getGeneralPurposeBit().usesEncryption() || !zip.canReadEntryData(entry)) {
                throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                        "ZIPX entry uses an unsupported compression/encryption combination");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return ArchiveSupport.writeArchiveEntryStream(in, outFile, progress);
            }
        }

        AesMetadata aes = descriptor.aes;
        if (!isSupportedAesMethod(aes.compressionMethod)) {
            throw unsupportedMethod(aes.compressionMethod);
        }
        InputStream raw = zip.getRawInputStream(entry);
        if (raw == null) throw new IOException("ZIPX raw entry stream is unavailable");
        if (ZipxNativeCodecs.supports(aes.compressionMethod)) {
            return extractNativeAesEntry(raw, entry, aes, outFile, password, progress);
        }
        try (WinZipAesInputStream decrypted = new WinZipAesInputStream(
                     raw, entry.getCompressedSize(), aes, password);
             InputStream decoded = wrapCompressedStream(
                     decrypted, aes.compressionMethod, entry.getSize(), usesLzmaEndMarker(entry))) {
            IntegrityInputStream checked = new IntegrityInputStream(decoded);
            boolean written = ArchiveSupport.writeArchiveEntryStream(checked, outFile, progress);
            if (!written) return false;
            decrypted.verifyAuthentication();
            verifyDecodedEntry(entry, aes, checked);
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
        long outputLimit = ArchiveSupport.MAX_EXTRACTION_TOTAL_BYTES;
        if (entry.getSize() >= 0L) outputLimit = Math.min(outputLimit, entry.getSize());
        try (WinZipAesInputStream decrypted = new WinZipAesInputStream(
                     raw, entry.getCompressedSize(), aes, password);
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
        switch (method) {
            case METHOD_STORED:
                return decrypted;
            case METHOD_DEFLATE:
                // Raw ZIP deflate streams may require one trailing zero byte.
                return new InflaterInputStream(
                        new SequenceInputStream(
                                decrypted, new ByteArrayInputStream(new byte[] {0})),
                        new Inflater(true));
            case METHOD_DEFLATE64:
                return new Deflate64CompressorInputStream(decrypted);
            case METHOD_BZIP2:
                return new BZip2CompressorInputStream(decrypted, false);
            case METHOD_LZMA:
                return wrapZipLzmaStream(decrypted, uncompressedSize, lzmaEndMarker);
            case METHOD_XZ:
                return new XZCompressorInputStream(decrypted, false);
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
        int dictionarySize = (properties[1] & 0xff)
                | ((properties[2] & 0xff) << 8)
                | ((properties[3] & 0xff) << 16)
                | ((properties[4] & 0xff) << 24);
        int memoryKiB = LZMAInputStream.getMemoryUsage(dictionarySize, properties[0]);
        if (memoryKiB > MAX_LZMA_DECODER_MEMORY_KIB) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                    "ZIPX LZMA dictionary exceeds the decoder memory safety limit");
        }
        return new LZMAInputStream(
                input, endMarker ? -1L : uncompressedSize, properties[0], dictionarySize);
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
        long expectedSize = entry.getSize();
        if (expectedSize >= 0L && expectedSize != checked.count) {
            throw new IOException("Corrupt ZIPX decoded size mismatch");
        }
        long expectedCrc = entry.getCrc();
        if (expectedCrc >= 0L && expectedCrc != checked.crc.getValue()) {
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

    private static boolean isExtendedMethod(int method) {
        return method == METHOD_DEFLATE64 || method == METHOD_BZIP2
                || method == METHOD_LZMA || method == METHOD_XZ
                || method == METHOD_JPEG || method == METHOD_WAVPACK;
    }

    private static boolean isSupportedAesMethod(int method) {
        return method == METHOD_STORED || method == METHOD_DEFLATE || isExtendedMethod(method);
    }

    private static boolean isLibarchiveAesMethod(int method) {
        return method == METHOD_BZIP2 || method == METHOD_LZMA
                || method == METHOD_PPMD || method == METHOD_XZ
                || method == METHOD_ZSTANDARD;
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

    private static final class EntryDescriptor {
        @NonNull final ZipArchiveEntry entry;
        @NonNull final String displayName;
        @Nullable final AesMetadata aes;

        EntryDescriptor(@NonNull ZipArchiveEntry entry,
                        @NonNull String displayName,
                        @Nullable AesMetadata aes) {
            this.entry = entry;
            this.displayName = displayName;
            this.aes = aes;
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
        private final byte[] decryptedBlock = new byte[16];
        private int blockOffset;
        private int blockLength;
        private boolean authenticated;
        private boolean closed;

        WinZipAesInputStream(@NonNull InputStream raw,
                            long storedSize,
                            @NonNull AesMetadata aes,
                            @NonNull char[] password) throws IOException {
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
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            if (closed) throw new IOException("ZIPX AES stream is closed");
            if (buffer == null) throw new NullPointerException("buffer");
            if (offset < 0 || length < 0 || length > buffer.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) return 0;
            if (blockOffset >= blockLength && !readNextBlock()) {
                verifyAuthentication();
                return -1;
            }
            int copied = Math.min(length, blockLength - blockOffset);
            System.arraycopy(decryptedBlock, blockOffset, buffer, offset, copied);
            blockOffset += copied;
            return copied;
        }

        private boolean readNextBlock() throws IOException {
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
            raw.close();
        }
    }

    private static final class IntegrityInputStream extends InputStream {
        @NonNull private final InputStream in;
        @NonNull final CRC32 crc = new CRC32();
        long count;

        IntegrityInputStream(@NonNull InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0) {
                crc.update(value);
                count++;
            }
            return value;
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            int read = in.read(buffer, offset, length);
            if (read > 0) {
                crc.update(buffer, offset, read);
                count += read;
            }
            return read;
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
            int read = in.read(buffer, offset + done, length - done);
            if (read < 0) throw new IOException("Truncated ZIPX AES entry");
            if (read == 0) continue;
            done += read;
        }
    }
}
