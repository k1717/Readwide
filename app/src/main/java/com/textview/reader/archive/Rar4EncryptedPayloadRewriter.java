package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Builds a temporary visible-header RAR4 stream with file-data encryption removed.
 *
 * <p>This is intentionally narrow. It does not implement the RAR3/RAR4 compressed
 * unpacker itself. Instead it handles the part libarchive commonly lacks for older
 * visible-header encrypted RAR files: decrypt the per-file AES-CBC payload, clear the
 * RAR4 password/salt flags, and hand the resulting ordinary RAR4 stream back to the
 * normal decompressor backend.</p>
 */
final class Rar4EncryptedPayloadRewriter {
    private static final byte[] RAR4_SIGNATURE = new byte[] {
            0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00
    };

    private static final int MAX_SFX_SCAN = 1024 * 1024;
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private static final int RAR4_HEADER_FILE = 0x74;
    private static final int RAR4_HEADER_END = 0x7b;
    private static final int RAR4_LONG_BLOCK = 0x8000;
    private static final int RAR4_FILE_SPLIT_BEFORE = 0x0001;
    private static final int RAR4_FILE_SPLIT_AFTER = 0x0002;
    private static final int RAR4_FILE_PASSWORD = 0x0004;
    private static final int RAR4_FILE_SOLID = 0x0010;
    private static final int RAR4_FILE_LARGE = 0x0100;
    private static final int RAR4_FILE_SALT = 0x0400;
    private static final int RAR4_METHOD_STORE = 0x30;

    private Rar4EncryptedPayloadRewriter() {}

    @NonNull
    static File buildDecryptedCopy(@NonNull File archive,
                                   @NonNull char[] password,
                                   @Nullable FileOperationProgress progress) throws IOException {
        if (password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
        File temp = File.createTempFile("textview-rar4-decrypted-", ".rar");
        boolean success = false;
        try (RandomAccessFile in = new RandomAccessFile(archive, "r");
             FileOutputStream out = new FileOutputStream(temp)) {
            long signatureOffset = findRar4SignatureOffset(in);
            if (signatureOffset < 0) throw new IOException("RAR4 signature not found");
            in.seek(signatureOffset + RAR4_SIGNATURE.length);
            out.write(RAR4_SIGNATURE);

            boolean sawEncryptedFile = false;
            byte[] copyBuffer = new byte[COPY_BUFFER_SIZE];
            while (in.getFilePointer() < in.length()) {
                if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
                long headerStart = in.getFilePointer();
                if (in.length() - headerStart < 7) break;
                int ignoredCrc = readUInt16LE(in);
                int type = in.readUnsignedByte();
                int flags = readUInt16LE(in);
                int headerSize = readUInt16LE(in);
                if (headerSize < 7 || headerStart + headerSize > in.length()) {
                    throw new IOException("Invalid RAR4 header size");
                }
                byte[] headerData = new byte[headerSize - 7];
                in.readFully(headerData);

                long dataSize = dataSizeForHeader(type, flags, headerData);
                if (dataSize < 0 || dataSize > in.length() - in.getFilePointer()) {
                    throw new IOException("Invalid RAR4 data size");
                }

                if (type == RAR4_HEADER_FILE && (flags & RAR4_FILE_PASSWORD) != 0) {
                    RewrittenFileBlock rewritten = prepareEncryptedFileBlock(flags, headerData, dataSize);
                    writeRar4Header(out, type, rewritten.flags, rewritten.headerData);
                    writeDecryptedPayload(in, dataSize, password, rewritten.block, out, progress);
                    sawEncryptedFile = true;
                } else {
                    writeOriginalHeader(out, ignoredCrc, type, flags, headerData);
                    copyBytes(in, out, dataSize, copyBuffer);
                }
                if (type == RAR4_HEADER_END) break;
            }
            if (!sawEncryptedFile) throw new IOException("RAR4 archive has no visible encrypted file payload");
            success = true;
            return temp;
        } finally {
            if (!success) safeDelete(temp);
        }
    }

    @NonNull
    private static RewrittenFileBlock prepareEncryptedFileBlock(int flags,
                                                                @NonNull byte[] headerData,
                                                                long dataSize) throws IOException {
        FileBlock block = parseFileBlock(flags, headerData);
        if ((flags & RAR4_FILE_SOLID) != 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 solid encrypted payload still requires the solid-state decoder");
        }
        if ((flags & RAR4_FILE_SPLIT_BEFORE) != 0 || (flags & RAR4_FILE_SPLIT_AFTER) != 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 encrypted split payload is not supported yet");
        }
        if ((flags & RAR4_FILE_SALT) == 0 || block.saltOffset < 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 encrypted file data has no visible salt");
        }
        if ((dataSize % 16L) != 0L) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 encrypted payload is not AES block aligned");
        }

        long newPackedSize = block.method == RAR4_METHOD_STORE ? block.unpackedSize : dataSize;
        if (newPackedSize < 0L) throw new IOException("Invalid RAR4 decrypted payload size");

        byte[] rewrittenHeaderData = removeRange(headerData, block.saltOffset, 8);
        writePackedSize(rewrittenHeaderData, newPackedSize, (flags & RAR4_FILE_LARGE) != 0);
        int rewrittenFlags = flags & ~RAR4_FILE_PASSWORD & ~RAR4_FILE_SALT;
        return new RewrittenFileBlock(rewrittenFlags, rewrittenHeaderData, block);
    }

    private static void writeDecryptedPayload(@NonNull RandomAccessFile in,
                                              long dataSize,
                                              @NonNull char[] password,
                                              @NonNull FileBlock block,
                                              @NonNull FileOutputStream out,
                                              @Nullable FileOperationProgress progress) throws IOException {
        javax.crypto.Cipher cipher = Rar3Crypto.createAesCbcDecryptCipher(password, block.salt);
        long plaintextLimit = block.method == RAR4_METHOD_STORE ? block.unpackedSize : -1L;
        long remaining = RarCryptoStreams.decryptToStream(
                in,
                dataSize,
                cipher,
                out,
                plaintextLimit,
                "RAR3/RAR4 AES decrypt failed",
                progress,
                false);
        if (remaining != 0L) throw new IOException("Invalid RAR4 encrypted stored size");
    }

    @NonNull
    private static FileBlock parseFileBlock(int flags, @NonNull byte[] headerData) throws IOException {
        if (headerData.length < 25) throw new IOException("Invalid RAR4 file header");
        long packedSize = uint32(headerData, 0);
        long unpackedSize = uint32(headerData, 4);
        int method = headerData[18] & 0xff;
        int nameSize = uint16(headerData, 19);
        int nameOffset = 25;
        if ((flags & RAR4_FILE_LARGE) != 0) {
            if (headerData.length < 33) throw new IOException("Invalid RAR4 large file header");
            packedSize |= uint32(headerData, 25) << 32;
            unpackedSize |= uint32(headerData, 29) << 32;
            nameOffset = 33;
        }
        if (nameSize < 0 || nameOffset + nameSize > headerData.length) {
            throw new IOException("Invalid RAR4 file name size");
        }
        int saltOffset = -1;
        byte[] salt = new byte[0];
        if ((flags & RAR4_FILE_SALT) != 0) {
            saltOffset = nameOffset + nameSize;
            if (saltOffset + 8 > headerData.length) throw new IOException("Invalid RAR4 salt position");
            salt = Arrays.copyOfRange(headerData, saltOffset, saltOffset + 8);
        }
        return new FileBlock(packedSize, unpackedSize, method, saltOffset, salt);
    }

    private static long dataSizeForHeader(int type, int flags, @NonNull byte[] headerData) throws IOException {
        if (type == RAR4_HEADER_FILE) {
            FileBlock block = parseFileBlock(flags, headerData);
            return block.packedSize;
        }
        if ((flags & RAR4_LONG_BLOCK) != 0) {
            if (headerData.length < 4) throw new IOException("Invalid RAR4 long block");
            return uint32(headerData, 0);
        }
        return 0L;
    }

    private static void writePackedSize(@NonNull byte[] headerData, long packedSize, boolean large) {
        putUInt32(headerData, 0, packedSize & 0xffff_ffffL);
        if (large) putUInt32(headerData, 25, (packedSize >>> 32) & 0xffff_ffffL);
    }

    @NonNull
    private static byte[] removeRange(@NonNull byte[] input, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > input.length) throw new IOException("Invalid RAR4 header rewrite range");
        byte[] out = new byte[input.length - length];
        System.arraycopy(input, 0, out, 0, offset);
        System.arraycopy(input, offset + length, out, offset, input.length - offset - length);
        return out;
    }

    private static void writeOriginalHeader(@NonNull FileOutputStream out,
                                            int crc,
                                            int type,
                                            int flags,
                                            @NonNull byte[] headerData) throws IOException {
        writeUInt16LE(out, crc);
        out.write(type & 0xff);
        writeUInt16LE(out, flags);
        writeUInt16LE(out, 7 + headerData.length);
        out.write(headerData);
    }

    private static void writeRar4Header(@NonNull FileOutputStream out,
                                        int type,
                                        int flags,
                                        @NonNull byte[] headerData) throws IOException {
        int size = 7 + headerData.length;
        CRC32 crc32 = new CRC32();
        crc32.update(type & 0xff);
        crc32.update(flags & 0xff);
        crc32.update((flags >>> 8) & 0xff);
        crc32.update(size & 0xff);
        crc32.update((size >>> 8) & 0xff);
        crc32.update(headerData, 0, headerData.length);
        writeUInt16LE(out, (int) crc32.getValue() & 0xffff);
        out.write(type & 0xff);
        writeUInt16LE(out, flags);
        writeUInt16LE(out, size);
        out.write(headerData);
    }

    private static void copyBytes(@NonNull RandomAccessFile in,
                                  @NonNull FileOutputStream out,
                                  long count,
                                  @NonNull byte[] buffer) throws IOException {
        long remaining = count;
        while (remaining > 0L) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Unexpected end of RAR4 data");
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static long findRar4SignatureOffset(@NonNull RandomAccessFile in) throws IOException {
        long original = in.getFilePointer();
        try {
            long length = Math.max(0L, in.length());
            int scanLimit = (int) Math.min(length, MAX_SFX_SCAN + RAR4_SIGNATURE.length);
            byte[] data = new byte[scanLimit];
            in.seek(0L);
            int read = 0;
            while (read < data.length) {
                int count = in.read(data, read, data.length - read);
                if (count < 0) break;
                read += count;
            }
            outer:
            for (int i = 0; i <= read - RAR4_SIGNATURE.length; i++) {
                for (int j = 0; j < RAR4_SIGNATURE.length; j++) {
                    if (data[i + j] != RAR4_SIGNATURE[j]) continue outer;
                }
                return i;
            }
            return -1L;
        } finally {
            in.seek(original);
        }
    }

    private static int readUInt16LE(@NonNull RandomAccessFile in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        return b0 | (b1 << 8);
    }

    private static int uint16(@NonNull byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long uint32(@NonNull byte[] data, int offset) {
        return ((long) data[offset] & 0xff)
                | (((long) data[offset + 1] & 0xff) << 8)
                | (((long) data[offset + 2] & 0xff) << 16)
                | (((long) data[offset + 3] & 0xff) << 24);
    }

    private static void putUInt32(@NonNull byte[] data, int offset, long value) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >>> 8) & 0xff);
        data[offset + 2] = (byte) ((value >>> 16) & 0xff);
        data[offset + 3] = (byte) ((value >>> 24) & 0xff);
    }

    private static void writeUInt16LE(@NonNull FileOutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void safeDelete(@Nullable File file) {
        if (file != null && file.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } catch (SecurityException ignored) {
            }
        }
    }

    private static final class FileBlock {
        final long packedSize;
        final long unpackedSize;
        final int method;
        final int saltOffset;
        final byte[] salt;

        FileBlock(long packedSize, long unpackedSize, int method, int saltOffset, @NonNull byte[] salt) {
            this.packedSize = packedSize;
            this.unpackedSize = unpackedSize;
            this.method = method;
            this.saltOffset = saltOffset;
            this.salt = salt;
        }
    }

    private static final class RewrittenFileBlock {
        final int flags;
        final byte[] headerData;
        final FileBlock block;

        RewrittenFileBlock(int flags, @NonNull byte[] headerData, @NonNull FileBlock block) {
            this.flags = flags;
            this.headerData = headerData;
            this.block = block;
        }
    }
}
