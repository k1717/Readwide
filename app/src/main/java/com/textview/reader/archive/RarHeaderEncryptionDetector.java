package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Detects RAR archives whose headers are encrypted before the normal entry parser can see
 * file blocks.
 *
 * <p>This helper only detects the encrypted-header mode. RAR3/RAR4 header block decryption lives in
 * {@link Rar4HeaderEncryptedArchiveRewriter}; RAR5 header decryption still belongs to libarchive.
 * Keeping detection separate prevents password-required checks from depending on successful entry
 * parsing.</p>
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

    static void throwIfHeaderEncryptedNeedsUnsupportedPath(@NonNull File archive,
                                                           @Nullable char[] password,
                                                           @Nullable IOException backendFailure) throws IOException {
        if (!hasEncryptedHeaders(archive)) return;
        if (password == null || password.length == 0) {
            throw new ArchiveSupport.PasswordRequiredException();
        }
        String message = "RAR header-encrypted archive could not be handled by the first-party header rewriter or libarchive";
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
            readUInt16LE(raf); // CRC.
            int type = raf.readUnsignedByte();
            int flags = readUInt16LE(raf);
            int headerSize = readUInt16LE(raf);
            if (headerSize < 7 || headerStart + headerSize > raf.length()) return false;
            if (type == RAR4_HEADER_MAIN && (flags & RAR4_MAIN_PASSWORD) != 0) return true;
            long dataSize = 0L;
            if ((flags & RAR4_LONG_BLOCK) != 0) {
                if (headerSize - 7 < 4) return false;
                dataSize = readUInt32LE(raf);
                raf.seek(headerStart + headerSize);
            } else {
                raf.seek(headerStart + headerSize);
            }
            long next = headerStart + headerSize + dataSize;
            if (next < headerStart || next > raf.length()) return false;
            raf.seek(next);
            if (type == RAR4_HEADER_END) return false;
        }
        return false;
    }

    private static boolean hasRar5HeaderEncryption(@NonNull RandomAccessFile raf) throws IOException {
        while (raf.getFilePointer() < raf.length()) {
            long headerStart = raf.getFilePointer();
            if (raf.length() - headerStart < 5) return false;
            skipUInt32LE(raf); // Header CRC.
            VInt headerSize = readVInt(raf);
            if (headerSize.value < 0 || headerSize.value > 2L * 1024L * 1024L) return false;
            if (headerSize.value > raf.length() - raf.getFilePointer()) return false;
            byte[] headerData = new byte[(int) headerSize.value];
            raf.readFully(headerData);
            ByteCursor cursor = new ByteCursor(headerData);
            int type = (int) cursor.readVInt();
            long flags = cursor.readVInt();
            if (type == RAR5_HEADER_ENCRYPTION) return true;
            if ((flags & RAR5_HEADER_FLAG_EXTRA) != 0) cursor.readVInt();
            long dataSize = (flags & RAR5_HEADER_FLAG_DATA) != 0 ? cursor.readVInt() : 0L;
            long next = raf.getFilePointer() + dataSize;
            if (next < raf.getFilePointer() || next > raf.length()) return false;
            raf.seek(next);
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

    private static void skipUInt32LE(@NonNull RandomAccessFile in) throws IOException {
        in.skipBytes(4);
    }

    @NonNull
    private static VInt readVInt(@NonNull RandomAccessFile in) throws IOException {
        long value = 0L;
        int shift = 0;
        byte[] encoded = new byte[10];
        int count = 0;
        while (count < encoded.length) {
            int b = in.readUnsignedByte();
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

    private static final class VInt {
        final long value;
        @SuppressWarnings("unused") final byte[] encoded;

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
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return value;
                shift += 7;
            }
            throw new IOException("Invalid RAR vint");
        }
    }
}
