package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.zip.CRC32;

/**
 * Detects encrypted RAR headers before entry parsing.
 *
 * <p>RAR3/RAR4 headers are decrypted by {@link Rar4HeaderEncryptedArchiveRewriter};
 * RAR5 headers are decrypted inside {@code RarArchiveReader.readRar5Entries}.
 * This probe does not verify passwords or decrypt headers.</p>
 */
final class RarHeaderEncryptionDetector {
    private static final int RAR4_HEADER_MAIN = 0x73;
    private static final int RAR4_HEADER_END = 0x7b;
    private static final int RAR4_MAIN_PASSWORD = 0x0080;
    private static final int RAR4_LONG_BLOCK = 0x8000;

    private static final int RAR5_HEADER_ENCRYPTION = 4;
    private static final int RAR5_HEADER_FILE = 2;
    private static final int RAR5_HEADER_END = 5;
    private static final long RAR5_HEADER_FLAG_EXTRA = 0x0001L;
    private static final long RAR5_HEADER_FLAG_DATA = 0x0002L;

    private RarHeaderEncryptionDetector() {}

    static boolean hasEncryptedHeaders(@NonNull File archive) throws IOException {
        for (File volume : RarArchiveLocator.collectVolumes(archive)) {
            try (RandomAccessFile raf = new RandomAccessFile(volume, "r")) {
                RarArchiveLocator.Signature signature = RarArchiveLocator.findSignature(raf);
                if (signature == null) continue;
                raf.seek(signature.offset + RarArchiveLocator.signatureLength(signature.version));
                if (signature.version == 5) {
                    return hasRar5HeaderEncryption(raf);
                }
                return hasRar4HeaderEncryption(raf);
            }
        }
        return false;
    }

    /**
     * @return 4 or 5 for the first volume whose headers are encrypted, or 0 if none is.
     */
    static int headerEncryptedRarVersion(@NonNull File archive) throws IOException {
        for (File volume : RarArchiveLocator.collectVolumes(archive)) {
            try (RandomAccessFile raf = new RandomAccessFile(volume, "r")) {
                RarArchiveLocator.Signature signature = RarArchiveLocator.findSignature(raf);
                if (signature == null) continue;
                raf.seek(signature.offset + RarArchiveLocator.signatureLength(signature.version));
                if (signature.version == 5) {
                    if (hasRar5HeaderEncryption(raf)) return 5;
                } else if (hasRar4HeaderEncryption(raf)) {
                    return 4;
                }
            }
        }
        return 0;
    }

    static void throwIfHeaderEncryptedNeedsUnsupportedPath(@NonNull File archive,
                                                           @Nullable char[] password,
                                                           @Nullable IOException backendFailure) throws IOException {
        int version = headerEncryptedRarVersion(archive);
        if (version == 0) return;
        if (password == null || password.length == 0) {
            throw new ArchiveSupport.PasswordRequiredException();
        }
        String message = version == 5
                ? "RAR5 header-encrypted (-hp) archive could not be read: the first-party header "
                    + "parser could not read the headers, and the "
                    + "bundled libarchive backend cannot decrypt RAR5 headers at all"
                : "RAR4 header-encrypted (-hp) archive could not be handled by the first-party header rewriter or libarchive";
        if (backendFailure != null && backendFailure.getMessage() != null && !backendFailure.getMessage().isEmpty()) {
            message += " (libarchive: " + backendFailure.getMessage() + ")";
        } else if (!RarLibarchiveFallback.isAvailable()) {
            message += " (libarchive backend unavailable: " + LibarchiveNativeBridge.backendStatus() + ")";
        }
        throw new RarArchiveReader.UnsupportedRarFeatureException(message);
    }

    private static boolean hasRar4HeaderEncryption(@NonNull RandomAccessFile raf) throws IOException {
        while (raf.getFilePointer() < raf.length()) {
            long headerStart = raf.getFilePointer();
            if (raf.length() - headerStart < 7) return false;
            int headerCrc = readUInt16LE(raf);
            int type = raf.readUnsignedByte();
            int flags = readUInt16LE(raf);
            int headerSize = readUInt16LE(raf);
            if (headerSize < 7 || headerStart + headerSize > raf.length()) return false;
            if (type == RAR4_HEADER_MAIN && (flags & RAR4_MAIN_PASSWORD) != 0) {
                byte[] body = new byte[headerSize - 7];
                raf.readFully(body);
                try {
                    Rar4HeaderChecksum.verify(headerCrc, type, flags, body);
                } catch (IOException invalidHeader) {
                    return false;
                }
                return true;
            }
            long dataSize = 0L;
            if (type == 0x74 || type == 0x7a) {
                int fixedSize = (flags & 0x0100) != 0 ? 33 : 25;
                if (headerSize - 7 < fixedSize) return false;
                byte[] fixed = new byte[fixedSize];
                raf.readFully(fixed);
                try {
                    dataSize = Rar4BlockSize.packedSize(flags, fixed, 0);
                } catch (IOException invalidSize) {
                    return false;
                }
            } else if ((flags & RAR4_LONG_BLOCK) != 0) {
                if (headerSize - 7 < 4) return false;
                dataSize = readUInt32LE(raf);
            }
            long dataOffset = headerStart + headerSize;
            if (dataSize < 0L || dataSize > raf.length() - dataOffset) return false;
            raf.seek(dataOffset + dataSize);
            if (type == RAR4_HEADER_END) return false;
        }
        return false;
    }

    private static boolean hasRar5HeaderEncryption(@NonNull RandomAccessFile raf) throws IOException {
        while (raf.getFilePointer() < raf.length()) {
            long headerStart = raf.getFilePointer();
            if (raf.length() - headerStart < 5) return false;
            long headerCrc = readUInt32LE(raf);
            VInt headerSize = readVInt(raf);
            if (headerSize.value < 0 || headerSize.value > 2L * 1024L * 1024L) return false;
            if (headerSize.value > raf.length() - raf.getFilePointer()) return false;
            byte[] headerData = new byte[(int) headerSize.value];
            raf.readFully(headerData);
            CRC32 crc = new CRC32();
            crc.update(headerSize.encoded);
            crc.update(headerData);
            if (crc.getValue() != headerCrc) return false;
            ByteCursor cursor = new ByteCursor(headerData);
            long type = cursor.readVInt();
            long flags = cursor.readVInt();
            long extraSize = (flags & RAR5_HEADER_FLAG_EXTRA) != 0 ? cursor.readVInt() : 0L;
            long dataSize = (flags & RAR5_HEADER_FLAG_DATA) != 0 ? cursor.readVInt() : 0L;
            if (extraSize < 0L || extraSize > headerData.length - cursor.position) return false;
            long dataOffset = raf.getFilePointer();
            if (dataSize < 0L || dataSize > raf.length() - dataOffset) return false;
            if (type == RAR5_HEADER_ENCRYPTION) return true;
            raf.seek(dataOffset + dataSize);
            if (type == RAR5_HEADER_FILE || type == RAR5_HEADER_END) return false;
        }
        return false;
    }

    private static int readUInt16LE(@NonNull RandomAccessFile in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        return b0 | (b1 << 8);
    }

    private static long readUInt32LE(@NonNull RandomAccessFile in) throws IOException {
        long b0 = in.readUnsignedByte();
        long b1 = in.readUnsignedByte();
        long b2 = in.readUnsignedByte();
        long b3 = in.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    @NonNull
    private static VInt readVInt(@NonNull RandomAccessFile in) throws IOException {
        long value = 0L;
        int shift = 0;
        byte[] encoded = new byte[10];
        int count = 0;
        while (count < encoded.length) {
            int b = in.readUnsignedByte();
            checkVIntByte(b, count);
            encoded[count++] = (byte) b;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                byte[] exact = new byte[count];
                System.arraycopy(encoded, 0, exact, 0, count);
                return new VInt(value, exact);
            }
            shift += 7;
        }
        throw new IOException("Invalid RAR vint");
    }

    private static void checkVIntByte(int value, int index) throws IOException {
        // Padded encodings are valid; byte ten has only one data bit left.
        if (index == 9 && (value & 0xfe) != 0) {
            throw new IOException((value & 0x80) != 0 ? "RAR vint is too long" : "RAR vint overflow");
        }
    }

    private static final class VInt {
        final long value;
        final byte[] encoded;

        VInt(long value, @NonNull byte[] encoded) {
            this.value = value;
            this.encoded = encoded;
        }
    }

    private static final class ByteCursor {
        private final byte[] data;
        private int position;

        ByteCursor(@NonNull byte[] data) {
            this.data = data;
        }

        long readVInt() throws IOException {
            long value = 0L;
            int shift = 0;
            for (int i = 0; i < 10; i++) {
                if (position >= data.length) throw new IOException("Unexpected EOF in RAR header");
                int b = data[position++] & 0xff;
                checkVIntByte(b, i);
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return value;
                shift += 7;
            }
            throw new IOException("Invalid RAR vint");
        }
    }
}
